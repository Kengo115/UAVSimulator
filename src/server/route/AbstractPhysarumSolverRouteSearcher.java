package server.route;

import client.Client;
import client.ClientController;
import controller.BoundaryController;
import item.BeaconCluster;
import item.Link;
import item.Uav;
import server.controller.ServerController;
import server.redis.ClientTimeManager;
import server.redis.LinkCapacityManager;
import server.redis.UAVJob;
import server.redis.UAVJobQueue;
import server.util.Link117DebugLogger;
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

            // フロー分解アルゴリズムでは整数丸め込みは不要
            // （explorePath/explorePathGreedyがリアルタイムでフローを減算する）

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
            
            // 最後のループの場合に実行する処理（フロー分解アルゴリズムによる経路割り当て）
            if (ct == numLoop) {
                LogManager.getInstance().log("EPS計算完了: フロー分解アルゴリズムで経路割り当てを開始");

                // スタートノード、ゴールノード、必要なUAV台数を取得
                int startNode = client.getFlow().getSource().getId();
                int goalNode = client.getFlow().getDestination().getId();
                int requiredUAVs = (int) client.getFlow().getTheNumberOfUAV();

                // 実際のUAVに経路を割り当てるためのメイン処理
                // Phase 1: BFSでフロー≥1.0の経路を探索
                // Phase 2: グリーディ探索で残りのUAVに経路を割り当て
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

        // Phase 8-Fix: 容量予約用のLinkCapacityManager
        LinkCapacityManager capacityManager = new LinkCapacityManager();

        // 同一リンクごとに2秒間隔でジョブ投入するためのスケジューラ
        ScheduledExecutorService enqueueScheduler = Executors.newScheduledThreadPool(1);

        UAV_count = 0;
        boolean flowAvailable = true;

        // 一ホップ目リンクごとの投入順序を管理（キー: "from-to"）
        Map<String, Integer> linkEnqueueOrder = new HashMap<>();

        while (UAV_count < requiredUAVs && flowAvailable) {
            min_Flow = 100;

            int[] path = new int[40];
            int pathIndex = 0;
            path[pathIndex++] = startNode;
            maxPathIndex = pathIndex;

            int flow = explorePath(startNode, startNode, goalNode, path, pathIndex, 0);

            if (flow > 0) {
                int pathLength = maxPathIndex;
                int[] pathArray = Arrays.copyOf(path, pathLength);

                // 容量消費はAsyncUAVWorker/FlightSchedulerで各リンク進入時に行う
                // （FlightSchedulerのRace condition対策により待機UAVがいるリンクは容量0のまま）

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

                        // DEBUG: 117-123を含む経路の場合、専用ログに記録
                        Link117DebugLogger.getInstance().logRouteAssign(clientId, uavId, finalPathArray, 1);

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

        // Phase 8-Fix: ScheduledExecutorServiceをシャットダウン
        // shutdown()を呼ぶと新しいタスクは受け付けなくなるが、
        // スケジュール済みタスクは実行され、完了後にスレッドは自動終了する
        // awaitTermination()で待機すると次のクライアント生成が遅延するため、待機しない
        enqueueScheduler.shutdown();
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
     * Phase 2: 残りのUAVをグリーディ探索で割り当てる（Redis版）
     * 各UAVごとに最大残存フローのリンクを選択して経路を決定
     * 同一リンクのUAVは2秒間隔でジョブを投入
     */
    protected void adjustRemainingFlowRedis(int needUAV, int startNode, int goalNode, Client client,
                                            UAVJobQueue jobQueue, ScheduledExecutorService enqueueScheduler,
                                            Map<String, Integer> linkEnqueueOrder) {
        int clientId = client.getId();
        LogManager.getInstance().log("Phase 2: グリーディ探索で残り" + needUAV + "台を割り当て");

        for (int i = 0; i < needUAV; i++) {
            // 各UAVごとにグリーディ経路を探索
            int[] path = new int[40];
            boolean found = explorePathGreedy(startNode, goalNode, path);

            if (!found) {
                LogManager.getInstance().error("Phase 2: UAV " + (UAV_count + 1) + "/" + (UAV_count + needUAV - i) + " の経路が見つかりませんでした");
                // フォールバック: BFSで最短経路を探索
                int[] fallbackPath = findSimplePath(startNode, goalNode);
                if (fallbackPath == null || fallbackPath.length < 2) {
                    LogManager.getInstance().error("Phase 2: フォールバック経路も見つかりませんでした");
                    continue;
                }
                path = fallbackPath;
            } else {
                // explorePathGreedyはmaxPathIndexを設定するので、それを使用
                path = Arrays.copyOf(path, maxPathIndex);
            }

            final int[] pathArray = path;
            Uav uav = client.getFlow().getUav(UAV_count);
            int uavId = uav.getId();
            double uavSpeed = uav.getSpeed();

            // 一ホップ目リンクのキーを作成
            String firstLinkKey = pathArray[0] + "-" + pathArray[1];

            // このリンクの現在の投入順序を取得・更新
            int currentOrder = linkEnqueueOrder.getOrDefault(firstLinkKey, 0);
            linkEnqueueOrder.put(firstLinkKey, currentOrder + 1);

            final int delaySeconds = currentOrder * 2;
            double[] linkDistances = calculateLinkDistances(pathArray);
            final double[] finalLinkDistances = linkDistances;

            // 同一リンクのUAVは2秒間隔でジョブ投入をスケジュール
            enqueueScheduler.schedule(() -> {
                UAVJob job = new UAVJob(
                    uavId,
                    clientId,
                    pathArray,
                    uavSpeed,
                    System.currentTimeMillis(),
                    startNode,
                    goalNode
                );
                job.setLinkDistances(finalLinkDistances);
                job.setSessionId(BoundaryController.getCurrentSessionId());

                jobQueue.enqueueJob(job);
                LogManager.getInstance().log("Phase 2: UAV" + uavId + " グリーディ経路ジョブ投入完了 (経路: " + Arrays.toString(pathArray) + ", 速度: " + String.format("%.2f", uavSpeed) + "m/s)");

                // DEBUG: 117-123を含む経路の場合、専用ログに記録
                Link117DebugLogger.getInstance().logRouteAssign(clientId, uavId, pathArray, 2);

                try {
                    ResultOutputManager.outputRouteAssignment(job, clientId);
                } catch (IOException e) {
                    LogManager.getInstance().error("Phase 2: 経路割り当て記録エラー", e);
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

            int[] path = new int[40];
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
     * Phase 1: 幅優先探索でフロー≥1.0の経路を探索し、floor(minFlow)台のUAVを割り当てる
     * Ford-Fulkerson法に基づくフロー分解アルゴリズム
     *
     * @param startNode 開始ノード
     * @param currentNode 現在のノード（互換性のため、startNodeと同じ値を渡す）
     * @param goalNode 目標ノード
     * @param path 経路（結果を格納）
     * @param pathIndex 経路インデックス（互換性のため、1を渡す）
     * @param passedFlow 通過フロー（互換性のため、0を渡す）
     * @return フロー（経路上の最小フローのfloor値、0以上の整数）
     */
    protected int explorePath(int startNode, int currentNode, int goalNode, int[] path, int pathIndex, int passedFlow) {
        // BFSで最短経路を探索（フロー≥1.0のリンクのみ使用）
        int[] parent = new int[node];
        Arrays.fill(parent, -1);
        parent[startNode] = startNode; // 自分自身を親として開始点をマーク

        java.util.Queue<Integer> queue = new java.util.LinkedList<>();
        queue.add(startNode);

        boolean found = false;

        while (!queue.isEmpty() && !found) {
            int current = queue.poll();

            for (int next = 0; next < node; next++) {
                // Phase 1: フロー≥1.0のリンクのみを使用（tubeFlowではなくlink.getQ_tubeFlow()を直接参照）
                if (parent[next] == -1 &&
                    link[current][next].getL_tubeLength() != INF &&
                    link[current][next].getQ_tubeFlow() >= 1.0) {
                    parent[next] = current;

                    if (next == goalNode) {
                        found = true;
                        break;
                    }

                    queue.add(next);
                }
            }
        }

        if (!found) {
            return 0; // 経路が見つからない
        }

        // 経路を復元（ゴールからスタートへ逆順にたどる）
        java.util.List<Integer> pathList = new java.util.ArrayList<>();
        int current = goalNode;
        while (current != startNode) {
            pathList.add(0, current); // 先頭に追加
            current = parent[current];
        }

        // 経路長チェック（path[0]にはstartNodeが既に入っているので、pathList.size() + 1が実際の経路長）
        if (pathList.size() + 1 > path.length) {
            LogManager.getInstance().log("警告: 経路長(" + (pathList.size() + 1) + ")が上限(" + path.length + ")を超えています");
            return 0;
        }

        // 経路をpath配列にコピー（path[0]はstartNodeのまま、path[1]以降にpathListをコピー）
        for (int i = 0; i < pathList.size(); i++) {
            path[i + 1] = pathList.get(i);  // path[1]から始める
        }
        maxPathIndex = pathList.size() + 1;  // startNodeを含めた経路長

        // 経路上の最小フローを計算（実数値）
        double minFlowDouble = Double.MAX_VALUE;
        int prevNode = startNode;
        for (int i = 0; i < pathList.size(); i++) {
            int nextNode = pathList.get(i);
            minFlowDouble = Math.min(minFlowDouble, link[prevNode][nextNode].getQ_tubeFlow());
            prevNode = nextNode;
        }

        // floor(minFlow)を取得（整数台数）
        int floorMinFlow = (int) Math.floor(minFlowDouble);
        if (floorMinFlow <= 0) {
            return 0;
        }

        // 経路上の各リンクからfloorMinFlowを減算
        prevNode = startNode;
        for (int i = 0; i < pathList.size(); i++) {
            int nextNode = pathList.get(i);
            double currentFlow = link[prevNode][nextNode].getQ_tubeFlow();
            link[prevNode][nextNode].setQ_tubeFlow(currentFlow - floorMinFlow);
            // 逆方向のフローも更新（双方向リンクの場合）
            if (link[nextNode][prevNode].getL_tubeLength() != INF) {
                double reverseFlow = link[nextNode][prevNode].getQ_tubeFlow();
                link[nextNode][prevNode].setQ_tubeFlow(reverseFlow + floorMinFlow);
            }
            prevNode = nextNode;
        }

        min_Flow = floorMinFlow;
        return floorMinFlow;
    }

    /**
     * Phase 2: フロー優先探索で1台分の経路を見つける
     * 正のフロー(>0)があるリンクのみを使用し、フロー値が大きいリンクを優先
     * 優先度付きキュー（最大フロー優先）を使用してダイクストラ的に探索
     * 経路発見後、各リンクのフローを1減少させる
     *
     * @param startNode 開始ノード
     * @param goalNode 目標ノード
     * @param path 経路（結果を格納）
     * @return true: 経路が見つかった, false: 経路が見つからない
     */
    protected boolean explorePathGreedy(int startNode, int goalNode, int[] path) {
        // 各ノードへの「最大の最小フロー」を記録（ボトルネックフロー最大化）
        double[] maxMinFlow = new double[node];
        Arrays.fill(maxMinFlow, Double.NEGATIVE_INFINITY);
        maxMinFlow[startNode] = Double.MAX_VALUE;

        int[] parent = new int[node];
        Arrays.fill(parent, -1);
        parent[startNode] = startNode;

        // 優先度付きキュー: (ノード, そのノードまでの経路の最小フロー) - 最小フローが大きい順
        java.util.PriorityQueue<double[]> pq = new java.util.PriorityQueue<>(
            (a, b) -> Double.compare(b[1], a[1])  // 降順（大きいフロー優先）
        );
        pq.add(new double[]{startNode, Double.MAX_VALUE});

        boolean found = false;

        while (!pq.isEmpty() && !found) {
            double[] curr = pq.poll();
            int current = (int) curr[0];
            double currentMinFlow = curr[1];

            // より良い経路が既に見つかっている場合はスキップ
            if (currentMinFlow < maxMinFlow[current]) {
                continue;
            }

            for (int next = 0; next < node; next++) {
                if (link[current][next].getL_tubeLength() != INF) {
                    double edgeFlow = link[current][next].getQ_tubeFlow();

                    // 正のフローがあるリンクのみ使用
                    if (edgeFlow > 0) {
                        // このリンクを通った場合の最小フロー
                        double newMinFlow = Math.min(currentMinFlow, edgeFlow);

                        // より良い経路が見つかった場合のみ更新
                        if (newMinFlow > maxMinFlow[next]) {
                            maxMinFlow[next] = newMinFlow;
                            parent[next] = current;

                            if (next == goalNode) {
                                found = true;
                                break;
                            }

                            pq.add(new double[]{next, newMinFlow});
                        }
                    }
                }
            }
        }

        if (!found) {
            return false;
        }

        // 経路を復元（ゴールからスタートへ逆順にたどる）
        java.util.List<Integer> pathList = new java.util.ArrayList<>();
        int current = goalNode;
        while (current != startNode) {
            pathList.add(0, current);
            current = parent[current];
        }
        pathList.add(0, startNode);

        // 経路長チェック
        if (pathList.size() > path.length) {
            LogManager.getInstance().log("警告: Phase 2 経路長(" + pathList.size() + ")が上限(" + path.length + ")を超えています");
            return false;
        }

        // 経路をpath配列にコピー
        for (int i = 0; i < pathList.size(); i++) {
            path[i] = pathList.get(i);
        }
        maxPathIndex = pathList.size();

        // 経路上の各リンクからフローを1減少
        for (int i = 0; i < pathList.size() - 1; i++) {
            int from = pathList.get(i);
            int to = pathList.get(i + 1);
            double currentFlow = link[from][to].getQ_tubeFlow();
            link[from][to].setQ_tubeFlow(currentFlow - 1.0);
            // 逆方向のフローも更新
            if (link[to][from].getL_tubeLength() != INF) {
                double reverseFlow = link[to][from].getQ_tubeFlow();
                link[to][from].setQ_tubeFlow(reverseFlow + 1.0);
            }
        }

        return true;
    }

    /**
     * Phase 2: 残りのUAVにグリーディ探索で経路を割り当てる（メモリ版）
     * 各UAVごとに最大残存フローのリンクを選択して経路を決定
     *
     * @param needUAV 必要なUAV数
     * @param startNode 開始ノード
     * @param goalNode 目標ノード
     * @param client クライアント
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     */
    protected void adjustRemainingFlow(int needUAV, int startNode, int goalNode, Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue) {
        LogManager.getInstance().log("Phase 2: グリーディ探索で残り" + needUAV + "台を割り当て（メモリモード）");
        int countOfUAV = 0;

        while (countOfUAV < needUAV) {
            // 各UAVごとにグリーディ経路を探索
            int[] path = new int[40];
            boolean found = explorePathGreedy(startNode, goalNode, path);

            if (!found) {
                LogManager.getInstance().error("Phase 2: UAV " + (UAV_count + countOfUAV + 1) + " の経路が見つかりませんでした");
                // フォールバック: BFSで最短経路を探索
                int[] fallbackPath = findSimplePath(startNode, goalNode);
                if (fallbackPath == null || fallbackPath.length < 2) {
                    LogManager.getInstance().error("Phase 2: フォールバック経路も見つかりませんでした。処理を中断します。");
                    break;
                }
                path = fallbackPath;
            } else {
                // explorePathGreedyはmaxPathIndexを設定するので、それを使用
                path = Arrays.copyOf(path, maxPathIndex);
            }

            int[] assignedPath = path;
            int u = assignedPath[0];
            int v = assignedPath[1];
            double minCapacity = link[u][v].getCapacity();

            int currentUAVIndex = UAV_count + countOfUAV;
            Uav currentUAV = client.getFlow().getUav(currentUAVIndex);
            currentUAV.setPath(assignedPath);
            server.uav.FlightDataRecorder.recordRoute(currentUAV, getRemainingRouteRecordTag());

            // Phase 7-2: 経路割り当て情報を記録（メモリモード・Phase 2）
            try {
                ResultOutputManager.outputRouteAssignment(
                    currentUAV.getId(),
                    currentUAV.getSource().getId(),
                    currentUAV.getDistination().getId(),
                    currentUAV.getPath(),
                    currentUAV.getClientId()
                );
            } catch (IOException e) {
                LogManager.getInstance().error("Phase 2: 経路割り当て記録エラー（メモリモード）", e);
            }

            if (countOfUAV < minCapacity) {
                currentUAV.startTimer();
                currentUAV.setFlyingLink(link[u][v]);
                currentUAV.setPassedLink(link[u][v]);
                flyingUavQueue.add(currentUAV);
                link[u][v].decrementCapacity();
                LogManager.getInstance().log("Phase 2: client" + currentUAV.getClientId() + " UAV" + currentUAV.getId() + " flying " + u + "->" + v + " (経路: " + Arrays.toString(assignedPath) + ")");
            } else {
                currentUAV.startWaitingTimer();
                currentUAV.setStayedBeaconId(u);
                beaconCluster.getBeacon(u).addUav(currentUAV);
                beaconCluster.getBeacon(u).incrementWaitingUavCount();
                uavQueue.add(currentUAV);
                LogManager.getInstance().log("Phase 2: client" + currentUAV.getClientId() + " UAV" + currentUAV.getId() + " waiting at " + u + " (経路: " + Arrays.toString(assignedPath) + ")");
            }
            countOfUAV++;
        }

        UAV_count += countOfUAV;
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
