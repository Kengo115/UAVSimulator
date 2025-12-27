package server.controller;

import client.Client;
import client.ClientController;
import item.Beacon;
import item.BeaconCluster;
import item.Link;
import item.Uav;
import server.network.NetworkTopologyManager;
import server.network.TopologyFileReader;
import server.route.BinaryExtendedPhysarumSolverRouteSearcher;
import server.route.DijkstraRouteSearcher;
import server.route.ExtendedPhysarumSolverRouteSearcher;
import server.route.HybridPhysarumSolverRouteSearcher;
import server.route.PhysarumSolverRouteSearcher;
import server.route.RouteSearcher;
import server.uav.CapacityManager;
import server.uav.FlightDataRecorder;
import server.uav.UAVFlightController;
import server.uav.UAVFlyScheduler;
import server.util.ResultOutputManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Queue;

/**
 * サーバー側の処理を制御するコントローラークラス
 */
public class ServerController {

    // 定数
    private static final double INF = 10000.0;
    private static final double INIT_THICKNESS = 0.5;
    private static final double INIT_LENGTH = 1.0;
    private static final double INIT_RATE = 100.0;

    // ノード数
    private static int node;
    
    // 実行カウンター
    private int runCounter = 0;
    
    // リンク情報
    private static Link[][] link;
    
    // ビーコンクラスター
    private static BeaconCluster beaconCluster;
    
    // 隣接行列
    private int[][] adjMatrix;
    
    // UAVカウント
    private int UAV_count;
    
    // 経路探索アルゴリズム
    private RouteSearcher dijkstraRouteSearcher;
    private RouteSearcher physarumSolverRouteSearcher;
    private RouteSearcher extendedPhysarumSolverRouteSearcher;
    private RouteSearcher hybridPhysarumSolverRouteSearcher;
    private RouteSearcher binaryExtendedPhysarumSolverRouteSearcher;
    
    // ネットワークトポロジーマネージャー
    private NetworkTopologyManager networkTopologyManager;

    /**
     * コンストラクタ
     * @param node ノード数
     */
    public ServerController(int node) {
        initialize(node);
    }
    
    /**
     * コンストラクタ
     * @param node ノード数
     * @param beaconCluster ビーコンクラスター
     */
    public ServerController(int node, BeaconCluster beaconCluster) {
        initialize(node);
        ServerController.beaconCluster = beaconCluster;
        this.networkTopologyManager = new NetworkTopologyManager(node, link, beaconCluster, adjMatrix);
    }

    /**
     * コンストラクタ（外部ファイルから読み込んだトポロジデータを使用）
     * @param beaconCluster ビーコンクラスター
     * @param topologyData トポロジデータ
     */
    public ServerController(BeaconCluster beaconCluster, TopologyFileReader.TopologyData topologyData) {
        initialize(topologyData.nodeCount);
        ServerController.beaconCluster = beaconCluster;
        this.networkTopologyManager = new NetworkTopologyManager(link, beaconCluster, adjMatrix, topologyData);
    }

