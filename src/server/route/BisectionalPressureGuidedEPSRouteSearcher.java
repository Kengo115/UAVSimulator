package server.route;

import client.Client;
import item.BeaconCluster;
import item.Link;
import item.Uav;
import server.controller.ServerController;
import server.util.EPSSavePoint;
import server.util.ICCGSolver;
import server.util.LogManager;
import server.util.MathUtils;
import server.util.ResultOutputManager;
import server.redis.ClientTimeManager;
import server.redis.PathWaitingManager;
import server.redis.UAVJob;
import server.scheduler.FlightScheduler;
import controller.BoundaryController;

import java.io.IOException;
import java.util.Queue;

/**
 * 二分法型圧力誘導EPS (Bisectional Pressure-Guided EPS)
 * Phase 4: 最大フロー超過分は経路待ちキューで管理し、
 * 第一ホップ通過時に経路をコピーして飛行開始する
 *
 * 二分探索を使用して最適なフロー値を見つけるEPS
 */
public class BisectionalPressureGuidedEPSRouteSearcher extends ExtendedPhysarumSolverRouteSearcher {

    // 定数
    private static final double INIT_THICKNESS = 0.5; // 初期チューブ厚
    private static final double INIT_LENGTH = 1.0; // 初期チューブ長
    private static final int MAX_ITERATIONS = 1000; // 最大イテレーション数
    private static final int REQUIRED_STABLE_ITERATIONS = 150; // 収束判定用の連続安定回数
    private static final int MAX_BINARY_SEARCH_ITERATIONS = 30; // 二分探索の最大回数

    // ソースノード圧力専用閾値
    private static final double SOURCE_PRESSURE_EMERGENCY = 100; // 圧力絶対値閾値
    private static final double SOURCE_PRESSURE_CHANGE_THRESHOLD = 0.30; // 20%変化率閾値（フロー減少用）
    private static final double SOURCE_PRESSURE_REDUCTION_THRESHOLD = 0.50; // 20%減少閾値（フロー増加用）

    // フロー減少（UAV整数値対応）
    private static final double MINIMUM_FLOW_RATIO = 0.1; // 最小安全フロー（要求フローの10%）

    // 安定化フェーズ設定
    private static final int STABILIZATION_PHASE_ITERATIONS = 5; // 安定化フェーズのイテレーション数

    // 現在のフロー値と制御状態
    private double currentFlow;
    private double requestedFlow;
    private int stableIterationCount = 0;
    private int outputIterationCursor = 0;
    private double[] previousThickness;
    private int sourceNode = -1; // ソースノードID
    private int destNode = -1; // デスティネーションノードID
    private double previousSourcePressure = 0.0; // 前回のソース圧力値
    private double currentFlowBaselinePressure = 0.0; // 現在の要求フローでの基準圧力値（フロー増加判定用）
    private boolean currentFlowBaselineCaptured = false; // 現在フロー基準圧力がキャプチャ済みかどうか

    // 安定化フェーズ制御
    private boolean inStabilizationPhase = false; // 安定化フェーズ中かどうか
    private int stabilizationIterationCount = 0; // 安定化フェーズのイテレーション数

    // 二分探索用変数
    private double lowerBound = 0.0; // 下限
    private double upperBound; // 上限（要求フロー）
    private int binarySearchIteration = 0; // 二分探索回数

    // EPSセーブポイント機能
    private EPSSavePoint epsSavePoint = null; // EPSセーブポイント

    /**
     * コンストラクタ
     * @param serverController サーバーコントローラー
     * @param adjMatrix 隣接行列
     * @param link リンク情報
     * @param beaconCluster ビーコンクラスター
     * @param node ノード数
     */
    public BisectionalPressureGuidedEPSRouteSearcher(ServerController serverController, int[][] adjMatrix,
                                                    Link[][] link, BeaconCluster beaconCluster, int node) {
        super(serverController, adjMatrix, link, beaconCluster, node);
    }
    
    /**
     * 経路記録のタグを取得する
     * @return 経路記録のタグ
     */
    @Override
    protected String getRouteRecordTag() {
        return "runUAVFlow_BISECTIONAL_PGEPS";
    }

