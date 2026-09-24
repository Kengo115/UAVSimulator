package operator.debug;

import org.redisson.api.RTopic;
import org.redisson.client.codec.StringCodec;
import shared.redis.RedisConnectionManager;
import shared.util.LogManager;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * デバッグモード フック シングルトン
 *
 * RouteSearcher（AbstractPhysarumSolverRouteSearcher / DijkstraRouteSearcher）と
 * DebugModeController を疎結合で繋ぐ。
 *
 * フロー:
 *  1. RouteSearcher が pendingJobs 確定後に onPendingJobsReady() を呼ぶ
 *     → Redis に経路結果を書き込み、ROUTES_READY:{clientId} を発行
 *     → CountDownLatch を登録
 *  2. RouteSearcher が waitForFlyApproved() でブロック
 *  3. DebugModeController が FLY:{clientId} を受信 → flyApproved() を呼ぶ
 *     → CountDownLatch を解放
 *  4. RouteSearcher がブロック解除 → UAVジョブをキューに投入
 */
public class DebugModeHook {

    private static final DebugModeHook INSTANCE = new DebugModeHook();
    private static final AtomicBoolean debugMode = new AtomicBoolean(false);

    private static final String COMMAND_CHANNEL = "debug:command";
    private static final String ROUTE_RESULTS_KEY_PREFIX = "debug:route_results:";
    private static final long FLY_TIMEOUT_MINUTES = 30;

    /** clientId → CountDownLatch（飛行開始待ち） */
    private final ConcurrentHashMap<Integer, CountDownLatch> flyLatches = new ConcurrentHashMap<>();

    /**
     * RESET 実行中フラグ。
     * waitForFlyApproved() がラッチ解放で戻った後、RouteSearcher 側で
     * "FLY による解放" か "RESET による解放" かを区別するために使う。
     */
    private final AtomicBoolean resetActive = new AtomicBoolean(false);

    private DebugModeHook() {}

    public static DebugModeHook getInstance() { return INSTANCE; }
    public static boolean isDebugMode() { return debugMode.get(); }
    public static void setDebugMode(boolean v) { debugMode.set(v); }

    /** RESET が進行中かどうか（RouteSearcher がジョブ投入をスキップするために使う） */
    public static boolean isResetActive() { return INSTANCE.resetActive.get(); }

    // ------------------------------------------------------------------
    // RouteSearcher 側から呼ばれる公開データクラス
    // ------------------------------------------------------------------

    public static class PendingJobInfo {
        public final int uavId;
        public final int clientId;
        public final int[] path;
        public final double[] linkDistances;
        public final double speed;
        public final int delaySeconds;

        public PendingJobInfo(int uavId, int clientId, int[] path,
                              double[] linkDistances, double speed, int delaySeconds) {
            this.uavId = uavId;
            this.clientId = clientId;
            this.path = path;
            this.linkDistances = linkDistances;
            this.speed = speed;
            this.delaySeconds = delaySeconds;
        }
    }

    // ------------------------------------------------------------------
    // RouteSearcher から呼ばれるメイン API
    // ------------------------------------------------------------------

