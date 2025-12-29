package server.scheduler;

import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import server.redis.*;
import server.util.LogManager;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 飛行スケジューラ
 * Phase 3b-3: イベントスケジューリング方式による非同期飛行管理
 *
 * リンク通過時刻を事前計算し、ScheduledExecutorServiceで
 * 正確なタイミングでイベントを発火させる
 */
public class FlightScheduler {

    private static FlightScheduler instance;

    private ScheduledExecutorService scheduler;
    private LinkCapacityManager capacityManager;
    private WaitingUAVManager waitingManager;
    private UAVJobQueue jobQueue;

    // Pub/Sub
    private RedissonClient client;
    private RTopic linkPassedTopic;
    private RTopic completionTopic;

    // 統計情報
    private AtomicInteger activeFlights = new AtomicInteger(0);
    private AtomicInteger completedFlights = new AtomicInteger(0);
    private AtomicInteger linkPassedCount = new AtomicInteger(0);

    // スレッドプールサイズ（イベント処理は瞬時なので少数で十分）
    private static final int SCHEDULER_POOL_SIZE = 8;

    /**
     * シングルトンインスタンス取得
     */
    public static synchronized FlightScheduler getInstance() {
        if (instance == null) {
            instance = new FlightScheduler();
        }
        return instance;
    }

    /**
     * コンストラクタ
     */
    private FlightScheduler() {
        try {
            RedisConnectionManager connectionManager = RedisConnectionManager.getInstance();
            if (connectionManager.isConnected()) {
                this.client = connectionManager.getClient();
                this.scheduler = Executors.newScheduledThreadPool(SCHEDULER_POOL_SIZE);
                this.capacityManager = new LinkCapacityManager();
                this.waitingManager = new WaitingUAVManager();
                this.jobQueue = new UAVJobQueue();

                // Pub/Subトピック
                this.linkPassedTopic = client.getTopic(UAVEventChannels.LINK_PASSED);
                this.completionTopic = client.getTopic(UAVEventChannels.COMPLETION);

                LogManager.getInstance().log("Phase 3b-3: FlightScheduler initialized (poolSize=" + SCHEDULER_POOL_SIZE + ")");
            } else {
                throw new RuntimeException("Redis connection required");
            }
        } catch (Exception e) {
            LogManager.getInstance().error("FlightScheduler initialization failed", e);
            throw new RuntimeException("FlightScheduler initialization failed", e);
        }
    }

    /**
     * テスト用コンストラクタ（依存性注入）
     */
    public FlightScheduler(RedissonClient client, LinkCapacityManager capacityManager,
                           WaitingUAVManager waitingManager, UAVJobQueue jobQueue) {
        this.client = client;
        this.scheduler = Executors.newScheduledThreadPool(SCHEDULER_POOL_SIZE);
        this.capacityManager = capacityManager;
        this.waitingManager = waitingManager;
        this.jobQueue = jobQueue;

        if (client != null) {
            this.linkPassedTopic = client.getTopic(UAVEventChannels.LINK_PASSED);
            this.completionTopic = client.getTopic(UAVEventChannels.COMPLETION);
        }

        LogManager.getInstance().log("Phase 3b-3: FlightScheduler initialized (test mode)");
    }

    /**
     * 飛行を開始（非同期・即座にreturn）
     *
     * @param job UAVジョブ
     */
    public void startFlight(UAVJob job) {
        int linkIndex = job.getCurrentPathIndex();

        // 飛行開始時刻を記録
        job.setCurrentLinkStartTime(System.currentTimeMillis());

        // 飛行中カウント増加
        activeFlights.incrementAndGet();

        LogManager.getInstance().log(
            "Phase 3b-3: UAV " + job.getUavId() + " 飛行開始 " +
            "(path=" + formatPath(job.getPath()) + ", linkIndex=" + linkIndex + ")"
        );

        // 最初のリンク飛行をスケジュール
        scheduleNextLink(job, linkIndex);
    }

