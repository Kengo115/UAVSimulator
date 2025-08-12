package server.route;

import client.Client;
import client.ClientController;
import item.BeaconCluster;
import item.Link;
import item.Uav;
import server.controller.ServerController;
import server.util.MathUtils;
import server.util.ResultOutputManager;

import java.io.IOException;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * PhysarumSolverアルゴリズムの基底クラス
 * 共通のロジックを提供する
 */
public abstract class AbstractPhysarumSolverRouteSearcher implements RouteSearcher {
    // 定数
    protected static final double INF = 10000.0;
    protected static final double NEG = -1.0;
    protected static final double GAMMA = 1.01;
    protected static final double DELTA_TIME = 0.01;
    protected static final int PLOT = 1;
    protected static final int PLOT_2 = 20;
    protected static final double THRESHOLD_1 = 0.5;
    protected static final double THRESHOLD_2 = 2.0;
    protected static final double coefficient_tanh = 1;

    // サーバーコントローラー
    protected final ServerController serverController;
    
    // 隣接行列
    protected final int[][] adjMatrix;
    
    // リンク情報
    protected final Link[][] link;
    
    // ビーコンクラスター
    protected final BeaconCluster beaconCluster;
    
    // ノード数
    protected final int node;
    
    // 基本パラメータ
    protected double[] Q_Kirchhoff;
    protected double[] P_tubePressure;
    
    // 計算パラメータ
    protected double[][] D_tubeThickness_deltaT;
    protected double[][] pressureCoefficient;
    protected double[][] Q_tubeFlow_sigmoidOutput;
    
    // フロー関連
    protected double[][] Flow_Capacity;
    protected int[][] tubeFlow;
    
    // UAVカウント
    protected int UAV_count;
    
    // 最小フロー
    protected int min_Flow = 100;
    
    // 最大パスインデックス
    protected int maxPathIndex = 0;

    /**
     * コンストラクタ
     * @param serverController サーバーコントローラー
     * @param adjMatrix 隣接行列
     * @param link リンク情報
     * @param beaconCluster ビーコンクラスター
     * @param node ノード数
     */
    public AbstractPhysarumSolverRouteSearcher(ServerController serverController, int[][] adjMatrix, Link[][] link, BeaconCluster beaconCluster, int node) {
        this.serverController = serverController;
        this.adjMatrix = adjMatrix;
        this.link = link;
        this.beaconCluster = beaconCluster;
        this.node = node;
        
        // 初期化
        this.Q_Kirchhoff = new double[node];
        this.P_tubePressure = new double[node];
        this.D_tubeThickness_deltaT = new double[node][node];
        this.pressureCoefficient = new double[node][node];
        this.Q_tubeFlow_sigmoidOutput = new double[node][node];
        this.Flow_Capacity = new double[node][node];
        this.tubeFlow = new int[node][node];
    }

