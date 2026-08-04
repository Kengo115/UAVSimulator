package operator;

import operator.debug.DebugModeHook;
import shared.client.Client;
import shared.redis.RedisConnectionManager;
import shared.redis.VizStateManager;
import shared.util.LogManager;
import network_manager.scheduler.FlightScheduler;

import org.redisson.api.RTopic;
import org.redisson.client.codec.StringCodec;

import java.io.IOException;
import java.util.concurrent.*;

/**
 * デバッグモード コントローラー
 *
 * make run debug で起動し、ブラウザUI から以下を操作できる:
 *   - 事業者（クライアント）生成: 経路探索 → 全UAV経路表示 → 飛行開始
 *   - 一時停止 / 再開（B レベル完全停止）
 *   - 初期化（Redis flushdb + ワーカー再起動）
 *
 * Redis pub/sub チャンネル "debug:command" を双方向通信に使用:
 *   Python → Java: ASSIGN:{src}:{dst}:{uavCount}:{method}, FLY:{clientId},
 *                   PAUSE, RESUME, RESET
 *   Java → Python: ROUTES_READY:{clientId}, RESET_DONE
 */
public class DebugModeController {

    static final int DEBUG_PORT = 9001;
    private static final String COMMAND_CHANNEL = "debug:command";

    private final BoundaryController boundaryController;
    private final java.util.concurrent.atomic.AtomicInteger nextClientId =
        new java.util.concurrent.atomic.AtomicInteger(1);

    private Process debugServerProcess = null;
    private boolean resetRequested = false;

    public DebugModeController(BoundaryController bc) {
        this.boundaryController = bc;
    }

