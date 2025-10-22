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

import java.io.IOException;
import java.util.Queue;

/**
 * ハイブリッドPhysarumSolverルートサーチャー
 * インクリメンタルフロー方式でEPSを適用し、段階的に流入フローを増加させる
 */
public class HybridPhysarumSolverRouteSearcher extends ExtendedPhysarumSolverRouteSearcher {

    // 定数
    private static final int ITERATIONS_PER_FLOW = 50; // 各フロー値ごとのイテレーション数
    private static final int STABILIZATION_ITERATIONS = 500; // 異常検知後の安定化イテレーション数
    private static final double INIT_THICKNESS = 0.5; // 初期チューブ厚
    private static final double INIT_LENGTH = 1.0; // 初期チューブ長

    // 現在のフロー値
    private double currentFlow = 1.0; // 1から開始
    private int outputIterationCursor = 0;

    /**
     * コンストラクタ
     * @param serverController サーバーコントローラー
     * @param adjMatrix 隣接行列
     * @param link リンク情報
     * @param beaconCluster ビーコンクラスター
     * @param node ノード数
     */
    public HybridPhysarumSolverRouteSearcher(ServerController serverController, int[][] adjMatrix, 
                                            Link[][] link, BeaconCluster beaconCluster, int node) {
        super(serverController, adjMatrix, link, beaconCluster, node);
    }
    
    /**
     * 経路記録のタグを取得する
     * @return 経路記録のタグ
     */
    @Override
    protected String getRouteRecordTag() {
        return "runUAVFlow_HYBRID";
    }

    /**
     * 残りの経路記録のタグを取得する
     * @return 残りの経路記録のタグ
     */
    @Override
    protected String getRemainingRouteRecordTag() {
        return "remainingFlow_HYBRID";
    }