    /**
     * 残りの経路記録のタグを取得する
     * @return 残りの経路記録のタグ
     */
    @Override
    protected String getRemainingRouteRecordTag() {
        return "remainingFlow_BISECTIONAL_PGEPS";
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
        double eps = 1e-10;

        LogManager.getInstance().log("BisectionalPGEPS: Starting dynamic binary search EPS with requested flow " + requestedFlow);

        // 二分探索の初期化
        lowerBound = 0.0;
        upperBound = requestedFlow;
        currentFlow = requestedFlow; // 要求フローから開始
        
        LogManager.getInstance().log("BisectionalPGEPS: Initial bounds - Lower: " + lowerBound + ", Upper: " + upperBound + ", Starting flow: " + currentFlow);

        // 前回チューブ厚の初期化
        initializePreviousThickness();
        
        try {
        // EPSセーブポイントの初期化
        epsSavePoint = new EPSSavePoint(node);
        LogManager.getInstance().log("BisectionalPGEPS: EPSSavePoint initialized");
        
        // 動的二分探索EPS実行
        performDynamicBinarySearchEPS(client, eps);

            // 親クラスのUAV割り当て処理を実行
            LogManager.getInstance().log("BisectionalPGEPS: Setting up flow capacity arrays");
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) {
                        adjMatrix[i][j] = 1;
                        if (link[i][j].getQ_tubeFlow() > 0) {
                            Flow_Capacity[i][j] = Math.max(0.0, link[i][j].getCapacity() - link[i][j].getQ_tubeFlow());
                            int flow = (int) Math.floor(link[i][j].getQ_tubeFlow());
                            tubeFlow[i][j] = flow;
                        }
                    }
                }
            }

            // EPSフロー値から残りUAV数を計算
            int requiredUAVs = (int) requestedFlow;
            double epsAssignedFlow = currentFlow;
            int remainingUAVs = requiredUAVs - (int)epsAssignedFlow;

            LogManager.getInstance().log("BisectionalPGEPS: EPS assigned flow " + epsAssignedFlow + " out of " + requiredUAVs + " required UAVs");

            // Phase 4: 事業者の時間計測開始（経路割り当て開始時点）
            ClientTimeManager.getInstance().startClientTime(client.getId(), requiredUAVs);

            // Phase 4: 残りUAVがある場合は経路待ちキューに登録
            if (remainingUAVs > 0) {
                LogManager.getInstance().log("BisectionalPGEPS Phase 4: EPS割当=" + (int)epsAssignedFlow +
                    ", 経路待ち登録=" + remainingUAVs + " (要求UAV数=" + requiredUAVs + ")");

                // FlightSchedulerからPathWaitingManagerを取得
                PathWaitingManager pathWaitingManager = FlightScheduler.getInstance().getPathWaitingManager();

                // 経路待ちUAVをキューに登録（EPS割り当て分の次のIDから開始）
                int epsAssigned = (int) epsAssignedFlow;
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

                LogManager.getInstance().log("BisectionalPGEPS Phase 4: " + remainingUAVs + "機の経路待ち登録完了");
            } else {
                LogManager.getInstance().log("BisectionalPGEPS Phase 4: 全UAV(" + requiredUAVs + "機)をEPS割当完了、経路待ちなし");
            }

            // EPS割り当て分のUAVのみ飛行開始
            int epsUAVCount = (int) epsAssignedFlow;
            LogManager.getInstance().log("BisectionalPGEPS: Starting UAV flight assignment for " + epsUAVCount + " EPS UAVs (out of " + requiredUAVs + " total)");
            runUAVFlow(sourceNode, destNode, epsUAVCount, client, flyingUavQueue, uavQueue);
            
        } catch (Exception e) {
            LogManager.getInstance().error("Error in binary search EPS process: ", e);
            throw e;
        }
    }

    /**
     * フローテスト結果の状態
     */
    private enum FlowTestStatus {
        STABLE,                    // 安定
        UNSTABLE_PRESSURE,         // 圧力絶対値による不安定
        UNSTABLE_PRESSURE_CHANGE,  // 圧力変化率による不安定
        SOLVER_ERROR,              // ソルバーエラー
        MAX_ITERATIONS             // 最大イテレーション到達
    }

    /**
     * フローテスト結果
     */
    private static class FlowTestResult {
        FlowTestStatus status;
        double maxSourcePressure;
        int iterations;
        
        FlowTestResult(FlowTestStatus status, double maxSourcePressure, int iterations) {
            this.status = status;
            this.maxSourcePressure = maxSourcePressure;
            this.iterations = iterations;
        }
    }

    /**
     * 指定されたフローでの安定性をテストする
     * @param client クライアント
     * @param testFlow テストするフロー値
     * @param eps イプシロン値
     * @return テスト結果
     */
    private FlowTestResult testFlowStability(Client client, double testFlow, double eps) {
        // EPSの初期化
        initializeEPS();
        
        stableIterationCount = 0;
        previousSourcePressure = 0.0;
        currentFlowBaselinePressure = 0.0;
        currentFlowBaselineCaptured = false;
        
        double maxSourcePressure = 0.0;
        int ct = 0;

        try {
            // テストループ
            while (ct < MAX_ITERATIONS && stableIterationCount < REQUIRED_STABLE_ITERATIONS) {
                // 流入フロー値を設定
                Q_Kirchhoff[sourceNode] = testFlow;
                Q_Kirchhoff[destNode] = testFlow * NEG;
                
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
                    return new FlowTestResult(FlowTestStatus.SOLVER_ERROR, maxSourcePressure, ct);
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

                // 現在フロー基準圧力をキャプチャ（初回のみ）
                if (!currentFlowBaselineCaptured && sourceNode >= 0) {
                    currentFlowBaselinePressure = Math.abs(P_tubePressure[sourceNode]);
                    currentFlowBaselineCaptured = true;
                }

                // ソース圧力10%減少検知とフロー増加トリガー（二分探索方式）
                boolean shouldIncrease = checkShouldIncreaseFlow();
                if (shouldIncrease) {
                    // 二分探索テスト中は実際のフロー変更は行わず、ログ出力のみ
                    LogManager.getInstance().log("BisectionalPGEPS: Flow increase trigger detected at iteration " + (ct + 1) + " for flow " + testFlow + " (test only - no action taken during binary search)");
                }

                // ソース圧力チェック
                double currentSourcePressure = Math.abs(P_tubePressure[sourceNode]);
                maxSourcePressure = Math.max(maxSourcePressure, currentSourcePressure);

                // 圧力絶対値チェック
                if (currentSourcePressure >= SOURCE_PRESSURE_EMERGENCY) {
                    return new FlowTestResult(FlowTestStatus.UNSTABLE_PRESSURE, maxSourcePressure, ct);
                }

                // 圧力変化率チェック（10%増）
                if (previousSourcePressure > 0.0) {
                    double changeRate = (currentSourcePressure - previousSourcePressure) / previousSourcePressure;
                    if (changeRate >= SOURCE_PRESSURE_CHANGE_THRESHOLD) {
                        return new FlowTestResult(FlowTestStatus.UNSTABLE_PRESSURE_CHANGE, maxSourcePressure, ct);
                    }
                }

                // イテレーション毎の結果を記録（テスト段階でも記録）
                try {
                    ResultOutputManager.outputIterationSourcePressure(ct + 1, currentSourcePressure, serverController.getRunCounter());
                    ResultOutputManager.outputIterationFlow(ct + 1, testFlow, serverController.getRunCounter());
                } catch (IOException e) {
                    LogManager.getInstance().error("Failed to output test iteration data", e);
                }

                // 安定カウンター増加
                stableIterationCount++;

                // 前回のソース圧力を更新
                previousSourcePressure = currentSourcePressure;
                ct++;
            }

            // 最大イテレーション到達
            if (ct >= MAX_ITERATIONS) {
                return new FlowTestResult(FlowTestStatus.MAX_ITERATIONS, maxSourcePressure, ct);
            }

            // 安定収束
            return new FlowTestResult(FlowTestStatus.STABLE, maxSourcePressure, ct);

        } catch (Exception e) {
            LogManager.getInstance().error("Error during flow stability test for flow " + testFlow, e);
            return new FlowTestResult(FlowTestStatus.SOLVER_ERROR, maxSourcePressure, ct);
        }
    }

    /**
     * ソース圧力10%減少検知とフロー増加判定（引数なしバージョン）
     * @return フロー増加すべき場合true
     */
    private boolean checkShouldIncreaseFlow() {
        // 現在フロー基準圧力がキャプチャされていない場合は何もしない
        if (!currentFlowBaselineCaptured || sourceNode < 0 || currentFlowBaselinePressure == 0.0) {
            return false;
        }

        double currentPressure = Math.abs(P_tubePressure[sourceNode]);
        
        // 現在の要求フローでの基準圧力から指定%減少したかをチェック
        double reductionThreshold = currentFlowBaselinePressure * (1.0 - SOURCE_PRESSURE_REDUCTION_THRESHOLD); // 閾値計算
        
        return currentPressure < reductionThreshold;
    }

    /**
     * ソース圧力10%減少検知（フロー増加判定）
     * 最終EPS実行でのみ使用。現在のフローの1回目圧力と比較して10%減少している場合にフロー増加を判定
     * @param currentPressure 現在の圧力値
     * @param baselinePressure 基準圧力値（現在フローの1回目の圧力）
     * @return フロー増加すべき場合true
     */
    private boolean checkShouldIncreaseFlow(double currentPressure, double baselinePressure) {
        if (baselinePressure <= 0.0 || sourceNode < 0) {
            return false;
        }

        double reductionThreshold = baselinePressure * (1.0 - SOURCE_PRESSURE_REDUCTION_THRESHOLD);
        return currentPressure < reductionThreshold;
    }

    /**
     * 最適フローでの最終EPS実行
     * @param client クライアント
     * @param finalFlow 最終フロー値
     * @param eps イプシロン値
     */
    private void performFinalEPSRun(Client client, double finalFlow, double eps) throws IOException {
        LogManager.getInstance().log("BisectionalPGEPS: Performing final EPS run with flow " + finalFlow);
        
        // EPSの初期化
        initializeEPS();
        
        // *** 初期セーブポイントの作成 ***
        epsSavePoint.saveEPSState(link, pressureCoefficient, P_tubePressure, Q_Kirchhoff, D_tubeThickness_deltaT, Q_tubeFlow_sigmoidOutput);
        LogManager.getInstance().log("BisectionalPGEPS: Initial EPS savepoint created. " + epsSavePoint.getStatistics());
        
        stableIterationCount = 0;
        previousSourcePressure = 0.0;
        currentFlowBaselinePressure = 0.0;
        currentFlowBaselineCaptured = false;
        int ct = 0;

        // 最終EPSループ
        while (ct < MAX_ITERATIONS && stableIterationCount < REQUIRED_STABLE_ITERATIONS) {
            // 流入フロー値を設定
            Q_Kirchhoff[sourceNode] = finalFlow;
            Q_Kirchhoff[destNode] = finalFlow * NEG;
            
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
                LogManager.getInstance().log("BisectionalPGEPS: [Solver Failure] Final EPS pressure equation solving failed at iteration " + (ct + 1) +
                                           " with flow " + finalFlow + ". Applying SavePoint restoration and binary search flow reduction.");

                // EPSSavePointから復元（圧力100超過時と同様の処理）
                if (epsSavePoint != null && epsSavePoint.isAvailable()) {
                    epsSavePoint.restoreEPSState(link, pressureCoefficient, P_tubePressure, Q_Kirchhoff, D_tubeThickness_deltaT, Q_tubeFlow_sigmoidOutput);
                    LogManager.getInstance().log("BisectionalPGEPS: EPS state restored from savepoint after solver failure. " + epsSavePoint.getStatistics());
                } else {
                    initializeEPS();
                    LogManager.getInstance().log("BisectionalPGEPS: EPS initialized to default state after solver failure (no savepoint available).");
                }

                // 二分探索でフロー減少
                upperBound = finalFlow;
                double newFlow = Math.ceil((lowerBound + upperBound) / 2.0);

                if (newFlow != finalFlow && newFlow > lowerBound) {
                    finalFlow = newFlow;
                    stableIterationCount = 0;
                    currentFlowBaselineCaptured = false;
                    LogManager.getInstance().log("BisectionalPGEPS: Binary search flow reduction after solver failure: " + upperBound + " → " + finalFlow +
                                              " (new bounds: " + lowerBound + " - " + upperBound + ")");
                } else {
                    LogManager.getInstance().log("BisectionalPGEPS: Cannot reduce flow further after solver failure. Breaking loop.");
                    break;
                }
                ct++;
                continue;
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

            // 現在フロー基準圧力をキャプチャ（初回のみ）
            if (!currentFlowBaselineCaptured && sourceNode >= 0) {
                currentFlowBaselinePressure = Math.abs(P_tubePressure[sourceNode]);
                currentFlowBaselineCaptured = true;
                LogManager.getInstance().log("BisectionalPGEPS: Final EPS baseline pressure captured: " + String.format("%.4f", currentFlowBaselinePressure));
            }

            // ソース圧力10%減少検知とフロー増加ロジック（二分探索方式）
            boolean shouldIncrease = checkShouldIncreaseFlow();
            if (shouldIncrease && finalFlow < requestedFlow) {
                // 下限を更新して二分探索方式でフロー増加
                lowerBound = finalFlow;
                double newFlow = Math.ceil((lowerBound + Math.min(upperBound, requestedFlow)) / 2.0);
                
                // 要求フローを超えないように制限
                if (newFlow > requestedFlow) {
                    newFlow = requestedFlow;
                }
                
                double increaseAmount = newFlow - finalFlow;
                if (increaseAmount > 0) {
                    finalFlow = newFlow;
                    stableIterationCount = 0; // リセット
                    
                    // 基準圧力をリセット（新しいフロー値での基準を次回キャプチャ）
                    currentFlowBaselineCaptured = false;
                    
                    LogManager.getInstance().log("BisectionalPGEPS: Source pressure 10% reduction detected in final EPS " +
                                              " (baselinePressure=" + String.format("%.4f", currentFlowBaselinePressure) +
                                              ", currentPressure=" + String.format("%.4f", Math.abs(P_tubePressure[sourceNode])) +
                                              "). Binary search flow increase: " + (finalFlow - increaseAmount) + " → " + finalFlow +
                                              " (new bounds: " + lowerBound + " - " + Math.min(upperBound, requestedFlow) + ")");
                }
            }

            // ソース圧力チェック（最終実行でも安定性をチェック）
            double currentSourcePressure = Math.abs(P_tubePressure[sourceNode]);
            
            // 圧力絶対値チェック
            if (currentSourcePressure >= SOURCE_PRESSURE_EMERGENCY) {
                LogManager.getInstance().error("BisectionalPGEPS: CRITICAL ERROR - Final EPS pressure emergency detected at iteration " + (ct + 1) + " (pressure: " + currentSourcePressure + "). System termination required.");
                throw new RuntimeException("System termination: Pressure emergency in final EPS run");
            }

            // 負の圧力チェック
            if (P_tubePressure[sourceNode] < 0.0) {
                LogManager.getInstance().error("BisectionalPGEPS: CRITICAL ERROR - Negative pressure detected in final EPS at iteration " + (ct + 1) + " (pressure: " + P_tubePressure[sourceNode] + "). System termination required.");
                throw new RuntimeException("System termination: Negative pressure detected in final EPS run");
            }

            // 圧力変化率チェック（30%増）
            if (previousSourcePressure > 0.0) {
                double changeRate = (currentSourcePressure - previousSourcePressure) / previousSourcePressure;
                if (changeRate >= SOURCE_PRESSURE_CHANGE_THRESHOLD) {
                    LogManager.getInstance().log("BisectionalPGEPS: Final EPS run terminated due to pressure change rate at iteration " + (ct + 1) + " (change rate: " + (changeRate * 100) + "%)");
                    break;
                }
            }

            // 安定カウンター増加
            stableIterationCount++;
            
            // 前回のソース圧力を更新（次回の変化率計算用）
            previousSourcePressure = currentSourcePressure;

            // イテレーション毎の結果を記録
            try {
                if (sourceNode >= 0) {
                    ResultOutputManager.outputIterationSourcePressure(ct + 1, currentSourcePressure, serverController.getRunCounter());
                }
                ResultOutputManager.outputIterationFlow(ct + 1, finalFlow, serverController.getRunCounter());
            } catch (IOException e) {
                LogManager.getInstance().error("Failed to output iteration data", e);
            }

            // 結果のプロット
            if ((ct + 1) % PLOT == 0) {
                LogManager.getInstance().log("Final EPS Iteration: " + (ct + 1) + " with flow " + finalFlow +
                                          " (stable count: " + stableIterationCount + "/" + REQUIRED_STABLE_ITERATIONS + ")");
                ResultOutputManager.outputToPajek(client, eps, requestedFlow, ct, link, beaconCluster, node, serverController.getRunCounter());
                ResultOutputManager.outputToExcel(client, ct, link, node, serverController.getRunCounter(), finalFlow);
                ResultOutputManager.outputToTxt(client, ct, link, node, serverController.getRunCounter(), pressureCoefficient, P_tubePressure, finalFlow);
            }

            ct++;
        }

        // 最終流量の整数丸め（ソースノード流出を基準に丸める）
        LogManager.getInstance().log("BisectionalPGEPS: Performing final flow rounding after " + ct + " iterations");
        roundSourceOutflowsAndPropagate(link, sourceNode, destNode, (int) finalFlow);

        LogManager.getInstance().log("BisectionalPGEPS: Final EPS completed with flow " + finalFlow);

        // 最終結果を出力
        ResultOutputManager.outputToPajek(client, eps, requestedFlow, ct, link, beaconCluster, node, serverController.getRunCounter());
        ResultOutputManager.outputToExcel(client, ct, link, node, serverController.getRunCounter(), finalFlow);
        ResultOutputManager.outputToTxt(client, ct, link, node, serverController.getRunCounter(), pressureCoefficient, P_tubePressure, finalFlow);
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
        LogManager.getInstance().log("BisectionalPGEPS: Initialized previous thickness array with " + totalLinks + " links");
    }

    /**
     * EPSを初期化する（一番初めの状態に戻す）
     */
    private void initializeEPS() {
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
     * EPS開始時のリンクデータを詳細ログ出力する（デバッグ用）
     * メモリモードとRedisモードの差分を特定するため
     * @param client クライアント
     */
    private void logEPSInputData(Client client) {
        LogManager log = LogManager.getInstance();
        String workerMode = BoundaryController.getCurrentWorkerMode().toString();

        log.log("=== DEBUG: EPS Input Data for Client " + client.getId() + " ===");
        log.log("WorkerMode: " + workerMode);
        log.log("sourceNode: " + sourceNode + ", destNode: " + destNode);
        log.log("node count: " + node);

        // リンク統計
        int validLinkCount = 0;
        int zeroCapacityCount = 0;
        int negativeCapacityCount = 0;
        double minCapacity = Double.MAX_VALUE;
        double maxCapacity = Double.MIN_VALUE;
        double sumCapacity = 0.0;

        StringBuilder sampleLinks = new StringBuilder();
        int sampleCount = 0;

        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    validLinkCount++;
                    double cap = link[i][j].getCapacity();
                    double initCap = link[i][j].getInitCapacity();
                    double thickness = link[i][j].getD_tubeThickness();
                    double length = link[i][j].getL_tubeLength();

                    sumCapacity += cap;
                    if (cap < minCapacity) minCapacity = cap;
                    if (cap > maxCapacity) maxCapacity = cap;
                    if (cap == 0.0) zeroCapacityCount++;
                    if (cap < 0.0) negativeCapacityCount++;

                    // サンプルリンクを記録（最初の10個）
                    if (sampleCount < 10) {
                        sampleLinks.append(String.format("  Link[%d][%d]: cap=%.2f, initCap=%.2f, thickness=%.4f, length=%.2f%n",
                                i, j, cap, initCap, thickness, length));
                        sampleCount++;
                    }
                }
            }
        }

        log.log("Valid links: " + validLinkCount);
        log.log("Zero capacity links: " + zeroCapacityCount);
        log.log("Negative capacity links: " + negativeCapacityCount);
        if (validLinkCount > 0) {
            log.log(String.format("Capacity stats: min=%.2f, max=%.2f, avg=%.2f",
                    minCapacity, maxCapacity, sumCapacity / validLinkCount));
        }
        log.log("Sample links:\n" + sampleLinks.toString());
        log.log("=== END DEBUG ===");
    }

    /**
     * PSによる追加計算を実行する
     * @param client クライアント
     * @param remainingUAVs 残りUAV数
     * @param sourceNode ソースノード
     * @param destNode デスティネーションノード
     * @param eps イプシロン
     * @throws IOException 入出力例外
     */
    private void performPSComputation(Client client, int remainingUAVs, int sourceNode, int destNode, double eps) throws IOException {
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
        
        LogManager.getInstance().log("BisectionalPGEPS: PS starting with " + remainingUAVs + " UAVs flow");
        
        // PS用500回イテレーション
        for (int psIter = 0; psIter < 500; psIter++) {
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
                LogManager.getInstance().log("BisectionalPGEPS: PS pressure equation solving failed at iteration " + (psIter + 1));
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

            // 最終イテレーションで流量を整数に丸める（ソースノード流出を基準に）
            if (psIter == 499) {
                roundSourceOutflowsAndPropagate(psLink, sourceNode, destNode, remainingUAVs);

                // 丸め後のソース流出合計を検証
                double finalSum = 0.0;
                for (int j = 0; j < node; j++) {
                    if (psLink[sourceNode][j].getL_tubeLength() != INF && psLink[sourceNode][j].getQ_tubeFlow() > 0) {
                        finalSum += psLink[sourceNode][j].getQ_tubeFlow();
                    }
                }
                LogManager.getInstance().log("BisectionalPGEPS: PS source outflow sum after rounding = " + finalSum + " (expected: " + remainingUAVs + ")");
            }

            // シグモイド関数
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (psLink[i][j].getL_tubeLength() != INF) {
                        ps_Q_tubeFlow_sigmoidOutput[i][j] = Math.pow(Math.abs(psLink[i][j].getQ_tubeFlow()), GAMMA) / (1 + Math.pow(Math.abs(psLink[i][j].getQ_tubeFlow()), GAMMA));
                    }
                }
            }

            // PSのチューブ厚更新
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
        }

        // PS結果をpsTempResultsに保存
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (psLink[i][j].getL_tubeLength() != INF) {
                    psTempResults[i][j] = psLink[i][j].getQ_tubeFlow();
                }
            }
        }

        // EPSとPSの結果を統合
        LogManager.getInstance().log("BisectionalPGEPS: Integrating EPS and PS results");
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

        // 統合後に再度整数への丸め込みを実行（ソースノード流出を基準に）
        LogManager.getInstance().log("BisectionalPGEPS: Applying final integer rounding after EPS+PS integration");
        int totalRequiredFlow = (int) requestedFlow;
        roundSourceOutflowsAndPropagate(link, sourceNode, destNode, totalRequiredFlow);

        // 丸め後のソース流出合計を検証
        double integratedSourceOutflow = 0.0;
        for (int j = 0; j < node; j++) {
            if (link[sourceNode][j].getL_tubeLength() != INF && link[sourceNode][j].getQ_tubeFlow() > 0) {
                integratedSourceOutflow += link[sourceNode][j].getQ_tubeFlow();
            }
        }
        LogManager.getInstance().log("BisectionalPGEPS: Integrated source outflow sum after rounding = " + integratedSourceOutflow + " (expected: " + totalRequiredFlow + ")");

        LogManager.getInstance().log("BisectionalPGEPS: Final integer rounding completed. All flows are now exact integers.");

        // 統合完了後に、Flow_CapacityとtubeFlowを統合結果で更新
        LogManager.getInstance().log("BisectionalPGEPS: Updating Flow_Capacity and tubeFlow arrays with integrated results");
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    if (link[i][j].getQ_tubeFlow() > 0) {
                        Flow_Capacity[i][j] = Math.max(0.0, link[i][j].getCapacity() - link[i][j].getQ_tubeFlow());
                        int flow = (int) Math.floor(link[i][j].getQ_tubeFlow());
                        tubeFlow[i][j] = flow;
                        LogManager.getInstance().log("Updated arrays: Link(" + i + "," + j + ") - Flow_Capacity=" + Flow_Capacity[i][j] + ", tubeFlow=" + tubeFlow[i][j]);
                    }
                }
            }
        }
        LogManager.getInstance().log("BisectionalPGEPS: Flow arrays update completed");
    }

    /**
     * 動的二分探索EPS実行
     * EPSを継続実行しながら、圧力検知に基づいて二分探索でフロー値を動的に調整
     * @param client クライアント
     * @param eps イプシロン値
     * @throws IOException 入出力例外
     */
    private void performDynamicBinarySearchEPS(Client client, double eps) throws IOException {
        LogManager.getInstance().log("BisectionalPGEPS: Starting dynamic binary search EPS with initial flow " + currentFlow);

        // EPSの初期化
        initializeEPS();

        // === DEBUG: EPS開始時のリンクデータ詳細ログ ===
        logEPSInputData(client);

        // *** 初期セーブポイントの作成 ***
        epsSavePoint.saveEPSState(link, pressureCoefficient, P_tubePressure, Q_Kirchhoff, D_tubeThickness_deltaT, Q_tubeFlow_sigmoidOutput);
        LogManager.getInstance().log("BisectionalPGEPS: Initial EPS savepoint created. " + epsSavePoint.getStatistics());
        
        stableIterationCount = 0;
        previousSourcePressure = 0.0;
        currentFlowBaselinePressure = 0.0;
        currentFlowBaselineCaptured = false;
        int ct = 0;

        // 動的二分探索EPSループ
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
                LogManager.getInstance().log("BisectionalPGEPS: [Solver Failure] Pressure equation solving failed at iteration " + (ct + 1) +
                                           " with flow " + currentFlow + ". Applying SavePoint restoration and binary search flow reduction.");

                // EPSSavePointから復元（圧力100超過時と同様の処理）
                if (epsSavePoint != null && epsSavePoint.isAvailable()) {
                    epsSavePoint.restoreEPSState(link, pressureCoefficient, P_tubePressure, Q_Kirchhoff, D_tubeThickness_deltaT, Q_tubeFlow_sigmoidOutput);
                    LogManager.getInstance().log("BisectionalPGEPS: EPS state restored from savepoint after solver failure. " + epsSavePoint.getStatistics());
                } else {
                    initializeEPS();
                    LogManager.getInstance().log("BisectionalPGEPS: EPS initialized to default state after solver failure (no savepoint available).");
                }

                // 二分探索でフロー減少
                upperBound = currentFlow;
                double newFlow = Math.ceil((lowerBound + upperBound) / 2.0);

                if (newFlow != currentFlow && newFlow > lowerBound) {
                    LogManager.getInstance().log("BisectionalPGEPS: Binary search flow reduction after solver failure: " + currentFlow + " → " + newFlow +
                                               " (new bounds: " + lowerBound + " - " + upperBound + ")");
                    currentFlow = newFlow;
                    stableIterationCount = 0;
                    currentFlowBaselineCaptured = false;

                    // 安定化フェーズに入る
                    inStabilizationPhase = true;
                    stabilizationIterationCount = 0;
                } else {
                    // フロー減少できない場合（最小フロー到達）
                    LogManager.getInstance().log("BisectionalPGEPS: Cannot reduce flow further after solver failure (lowerBound=" + lowerBound +
                                               ", currentFlow=" + currentFlow + "). Breaking loop.");
                    break;
                }

                ct++;
                continue;
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

            // 現在フロー基準圧力をキャプチャ（初回のみ）
            if (!currentFlowBaselineCaptured && sourceNode >= 0) {
                currentFlowBaselinePressure = Math.abs(P_tubePressure[sourceNode]);
                currentFlowBaselineCaptured = true;
                LogManager.getInstance().log("BisectionalPGEPS: Baseline pressure captured for flow " + currentFlow + ": " + String.format("%.4f", currentFlowBaselinePressure));
            }

            // ソース圧力チェック（優先度順で実行）
            double currentSourcePressure = Math.abs(P_tubePressure[sourceNode]);
            boolean flowChanged = false;
            
            // デバッグ用ログ出力（常に出力）
            boolean shouldIncrease = checkShouldIncreaseFlow();
            double tenPercentReduction = currentFlowBaselineCaptured ? currentFlowBaselinePressure * 0.9 : 0.0;
            
            // 100回に1回だけ詳細ログ出力（ログ量を抑制）
            if ((ct + 1) % 100 == 0 || ct < 10) {
                LogManager.getInstance().log("BisectionalPGEPS: [Debug] Flow increase check at iteration " + (ct + 1) + 
                                           " - baselineCaptured=" + currentFlowBaselineCaptured +
                                           ", baselinePressure=" + String.format("%.4f", currentFlowBaselinePressure) +
                                           ", currentPressure=" + String.format("%.4f", currentSourcePressure) +
                                           ", threshold=" + String.format("%.4f", tenPercentReduction) +
                                           ", shouldIncrease=" + shouldIncrease +
                                           ", currentFlow=" + currentFlow + 
                                           ", requestedFlow=" + requestedFlow +
                                           ", sourceNode=" + sourceNode);
            }
            
            // 安定化フェーズ中の場合は異常検知をスキップ
            if (!inStabilizationPhase) {
                // 【優先度1】圧力絶対値チェック（100以上でフロー減少）
                if (currentSourcePressure >= SOURCE_PRESSURE_EMERGENCY) {
                    LogManager.getInstance().log("BisectionalPGEPS: [Priority 1] Pressure emergency detected at iteration " + (ct + 1) + 
                                               " (pressure: " + String.format("%.2f", currentSourcePressure) + "). Applying binary search flow reduction.");
                    
                    // 上限を現在のフロー値に更新
                    upperBound = currentFlow;
                    double newFlow = Math.ceil((lowerBound + upperBound) / 2.0);
                    
                    if (newFlow != currentFlow && newFlow > lowerBound) {
                        LogManager.getInstance().log("BisectionalPGEPS: Binary search flow reduction: " + currentFlow + " → " + newFlow +
                                                   " (new bounds: " + lowerBound + " - " + upperBound + ")");
                        
                        currentFlow = newFlow;
                        stableIterationCount = 0; // リセット
                        currentFlowBaselineCaptured = false; // 基準圧力をリセット
                        flowChanged = true;
                        
                        // *** フロー減少後のEPS状態復元 ***
                        if (epsSavePoint.isAvailable()) {
                            epsSavePoint.restoreEPSState(link, pressureCoefficient, P_tubePressure, Q_Kirchhoff, D_tubeThickness_deltaT, Q_tubeFlow_sigmoidOutput);
                            LogManager.getInstance().log("BisectionalPGEPS: EPS state restored from savepoint after flow reduction due to pressure emergency. " + epsSavePoint.getStatistics());
                        } else {
                            // セーブポイントがない場合（流入フロー初回等）は初期状態に戻す
                            LogManager.getInstance().log("BisectionalPGEPS: No savepoint available, restoring EPS to initial state after flow reduction");
                            initializeEPS();
                        }
                        
                        // フロー減少後に安定化フェーズを開始
                        inStabilizationPhase = true;
                        stabilizationIterationCount = 0;
                        LogManager.getInstance().log("BisectionalPGEPS: Entering stabilization phase for " + STABILIZATION_PHASE_ITERATIONS + " iterations");
                    } else {
                        // フロー変更がない場合はEPS復元のみ実行
                        if (epsSavePoint.isAvailable()) {
                            epsSavePoint.restoreEPSState(link, pressureCoefficient, P_tubePressure, Q_Kirchhoff, D_tubeThickness_deltaT, Q_tubeFlow_sigmoidOutput);
                            LogManager.getInstance().log("BisectionalPGEPS: EPS state restored from savepoint (no flow change required). " + epsSavePoint.getStatistics());
                        } else {
                            LogManager.getInstance().log("BisectionalPGEPS: No savepoint available, restoring EPS to initial state (no flow change required)");
                            initializeEPS();
                        }
                        
                        // EPS復元後は安定化フェーズに入る
                        LogManager.getInstance().log("BisectionalPGEPS: EPS state restored but no flow change required. Entering stabilization phase for " + STABILIZATION_PHASE_ITERATIONS + " iterations");
                        inStabilizationPhase = true;
                        stabilizationIterationCount = 0;
                        stableIterationCount = 0; // リセット
                        currentFlowBaselineCaptured = false; // 基準圧力をリセット
                        flowChanged = true; // 復元により状態が変化したことを示す
                    }
                }
                // 【優先度2】圧力増加率チェック（現在フロー基準圧力からの30%増加でフロー減少）
                else if (currentFlowBaselineCaptured && currentFlowBaselinePressure > 0.0) {
                    double increaseThreshold = currentFlowBaselinePressure * (1.0 + SOURCE_PRESSURE_CHANGE_THRESHOLD);
                    if (currentSourcePressure >= increaseThreshold) {
                        double increaseRate = (currentSourcePressure - currentFlowBaselinePressure) / currentFlowBaselinePressure;
                        LogManager.getInstance().log("BisectionalPGEPS: [Priority 2] Pressure increase detected at iteration " + (ct + 1) + 
                                                   " (baselinePressure=" + String.format("%.4f", currentFlowBaselinePressure) + 
                                                   ", currentPressure=" + String.format("%.4f", currentSourcePressure) + 
                                                   ", increase rate: " + String.format("%.2f%%", increaseRate * 100) + "). Applying binary search flow reduction.");
                        
                        // 上限を現在のフロー値に更新
                        upperBound = currentFlow;
                        double newFlow = Math.ceil((lowerBound + upperBound) / 2.0);
                        
                        if (newFlow != currentFlow && newFlow > lowerBound) {
                            LogManager.getInstance().log("BisectionalPGEPS: Binary search flow reduction: " + currentFlow + " → " + newFlow +
                                                       " (new bounds: " + lowerBound + " - " + upperBound + ")");
                            
                            currentFlow = newFlow;
                            stableIterationCount = 0; // リセット
                            currentFlowBaselineCaptured = false; // 基準圧力をリセット
                            flowChanged = true;
                            
                            // フロー減少後に安定化フェーズを開始
                            inStabilizationPhase = true;
                            stabilizationIterationCount = 0;
                            LogManager.getInstance().log("BisectionalPGEPS: Entering stabilization phase for " + STABILIZATION_PHASE_ITERATIONS + " iterations");
                        }
                    }
                }
            } else {
                // 安定化フェーズ中の処理
                stabilizationIterationCount++;
                LogManager.getInstance().log("BisectionalPGEPS: Stabilization phase iteration " + stabilizationIterationCount + "/" + STABILIZATION_PHASE_ITERATIONS + " (pressure emergency detection only)");
                
                // 安定化フェーズ中でも圧力絶対値100の異常検知のみ作動
                if (currentSourcePressure >= SOURCE_PRESSURE_EMERGENCY) {
                    LogManager.getInstance().log("BisectionalPGEPS: [Priority 1 - Stabilization Phase] Pressure emergency detected during stabilization at iteration " + (ct + 1) + 
                                               " (pressure: " + String.format("%.2f", currentSourcePressure) + "). Applying binary search flow reduction.");
                    
                    // 上限を現在のフロー値に更新
                    upperBound = currentFlow;
                    double newFlow = Math.ceil((lowerBound + upperBound) / 2.0);
                    
                    if (newFlow != currentFlow && newFlow > lowerBound) {
                        LogManager.getInstance().log("BisectionalPGEPS: Binary search flow reduction during stabilization: " + currentFlow + " → " + newFlow +
                                                   " (new bounds: " + lowerBound + " - " + upperBound + ")");
                        
                        double oldFlow = currentFlow;
                        currentFlow = newFlow;
                        stableIterationCount = 0; // リセット
                        currentFlowBaselineCaptured = false; // 基準圧力をリセット
                        flowChanged = true;
                        
                        // *** フロー減少後のEPS状態復元（安定化フェーズ中も同様） ***
                        if (epsSavePoint.isAvailable()) {
                            epsSavePoint.restoreEPSState(link, pressureCoefficient, P_tubePressure, Q_Kirchhoff, D_tubeThickness_deltaT, Q_tubeFlow_sigmoidOutput);
                            LogManager.getInstance().log("BisectionalPGEPS: EPS state restored from savepoint after flow reduction during stabilization. " + epsSavePoint.getStatistics());
                        } else {
                            LogManager.getInstance().log("BisectionalPGEPS: No savepoint available, restoring EPS to initial state after flow reduction during stabilization");
                            initializeEPS();
                        }
                        
                        // 安定化フェーズをリセット（新しいフロー値で再開始）
                        stabilizationIterationCount = 0;
                        LogManager.getInstance().log("BisectionalPGEPS: Flow reduced from " + oldFlow + " to " + currentFlow + " during stabilization. Restarting stabilization phase for " + STABILIZATION_PHASE_ITERATIONS + " iterations (EPS state restored)");
                    }
                } else {
                    // 安定化フェーズ完了チェック
                    if (stabilizationIterationCount >= STABILIZATION_PHASE_ITERATIONS) {
                        inStabilizationPhase = false;
                        stabilizationIterationCount = 0;
                        LogManager.getInstance().log("BisectionalPGEPS: Stabilization phase completed. Resuming full anomaly detection.");
                        
                        // 安定化フェーズ後の安全性チェック
                        if (currentSourcePressure >= SOURCE_PRESSURE_EMERGENCY) {
                            LogManager.getInstance().error("BisectionalPGEPS: CRITICAL ERROR - Pressure still >= 100 after stabilization phase (pressure: " + String.format("%.2f", currentSourcePressure) + "). System termination required.");
                            throw new RuntimeException("System termination: Pressure emergency persists after stabilization phase");
                        }
                        
                        if (P_tubePressure[sourceNode] < 0.0) {
                            LogManager.getInstance().error("BisectionalPGEPS: CRITICAL ERROR - Negative pressure detected after stabilization phase (pressure: " + P_tubePressure[sourceNode] + "). System termination required.");
                            throw new RuntimeException("System termination: Negative pressure detected after stabilization phase");
                        }
                    }
                }
            }
            
            // 【優先度3】圧力減少率チェック（現在フロー基準圧力から30%減少でフロー増加）- 安定化フェーズ中はスキップ
            if (!inStabilizationPhase && !flowChanged && shouldIncrease && currentFlow < requestedFlow) {
                // デバッグログ
                LogManager.getInstance().log("BisectionalPGEPS: [Priority 3 DEBUG] Flow increase conditions - shouldIncrease=" + shouldIncrease + 
                                           ", currentFlow=" + currentFlow + ", requestedFlow=" + requestedFlow +
                                           ", lowerBound=" + lowerBound + ", upperBound=" + upperBound);
                
                // 下限を現在のフロー値に更新
                lowerBound = currentFlow;
                double newFlow = Math.ceil((lowerBound + Math.min(upperBound, requestedFlow)) / 2.0);
                
                // 要求フローを超えないように制限
                if (newFlow > requestedFlow) {
                    newFlow = requestedFlow;
                }
                
                LogManager.getInstance().log("BisectionalPGEPS: [Priority 3 DEBUG] Calculated newFlow=" + newFlow + 
                                           " (newFlow != currentFlow: " + (newFlow != currentFlow) + 
                                           ", newFlow <= requestedFlow: " + (newFlow <= requestedFlow) + ")");
                
                if (newFlow != currentFlow && newFlow <= requestedFlow) {
                    LogManager.getInstance().log("BisectionalPGEPS: [Priority 3] Source pressure 30% reduction detected at iteration " + (ct + 1) + 
                                               " (baselinePressure=" + String.format("%.4f", currentFlowBaselinePressure) +
                                               ", currentPressure=" + String.format("%.4f", currentSourcePressure) +
                                               "). Binary search flow increase: " + currentFlow + " → " + newFlow +
                                               " (new bounds: " + lowerBound + " - " + Math.min(upperBound, requestedFlow) + ")");
                    
                    // *** フロー増加直前のEPSセーブポイント保存 ***
                    epsSavePoint.saveEPSState(link, pressureCoefficient, P_tubePressure, Q_Kirchhoff, D_tubeThickness_deltaT, Q_tubeFlow_sigmoidOutput);
                    LogManager.getInstance().log("BisectionalPGEPS: EPS savepoint created before flow increase from " + currentFlow + " to " + newFlow + ". " + epsSavePoint.getStatistics());
                    
                    currentFlow = newFlow;
                    stableIterationCount = 0; // リセット
                    currentFlowBaselineCaptured = false; // 基準圧力をリセット
                    flowChanged = true;
                    
                    // フロー増加後に安定化フェーズを開始（EPSは初期化しない）
                    inStabilizationPhase = true;
                    stabilizationIterationCount = 0;
                    LogManager.getInstance().log("BisectionalPGEPS: Flow increased to " + currentFlow + ". Entering stabilization phase for " + STABILIZATION_PHASE_ITERATIONS + " iterations (EPS state preserved)");
                } else {
                    LogManager.getInstance().log("BisectionalPGEPS: [Priority 3 DEBUG] Flow increase skipped - newFlow=" + newFlow + 
                                               ", currentFlow=" + currentFlow + ", requestedFlow=" + requestedFlow +
                                               ", condition check: newFlow != currentFlow=" + (newFlow != currentFlow) +
                                               ", newFlow <= requestedFlow=" + (newFlow <= requestedFlow));
                }
            } else if (!flowChanged && (ct + 1) % 100 == 0) {
                // 100回に1回だけログ出力（フロー増加しない理由）
                LogManager.getInstance().log("BisectionalPGEPS: [Priority 3 DEBUG] Flow increase not triggered - inStabilizationPhase=" + inStabilizationPhase + 
                                           ", shouldIncrease=" + shouldIncrease + ", currentFlow=" + currentFlow + ", requestedFlow=" + requestedFlow + ", flowChanged=" + flowChanged);
            }

            // 安定カウンター増加（フロー変更されなかった場合のみ）
            if (!flowChanged) {
                stableIterationCount++;
            }
            
            // 前回のソース圧力を更新
            previousSourcePressure = currentSourcePressure;

            // イテレーション毎の結果を記録
            try {
                if (sourceNode >= 0) {
                    ResultOutputManager.outputIterationSourcePressure(ct + 1, currentSourcePressure, serverController.getRunCounter());
                }
                ResultOutputManager.outputIterationFlow(ct + 1, currentFlow, serverController.getRunCounter());
            } catch (IOException e) {
                LogManager.getInstance().error("Failed to output iteration data", e);
            }

            // 結果のプロット
            if ((ct + 1) % PLOT == 0) {
                LogManager.getInstance().log("Dynamic Binary Search EPS Iteration: " + (ct + 1) + " with flow " + currentFlow +
                                          " (stable count: " + stableIterationCount + "/" + REQUIRED_STABLE_ITERATIONS + ")");
                ResultOutputManager.outputToPajek(client, eps, requestedFlow, ct, link, beaconCluster, node, serverController.getRunCounter());
                ResultOutputManager.outputToExcel(client, ct, link, node, serverController.getRunCounter(), currentFlow);
                ResultOutputManager.outputToTxt(client, ct, link, node, serverController.getRunCounter(), pressureCoefficient, P_tubePressure, currentFlow);
            }

            ct++;
        }

        // 最終流量の整数丸め（ソースノード流出を基準に丸める）
        LogManager.getInstance().log("BisectionalPGEPS: Performing final flow rounding after " + ct + " iterations");
        roundSourceOutflowsAndPropagate(link, sourceNode, destNode, (int) currentFlow);

        // 丸め後のソース流出合計を検証
        double sourceOutflowSum = 0.0;
        for (int j = 0; j < node; j++) {
            if (link[sourceNode][j].getL_tubeLength() != INF && link[sourceNode][j].getQ_tubeFlow() > 0) {
                sourceOutflowSum += link[sourceNode][j].getQ_tubeFlow();
            }
        }
        LogManager.getInstance().log("BisectionalPGEPS: Source outflow sum after rounding = " + sourceOutflowSum + " (expected: " + (int) currentFlow + ")");

        LogManager.getInstance().log("BisectionalPGEPS: Dynamic binary search EPS completed with optimal flow " + currentFlow);

        // 最終結果を出力
        ResultOutputManager.outputToPajek(client, eps, requestedFlow, ct, link, beaconCluster, node, serverController.getRunCounter());
        ResultOutputManager.outputToExcel(client, ct, link, node, serverController.getRunCounter(), currentFlow);
        ResultOutputManager.outputToTxt(client, ct, link, node, serverController.getRunCounter(), pressureCoefficient, P_tubePressure, currentFlow);
    }

    /**
     * ネットワーク全体のフローを整数に丸める（流量保存則を維持）
     * BFSでソースからデスティネーションまで各ノードを処理し、
     * 各ノードで流入合計に合わせて流出を整数に丸める
     *
     * @param linkArray リンク配列
     * @param srcNode ソースノード
     * @param dstNode デスティネーションノード
     * @param targetFlow ターゲットフロー（整数）
     */
    @Override
    protected void roundSourceOutflowsAndPropagate(Link[][] linkArray, int srcNode, int dstNode, int targetFlow) {
        // Step 1: ソースノードの流出を丸める
        java.util.List<Integer> sourceOutLinks = new java.util.ArrayList<>();
        java.util.List<Double> sourceOutFlows = new java.util.ArrayList<>();

        for (int j = 0; j < node; j++) {
            if (linkArray[srcNode][j].getL_tubeLength() != INF && linkArray[srcNode][j].getQ_tubeFlow() > 0) {
                sourceOutLinks.add(j);
                sourceOutFlows.add(linkArray[srcNode][j].getQ_tubeFlow());
            }
        }

        if (sourceOutFlows.isEmpty()) {
            LogManager.getInstance().log("BisectionalPGEPS: No positive source outflows found. Skipping rounding.");
            return;
        }

        // ソース流出を丸める
        double[] sourceFlowsArray = sourceOutFlows.stream().mapToDouble(Double::doubleValue).toArray();
        MathUtils.roundWithTargetSum(sourceFlowsArray, targetFlow);

        // ソースリンクに適用
        for (int i = 0; i < sourceOutLinks.size(); i++) {
            int linkDest = sourceOutLinks.get(i);
            double newFlow = sourceFlowsArray[i];
            linkArray[srcNode][linkDest].setQ_tubeFlow(newFlow);
            if (linkArray[linkDest][srcNode].getL_tubeLength() != INF) {
                linkArray[linkDest][srcNode].setQ_tubeFlow(-newFlow);
            }
        }

        // Step 2: BFSで中間ノードを処理
        java.util.Set<Integer> visited = new java.util.HashSet<>();
        java.util.Queue<Integer> queue = new java.util.LinkedList<>();
        visited.add(srcNode);

        // ソースの隣接ノードをキューに追加
        for (int i = 0; i < sourceOutLinks.size(); i++) {
            int dest = sourceOutLinks.get(i);
            if (sourceFlowsArray[i] > 0 && dest != dstNode) {
                queue.add(dest);
            }
        }

        while (!queue.isEmpty()) {
            int currentNode = queue.poll();
            if (visited.contains(currentNode)) {
                continue;
            }
            visited.add(currentNode);

            // 現在のノードへの整数流入量を計算（丸め済みの正の流入のみ）
            int integerInflow = 0;
            for (int i = 0; i < node; i++) {
                if (linkArray[i][currentNode].getL_tubeLength() != INF) {
                    double flow = linkArray[i][currentNode].getQ_tubeFlow();
                    if (flow > 0) {
                        integerInflow += (int) Math.round(flow);
                    }
                }
            }

            // 現在のノードからの流出を収集
            java.util.List<Integer> outLinks = new java.util.ArrayList<>();
            java.util.List<Double> outFlows = new java.util.ArrayList<>();
            for (int j = 0; j < node; j++) {
                if (linkArray[currentNode][j].getL_tubeLength() != INF && linkArray[currentNode][j].getQ_tubeFlow() > 0) {
                    outLinks.add(j);
                    outFlows.add(linkArray[currentNode][j].getQ_tubeFlow());
                }
            }

            if (!outFlows.isEmpty() && integerInflow > 0) {
                // 流出を流入に合わせて丸める
                double[] outFlowsArray = outFlows.stream().mapToDouble(Double::doubleValue).toArray();
                MathUtils.roundWithTargetSum(outFlowsArray, integerInflow);

                // 適用
                for (int i = 0; i < outLinks.size(); i++) {
                    int linkDest = outLinks.get(i);
                    double newFlow = outFlowsArray[i];
                    linkArray[currentNode][linkDest].setQ_tubeFlow(newFlow);
                    if (linkArray[linkDest][currentNode].getL_tubeLength() != INF) {
                        linkArray[linkDest][currentNode].setQ_tubeFlow(-newFlow);
                    }

                    // 次のノードをキューに追加（デスティネーション以外）
                    if (newFlow > 0 && linkDest != dstNode && !visited.contains(linkDest)) {
                        queue.add(linkDest);
                    }
                }
            } else if (integerInflow == 0) {
                // 流入が0になった場合、流出も0にする
                for (int j = 0; j < node; j++) {
                    if (linkArray[currentNode][j].getL_tubeLength() != INF && linkArray[currentNode][j].getQ_tubeFlow() > 0) {
                        linkArray[currentNode][j].setQ_tubeFlow(0.0);
                        if (linkArray[j][currentNode].getL_tubeLength() != INF) {
                            linkArray[j][currentNode].setQ_tubeFlow(0.0);
                        }
                    }
                }
            }
        }

        // Step 3: フロー保存則の検証と修正（中間ノードのみ）
        // BFS処理後も不整合が残っている場合に修正
        boolean hasViolation = true;
        int maxIterations = 10; // 無限ループ防止
        int iteration = 0;

        while (hasViolation && iteration < maxIterations) {
            hasViolation = false;
            iteration++;

            for (int currentNode = 0; currentNode < node; currentNode++) {
                // ソースとデスティネーションはスキップ
                if (currentNode == srcNode || currentNode == dstNode) {
                    continue;
                }

                // 入力フロー計算
                double actualInflow = 0.0;
                for (int i = 0; i < node; i++) {
                    if (linkArray[i][currentNode].getL_tubeLength() != INF) {
                        double flow = linkArray[i][currentNode].getQ_tubeFlow();
                        if (flow > 0) {
                            actualInflow += flow;
                        }
                    }
                }

                // 出力フロー計算
                double actualOutflow = 0.0;
                java.util.List<Integer> outLinks = new java.util.ArrayList<>();
                java.util.List<Double> outFlows = new java.util.ArrayList<>();
                for (int j = 0; j < node; j++) {
                    if (linkArray[currentNode][j].getL_tubeLength() != INF) {
                        double flow = linkArray[currentNode][j].getQ_tubeFlow();
                        if (flow > 0) {
                            actualOutflow += flow;
                            outLinks.add(j);
                            outFlows.add(flow);
                        }
                    }
                }

                // フロー保存則違反チェック
                int intInflow = (int) Math.round(actualInflow);
                int intOutflow = (int) Math.round(actualOutflow);

                if (intInflow != intOutflow && intInflow > 0 && !outFlows.isEmpty()) {
                    hasViolation = true;
                    LogManager.getInstance().log("BisectionalPGEPS: Flow conservation violation at node " + currentNode +
                                               " (inflow=" + intInflow + ", outflow=" + intOutflow + "). Correcting...");

                    // 出力フローを入力フローに合わせて再丸め
                    double[] outFlowsArray = outFlows.stream().mapToDouble(Double::doubleValue).toArray();
                    MathUtils.roundWithTargetSum(outFlowsArray, intInflow);

                    // 適用
                    for (int i = 0; i < outLinks.size(); i++) {
                        int linkDest = outLinks.get(i);
                        double newFlow = outFlowsArray[i];
                        linkArray[currentNode][linkDest].setQ_tubeFlow(newFlow);
                        if (linkArray[linkDest][currentNode].getL_tubeLength() != INF) {
                            linkArray[linkDest][currentNode].setQ_tubeFlow(-newFlow);
                        }
                    }
                }
            }
        }

        if (iteration > 1) {
            LogManager.getInstance().log("BisectionalPGEPS: Flow conservation correction completed after " + iteration + " iterations");
        }
    }

    /**
     * 下流のフローを再帰的に調整する
     * @param psLink PSリンク配列
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
