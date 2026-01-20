package controller;

import client.Client;
import client.ClientController;
import item.Beacon;
import item.BeaconCluster;
import item.Flow;
import item.Uav;
import server.controller.ServerController;
import server.network.TopologyFileReader;
import server.config.SimulationConfig;
import server.config.StatisticalSimulationConfig;
import server.redis.*;
import server.scheduler.FlightScheduler;
import server.scheduler.PhaseController;
import server.scheduler.RandomClientGenerator;
import server.scheduler.StatisticalSimulationController;
import server.uav.UAVFlyScheduler;
import server.util.LinkStatusRecorder;
import server.util.LogManager;
import server.util.ResultOutputManager;
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

    /**
     * スケジュールエントリからクライアントを生成
     *
     * @param entry スケジュールエントリ
     * @return 生成されたクライアント
     */
    public Client createClientFromSchedule(ClientScheduleLoader.ScheduleEntry entry) {
        Beacon source = beaconCluster.getBeacon(entry.sourceId);
        Beacon destination = beaconCluster.getBeacon(entry.destinationId);

        if (source == null) {
            throw new IllegalArgumentException("Invalid source node ID: " + entry.sourceId);
        }
        if (destination == null) {
            throw new IllegalArgumentException("Invalid destination node ID: " + entry.destinationId);
        }

        flow = new Flow(source, destination, entry.uavCount);
        Client client = new Client(flow, entry.clientId);
        clientController.addClient(client);

        LogManager.getInstance().log("クライアント生成: client" + client.getId() +
                                    " (source=" + entry.sourceId + ", dest=" + entry.destinationId +
                                    ", uavs=" + entry.uavCount + ")");

        return client;
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

    // Phase 7-1: 大規模シミュレーションモード
    private static boolean isLargeScaleMode = false;

    // Phase 8: 統計的シミュレーションモード（UAVFlySchedulerの自動停止を無効化）
    private static boolean isStatisticalSimulationMode = false;
    
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
     * Phase 7-1: 大規模シミュレーションモードを設定する
     * @param isLargeScale 大規模モードかどうか
     */
    public static void setLargeScaleMode(boolean isLargeScale) {
        isLargeScaleMode = isLargeScale;
        LogManager.getInstance().log("Phase 7-1: シミュレーション規模を " + (isLargeScale ? "大規模" : "小規模") + " に設定しました");
    }

    /**
     * Phase 7-1: 大規模シミュレーションモードかどうかを取得する
     * @return 大規模モードならtrue
     */
    public static boolean isLargeScaleMode() {
        return isLargeScaleMode;
    }

    /**
     * Phase 8: 統計的シミュレーションモードを設定する
     * @param isStatistical 統計的シミュレーションモードかどうか
     */
    public static void setStatisticalSimulationMode(boolean isStatistical) {
        isStatisticalSimulationMode = isStatistical;
        LogManager.getInstance().log("Phase 8: 統計的シミュレーションモードを " + (isStatistical ? "有効" : "無効") + " に設定");
    }

    /**
     * Phase 8: 統計的シミュレーションモードかどうかを取得する
     * @return 統計的シミュレーションモードならtrue
     */
    public static boolean isStatisticalSimulationMode() {
        return isStatisticalSimulationMode;
    }

    /**
     * Phase 8: 結果出力ディレクトリを取得する
     * @return 結果出力ディレクトリのパス
     */
    public static String getResultDir() {
        String scaleDir = isLargeScaleMode ? "large_scale" : "small_scale";
        return "src/result/" + scaleDir + "/" + currentMethod.getName();
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

    /**
     * Phase 7-7: シミュレーション設定を適用する
     */
    private static void applySimulationConfig() {
        if (!SimulationConfig.isLoaded()) {
            return;
        }

        SimulationConfig config = SimulationConfig.getInstance();

        // スナップショット間隔を設定
        int snapshotIntervalSec = config.getSnapshotIntervalSec();
        LinkStatusRecorder.getInstance().setSnapshotIntervalSeconds(snapshotIntervalSec);
        LogManager.getInstance().log("Phase 7-7: スナップショット間隔を " + snapshotIntervalSec + " 秒に設定");

        // トポロジパスが指定されていれば表示（実際の適用はCUI前に行う必要があるため警告）
        if (config.getTopologyPath() != null) {
            LogManager.getInstance().log("Phase 7-7: 設定ファイルでトポロジが指定されています: " + config.getTopologyPath());
        }

        // 経路探索手法が指定されていれば適用
        if (config.getRouteSearchMethod() != null) {
            String methodName = config.getRouteSearchMethod();
            for (RouteSearchMethod method : RouteSearchMethod.values()) {
                if (method.getName().equalsIgnoreCase(methodName)) {
                    setRouteSearchMethod(method);
                    LogManager.getInstance().log("Phase 7-7: 経路探索手法を " + method.getName() + " に設定");
                    break;
                }
            }
        }
    }

    /**
     * トポロジをPNG画像として出力する
     * Pythonスクリプトを呼び出して描画を行う
     *
     * @param topologyFilePath トポロジファイルのパス
     * @param isLargeScale 大規模モードかどうか（true=シンプル表示、false=詳細表示）
     */
    private static void renderTopologyImage(String topologyFilePath, boolean isLargeScale) {
        try {
            // 出力ファイル名を生成
            String baseName = new File(topologyFilePath).getName();
            if (baseName.contains(".")) {
                baseName = baseName.substring(0, baseName.lastIndexOf('.'));
            }
            String outputPath = "output/topology_" + baseName + ".png";

            System.out.println("トポロジ画像を生成しています...");
            System.out.println("  モード: " + (isLargeScale ? "シンプル（大規模向け）" : "詳細（小規模向け）"));

            // Pythonスクリプトを実行（venv優先、なければシステムPython）
            String pythonCmd = new File(".venv/bin/python").exists() ? ".venv/bin/python" : "python3";
            String modeArg = isLargeScale ? "--simple" : "--detailed";
            ProcessBuilder pb = new ProcessBuilder(
                pythonCmd,
                "scripts/plot_topology.py",
                topologyFilePath,
                outputPath,
                modeArg
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 出力を読み取り
            try (BufferedReader processReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = processReader.readLine()) != null) {
                    System.out.println("  " + line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                System.out.println("トポロジ画像を保存しました: " + outputPath);
            } else {
                System.err.println("トポロジ画像の生成に失敗しました (exit code: " + exitCode + ")");
                System.err.println("必要なPythonライブラリがインストールされているか確認してください:");
                System.err.println("  pip install matplotlib networkx");
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("トポロジ画像の生成中にエラーが発生しました: " + e.getMessage());
            System.err.println("Python3がインストールされているか確認してください。");
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void routeRequest(Client client) throws IOException {
        // Phase 8-Debug: routeRequest開始ログ
        String routeOp = "nodeConfigureToPajek";
        try {
            // Pajekファイルにネットワークトポロジーを出力
            server.nodeConfigureToPajek(filePath, client, beaconCluster);

            routeOp = "run_" + currentMethod.getName();
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
        } catch (IOException e) {
            // Phase 8-Debug: routeRequest内のIOException詳細ログ
            LogManager.getInstance().error("routeRequest IOException at " + routeOp + " for client " + client.getId(), e);
            // StatisticalSimulationControllerにも記録
            try {
                StatisticalSimulationController statController = StatisticalSimulationController.getInstance();
                if (statController != null) {
                    statController.logDiagnostic("ROUTE_REQUEST_ERROR",
                        String.format("operation=%s,client=%d,source=%d,dest=%d,error=%s",
                            routeOp, client.getId(),
                            client.getFlow().getSource().getId(),
                            client.getFlow().getDestination().getId(),
                            e.getMessage()));
                }
            } catch (Exception ignored) {}
            throw e; // 再スロー
        } catch (RuntimeException e) {
            // Phase 8-Debug: routeRequest内のRuntimeException詳細ログ
            LogManager.getInstance().error("routeRequest RuntimeException at " + routeOp + " for client " + client.getId(), e);
            try {
                StatisticalSimulationController statController = StatisticalSimulationController.getInstance();
                if (statController != null) {
                    statController.logDiagnostic("ROUTE_REQUEST_ERROR",
                        String.format("operation=%s,client=%d,source=%d,dest=%d,error=%s,type=RuntimeException",
                            routeOp, client.getId(),
                            client.getFlow().getSource().getId(),
                            client.getFlow().getDestination().getId(),
                            e.getMessage()));
                }
            } catch (Exception ignored) {}
            throw e; // 再スロー
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
            System.out.println("=== UAVシミュレーター ===");

            // ネットワーク規模の選択
            System.out.println("ネットワーク規模を選択してください:");
            System.out.println("1: 小規模（詳細表示: ノードラベル・容量表示あり）");
            System.out.println("2: 大規模（シンプル表示: 地区タイプで色分け）");

            int scaleChoice = 1;
            try {
                String input = reader.readLine();
                if (!input.trim().isEmpty()) {
                    scaleChoice = Integer.parseInt(input);
                    if (scaleChoice < 1 || scaleChoice > 2) {
                        System.out.println("無効な選択です。小規模モードを使用します。");
                        scaleChoice = 1;
                    }
                }
            } catch (NumberFormatException e) {
                System.out.println("無効な入力です。小規模モードを使用します。");
            }

            boolean isLargeScale = (scaleChoice == 2);
            setLargeScaleMode(isLargeScale);  // Phase 7-1: 規模情報を設定

            String defaultPath = isLargeScale
                ? "config/topology/koriyama_topology.txt"
                : TopologyFileReader.DEFAULT_TOPOLOGY_PATH;

            System.out.println("ネットワーク規模: " + (isLargeScale ? "大規模" : "小規模"));

            // トポロジファイルのパスを入力
            System.out.println("トポロジファイルのパスを入力してください（空欄でデフォルト: " + defaultPath + "）:");
            System.out.print("> ");

            String topologyFilePath = reader.readLine().trim();
            if (topologyFilePath.isEmpty()) {
                topologyFilePath = defaultPath;
            }

            // トポロジの初期化（外部ファイルから読み込み）
            System.out.println("トポロジファイルを読み込んでいます: " + topologyFilePath);
            TopologyFileReader.TopologyData topologyData =
                TopologyFileReader.readTopologyFile(topologyFilePath);

            // トポロジ情報を表示
            TopologyFileReader.printTopologyInfo(topologyData);

            // トポロジ描画オプション
            System.out.println("トポロジをPNG画像として出力しますか？");
            System.out.println("1: 出力しない");
            System.out.println("2: 出力する (output/ディレクトリに保存)");

            int renderChoice = 1;
            try {
                String input = reader.readLine();
                if (!input.trim().isEmpty()) {
                    renderChoice = Integer.parseInt(input);
                }
            } catch (NumberFormatException e) {
                // デフォルトのまま
            }

            if (renderChoice == 2) {
                renderTopologyImage(topologyFilePath, isLargeScale);
            }

            // ビーコンとサーバーを初期化
            boundaryController.setNodeNum(topologyData.nodeCount);
            beaconCluster = new BeaconCluster(topologyData);
            server = new ServerController(beaconCluster, topologyData);

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

            // Phase 7-7: シミュレーション設定ファイルの読み込み
            System.out.println("\nシミュレーション設定ファイルを読み込みますか？");
            System.out.println("1: 読み込まない（デフォルト設定を使用）");
            System.out.println("2: 読み込む（config/simulation_params.json）");
            System.out.println("3: ファイルパスを指定して読み込む");

            int configChoice = 1;
            try {
                String input = reader.readLine();
                if (!input.trim().isEmpty()) {
                    configChoice = Integer.parseInt(input);
                    if (configChoice < 1 || configChoice > 3) {
                        System.out.println("無効な選択です。デフォルト設定を使用します。");
                        configChoice = 1;
                    }
                }
            } catch (NumberFormatException e) {
                System.out.println("無効な入力です。デフォルト設定を使用します。");
            }

            if (configChoice == 2) {
                // デフォルトパスから読み込み
                if (SimulationConfig.loadDefault()) {
                    System.out.println("✓ 設定ファイルを読み込みました: config/simulation_params.json");
                    // スナップショット間隔を適用
                    applySimulationConfig();
                } else {
                    System.out.println("⚠ 設定ファイルの読み込みに失敗しました。デフォルト設定を使用します。");
                }
            } else if (configChoice == 3) {
                // パスを指定して読み込み
                System.out.print("設定ファイルのパスを入力してください: ");
                String configPath = reader.readLine().trim();
                if (!configPath.isEmpty()) {
                    if (SimulationConfig.load(configPath)) {
                        System.out.println("✓ 設定ファイルを読み込みました: " + configPath);
                        applySimulationConfig();
                    } else {
                        System.out.println("⚠ 設定ファイルの読み込みに失敗しました。デフォルト設定を使用します。");
                    }
                }
            }

            // Phase 7-8/7-9: クライアント生成モードを選択
            System.out.println("\nクライアント生成モードを選択してください:");
            System.out.println("1: スケジュールファイルから読み込み");
            System.out.println("2: ランダム生成（固定数）");
            System.out.println("3: 4フェーズ制御（Phase 7-9: 動的生成）");
            System.out.println("4: 統計的シミュレーション（Phase 8: 指数分布）");

            int clientGenMode = 1;
            try {
                String input = reader.readLine();
                if (!input.trim().isEmpty()) {
                    clientGenMode = Integer.parseInt(input);
                    if (clientGenMode < 1 || clientGenMode > 4) {
                        System.out.println("無効な選択です。スケジュールファイルを使用します。");
                        clientGenMode = 1;
                    }
                }
            } catch (NumberFormatException e) {
                System.out.println("無効な入力です。スケジュールファイルを使用します。");
            }

            List<ClientScheduleLoader.ScheduleEntry> schedule = new ArrayList<>();
            RandomClientGenerator randomGenerator = null;
            boolean usePhaseControl = (clientGenMode == 3);
            boolean useStatisticalSimulation = (clientGenMode == 4);

            if (clientGenMode == 4) {
                // Phase 8: 統計的シミュレーションモード
                System.out.println("\n=== 統計的シミュレーションモード (Phase 8) ===");

                // Phase 8: 統計的シミュレーションモードを有効化（UAVFlySchedulerの自動停止を無効化）
                setStatisticalSimulationMode(true);

                // 設定ファイルを読み込み
                if (!StatisticalSimulationConfig.loadDefault()) {
                    System.out.println("⚠ 設定ファイルの読み込みに失敗しました。デフォルト設定を使用します。");
                }

                StatisticalSimulationConfig statConfig = StatisticalSimulationConfig.getInstance();
                System.out.println("シミュレーション時間: " + statConfig.getSimulationDurationHours() + "時間");
                System.out.println("平均発生間隔: " + statConfig.getArrival().getMeanIntervalSec() + "秒（指数分布）");
                System.out.println("1回あたりUAV数: " + statConfig.getArrival().getUavCountPerBatch() + "機");

                // StatisticalSimulationControllerを初期化
                StatisticalSimulationController statController = StatisticalSimulationController.getInstance();
                statController.initialize(statConfig, nodeNum, beaconCluster);

                // LinkStatusRecorderを開始
                LinkStatusRecorder.getInstance().setSnapshotIntervalSeconds(statConfig.getSnapshotIntervalSec());
                LinkStatusRecorder.getInstance().startSimulation();

                // コントローラーを開始
                statController.start();

                System.out.println("✓ 統計的シミュレーションを開始しました (seed=" + statConfig.getSeed() + ")");
                System.out.println("シミュレーション時間: " + statConfig.getSimulationDurationHours() + "時間 (" + statConfig.getDurationSeconds() + "秒)");

                // シミュレーションループ
                boolean timerStarted = false;
                int lastProgressMinute = -1;
                int loopIterationCount = 0;
                String lastOperation = "初期化";
                Throwable loopException = null;

                // Phase 8: StatisticalSimulationControllerが実行中の間ループ
                // （時間管理はStatisticalSimulationController内で一元管理）
                // try-finally で例外発生時もクリーンアップを保証
                try {
                    while (statController.isRunning()) {
                        loopIterationCount++;

                        // Phase 8-Debug: 100回ごとにループ状態をログ
                        if (loopIterationCount % 100 == 0) {
                            statController.logDiagnostic("LOOP_PROGRESS",
                                String.format("iteration=%d,elapsed_sec=%.0f,memory_mb=%.1f",
                                    loopIterationCount,
                                    statController.getElapsedSeconds() * 1.0,
                                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024.0 / 1024.0));
                        }

                        lastOperation = "generateNextClient";
                        // クライアントを生成（時間超過時はnullが返され、内部でstop()が呼ばれる）
                        ClientScheduleLoader.ScheduleEntry entry = statController.generateNextClient();
                        if (entry == null) {
                            // シミュレーション終了（時間経過またはエラー）
                            System.out.println("\n[Phase 8] クライアント生成終了");
                            statController.logDiagnostic("LOOP_END_NORMAL",
                                String.format("reason=null_entry,iteration=%d", loopIterationCount));
                            break;
                        }

                        lastOperation = "createClientFromSchedule";
                        client = boundaryController.createClientFromSchedule(entry);

                        lastOperation = "routeRequest";
                        boundaryController.routeRequest(client);

                        lastOperation = "passedClient.add";
                        synchronized (passedClient) {
                            passedClient.add(client);
                        }

                        if (!timerStarted) {
                            lastOperation = "clientController.startTimer";
                            clientController.startTimer();
                            timerStarted = true;
                        }

                        lastOperation = "UAVFlyScheduler.startFlyUAVUpdates";
                        UAVFlyScheduler.startFlyUAVUpdates(flyingUavQueue, uavQueue, clientController, beaconCluster, nodeNum);

                        // Phase 8: クライアント情報をファイルに記録
                        lastOperation = "writeClientInfo";
                        String scaleDir = isLargeScaleMode ? "large_scale" : "small_scale";
                        String clientDirPath = "src/result/" + scaleDir + "/" + currentMethod.getName() + "/client";
                        String clientFilePath = clientDirPath + "/client.txt";

                        File clientDir = new File(clientDirPath);
                        if (!clientDir.exists()) {
                            clientDir.mkdirs();
                        }

                        try (FileWriter writer = new FileWriter(clientFilePath, true)) {
                            File file = new File(clientFilePath);
                            if (file.length() == 0) {
                                writer.write("source,dest,requiredUAVs\n");
                            }
                            writer.write(String.format("%d,%d,%d\n",
                                client.getFlow().getSource().getId(),
                                client.getFlow().getDestination().getId(),
                                (int) client.getFlow().getTheNumberOfUAV()));
                        } catch (IOException e) {
                            System.err.println("クライアント情報ファイル書き込みエラー: " + e.getMessage());
                            // この例外はループを止めない（ファイル書き込みは補助的）
                        }

                        // 5分ごとに進捗をログ出力
                        int currentMinute = (int) statController.getElapsedMinutes();
                        if (currentMinute > 0 && currentMinute % 5 == 0 && currentMinute != lastProgressMinute) {
                            statController.logProgress();
                            lastProgressMinute = currentMinute;
                        }

                        // 次の生成まで待機（指数分布に従った間隔）
                        // ただし、シミュレーション終了時刻を超えないようにする
                        lastOperation = "Thread.sleep";
                        long waitMs = (long) (entry.intervalAfterSec * 1000);
                        long remainingMs = statController.getRemainingSeconds() * 1000;
                        if (waitMs > 0 && remainingMs > 0) {
                            Thread.sleep(Math.min(waitMs, remainingMs));
                        }
                    }
                } catch (IOException e) {
                    loopException = e;
                    statController.logDiagnostic("LOOP_EXCEPTION",
                        String.format("type=IOException,operation=%s,iteration=%d,message=%s",
                            lastOperation, loopIterationCount, e.getMessage()));
                    System.err.println("[Phase 8] IOException発生: " + lastOperation + " (iteration=" + loopIterationCount + ")");
                    e.printStackTrace();
                } catch (RuntimeException e) {
                    loopException = e;
                    statController.logDiagnostic("LOOP_EXCEPTION",
                        String.format("type=RuntimeException,operation=%s,iteration=%d,message=%s",
                            lastOperation, loopIterationCount, e.getMessage()));
                    System.err.println("[Phase 8] RuntimeException発生: " + lastOperation + " (iteration=" + loopIterationCount + ")");
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    loopException = e;
                    statController.logDiagnostic("LOOP_EXCEPTION",
                        String.format("type=InterruptedException,operation=%s,iteration=%d,message=%s",
                            lastOperation, loopIterationCount, e.getMessage()));
                    Thread.currentThread().interrupt();
                    System.err.println("[Phase 8] InterruptedException発生: " + lastOperation + " (iteration=" + loopIterationCount + ")");
                }
                // finally ブロックは不要（以下のクリーンアップコードが常に実行される）

                // Phase 8: ループ終了の診断情報
                if (loopException != null) {
                    System.err.println("[Phase 8] ループが例外により終了しました。クリーンアップを実行します...");
                }
                statController.logDiagnostic("LOOP_CLEANUP_START",
                    String.format("total_iterations=%d,had_exception=%s,last_operation=%s",
                        loopIterationCount, loopException != null, lastOperation));

                // Phase 8: シミュレーション終了処理
                System.out.println("\n[Phase 8] シミュレーション終了処理を開始...");

                // statController.stop()はgenerateNextClient()内で既に呼ばれているが、
                // ループが別の理由で終了した場合に備えて再度呼ぶ（二重呼び出しは安全）
                if (statController.isRunning()) {
                    statController.stop();
                }

                // Phase 8: 統計的シミュレーションモードを無効化
                setStatisticalSimulationMode(false);

                // LinkStatusRecorderを停止
                LinkStatusRecorder.getInstance().stopSimulation();

                // UAVFlySchedulerを強制停止
                UAVFlyScheduler.stopFlyUAVUpdates(clientController);

                // 平均飛行ステータスを出力
                ResultOutputManager.outputAverageFlightStatus();

                // シミュレーションサマリーを出力
                long totalElapsedMs = statController.getElapsedSeconds() * 1000;
                LogManager.getInstance().logSimulationSummary(statController.getGeneratedClientCount(), totalElapsedMs);

                statController.logStatistics();
                System.out.println("\n✓ 統計的シミュレーション完了");
                System.out.println("  シミュレーション時間: " + String.format("%.1f", totalElapsedMs / 1000.0 / 60.0) + " 分");
                System.out.println("  総クライアント数: " + statController.getGeneratedClientCount());
                System.out.println("  総UAV数: " + statController.getTotalUavCount());
                System.out.println("  最終平均リンク負荷率: " + String.format("%.2f%%",
                        LinkStatusRecorder.getInstance().getAverageLoadRate()));

                // 統計的シミュレーションモードでは以降のスケジュール処理をスキップ
                schedule = new ArrayList<>();

            } else if (clientGenMode == 3) {
                // Phase 7-9: 4フェーズ制御モード
                if (!SimulationConfig.isLoaded()) {
                    System.out.println("⚠ 設定ファイルが読み込まれていません。デフォルト設定ファイルを読み込みます。");
                    SimulationConfig.loadDefault();
                    applySimulationConfig();
                }

                System.out.println("\n=== 4フェーズ制御モード ===");
                SimulationConfig config = SimulationConfig.getInstance();
                System.out.println("シミュレーション時間: " + config.getDurationMinutes() + "分");
                System.out.println("Phase1(生成期): 混雑率≥" + config.getPhases().getPhase1().getCongestionThresholdPercent() + "%で遷移");
                System.out.println("Phase2(安定期): " + config.getPhases().getPhase2().getDurationMinutes() + "分間、目標混雑率" + config.getPhases().getPhase2().getTargetCongestionPercent() + "%");
                System.out.println("Phase3(混雑期): " + config.getPhases().getPhase3().getDurationMinutes() + "分間");
                System.out.println("Phase4(回復期): シミュレーション終了まで、目標混雑率" + config.getPhases().getPhase4().getTargetCongestionPercent() + "%");

                long seed = config.getSeed();
                randomGenerator = new RandomClientGenerator(seed, nodeNum, beaconCluster);

                // PhaseControllerを初期化
                PhaseController phaseController = PhaseController.getInstance();
                phaseController.initialize(config, randomGenerator, entry -> {
                    // コールバック（必要に応じて使用）
                });

                // PhaseControllerにリンク配列を設定（容量操作用）
                phaseController.setLinkArray(ServerController.getLinkArray(), nodeNum);

                // LinkStatusRecorderを開始
                LinkStatusRecorder.getInstance().startSimulation();

                // PhaseControllerを開始
                phaseController.start();

                System.out.println("✓ 4フェーズ制御を開始しました (seed=" + seed + ")");

                // フェーズ制御モードでのクライアント生成ループ
                long simulationStartTime = System.currentTimeMillis();
                long simulationEndTime = simulationStartTime + config.getDurationMillis();
                int generatedCount = 0;
                boolean timerStarted = false;

                // Phase 7-11: Phase4では全UAV完了まで継続するため、時間制限は削除
                // PhaseController.isRunning()がfalseになるまでループ継続
                while (phaseController.isRunning()) {
                    // Phase4の場合は全UAV完了を待機（クライアント生成なし）
                    if (phaseController.getCurrentPhase() == PhaseController.Phase.PHASE4_RECOVERY) {
                        Thread.sleep(100);  // 100msごとにチェック
                        continue;
                    }

                    // Phase1-3: 設定時間を超えたらクライアント生成を停止
                    if (System.currentTimeMillis() >= simulationEndTime) {
                        Thread.sleep(100);
                        continue;
                    }
                    // クライアントを生成
                    ClientScheduleLoader.ScheduleEntry entry = phaseController.generateNextClient();
                    if (entry != null) {
                        generatedCount++;
                        System.out.println("クライアント #" + generatedCount + " を生成 (Phase: " +
                                          phaseController.getCurrentPhase().getName() + ", 間隔: " +
                                          String.format("%.1f", phaseController.getCurrentIntervalSec()) + "s)");

                        // フェーズ統計にクライアント生成を記録
                        phaseController.recordClientGeneration(entry.uavCount);

                        client = boundaryController.createClientFromSchedule(entry);
                        boundaryController.routeRequest(client);

                        synchronized (passedClient) {
                            passedClient.add(client);
                        }

                        if (!timerStarted) {
                            clientController.startTimer();
                            timerStarted = true;
                        }

                        UAVFlyScheduler.startFlyUAVUpdates(flyingUavQueue, uavQueue, clientController, beaconCluster, nodeNum);

                        // Phase 7-1: クライアント情報をファイルに記録
                        String scaleDir = isLargeScaleMode ? "large_scale" : "small_scale";
                        String clientDirPath = "src/result/" + scaleDir + "/" + currentMethod.getName() + "/client";
                        String clientFilePath = clientDirPath + "/client.txt";

                        File clientDir = new File(clientDirPath);
                        if (!clientDir.exists()) {
                            clientDir.mkdirs();
                        }

                        try (FileWriter writer = new FileWriter(clientFilePath, true)) {
                            File file = new File(clientFilePath);
                            if (file.length() == 0) {
                                writer.write("source,dest,requiredUAVs\n");
                            }
                            writer.write(String.format("%d,%d,%d\n",
                                client.getFlow().getSource().getId(),
                                client.getFlow().getDestination().getId(),
                                (int) client.getFlow().getTheNumberOfUAV()));
                        } catch (IOException e) {
                            System.err.println("クライアント情報ファイル書き込みエラー: " + e.getMessage());
                        }

                        // 次の生成まで待機
                        long waitMs = (long) (entry.intervalAfterSec * 1000);
                        if (waitMs > 0) {
                            Thread.sleep(waitMs);
                        }
                    }
                }

                // Phase 7-11: PhaseControllerが停止したらループを抜ける
                // （Phase4で全UAV完了時にPhaseController内でstop()が呼ばれる）

                // LinkStatusRecorderを停止
                LinkStatusRecorder.getInstance().stopSimulation();

                // 平均飛行ステータスを出力
                ResultOutputManager.outputAverageFlightStatus();

                // Phase 7-10: シミュレーションサマリーを出力
                long totalElapsedMs = System.currentTimeMillis() - simulationStartTime;
                LogManager.getInstance().logSimulationSummary(generatedCount, totalElapsedMs);

                LogManager.getInstance().log("Phase 7-9: 4フェーズ制御完了 (生成クライアント数=" + generatedCount + ")");
                System.out.println("\n✓ シミュレーション完了 (生成クライアント数: " + generatedCount + ")");

                // フェーズ制御モードでは以降のスケジュール処理をスキップ
                schedule = new ArrayList<>();

            } else if (clientGenMode == 2) {
                // Phase 7-8: ランダム生成モード（固定数）
                System.out.println("\n=== ランダム生成モード ===");

                // シード値の取得（設定ファイルまたは入力）
                long seed = SimulationConfig.isLoaded()
                        ? SimulationConfig.getInstance().getSeed()
                        : 12345L;

                System.out.println("シード値を入力してください（空欄でデフォルト: " + seed + "）:");
                try {
                    String input = reader.readLine().trim();
                    if (!input.isEmpty()) {
                        seed = Long.parseLong(input);
                    }
                } catch (NumberFormatException e) {
                    System.out.println("無効な入力です。デフォルトシード値を使用します。");
                }

                // 生成数の入力
                System.out.println("生成するクライアント数を入力してください（空欄でデフォルト: 10）:");
                int clientCount = 10;
                try {
                    String input = reader.readLine().trim();
                    if (!input.isEmpty()) {
                        clientCount = Integer.parseInt(input);
                        if (clientCount <= 0) {
                            clientCount = 10;
                        }
                    }
                } catch (NumberFormatException e) {
                    System.out.println("無効な入力です。10クライアントを生成します。");
                }

                // RandomClientGenerator初期化
                randomGenerator = new RandomClientGenerator(seed, nodeNum, beaconCluster);

                // 設定ファイルがあれば適用
                if (SimulationConfig.isLoaded()) {
                    randomGenerator.applyConfig(SimulationConfig.getInstance());
                }

                // スケジュールを生成
                schedule = randomGenerator.generateEntries(clientCount);
                randomGenerator.logSettings();

                System.out.println("✓ " + clientCount + " クライアントをランダム生成しました (seed=" + seed + ")");

            } else {
                // スケジュールファイルモード
                System.out.println("スケジュールファイルのパスを入力してください（空欄でデフォルト: " + ClientScheduleLoader.DEFAULT_SCHEDULE_PATH + "）:");
                String schedulePath = reader.readLine().trim();
                if (schedulePath.isEmpty()) {
                    schedulePath = ClientScheduleLoader.DEFAULT_SCHEDULE_PATH;
                }

                // スケジュールファイルを読み込み
                try {
                    schedule = ClientScheduleLoader.load(schedulePath);
                } catch (IOException e) {
                    System.err.println("スケジュールファイルの読み込みに失敗しました: " + e.getMessage());
                    System.err.println("デフォルトのクライアント設定で続行します。");
                    // フォールバック: デフォルトのクライアント1つ
                    schedule = new ArrayList<>();
                    schedule.add(new ClientScheduleLoader.ScheduleEntry(1, 0, 5, 25, 0));
                }
            }

            // フェーズ制御モードまたは統計的シミュレーションモードの場合はスケジュール処理をスキップ
            if (usePhaseControl || useStatisticalSimulation) {
                // 既に処理完了
            } else if (schedule.isEmpty()) {
                System.err.println("スケジュールが空です。終了します。");
                return;
            } else {
                // スケジュールに従ってクライアントを生成して処理
            for (int i = 0; i < schedule.size(); i++) {
                ClientScheduleLoader.ScheduleEntry entry = schedule.get(i);

                System.out.println("クライアント " + (i + 1) + "/" + schedule.size() + " を生成しています...");
                client = boundaryController.createClientFromSchedule(entry);
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

                // Phase 7-1: スケール別のディレクトリに出力
                String scaleDir = isLargeScaleMode ? "large_scale" : "small_scale";
                String dirPath = "src/result/" + scaleDir + "/" + currentMethod.getName() + "/client";
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
                        writer.write("source,dest,requiredUAVs\n");
                    }
                    // 行を書き込み
                    writer.write(String.format("%d,%d,%d\n", client.getFlow().getSource().getId(), client.getFlow().getDestination().getId(), (int) client.getFlow().getTheNumberOfUAV()));
                } catch (IOException e) {
                    System.err.println("ファイル書き込みエラー: " + e.getMessage());
                }

                // 次のクライアント生成までの待機（スケジュールに従う）
                if (entry.intervalAfterSec > 0) {
                    LogManager.getInstance().log("次のクライアント生成まで " + entry.intervalAfterSec + " 秒待機します...");
                    Thread.sleep((long) (entry.intervalAfterSec * 1000));
                }
            }

            LogManager.getInstance().log("すべてのクライアント生成と処理が完了しました。");
            } // else ブロック終了

            if (!usePhaseControl && !useStatisticalSimulation) {
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
            } // if (!usePhaseControl) 終了

        } catch (IOException e) {
            LogManager.getInstance().error("IOエラーが発生しました", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LogManager.getInstance().error("メインスレッドが中断されました", e);
        }
    }
}