    @Override
    public void search(Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, int numLoop) throws IOException {
        // 通常のUAV割り当てのみを行う場合は親クラスのメソッドを呼び出す
        if (numLoop == 0) {
            super.search(client, flyingUavQueue, uavQueue, numLoop);
            return;
        }

        // 入力パラメータの取得
        double requestedFlow = client.getFlow().getTheNumberOfUAV();
        int sourceNode = client.getFlow().getSource().getId();
        int destNode = client.getFlow().getDestination().getId();
        double eps = 1e-10; // 結果出力用の小さい値
        int ct = 0;
        final int MAX_ITERATIONS = 10000; // 最大イテレーション数を制限
        
        LogManager.getInstance().log("HybridPhysarumSolver: Starting search with requested flow " + requestedFlow);
        
        // 初期フロー値を設定（1から開始）
        currentFlow = 1.0;
        
        try {
            // メインループ - 要求フローに達した後も必要な数のイテレーションを実行
            boolean finalFlowReached = false;
            int iterationsAtFinalFlow = 0;
            final int REQUIRED_ITERATIONS_AT_FINAL_FLOW = 1000; // 最大フロー到達後の追加イテレーション数（安定収束のため）
            
            while (ct < MAX_ITERATIONS) {
                // 終了条件の詳細チェック
                if (finalFlowReached && iterationsAtFinalFlow >= REQUIRED_ITERATIONS_AT_FINAL_FLOW) {
                    boolean tubeThicknessAnomalyDetected = checkTubeThicknessAnomaly();
                    
                    if (tubeThicknessAnomalyDetected) {
                        // 異常検知時：フロー減少して再度500回安定化
                        currentFlow -= 1.0;
                        LogManager.getInstance().log("HybridPhysarumSolver: Tube thickness anomaly detected after " + REQUIRED_ITERATIONS_AT_FINAL_FLOW + 
                                                  " stabilization iterations. Decreasing flow to " + currentFlow + " and restarting stabilization.");
                        
                        iterationsAtFinalFlow = 0; // カウンターリセットして再度500回開始
                    } else {
                        // 異常なし：安定化完了、UAV割り当てに進む
                        LogManager.getInstance().log("HybridPhysarumSolver: No anomaly detected after stabilization. Proceeding to UAV assignment.");
                        break; // ループを抜けてUAV割り当てに進む
                    }
                } else if (!finalFlowReached && currentFlow >= requestedFlow) {
                    // 通常の最大フロー到達処理
                    finalFlowReached = true;
                    iterationsAtFinalFlow = 0;
                    LogManager.getInstance().log("HybridPhysarumSolver: Reached requested flow " + requestedFlow + 
                                              ", continuing for " + REQUIRED_ITERATIONS_AT_FINAL_FLOW + " more iterations");
                }
                // ここで流入フロー値を設定（右辺ベクトルの更新）
                Q_Kirchhoff[sourceNode] = currentFlow; // ソースノードに現在のフロー値を設定
                Q_Kirchhoff[destNode] = currentFlow * NEG; // デスティネーションノードに負のフロー値を設定
                
                // 親クラスのsearchメソッド内と同様の処理を実行（1イテレーション分）
                // sourceとdistを取得
                for (int i = 0; i < node; i++) {
                    pressureCoefficient[i][i] = 0.0;
                    boolean fig_DIST = false;
                    
                    if (i == sourceNode || i == destNode) {
                        fig_DIST = true;
                    }

                    if (!fig_DIST) {
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
                            pressureCoefficient[k][k] = pressureCoefficient[k][k] + link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength();
                        }
                    }
                    k++;
                }

                // 線形方程式を解く
                int testIter = 10;
                if (solvePressureEquation(pressureCoefficient, Q_Kirchhoff, P_tubePressure, node, testIter, eps) == -1) {
                    LogManager.getInstance().log("HybridPhysarumSolver: Pressure equation solving failed at iteration " + (ct + 1) + 
                                              " with flow " + currentFlow + ". Checking for tube thickness anomaly and reducing flow.");
                    
                    // 圧力方程式が解けない場合もチューブ厚異常として扱い、フロー減少
                    currentFlow -= 1.0;
                    LogManager.getInstance().log("HybridPhysarumSolver: Reducing flow to " + currentFlow + " due to pressure equation failure and restarting stabilization.");
                    
                    finalFlowReached = true;
                    iterationsAtFinalFlow = 0;
                    
                    // 次のイテレーションに進む（breakしない）
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

                // 最大フローの最終ループ時、または最大イテレーションの直前で流量を整数に丸める
                if ((finalFlowReached && iterationsAtFinalFlow == REQUIRED_ITERATIONS_AT_FINAL_FLOW - 1) || ct == MAX_ITERATIONS - 1) {
                    int linkCount = 0;
                    for (int i = 0; i < node; i++) {
                        for (int j = 0; j < node; j++) {
                            if (link[i][j].getL_tubeLength() != INF) {
                                linkCount++;
                            }
                        }
                    }

                    double[] flows = new double[linkCount];
                    int index = 0;
                    for (int i = 0; i < node; i++) {
                        for (int j = 0; j < node; j++) {
                            if (link[i][j].getL_tubeLength() != INF) {
                                flows[index++] = link[i][j].getQ_tubeFlow();
                            }
                        }
                    }

                    MathUtils.roundWithConservation(flows);

                    index = 0;
                    for (int i = 0; i < node; i++) {
                        for (int j = 0; j < node; j++) {
                            if (link[i][j].getL_tubeLength() != INF) {
                                link[i][j].setQ_tubeFlow(flows[index++]);
                            }
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

                // 各イテレーション後にチューブ厚異常検知
                boolean tubeThicknessAnomalyDetected = checkTubeThicknessAnomaly();
                
                if (tubeThicknessAnomalyDetected) {
                    // 異常検知時：EPSを初期化してフロー減少
                    currentFlow -= 1.0;
                    LogManager.getInstance().log("HybridPhysarumSolver: Tube thickness anomaly detected at iteration " + (ct + 1) + 
                                              ". Decreasing flow to " + currentFlow + " and initializing EPS for " + STABILIZATION_ITERATIONS + " iterations.");
                    
                    // EPSを初期化（一番初めの状態に戻す）
                    initializeEPS();
                    
                    // 1000回の安定化イテレーション実行
                    boolean stabilized = performStabilizationIterations(client, sourceNode, destNode, ct, eps);
                    
                    if (stabilized) {
                        LogManager.getInstance().log("HybridPhysarumSolver: Stabilization successful with flow " + currentFlow + ". Proceeding to UAV assignment.");
                        break; // 安定化成功、UAV割り当てに進む
                    }
                    // 安定化失敗の場合は次のイテレーションで再度フロー減少
                    continue;
                }

                // 結果のプロット
                if ((ct + 1) % PLOT == 0) {
                    LogManager.getInstance().log("Iteration: " + (ct + 1) + " with flow " + currentFlow);
                    ResultOutputManager.outputToPajek(client, eps, requestedFlow, ct, link, beaconCluster, node, serverController.getRunCounter());
                    ResultOutputManager.outputToExcel(client, ct, link, node, serverController.getRunCounter(), currentFlow);
                    ResultOutputManager.outputToTxt(client, ct, link, node, serverController.getRunCounter(), pressureCoefficient, currentFlow);
                }
                
                // 50イテレーションごとのフロー増加判定（異常がない場合のみ）
                if ((ct + 1) % ITERATIONS_PER_FLOW == 0 && currentFlow < requestedFlow && !finalFlowReached) {
                    // 異常なし：フロー増加
                    currentFlow += 1.0;
                    if (currentFlow > requestedFlow) {
                        currentFlow = requestedFlow;
                    }
                    LogManager.getInstance().log("HybridPhysarumSolver: No anomaly detected. Increased flow to " + currentFlow);
                }
                
                
                // 要求フロー値に達した後の追加イテレーションをカウント
                if (finalFlowReached) {
                    iterationsAtFinalFlow++;
                }
                
                ct++;
            }
            
            // 最終処理
            if (ct >= MAX_ITERATIONS) {
                LogManager.getInstance().log("HybridPhysarumSolver: Reached maximum iterations (" + MAX_ITERATIONS + 
                                          ") with flow " + currentFlow + " of " + requestedFlow + " requested");
            } else {
                LogManager.getInstance().log("HybridPhysarumSolver: Completed with final flow " + currentFlow + 
                                          " after " + ct + " iterations");
            }
            
            // 最終結果を出力
            ResultOutputManager.outputToPajek(client, eps, requestedFlow, ct, link, beaconCluster, node, serverController.getRunCounter());
            ResultOutputManager.outputToExcel(client, ct, link, node, serverController.getRunCounter(), currentFlow);
            ResultOutputManager.outputToTxt(client, ct, link, node, serverController.getRunCounter(), pressureCoefficient, currentFlow);
            
            // 親クラスのUAV割り当て処理を実行
            LogManager.getInstance().log("breakout point");
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
            int requiredUAVs = (int) client.getFlow().getTheNumberOfUAV();
            double epsAssignedFlow = currentFlow;
            int remainingUAVs = requiredUAVs - (int)epsAssignedFlow;
            
            LogManager.getInstance().log("HybridPhysarumSolver: EPS assigned flow " + epsAssignedFlow + " out of " + requiredUAVs + " required UAVs");
            LogManager.getInstance().log("HybridPhysarumSolver: Remaining UAVs to be assigned by PS: " + remainingUAVs);
            
            // 残りUAVがある場合はPSで追加計算
            if (remainingUAVs > 0) {
                LogManager.getInstance().log("HybridPhysarumSolver: Starting PS computation for " + remainingUAVs + " remaining UAVs");
                
                // PS計算実行
                ct = performPSComputation(client, remainingUAVs, sourceNode, destNode, outputIterationCursor, eps);
                
                LogManager.getInstance().log("HybridPhysarumSolver: PS computation completed. Results integrated.");
            }
            
            // 全UAV割り当て実行前に流量の最終確認
            LogManager.getInstance().log("HybridPhysarumSolver: Starting UAV flight assignment for all " + requiredUAVs + " UAVs");
            
            // 統合後の流量とtubeFlowを詳細ログ出力
            LogManager.getInstance().log("HybridPhysarumSolver: Final flow verification before runUAVFlow:");
            double totalFlowSum = 0.0;
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF && link[i][j].getQ_tubeFlow() > 0) {
                        double flowValue = link[i][j].getQ_tubeFlow();
                        double floorValue = Math.floor(flowValue);
                        if (flowValue != floorValue) {
                            LogManager.getInstance().log("WARNING: Non-integer flow detected! Link(" + i + "," + j + ") = " + flowValue + " (should be integer)");
                        }
                        LogManager.getInstance().log("Link(" + i + "," + j + "): Q_tubeFlow=" + flowValue + ", tubeFlow=" + tubeFlow[i][j] + ", Flow_Capacity=" + Flow_Capacity[i][j]);
                        totalFlowSum += flowValue;
                    }
                }
            }
            LogManager.getInstance().log("HybridPhysarumSolver: Total flow sum = " + totalFlowSum + " (expected: " + requiredUAVs + ")");
            
            runUAVFlow(sourceNode, destNode, requiredUAVs, client, flyingUavQueue, uavQueue);
            
        } catch (Exception e) {
            // エラー詳細をログ出力
            LogManager.getInstance().error("Error in incremental EPS process: ", e);
            throw e; // 再スローして上位で処理
        }
    }

