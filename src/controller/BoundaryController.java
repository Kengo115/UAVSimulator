package controller;

import client.Client;
import client.ClientController;
import item.Beacon;
import item.BeaconCluster;
import item.Flow;
import item.Uav;
import server.controller.ServerController;
import server.network.TopologyFileReader;
import server.redis.*;
import server.scheduler.FlightScheduler;
import server.uav.UAVFlyScheduler;
import server.util.LogManager;
import server.worker.AsyncUAVWorker;

import java.io.*;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


public class BoundaryController {
    private static int num_loop = 500;
    private static int nodeNum;
    // ビーコンクラスタークラスを生成
    static BeaconCluster beaconCluster;
    public Beacon[] beaconList;
    static ServerController server;
    static Client client;
    static int clientId = 1;
    static ClientController clientController = new ClientController();
    static Queue<Uav> uavQueue = new LinkedList<>();
    static Queue<Uav> flyingUavQueue = new LinkedList<>();
    static Queue<Client> passedClient = new LinkedList<>();
    Flow flow;

    // Phase 3b-6: Redisベースワーカー用
    // Phase 3b-10: 4ワーカー固定
    private static final int WORKER_COUNT = 4;
    private static List<AsyncUAVWorker> asyncWorkers = new ArrayList<>();
    private static ExecutorService workerExecutor;
    private static UAVCompletionListener completionListener;
    private static FlightScheduler flightScheduler;

    // Phase 3b-8: セッションID（古いプロセスからのジョブを無視するため）
    private static String currentSessionId;

    String filePath = "src/result/practice.net";

    private static int trial = 5;

    // ネットワークトポロジーを設定する関数
    public void setNetworkTopology() throws IOException {
        // ビーコンクラスタークラスを取得
        beaconList = new Beacon[nodeNum];
        beaconList = beaconCluster.getBeaconList();
        // ビーコンの情報を設定
        setLink();
    }

    private void setLink() {
        server.setLink(nodeNum, beaconCluster);
    }

    public void setNodeNum(int nodeNum) {
        this.nodeNum = nodeNum;
    }

    public int getNodeNum() {
        return nodeNum;
    }

    // クライアントを生成する関数
    public Client createClient1() {
        int sourceId = 0;
        int destinationId = 5;
        Beacon source = beaconCluster.getBeacon(sourceId);
        Beacon destination = beaconCluster.getBeacon(destinationId);

        int uavNum = 12;
        // flowListにsource, destination, uavNumを格納
        flow = new Flow(source, destination, uavNum);

        Client client = new Client(flow, 1);
        clientController.addClient(client);

        return client;
    }

    public Client createClient2() {
        int sourceId = 0;
        int destinationId = 5;
        Beacon source = beaconCluster.getBeacon(sourceId);
        Beacon destination = beaconCluster.getBeacon(destinationId);

        int uavNum = 12;
        // flowListにsource, destination, uavNumを格納
        flow = new Flow(source, destination, uavNum);

        Client client = new Client(flow, 2);
        clientController.addClient(client);

        return client;
    }

    public Client createClient3() {
        int sourceId = 2;
        int destinationId = 4;
        Beacon source = beaconCluster.getBeacon(sourceId);
        Beacon destination = beaconCluster.getBeacon(destinationId);

        int uavNum = 14;
        // flowListにsorrce, destination, uavNumを格納
        flow = new Flow(source, destination, uavNum);

        Client client = new Client(flow, 3);
        clientController.addClient(client);

        return client;
    }

