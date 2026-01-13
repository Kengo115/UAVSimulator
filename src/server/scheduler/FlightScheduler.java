package server.scheduler;

import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import server.redis.*;
import server.util.LinkStatusRecorder;
import server.util.LogManager;
import server.util.ResultOutputManager;

import java.io.IOException;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 飛行スケジューラ
 * Phase 3b-3: イベントスケジューリング方式による非同期飛行管理
 * Phase 3b-10: スレッドプール・オートスケーリング
 *
 * リンク通過時刻を事前計算し、ScheduledExecutorServiceで
 * 正確なタイミングでイベントを発火させる
 */
public class FlightScheduler {

    private static FlightScheduler instance;

    private ScheduledThreadPoolExecutor scheduler;
    private LinkCapacityManager capacityManager;
    private WaitingUAVManager waitingManager;
    private UAVJobQueue jobQueue;
    private PathWaitingManager pathWaitingManager;  // Phase 4: 経路待ちUAV管理

    // Pub/Sub
    private RedissonClient client;
    private RTopic linkPassedTopic;
    private RTopic completionTopic;

    // 統計情報
    private AtomicInteger activeFlights = new AtomicInteger(0);
    private AtomicInteger completedFlights = new AtomicInteger(0);
    private AtomicInteger linkPassedCount = new AtomicInteger(0);
    private AtomicInteger skippedJobs = new AtomicInteger(0);

    // Phase 3b-8: セッションID（古いプロセスからのジョブを無視するため）
    private String currentSessionId;

    // Phase 3b-10: オートスケーリング設定
    private static final int MIN_POOL_SIZE = 16;
    private static final int MAX_POOL_SIZE = 32;
    private static final int SCALE_STEP = 4;
    private static final double SCALE_UP_THRESHOLD = 0.9;    // 90%でスケールアップ
    private static final double SCALE_DOWN_THRESHOLD = 0.5;  // 50%未満でスケールダウン
    private static final int SCALE_DOWN_DELAY_SECONDS = 30;  // 30秒継続でスケールダウン
    private static final int MONITOR_INTERVAL_MS = 1000;     // 監視間隔1秒

    // オートスケーリング用
    private ScheduledExecutorService monitorExecutor;
    private AtomicLong lowUsageStartTime = new AtomicLong(0);

    // スレッドプールサイズ（初期値、後方互換性のため残す）
    private static final int SCHEDULER_POOL_SIZE = MIN_POOL_SIZE;

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
                this.scheduler = new ScheduledThreadPoolExecutor(MIN_POOL_SIZE);
                this.capacityManager = new LinkCapacityManager();
                this.waitingManager = new WaitingUAVManager();
                this.jobQueue = new UAVJobQueue();
                this.pathWaitingManager = new PathWaitingManager();  // Phase 4

                // Pub/Subトピック
                this.linkPassedTopic = client.getTopic(UAVEventChannels.LINK_PASSED);
                this.completionTopic = client.getTopic(UAVEventChannels.COMPLETION);

                // Phase 3b-10: オートスケーリング監視開始
                startAutoScalingMonitor();

                LogManager.getInstance().log("Phase 3b-10: FlightScheduler initialized (poolSize=" + MIN_POOL_SIZE +
                    ", autoScaling=" + MIN_POOL_SIZE + "-" + MAX_POOL_SIZE + ")");
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
        this.scheduler = new ScheduledThreadPoolExecutor(MIN_POOL_SIZE);
        this.capacityManager = capacityManager;
        this.waitingManager = waitingManager;
        this.jobQueue = jobQueue;
        this.pathWaitingManager = new PathWaitingManager();  // Phase 4

        if (client != null) {
            this.linkPassedTopic = client.getTopic(UAVEventChannels.LINK_PASSED);
            this.completionTopic = client.getTopic(UAVEventChannels.COMPLETION);
        }

        // テストモードでもオートスケーリング有効
        startAutoScalingMonitor();