    /**
     * チューブ厚異常検知メソッド
     * @return 異常が検知された場合true、それ以外はfalse
     */
    private boolean checkTubeThicknessAnomaly() {
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF && link[i][j].getD_tubeThickness() < 0.0) {
                    LogManager.getInstance().log("HybridPhysarumSolver: Tube thickness anomaly detected at link (" + i + "," + j + 
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
        LogManager.getInstance().log("HybridPhysarumSolver: Initializing EPS to initial state");
        
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
        LogManager.getInstance().log("HybridPhysarumSolver: Starting " + STABILIZATION_ITERATIONS + " stabilization iterations with flow " + currentFlow);
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
                LogManager.getInstance().log("HybridPhysarumSolver: Pressure equation solving failed during stabilization iteration " + (stabIter + 1));
                // 出力を残す（連番イテレーション番号）
                try {
                    ResultOutputManager.outputToExcel(client, currentIteration - 1, link, node, serverController.getRunCounter(), currentFlow);
                    ResultOutputManager.outputToTxt(client, currentIteration - 1, link, node, serverController.getRunCounter(), pressureCoefficient, currentFlow);
                } catch (IOException ioe) {
                    LogManager.getInstance().error("HybridPhysarumSolver: Failed to write stabilization outputs on solver failure", ioe);
                }
                return false;
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
                LogManager.getInstance().error("HybridPhysarumSolver: Failed to write stabilization outputs", ioe);
            }
            
            // 各安定化イテレーション後にチューブ厚をチェック
            if (checkTubeThicknessAnomaly()) {
                LogManager.getInstance().log("HybridPhysarumSolver: Tube thickness anomaly detected during stabilization iteration " + (stabIter + 1) + ". Stabilization failed.");
                return false;
            }
        }
        
