package server.route;

import client.Client;
import item.BeaconCluster;
import item.Link;
import item.Uav;
import server.controller.ServerController;
import server.util.ICCGSolver;
import server.util.LogManager;
import server.util.MathUtils;
import server.util.ResultOutputManager;
import server.redis.ClientTimeManager;
import server.redis.PathWaitingManager;
import server.redis.UAVJob;
import server.scheduler.FlightScheduler;
import server.uav.MemoryPathWaitingManager;
import server.route.SolverFailedException;
import controller.BoundaryController;

import java.io.IOException;
import java.util.Queue;

/**
 * 段階制御型圧力誘導EPS (Step-Controlled Pressure-Guided EPS)
 * Phase 4: 最大フロー超過分は経路待ちキューで管理し、
 * 第一ホップ通過時に経路をコピーして飛行開始する
 *
 * 適応的フロー制御方式でEPSを適用し、予防的不安定性検知によりフローを調整する
 */
public class StepControlledPressureGuidedEPSRouteSearcher extends ExtendedPhysarumSolverRouteSearcher {

    // 定数
    private static final double INIT_THICKNESS = 0.5; // 初期チューブ厚
    private static final double INIT_LENGTH = 1.0; // 初期チューブ長
    private static final int MAX_ITERATIONS = 1000; // 最大イテレーション数
    private static final int REQUIRED_STABLE_ITERATIONS = 150; // 収束判定用の連続安定回数
    private static final int STABILIZATION_ITERATIONS = 500; // 異常検知後の安定化イテレーション数


    // ソースノード圧力専用閾値（実データ分析：正常時51~54、異常時87以上）
    private static final double SOURCE_PRESSURE_WARNING = 70.0; // 正常時の約1.3倍で早期警告
    private static final double SOURCE_PRESSURE_EMERGENCY = 100.0; // フロー半分に減少する閾値

    // ソースノード圧力変化率の段階的閾値
    private static final double SOURCE_PRESSURE_CHANGE_LEVEL1 = 0.07;  // 7%: 1UAV減少
    private static final double SOURCE_PRESSURE_CHANGE_LEVEL2 = 0.12;  // 12%: 3UAV減少
    private static final double SOURCE_PRESSURE_CHANGE_LEVEL3 = 0.15;  // 15%: 5UAV減少

    // フロー減少（UAV整数値対応）
    private static final double EMERGENCY_FLOW_REDUCTION_UAV = 4.0; // 4UAV減少（ソルバー失敗時に使用）
    private static final double MINIMUM_FLOW_RATIO = 0.1; // 最小安全フロー（要求フローの10%）

    // 現在のフロー値と制御状態
    private double currentFlow;
    private double requestedFlow;
    private int stableIterationCount = 0;
    private int outputIterationCursor = 0;
    private double[] previousThickness;
    private int flowReductionCount = 0; // 統計目的のカウンター（制限なし）
    private int iterationsSinceFlowChange = 0; // フロー変更後の経過イテレーション数
    private static final int STABILIZATION_GRACE_PERIOD = 50; // 安定化猶予期間（イテレーション数）
    private int sourceNode = -1; // ソースノードID
    private int destNode = -1; // デスティネーションノードID
    private double previousSourcePressure = 0.0; // 前回のソース圧力値
    private double currentFlowBaselinePressure = 0.0; // 現在の要求フローでの基準圧力値（フロー増加判定用）
    private boolean currentFlowBaselineCaptured = false; // 現在フロー基準圧力がキャプチャ済みかどうか

    // Phase 5: 現在処理中のクライアントID
    private int currentClientId = -1;

    /**
     * コンストラクタ
     * @param serverController サーバーコントローラー
     * @param adjMatrix 隣接行列
     * @param link リンク情報
     * @param beaconCluster ビーコンクラスター
     * @param node ノード数
     */
    public StepControlledPressureGuidedEPSRouteSearcher(ServerController serverController, int[][] adjMatrix,
                                                        Link[][] link, BeaconCluster beaconCluster, int node) {
        super(serverController, adjMatrix, link, beaconCluster, node);
    }
    
    /**
     * 経路記録のタグを取得する
     * @return 経路記録のタグ
     */
    @Override
    protected String getRouteRecordTag() {
        return "runUAVFlow_STEPCONTROLLED_PGEPS";
    }

    /**
     * 残りの経路記録のタグを取得する
     * @return 残りの経路記録のタグ
     */
    @Override
    protected String getRemainingRouteRecordTag() {
        return "remainingFlow_STEPCONTROLLED_PGEPS";
    }

