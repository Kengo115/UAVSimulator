package network_manager.scheduler;
import shared.redis.ClientTimeManager;
import shared.redis.LinkCapacityManager;
import shared.redis.PathWaitingManager;
import shared.redis.RedisConnectionManager;
import shared.redis.UAVJobQueue;
import shared.item.UAVJob;

import org.redisson.api.RMap;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import network_manager.redis.*;
import shared.util.Link117DebugLogger;
import shared.util.LinkStatusRecorder;
import shared.util.LogManager;
import shared.util.ResultOutputManager;

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

    // Phase 12-Fix: 飛行中UAV追跡用マップ（clientId -> UAVジョブのセット）
    // リトライ時のキャンセル用のみ使用
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, UAVJob>> activeClientUAVs = new ConcurrentHashMap<>();

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

                // Phase C2: キャンセルリクエストのサブスクライブ
                subscribeCancelRequests(this.client);

                // スレッド数遷移ログを初期化・記録
                LogManager.getInstance().initThreadCountLog();
                LogManager.getInstance().logThreadCountChange(MIN_POOL_SIZE, "INIT");

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
        // Phase 12-Fix: UAV追跡（キャンセル用のみ）
        int clientId = job.getClientId();
        int uavId = job.getUavId();

        // クライアントごとのUAVマップを取得または作成
        ConcurrentHashMap<Integer, UAVJob> clientUAVMap = activeClientUAVs.computeIfAbsent(clientId, k -> new ConcurrentHashMap<>());

        // UAVを追跡（キャンセル用）- 重複チェックはせず常に登録
        // リトライ時にcancelClientFlights()が呼ばれてクリアされるため問題なし
        clientUAVMap.put(uavId, job);

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

        // Phase 5: 1ホップ目の場合は飛行開始をマーク
        // これ以降の待機はtotalWaitingTime（バッテリー消費あり）に記録される
        if (linkIndex == 0 && !job.hasStartedFlight()) {
            job.markFlightStarted();
        }

        // 飛行開始時刻を記録
        job.setCurrentLinkStartTime(System.currentTimeMillis());

        // 飛行中カウント増加
        activeFlights.incrementAndGet();

        // Phase 7-4: リンク進入を記録
        LinkStatusRecorder.getInstance().onLinkEnter(fromNode, toNode);

        LogManager.getInstance().log(
            "Phase 3b-3: client" + job.getClientId() + " UAV" + job.getUavId() + " 飛行開始 " +
            "(path=" + formatPath(job.getPath()) + ", linkIndex=" + linkIndex +
            ", 飛行前待機=" + String.format("%.2f", job.getFlightStayingTime()) + "s" +
            ", 飛行中待機=" + String.format("%.2f", job.getTotalWaitingTime()) + "s)"
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
        // Phase 12-Fix: キャンセルされたUAVは処理をスキップ
        if (job.isCancelled()) {
            LogManager.getInstance().log(
                "Phase 12-Fix: client" + job.getClientId() + " UAV" + job.getUavId() +
                " はキャンセル済みのため処理をスキップします"
            );
            // 飛行中カウント減少（キャンセル時に処理）
            activeFlights.decrementAndGet();
            return;
        }

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

        // 3. 待機UAVの有無をチェック（容量回復と消費をアトミックに処理）
        // Phase 8-Fix: Race condition防止のため、容量回復と待機UAV消費を同時に行う
        int[] normalized = UAVEventChannels.normalizeLink(fromNode, toNode);
        boolean hasWaitingUAV = waitingManager.hasWaitingUAV(normalized[0], normalized[1]);

        if (hasWaitingUAV) {
            // 待機UAVがいる場合: 容量回復と消費が相殺 → Redis容量変化なし
            // キュー経由せず直接飛行開始させる（Race condition防止）
            UAVJob waitingJob = waitingManager.dequeue(normalized[0], normalized[1]);
            if (waitingJob != null) {
                // DEBUG: 117-123リンクの待機キュー削除を専用ログに記録
                int remainingCount = waitingManager.getWaitingCount(normalized[0], normalized[1]);
                Link117DebugLogger.getInstance().logWaitingQueue(normalized[0], normalized[1], "DEQUEUE", remainingCount);

                // 直接飛行開始（容量は0のまま、回復も消費もしない）
                startFlight(waitingJob);
                LogManager.getInstance().log(
                    "Phase 8-Fix: 待機 client" + waitingJob.getClientId() + " UAV" + waitingJob.getUavId() +
                    " を直接飛行開始（容量変化なし）(link " + normalized[0] + "-" + normalized[1] + ")"
                );
            }
        } else {
            // 待機UAVがいない場合: 容量を回復
            double newCapacity = capacityManager.recoverCapacity(fromNode, toNode);
            LogManager.getInstance().log(
                "Phase 8-Fix: link[" + fromNode + "][" + toNode + "] 容量回復 → " + newCapacity
            );
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

        // Phase 8-Debug: 待機数が異常に多い場合に警告ログ出力
        int waitingCount = waitingManager.getWaitingCount(fromNode, toNode);

        // DEBUG: 117-123リンクの待機キュー追加を専用ログに記録
        Link117DebugLogger.getInstance().logWaitingQueue(fromNode, toNode, "ENQUEUE", waitingCount);
        if (waitingCount > 50) {
            LogManager.getInstance().log(
                "WARNING: リンク " + fromNode + "→" + toNode + " で待機UAVが " + waitingCount + " 台に達しました！"
            );
            LogManager.getInstance().log(
                "LINK_CONGESTION_WARNING: 待機UAV過多 (link=" + fromNode + "-" + toNode +
                ",waiting_count=" + waitingCount + ",client=" + job.getClientId() + ",uav=" + job.getUavId() + ")"
            );
        }

        // 飛行中から削除
        activeFlights.decrementAndGet();

        LogManager.getInstance().log(
            "Phase 3b-3: client" + job.getClientId() + " UAV" + job.getUavId() + " 途中待機 " +
            "(link " + fromNode + "→" + toNode + ", linkIndex=" + waitingLinkIndex + ", 待機数=" + waitingCount + ")"
        );
    }

    /**
     * 飛行完了
     *
     * @param job 完了したジョブ
     */
    private void onFlightCompleted(UAVJob job) {
        // Phase 10: 二重完了防止チェック
        if (!job.markCompleted()) {
            LogManager.getInstance().log(
                "Phase 10: client" + job.getClientId() + " UAV" + job.getUavId() +
                " の二重完了を検出しスキップしました"
            );
            return;
        }

        activeFlights.decrementAndGet();
        int completed = completedFlights.incrementAndGet();

        // Phase 5: 時間情報を取得
        double realFlightTime = job.getElapsedFlightTime();       // 純粋な飛行時間
        double flightStayingTime = job.getFlightStayingTime();    // 飛行前待機時間（バッテリー消費なし）
        double waitingTime = job.getTotalWaitingTime();           // 飛行中待機時間（バッテリー消費あり）
        double totalTime = job.getTotalTime();                    // 合計時間

        LogManager.getInstance().log(
            "Phase 3b-3: client" + job.getClientId() + " UAV" + job.getUavId() + " 飛行完了 " +
            "(総距離=" + String.format("%.2f", job.getTotalDistance()) + "m, " +
            "飛行時間=" + String.format("%.2f", realFlightTime) + "s, " +
            "飛行前待機=" + String.format("%.2f", flightStayingTime) + "s, " +
            "飛行中待機=" + String.format("%.2f", waitingTime) + "s, " +
            "合計=" + String.format("%.2f", totalTime) + "s, " +
            "総完了数=" + completed + ")"
        );

        // Phase 3b-7: 結果ファイルに出力
        try {
            // clientIdをrunCounterに変換（clientIdは1始まり、runCounterは0始まり）
            // ResultOutputManager.getDirectoryPath()で+1されるため、-1して調整
            int runCounter = job.getClientId() - 1;
            ResultOutputManager.outputFlightTimeResult(job, runCounter);
        } catch (IOException e) {
            LogManager.getInstance().error("Phase 3b-7: 結果ファイル出力エラー", e);
        }

        // 完了イベント送信
        publishCompletionEvent(job);

        // Phase 4: 事業者の時間計測（UAV完了通知）
        ClientTimeManager.getInstance().onUAVCompleted(job.getClientId());

        // Phase 7-11: 飛行状況を Redis Hash に更新し、全完了チェック
        updateFlightStatusToRedis();
        isAllFlightsCompleted();

        // Phase 12-Fix: activeClientUAVsから削除
        ConcurrentHashMap<Integer, UAVJob> clientUAVMap = activeClientUAVs.get(job.getClientId());
        if (clientUAVMap != null) {
            clientUAVMap.remove(job.getUavId());
            // クライアントのUAVマップが空になったら削除
            if (clientUAVMap.isEmpty()) {
                activeClientUAVs.remove(job.getClientId());
            }
        }
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

        // Phase 4 Fix: 経路割り当てをroute.csvに記録（PathWaiting経由のUAVも記録する）
        try {
            ResultOutputManager.outputRouteAssignment(waitingJob, waitingJob.getClientId() - 1);
        } catch (IOException e) {
            LogManager.getInstance().error("Phase 4 Fix: route.csv 書き込みエラー (client" + clientId + " UAV" + waitingJob.getUavId() + ")", e);
        }

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

                // スレッド数遷移ログに記録
                LogManager.getInstance().logThreadCountChange(newSize, "SCALE_UP");

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

                    // スレッド数遷移ログに記録
                    LogManager.getInstance().logThreadCountChange(newSize, "SCALE_DOWN");

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
     * 飛行中UAVと待機中UAVが両方0の場合にtrueを返し、RTopic で通知する
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
            // PhaseController に RTopic 経由で通知
            if (client != null && currentSessionId != null) {
                try {
                    RTopic topic = client.getTopic("flight:all_completed");
                    topic.publish(currentSessionId);
                } catch (Exception e) {
                    LogManager.getInstance().error("Phase 7-11: flight:all_completed 送信エラー", e);
                }
            }
        }

        return completed;
    }

    /**
     * Phase 7-11: 飛行状況を Redis Hash に書き込む
     * PhaseController が参照するステータス情報
     */
    private void updateFlightStatusToRedis() {
        if (client == null || currentSessionId == null) return;
        try {
            RMap<String, Integer> statusMap = client.getMap("flight:status:" + currentSessionId);
            statusMap.put("flying",      activeFlights.get());
            statusMap.put("waiting",     waitingManager.getTotalWaitingCount());
            statusMap.put("pathWaiting", pathWaitingManager != null ? pathWaitingManager.getTotalWaitingCount() : 0);
            statusMap.put("completed",   completedFlights.get());
        } catch (Exception e) {
            LogManager.getInstance().error("Phase 7-11: flight:status 更新エラー", e);
        }
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
     * Phase 12-Fix: クライアントの飛行中UAVをすべてキャンセル
     * SearcherRetryManagerからリトライ時に呼び出される
     *
     * このメソッドは以下を行う：
     * 1. 指定クライアントの飛行中UAVをすべてキャンセル状態にマーク
     * 2. activeClientUAVsマップから削除
     * 3. 使用中のリンク容量を回復
     *
     * @param clientId クライアントID
     * @return キャンセルされたUAV数
     */
    public int cancelClientFlights(int clientId) {
        if (clientId <= 0) {
            LogManager.getInstance().error("Phase 12-Fix: 無効なクライアントID=" + clientId);
            return 0;
        }

        int cancelledCount = 0;

        // クライアントの飛行中UAVマップを取得
        ConcurrentHashMap<Integer, UAVJob> clientUAVMap = activeClientUAVs.get(clientId);
        if (clientUAVMap == null || clientUAVMap.isEmpty()) {
            // 飛行中UAVがない場合は何もしない
            return 0;
        }

        // すべてのUAVをキャンセル
        for (UAVJob job : clientUAVMap.values()) {
            try {
                // キャンセル状態にマーク（volatile変数のためスレッドセーフ）
                job.setCancelled(true);
                cancelledCount++;

                // 現在使用中のリンク容量を回復
                int[] path = job.getPath();
                if (path != null && path.length >= 2) {
                    int currentIndex = job.getCurrentPathIndex();
                    if (currentIndex < path.length - 1) {
                        int fromNode = path[currentIndex];
                        int toNode = path[currentIndex + 1];

                        // リンク容量を回復（消費している場合のみ）
                        try {
                            capacityManager.recoverCapacity(fromNode, toNode);
                            LogManager.getInstance().log(
                                "Phase 12-Fix: client" + clientId + " UAV" + job.getUavId() +
                                " のリンク容量回復 (link " + fromNode + "-" + toNode + ")"
                            );
                        } catch (Exception capEx) {
                            LogManager.getInstance().error(
                                "Phase 12-Fix: client" + clientId + " UAV" + job.getUavId() +
                                " リンク容量回復失敗", capEx
                            );
                        }
                    }
                }

                LogManager.getInstance().log(
                    "Phase 12-Fix: client" + clientId + " UAV" + job.getUavId() +
                    " をキャンセルしました（経過飛行時間=" + String.format("%.2f", job.getElapsedFlightTime()) + "s）"
                );

            } catch (Exception e) {
                LogManager.getInstance().error(
                    "Phase 12-Fix: client" + clientId + " UAV" + job.getUavId() +
                    " のキャンセル処理中にエラー", e
                );
            }
        }

        // activeClientUAVsマップから削除
        activeClientUAVs.remove(clientId);

        // activeFlightsカウントを減少（キャンセルされたUAV分）
        // 注: onLinkPassed()でキャンセル検出時にも減少するが、ここで先に減少させる
        for (int i = 0; i < cancelledCount; i++) {
            activeFlights.decrementAndGet();
        }

        if (cancelledCount > 0) {
            LogManager.getInstance().log(
                "Phase 12-Fix: client" + clientId + " の飛行中UAVキャンセル完了 (UAV数=" + cancelledCount + ")"
            );
        }

        return cancelledCount;
    }

    /**
     * Phase C2: operator:cancel_flight トピックを購読してキャンセルリクエストを処理
     * SearcherRetryManager からの publish を受け取り cancelClientFlights() を呼ぶ
     */
    private void subscribeCancelRequests(RedissonClient redisClient) {
        RTopic cancelTopic = redisClient.getTopic("operator:cancel_flight");
        cancelTopic.addListener(Integer.class, (channel, clientId) -> {
            LogManager.getInstance().log(
                "Phase C2: operator:cancel_flight 受信 - client" + clientId + " のUAVをキャンセル"
            );
            cancelClientFlights(clientId);
        });
        LogManager.getInstance().log("Phase C2: operator:cancel_flight トピックを購読しました");
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
            int finalPoolSize = scheduler.getCorePoolSize();

            // スレッド数遷移ログに終了を記録
            LogManager.getInstance().logThreadCountChange(finalPoolSize, "SHUTDOWN");
            LogManager.getInstance().closeThreadCountLog();

            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
            LogManager.getInstance().log("Phase 3b-10: FlightScheduler shutdown (最終poolSize=" + finalPoolSize + ")");
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