    /**
     * 初期化処理
     * @param node ノード数
     */
    public void initialize(int node) {
        ServerController.node = node;
        
        // 隣接行列の初期化
        this.adjMatrix = new int[node][node];
        
        // リンク情報の初期化
        link = new Link[node][node];
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                link[i][j] = new Link();
            }
        }
    }

    /**
     * 経路探索アルゴリズムを初期化する
     */
    public void initializeRouteSearchers() {
        this.dijkstraRouteSearcher = new DijkstraRouteSearcher(this, adjMatrix, link, beaconCluster, node);
        this.physarumSolverRouteSearcher = new PhysarumSolverRouteSearcher(this, adjMatrix, link, beaconCluster, node);
        this.extendedPhysarumSolverRouteSearcher = new ExtendedPhysarumSolverRouteSearcher(this, adjMatrix, link, beaconCluster, node);
        this.hybridPhysarumSolverRouteSearcher = new HybridPhysarumSolverRouteSearcher(this, adjMatrix, link, beaconCluster, node);
        this.binaryExtendedPhysarumSolverRouteSearcher = new BinaryExtendedPhysarumSolverRouteSearcher(this, adjMatrix, link, beaconCluster, node);
    }

    /**
     * フィールドをすべてリセットする
     */
    public void reset() {
        // 隣接行列の初期化
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                adjMatrix[i][j] = 0;
                link[i][j].setD_tubeThickness(0.0);
                link[i][j].setL_tubeLength(INF);
                link[i][j].setQ_tubeFlow(0.0);
            }
        }
        
        // 基本的なリンクの設定
        networkTopologyManager.setupBasicLinks();
    }

    /**
     * リンクを設定する
     * @param node ノード数
     * @param beaconCluster ビーコンクラスター
     */
    public void setLink(int node, BeaconCluster beaconCluster) {
        ServerController.node = node;
        ServerController.beaconCluster = beaconCluster;

        // リンクの初期化
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                link[i][j].setD_tubeThickness(0.0);
                link[i][j].setL_tubeLength(INF);
            }
        }

        // 詳細なリンクの設定
        networkTopologyManager.setupDetailedLinks();
    }
    
    /**
     * ネットワークトポロジーマネージャーを取得する
     * @return ネットワークトポロジーマネージャー
     */
    public NetworkTopologyManager getNetworkTopologyManager() {
        return networkTopologyManager;
    }

    /**
     * Dijkstra法による経路探索を実行する
     * @param client クライアント
     * @param clientController クライアントコントローラー
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @throws IOException 入出力例外
     */
    public void run_Dijkstra(Client client, ClientController clientController, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue) throws IOException {
        if (runCounter != 0) {
            reset();
        }

        // 飛行中のUAVがある場合、UAVFlySchedulerを停止
        if (!flyingUavQueue.isEmpty()) {
            UAVFlyScheduler.stopFlyUAVUpdates(clientController);
        }

        // 隣接行列の更新
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    adjMatrix[i][j] = 1;
                }
            }
        }

        // Dijkstra法による経路探索
        dijkstraRouteSearcher.search(client, flyingUavQueue, uavQueue, 1);

        if (runCounter != 0) {
            // UAVFlySchedulerを開始
            UAVFlyScheduler.startFlyUAVUpdates(flyingUavQueue, uavQueue, clientController);
        }

        runCounter++;
    }
    
    /**
     * PhysarumSolver法による経路探索を実行する
     * @param client クライアント
     * @param clientController クライアントコントローラー
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param numLoop 反復回数
     * @throws IOException 入出力例外
     */
    public void run_PS(Client client, ClientController clientController, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, int numLoop) throws IOException {
        if (runCounter != 0) {
            reset();
        }

        // 飛行中のUAVがある場合、UAVFlySchedulerを停止
        if (!flyingUavQueue.isEmpty()) {
            UAVFlyScheduler.stopFlyUAVUpdates(clientController);
        }

        // PhysarumSolver法による経路探索
        physarumSolverRouteSearcher.search(client, flyingUavQueue, uavQueue, numLoop);

        if (runCounter != 0) {
            // UAVFlySchedulerを開始
            UAVFlyScheduler.startFlyUAVUpdates(flyingUavQueue, uavQueue, clientController);
        }

        runCounter++;
    }
    
    /**
     * ExtendedPhysarumSolver法による経路探索を実行する
     * @param client クライアント
     * @param clientController クライアントコントローラー
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param numLoop 反復回数
     * @throws IOException 入出力例外
     */
    public void run_EPS(Client client, ClientController clientController, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, int numLoop) throws IOException {
        if (runCounter != 0) {
            reset();
        }

        // 飛行中のUAVがある場合、UAVFlySchedulerを停止
        if (!flyingUavQueue.isEmpty()) {
            UAVFlyScheduler.stopFlyUAVUpdates(clientController);
        }

        // ExtendedPhysarumSolver法による経路探索
        extendedPhysarumSolverRouteSearcher.search(client, flyingUavQueue, uavQueue, numLoop);

        if (runCounter != 0) {
            // UAVFlySchedulerを開始
            UAVFlyScheduler.startFlyUAVUpdates(flyingUavQueue, uavQueue, clientController);
        }

        runCounter++;
    }
    
    /**
     * ハイブリッドPhysarumSolver法による経路探索を実行する
     * @param client クライアント
     * @param clientController クライアントコントローラー
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param numLoop 反復回数
     * @throws IOException 入出力例外
     */
    public void run_Hybrid(Client client, ClientController clientController, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, int numLoop) throws IOException {
        if (runCounter != 0) {
            reset();
        }

        // 飛行中のUAVがある場合、UAVFlySchedulerを停止
        if (!flyingUavQueue.isEmpty()) {
            UAVFlyScheduler.stopFlyUAVUpdates(clientController);
        }

        // ハイブリッドPhysarumSolver法による経路探索
        hybridPhysarumSolverRouteSearcher.search(client, flyingUavQueue, uavQueue, numLoop);

        if (runCounter != 0) {
            // UAVFlySchedulerを開始
            UAVFlyScheduler.startFlyUAVUpdates(flyingUavQueue, uavQueue, clientController);
        }

        runCounter++;
    }
    
    /**
     * バイナリサーチExtendedPhysarumSolver法による経路探索を実行する
     * @param client クライアント
     * @param clientController クライアントコントローラー
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param numLoop 反復回数
     * @throws IOException 入出力例外
     */
    public void run_Binary(Client client, ClientController clientController, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, int numLoop) throws IOException {
        if (runCounter != 0) {
            reset();
        }

        // 飛行中のUAVがある場合、UAVFlySchedulerを停止
        if (!flyingUavQueue.isEmpty()) {
            UAVFlyScheduler.stopFlyUAVUpdates(clientController);
        }

        // バイナリサーチExtendedPhysarumSolver法による経路探索
        binaryExtendedPhysarumSolverRouteSearcher.search(client, flyingUavQueue, uavQueue, numLoop);

        if (runCounter != 0) {
            // UAVFlySchedulerを開始
            UAVFlyScheduler.startFlyUAVUpdates(flyingUavQueue, uavQueue, clientController);
        }

        runCounter++;
    }

    /**
     * UAVを移動させる
     * @param clientController クライアントコントローラー
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     */
    public static void flyUAV(ClientController clientController, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue) {
        UAVFlightController.flyUAV(clientController, flyingUavQueue, uavQueue, link, beaconCluster, node);
    }

    /**
     * UAVカウントを取得する
     * @return UAVカウント
     */
    public int getUAVCount() {
        return UAV_count;
    }

    /**
     * UAVカウントを設定する
     * @param UAV_count UAVカウント
     */
    public void setUAVCount(int UAV_count) {
        this.UAV_count = UAV_count;
    }

    /**
     * 実行カウンターを取得する
     * @return 実行カウンター
     */
    public int getRunCounter() {
        return runCounter;
    }
    
    /**
     * ネットワークトポロジーをPajekファイルに出力する
     * @param NET_file 出力ファイルパス
     * @param client クライアント
     * @param beaconList ビーコンクラスター
     * @throws IOException 入出力例外
     */
    public void nodeConfigureToPajek(String NET_file, Client client, BeaconCluster beaconList) throws IOException {
        double maxDistance = Math.sqrt(2);  // 最大距離 sqrt(2)

        // sourceとdistを取得
        Beacon source = client.getFlow().getSource();
        Beacon dist = client.getFlow().getDestination();
        boolean fig_SOURCE = false;
        boolean fig_DIST = false;

        // ファイル出力処理
        try (FileWriter writer = new FileWriter(new File(NET_file))) {
            writer.write("*Vertices\t" + node + "\n");
            for (int i = 0; i < node; i++) {
                if (i == source.getId()) {
                    fig_SOURCE = true;
                }

                if (i == dist.getId()) {
                    fig_DIST = true;
                }

                if (fig_SOURCE || fig_DIST) {
                    writer.write(String.format("%d \"%d\" %.4f %.4f ic Black\n", i + 1, i + 1, beaconList.getBeacon(i).getX(), beaconList.getBeacon(i).getY()));
                } else {
                    writer.write(String.format("%d \"%d\" %.4f %.4f ic White\n", i + 1, i + 1, beaconList.getBeacon(i).getX(), beaconList.getBeacon(i).getY()));
                }
                fig_SOURCE = false;
                fig_DIST = false;
            }
            writer.write("*Arcs\n*Edges\n");

            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (i != j && link[i][j].getL_tubeLength() != INF) {
                        writer.write(String.format("%d %d 1\n", i + 1, j + 1));
                    }
                }
            }
        }
    }
}