    @Override
    public void search(Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, int numLoop) throws IOException {
        // 通常のUAV割り当てのみを行う場合は親クラスのメソッドを呼び出す
        if (numLoop == 0) {
            super.search(client, flyingUavQueue, uavQueue, numLoop);
            return;
        }

        // 入力パラメータの取得と初期化
        requestedFlow = client.getFlow().getTheNumberOfUAV();
        this.sourceNode = client.getFlow().getSource().getId();
        this.destNode = client.getFlow().getDestination().getId();
        this.currentClientId = client.getId();  // Phase 5: クライアントID保存
        double eps = 1e-10;
        int ct = 0;

        LogManager.getInstance().log("StepControlledPGEPS: Starting adaptive flow control with requested flow " + requestedFlow);

        // 適応的フロー制御：要求フロー値から開始
        currentFlow = requestedFlow;
        stableIterationCount = 0;
        flowReductionCount = 0;
        iterationsSinceFlowChange = 0; // 初期起動時は猶予期間内から開始（初期の不安定性を回避）
        previousSourcePressure = 0.0; // 初期化

        // 前回チューブ厚の初期化
        initializePreviousThickness();

        // 容量0のリンクを一時的に切断（EPSが選択しないようにする）
        disconnectZeroCapacityLinks();

        try {
            // 適応的フロー制御メインループ
            while (ct < MAX_ITERATIONS && stableIterationCount < REQUIRED_STABLE_ITERATIONS) {
                // 流入フロー値を設定
                Q_Kirchhoff[sourceNode] = currentFlow;
                Q_Kirchhoff[destNode] = currentFlow * NEG;
                
                // その他のノードは0に設定
                for (int i = 0; i < node; i++) {
                    pressureCoefficient[i][i] = 0.0;
                    if (i != sourceNode && i != destNode) {
                        Q_Kirchhoff[i] = 0.0;
                    }
                }

                // 圧力勾配の導出
                for (int i = 0; i < node; i++) {
                    for (int j = 0; j < node; j++) {
                        if (link[i][j].getL_tubeLength() != INF) {
                            if (i != j) {
                                pressureCoefficient[i][j] = link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength() * NEG;
                            }
                        }
                    }
                }

                int k = 0;
                for (int i = 0; i < node; i++) {
                    for (int j = 0; j < node; j++) {
                        if (link[i][j].getL_tubeLength() != INF) {
                            pressureCoefficient[k][k] += link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength();
                        }
                    }
                    k++;
                }

                // 線形方程式を解く
                int testIter = 10;
                if (solvePressureEquation(pressureCoefficient, Q_Kirchhoff, P_tubePressure, node, testIter, eps) == -1) {
                    // Phase 5: ソルバー失敗時は例外をスローして再試行管理に委ねる
                    LogManager.getInstance().log("StepControlledPGEPS: [Solver Failure] Pressure equation solving failed at iteration " + (ct + 1) +
                                              " with flow " + currentFlow + ". Throwing SolverFailedException for retry management.");
                    throw new SolverFailedException(currentClientId, ct + 1, "StepControlledPGEPS");
                }

                // 流量の計算
                for (int i = 0; i < node; i++) {
                    for (int j = 0; j < node; j++) {
                        if (link[i][j].getL_tubeLength() != INF) {
                            link[i][j].setQ_tubeFlow((link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength()) * (P_tubePressure[i] - P_tubePressure[j]));
                        }
                    }
                }

                // シグモイド関数
                for (int i = 0; i < node; i++) {
                    for (int j = 0; j < node; j++) {
                        if (link[i][j].getL_tubeLength() != INF) {
                            Q_tubeFlow_sigmoidOutput[i][j] = Math.pow(Math.abs(link[i][j].getQ_tubeFlow()), GAMMA) / (1 + Math.pow(Math.abs(link[i][j].getQ_tubeFlow()), GAMMA));
                        }
                    }
                }

                // チューブ厚の更新
                updateTubeThickness(ct);

                // フロー変更後の経過イテレーションをカウント
                iterationsSinceFlowChange++;


                // 現在フロー基準圧力をキャプチャ（フロー変更後の最初のループのみ）
                if (!currentFlowBaselineCaptured && sourceNode >= 0) {
                    currentFlowBaselinePressure = Math.abs(P_tubePressure[sourceNode]);
                    currentFlowBaselineCaptured = true;
                    LogManager.getInstance().log("StepControlledPGEPS: Current flow baseline pressure captured: " + String.format("%.4f", currentFlowBaselinePressure));
                }

                // ソース圧力の個別チェック（猶予期間に関係なく常に実行）
                double sourcePressureScore = calculateSourcePressureScore();
                double sourcePressureChangeScore = calculateSourcePressureChangeScore();
                
                // ソース圧力10%減少検知とフロー増加ロジック
                boolean shouldIncreaseFlow = checkSourcePressureReductionAndIncreaseFlow();

                // ソース圧力絶対値チェック（最優先 - フロー半減）
                if (sourcePressureScore >= 1.0) {
                    // ソース圧力絶対値が緊急レベル（100以上）- フローを半分にする（整数値）
                    double currentPressure = Math.abs(P_tubePressure[sourceNode]);
                    double targetHalfFlow = currentFlow / 2.0;
                    double halvingReductionAmount = currentFlow - Math.ceil(targetHalfFlow); // 半分値を繰り上げして減少量を計算
                    
                    LogManager.getInstance().log("StepControlledPGEPS: CRITICAL absolute source pressure detected at iteration " + (ct + 1) +
                                              " (sourcePressure=" + String.format("%.2f", currentPressure) + 
                                              "). Halving flow from " + currentFlow + " to " + Math.ceil(targetHalfFlow) + " (reduction: " + halvingReductionAmount + " UAVs)");
                    
                    if (!applyUAVFlowReduction(halvingReductionAmount, "Critical absolute source pressure - halving flow")) {
                        LogManager.getInstance().log("StepControlledPGEPS: Cannot reduce flow further. Treating as stable iteration.");
                        // 最小フロー制約に達した場合、これ以上改善できないため安定とみなす
                        stableIterationCount++;

                        // 500回連続安定を達成したか確認
                        if (stableIterationCount >= REQUIRED_STABLE_ITERATIONS) {
                            LogManager.getInstance().log("StepControlledPGEPS: Achieved " + REQUIRED_STABLE_ITERATIONS + " stable iterations at iteration " + (ct + 1) + " with flow " + currentFlow);

                            // フロー分解アルゴリズムでは整数丸め込みは不要
                            LogManager.getInstance().log("StepControlledPGEPS: EPS converged with flow " + currentFlow);

                            // 最終結果を出力
                            ResultOutputManager.outputToPajek(client, eps, requestedFlow, ct, link, beaconCluster, node, serverController.getRunCounter());
                            ResultOutputManager.outputToExcel(client, ct, link, node, serverController.getRunCounter(), currentFlow);
                            ResultOutputManager.outputToTxt(client, ct, link, node, serverController.getRunCounter(), pressureCoefficient, P_tubePressure, currentFlow);

                            // ループを抜ける
                            break;
                        }
                    }
                } else if (sourcePressureChangeScore >= 1.0) {
                    // ソース圧力変化率チェック（段階的なUAV減少）
                    double currentPressure = Math.abs(P_tubePressure[sourceNode]);
                    double changeRate = (currentPressure - previousSourcePressure) / previousSourcePressure;
                    double reductionAmount = calculateSourcePressureReduction();

                    String reductionLevel;
                    if (changeRate >= SOURCE_PRESSURE_CHANGE_LEVEL3) {
                        reductionLevel = "CRITICAL (≥10%)";
                    } else if (changeRate >= SOURCE_PRESSURE_CHANGE_LEVEL2) {
                        reductionLevel = "HIGH (5-10%)";
                    } else {
                        reductionLevel = "MODERATE (3-5%)";
                    }

                    LogManager.getInstance().log("StepControlledPGEPS: Source pressure change detected [" + reductionLevel + "] at iteration " + (ct + 1) +
                                              " (changeRate=" + String.format("%.1f%%", changeRate * 100) +
                                              ", " + String.format("%.2f", previousSourcePressure) + " → " + String.format("%.2f", currentPressure) +
                                              ", reducing " + (int)reductionAmount + " UAVs)");

                    if (!applyUAVFlowReduction(reductionAmount, "Source pressure change rate")) {
                        LogManager.getInstance().log("StepControlledPGEPS: Cannot reduce flow further. Treating as stable iteration.");
                        // 最小フロー制約に達した場合、これ以上改善できないため安定とみなす
                        stableIterationCount++;

                        // 500回連続安定を達成したか確認
                        if (stableIterationCount >= REQUIRED_STABLE_ITERATIONS) {
                            LogManager.getInstance().log("StepControlledPGEPS: Achieved " + REQUIRED_STABLE_ITERATIONS + " stable iterations at iteration " + (ct + 1) + " with flow " + currentFlow);

                            // フロー分解アルゴリズムでは整数丸め込みは不要
                            LogManager.getInstance().log("StepControlledPGEPS: EPS converged with flow " + currentFlow);

                            // 最終結果を出力
                            ResultOutputManager.outputToPajek(client, eps, requestedFlow, ct, link, beaconCluster, node, serverController.getRunCounter());
                            ResultOutputManager.outputToExcel(client, ct, link, node, serverController.getRunCounter(), currentFlow);
                            ResultOutputManager.outputToTxt(client, ct, link, node, serverController.getRunCounter(), pressureCoefficient, P_tubePressure, currentFlow);

                            // ループを抜ける
                            break;
                        }
                    }
                } else if (iterationsSinceFlowChange < STABILIZATION_GRACE_PERIOD) {
                    // 猶予期間中：他の不安定性検知をスキップし、システムに安定化の時間を与える
                    // 注意：猶予期間中はstableIterationCountをカウントしない

                    // 定期的にログ出力（10イテレーションごと）
                    if ((ct + 1) % 10 == 0) {
                        LogManager.getInstance().log("Stabilization grace period: iteration " + (ct + 1) +
                                                  " (" + iterationsSinceFlowChange + "/" + STABILIZATION_GRACE_PERIOD +
                                                  ") with flow " + currentFlow +
                                                  " (sourcePressure: " + String.format("%.2f", Math.abs(P_tubePressure[sourceNode])) + ")");
                    }
                } else {
                    // 安定：カウンター増加
                    stableIterationCount++;

                    // 500回連続安定を達成したらEPS終了
                    if (stableIterationCount >= REQUIRED_STABLE_ITERATIONS) {
                        LogManager.getInstance().log("StepControlledPGEPS: Achieved " + REQUIRED_STABLE_ITERATIONS + " stable iterations at iteration " + (ct + 1) + " with flow " + currentFlow);

                        // フロー分解アルゴリズムでは整数丸め込みは不要
                        LogManager.getInstance().log("StepControlledPGEPS: EPS converged with flow " + currentFlow);

                        // 最終結果を出力
                        ResultOutputManager.outputToPajek(client, eps, requestedFlow, ct, link, beaconCluster, node, serverController.getRunCounter());
                        ResultOutputManager.outputToExcel(client, ct, link, node, serverController.getRunCounter(), currentFlow);
                        ResultOutputManager.outputToTxt(client, ct, link, node, serverController.getRunCounter(), pressureCoefficient, P_tubePressure, currentFlow);

                        // ループを抜ける
                        break;
                    }
                }

                // 結果のプロット（500回到達前のみ）
                if ((ct + 1) % PLOT == 0) {
                    LogManager.getInstance().log("Iteration: " + (ct + 1) + " with flow " + currentFlow +
                                              " (stable count: " + stableIterationCount + "/" + REQUIRED_STABLE_ITERATIONS + ")");
                    ResultOutputManager.outputToPajek(client, eps, requestedFlow, ct, link, beaconCluster, node, serverController.getRunCounter());
                    ResultOutputManager.outputToExcel(client, ct, link, node, serverController.getRunCounter(), currentFlow);
                    ResultOutputManager.outputToTxt(client, ct, link, node, serverController.getRunCounter(), pressureCoefficient, P_tubePressure, currentFlow);
                }
                
                // 前回チューブ厚を更新（次回の変化率計算用）
                updatePreviousThickness();

                // イテレーション毎の結果を記録
                try {
                    if (sourceNode >= 0) {
                        double currentSourcePressure = P_tubePressure[sourceNode];
                        ResultOutputManager.outputIterationSourcePressure(ct + 1, currentSourcePressure, serverController.getRunCounter());
                    }
                    ResultOutputManager.outputIterationFlow(ct + 1, currentFlow, serverController.getRunCounter());
                } catch (IOException e) {
                    LogManager.getInstance().error("Failed to output iteration data", e);
                }

                // 前回のソース圧力を更新（次回の変化率計算用）
                if (sourceNode >= 0) {
                    previousSourcePressure = Math.abs(P_tubePressure[sourceNode]);
                }

                ct++;
            }

            // 収束時の処理
            if (stableIterationCount >= REQUIRED_STABLE_ITERATIONS) {
                // 既にループ内で整数丸め込みと出力が完了している
                LogManager.getInstance().log("StepControlledPGEPS: EPS converged successfully with flow " + currentFlow + ". Integer rounding completed.");
            } else if (ct >= MAX_ITERATIONS) {
                // Phase 5: MAX_ITERATIONS超過時は例外をスローして再試行管理に委ねる
                LogManager.getInstance().log("StepControlledPGEPS: [Max Iterations] Reached maximum iterations (" + MAX_ITERATIONS +
                                          ") without convergence. Throwing SolverFailedException for retry management.");
                throw new SolverFailedException(currentClientId, ct, "StepControlledPGEPS-MaxIterations");
            } else {
                LogManager.getInstance().log("StepControlledPGEPS: Terminated with final flow " + currentFlow +
                                          " after " + ct + " iterations (stable count: " + stableIterationCount + ")");
            }

            // フロー分解アルゴリズムによる経路割り当て準備
            // （tubeFlow配列は不要、explorePath/explorePathGreedyがlink[i][j].getQ_tubeFlow()を直接参照）
            LogManager.getInstance().log("StepControlledPGEPS: Flow decomposition will use link Q_tubeFlow directly");

            // EPSフロー値から残りUAV数を計算
            int requiredUAVs = (int) requestedFlow;
            double epsAssignedFlow = currentFlow;
            int remainingUAVs = requiredUAVs - (int)epsAssignedFlow;

            LogManager.getInstance().log("StepControlledPGEPS: EPS assigned flow " + epsAssignedFlow + " out of " + requiredUAVs + " required UAVs");

            // Phase 4: 事業者の時間計測開始（経路割り当て開始時点）
            ClientTimeManager.getInstance().startClientTime(client.getId(), requiredUAVs);

            // Phase 4: 残りUAVがある場合は経路待ちキューに登録
            if (remainingUAVs > 0) {
                LogManager.getInstance().log("StepControlledPGEPS Phase 4: EPS割当=" + (int)epsAssignedFlow +
                    ", 経路待ち登録=" + remainingUAVs + " (要求UAV数=" + requiredUAVs + ")");

                int epsAssigned = (int) epsAssignedFlow;

                if (BoundaryController.getCurrentWorkerMode() == BoundaryController.WorkerMode.MEMORY) {
                    // MEMORYモード: MemoryPathWaitingManager に Uav オブジェクトを登録
                    for (int i = 0; i < remainingUAVs; i++) {
                        int uavIndex = epsAssigned + i;
                        Uav waitingUav = client.getFlow().getUav(uavIndex);
                        waitingUav.startPathWaiting();
                        MemoryPathWaitingManager.getInstance().enqueue(client.getId(), waitingUav);
                    }
                } else {
                    // REDISモード: FlightSchedulerからPathWaitingManagerを取得
                    PathWaitingManager pathWaitingManager = FlightScheduler.getInstance().getPathWaitingManager();

                    // 経路待ちUAVをキューに登録（EPS割り当て分の次のIDから開始）
                    for (int i = 0; i < remainingUAVs; i++) {
                        int uavIndex = epsAssigned + i;
                        // UAVオブジェクトから速度を取得（各UAVは8~16m/sのランダム速度を持つ）
                        double uavSpeed = client.getFlow().getUav(uavIndex).getSpeed();
                        int uavId = client.getFlow().getUav(uavIndex).getId();
                        UAVJob waitingJob = new UAVJob(
                            uavId,
                            client.getId(),
                            null,  // path = null（経路待ち状態）
                            uavSpeed,
                            System.currentTimeMillis(),
                            sourceNode,
                            destNode
                        );
                        waitingJob.setSessionId(FlightScheduler.getInstance().getSessionId());
                        // Phase 4: 経路待ち開始時刻を記録
                        waitingJob.startPathWaiting();
                        pathWaitingManager.enqueue(client.getId(), waitingJob);
                    }
                }

                LogManager.getInstance().log("StepControlledPGEPS Phase 4: " + remainingUAVs + "機の経路待ち登録完了");
            } else {
                LogManager.getInstance().log("StepControlledPGEPS Phase 4: 全UAV(" + requiredUAVs + "機)をEPS割当完了、経路待ちなし");
            }

            // EPS割り当て分のUAV飛行開始前に流量の最終確認
            int epsUAVCount = (int) epsAssignedFlow;
            LogManager.getInstance().log("StepControlledPGEPS: Starting UAV flight assignment for " + epsUAVCount + " EPS UAVs (out of " + requiredUAVs + " total)");

            // フロー分解アルゴリズムでは非整数フローは許容される
            // （explorePathがfloor(minFlow)で整数台を抽出、explorePathGreedyが残りを処理）

            runUAVFlow(sourceNode, destNode, epsUAVCount, client, flyingUavQueue, uavQueue);
            
        } catch (Exception e) {
            // エラー詳細をログ出力
            LogManager.getInstance().error("Error in incremental EPS process: ", e);
            throw e; // 再スローして上位で処理
        } finally {
            // 切断したリンクを復元（例外発生時も確実に復元）
            restoreDisconnectedLinks();
        }
    }