        // 1000回安定化完了時に流量を整数に丸める
        LogManager.getInstance().log("HybridPhysarumSolver: Performing flow rounding after successful stabilization");
        
        int linkCount = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    linkCount++;
                }
            }
        }

        double[] flows = new double[linkCount];
        int index = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    flows[index++] = link[i][j].getQ_tubeFlow();
                }
            }
        }

        MathUtils.roundWithConservation(flows);

        index = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    link[i][j].setQ_tubeFlow(flows[index++]);
                }
            }
        }
        
        LogManager.getInstance().log("HybridPhysarumSolver: Stabilization completed successfully after " + STABILIZATION_ITERATIONS + " iterations");
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
        
        LogManager.getInstance().log("HybridPhysarumSolver: PS starting with " + remainingUAVs + " UAVs flow");
        
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
                LogManager.getInstance().log("HybridPhysarumSolver: PS pressure equation solving failed at iteration " + currentIteration);
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

            // 最終イテレーションで流量を整数に丸める
            if (psIter == 499) {
                int linkCount = 0;
                for (int i = 0; i < node; i++) {
                    for (int j = 0; j < node; j++) {
                        if (psLink[i][j].getL_tubeLength() != INF) {
                            linkCount++;
                        }
                    }
                }

                double[] flows = new double[linkCount];
                int index = 0;
                for (int i = 0; i < node; i++) {
                    for (int j = 0; j < node; j++) {
                        if (psLink[i][j].getL_tubeLength() != INF) {
                            flows[index++] = psLink[i][j].getQ_tubeFlow();
                        }
                    }
                }

                MathUtils.roundWithConservation(flows);

                index = 0;
                for (int i = 0; i < node; i++) {
                    for (int j = 0; j < node; j++) {
                        if (psLink[i][j].getL_tubeLength() != INF) {
                            psLink[i][j].setQ_tubeFlow(flows[index++]);
                        }
                    }
                }

                // PS流量制約の適用（全ネットワークの流量バランスを保持）
                double sourceOutflowSum = 0.0;
                for (int j = 0; j < node; j++) {
                    if (psLink[sourceNode][j].getL_tubeLength() != INF && psLink[sourceNode][j].getQ_tubeFlow() > 0) {
                        sourceOutflowSum += psLink[sourceNode][j].getQ_tubeFlow();
                    }
                }

                LogManager.getInstance().log("HybridPhysarumSolver: Before correction - PS source outflow sum = " + sourceOutflowSum + " (expected: " + remainingUAVs + ")");

                if (Math.abs(sourceOutflowSum - remainingUAVs) > 0.01) { // 小さな数値誤差を許容
                    LogManager.getInstance().log("HybridPhysarumSolver: PS source outflow " + sourceOutflowSum + " != expected " + remainingUAVs + ". Applying network-wide correction.");
                    
                    // 全ネットワークの流量を比例調整
                    double correctionFactor = (double)remainingUAVs / sourceOutflowSum;
                    LogManager.getInstance().log("HybridPhysarumSolver: Correction factor = " + correctionFactor);
                    
                    // 全てのリンクの流量を同じ比率で調整（ネットワーク全体のバランスを保持）
                    for (int i = 0; i < node; i++) {
                        for (int j = 0; j < node; j++) {
                            if (psLink[i][j].getL_tubeLength() != INF && psLink[i][j].getQ_tubeFlow() != 0) {
                                double correctedFlow = psLink[i][j].getQ_tubeFlow() * correctionFactor;
                                psLink[i][j].setQ_tubeFlow(correctedFlow);
                            }
                        }
                    }
                    
                    // 修正後に再度MathUtils.roundWithConservation適用（ソース流出のみ）
                    java.util.List<Integer> sourceOutLinks = new java.util.ArrayList<>();
                    java.util.List<Double> sourceOutFlows = new java.util.ArrayList<>();
                    for (int j = 0; j < node; j++) {
                        if (psLink[sourceNode][j].getL_tubeLength() != INF && psLink[sourceNode][j].getQ_tubeFlow() > 0) {
                            sourceOutLinks.add(j);
                            sourceOutFlows.add(psLink[sourceNode][j].getQ_tubeFlow());
                        }
                    }

                    if (!sourceOutFlows.isEmpty()) {
                        double[] sourceFlowsArray = sourceOutFlows.stream().mapToDouble(Double::doubleValue).toArray();
                        MathUtils.roundWithConservation(sourceFlowsArray);
                        
                        // 差分を計算してネットワーク全体に反映
                        for (int i = 0; i < sourceOutLinks.size(); i++) {
                            int linkDest = sourceOutLinks.get(i);
                            double oldFlow = psLink[sourceNode][linkDest].getQ_tubeFlow();
                            double newFlow = sourceFlowsArray[i];
                            double flowDiff = newFlow - oldFlow;
                            
                            // ソースリンクを更新
                            psLink[sourceNode][linkDest].setQ_tubeFlow(newFlow);
                            
                            // 下流のリンクも同じ比率で調整（流量バランス保持）
                            if (flowDiff != 0 && oldFlow != 0) {
                                double linkCorrectionFactor = newFlow / oldFlow;
                                adjustDownstreamFlows(psLink, linkDest, destNode, linkCorrectionFactor);
                            }
                        }
                    }
                    
                    // 修正後の合計を再計算して確認
                    double finalSum = 0.0;
                    for (int j = 0; j < node; j++) {
                        if (psLink[sourceNode][j].getL_tubeLength() != INF && psLink[sourceNode][j].getQ_tubeFlow() > 0) {
                            finalSum += psLink[sourceNode][j].getQ_tubeFlow();
                        }
                    }
                    
                    LogManager.getInstance().log("HybridPhysarumSolver: After correction - PS source outflow sum = " + finalSum + " (target: " + remainingUAVs + ")");
                }
            }

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
        LogManager.getInstance().log("HybridPhysarumSolver: PS source outflow sum = " + psFlowSum + " (expected: " + remainingUAVs + ")");
        
        // 全リンクの流量合計も表示（デバッグ用）
        double totalFlowSum = 0.0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (psLink[i][j].getL_tubeLength() != INF && psTempResults[i][j] > 0) {
                    totalFlowSum += psTempResults[i][j];
                }
            }
        }
        LogManager.getInstance().log("HybridPhysarumSolver: PS total all-links flow sum = " + totalFlowSum);

        // EPSとPSの結果を統合
        LogManager.getInstance().log("HybridPhysarumSolver: Integrating EPS and PS results");
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

        // 統合後に再度整数への丸め込みを実行（重要：adjustRemainingFlow回避のため）
        LogManager.getInstance().log("HybridPhysarumSolver: Applying final integer rounding after EPS+PS integration");
        int linkCount = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    linkCount++;
                }
            }
        }

        double[] integratedFlows = new double[linkCount];
        int index = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    integratedFlows[index++] = link[i][j].getQ_tubeFlow();
                }
            }
        }

        MathUtils.roundWithConservation(integratedFlows);

        index = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    double oldFlow = link[i][j].getQ_tubeFlow();
                    double newFlow = integratedFlows[index++];
                    link[i][j].setQ_tubeFlow(newFlow);
                    if (oldFlow != newFlow) {
                        LogManager.getInstance().log("Final rounding: Link (" + i + "," + j + "): " + oldFlow + " → " + newFlow);
                    }
                }
            }
        }

        LogManager.getInstance().log("HybridPhysarumSolver: Final integer rounding completed. All flows are now exact integers.");

        // 統合完了後に、Flow_CapacityとtubeFlowを統合結果で更新（重要！）
        LogManager.getInstance().log("HybridPhysarumSolver: Updating Flow_Capacity and tubeFlow arrays with integrated results");
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
        LogManager.getInstance().log("HybridPhysarumSolver: Flow arrays update completed");

        return startIteration + 500;
    }

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