    // sourceId, destinationId, uavNumをランダムに決める
    public Client createRandomClient() {
        if (clientId == 1) {
            int sourceId = 0;
            int destinationId = 5;
            while (sourceId == destinationId) {
                destinationId = (int) (Math.random() * nodeNum);
            }
            Beacon source = beaconCluster.getBeacon(sourceId);
            Beacon destination = beaconCluster.getBeacon(destinationId);

            int uavNum = 20; // + (int)(Math.random() * 20);
            // flowListにsource, destination, uavNumを格納
            flow = new Flow(source, destination, uavNum);

            Client client = new Client(flow, clientId);
            clientController.addClient(client);
            clientId++;
            return client;
        } else if (clientId == 2) {
            int sourceId = 0;
            int destinationId = 5;
            while (sourceId == destinationId) {
                destinationId = (int) (Math.random() * nodeNum);
            }
            Beacon source = beaconCluster.getBeacon(sourceId);
            Beacon destination = beaconCluster.getBeacon(destinationId);

            int uavNum = 20; // + (int)(Math.random() * 20);
            // flowListにsource, destination, uavNumを格納
            flow = new Flow(source, destination, uavNum);

            Client client = new Client(flow, clientId);
            clientController.addClient(client);
            clientId++;
            return client;
        } else {
            int sourceId = 2;
            int destinationId = 4;
            while (sourceId == destinationId) {
                destinationId = (int) (Math.random() * nodeNum);
            }
            Beacon source = beaconCluster.getBeacon(sourceId);
            Beacon destination = beaconCluster.getBeacon(destinationId);

            int uavNum = 20; // + (int)(Math.random() * 20);
            // flowListにsource, destination, uavNumを格納
            flow = new Flow(source, destination, uavNum);

            Client client = new Client(flow, clientId);
            clientController.addClient(client);
            clientId++;
            return client;
        }
    }

    // 経路探索手法の列挙型
    public enum RouteSearchMethod {
        DIJKSTRA(1, "Dijkstra"),
        PS(2, "PS"),
        EPS(3, "EPS"),
        HYBRID(4, "Hybrid"),
        BINARY(5, "Binary"),
        BISECTIONAL_PGEPS(6, "Bisectional"),
        STEP_CONTROLLED_PGEPS(7, "StepControlled");

        private final int id;
        private final String name;

        RouteSearchMethod(int id, String name) {
            this.id = id;
            this.name = name;
        }

        public int getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public static RouteSearchMethod fromId(int id) {
            for (RouteSearchMethod method : values()) {
                if (method.getId() == id) {
                    return method;
                }
            }
            return EPS; // デフォルトはEPS
        }
    }
    
    // ログモードの列挙型
    public enum LoggingMode {
        DISABLED(1, "無効"),
        ENABLED(2, "有効");

        private final int id;
        private final String name;

        LoggingMode(int id, String name) {
            this.id = id;
            this.name = name;
        }

        public int getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public static LoggingMode fromId(int id) {
            for (LoggingMode mode : values()) {
                if (mode.getId() == id) {
                    return mode;
                }
            }
            return DISABLED;
        }
    }

    /**
     * Phase 3b-6: ワーカーモードの列挙型
     * MEMORY: 従来のメモリベース処理（UAVFlyScheduler）
     * REDIS: Redisベース処理（AsyncUAVWorker + FlightScheduler）
     */
    public enum WorkerMode {
        MEMORY(1, "メモリベース"),
        REDIS(2, "Redisベース");

        private final int id;
        private final String name;

        WorkerMode(int id, String name) {
            this.id = id;
            this.name = name;
        }

        public int getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public static WorkerMode fromId(int id) {
            for (WorkerMode mode : values()) {
                if (mode.getId() == id) {
                    return mode;
                }
            }
            return MEMORY; // デフォルトはメモリベース
        }
    }

    // 現在選択されている経路探索手法
    private static RouteSearchMethod currentMethod = RouteSearchMethod.EPS;

    // 現在選択されているログモード
    private static LoggingMode currentLoggingMode = LoggingMode.DISABLED;

    // Phase 3b-6: 現在選択されているワーカーモード
    private static WorkerMode currentWorkerMode = WorkerMode.MEMORY;
    
    /**
     * 経路探索手法を設定する
     * @param method 経路探索手法
     */
    public static void setRouteSearchMethod(RouteSearchMethod method) {
        currentMethod = method;
    }
    