    /**
     * デバッグモードのメインループを開始する。
     * BoundaryController.main() の DEBUG_MODE 分岐から呼ばれる。
     * initializeRedisWorker() は呼び出し前に完了していること。
     */
    public void start() {
        System.out.println("=== UAV デバッグモード ===");
        System.out.println("  ポート: " + DEBUG_PORT);
        System.out.println("  終了: Ctrl+C");

        // DebugModeHook を有効化
        DebugModeHook.setDebugMode(true);

        // VizStateManager を有効化
        VizStateManager.getInstance().enable(
            System.getenv().getOrDefault("SIM_ID", "1"),
            RedisConnectionManager.getInstance().getClient()
        );

        // debug_server.py を起動
        startDebugServer();

        // Redis コマンドを購読
        subscribeCommands();

        System.out.println("✓ デバッグモード起動完了 → http://localhost:" + DEBUG_PORT);

        // シャットダウンフックを登録
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

        // メインスレッドをブロック（シャットダウンまで待機）
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // debug_server.py 起動
    // ------------------------------------------------------------------

    private void startDebugServer() {
        try {
            String redisPort = System.getenv().getOrDefault("REDIS_PORT", "6379");
            String topologyPath = "config/topology/koriyama_topology.txt";

            ProcessBuilder pb = new ProcessBuilder(
                ".venv/bin/python3", "scripts/debug_server.py",
                "--port", String.valueOf(DEBUG_PORT),
                "--topology", topologyPath,
                "--redis-port", redisPort
            );
            pb.inheritIO();
            debugServerProcess = pb.start();
            LogManager.getInstance().log(
                "DebugModeController: debug_server.py 起動 (port=" + DEBUG_PORT + ")");
        } catch (IOException e) {
            System.err.println("⚠ debug_server.py の起動に失敗しました: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Redis pub/sub コマンド受信
    // ------------------------------------------------------------------

    private void subscribeCommands() {
        var redisClient = RedisConnectionManager.getInstance().getClient();
        RTopic topic = redisClient.getTopic(COMMAND_CHANNEL, StringCodec.INSTANCE);
        topic.addListener(String.class, (channel, msg) -> handleCommand(msg));
        LogManager.getInstance().log(
            "DebugModeController: チャンネル '" + COMMAND_CHANNEL + "' 購読開始");
    }

    private void handleCommand(String msg) {
        if (msg == null) return;
        LogManager.getInstance().log("DebugModeController: コマンド受信 → " + msg);

        if (msg.startsWith("ASSIGN:")) {
            handleAssign(msg);
        } else if (msg.startsWith("FLY:")) {
            handleFly(msg);
        } else if (msg.equals("PAUSE")) {
            FlightScheduler.getInstance().setPaused(true);
            System.out.println("[DEBUG] 一時停止");
        } else if (msg.equals("RESUME")) {
            FlightScheduler.getInstance().setPaused(false);
            System.out.println("[DEBUG] 再開");
        } else if (msg.equals("RESET")) {
            handleReset();
        }
        // ROUTES_READY は debug_server.py 向けなのでここでは無視
    }

    // ------------------------------------------------------------------
    // ASSIGN: 事業者生成 → 経路探索
    // ------------------------------------------------------------------

    private void handleAssign(String msg) {
        // フォーマット: ASSIGN:{src}:{dst}:{uavCount}:{method}
        String[] parts = msg.split(":");
        if (parts.length < 5) {
            LogManager.getInstance().log("DebugModeController: ASSIGN 不正フォーマット: " + msg);
            return;
        }
        try {
            int src      = Integer.parseInt(parts[1]);
            int dst      = Integer.parseInt(parts[2]);
            int uavCount = Integer.parseInt(parts[3]);
            int methodId = Integer.parseInt(parts[4]);
            int clientId = nextClientId.getAndIncrement();

            System.out.printf("[DEBUG] ASSIGN client%d: src=%d dst=%d uavs=%d method=%d%n",
                clientId, src, dst, uavCount, methodId);

            // 経路探索手法を設定
            BoundaryController.RouteSearchMethod method =
                BoundaryController.RouteSearchMethod.fromId(methodId);
            BoundaryController.setRouteSearchMethod(method);

            // ScheduleEntry を作成してクライアント生成
            ClientScheduleLoader.ScheduleEntry entry =
                new ClientScheduleLoader.ScheduleEntry(clientId, src, dst, uavCount, 0);
            Client client = boundaryController.createClientFromSchedule(entry);

            // 非同期で routeRequest 実行
            // DebugModeHook.waitForFlyApproved() が FLY_APPROVED まで内部でブロック
            new Thread(() -> {
                try {
                    boundaryController.routeRequest(client);
                    LogManager.getInstance().log(
                        "DebugModeController: client" + clientId + " UAVジョブ投入完了");
                } catch (IOException e) {
                    LogManager.getInstance().error(
                        "DebugModeController: routeRequest 失敗 client" + clientId, e);
                }
            }, "debug-route-" + clientId).start();

        } catch (NumberFormatException e) {
            LogManager.getInstance().log("DebugModeController: ASSIGN パース失敗: " + msg);
        } catch (Exception e) {
            LogManager.getInstance().error("DebugModeController: handleAssign エラー", e);
        }
    }

    // ------------------------------------------------------------------
    // FLY: 飛行開始（CountDownLatch を解放してジョブ投入）
    // ------------------------------------------------------------------

    private void handleFly(String msg) {
        // フォーマット: FLY:{clientId}
        String[] parts = msg.split(":");
        if (parts.length < 2) return;
        try {
            int clientId = Integer.parseInt(parts[1]);
            DebugModeHook.getInstance().flyApproved(clientId);
            System.out.println("[DEBUG] FLY_APPROVED client" + clientId);
        } catch (NumberFormatException e) {
            LogManager.getInstance().log("DebugModeController: FLY パース失敗: " + msg);
        }
    }

    // ------------------------------------------------------------------
    // RESET: 全状態を初期化
    // ------------------------------------------------------------------

    private void handleReset() {
        System.out.println("[DEBUG] RESET 実行中...");
        resetRequested = true;

        // 0. 全飛行中UAVをキャンセル（スケジューラの残タスクが flushdb 後に発火しないよう先処理）
        FlightScheduler.getInstance().cancelAllFlights();

        // 1. 全ラッチをキャンセル（待機中の routeRequest スレッドを解放）
        DebugModeHook.getInstance().reset();

        // 2. FlightScheduler の一時停止を解除 & カウンタリセット
        FlightScheduler.getInstance().setPaused(false);
        FlightScheduler.getInstance().resetCounters();

        // 3. clientId カウンタをリセット
        nextClientId.set(1);

        // 4. Redis flushdb（ジョブキュー・リンク容量・UAV状態を全クリア）
        try {
            RedisConnectionManager.getInstance().getClient().getKeys().flushdb();
            LogManager.getInstance().log("DebugModeController: Redis flushdb 完了");
        } catch (Exception e) {
            LogManager.getInstance().error("DebugModeController: Redis flushdb 失敗", e);
        }

        // 5. VizStateManager を再有効化
        VizStateManager.getInstance().enable(
            System.getenv().getOrDefault("SIM_ID", "1"),
            RedisConnectionManager.getInstance().getClient()
        );

        // 6. Redisワーカーを再初期化（ジョブキュークリア・リンク容量再設定・ワーカー再起動）
        BoundaryController.initializeRedisWorker();

        // 7. RESET_DONE を発行して UI に通知
        try {
            RTopic topic = RedisConnectionManager.getInstance().getClient()
                .getTopic(COMMAND_CHANNEL, StringCodec.INSTANCE);
            topic.publish("RESET_DONE");
        } catch (Exception e) {
            LogManager.getInstance().error("DebugModeController: RESET_DONE 発行失敗", e);
        }

        // 8. RESET フラグをクリア（新しいジョブ投入を許可）
        DebugModeHook.getInstance().clearResetFlag();

        resetRequested = false;
        System.out.println("[DEBUG] RESET 完了");
    }

    // ------------------------------------------------------------------
    // シャットダウン
    // ------------------------------------------------------------------

    private void shutdown() {
        LogManager.getInstance().log("DebugModeController: シャットダウン");
        DebugModeHook.setDebugMode(false);
        DebugModeHook.getInstance().reset();
        if (debugServerProcess != null) {
            debugServerProcess.destroy();
        }
        RedisConnectionManager.getInstance().disconnect();
    }
}
