package legacy.eventdriven;

import legacy.eventdriven.route.EventDrivenDijkstraRouteSearcher;
import legacy.eventdriven.route.EventDrivenPGEPSRouteSearcher;
import legacy.eventdriven.route.EventDrivenPSRouteSearcher;
import operator.BoundaryController;
import operator.controller.ServerController;
import operator.route.RouteSearcher;
import shared.client.Client;
import shared.client.ClientController;
import shared.item.BeaconCluster;
import shared.item.Link;
import shared.util.LogManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Option B (イベント駆動インメモリ方式) シミュレーター
 *
 * BoundaryController から委譲されるメモリモード（EventDriven）の全体制御クラス。
 * AsyncUAVWorker + FlightScheduler + Redis を以下で置き換える:
 *   - MemoryUAVWorker    × WORKER_COUNT
 *   - MemoryFlightScheduler
 *   - MemoryCapacityManager / MemoryWaitingManager / MemoryPathWaitingManager
 *   - MemoryJobQueue / MemoryCompletionTracker
 *
 * 結果ディレクトリは "memory_eventdriven" サブディレクトリを使用する。
 * BoundaryController.setCustomScaleDir("memory_eventdriven") を呼んでおく。
 *
 * Singleton パターン（実行期間中に1インスタンス）。
 */
public class EventDrivenMemorySimulator {

    /** Redis 版と同数のワーカー */
    private static final int WORKER_COUNT = 4;

    private static EventDrivenMemorySimulator instance;

    // インメモリ管理クラス
    private MemoryCapacityManager    capacityManager;
    private MemoryWaitingManager     waitingManager;
    private MemoryPathWaitingManager pathWaitingManager;

    // ルートサーチャー
    private EventDrivenDijkstraRouteSearcher dijkstraSearcher;
    private EventDrivenPSRouteSearcher       psSearcher;
    private EventDrivenPGEPSRouteSearcher    pgepsSearcher;

    // ワーカースレッド
    private final List<MemoryUAVWorker> workers        = new ArrayList<>();
    private ExecutorService             workerExecutor;

    // 実行カウンタ（reset() を初回スキップするため）
    private final AtomicInteger runCounter = new AtomicInteger(0);

    private EventDrivenMemorySimulator() {}