    /**
     * pendingJobs が全て確定した後、RouteSearcher から呼ばれる。
     * Redis に経路結果を JSON 文字列で書き込み、ROUTES_READY を発行し、
     * FLY_APPROVED 待機用の CountDownLatch を登録する。
     */
    public void onPendingJobsReady(int clientId, List<PendingJobInfo> jobs, int src, int dst) {
        try {
            // JSON を手動構築（Redisson の StringCodec で保存）
            StringBuilder uavArray = new StringBuilder("[");
            for (int i = 0; i < jobs.size(); i++) {
                PendingJobInfo j = jobs.get(i);
                if (i > 0) uavArray.append(",");
                uavArray.append("{");
                uavArray.append("\"uavId\":").append(j.uavId).append(",");
                uavArray.append("\"path\":").append(intArrayToJson(j.path)).append(",");
                uavArray.append("\"linkDistances\":").append(doubleArrayToJson(j.linkDistances)).append(",");
                uavArray.append("\"speed\":").append(j.speed).append(",");
                uavArray.append("\"delaySeconds\":").append(j.delaySeconds);
                uavArray.append("}");
            }
            uavArray.append("]");

            String json = "{" +
                "\"clientId\":" + clientId + "," +
                "\"src\":" + src + "," +
                "\"dst\":" + dst + "," +
                "\"uavCount\":" + jobs.size() + "," +
                "\"status\":\"ready\"," +
                "\"uavs\":" + uavArray +
                "}";

            // Redis に書き込み（StringCodec で生文字列として保存）
            var redisClient = RedisConnectionManager.getInstance().getClient();
            var bucket = redisClient.getBucket(ROUTE_RESULTS_KEY_PREFIX + clientId, StringCodec.INSTANCE);
            bucket.set(json);

            // CountDownLatch を先に登録してからイベント発行（FLY が先に来ても対応）
            flyLatches.put(clientId, new CountDownLatch(1));

            // ROUTES_READY を発行（debug_server.py が受信して UI に通知）
            RTopic topic = redisClient.getTopic(COMMAND_CHANNEL, StringCodec.INSTANCE);
            topic.publish("ROUTES_READY:" + clientId);

            LogManager.getInstance().log(
                "DebugModeHook: ROUTES_READY:" + clientId + " 発行 (src=" + src + " dst=" + dst + " UAV数=" + jobs.size() + ")");

        } catch (Exception e) {
            LogManager.getInstance().error("DebugModeHook.onPendingJobsReady エラー", e);
        }
    }

    /**
     * FLY_APPROVED まで待機する（RouteSearcher スレッドがここでブロック）。
     * タイムアウト時は警告ログのみ（UAVジョブ投入は行われない）。
     *
     * @throws InterruptedException スレッド割り込み時
     */
    public void waitForFlyApproved(int clientId) throws InterruptedException {
        CountDownLatch latch = flyLatches.get(clientId);
        if (latch == null) return;
        boolean released = latch.await(FLY_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        flyLatches.remove(clientId);
        if (!released) {
            LogManager.getInstance().log(
                "DebugModeHook: client" + clientId + " FLY_APPROVED タイムアウト（" + FLY_TIMEOUT_MINUTES + "分）");
        }
    }

    /**
     * DebugModeController が FLY:{clientId} を受信したときに呼ぶ。
     * ブロック中の RouteSearcher スレッドを解放する。
     */
    public void flyApproved(int clientId) {
        CountDownLatch latch = flyLatches.get(clientId);
        if (latch != null) {
            latch.countDown();
            LogManager.getInstance().log(
                "DebugModeHook: client" + clientId + " FLY_APPROVED → ラッチ解放");
        } else {
            LogManager.getInstance().log(
                "DebugModeHook: client" + clientId + " FLY_APPROVED → ラッチ未登録（既に投入済み？）");
        }
    }

    /**
     * RESET 時に全ての待機ラッチをキャンセルする。
     * resetActive フラグを true にしてからラッチを解放することで、
     * waitForFlyApproved() から戻った RouteSearcher スレッドが
     * isResetActive() を確認してジョブ投入をスキップできる。
     */
    public void reset() {
        resetActive.set(true);  // ラッチ解放前に先にフラグを立てる
        for (Map.Entry<Integer, CountDownLatch> entry : flyLatches.entrySet()) {
            entry.getValue().countDown();
        }
        flyLatches.clear();
        LogManager.getInstance().log("DebugModeHook: 全ラッチをリセット（resetActive=true）");
    }

    /**
     * RESET 完了後に呼び出し、新しいセッションのジョブ投入を許可する。
     */
    public void clearResetFlag() {
        resetActive.set(false);
        LogManager.getInstance().log("DebugModeHook: resetActive フラグをクリア");
    }

    // ------------------------------------------------------------------
    // ユーティリティ
    // ------------------------------------------------------------------

    private static String intArrayToJson(int[] arr) {
        if (arr == null || arr.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arr[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private static String doubleArrayToJson(double[] arr) {
        if (arr == null || arr.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arr[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
