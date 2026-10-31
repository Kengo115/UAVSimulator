package legacy.polling;

import legacy.polling.route.PollingDijkstraRouteSearcher;
import legacy.polling.route.PollingPGEPSRouteSearcher;
import legacy.polling.route.PollingPSRouteSearcher;
import operator.controller.ServerController;
import operator.route.RouteSearcher;
import shared.client.Client;
import shared.client.ClientController;
import shared.item.BeaconCluster;
import shared.item.Link;
import shared.item.Uav;
import shared.util.LogManager;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Option A (ポーリング方式) メモリシミュレーター
 *
 * BoundaryController から委譲されるメモリモードの全体制御クラス。
 * PollingUAVFlyScheduler を起動し、ルートサーチャーを管理する。
 * Singleton パターン（実行期間中に1インスタンス）。
 */
public class PollingMemorySimulator {

    private static PollingMemorySimulator instance;

    // インメモリキュー（スレッドセーフ）
    private final Queue<Uav> flyingUavQueue = new ConcurrentLinkedQueue<>();
    private final Queue<Uav> uavQueue       = new ConcurrentLinkedQueue<>();

    // ルートサーチャー
    private PollingDijkstraRouteSearcher dijkstraSearcher;
    private PollingPSRouteSearcher       psSearcher;
    private PollingPGEPSRouteSearcher    pgepsSearcher;

    // スケジューラ
    private PollingUAVFlyScheduler scheduler;

    // 実行カウンタ（reset() を初回スキップするため）
    private final AtomicInteger runCounter = new AtomicInteger(0);

    private PollingMemorySimulator() {}

    public static synchronized PollingMemorySimulator getInstance() {
        if (instance == null) {
            instance = new PollingMemorySimulator();
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    // 初期化
    // ─────────────────────────────────────────────────────────────────

    /**
     * シミュレーター初期化。BoundaryController の起動時に呼ぶ。
     *
     * @param server           ServerController（既にトポロジ設定済み）
     * @param clientController クライアントコントローラー
     * @param beaconCluster    ビーコンクラスター
     */
    public synchronized void initialize(
            ServerController server,
            ClientController clientController,
            BeaconCluster beaconCluster) {

        int node       = ServerController.getNodeCount();
        Link[][] link  = ServerController.getLinkArray();
        int[][] adjMatrix = server.getAdjMatrix();  // minimal getter added to ServerController

        // ルートサーチャー生成
        dijkstraSearcher = new PollingDijkstraRouteSearcher(
                server, adjMatrix, link, beaconCluster, node,
                flyingUavQueue, uavQueue);

        psSearcher = new PollingPSRouteSearcher(
                server, adjMatrix, link, beaconCluster, node,
                flyingUavQueue, uavQueue);

        pgepsSearcher = new PollingPGEPSRouteSearcher(
                server, adjMatrix, link, beaconCluster, node,
                flyingUavQueue, uavQueue);

        // スケジューラ生成・起動
        scheduler = new PollingUAVFlyScheduler(
                flyingUavQueue, uavQueue,
                clientController, beaconCluster, node);
        scheduler.start();

        System.out.println("[PollingMode] PollingMemorySimulator 初期化完了");
        LogManager.getInstance().log("[PollingMode] PollingMemorySimulator 初期化完了");
    }

    // ─────────────────────────────────────────────────────────────────
    // ルートリクエスト（BoundaryController.routeRequest から呼ばれる）
    // ─────────────────────────────────────────────────────────────────

    /**
     * クライアントのルートリクエストを処理する。
     * Dijkstra のみ numLoop は無視される。
     *
     * @param client           クライアント
     * @param clientController クライアントコントローラー
     * @param server           ServerController（reset/adjMatrix 更新用）
     * @param methodName       手法名（RouteSearchMethod.getName() の値）
     *                         "Dijkstra" / "PS" / "EPS" / "Bisectional" / "StepControlled" など
     * @param numLoop          ループ数（PS/PG-EPS で使用）
     */
    public void routeRequest(
            Client client,
            ClientController clientController,
            ServerController server,
            String methodName,
            int numLoop) throws IOException {

        // Physarum パラメータ・隣接行列リセット（初回以外）
        // server.reset() は link.capacity に触れないため、ポーリングキューに影響しない
        if (runCounter.getAndIncrement() > 0) {
            server.reset();
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

        searcher.search(client, numLoop);
    }

    // ─────────────────────────────────────────────────────────────────
    // 終了処理
    // ─────────────────────────────────────────────────────────────────

    /**
     * シミュレーターを停止する。BoundaryController の終了時に呼ぶ。
     */
    public void shutdown() {
        if (scheduler != null) {
            scheduler.stop();
        }
        System.out.println("[PollingMode] PollingMemorySimulator 停止");
        LogManager.getInstance().log("[PollingMode] PollingMemorySimulator 停止");
    }

    // ─────────────────────────────────────────────────────────────────
    // キュー状態取得
    // ─────────────────────────────────────────────────────────────────

    /** 飛行中 UAV 数 */
    public int getFlyingCount() {
        return flyingUavQueue.size();
    }

    /** 待機中 UAV 数 */
    public int getWaitingCount() {
        return uavQueue.size();
    }

}