    /**
     * 現在の経路探索手法を取得する
     * @return 経路探索手法
     */
    public static RouteSearchMethod getCurrentMethod() {
        return currentMethod;
    }
    
    /**
     * ログモードを設定する
     * @param mode ログモード
     */
    public static void setLoggingMode(LoggingMode mode) {
        currentLoggingMode = mode;
        LogManager.getInstance().setLoggingEnabled(mode == LoggingMode.ENABLED);
    }
    
    /**
     * 現在のログモードを取得する
     * @return ログモード
     */
    public static LoggingMode getCurrentLoggingMode() {
        return currentLoggingMode;
    }

    /**
     * Phase 3b-6: ワーカーモードを設定する
     * @param mode ワーカーモード
     */
    public static void setWorkerMode(WorkerMode mode) {
        currentWorkerMode = mode;
        LogManager.getInstance().log("Phase 3b-6: ワーカーモードを " + mode.getName() + " に設定しました");
    }

    /**
     * Phase 3b-6: 現在のワーカーモードを取得する
     * @return ワーカーモード
     */
    public static WorkerMode getCurrentWorkerMode() {
        return currentWorkerMode;
    }

    /**
     * Phase 3b-6: Redisワーカーを初期化する
     */
    private static void initializeRedisWorker() {
        try {
            // Phase 3b-8: セッションIDを生成（古いプロセスからのジョブを無視するため）
            currentSessionId = UUID.randomUUID().toString().substring(0, 8);
            LogManager.getInstance().log("Phase 3b-8: 新しいセッションID生成: " + currentSessionId);

            // FlightSchedulerを初期化
            flightScheduler = FlightScheduler.getInstance();
            flightScheduler.resetCounters();
            flightScheduler.setSessionId(currentSessionId);

            // 完了リスナーを開始
            completionListener = new UAVCompletionListener();
            completionListener.startListening();

            // Phase 3b-10: AsyncUAVWorker x4をバックグラウンドで起動
            workerExecutor = Executors.newFixedThreadPool(WORKER_COUNT);
            asyncWorkers.clear();
            for (int i = 0; i < WORKER_COUNT; i++) {
                AsyncUAVWorker worker = new AsyncUAVWorker("async-worker-" + (i + 1));
                asyncWorkers.add(worker);
                workerExecutor.submit(() -> worker.start());
            }
            LogManager.getInstance().log("Phase 3b-10: " + WORKER_COUNT + "ワーカーを起動しました");

            // ジョブキューをクリア
            UAVJobQueue jobQueue = new UAVJobQueue();
            jobQueue.clearQueue();

            // 待機キューをクリア
            WaitingUAVManager waitingManager = new WaitingUAVManager();
            waitingManager.clearAll();

            // リンク容量をRedisに初期化
            initializeLinkCapacities();

            System.out.println("✓ Redisワーカーを起動しました (workers=" + WORKER_COUNT + ", セッションID: " + currentSessionId + ")");
            LogManager.getInstance().log("Phase 3b-6: Redisワーカー初期化完了");

        } catch (Exception e) {
            System.err.println("⚠ Redisワーカー初期化失敗: " + e.getMessage());
            LogManager.getInstance().error("Phase 3b-6: Redisワーカー初期化失敗", e);
            setWorkerMode(WorkerMode.MEMORY);
        }
    }

    /**
     * Phase 3b-8: 現在のセッションIDを取得する
     * @return セッションID（Redisモードでない場合はnull）
     */
    public static String getCurrentSessionId() {
        return currentSessionId;
    }

    /**
     * Phase 3b-6: リンク容量をRedisに初期化する
     */
    private static void initializeLinkCapacities() {
        try {
            LinkCapacityManager capacityManager = new LinkCapacityManager();
            int initialized = 0;

            // すべてのリンク容量を初期化
            for (int i = 0; i < nodeNum; i++) {
                for (int j = 0; j < nodeNum; j++) {
                    if (server.getLink(i, j) != null) {
                        double capacity = server.getLink(i, j).getCapacity();
                        capacityManager.saveCapacity(i, j, capacity);
                        initialized++;
                    }
                }
            }

            LogManager.getInstance().log("Phase 3b-6: " + initialized + "件のリンク容量をRedisに初期化しました");
        } catch (Exception e) {
            LogManager.getInstance().error("Phase 3b-6: リンク容量初期化エラー", e);
        }
    }

