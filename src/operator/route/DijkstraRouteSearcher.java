package operator.route;

import shared.client.Client;
import operator.BoundaryController;
import operator.debug.DebugModeHook;
import shared.item.BeaconCluster;
import shared.item.Link;
import shared.item.Uav;
import operator.controller.ServerController;
import shared.redis.ClientTimeManager;
import shared.item.UAVJob;
import shared.redis.UAVJobQueue;
import shared.util.LogManager;
import shared.util.ResultOutputManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Dijkstra法による経路探索を行うクラス
 */
public class DijkstraRouteSearcher implements RouteSearcher {
    private final ServerController serverController;
    private final int[][] adjMatrix;
    private final Link[][] link;
    private final BeaconCluster beaconCluster;
    private final int node;

    /**
     * コンストラクタ
     * @param serverController サーバーコントローラー
     * @param adjMatrix 隣接行列
     * @param link リンク情報
     * @param beaconCluster ビーコンクラスター
     * @param node ノード数
     */
    public DijkstraRouteSearcher(ServerController serverController, int[][] adjMatrix, Link[][] link, BeaconCluster beaconCluster, int node) {
        this.serverController = serverController;
        this.adjMatrix = adjMatrix;
        this.link = link;
        this.beaconCluster = beaconCluster;
        this.node = node;
    }

    @Override
    public void search(Client client, int numLoop) throws IOException {
        // Dijkstraメソッドを呼び出して、結果の配列を受け取る
        int[] path = dijkstra(client);

        // スタートノード、ゴールノード、必要なUAV台数を取得
        int requiredUAVs = (int) client.getFlow().getTheNumberOfUAV();

        // 実際のUAVに経路を割り当てるためのメイン処理
        runUAVFlow(client, path, requiredUAVs);
    }

    /**
     * Dijkstra法で最短経路を探索する
     * @param client クライアント
     * @return 最短経路
     */
    private int[] dijkstra(Client client) {
        // 出発地と目的地のノードIDを取得
        int source = client.getFlow().getSource().getId();
        int destination = client.getFlow().getDestination().getId();

        // 必要な配列を定義
        double[] minDist = new double[node];    // 各ノードへの最短距離
        int[] minHops = new int[node];          // 各ノードへのホップ数
        boolean[] visited = new boolean[node]; // 訪問済みフラグ
        int[] previous = new int[node];         // 経路復元用の配列
        int[] unvisited = new int[node];        // 未訪問ノードのリスト

        // 初期化
        for (int i = 0; i < node; i++) {
            minDist[i] = Double.POSITIVE_INFINITY; // 初期距離を無限大に
            minHops[i] = Integer.MAX_VALUE;        // 初期ホップ数を最大値に
            visited[i] = false;                    // 全ノード未訪問
            previous[i] = -1;                      // 前のノードを-1で初期化
            unvisited[i] = i;                      // 未訪問ノードリストを初期化
        }
        minDist[source] = 0;                        // 出発ノードの距離は0
        minHops[source] = 0;                        // 出発ノードのホップ数は0

        // Dijkstra法のメインループ
        while (true) {
            // 最短距離で未訪問のノードを探索
            int currentNode = -1;
            double shortestDistance = Double.POSITIVE_INFINITY;
            for (int i = 0; i < node; i++) {
                if (!visited[unvisited[i]] && minDist[unvisited[i]] < shortestDistance) {
                    currentNode = unvisited[i];
                    shortestDistance = minDist[currentNode];
                }
            }

            // 未訪問ノードが見つからない場合、または目的地に到達した場合は終了
            if (currentNode == -1 || currentNode == destination) {
                break;
            }

            // 現在のノードを訪問済みにマーク
            visited[currentNode] = true;

            // 隣接ノードを探索
            for (int neighbor = 0; neighbor < node; neighbor++) {
                if (adjMatrix[currentNode][neighbor] == 1 && !visited[neighbor]) {
                    // 距離を計算
                    double newDist = minDist[currentNode] + link[currentNode][neighbor].getDistance();
                    int newHops = minHops[currentNode] + 1;

                    // 条件に応じて更新
                    if (newDist < minDist[neighbor] ||
                            (Double.compare(newDist, minDist[neighbor]) == 0 && newHops < minHops[neighbor])) {
                        minDist[neighbor] = newDist;
                        minHops[neighbor] = newHops;
                        previous[neighbor] = currentNode;
                    }
                }
            }
        }

        // 経路を復元（逆順で格納し直す）
        int[] path = new int[node];
        int pathIndex = 0; // 経路の現在のインデックス
        for (int at = destination; at != -1; at = previous[at]) {
            path[pathIndex++] = at;
        }

        // 出発ノードから到達できない場合
        if (pathIndex == 1 && path[0] != source) {
            return new int[0]; // 空の配列を返す
        }

        // 経路を逆順にする
        int[] result = new int[pathIndex];
        for (int i = 0; i < pathIndex; i++) {
            result[i] = path[pathIndex - i - 1];
        }
        return result;
    }

