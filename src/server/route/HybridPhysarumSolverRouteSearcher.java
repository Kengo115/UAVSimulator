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
                    boolean stabilized = performStabilizationIterations(client, sourceNode, destNode, eps);
                    
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
                    ResultOutputManager.outputToExcel(client, ct, link, node, serverController.getRunCounter());
                    ResultOutputManager.outputToTxt(client, ct, link, node, serverController.getRunCounter(), pressureCoefficient);
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
            ResultOutputManager.outputToExcel(client, ct, link, node, serverController.getRunCounter());
            ResultOutputManager.outputToTxt(client, ct, link, node, serverController.getRunCounter(), pressureCoefficient);
            
            // 親クラスのUAV割り当て処理を実行
            LogManager.getInstance().log("breakout point");
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) {
                        adjMatrix[i][j] = 1;
                        if (link[i][j].getQ_tubeFlow() > 0) {
                            Flow_Capacity[i][j] = link[i][j].getQ_tubeFlow();
                            int flow = (int) Math.floor(Flow_Capacity[i][j]);
                            tubeFlow[i][j] = flow;
                        }
                    }
                }
            }
            
            // UAV割り当て
            int requiredUAVs = (int) client.getFlow().getTheNumberOfUAV();
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
    private boolean performStabilizationIterations(Client client, int sourceNode, int destNode, double eps) {
        LogManager.getInstance().log("HybridPhysarumSolver: Starting " + STABILIZATION_ITERATIONS + " stabilization iterations with flow " + currentFlow);
        
        for (int stabIter = 0; stabIter < STABILIZATION_ITERATIONS; stabIter++) {
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
}