    /**
     * Phase 3b-6: Redisワーカーを停止する
     */
    private static void shutdownRedisWorker() {
        try {
            // Phase 3b-10: 全ワーカーを停止
            for (AsyncUAVWorker worker : asyncWorkers) {
                if (worker != null) {
                    worker.stop();
                }
            }
            asyncWorkers.clear();

            if (workerExecutor != null) {
                workerExecutor.shutdownNow();
                workerExecutor.awaitTermination(5, TimeUnit.SECONDS);
            }

            if (completionListener != null) {
                completionListener.stopListening();
            }

            FlightScheduler.resetInstance();

            LogManager.getInstance().log("Phase 3b-10: " + WORKER_COUNT + "ワーカーをシャットダウンしました");
        } catch (Exception e) {
            LogManager.getInstance().error("Phase 3b-10: Redisワーカーシャットダウンエラー", e);
        }
    }

    /**
     * Phase 3b-6: 完了リスナーを取得する（統計情報用）
     */
    public static UAVCompletionListener getCompletionListener() {
        return completionListener;
    }

    public void routeRequest(Client client) throws IOException {
        // Pajekファイルにネットワークトポロジーを出力
        server.nodeConfigureToPajek(filePath, client, beaconCluster);
        
        // 選択された経路探索手法に基づいて実行
        switch (currentMethod) {
            case DIJKSTRA:
                server.run_Dijkstra(client, clientController, flyingUavQueue, uavQueue);
                break;
            case PS:
                server.run_PS(client, clientController, flyingUavQueue, uavQueue, num_loop);
                break;
            case HYBRID:
                server.run_Hybrid(client, clientController, flyingUavQueue, uavQueue, num_loop);
                break;
            case BINARY:
                server.run_Binary(client, clientController, flyingUavQueue, uavQueue, num_loop);
                break;
            case BISECTIONAL_PGEPS:
                server.run_BisectionalPGEPS(client, clientController, flyingUavQueue, uavQueue, num_loop);
                break;
            case STEP_CONTROLLED_PGEPS:
                server.run_StepControlledPGEPS(client, clientController, flyingUavQueue, uavQueue, num_loop);
                break;
            case EPS:
            default:
                server.run_EPS(client, clientController, flyingUavQueue, uavQueue, num_loop);
                break;
        }
    }

