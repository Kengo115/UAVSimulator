package operator.controller;

import shared.client.Client;
import shared.client.ClientController;
import shared.item.Beacon;
import shared.item.BeaconCluster;
import shared.item.Link;
import shared.network.NetworkTopologyManager;
import shared.network.TopologyFileReader;
import operator.route.BinaryExtendedPhysarumSolverRouteSearcher;
import operator.route.BisectionalPressureGuidedEPSRouteSearcher;
import operator.route.DijkstraRouteSearcher;
import operator.route.ExtendedPhysarumSolverRouteSearcher;
import operator.route.HybridPhysarumSolverRouteSearcher;
import operator.route.PhysarumSolverRouteSearcher;
import operator.route.RouteSearcher;
import operator.route.StepControlledPressureGuidedEPSRouteSearcher;
import shared.redis.LinkCapacityManager;
import operator.scheduler.SearcherRetryManager;
import shared.util.LogManager;
import shared.util.ResultOutputManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

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
    // Phase 4: PG-EPS (Pressure-Guided EPS)
    private RouteSearcher bisectionalPGEPSRouteSearcher;
    private RouteSearcher stepControlledPGEPSRouteSearcher;
    
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
        // Phase 4: PG-EPS (Pressure-Guided EPS)
        this.bisectionalPGEPSRouteSearcher = new BisectionalPressureGuidedEPSRouteSearcher(this, adjMatrix, link, beaconCluster, node);
        this.stepControlledPGEPSRouteSearcher = new StepControlledPressureGuidedEPSRouteSearcher(this, adjMatrix, link, beaconCluster, node);
    }

    /**
     * Phase 3b-6: リンク情報を取得する
     * @param from 始点ノード
     * @param to 終点ノード
     * @return リンク情報（存在しない場合はnull）
     */
    public Link getLink(int from, int to) {
        if (from >= 0 && from < node && to >= 0 && to < node) {
            Link l = link[from][to];
            // 隣接していない場合はnullを返す
            if (adjMatrix[from][to] == 1) {
                return l;
            }
        }
        return null;
    }

    /**
     * Phase 7-4: staticなリンク情報取得（LinkStatusRecorder用）
     * @param from 始点ノード
     * @param to 終点ノード
     * @return リンク情報（存在しない場合はnull）
     */
    public static Link getLinkStatic(int from, int to) {
        if (link != null && from >= 0 && from < node && to >= 0 && to < node) {
            return link[from][to];
        }
        return null;
    }

    /**
     * Phase 7-4: リンク配列を取得
     * @return リンク配列
     */
    public static Link[][] getLinkArray() {
        return link;
    }

    /**
     * Phase 7-4: ノード数を取得
     * @return ノード数
     */
    public static int getNodeCount() {
        return node;
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
     * Phase 5: SearcherRetryManagerによる排他制御・再試行管理
     * @param client クライアント
     * @param clientController クライアントコントローラー
     * @throws IOException 入出力例外
     */
    public void run_Dijkstra(Client client, ClientController clientController) throws IOException {
        final int currentRunCounter = runCounter;
        SearcherRetryManager.SearchRequest request = new SearcherRetryManager.SearchRequest(
            client,
            clientController,
            1,
            dijkstraRouteSearcher,
            // preSearchAction: 検索前処理（容量同期など）
            () -> {
                if (currentRunCounter != 0) {
                    reset();
                }
                LinkCapacityManager capacityManager = new LinkCapacityManager();
                if (currentRunCounter == 0) {
                    capacityManager.initializeCapacitiesToRedis(link, node);
                } else {
                    capacityManager.syncCapacitiesToMemory(link, node);
                }
                // 隣接行列の更新
                for (int i = 0; i < node; i++) {
                    for (int j = 0; j < node; j++) {
                        if (link[i][j].getL_tubeLength() != INF) {
                            adjMatrix[i][j] = 1;
                        }
                    }
                }
            },
            // postSearchAction: 検索後処理
            () -> {}
        );

        boolean success = SearcherRetryManager.getInstance().requestSearch(request);
        if (!success) {
            LogManager.getInstance().log("Phase 5: client" + client.getId() + " の経路探索がスキップされました（Dijkstra）");
        }

        runCounter++;
    }
    
    /**
     * PhysarumSolver法による経路探索を実行する
     * Phase 5: SearcherRetryManagerによる排他制御・再試行管理
     * @param client クライアント
     * @param clientController クライアントコントローラー
     * @param numLoop 反復回数
     * @throws IOException 入出力例外
     */
    public void run_PS(Client client, ClientController clientController, int numLoop) throws IOException {
        final int currentRunCounter = runCounter;
        SearcherRetryManager.SearchRequest request = new SearcherRetryManager.SearchRequest(
            client,
            clientController,
            numLoop,
            physarumSolverRouteSearcher,
            () -> {
                if (currentRunCounter != 0) {
                    reset();
                }
                LinkCapacityManager capacityManager = new LinkCapacityManager();
                if (currentRunCounter == 0) {
                    capacityManager.initializeCapacitiesToRedis(link, node);
                } else {
                    capacityManager.syncCapacitiesToMemory(link, node);
                }
            },
            () -> {}
        );

        boolean success = SearcherRetryManager.getInstance().requestSearch(request);
        if (!success) {
            LogManager.getInstance().log("Phase 5: client" + client.getId() + " の経路探索がスキップされました（PS）");
        }

        runCounter++;
    }
    
    /**
     * ExtendedPhysarumSolver法による経路探索を実行する
     * Phase 5: SearcherRetryManagerによる排他制御・再試行管理
     * @param client クライアント
     * @param clientController クライアントコントローラー
     * @param numLoop 反復回数
     * @throws IOException 入出力例外
     */
    public void run_EPS(Client client, ClientController clientController, int numLoop) throws IOException {
        final int currentRunCounter = runCounter;
        SearcherRetryManager.SearchRequest request = new SearcherRetryManager.SearchRequest(
            client,
            clientController,
            numLoop,
            extendedPhysarumSolverRouteSearcher,
            () -> {
                if (currentRunCounter != 0) {
                    reset();
                }
                LinkCapacityManager capacityManager = new LinkCapacityManager();
                if (currentRunCounter == 0) {
                    capacityManager.initializeCapacitiesToRedis(link, node);
                } else {
                    capacityManager.syncCapacitiesToMemory(link, node);
                }
            },
            () -> {}
        );

        boolean success = SearcherRetryManager.getInstance().requestSearch(request);
        if (!success) {
            LogManager.getInstance().log("Phase 5: client" + client.getId() + " の経路探索がスキップされました（EPS）");
        }

        runCounter++;
    }
    
    /**
     * ハイブリッドPhysarumSolver法による経路探索を実行する
     * Phase 5: SearcherRetryManagerによる排他制御・再試行管理
     * @param client クライアント
     * @param clientController クライアントコントローラー
     * @param numLoop 反復回数
     * @throws IOException 入出力例外
     */
    public void run_Hybrid(Client client, ClientController clientController, int numLoop) throws IOException {
        final int currentRunCounter = runCounter;
        SearcherRetryManager.SearchRequest request = new SearcherRetryManager.SearchRequest(
            client,
            clientController,
            numLoop,
            hybridPhysarumSolverRouteSearcher,
            () -> {
                if (currentRunCounter != 0) {
                    reset();
                }
                LinkCapacityManager capacityManager = new LinkCapacityManager();
                if (currentRunCounter == 0) {
                    capacityManager.initializeCapacitiesToRedis(link, node);
                } else {
                    capacityManager.syncCapacitiesToMemory(link, node);
                }
            },
            () -> {}
        );

        boolean success = SearcherRetryManager.getInstance().requestSearch(request);
        if (!success) {
            LogManager.getInstance().log("Phase 5: client" + client.getId() + " の経路探索がスキップされました（Hybrid）");
        }

        runCounter++;
    }
    
    /**
     * バイナリサーチExtendedPhysarumSolver法による経路探索を実行する
     * Phase 5: SearcherRetryManagerによる再試行管理
     * @param client クライアント
     * @param clientController クライアントコントローラー
     * @param numLoop 反復回数
     * @throws IOException 入出力例外
     */
    public void run_Binary(Client client, ClientController clientController, int numLoop) throws IOException {
        final int currentRunCounter = runCounter;
        SearcherRetryManager.SearchRequest request = new SearcherRetryManager.SearchRequest(
            client,
            clientController,
            numLoop,
            binaryExtendedPhysarumSolverRouteSearcher,
            () -> {
                if (currentRunCounter != 0) {
                    reset();
                }
                LinkCapacityManager capacityManager = new LinkCapacityManager();
                if (currentRunCounter == 0) {
                    capacityManager.initializeCapacitiesToRedis(link, node);
                } else {
                    capacityManager.syncCapacitiesToMemory(link, node);
                }
            },
            () -> {}
        );

        boolean success = SearcherRetryManager.getInstance().requestSearch(request);
        if (!success) {
            LogManager.getInstance().log("Phase 5: client" + client.getId() + " の経路探索がスキップされました（Binary）");
        }

        runCounter++;
    }

    /**
     * Phase 4: 二分法型圧力誘導EPS (Bisectional PG-EPS) による経路探索を実行する
     * Phase 5: SearcherRetryManagerによる再試行管理
     * 最大フロー超過分は経路待ちキューで管理し、第一ホップ通過時に経路コピーで飛行開始
     * @param client クライアント
     * @param clientController クライアントコントローラー
     * @param numLoop 反復回数
     * @throws IOException 入出力例外
     */
    public void run_BisectionalPGEPS(Client client, ClientController clientController, int numLoop) throws IOException {
        final int currentRunCounter = runCounter;
        SearcherRetryManager.SearchRequest request = new SearcherRetryManager.SearchRequest(
            client,
            clientController,
            numLoop,
            bisectionalPGEPSRouteSearcher,
            () -> {
                if (currentRunCounter != 0) {
                    reset();
                }
                LinkCapacityManager capacityManager = new LinkCapacityManager();
                if (currentRunCounter == 0) {
                    capacityManager.initializeCapacitiesToRedis(link, node);
                } else {
                    capacityManager.syncCapacitiesToMemory(link, node);
                }
            },
            () -> {}
        );

        boolean success = SearcherRetryManager.getInstance().requestSearch(request);
        if (!success) {
            LogManager.getInstance().log("Phase 5: client" + client.getId() + " の経路探索がスキップされました（BisectionalPGEPS）");
        }

        runCounter++;
    }

    /**
     * Phase 4: 段階制御型圧力誘導EPS (Step-Controlled PG-EPS) による経路探索を実行する
     * Phase 5: SearcherRetryManagerによる排他制御・再試行管理
     * 最大フロー超過分は経路待ちキューで管理し、第一ホップ通過時に経路コピーで飛行開始
     * @param client クライアント
     * @param clientController クライアントコントローラー
     * @param numLoop 反復回数
     * @throws IOException 入出力例外
     */
    public void run_StepControlledPGEPS(Client client, ClientController clientController, int numLoop) throws IOException {
        final int currentRunCounter = runCounter;
        SearcherRetryManager.SearchRequest request = new SearcherRetryManager.SearchRequest(
            client,
            clientController,
            numLoop,
            stepControlledPGEPSRouteSearcher,
            () -> {
                if (currentRunCounter != 0) {
                    reset();
                }
                LinkCapacityManager capacityManager = new LinkCapacityManager();
                if (currentRunCounter == 0) {
                    capacityManager.initializeCapacitiesToRedis(link, node);
                } else {
                    capacityManager.syncCapacitiesToMemory(link, node);
                }
            },
            () -> {}
        );

        boolean success = SearcherRetryManager.getInstance().requestSearch(request);
        if (!success) {
            LogManager.getInstance().log("Phase 5: client" + client.getId() + " の経路探索がスキップされました（StepControlledPGEPS）");
        }

        runCounter++;
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