    /**
     * 前回チューブ厚配列を初期化する
     */
    private void initializePreviousThickness() {
        int totalLinks = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    totalLinks++;
                }
            }
        }
        previousThickness = new double[totalLinks];
        
        // 初期値を設定
        int index = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    previousThickness[index++] = link[i][j].getD_tubeThickness();
                }
            }
        }
        LogManager.getInstance().log("StepControlledPGEPS: Initialized previous thickness array with " + totalLinks + " links");
    }

    /**
     * 前回チューブ厚を更新する
     */
    private void updatePreviousThickness() {
        int index = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    previousThickness[index++] = link[i][j].getD_tubeThickness();
                }
            }
        }
    }


    /**
     * ソースノード圧力スコアを計算する（絶対値）
     * @return ソース圧力絶対値スコア (0.0-1.0)
     */
    private double calculateSourcePressureScore() {
        if (sourceNode < 0) {
            return 0.0; // ソースノードが設定されていない場合
        }

        double sourcePressure = Math.abs(P_tubePressure[sourceNode]);

        // 段階的スコア計算
        if (sourcePressure >= SOURCE_PRESSURE_EMERGENCY) {
            return 1.0; // 緊急レベル
        } else if (sourcePressure >= SOURCE_PRESSURE_WARNING) {
            return 0.5 + 0.5 * (sourcePressure - SOURCE_PRESSURE_WARNING)
                       / (SOURCE_PRESSURE_EMERGENCY - SOURCE_PRESSURE_WARNING);
        } else {
            return 0.5 * sourcePressure / SOURCE_PRESSURE_WARNING;
        }
    }

    /**
     * ソースノード圧力変化率スコアを計算する（現在フロー基準圧力からの増加率）
     * @return ソース圧力変化率スコア (0.0-1.0)
     */
    private double calculateSourcePressureChangeScore() {
        if (sourceNode < 0 || !currentFlowBaselineCaptured || currentFlowBaselinePressure == 0.0) {
            return 0.0; // 初回または基準圧力なしの場合は変化なし
        }

        double currentPressure = Math.abs(P_tubePressure[sourceNode]);
        
        // 基準圧力からの増加量を計算
        if (currentPressure <= currentFlowBaselinePressure) {
            return 0.0; // 減少または不変ならスコア0（減少判定しない）
        }

        double increaseRate = (currentPressure - currentFlowBaselinePressure) / currentFlowBaselinePressure;

        // 変化率7%を超えたら1.0を返す（検知トリガー）
        if (increaseRate > SOURCE_PRESSURE_CHANGE_LEVEL1) {
            return 1.0;
        } else {
            // 7%以下は線形スコア
            return increaseRate / SOURCE_PRESSURE_CHANGE_LEVEL1;
        }
    }


    /**
     * ソースノード圧力変化率に基づくUAV減少量を計算する（現在フロー基準圧力からの増加率）
     * @return UAV減少量（0の場合は減少不要）
     */
    private double calculateSourcePressureReduction() {
        if (sourceNode < 0 || !currentFlowBaselineCaptured || currentFlowBaselinePressure == 0.0) {
            return 0.0; // 初回または基準圧力なしの場合は減少なし
        }

        double currentPressure = Math.abs(P_tubePressure[sourceNode]);
        
        // 基準圧力からの増加がない場合は減少不要
        if (currentPressure <= currentFlowBaselinePressure) {
            return 0.0;
        }
        
        double increaseRate = (currentPressure - currentFlowBaselinePressure) / currentFlowBaselinePressure;

        // 段階的なUAV減少量の決定（基準圧力からの増加率）
        if (increaseRate >= SOURCE_PRESSURE_CHANGE_LEVEL3) {
            return 5.0; // 15%以上: 5UAV減少
        } else if (increaseRate >= SOURCE_PRESSURE_CHANGE_LEVEL2) {
            return 3.0; // 12%~15%: 3UAV減少
        } else if (increaseRate > SOURCE_PRESSURE_CHANGE_LEVEL1) {
            return 1.0; // 7%~12%: 1UAV減少
        } else {
            return 0.0; // 7%以下: 減少なし
        }
    }

    /**
     * UAV用フロー減少を適用する（整数単位での減少）
     * @param reductionAmount 減少UAV数（整数）
     * @param reason 減少理由
     * @return フロー減少が適用された場合true、最小フロー以下の場合false
     */
    private boolean applyUAVFlowReduction(double reductionAmount, String reason) {
        double minFlow = Math.max(1.0, requestedFlow * MINIMUM_FLOW_RATIO);
        double newFlow = currentFlow - reductionAmount; // 整数単位での減少

        if (newFlow < minFlow) {
            LogManager.getInstance().log("StepControlledPGEPS: Cannot reduce flow below minimum safety level (" + minFlow + "). Current flow: " + currentFlow);
            return false;
        }

        currentFlow = newFlow;
        flowReductionCount++; // 統計目的でカウント保持
        stableIterationCount = 0; // リセット
        iterationsSinceFlowChange = 0; // 安定化猶予期間をリセット
        
        // 圧力変化率計算のための前回圧力をリセット（フロー変更後の過度な変化率検知を回避）
        previousSourcePressure = 0.0;
        
        // 基準圧力をリセット（新しいフロー値での基準を次回キャプチャ）
        currentFlowBaselineCaptured = false;

        LogManager.getInstance().log("StepControlledPGEPS: Flow reduced by " + reductionAmount + " UAVs due to " + reason +
                                  ". New flow: " + currentFlow + " (reduction count: " + flowReductionCount + ")");

        return true;
    }

    /**
     * ソース圧力10%減少検知とフロー増加ロジック
     * @return フロー増加を適用した場合true、それ以外はfalse
     */
    private boolean checkSourcePressureReductionAndIncreaseFlow() {
        // 現在フロー基準圧力がキャプチャされていない場合は何もしない
        if (!currentFlowBaselineCaptured || sourceNode < 0 || currentFlowBaselinePressure == 0.0) {
            return false;
        }

        double currentPressure = Math.abs(P_tubePressure[sourceNode]);

        // 現在の要求フローでの基準圧力から50%減少したかをチェック
        double fiftyPercentReduction = currentFlowBaselinePressure * 0.5; // 50%になった場合（50%減少）

        if (currentPressure < fiftyPercentReduction) {
            // 現在のフローが要求フローより少ない場合のみ増加を適用
            if (currentFlow < requestedFlow) {
                double increaseAmount = 1.0; // 1UAV増加
                double newFlow = currentFlow + increaseAmount;
                
                // 要求フローを超えないように制限
                if (newFlow > requestedFlow) {
                    newFlow = requestedFlow;
                    increaseAmount = newFlow - currentFlow;
                }
                
                // フロー増加を適用
                if (increaseAmount > 0) {
                    currentFlow = newFlow;
                    stableIterationCount = 0; // リセット
                    iterationsSinceFlowChange = 0; // 安定化猶予期間をリセット
                    
                    // 基準圧力をリセット（新しいフロー値での基準を次回キャプチャ）
                    currentFlowBaselineCaptured = false;
                    
                    LogManager.getInstance().log("StepControlledPGEPS: Source pressure 50% reduction detected " +
                                              " (baselinePressure=" + String.format("%.4f", currentFlowBaselinePressure) +
                                              ", currentPressure=" + String.format("%.4f", currentPressure) +
                                              ", threshold=" + String.format("%.4f", fiftyPercentReduction) +
                                              "). Increasing flow by " + (int)increaseAmount + " UAVs to " + currentFlow);
                    
                    return true;
                }
            } else {
                LogManager.getInstance().log("StepControlledPGEPS: Source pressure 50% reduction detected but flow already at requested level " +
                                          " (currentFlow=" + currentFlow + ", requestedFlow=" + requestedFlow + ")");
            }
        }
        
        return false;
    }

    /**
     * チューブ厚異常検知メソッド
     * @return 異常が検知された場合true、それ以外はfalse
     */
    private boolean checkTubeThicknessAnomaly() {
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF && link[i][j].getD_tubeThickness() < 0.0) {
                    LogManager.getInstance().log("StepControlledPGEPS: Tube thickness anomaly detected at link (" + i + "," + j + 
                                              ") with thickness = " + link[i][j].getD_tubeThickness());
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * EPSを初期化する（一番初めの状態に戻す）
     */
    private void initializeEPS() {
        LogManager.getInstance().log("StepControlledPGEPS: Initializing EPS to initial state");
        
        // リンクのチューブ厚を初期値にリセット
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    link[i][j].setD_tubeThickness(INIT_THICKNESS);
                    link[i][j].setQ_tubeFlow(0.0);
                }
            }
        }
        
        // 圧力係数配列をリセット
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                pressureCoefficient[i][j] = 0.0;
            }
        }
        
        // その他の配列もリセット
        for (int i = 0; i < node; i++) {
            P_tubePressure[i] = 0.0;
            Q_Kirchhoff[i] = 0.0;
            for (int j = 0; j < node; j++) {
                D_tubeThickness_deltaT[i][j] = 0.0;
                Q_tubeFlow_sigmoidOutput[i][j] = 0.0;
            }
        }
    }
    
    /**
     * 安定化イテレーションを実行する
     * @param client クライアント
     * @param sourceNode ソースノード
     * @param destNode デスティネーションノード
     * @param eps イプシロン
     * @return 安定化に成功した場合true、失敗した場合false
     */
    private boolean performStabilizationIterations(Client client, int sourceNode, int destNode, int startIteration, double eps) {
        LogManager.getInstance().log("StepControlledPGEPS: Starting " + STABILIZATION_ITERATIONS + " stabilization iterations with flow " + currentFlow);
        if (this.outputIterationCursor < startIteration) {
            this.outputIterationCursor = startIteration;
        }
        
        for (int stabIter = 0; stabIter < STABILIZATION_ITERATIONS; stabIter++) {
            int currentIteration = ++outputIterationCursor;
            // フロー値を設定
            Q_Kirchhoff[sourceNode] = currentFlow;
            Q_Kirchhoff[destNode] = currentFlow * NEG;
            
            // その他のノードは0に設定
            for (int i = 0; i < node; i++) {
                if (i != sourceNode && i != destNode) {
                    Q_Kirchhoff[i] = 0.0;
                }
            }
            
            // 圧力係数の計算
            for (int i = 0; i < node; i++) {
                pressureCoefficient[i][i] = 0.0;
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF && i != j) {
                        pressureCoefficient[i][j] = link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength() * NEG;
                    }
                }
            }
            
            int k = 0;
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) {
                        pressureCoefficient[k][k] += link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength();
                    }
                }
                k++;
            }
            
            // 圧力方程式を解く
            int testIter = 10;
            if (solvePressureEquation(pressureCoefficient, Q_Kirchhoff, P_tubePressure, node, testIter, eps) == -1) {
                // Phase 5: ソルバー失敗時は例外をスローして再試行管理に委ねる
                LogManager.getInstance().log("StepControlledPGEPS: [Solver Failure] Pressure equation solving failed during stabilization iteration " + (stabIter + 1) +
                                          ". Throwing SolverFailedException for retry management.");
                throw new SolverFailedException(currentClientId, currentIteration, "StepControlledPGEPS-Stabilization");
            }
            
            // 流量の計算
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) {
                        link[i][j].setQ_tubeFlow((link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength()) * (P_tubePressure[i] - P_tubePressure[j]));
                    }
                }
            }
            
            // シグモイド関数
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) {
                        Q_tubeFlow_sigmoidOutput[i][j] = Math.pow(Math.abs(link[i][j].getQ_tubeFlow()), GAMMA) / (1 + Math.pow(Math.abs(link[i][j].getQ_tubeFlow()), GAMMA));
                    }
                }
            }
            
            // チューブ厚の更新
            updateTubeThickness(stabIter);

            // 安定化イテレーションごとにflow/statusを出力（連番）
            try {
                ResultOutputManager.outputToExcel(client, currentIteration - 1, link, node, serverController.getRunCounter(), currentFlow);
                ResultOutputManager.outputToTxt(client, currentIteration - 1, link, node, serverController.getRunCounter(), pressureCoefficient, currentFlow);
            } catch (IOException ioe) {
                LogManager.getInstance().error("StepControlledPGEPS: Failed to write stabilization outputs", ioe);
            }
            
            // 各安定化イテレーション後にチューブ厚をチェック
            if (checkTubeThicknessAnomaly()) {
                LogManager.getInstance().log("StepControlledPGEPS: Tube thickness anomaly detected during stabilization iteration " + (stabIter + 1) + ". Stabilization failed.");
                return false;
            }
        }
        
        // フロー分解アルゴリズムでは整数丸め込みは不要
        LogManager.getInstance().log("StepControlledPGEPS: Stabilization completed successfully after " + STABILIZATION_ITERATIONS + " iterations with flow " + currentFlow);
        return true;
    }

    /**
     * PSによる追加計算を実行する
     * @param client クライアント
     * @param remainingUAVs 残りUAV数
     * @param sourceNode ソースノード
     * @param destNode デスティネーションノード
     * @param startIteration 開始イテレーション数
     * @param eps イプシロン
     * @return 最終イテレーション数
     * @throws IOException 入出力例外
     */
    private int performPSComputation(Client client, int remainingUAVs, int sourceNode, int destNode, 
                                   int startIteration, double eps) throws IOException {
        
        // PS用一時結果配列
        double[][] psTempResults = new double[node][node];
        
        // PSのための初期化（EPSの結果は保持したまま、PSは独立して計算）
        double[] ps_Q_Kirchhoff = new double[node];
        double[] ps_P_tubePressure = new double[node];
        double[][] ps_pressureCoefficient = new double[node][node];
        double[][] ps_Q_tubeFlow_sigmoidOutput = new double[node][node];
        double[][] ps_D_tubeThickness_deltaT = new double[node][node];
        
        // PSのリンク情報を初期状態で初期化
        Link[][] psLink = new Link[node][node];
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    psLink[i][j] = new Link();
                    psLink[i][j].setDistance(link[i][j].getDistance());
                    psLink[i][j].setCapacity(link[i][j].getCapacity());
                    psLink[i][j].setL_tubeLength(link[i][j].getL_tubeLength());
                    psLink[i][j].setD_tubeThickness(INIT_THICKNESS); // 初期値0.5から開始
                    psLink[i][j].setQ_tubeFlow(0.0);
                } else {
                    psLink[i][j] = link[i][j]; // INFの場合はそのまま
                }
            }
        }
        
        LogManager.getInstance().log("StepControlledPGEPS: PS starting with " + remainingUAVs + " UAVs flow");
        
        // PS用500回イテレーション
        for (int psIter = 0; psIter < 500; psIter++) {
            int currentIteration = startIteration + psIter + 1;
            
            // フロー値設定
            ps_Q_Kirchhoff[sourceNode] = remainingUAVs;
            ps_Q_Kirchhoff[destNode] = remainingUAVs * NEG;
            
            for (int i = 0; i < node; i++) {
                ps_pressureCoefficient[i][i] = 0.0;
                if (i != sourceNode && i != destNode) {
                    ps_Q_Kirchhoff[i] = 0.0;
                }
            }

            // 圧力勾配の導出
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (psLink[i][j].getL_tubeLength() != INF) {
                        if (i != j) {
                            ps_pressureCoefficient[i][j] = psLink[i][j].getD_tubeThickness() / psLink[i][j].getL_tubeLength() * NEG;
                        }
                    }
                }
            }

            int k = 0;
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (psLink[i][j].getL_tubeLength() != INF) {
                        ps_pressureCoefficient[k][k] = ps_pressureCoefficient[k][k] + psLink[i][j].getD_tubeThickness() / psLink[i][j].getL_tubeLength();
                    }
                }
                k++;
            }

            // 圧力方程式を解く（ICCGソルバーを使用）
            int testIter = 10;
            int result = ICCGSolver.solve(ps_pressureCoefficient, ps_Q_Kirchhoff, ps_P_tubePressure, node, testIter, eps);
            if (result != 1) {
                LogManager.getInstance().log("StepControlledPGEPS: PS pressure equation solving failed at iteration " + currentIteration);
                break;
            }

            // 流量の計算
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (psLink[i][j].getL_tubeLength() != INF) {
                        psLink[i][j].setQ_tubeFlow((psLink[i][j].getD_tubeThickness() / psLink[i][j].getL_tubeLength()) * (ps_P_tubePressure[i] - ps_P_tubePressure[j]));
                    }
                }
            }

            // フロー分解アルゴリズムでは整数丸め込みは不要
            // （explorePath/explorePathGreedyがリアルタイムでフローを減算する）

            // シグモイド関数
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (psLink[i][j].getL_tubeLength() != INF) {
                        ps_Q_tubeFlow_sigmoidOutput[i][j] = Math.pow(Math.abs(psLink[i][j].getQ_tubeFlow()), GAMMA) / (1 + Math.pow(Math.abs(psLink[i][j].getQ_tubeFlow()), GAMMA));
                    }
                }
            }

            // PSのチューブ厚更新（PSのロジック）
            double degeneracyEffect = 0.5;
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (psLink[i][j].getL_tubeLength() != INF) {
                        double deltaThickness = (ps_Q_tubeFlow_sigmoidOutput[i][j] - (degeneracyEffect * psLink[i][j].getD_tubeThickness())) * DELTA_TIME;
                        ps_D_tubeThickness_deltaT[i][j] = deltaThickness;
                        double newThickness = psLink[i][j].getD_tubeThickness() + deltaThickness;
                        psLink[i][j].setD_tubeThickness(newThickness);
                    }
                }
            }

            // PS結果のプロット（継続イテレーション番号）
            if (currentIteration % PLOT == 0) {
                LogManager.getInstance().log("Iteration: " + currentIteration + " with PS flow " + remainingUAVs);
                ResultOutputManager.outputToPajek(client, eps, (double)remainingUAVs, currentIteration - 1, psLink, beaconCluster, node, serverController.getRunCounter());
                ResultOutputManager.outputToExcel(client, currentIteration - 1, psLink, node, serverController.getRunCounter(), remainingUAVs);
                ResultOutputManager.outputToTxt(client, currentIteration - 1, psLink, node, serverController.getRunCounter(), remainingUAVs);
            }
        }

        // PS結果をpsTempResultsに保存
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (psLink[i][j].getL_tubeLength() != INF) {
                    psTempResults[i][j] = psLink[i][j].getQ_tubeFlow();
                }
            }
        }

        // PS結果の流量合計を検証（ソースからの流出のみをカウント）
        double psFlowSum = 0.0;
        for (int j = 0; j < node; j++) {
            if (psLink[sourceNode][j].getL_tubeLength() != INF && psTempResults[sourceNode][j] > 0) {
                psFlowSum += psTempResults[sourceNode][j];
            }
        }
        LogManager.getInstance().log("StepControlledPGEPS: PS source outflow sum = " + psFlowSum + " (expected: " + remainingUAVs + ")");
        
        // 全リンクの流量合計も表示（デバッグ用）
        double totalFlowSum = 0.0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (psLink[i][j].getL_tubeLength() != INF && psTempResults[i][j] > 0) {
                    totalFlowSum += psTempResults[i][j];
                }
            }
        }
        LogManager.getInstance().log("StepControlledPGEPS: PS total all-links flow sum = " + totalFlowSum);

        // EPSとPSの結果を統合
        LogManager.getInstance().log("StepControlledPGEPS: Integrating EPS and PS results");
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    double epsFlow = link[i][j].getQ_tubeFlow();
                    double psFlow = psTempResults[i][j];
                    link[i][j].setQ_tubeFlow(epsFlow + psFlow);
                    if (psFlow > 0) {
                        LogManager.getInstance().log("Link (" + i + "," + j + "): EPS=" + epsFlow + " + PS=" + psFlow + " = " + (epsFlow + psFlow));
                    }
                }
            }
        }

        // フロー分解アルゴリズムでは整数丸め込みは不要
        // （explorePath/explorePathGreedyがリアルタイムでフローを減算する）

        // 統合後のソース流出合計を検証
        double integratedSourceOutflow = 0.0;
        for (int j = 0; j < node; j++) {
            if (link[sourceNode][j].getL_tubeLength() != INF && link[sourceNode][j].getQ_tubeFlow() > 0) {
                integratedSourceOutflow += link[sourceNode][j].getQ_tubeFlow();
            }
        }
        LogManager.getInstance().log("StepControlledPGEPS: Integrated source outflow sum = " + integratedSourceOutflow + " (expected: " + (int) requestedFlow + ")");
        LogManager.getInstance().log("StepControlledPGEPS: EPS+PS integration completed. Flow decomposition will handle path extraction.");

        return startIteration + 500;
    }

    // roundSourceOutflowsAndPropagate は削除
    // フロー分解アルゴリズムでは整数丸め込みは不要
    // （explorePath/explorePathGreedyがリアルタイムでフローを減算する）

    /**
     * 下流のリンクの流量を調整する（ネットワーク全体のバランス保持）
     * @param psLink PSのリンク配列
     * @param currentNode 現在のノード
     * @param destNode デスティネーションノード
     * @param correctionFactor 修正係数
     */
    private void adjustDownstreamFlows(Link[][] psLink, int currentNode, int destNode, double correctionFactor) {
        // 現在のノードから出るリンクを調整
        for (int j = 0; j < node; j++) {
            if (psLink[currentNode][j].getL_tubeLength() != INF && psLink[currentNode][j].getQ_tubeFlow() > 0) {
                double oldFlow = psLink[currentNode][j].getQ_tubeFlow();
                double newFlow = oldFlow * correctionFactor;
                psLink[currentNode][j].setQ_tubeFlow(newFlow);
                
                // 再帰的に下流も調整（デスティネーション到達まで）
                if (j != destNode && newFlow > 0) {
                    adjustDownstreamFlows(psLink, j, destNode, correctionFactor);
                }
            }
        }
    }
}