    public static synchronized EventDrivenMemorySimulator getInstance() {
        if (instance == null) {
            instance = new EventDrivenMemorySimulator();
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    // 初期化
    // ─────────────────────────────────────────────────────────────────

    /**
     * シミュレーター初期化。BoundaryController の起動時に1回呼ぶ。
     *
     * @param server           ServerController（トポロジ設定済み）
     * @param clientController クライアントコントローラー（未使用、将来拡張用）
     * @param beaconCluster    ビーコンクラスター
     */
    public synchronized void initialize(
            ServerController server,
            ClientController clientController,
            BeaconCluster beaconCluster) {

        int     node  = ServerController.getNodeCount();
        Link[][] link = ServerController.getLinkArray();
        int[][] adjMatrix = server.getAdjMatrix();

        // 結果ディレクトリを memory_eventdriven に設定
        BoundaryController.setCustomScaleDir("memory_eventdriven");

        // インメモリ管理クラスを生成
        capacityManager    = new MemoryCapacityManager();
        waitingManager     = new MemoryWaitingManager();
        pathWaitingManager = new MemoryPathWaitingManager();

        // 容量を初期化（link[][] から読み込む）
        capacityManager.initializeCapacities(link, node);

        // Singleton のリセット
        MemoryJobQueue.reset();
        MemoryCompletionTracker.reset();

        // MemoryFlightScheduler を初期化
        MemoryFlightScheduler.init(capacityManager, waitingManager, pathWaitingManager);

        // ルートサーチャーを生成
        dijkstraSearcher = new EventDrivenDijkstraRouteSearcher(
            server, adjMatrix, link, beaconCluster, node);

        psSearcher = new EventDrivenPSRouteSearcher(
            server, adjMatrix, link, beaconCluster, node);

        pgepsSearcher = new EventDrivenPGEPSRouteSearcher(
            server, adjMatrix, link, beaconCluster, node, pathWaitingManager);

        // ワーカーを起動
        startWorkers();

        System.out.println("[EventDrivenMode] EventDrivenMemorySimulator 初期化完了 (workers=" + WORKER_COUNT + ")");
        LogManager.getInstance().log(
            "[EventDrivenMode] EventDrivenMemorySimulator 初期化完了 (workers=" + WORKER_COUNT + ")");
    }

    /** ワーカースレッドを起動する */
    private void startWorkers() {
        workers.clear();
        workerExecutor = Executors.newFixedThreadPool(WORKER_COUNT, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("MemoryUAVWorker-" + t.getId());
            return t;
        });

        for (int i = 1; i <= WORKER_COUNT; i++) {
            MemoryUAVWorker worker = new MemoryUAVWorker(
                "mem-worker-" + i, capacityManager, waitingManager);
            workers.add(worker);
            workerExecutor.submit(worker::start);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // ルートリクエスト
    // ─────────────────────────────────────────────────────────────────

    /**
     * クライアントのルートリクエストを処理する。
     * BoundaryController.routeRequest() から委譲される。
     *
     * @param client           クライアント
     * @param server           ServerController（Physarum パラメータのリセット用）
     * @param methodName       手法名 ("DIJKSTRA" / "PS" / "BISECTIONAL_PGEPS")
     * @param numLoop          ループ数（PS / PG-EPS で使用）
     */
    public void routeRequest(
            Client client,
            ServerController server,
            String methodName,
            int numLoop) throws IOException {

        // Physarum パラメータ・隣接行列リセット（初回以外）
        if (runCounter.getAndIncrement() > 0) {
            server.reset();
            // 容量を再初期化（リセット後に link が更新されるため）
            capacityManager.initializeCapacities(ServerController.getLinkArray(), ServerController.getNodeCount());
        }

        RouteSearcher searcher;
        switch (methodName) {
            case "Dijkstra":
                searcher = dijkstraSearcher;
                break;
            case "PS":
                searcher = psSearcher;
                break;
            case "Bisectional":
            case "StepControlled":
            default:
                searcher = pgepsSearcher;
                break;
        }

        LogManager.getInstance().log(
            "[EventDrivenMode] routeRequest: client" + client.getId()
            + ", method=" + methodName + ", numLoop=" + numLoop);

        searcher.search(client, numLoop);
    }

    // ─────────────────────────────────────────────────────────────────
    // 状態取得
    // ─────────────────────────────────────────────────────────────────

    /** 飛行中 UAV 数 */
    public int getActiveFlights() {
        return MemoryFlightScheduler.getInstance().getActiveFlights();
    }

    /** 完了 UAV 数 */
    public int getCompletedFlights() {
        return MemoryFlightScheduler.getInstance().getCompletedFlights();
    }

    /** ジョブキューサイズ */
    public int getJobQueueSize() {
        return MemoryJobQueue.getInstance().getQueueSize();
    }

    // ─────────────────────────────────────────────────────────────────
    // 終了処理
    // ─────────────────────────────────────────────────────────────────

    /**
     * シミュレーターを停止する。BoundaryController の終了時に呼ぶ。
     */
    public void shutdown() {
        // ワーカー停止
        for (MemoryUAVWorker worker : workers) {
            worker.stop();
        }
        if (workerExecutor != null) {
            workerExecutor.shutdownNow();
        }

        // MemoryFlightScheduler 停止
        MemoryFlightScheduler.reset();

        // カスタムスケールディレクトリをクリア
        BoundaryController.setCustomScaleDir(null);

        System.out.println("[EventDrivenMode] EventDrivenMemorySimulator 停止");
        LogManager.getInstance().log("[EventDrivenMode] EventDrivenMemorySimulator 停止");
    }
}