    /**
     * 次のリンク通過をスケジュール
     *
     * @param job UAVジョブ
     * @param linkIndex 飛行するリンクのインデックス
     */
    private void scheduleNextLink(UAVJob job, int linkIndex) {
        // 飛行時間を計算
        double distance = job.getLinkDistance(linkIndex);
        double speed = job.getSpeed();
        double flightTimeSec = distance / speed;
        long flightTimeMs = (long)(flightTimeSec * 1000);

        int[] path = job.getPath();
        int fromNode = path[linkIndex];
        int toNode = path[linkIndex + 1];

        LogManager.getInstance().log(
            "Phase 3b-3: UAV " + job.getUavId() + " リンク " + fromNode + "→" + toNode +
            " スケジュール (" + String.format("%.2f", distance) + "m, " +
            String.format("%.2f", flightTimeSec) + "s後)"
        );

        // リンク通過イベントをスケジュール
        scheduler.schedule(
            () -> onLinkPassed(job, linkIndex),
            flightTimeMs,
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * リンク通過時の処理（スケジュールされた時刻に実行）
     *
     * @param job UAVジョブ
     * @param linkIndex 通過したリンクのインデックス
     */
    private void onLinkPassed(UAVJob job, int linkIndex) {
        int[] path = job.getPath();
        int fromNode = path[linkIndex];
        int toNode = path[linkIndex + 1];

        // 1. 経過時間を更新
        double linkDistance = job.getLinkDistance(linkIndex);
        double linkTime = linkDistance / job.getSpeed();
        job.addElapsedFlightTime(linkTime);

        int count = linkPassedCount.incrementAndGet();

        LogManager.getInstance().log(
            "Phase 3b-3: UAV " + job.getUavId() + " リンク通過 " + fromNode + "→" + toNode +
            " (経過=" + String.format("%.2f", job.getElapsedFlightTime()) + "s, 総通過数=" + count + ")"
        );

        // 2. リンク通過イベントを送信（Pub/Sub）
        publishLinkPassedEvent(job, fromNode, toNode, linkIndex);

        // 3. 容量回復
        double newCapacity = capacityManager.recoverCapacity(fromNode, toNode);

        // 4. 待機UAVがいれば再ジョブ化
        if (waitingManager.hasWaitingUAV(fromNode, toNode)) {
            UAVJob waitingJob = waitingManager.dequeue(fromNode, toNode);
            if (waitingJob != null) {
                jobQueue.enqueueJob(waitingJob);
                LogManager.getInstance().log(
                    "Phase 3b-3: 待機UAV " + waitingJob.getUavId() +
                    " を再ジョブ化 (link " + fromNode + "→" + toNode + ")"
                );
            }
        }

        // 5. 最終リンクか判定
        if (linkIndex >= path.length - 2) {
            onFlightCompleted(job);
            return;
        }

        // 6. 次のリンクの容量チェック
        int nextFrom = path[linkIndex + 1];
        int nextTo = path[linkIndex + 2];

        if (!capacityManager.tryConsumeCapacity(nextFrom, nextTo)) {
            // 容量不足 → 途中待機
            onMidFlightWaiting(job, linkIndex + 1, nextFrom, nextTo);
            return;
        }

        // 7. 次のリンク飛行をスケジュール
        job.setCurrentLinkStartTime(System.currentTimeMillis());
        scheduleNextLink(job, linkIndex + 1);
    }

    /**
     * 途中リンクで待機
     *
     * @param job UAVジョブ
     * @param waitingLinkIndex 待機するリンクのインデックス
     * @param fromNode リンクの始点
     * @param toNode リンクの終点
     */
    private void onMidFlightWaiting(UAVJob job, int waitingLinkIndex, int fromNode, int toNode) {
        // 再開位置を記録
        job.setCurrentPathIndex(waitingLinkIndex);

        // 待機キューに登録
        waitingManager.enqueue(fromNode, toNode, job);

        // 飛行中から削除
        activeFlights.decrementAndGet();

        LogManager.getInstance().log(
            "Phase 3b-3: UAV " + job.getUavId() + " 途中待機 " +
            "(link " + fromNode + "→" + toNode + ", linkIndex=" + waitingLinkIndex + ")"
        );
    }

    /**
     * 飛行完了
     *
     * @param job 完了したジョブ
     */
    private void onFlightCompleted(UAVJob job) {
        activeFlights.decrementAndGet();
        int completed = completedFlights.incrementAndGet();

        LogManager.getInstance().log(
            "Phase 3b-3: UAV " + job.getUavId() + " 飛行完了 " +
            "(総距離=" + String.format("%.2f", job.getTotalDistance()) + "m, " +
            "総時間=" + String.format("%.2f", job.getElapsedFlightTime()) + "s, " +
            "総完了数=" + completed + ")"
        );

        // 完了イベント送信
        publishCompletionEvent(job);
    }

    /**
     * リンク通過イベント送信
     */
    private void publishLinkPassedEvent(UAVJob job, int fromNode, int toNode, int linkIndex) {
        if (linkPassedTopic == null) return;

        int[] path = job.getPath();
        int nextFromNode = -1;
        int nextToNode = -1;
        if (linkIndex + 1 < path.length - 1) {
            nextFromNode = path[linkIndex + 1];
            nextToNode = path[linkIndex + 2];
        }

        UAVLinkPassedEvent event = new UAVLinkPassedEvent(
            job.getUavId(),
            job.getClientId(),
            fromNode,
            toNode,
            nextFromNode,
            nextToNode,
            path,
            linkIndex,
            job.getElapsedFlightTime()
        );

        linkPassedTopic.publish(event);
    }

    /**
     * 完了イベント送信
     */
    private void publishCompletionEvent(UAVJob job) {
        if (completionTopic == null) return;

        UAVCompletionEvent event = new UAVCompletionEvent(
            job.getUavId(),
            job.getClientId(),
            job.getTotalDistance(),
            job.getElapsedFlightTime(),
            0.0,  // totalWaitingTime（将来実装）
            job.getPath(),
            job.getSourceBeaconId(),
            job.getDestinationBeaconId()
        );

        completionTopic.publish(event);
    }

    /**
     * 経路を文字列に整形
     */
    private String formatPath(int[] path) {
        if (path == null || path.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < path.length; i++) {
            if (i > 0) sb.append("→");
            sb.append(path[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    // 統計情報取得

    public int getActiveFlights() {
        return activeFlights.get();
    }

    public int getCompletedFlights() {
        return completedFlights.get();
    }

    public int getLinkPassedCount() {
        return linkPassedCount.get();
    }

    /**
     * カウンタをリセット
     */
    public void resetCounters() {
        activeFlights.set(0);
        completedFlights.set(0);
        linkPassedCount.set(0);
    }

    /**
     * スケジューラを停止
     */
    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
            LogManager.getInstance().log("Phase 3b-3: FlightScheduler shutdown");
        }
    }

    /**
     * シングルトンインスタンスをリセット（テスト用）
     */
    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
    }
}
