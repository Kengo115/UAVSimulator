package server.route;

import client.Client;
import client.ClientController;
import controller.BoundaryController;
import item.BeaconCluster;
import item.Link;
import item.Uav;
import server.controller.ServerController;
import server.redis.ClientTimeManager;
import server.redis.UAVJob;
import server.redis.UAVJobQueue;
import server.util.LogManager;
import server.util.MathUtils;
import server.util.ResultOutputManager;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
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
    protected static final double coefficient_tanh = 0.5;

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

            // 最後のループで流量を整数に丸める（ソース流出を基準に、流量保存則を維持）
            if (ct == numLoop - 1) {
                int requiredFlow = (int) Math.round(Q_Kirchhoff[source]);
                roundSourceOutflowsAndPropagate(link, source, dist, requiredFlow);
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
                LogManager.getInstance().log("Iteration: " + (ct + 1));
                ResultOutputManager.outputToPajek(client, eps, client.getFlow().getTheNumberOfUAV(), ct, link, beaconCluster, node, serverController.getRunCounter());
                ResultOutputManager.outputToExcel(client, ct, link, node, serverController.getRunCounter(), client.getFlow().getTheNumberOfUAV());
                ResultOutputManager.outputToTxt(client, ct, link, node, serverController.getRunCounter(), pressureCoefficient, P_tubePressure, client.getFlow().getTheNumberOfUAV());
            }

            // 追加のプロット（サブクラスでオーバーライド可能）
            additionalPlotting(client, ct);

            // イテレーション毎の結果を記録
            try {
                int sourceNodeId = client.getFlow().getSource().getId();
                if (sourceNodeId >= 0 && sourceNodeId < P_tubePressure.length) {
                    double currentSourcePressure = P_tubePressure[sourceNodeId];
                    ResultOutputManager.outputIterationSourcePressure(ct + 1, currentSourcePressure, serverController.getRunCounter());
                }
                ResultOutputManager.outputIterationFlow(ct + 1, client.getFlow().getTheNumberOfUAV(), serverController.getRunCounter());
            } catch (IOException e) {
                LogManager.getInstance().error("Failed to output iteration data", e);
            }

            ct++;
            
            // 最後のループの場合に実行する処理
            if (ct == numLoop) {
                // 初期設定として、Flow_CapacityにQ_tubeFlowを代入,各リンクを流れる流量の整数値をtubeFlowに追加
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
        // Phase 4: 時間計測開始（全経路探索手法共通）
        ClientTimeManager.getInstance().startClientTime(client.getId(), requiredUAVs);

        // Phase 3b-6: WorkerModeに応じて処理を分岐
        if (BoundaryController.getCurrentWorkerMode() == BoundaryController.WorkerMode.REDIS) {
            runUAVFlowRedis(startNode, goalNode, requiredUAVs, client);
        } else {
            runUAVFlowMemory(startNode, goalNode, requiredUAVs, client, flyingUavQueue, uavQueue);
        }
    }

    /**
     * Phase 3b-6: Redis経由でUAVジョブを投入する
     * 同一の一ホップ目リンクを飛行するUAVは2秒間隔で投入
     * 異なるリンクを飛行するUAVは同時に投入可能
     */
    protected void runUAVFlowRedis(int startNode, int goalNode, int requiredUAVs, Client client) {
        LogManager.getInstance().log("Phase 3b-6: Redisモードでジョブ投入 (" + requiredUAVs + "機) [" + getRouteRecordTag() + "]");

        UAVJobQueue jobQueue = new UAVJobQueue();
        int clientId = client.getId();

        // 同一リンクごとに2秒間隔でジョブ投入するためのスケジューラ
        ScheduledExecutorService enqueueScheduler = Executors.newScheduledThreadPool(1);

        UAV_count = 0;
        boolean flowAvailable = true;

        // 一ホップ目リンクごとの投入順序を管理（キー: "from-to"）
        Map<String, Integer> linkEnqueueOrder = new HashMap<>();

        while (UAV_count < requiredUAVs && flowAvailable) {
            min_Flow = 100;

            int[] path = new int[20];
            int pathIndex = 0;
            path[pathIndex++] = startNode;
            maxPathIndex = pathIndex;

            int flow = explorePath(startNode, startNode, goalNode, path, pathIndex, 0);

            if (flow > 0) {
                int pathLength = maxPathIndex;
                int[] pathArray = Arrays.copyOf(path, pathLength);

                // 一ホップ目リンクのキーを作成
                String firstLinkKey = pathArray[0] + "-" + pathArray[1];

                // リンク距離を計算
                double[] linkDistances = calculateLinkDistances(pathArray);

                for (int f = 0; f < flow && UAV_count < requiredUAVs; f++) {
                    Uav uav = client.getFlow().getUav(UAV_count);
                    int uavId = uav.getId();
                    // 各UAVの個別速度を使用（8~16 m/sのランダム値）
                    double uavSpeed = uav.getSpeed();

                    // このリンクの現在の投入順序を取得・更新
                    int currentOrder = linkEnqueueOrder.getOrDefault(firstLinkKey, 0);
                    linkEnqueueOrder.put(firstLinkKey, currentOrder + 1);

                    final int delaySeconds = currentOrder * 2;
                    final int[] finalPathArray = pathArray;
                    final double[] finalLinkDistances = linkDistances;

                    // 同一リンクのUAVは2秒間隔でジョブ投入をスケジュール
                    enqueueScheduler.schedule(() -> {
                        // UAVJobを作成（投入時刻を開始時刻として設定）
                        UAVJob job = new UAVJob(
                            uavId,
                            clientId,
                            finalPathArray,
                            uavSpeed,
                            System.currentTimeMillis(),
                            startNode,
                            goalNode
                        );
                        job.setLinkDistances(finalLinkDistances);
                        // Phase 3b-8: セッションIDを設定
                        job.setSessionId(BoundaryController.getCurrentSessionId());

                        // キューに投入
                        jobQueue.enqueueJob(job);
                        LogManager.getInstance().log("Phase 3b-6: UAV" + uavId + " ジョブ投入完了 (経路: " + Arrays.toString(finalPathArray) + ", 速度: " + String.format("%.2f", uavSpeed) + "m/s)");

                        // Phase 7-2: 経路割り当て情報を記録
                        try {
                            ResultOutputManager.outputRouteAssignment(job, clientId);
                        } catch (IOException e) {
                            LogManager.getInstance().error("Phase 7-2: 経路割り当て記録エラー", e);
                        }
                    }, delaySeconds, TimeUnit.SECONDS);

                    UAV_count++;
                }
            } else {
                flowAvailable = false;
            }
        }

        if (!flowAvailable && UAV_count < requiredUAVs) {
            int needUAV = requiredUAVs - UAV_count;
            LogManager.getInstance().log("Phase 3b-6: 残り" + needUAV + "台のUAVを再割り当てします。");
            adjustRemainingFlowRedis(needUAV, startNode, goalNode, client, jobQueue, enqueueScheduler, linkEnqueueOrder);
        }

        LogManager.getInstance().log("Phase 3b-6: 全" + UAV_count + "件のジョブをスケジュールしました");
    }

    /**
     * Phase 3b-6: リンク距離を計算する
     */
    protected double[] calculateLinkDistances(int[] path) {
        double[] distances = new double[path.length - 1];
        for (int i = 0; i < path.length - 1; i++) {
            int from = path[i];
            int to = path[i + 1];
            distances[i] = link[from][to].getDistance();
        }
        return distances;
    }

    /**
     * Phase 3b-6: 残りのUAVをRedis経由で再割り当てする
     * 同一リンクのUAVは2秒間隔でジョブを投入
     */
    protected void adjustRemainingFlowRedis(int needUAV, int startNode, int goalNode, Client client,
                                            UAVJobQueue jobQueue, ScheduledExecutorService enqueueScheduler,
                                            Map<String, Integer> linkEnqueueOrder) {
        int clientId = client.getId();

        // 簡易的な経路を探索（Dijkstra的なアプローチ）
        int[] simplePath = findSimplePath(startNode, goalNode);
        if (simplePath == null || simplePath.length < 2) {
            LogManager.getInstance().error("Phase 3b-6: 再割り当て用の経路が見つかりませんでした");
            return;
        }

        // 一ホップ目リンクのキーを作成
        String firstLinkKey = simplePath[0] + "-" + simplePath[1];

        double[] linkDistances = calculateLinkDistances(simplePath);

        for (int i = 0; i < needUAV; i++) {
            Uav uav = client.getFlow().getUav(UAV_count);
            int uavId = uav.getId();
            // 各UAVの個別速度を使用（8~16 m/sのランダム値）
            double uavSpeed = uav.getSpeed();

            // このリンクの現在の投入順序を取得・更新
            int currentOrder = linkEnqueueOrder.getOrDefault(firstLinkKey, 0);
            linkEnqueueOrder.put(firstLinkKey, currentOrder + 1);

            final int delaySeconds = currentOrder * 2;
            final int[] finalSimplePath = simplePath;
            final double[] finalLinkDistances = linkDistances;

            // 同一リンクのUAVは2秒間隔でジョブ投入をスケジュール
            enqueueScheduler.schedule(() -> {
                UAVJob job = new UAVJob(
                    uavId,
                    clientId,
                    finalSimplePath,
                    uavSpeed,
                    System.currentTimeMillis(),
                    startNode,
                    goalNode
                );
                job.setLinkDistances(finalLinkDistances);
                // Phase 3b-8: セッションIDを設定
                job.setSessionId(BoundaryController.getCurrentSessionId());

                jobQueue.enqueueJob(job);
                LogManager.getInstance().log("Phase 3b-6: UAV" + uavId + " 再割り当てジョブ投入完了 (速度: " + String.format("%.2f", uavSpeed) + "m/s)");

                // Phase 7-2: 経路割り当て情報を記録
                try {
                    ResultOutputManager.outputRouteAssignment(job, clientId);
                } catch (IOException e) {
                    LogManager.getInstance().error("Phase 7-2: 経路割り当て記録エラー（再割り当て）", e);
                }
            }, delaySeconds, TimeUnit.SECONDS);

            UAV_count++;
        }
    }

    /**
     * Phase 3b-6: 簡易的な経路探索（再割り当て用）
     */
    protected int[] findSimplePath(int start, int goal) {
        // BFSで最短経路を探索
        boolean[] visited = new boolean[node];
        int[] parent = new int[node];
        Arrays.fill(parent, -1);

        java.util.Queue<Integer> queue = new java.util.LinkedList<>();
        queue.add(start);
        visited[start] = true;

        while (!queue.isEmpty()) {
            int current = queue.poll();
            if (current == goal) {
                // 経路を復元
                java.util.List<Integer> pathList = new java.util.ArrayList<>();
                int at = goal;
                while (at != -1) {
                    pathList.add(0, at);
                    at = parent[at];
                }
                return pathList.stream().mapToInt(Integer::intValue).toArray();
            }

            for (int next = 0; next < node; next++) {
                if (adjMatrix[current][next] == 1 && !visited[next]) {
                    visited[next] = true;
                    parent[next] = current;
                    queue.add(next);
                }
            }
        }

        return null;
    }

    /**
     * 従来のメモリベースUAV処理
     */
    protected void runUAVFlowMemory(int startNode, int goalNode, int requiredUAVs, Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue) {
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

                        // Phase 7-2: 経路割り当て情報を記録（メモリモード）
                        try {
                            ResultOutputManager.outputRouteAssignment(
                                currentUAV.getId(),
                                currentUAV.getSource().getId(),
                                currentUAV.getDistination().getId(),
                                currentUAV.getPath(),
                                currentUAV.getClientId()
                            );
                        } catch (IOException e) {
                            LogManager.getInstance().error("Phase 7-2: 経路割り当て記録エラー（メモリモード）", e);
                        }

                        if (flowCounter[0] < minCapacity) {
                            currentUAV.startTimer();
                            currentUAV.setFlyingLink(link[u][v]);
                            currentUAV.setPassedLink(link[u][v]);
                            flyingUavQueue.add(currentUAV);
                            link[u][v].decrementCapacity();
                            flowCounter[0]++;
                            LogManager.getInstance().log("client" + currentUAV.getClientId() + " UAV" + currentUAV.getId() + " is flying from " + u + " to " + v);
                        } else {
                            currentUAV.startWaitingTimer();
                            currentUAV.setStayedBeaconId(u);
                            beaconCluster.getBeacon(u).addUav(currentUAV);
                            beaconCluster.getBeacon(u).incrementWaitingUavCount();
                            uavQueue.add(currentUAV);
                            LogManager.getInstance().log("client" + currentUAV.getClientId() + " UAV" + currentUAV.getId() + " is waiting at " + u + "(" + u + " -> " + v + ")");
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
            LogManager.getInstance().error("スレッドが割り込まれました: " + e.getMessage());
        }

        if (!flowAvailable) {
            int needUAV = requiredUAVs - UAV_count;
            if (needUAV > 0) {
                LogManager.getInstance().log("全てのUAVに経路が割り当てられませんでした。残り" + needUAV + "台のUAVを再割り当てします。");
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

                        // Phase 7-2: 経路割り当て情報を記録（メモリモード・再割り当て）
                        try {
                            ResultOutputManager.outputRouteAssignment(
                                currentUAV.getId(),
                                currentUAV.getSource().getId(),
                                currentUAV.getDistination().getId(),
                                currentUAV.getPath(),
                                currentUAV.getClientId()
                            );
                        } catch (IOException e) {
                            LogManager.getInstance().error("Phase 7-2: 経路割り当て記録エラー（メモリモード・再割り当て）", e);
                        }

                        if (countOfUAV < minCapacity) {
                            currentUAV.startTimer();
                            currentUAV.setFlyingLink(link[u][v]);
                            currentUAV.setPassedLink(link[u][v]);
                            flyingUavQueue.add(currentUAV);
                            link[u][v].decrementCapacity();
                            LogManager.getInstance().log("client" + currentUAV.getClientId() + " UAV" + currentUAV.getId() + " is flying from " + u + " to " + v + " adjustRemainingFlow");
                        } else {
                            currentUAV.startWaitingTimer();
                            currentUAV.setStayedBeaconId(u);
                            beaconCluster.getBeacon(u).addUav(currentUAV);
                            beaconCluster.getBeacon(u).incrementWaitingUavCount();
                            uavQueue.add(currentUAV);
                            LogManager.getInstance().log("client" + currentUAV.getClientId() + " UAV" + currentUAV.getId() + " is waiting at " + u + "(" + u + " -> " + v + ") adjustRemainingFlow");
                        }
                        countOfUAV++;
                    }
                }
            }

            if (!pathFound) {
                LogManager.getInstance().log("有効な経路が見つかりませんでした");
                break;
            }
        }

        UAV_count += countOfUAV; // UAV_count を適切に更新
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
            LogManager.getInstance().log("AbstractPhysarumSolver: No positive source outflows found. Skipping rounding.");
            return;
        }

        // ソース流出を丸める（targetFlowに合わせる）
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
    }
}