    /**
     * UAVに経路を割り当てる
     * @param client クライアント
     * @param path 経路
     * @param requiredUAVs 必要なUAV数
     */
    private void runUAVFlow(Client client, int[] path, int requiredUAVs) {
        // Phase 4: 時間計測開始（全経路探索手法共通）
        ClientTimeManager.getInstance().startClientTime(client.getId(), requiredUAVs);
        runUAVFlowRedis(client, path, requiredUAVs);
    }

    /**
     * Phase 3b-6: Redis経由でUAVジョブを投入する
     * Dijkstraでは全UAVが同一経路のため、同一の一ホップ目リンクを使用
     * そのため全UAVを2秒間隔で投入
     * @param client クライアント
     * @param path 経路
     * @param requiredUAVs 必要なUAV数
     */
    private void runUAVFlowRedis(Client client, int[] path, int requiredUAVs) {
        String firstLink = path[0] + "-" + path[1];
        LogManager.getInstance().log("Phase 3b-6: Redisモードでジョブ投入 (" + requiredUAVs + "機, 一ホップ目: " + firstLink + ", 2秒間隔)");

        // リンク距離を計算
        double[] linkDistances = calculateLinkDistances(path);

        // UAVJobQueueを取得
        UAVJobQueue jobQueue = new UAVJobQueue();

        // 各UAVに対してジョブを作成・投入
        int clientId = client.getId();
        int sourceBeaconId = path[0];
        int destinationBeaconId = path[path.length - 1];

        // メモリベースと同様に2秒間隔でジョブ投入するためのスケジューラ
        ScheduledExecutorService enqueueScheduler = Executors.newScheduledThreadPool(1);

        // デバッグモード: 全UAV情報を収集後に FLY_APPROVED まで待機
        if (DebugModeHook.isDebugMode()) {
            List<DebugModeHook.PendingJobInfo> debugJobs = new ArrayList<>();
            for (int f = 0; f < requiredUAVs; f++) {
                Uav uav = client.getFlow().getUav(f);
                debugJobs.add(new DebugModeHook.PendingJobInfo(
                    uav.getId(), clientId, path, linkDistances, uav.getSpeed(), f * 2));
            }
            DebugModeHook.getInstance().onPendingJobsReady(
                clientId, debugJobs, path[0], path[path.length - 1]);
            try {
                DebugModeHook.getInstance().waitForFlyApproved(clientId);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                enqueueScheduler.shutdown();
                return;
            }
            // RESET によるラッチ解放の場合はジョブ投入をスキップ
            if (DebugModeHook.isResetActive()) {
                LogManager.getInstance().log(
                    "DebugModeHook: client" + clientId + " RESET検出 → ジョブ投入スキップ (Dijkstra)");
                enqueueScheduler.shutdown();
                return;
            }
        }

        for (int f = 0; f < requiredUAVs; f++) {
            Uav uav = client.getFlow().getUav(f);
            int uavId = uav.getId();
            // 各UAVの個別速度を使用（8~16 m/sのランダム値）
            double uavSpeed = uav.getSpeed();
            final int finalF = f;

            // 2秒間隔でジョブ投入をスケジュール
            enqueueScheduler.schedule(() -> {
                // UAVJobを作成（投入時刻を開始時刻として設定）
                UAVJob job = new UAVJob(
                    uavId,
                    clientId,
                    path,
                    uavSpeed,
                    System.currentTimeMillis(),
                    sourceBeaconId,
                    destinationBeaconId
                );
                job.setLinkDistances(linkDistances);
                // Phase 3b-8: セッションIDを設定
                job.setSessionId(BoundaryController.getCurrentSessionId());

                // キューに投入
                jobQueue.enqueueJob(job);
                LogManager.getInstance().log("Phase 3b-6: UAV" + uavId + " ジョブ投入完了 (経路: " + formatPath(path) + ", 速度: " + String.format("%.2f", uavSpeed) + "m/s)");

                // Phase 7-2: 経路割り当て情報を記録
                // clientIdは1始まり、runCounterは0始まりなので-1して調整
                try {
                    ResultOutputManager.outputRouteAssignment(job, clientId - 1);
                } catch (IOException e) {
                    LogManager.getInstance().error("Phase 7-2: 経路割り当て記録エラー", e);
                }
            }, finalF * 2, TimeUnit.SECONDS);
        }

        LogManager.getInstance().log("Phase 3b-6: 全" + requiredUAVs + "件のジョブを2秒間隔でスケジュールしました");

        // Phase 8-Fix: ScheduledExecutorServiceをシャットダウン
        // shutdown()を呼ぶと新しいタスクは受け付けなくなるが、
        // スケジュール済みタスクは実行され、完了後にスレッドは自動終了する
        // awaitTermination()で待機すると次のクライアント生成が遅延するため、待機しない
        enqueueScheduler.shutdown();
    }

    /**
     * 経路を文字列に整形
     */
    private String formatPath(int[] path) {
        if (path == null || path.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < path.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(path[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Phase 3b-6: リンク距離を計算する
     * @param path 経路
     * @return 各リンクの距離配列
     */
    private double[] calculateLinkDistances(int[] path) {
        double[] distances = new double[path.length - 1];
        for (int i = 0; i < path.length - 1; i++) {
            int from = path[i];
            int to = path[i + 1];
            distances[i] = link[from][to].getDistance();
        }
        return distances;
    }

}