    public static void main(String[] args) {
        BoundaryController boundaryController = new BoundaryController();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        // Phase 1: Redis接続を確立
        try {
            RedisConnectionManager redisManager = RedisConnectionManager.getInstance();
            redisManager.connect();
            System.out.println("✓ Redisに接続しました: " + redisManager.getConnectionInfo());
        } catch (IOException e) {
            System.err.println("⚠ Redis接続に失敗しました。メモリベースで動作します。");
            LogManager.getInstance().error("Redis接続失敗", e);
        }

        // ランダムクライアント生成実験
        try {
            // トポロジファイルのパスを入力
            System.out.println("=== UAVシミュレーター ===");
            System.out.println("ネットワークトポロジファイルのパスを入力してください:");
            System.out.println("（Enterキーのみを押すとデフォルトトポロジを使用します）");
            System.out.print("> ");

            String topologyFilePath = reader.readLine().trim();

            // トポロジの初期化
            if (topologyFilePath.isEmpty()) {
                // デフォルト（ハードコード）
                System.out.println("デフォルトトポロジを使用します。");
                boundaryController.setNodeNum(6);
                beaconCluster = new BeaconCluster(nodeNum);
                server = new ServerController(nodeNum, beaconCluster);
            } else {
                // 外部ファイルから読み込み
                System.out.println("トポロジファイルを読み込んでいます: " + topologyFilePath);
                TopologyFileReader.TopologyData topologyData =
                    TopologyFileReader.readTopologyFile(topologyFilePath);

                // トポロジ情報を表示
                TopologyFileReader.printTopologyInfo(topologyData);

                // ビーコンとサーバーを初期化
                boundaryController.setNodeNum(topologyData.nodeCount);
                beaconCluster = new BeaconCluster(topologyData);
                server = new ServerController(beaconCluster, topologyData);
            }

            // 経路探索アルゴリズムを初期化
            server.initializeRouteSearchers();

            boundaryController.setNetworkTopology(); // ネットワークの初期化

            // 経路探索手法を選択
            System.out.println("経路探索手法を選択してください:");
            System.out.println("1: Dijkstra法");
            System.out.println("2: PhysarumSolver法 (PS)");
            System.out.println("3: ExtendedPhysarumSolver法 (EPS)");
            System.out.println("4: ハイブリッド法 (HYBRID: EPS+PS)");
            System.out.println("5: バイナリサーチ法 (BINARY: Binary Search EPS+PS)");
            System.out.println("6: 二分法型圧力誘導法 (BISECTIONAL_PG-EPS)");
            System.out.println("7: ステップ制御型圧力誘導法 (STEP_CONTROLLED_PG-EPS)");

            int methodChoice = 3; // デフォルトはEPS
            try {
                String input = reader.readLine();
                if (!input.trim().isEmpty()) {
                    methodChoice = Integer.parseInt(input);
                    if (methodChoice < 1 || methodChoice > 7) {
                        System.out.println("無効な選択です。ExtendedPhysarumSolver法 (EPS) を使用します。");
                        methodChoice = 3;
                    }
                }
            } catch (NumberFormatException e) {
                System.out.println("無効な入力です。ExtendedPhysarumSolver法 (EPS) を使用します。");
            }
            
            // 選択された経路探索手法を設定
            RouteSearchMethod selectedMethod = RouteSearchMethod.fromId(methodChoice);
            setRouteSearchMethod(selectedMethod);
            System.out.println(selectedMethod.getName() + " を使用します。");
            
            // ログモードを選択
            System.out.println("ログをファイルに記録しますか？");
            System.out.println("1: 記録しない");
            System.out.println("2: 記録する (log/simulator.logに記録)");
            
            int loggingChoice = 1; // デフォルトは記録しない
            try {
                String input = reader.readLine();
                if (!input.trim().isEmpty()) {
                    loggingChoice = Integer.parseInt(input);
                    if (loggingChoice < 1 || loggingChoice > 2) {
                        System.out.println("無効な選択です。ログは記録しません。");
                        loggingChoice = 1;
                    }
                }
            } catch (NumberFormatException e) {
                System.out.println("無効な入力です。ログは記録しません。");
            }
            
            // 選択されたログモードを設定
            LoggingMode selectedLoggingMode = LoggingMode.fromId(loggingChoice);
            setLoggingMode(selectedLoggingMode);
            System.out.println("ログ記録: " + selectedLoggingMode.getName());
            
            if (selectedLoggingMode == LoggingMode.ENABLED) {
                System.out.println("ログは log/simulator.log に記録されます。");
                System.out.println("別のターミナルで 'tail -f log/simulator.log' を実行すると、リアルタイムでログを確認できます。");
            }

            // Phase 3b-6: ワーカーモードを選択
            System.out.println("\nワーカーモードを選択してください:");
            System.out.println("1: メモリベース（従来の方式）");
            System.out.println("2: Redisベース（Phase 3b 非同期ワーカー）");

            int workerChoice = 1; // デフォルトはメモリベース
            try {
                String input = reader.readLine();
                if (!input.trim().isEmpty()) {
                    workerChoice = Integer.parseInt(input);
                    if (workerChoice < 1 || workerChoice > 2) {
                        System.out.println("無効な選択です。メモリベースを使用します。");
                        workerChoice = 1;
                    }
                }
            } catch (NumberFormatException e) {
                System.out.println("無効な入力です。メモリベースを使用します。");
            }

            // 選択されたワーカーモードを設定
            WorkerMode selectedWorkerMode = WorkerMode.fromId(workerChoice);
            setWorkerMode(selectedWorkerMode);
            System.out.println("ワーカーモード: " + selectedWorkerMode.getName());

            // Phase 3b-6: Redisモード時の初期化
            if (selectedWorkerMode == WorkerMode.REDIS) {
                if (!RedisConnectionManager.getInstance().isConnected()) {
                    System.err.println("⚠ Redis未接続のため、メモリベースにフォールバックします。");
                    setWorkerMode(WorkerMode.MEMORY);
                } else {
                    initializeRedisWorker();
                }
            }

            // 標準入力からクライアントの生成回数を取得
            System.out.println("生成するクライアントの数を入力してください:");
            int clientCount = 1; // デフォルトは1
            try {
                String input = reader.readLine();
                if (!input.trim().isEmpty()) {
                    clientCount = Integer.parseInt(input);
                }
            } catch (NumberFormatException e) {
                System.out.println("無効な入力です。クライアント数を1に設定します。");
            }

            // 指定された回数だけクライアントを生成して処理
            for (int i = 0; i < clientCount; i++) {
                System.out.println("クライアント " + (i + 1) + " を生成しています...");
                client = boundaryController.createRandomClient();
                boundaryController.routeRequest(client);

                synchronized (passedClient) {
                    passedClient.add(client);
                }

                // 最初のクライアントに対してタイマーを起動
                if (i == 0) {
                    clientController.startTimer();
                }

                // UAVスケジューリングを更新（Phase 2: beaconClusterとnodeNumを渡す）
                UAVFlyScheduler.startFlyUAVUpdates(flyingUavQueue, uavQueue, clientController, beaconCluster, nodeNum);

                String dirPath = "src/result/client";
                String filePath = dirPath + "/client.txt";

                // ディレクトリが存在しない場合は作成
                File dir1 = new File(dirPath);
                if (!dir1.exists()) {
                    dir1.mkdirs();
                }

                try (FileWriter writer = new FileWriter(filePath, true)) {
                    // ファイルが空の場合、ヘッダーを追加
                    File file = new File(filePath);
                    if (file.length() == 0) {
                        writer.write("source,dist,requiredUAVs\n");
                    }
                    // 行を書き込み
                    writer.write(String.format("%d,%d,%f\n", client.getFlow().getSource().getId(), client.getFlow().getDestination().getId(), client.getFlow().getTheNumberOfUAV()));
                } catch (IOException e) {
                    System.err.println("ファイル書き込みエラー: " + e.getMessage());
                }

                // 次のクライアント生成まで30秒待機
                if (i < clientCount - 1) { // 最後のクライアント以外
                    Thread.sleep(30000); // 30秒待機
                }
            }

            LogManager.getInstance().log("すべてのクライアント生成と処理が完了しました。");
            if (flyingUavQueue.isEmpty() && uavQueue.isEmpty()) {
                UAVFlyScheduler.stopFlyUAVUpdates(clientController);
            }
            
            // プログラム終了時にログマネージャーを閉じるようにシャットダウンフックを追加
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LogManager.getInstance().log("プログラムを終了します。");

                // Phase 3b-6: Redisワーカーを停止
                if (currentWorkerMode == WorkerMode.REDIS) {
                    shutdownRedisWorker();
                }

                // Phase 1: Redis接続を切断
                try {
                    RedisConnectionManager.getInstance().disconnect();
                } catch (Exception e) {
                    System.err.println("Redis切断中にエラーが発生しました: " + e.getMessage());
                }

                LogManager.getInstance().log("ログを閉じます。");
                LogManager.getInstance().close();
            }));

        } catch (IOException e) {
            LogManager.getInstance().error("IOエラーが発生しました", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LogManager.getInstance().error("メインスレッドが中断されました", e);
        }
    }
}
