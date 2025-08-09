package server.route;

import client.Client;
import client.ClientController;
import item.BeaconCluster;
import item.Link;
import item.Uav;
import server.controller.ServerController;
import server.util.LogManager;

import java.io.IOException;
import java.util.Queue;
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
    public void search(Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, int numLoop) throws IOException {
        // Dijkstraメソッドを呼び出して、結果の配列を受け取る
        int[] path = dijkstra(client);

        // スタートノード、ゴールノード、必要なUAV台数を取得
        int requiredUAVs = (int) client.getFlow().getTheNumberOfUAV();

        // 実際のUAVに経路を割り当てるためのメイン処理
        runUAVFlow(client, path, flyingUavQueue, uavQueue, requiredUAVs);
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
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param requiredUAVs 必要なUAV数
     */
    private void runUAVFlow(Client client, int[] path, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, int requiredUAVs) {
        // 最初のリンクを取得
        int u = path[0];
        int v = path[1];
        
        // UAVの飛行をスケジュールするためのスレッドプールを作成
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        double minCapacity = link[u][v].getCapacity();
        int flow_count = 0;

        // UAVがrequiredUAVsより少ない場合はすべてのUAVに経路を割り当て
        for (int f = 0; f < requiredUAVs; f++) {
            int currentUAVIndex = f;
            Uav currentUAV = client.getFlow().getUav(currentUAVIndex);

            if (flow_count < minCapacity) {
                // 2秒ごとに飛行開始
                final int finalF = f;
                scheduler.schedule(() -> {
                    currentUAV.setPath(path);
                    currentUAV.startTimer();
                    currentUAV.setFlyingLink(link[u][v]);
                    currentUAV.setPassedLink(link[u][v]);
                    flyingUavQueue.add(currentUAV);
                    link[u][v].decrementCapacity();
                }, finalF * 2, TimeUnit.SECONDS);
                LogManager.getInstance().log("client" + currentUAV.getClientId() + " UAV" + currentUAV.getId() + " is flying from " + u + " to " + v);

                flow_count++;
            } else {
                // UAVを待機させる処理
                currentUAV.setPath(path);
                currentUAV.startWaitingTimer();
                currentUAV.setStayedBeaconId(u);
                beaconCluster.getBeacon(u).addUav(currentUAV);
                beaconCluster.getBeacon(u).incrementWaitingUavCount();
                uavQueue.add(currentUAV);
                LogManager.getInstance().log("client" + currentUAV.getClientId() + " UAV" + currentUAV.getId() + " is waiting at " + u);
            }
        }
    }
}
