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
            final int REQUIRED_ITERATIONS_AT_FINAL_FLOW = 500; // 最大フロー到達後の追加イテレーション数（安定収束のため）
            
            while ((currentFlow < requestedFlow || (finalFlowReached && iterationsAtFinalFlow < REQUIRED_ITERATIONS_AT_FINAL_FLOW)) && ct < MAX_ITERATIONS) {
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
                    break;
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

                // 結果のプロット
                if ((ct + 1) % PLOT == 0) {
                    LogManager.getInstance().log("Iteration: " + (ct + 1) + " with flow " + currentFlow);
                    ResultOutputManager.outputToPajek(client, eps, requestedFlow, ct, link, beaconCluster, node, serverController.getRunCounter());
                    ResultOutputManager.outputToExcel(client, ct, link, node, serverController.getRunCounter());
                }
                
                // 50イテレーションごとにフロー値を増加
                if ((ct + 1) % ITERATIONS_PER_FLOW == 0 && currentFlow < requestedFlow) {
                    currentFlow += 1.0;
                    if (currentFlow > requestedFlow) {
                        currentFlow = requestedFlow;
                    }
                    LogManager.getInstance().log("HybridPhysarumSolver: Increased flow to " + currentFlow);
                }
                
                // 要求フロー値に達したかチェック
                if (currentFlow >= requestedFlow && !finalFlowReached) {
                    finalFlowReached = true;
                    LogManager.getInstance().log("HybridPhysarumSolver: Reached requested flow " + requestedFlow + 
                                              ", continuing for " + REQUIRED_ITERATIONS_AT_FINAL_FLOW + " more iterations");
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
            ResultOutputManager.outputToTxt(client, ct, link, node, serverController.getRunCounter());
            
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
}