    @Override
    public void search(Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, int numLoop) throws IOException {
        int ct = 0;
        double eps = 1e-10;
        int testIter = 10;
        boolean fig_DIST = false;

        while (ct < numLoop) {
            // sourceとdistを取得
            int source = client.getFlow().getSource().getId();
            int dist = client.getFlow().getDestination().getId();
            Q_Kirchhoff[source] = client.getFlow().getTheNumberOfUAV();
            Q_Kirchhoff[dist] = client.getFlow().getTheNumberOfUAV() * NEG;

            for (int i = 0; i < node; i++) {
                pressureCoefficient[i][i] = 0.0;
                
                if (i == source || i == dist) {
                    fig_DIST = true;
                }

                if (!fig_DIST) {
                    Q_Kirchhoff[i] = 0.0;
                }
                fig_DIST = false;
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

            // 線形方程式を解く（サブクラスで実装）
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

            // 最後のループで流量を整数に丸める
            if (ct == numLoop - 1) {
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

            // チューブ厚の更新（サブクラスで実装）
            updateTubeThickness(ct);

            // 結果のプロット
            if ((ct + 1) % PLOT == 0) {
                System.out.println("Iteration: " + (ct + 1));
                ResultOutputManager.outputToPajek(client, eps, client.getFlow().getTheNumberOfUAV(), ct, link, beaconCluster, node, serverController.getRunCounter());
                ResultOutputManager.outputToExcel(client, ct, link, node, serverController.getRunCounter());
                ResultOutputManager.outputToTxt(client, ct, link, node, serverController.getRunCounter());
            }

            // 追加のプロット（サブクラスでオーバーライド可能）
            additionalPlotting(client, ct);

            ct++;
            
            // 最後のループの場合に実行する処理
            if (ct == numLoop) {
                // 初期設定として、Flow_CapacityにQ_tubeFlowを代入,各リンクを流れる流量の整数値をtubeFlowに追加
                System.out.println("breakout point");
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

                // スタートノード、ゴールノード、必要なUAV台数を取得
                int startNode = client.getFlow().getSource().getId();
                int goalNode = client.getFlow().getDestination().getId();
                int requiredUAVs = (int) client.getFlow().getTheNumberOfUAV();

                // 実際のUAVに経路を割り当てるためのメイン処理
                runUAVFlow(startNode, goalNode, requiredUAVs, client, flyingUavQueue, uavQueue);
            }
        }
    }

    /**
     * チューブ厚を更新する抽象メソッド
     * サブクラスで実装する
     * @param ct 現在の反復回数
     */
    protected abstract void updateTubeThickness(int ct);
    
    /**
     * 線形方程式を解く抽象メソッド
     * サブクラスで実装する
     * @param pressCoeff 係数行列
     * @param dataAll 右辺ベクトル
     * @param output 解ベクトル
     * @param n 次元数
     * @param maxIter 最大反復回数
     * @param eps 収束判定閾値
     * @return 反復回数（収束しなかった場合は-1）
     */
    protected abstract int solvePressureEquation(double[][] pressCoeff, double[] dataAll, double[] output, int n, int maxIter, double eps);

    /**
     * 追加のプロット処理を行う
     * サブクラスでオーバーライド可能
     * @param client クライアント
     * @param ct 現在の反復回数
     * @throws IOException 入出力例外
     */
    protected void additionalPlotting(Client client, int ct) throws IOException {
        // デフォルトでは何もしない
    }

    /**
     * 経路記録のタグを取得する
     * サブクラスでオーバーライド可能
     * @return 経路記録のタグ
     */
    protected String getRouteRecordTag() {
        return "runUAVFlow";
    }

    /**
     * 残りの経路記録のタグを取得する
     * サブクラスでオーバーライド可能
     * @return 残りの経路記録のタグ
     */
    protected String getRemainingRouteRecordTag() {
        return "remainingFlow";
    }

    /**
     * UAVに経路を割り当てる
     * @param startNode 開始ノード
     * @param goalNode 目標ノード
     * @param requiredUAVs 必要なUAV数
     * @param client クライアント
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     */
    protected void runUAVFlow(int startNode, int goalNode, int requiredUAVs, Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue) {
        UAV_count = 0;
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        CountDownLatch latch = new CountDownLatch(requiredUAVs);
        boolean flowAvailable = true;

        while (UAV_count < requiredUAVs && flowAvailable) {
            int previousUAVCount = UAV_count;
            min_Flow = 100;

            int[] path = new int[20];
            int pathIndex = 0;
            path[pathIndex++] = startNode;

            maxPathIndex = pathIndex;

            int flow = explorePath(startNode, startNode, goalNode, path, pathIndex, 0);

            if (flow > 0) {
                int pathLength = maxPathIndex;
                int[] pathArray = Arrays.copyOf(path, pathLength);

                int u = pathArray[0];
                int v = pathArray[1];

                double minCapacity = link[u][v].getCapacity();
                final int[] flowCounter = {0};

                for (int f = 0; f < flow; f++) {
                    int currentUAVIndex;

                    synchronized (this) {
                        UAV_count++;
                        currentUAVIndex = UAV_count - 1;
                    }

                    Uav currentUAV = client.getFlow().getUav(currentUAVIndex);

                    final int finalF = f;
                    scheduler.schedule(() -> {
                        currentUAV.setPath(pathArray);
                        server.uav.FlightDataRecorder.recordRoute(currentUAV, getRouteRecordTag());

                        if (flowCounter[0] < minCapacity) {
                            currentUAV.startTimer();
                            currentUAV.setFlyingLink(link[u][v]);
                            currentUAV.setPassedLink(link[u][v]);
                            flyingUavQueue.add(currentUAV);
                            link[u][v].decrementCapacity();
                            flowCounter[0]++;
                            System.out.println("UAV " + currentUAV.getId() + " is flying from " + u + " to " + v);
                        } else {
                            currentUAV.startWaitingTimer();
                            currentUAV.setStayedBeaconId(u);
                            beaconCluster.getBeacon(u).addUav(currentUAV);
                            beaconCluster.getBeacon(u).incrementWaitingUavCount();
                            uavQueue.add(currentUAV);
                            System.out.println("UAV " + currentUAV.getId() + " is waiting at " + u + "(" + u + " -> " + v + ")");
                        }

                        latch.countDown();
                    }, finalF * 2, TimeUnit.SECONDS);
                }
            } else {
                flowAvailable = false;
            }

            if (UAV_count == requiredUAVs) break;
        }

        scheduler.shutdown();
        try {
            scheduler.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("スレッドが割り込まれました: " + e.getMessage());
        }

        if (!flowAvailable) {
            int needUAV = requiredUAVs - UAV_count;
            if (needUAV > 0) {
                System.out.println("全てのUAVに経路が割り当てられませんでした。残り" + needUAV + "台のUAVを再割り当てします。");
                adjustRemainingFlow(needUAV, startNode, goalNode, client, flyingUavQueue, uavQueue);
            }
        }
    }

    /**
     * 深さ優先探索で経路を探索する
     * @param startNode 開始ノード
     * @param currentNode 現在のノード
     * @param goalNode 目標ノード
     * @param path 経路
     * @param pathIndex 経路インデックス
     * @param passedFlow 通過フロー
     * @return フロー
     */
    protected int explorePath(int startNode, int currentNode, int goalNode, int[] path, int pathIndex, int passedFlow) {
        // ゴールノードに到達したら流量を返して経路探索を終了
        if (currentNode == goalNode) {
            maxPathIndex = pathIndex; // 正しい最大経路長を記録
            return passedFlow;
        }

        // `maxPathIndex` を `pathIndex` と比較して更新
        if (pathIndex > maxPathIndex) {
            maxPathIndex = pathIndex;
        }

        // 次のノードを探索し、経路を進む
        for (int nextNode = 0; nextNode < node; nextNode++) {
            if (adjMatrix[currentNode][nextNode] == 1 && tubeFlow[currentNode][nextNode] > 0) {
                int flow = tubeFlow[currentNode][nextNode]; // 現在ノード間の流量

                // 最小フローの計算
                int prevMinFlow = min_Flow;  // バックトラックのために保存
                min_Flow = (passedFlow == 0) ? flow : Math.min(min_Flow, flow);

                // 経路に次のノードを追加
                path[pathIndex] = nextNode;

                // ゴールに到達した場合、`tubeFlow` を減算
                if (nextNode == goalNode && min_Flow > 0) {
                    int nodeA = startNode;
                    for (int i = 0; i <= pathIndex; i++) {
                        int nodeB = path[i];
                        tubeFlow[nodeA][nodeB] -= min_Flow;
                        Flow_Capacity[nodeA][nodeB] -= min_Flow;

                        if (tubeFlow[nodeA][nodeB] == 0) {
                            adjMatrix[nodeA][nodeB] = 0;
                        }
                        nodeA = nodeB;
                    }
                }

                // 再帰的に経路を探索
                int resultFlow = explorePath(startNode, nextNode, goalNode, path, pathIndex + 1, min_Flow);
                if (resultFlow > 0) {
                    return resultFlow; // 成功した場合は流量を返す
                }

                // バックトラック処理
                min_Flow = prevMinFlow;  // 元の min_Flow を復元
            }
        }

        return 0; // 失敗した場合、流量0を返す
    }

    /**
     * 残りのUAVに経路を割り当てる
     * @param needUAV 必要なUAV数
     * @param startNode 開始ノード
     * @param goalNode 目標ノード
     * @param client クライアント
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     */
    protected void adjustRemainingFlow(int needUAV, int startNode, int goalNode, Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue) {
        int countOfUAV = 0;
        int[] path = new int[20]; // path を再利用
        int pathIndex;

        while (countOfUAV < needUAV) {
            pathIndex = 0;
            Arrays.fill(path, 0);
            path[pathIndex++] = startNode;

            int currentNode = startNode;
            boolean pathFound = false;

            while (currentNode != goalNode) {
                int nextNode = -1;
                double maxCapacity = -1.0;

                for (int j = 0; j < node; j++) {
                    if (tubeFlow[currentNode][j] > 0 && Flow_Capacity[currentNode][j] >= 1) {
                        if (Flow_Capacity[currentNode][j] > maxCapacity) {
                            maxCapacity = Flow_Capacity[currentNode][j];
                            nextNode = j;
                        }
                    }
                }

                if (nextNode == -1) {
                    for (int j = 0; j < node; j++) {
                        if (Flow_Capacity[currentNode][j] > maxCapacity) {
                            maxCapacity = Flow_Capacity[currentNode][j];
                            nextNode = j;
                        }
                    }
                    if (nextNode != -1) {
                        tubeFlow[currentNode][nextNode] = 1;
                        Flow_Capacity[currentNode][nextNode] = 1.0;
                    }
                }

                if (nextNode == -1) {
                    break;
                }

                path[pathIndex++] = nextNode;
                int flow = tubeFlow[currentNode][nextNode];
                tubeFlow[currentNode][nextNode] -= flow;
                Flow_Capacity[currentNode][nextNode] -= flow;

                currentNode = nextNode;

                if (currentNode == goalNode) {
                    pathFound = true;
                    int[] assignedPath = new int[pathIndex];
                    System.arraycopy(path, 0, assignedPath, 0, pathIndex);

                    int u = assignedPath[0];
                    int v = assignedPath[1];
                    double minCapacity = link[u][v].getCapacity();

                    for (int uav = 0; uav < flow && countOfUAV < needUAV; uav++) {
                        int currentUAVIndex = UAV_count + countOfUAV;
                        Uav currentUAV = client.getFlow().getUav(currentUAVIndex);
                        currentUAV.setPath(assignedPath);
                        server.uav.FlightDataRecorder.recordRoute(currentUAV, getRemainingRouteRecordTag());

                        if (countOfUAV < minCapacity) {
                            currentUAV.startTimer();
                            currentUAV.setFlyingLink(link[u][v]);
                            currentUAV.setPassedLink(link[u][v]);
                            flyingUavQueue.add(currentUAV);
                            link[u][v].decrementCapacity();
                            System.out.println("UAV " + currentUAV.getId() + " is flying from " + u + " to " + v + " adjustRemainingFlow");
                        } else {
                            currentUAV.startWaitingTimer();
                            currentUAV.setStayedBeaconId(u);
                            beaconCluster.getBeacon(u).addUav(currentUAV);
                            beaconCluster.getBeacon(u).incrementWaitingUavCount();
                            uavQueue.add(currentUAV);
                            System.out.println("UAV " + currentUAV.getId() + " is waiting at " + u + "(" + u + " -> " + v + ") adjustRemainingFlow");
                        }
                        countOfUAV++;
                    }
                }
            }

            if (!pathFound) {
                System.out.println("有効な経路が見つかりませんでした");
                break;
            }
        }

        UAV_count += countOfUAV; // UAV_count を適切に更新
    }
}
