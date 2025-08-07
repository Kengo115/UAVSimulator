package server.route;

import client.Client;
import client.ClientController;
import item.Link;
import item.Uav;
import server.communication.ResultOutputManager;
import server.util.ConfigurationManager;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Dijkstraアルゴリズムによる経路探索を行う実装クラス
 */
public class DijkstraRouteSearcherImpl implements DijkstraRouteSearcher {
    
    private Link[][] linkMatrix;
    private int nodeNum;
    private int[][] adjMatrix;
    private int runCounter;
    private ResultOutputManager outputManager;
    private ConfigurationManager config;
    private int uavCount;
    
    /**
     * コンストラクタ
     * 
     * @param linkMatrix リンク行列
     * @param nodeNum ノード数
     * @param outputManager 結果出力管理
     */
    public DijkstraRouteSearcherImpl(Link[][] linkMatrix, int nodeNum, ResultOutputManager outputManager) {
        this.linkMatrix = linkMatrix;
        this.nodeNum = nodeNum;
        this.outputManager = outputManager;
        this.config = ConfigurationManager.getInstance();
        this.adjMatrix = new int[nodeNum][nodeNum];
        this.runCounter = 0;
        this.uavCount = 0;
    }
    
    /**
     * 経路探索を実行し、UAVに経路を割り当てる
     * 
     * @param client クライアント
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param numLoop 繰り返し回数（Dijkstraでは使用しない）
     * @throws IOException 入出力例外
     */
    @Override
    public void searchAndAssignRoutes(Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, int numLoop) throws IOException {
        run_Dijkstra(client, null, flyingUavQueue, uavQueue);
    }
    
    /**
     * アルゴリズム名を取得する
     * 
     * @return アルゴリズム名
     */
    @Override
    public String getAlgorithmName() {
        return "Dijkstra";
    }
    
    /**
     * Dijkstraアルゴリズムを実行する
     * 
     * @param client クライアント
     * @param clientController クライアントコントローラ
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @throws IOException 入出力例外
     */
    @Override
    public void run_Dijkstra(Client client, ClientController clientController, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue) throws IOException {
        if (runCounter != 0) {
            // 更新メソッドを呼び出す
            reset();
        }
        
        // 隣接行列の初期化
        for (int i = 0; i < nodeNum; i++) {
            for (int j = 0; j < nodeNum; j++) {
                if (linkMatrix[i][j].getL_tubeLength() != config.getInf()) {
                    adjMatrix[i][j] = 1;
                }
            }
        }
        
        // Dijkstraメソッドを呼び出して、結果の配列を受け取る
        int[] path = dijkstra(client);
        
        // スタートノード、ゴールノード、必要なUAV台数を取得
        int requiredUAVs = (int) client.getFlow().getTheNumberOfUAV();
        
        // 実際のUAVに経路を割り当てるためのメイン処理
        runUAVFlow_Dijkstra(client, path, flyingUavQueue, uavQueue, requiredUAVs);
        
        runCounter++;
    }
    