        LogManager.getInstance().log("Phase 3b-10: FlightScheduler initialized (test mode, autoScaling enabled)");
    }

    /**
     * 飛行を開始（非同期・即座にreturn）
     *
     * @param job UAVジョブ
     */
    public void startFlight(UAVJob job) {
        int linkIndex = job.getCurrentPathIndex();
        int[] path = job.getPath();
        int fromNode = path[linkIndex];
        int toNode = path[linkIndex + 1];

        // Phase 7-11: 待機解除時は待機終了を記録
        // 注: getTotalWaitingTime()ではなくgetWaitingStartTime()で「現在待機中か」を判定
        if (job.getWaitingStartTime() > 0) {
            LinkStatusRecorder.getInstance().onWaitingEnd(fromNode, toNode);
        }

        // Phase 3b-9: 待機中だった場合は待機時間を確定
        job.endWaiting();

        // 飛行開始時刻を記録
        job.setCurrentLinkStartTime(System.currentTimeMillis());

        // 飛行中カウント増加
        activeFlights.incrementAndGet();

        // Phase 7-4: リンク進入を記録
        LinkStatusRecorder.getInstance().onLinkEnter(fromNode, toNode);

        LogManager.getInstance().log(
            "Phase 3b-3: client" + job.getClientId() + " UAV" + job.getUavId() + " 飛行開始 " +
            "(path=" + formatPath(job.getPath()) + ", linkIndex=" + linkIndex +
            ", 累積待機=" + String.format("%.2f", job.getTotalWaitingTime()) + "s)"
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
            "Phase 3b-3: client" + job.getClientId() + " UAV" + job.getUavId() + " リンク " + fromNode + "→" + toNode +
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

        // Phase 7-4: リンク退出を記録
        LinkStatusRecorder.getInstance().onLinkExit(fromNode, toNode);

        // 1. 経過時間を更新
        double linkDistance = job.getLinkDistance(linkIndex);
        double linkTime = linkDistance / job.getSpeed();
        job.addElapsedFlightTime(linkTime);

        int count = linkPassedCount.incrementAndGet();

        LogManager.getInstance().log(
            "Phase 3b-3: client" + job.getClientId() + " UAV" + job.getUavId() + " リンク通過 " + fromNode + "→" + toNode +
            " (経過=" + String.format("%.2f", job.getElapsedFlightTime()) + "s, 総通過数=" + count + ")"
        );

        // 2. リンク通過イベントを送信（Pub/Sub）
        publishLinkPassedEvent(job, fromNode, toNode, linkIndex);

        // 2.5 Phase 4: 第一ホップ通過時に経路待ちUAVへ経路コピー
        if (linkIndex == 0) {
            processFirstHopPathCopy(job);
        }

        // 3. 容量回復（順方向・逆方向両方）
        double newCapacity = capacityManager.recoverCapacity(fromNode, toNode);

        // 4. 待機UAVがいれば再ジョブ化（順方向）
        if (waitingManager.hasWaitingUAV(fromNode, toNode)) {
            UAVJob waitingJob = waitingManager.dequeue(fromNode, toNode);
            if (waitingJob != null) {
                jobQueue.enqueueJob(waitingJob);
                LogManager.getInstance().log(
                    "Phase 3b-3: 待機 client" + waitingJob.getClientId() + " UAV" + waitingJob.getUavId() +
                    " を再ジョブ化 (link " + fromNode + "→" + toNode + ")"
                );
            }
        }

        // 5. 待機UAVがいれば再ジョブ化（逆方向：双方向リンク対応）
        if (waitingManager.hasWaitingUAV(toNode, fromNode)) {
            UAVJob waitingJob = waitingManager.dequeue(toNode, fromNode);
            if (waitingJob != null) {
                jobQueue.enqueueJob(waitingJob);
                LogManager.getInstance().log(
                    "Phase 3b-3: 待機 client" + waitingJob.getClientId() + " UAV" + waitingJob.getUavId() +
                    " を再ジョブ化 (link " + toNode + "→" + fromNode + ", 逆方向リンク回復)"
                );
            }
        }

        // 6. 最終リンクか判定
        if (linkIndex >= path.length - 2) {
            onFlightCompleted(job);
            return;
        }

        // 7. 次のリンクの容量チェック
        int nextFrom = path[linkIndex + 1];
        int nextTo = path[linkIndex + 2];

        if (!capacityManager.tryConsumeCapacity(nextFrom, nextTo)) {
            // 容量不足 → 途中待機
            onMidFlightWaiting(job, linkIndex + 1, nextFrom, nextTo);
            return;
        }

        // 8. 次のリンク飛行をスケジュール
        // Phase 7-4: 次リンク進入を記録
        LinkStatusRecorder.getInstance().onLinkEnter(nextFrom, nextTo);

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

        // Phase 3b-9: 待機開始時刻を記録
        job.startWaiting();

        // Phase 7-4: 待機開始を記録
        LinkStatusRecorder.getInstance().onWaitingStart(fromNode, toNode);

        // 待機キューに登録
        waitingManager.enqueue(fromNode, toNode, job);

        // 飛行中から削除
        activeFlights.decrementAndGet();

        LogManager.getInstance().log(
            "Phase 3b-3: client" + job.getClientId() + " UAV" + job.getUavId() + " 途中待機 " +
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

        // Phase 3b-9: 時間情報を取得
        double realFlightTime = job.getElapsedFlightTime();   // 純粋な飛行時間
        double waitingTime = job.getTotalWaitingTime();        // 待機時間
        double totalTime = job.getTotalTime();                 // 合計時間

        LogManager.getInstance().log(
            "Phase 3b-3: client" + job.getClientId() + " UAV" + job.getUavId() + " 飛行完了 " +
            "(総距離=" + String.format("%.2f", job.getTotalDistance()) + "m, " +
            "飛行時間=" + String.format("%.2f", realFlightTime) + "s, " +
            "待機時間=" + String.format("%.2f", waitingTime) + "s, " +
            "合計=" + String.format("%.2f", totalTime) + "s, " +
            "総完了数=" + completed + ")"
        );

        // Phase 3b-7: 結果ファイルに出力
        try {
            // clientIdをrunCounterとして使用（各事業者ごとにディレクトリ分け）
            int runCounter = job.getClientId();
            ResultOutputManager.outputFlightTimeResult(job, runCounter);
        } catch (IOException e) {
            LogManager.getInstance().error("Phase 3b-7: 結果ファイル出力エラー", e);
        }

        // 完了イベント送信
        publishCompletionEvent(job);

        // Phase 4: 事業者の時間計測（UAV完了通知）
        ClientTimeManager.getInstance().onUAVCompleted(job.getClientId());
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

    /**
     * Phase 4: 第一ホップ通過時に経路待ちUAVへ経路コピー
     *
     * 同一クライアントの経路待ちUAVがいれば、通過したUAVの経路をコピーして
     * ジョブキューに投入する。これにより、経路待ちUAVが飛行を開始できる。
     *
     * @param job 第一ホップを通過したUAVジョブ
     */
    private void processFirstHopPathCopy(UAVJob job) {
        int clientId = job.getClientId();

        // 経路待ちUAVがいるか確認
        if (!pathWaitingManager.hasWaitingUAV(clientId)) {
            return;
        }

        // 経路待ちUAVを取り出し
        UAVJob waitingJob = pathWaitingManager.dequeue(clientId);
        if (waitingJob == null) {
            return;
        }

        // Phase 4: 経路待ち時間を確定
        waitingJob.endPathWaiting();

        // 経路をコピー
        int[] originalPath = job.getPath();
        int[] copiedPath = originalPath.clone();
        waitingJob.setPath(copiedPath);

        // リンク距離をコピー
        double[] originalDistances = job.getLinkDistances();
        if (originalDistances != null) {
            double[] copiedDistances = originalDistances.clone();
            waitingJob.setLinkDistances(copiedDistances);
        }

        // 開始位置を先頭に設定
        waitingJob.setCurrentPathIndex(0);

        // セッションIDをコピー（同一セッションであることを保証）
        waitingJob.setSessionId(job.getSessionId());

        // ジョブキューに投入して飛行開始
        jobQueue.enqueueJob(waitingJob);

        LogManager.getInstance().log(
            "Phase 4 [経路待ち→飛行]: client" + clientId + " UAV" + waitingJob.getUavId() +
            " が経路待ちから飛行開始 (経路コピー元=UAV" + job.getUavId() +
            ", path=" + formatPath(copiedPath) +
            ", 速度=" + String.format("%.2f", waitingJob.getSpeed()) + "m/s" +
            ", 経路待ち時間=" + String.format("%.2f", waitingJob.getPathWaitTime()) + "s" +
            ", 残り経路待ち=" + pathWaitingManager.getWaitingCount(clientId) + ")"
        );
    }

    /**
     * Phase 4: PathWaitingManagerを取得（RouteSearcherから使用）
     * @return PathWaitingManagerインスタンス
     */
    public PathWaitingManager getPathWaitingManager() {
        return pathWaitingManager;
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
        skippedJobs.set(0);
    }

    // Phase 3b-8: セッションID関連メソッド

    /**
     * セッションIDを設定
     * @param sessionId セッションID
     */
    public void setSessionId(String sessionId) {
        this.currentSessionId = sessionId;
        LogManager.getInstance().log("Phase 3b-8: セッションID設定: " + sessionId);
    }

    /**
     * 現在のセッションIDを取得
     * @return セッションID
     */
    public String getSessionId() {
        return currentSessionId;
    }

    /**
     * ジョブが現在のセッションに属するか検証
     * @param job UAVジョブ
     * @return 現在のセッションに属する場合はtrue
     */
    public boolean isValidSession(UAVJob job) {
        if (currentSessionId == null) {
            // セッションIDが未設定の場合は全て許可（後方互換性）
            return true;
        }
        String jobSessionId = job.getSessionId();
        if (jobSessionId == null) {
            // 古い形式のジョブは拒否
            LogManager.getInstance().log("Phase 3b-8: セッションIDなしのジョブを拒否 (client" + job.getClientId() + " UAV" + job.getUavId() + ")");
            skippedJobs.incrementAndGet();
            return false;
        }
        if (!currentSessionId.equals(jobSessionId)) {
            LogManager.getInstance().log("Phase 3b-8: 別セッションのジョブを拒否 (client" + job.getClientId() + " UAV" + job.getUavId() +
                ", expected=" + currentSessionId + ", got=" + jobSessionId + ")");
            skippedJobs.incrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * スキップされたジョブ数を取得
     */
    public int getSkippedJobs() {
        return skippedJobs.get();
    }

    // Phase 3b-10: オートスケーリング関連メソッド

    /**
     * オートスケーリング監視を開始
     */
    private void startAutoScalingMonitor() {
        monitorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FlightScheduler-AutoScaler");
            t.setDaemon(true);
            return t;
        });

        monitorExecutor.scheduleAtFixedRate(
            this::checkAndScale,
            MONITOR_INTERVAL_MS,
            MONITOR_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );

        LogManager.getInstance().log("Phase 3b-10: オートスケーリング監視開始 (間隔=" + MONITOR_INTERVAL_MS + "ms)");
    }

    /**
     * スレッドプールのスケーリングをチェック・実行
     */
    private void checkAndScale() {
        try {
            int currentPoolSize = scheduler.getCorePoolSize();
            int activeCount = scheduler.getActiveCount();
            double usage = (double) activeCount / currentPoolSize;

            // スケールアップ判定
            if (usage >= SCALE_UP_THRESHOLD && currentPoolSize < MAX_POOL_SIZE) {
                int newSize = Math.min(currentPoolSize + SCALE_STEP, MAX_POOL_SIZE);
                scheduler.setCorePoolSize(newSize);
                lowUsageStartTime.set(0);  // 低使用率カウンタをリセット
                LogManager.getInstance().log(
                    "Phase 3b-10: スケールアップ " + currentPoolSize + " → " + newSize +
                    " (使用率=" + String.format("%.1f", usage * 100) + "%)"
                );
                return;
            }

            // スケールダウン判定
            if (usage < SCALE_DOWN_THRESHOLD && currentPoolSize > MIN_POOL_SIZE) {
                long now = System.currentTimeMillis();
                long lowUsageStart = lowUsageStartTime.get();

                if (lowUsageStart == 0) {
                    // 低使用率開始時刻を記録
                    lowUsageStartTime.set(now);
                } else if (now - lowUsageStart >= SCALE_DOWN_DELAY_SECONDS * 1000L) {
                    // 30秒継続したのでスケールダウン
                    int newSize = Math.max(currentPoolSize - SCALE_STEP, MIN_POOL_SIZE);
                    scheduler.setCorePoolSize(newSize);
                    lowUsageStartTime.set(0);  // カウンタをリセット
                    LogManager.getInstance().log(
                        "Phase 3b-10: スケールダウン " + currentPoolSize + " → " + newSize +
                        " (使用率=" + String.format("%.1f", usage * 100) + "%, " +
                        SCALE_DOWN_DELAY_SECONDS + "秒継続)"
                    );
                }
            } else {
                // 使用率が閾値を超えたらカウンタをリセット
                lowUsageStartTime.set(0);
            }
        } catch (Exception e) {
            LogManager.getInstance().error("Phase 3b-10: オートスケーリングエラー", e);
        }
    }

    /**
     * 現在のスレッドプールサイズを取得
     */
    public int getCurrentPoolSize() {
        return scheduler.getCorePoolSize();
    }

    /**
     * アクティブスレッド数を取得
     */
    public int getActiveThreadCount() {
        return scheduler.getActiveCount();
    }

    /**
     * スレッドプール使用率を取得
     */
    public double getPoolUsage() {
        int poolSize = scheduler.getCorePoolSize();
        int active = scheduler.getActiveCount();
        return poolSize > 0 ? (double) active / poolSize : 0.0;
    }

    /**
     * Phase 7-11: 全UAV飛行完了判定
     * 飛行中UAVと待機中UAVが両方0の場合にtrueを返す
     *
     * @return 全UAVが飛行完了している場合true
     */
    public boolean isAllFlightsCompleted() {
        int flying = activeFlights.get();
        int waiting = waitingManager.getTotalWaitingCount();
        int pathWaiting = pathWaitingManager != null ? pathWaitingManager.getTotalWaitingCount() : 0;

        boolean completed = (flying == 0 && waiting == 0 && pathWaiting == 0);

        if (completed) {
            LogManager.getInstance().log(String.format(
                "Phase 7-11: 全UAV飛行完了確認 (飛行中=%d, 待機中=%d, 経路待ち=%d, 完了済み=%d)",
                flying, waiting, pathWaiting, completedFlights.get()
            ));
        }

        return completed;
    }

    /**
     * Phase 7-11: 現在の飛行状況を取得
     * @return [飛行中, 待機中, 経路待ち, 完了済み]
     */
    public int[] getFlightStatus() {
        return new int[] {
            activeFlights.get(),
            waitingManager.getTotalWaitingCount(),
            pathWaitingManager != null ? pathWaitingManager.getTotalWaitingCount() : 0,
            completedFlights.get()
        };
    }

    /**
     * スケジューラを停止
     */
    public void shutdown() {
        // オートスケーリング監視を停止
        if (monitorExecutor != null && !monitorExecutor.isShutdown()) {
            monitorExecutor.shutdown();
            LogManager.getInstance().log("Phase 3b-10: オートスケーリング監視停止");
        }

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
            LogManager.getInstance().log("Phase 3b-10: FlightScheduler shutdown (最終poolSize=" + scheduler.getCorePoolSize() + ")");
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