    /**
     * Dijkstraアルゴリズムで最短経路を計算する
     * 
     * @param client クライアント
     * @return 計算された経路
     */
    @Override
    public int[] dijkstra(Client client) {
        // 出発地と目的地のノードIDを取得
        int source = client.getFlow().getSource().getId();
        int destination = client.getFlow().getDestination().getId();
        
        // 必要な配列を定義
        double[] minDist = new double[nodeNum];    // 各ノードへの最短距離
        int[] minHops = new int[nodeNum];          // 各ノードへのホップ数
        boolean[] visited = new boolean[nodeNum]; // 訪問済みフラグ
        int[] previous = new int[nodeNum];         // 経路復元用の配列
        int[] unvisited = new int[nodeNum];        // 未訪問ノードのリスト
        
        // 初期化
        for (int i = 0; i < nodeNum; i++) {
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
            for (int i = 0; i < nodeNum; i++) {
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
            for (int neighbor = 0; neighbor < nodeNum; neighbor++) {
                if (adjMatrix[currentNode][neighbor] == 1 && !visited[neighbor]) {
                    // 距離を計算
                    double newDist = minDist[currentNode] + linkMatrix[currentNode][neighbor].getDistance();
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
        int[] path = new int[nodeNum];
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
     * 
     * @param client クライアント
     * @param path 経路
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param requiredUAVs 必要なUAV数
     */
    @Override
    public void runUAVFlow_Dijkstra(Client client, int[] path, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, int requiredUAVs) {
        // 最小Capacityを計算する
        int u = path[0];
        int v = path[1];
        // UAVの飛行をスケジュールするためのスレッドプールを作成
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        
        double minCapacity = linkMatrix[u][v].getCapacity();
        int flow_count = 0;
        
        // UAVがrequiredUAVsより少ない場合はすべてのUAVに経路を割り当て
        for (int f = 0; f < requiredUAVs; f++) {
            int currentUAVIndex = uavCount + f;
            Uav currentUAV = client.getFlow().getUav(currentUAVIndex);
            
            if (flow_count < minCapacity) {
                // 2秒ごとに飛行開始
                scheduler.schedule(() -> {
                    currentUAV.setPath(path);
                    outputManager.outputRoute(currentUAV, "Dijkstra");
                    currentUAV.startTimer();
                    currentUAV.setFlyingLink(linkMatrix[u][v]);
                    currentUAV.setPassedLink(linkMatrix[u][v]);
                    flyingUavQueue.add(currentUAV);
                    linkMatrix[u][v].decrementCapacity();
                }, f * 2, TimeUnit.SECONDS);
                System.out.println("UAV " + currentUAV.getId() + " is flying from " + u + " to " + v);
                
                flow_count++;
            } else {
                // UAVを待機させる処理
                currentUAV.setPath(path);
                outputManager.outputRoute(currentUAV, "Dijkstra-waiting");
                currentUAV.startWaitingTimer();
                currentUAV.setStayedBeaconId(u);
                client.getFlow().getSource().addUav(currentUAV);
                client.getFlow().getSource().incrementWaitingUavCount();
                uavQueue.add(currentUAV);
                System.out.println("UAV " + currentUAV.getId() + " is waiting at " + u);
            }
        }
        
        uavCount += requiredUAVs;
        scheduler.shutdown();
    }
    
    /**
     * 隣接行列を取得する
     * 
     * @return 隣接行列
     */
    @Override
    public int[][] getAdjMatrix() {
        return adjMatrix;
    }
    
    /**
     * 隣接行列を設定する
     * 
     * @param adjMatrix 隣接行列
     */
    @Override
    public void setAdjMatrix(int[][] adjMatrix) {
        this.adjMatrix = adjMatrix;
    }
    
    /**
     * リセットする
     */
    private void reset() {
        // 隣接行列をリセット
        for (int i = 0; i < nodeNum; i++) {
            for (int j = 0; j < nodeNum; j++) {
                adjMatrix[i][j] = 0;
            }
        }
    }
    
    /**
     * リンク行列を設定する
     * 
     * @param linkMatrix リンク行列
     */
    public void setLinkMatrix(Link[][] linkMatrix) {
        this.linkMatrix = linkMatrix;
    }
    
    /**
     * ノード数を設定する
     * 
     * @param nodeNum ノード数
     */
    public void setNodeNum(int nodeNum) {
        this.nodeNum = nodeNum;
        this.adjMatrix = new int[nodeNum][nodeNum];
    }
    
    /**
     * 結果出力管理を設定する
     * 
     * @param outputManager 結果出力管理
     */
    public void setOutputManager(ResultOutputManager outputManager) {
        this.outputManager = outputManager;
    }
    
    /**
     * 実行カウンターを設定する
     * 
     * @param runCounter 実行カウンター
     */
    public void setRunCounter(int runCounter) {
        this.runCounter = runCounter;
    }
    
    /**
     * UAVカウントを設定する
     * 
     * @param uavCount UAVカウント
     */
    public void setUavCount(int uavCount) {
        this.uavCount = uavCount;
    }
}
