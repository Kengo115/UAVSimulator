package legacy.eventdriven;

import shared.item.UAVJob;
import shared.util.LinkStatusRecorder;
import shared.util.LogManager;
import shared.util.ResultOutputManager;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Option B (イベント駆動インメモリ方式) 飛行スケジューラ
 *
 * Redis の FlightScheduler を以下のインメモリ実装で置き換える:
 *   - LinkCapacityManager (Lua)  → MemoryCapacityManager
 *   - WaitingUAVManager (RDeque) → MemoryWaitingManager
 *   - PathWaitingManager (RDeque)→ MemoryPathWaitingManager
 *   - RTopic pub/sub             → MemoryCompletionTracker への直接コール
 *   - VizStateManager            → 除去（可視化なし）
 *   - Link117DebugLogger         → 除去（Redis デバッグ不要）
 *
 * 飛行タイミング制御は FlightScheduler と同じ ScheduledThreadPoolExecutor を使用する。
 *
 * 使用方法:
 *   1. MemoryFlightScheduler.init(capacityMgr, waitingMgr, pathWaitingMgr) を呼ぶ
 *   2. MemoryFlightScheduler.getInstance() で取得して使用する
 *   3. シミュレーション終了時に MemoryFlightScheduler.reset() を呼ぶ
 */
public class MemoryFlightScheduler {

    private static MemoryFlightScheduler instance;

    // インメモリ管理クラス
    private final MemoryCapacityManager capacityManager;
    private final MemoryWaitingManager waitingManager;
    private final MemoryPathWaitingManager pathWaitingManager;

    // スレッドプール
    private final ScheduledThreadPoolExecutor scheduler;

    // 統計情報
    private final AtomicInteger activeFlights    = new AtomicInteger(0);
    private final AtomicInteger completedFlights = new AtomicInteger(0);
    private final AtomicInteger linkPassedCount  = new AtomicInteger(0);

    // セッションID（null = 全ジョブ許可）
    private String currentSessionId;

    // オートスケーリング設定（FlightScheduler と同値）
    private static final int    MIN_POOL_SIZE           = 16;
    private static final int    MAX_POOL_SIZE           = 32;
    private static final int    SCALE_STEP              = 4;
    private static final double SCALE_UP_THRESHOLD      = 0.9;
    private static final double SCALE_DOWN_THRESHOLD    = 0.5;
    private static final int    SCALE_DOWN_DELAY_SECONDS = 30;
    private static final int    MONITOR_INTERVAL_MS     = 1000;

    private ScheduledExecutorService monitorExecutor;
    private final AtomicLong lowUsageStartTime = new AtomicLong(0);

    // 一時停止フラグ（デバッグ用）
    private final java.util.concurrent.atomic.AtomicBoolean isPaused =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    // ─────────────────────────────────────────────────────────────────
    // ライフサイクル
    // ─────────────────────────────────────────────────────────────────

    private MemoryFlightScheduler(
            MemoryCapacityManager capacityManager,
            MemoryWaitingManager waitingManager,
            MemoryPathWaitingManager pathWaitingManager) {
        this.capacityManager    = capacityManager;
        this.waitingManager     = waitingManager;
        this.pathWaitingManager = pathWaitingManager;

        this.scheduler = new ScheduledThreadPoolExecutor(MIN_POOL_SIZE);
        this.scheduler.setRemoveOnCancelPolicy(true);

        startAutoScalingMonitor();

        LogManager.getInstance().log(
            "[EventDrivenMode] MemoryFlightScheduler 初期化完了 (poolSize=" + MIN_POOL_SIZE + ")");
        System.out.println("[EventDrivenMode] MemoryFlightScheduler 初期化完了");
    }

    /**
     * 初期化。EventDrivenMemorySimulator.initialize() 内で一度だけ呼ぶ。
     */
    public static synchronized void init(
            MemoryCapacityManager capacityManager,
            MemoryWaitingManager waitingManager,
            MemoryPathWaitingManager pathWaitingManager) {
        if (instance != null) {
            instance.shutdownInternal();
        }
        instance = new MemoryFlightScheduler(capacityManager, waitingManager, pathWaitingManager);
    }

    /** シングルトン取得。init() 未呼び出しの場合は例外。 */
    public static synchronized MemoryFlightScheduler getInstance() {
        if (instance == null) {
            throw new IllegalStateException(
                "[EventDrivenMode] MemoryFlightScheduler.init() を先に呼んでください");
        }
        return instance;
    }

    /** インスタンスを破棄して再利用可能にする */
    public static synchronized void reset() {
        if (instance != null) {
            instance.shutdownInternal();
            instance = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 公開 API
    // ─────────────────────────────────────────────────────────────────

    /**
     * 飛行を開始する（非同期・即座に return）。
     * MemoryUAVWorker が容量確保後に呼び出す。
     */
    public void startFlight(UAVJob job) {
        int linkIndex = job.getCurrentPathIndex();
        int[] path    = job.getPath();
        int fromNode  = path[linkIndex];
        int toNode    = path[linkIndex + 1];

        // 待機中だった場合は待機終了を記録
        if (job.getWaitingStartTime() > 0) {
            LinkStatusRecorder.getInstance().onWaitingEnd(fromNode, toNode);
        }
        job.endWaiting();

        // 最初のホップなら飛行開始マーク + CompletionTracker に通知
        if (linkIndex == 0 && !job.hasStartedFlight()) {
            job.markFlightStarted();
            MemoryCompletionTracker.getInstance().onFlightStarted();
        }

        job.setCurrentLinkStartTime(System.currentTimeMillis());
        activeFlights.incrementAndGet();

        LinkStatusRecorder.getInstance().onLinkEnter(fromNode, toNode);

        LogManager.getInstance().log(
            "[EventDrivenMode] client" + job.getClientId() + " UAV" + job.getUavId()
            + " 飛行開始 (path=" + formatPath(job.getPath()) + ", linkIndex=" + linkIndex + ")");
        System.out.printf("[EventDrivenMode] client%d UAV%d 飛行開始 (linkIndex=%d)%n",
            job.getClientId(), job.getUavId(), linkIndex);

        scheduleNextLink(job, linkIndex);
    }

    public void setSessionId(String sessionId) {
        this.currentSessionId = sessionId;
    }

    public String getSessionId() {
        return currentSessionId;
    }

    public MemoryPathWaitingManager getPathWaitingManager() {
        return pathWaitingManager;
    }

    // カウンタ取得
    public int getActiveFlights()    { return activeFlights.get(); }
    public int getCompletedFlights() { return completedFlights.get(); }
    public int getLinkPassedCount()  { return linkPassedCount.get(); }
    public int getCurrentPoolSize()  { return scheduler.getCorePoolSize(); }
    public int getActiveThreadCount(){ return scheduler.getActiveCount(); }

    public double getPoolUsage() {
        int poolSize = scheduler.getCorePoolSize();
        return poolSize > 0 ? (double) scheduler.getActiveCount() / poolSize : 0.0;
    }

    public void resetCounters() {
        activeFlights.set(0);
        completedFlights.set(0);
        linkPassedCount.set(0);
    }

    /**
     * 全完了判定（飛行中・待機中・経路待ち UAV がすべて 0 の場合 true）
     */
    public boolean isAllFlightsCompleted() {
        return activeFlights.get() == 0
            && waitingManager.getTotalWaitingCount() == 0
            && pathWaitingManager.getTotalWaitingCount() == 0;
    }

    // ─────────────────────────────────────────────────────────────────
    // private: 飛行制御
    // ─────────────────────────────────────────────────────────────────

    private void scheduleNextLink(UAVJob job, int linkIndex) {
        double distance       = job.getLinkDistance(linkIndex);
        double speed          = job.getSpeed();
        double flightTimeSec  = distance / speed;
        long   flightTimeMs   = (long)(flightTimeSec * 1000);

        int[] path      = job.getPath();
        int fromNode    = path[linkIndex];
        int toNode      = path[linkIndex + 1];

        LogManager.getInstance().log(
            "[EventDrivenMode] client" + job.getClientId() + " UAV" + job.getUavId()
            + " リンク " + fromNode + "→" + toNode
            + " スケジュール (" + String.format("%.2f", distance) + "m, "
            + String.format("%.2f", flightTimeSec) + "s後)");

        scheduler.schedule(
            () -> onLinkPassed(job, linkIndex),
            flightTimeMs,
            TimeUnit.MILLISECONDS
        );
    }

    private void onLinkPassed(UAVJob job, int linkIndex) {
        if (job.isCancelled()) {
            activeFlights.decrementAndGet();
            return;
        }
        if (isPaused.get()) {
            scheduler.schedule(() -> onLinkPassed(job, linkIndex), 200, TimeUnit.MILLISECONDS);
            return;
        }

        int[] path     = job.getPath();
        int fromNode   = path[linkIndex];
        int toNode     = path[linkIndex + 1];

        LinkStatusRecorder.getInstance().onLinkExit(fromNode, toNode);

        // 経過時間を更新
        double linkDistance = job.getLinkDistance(linkIndex);
        double linkTime     = linkDistance / job.getSpeed();
        job.addElapsedFlightTime(linkTime);

        int count = linkPassedCount.incrementAndGet();

        LogManager.getInstance().log(
            "[EventDrivenMode] client" + job.getClientId() + " UAV" + job.getUavId()
            + " リンク通過 " + fromNode + "→" + toNode
            + " (経過=" + String.format("%.2f", job.getElapsedFlightTime()) + "s, 総通過数=" + count + ")");

        // 第一ホップ通過時: 経路待ち UAV へ経路コピー
        if (linkIndex == 0) {
            processFirstHopPathCopy(job);
        }

        // 待機 UAV チェック（MemoryWaitingManager は正規化キーを使用）
        boolean hasWaiting = waitingManager.hasWaitingUAV(fromNode, toNode);
        if (hasWaiting) {
            UAVJob waitingJob = waitingManager.dequeue(fromNode, toNode);
            if (waitingJob != null) {
                startFlight(waitingJob);
                LogManager.getInstance().log(
                    "[EventDrivenMode] 待機 client" + waitingJob.getClientId()
                    + " UAV" + waitingJob.getUavId()
                    + " を直接飛行開始（容量変化なし）(link " + fromNode + "-" + toNode + ")");
            }
        } else {
            capacityManager.recoverCapacity(fromNode, toNode);
        }

        // 最終リンク判定
        if (linkIndex >= path.length - 2) {
            onFlightCompleted(job);
            return;
        }

        // 次リンクの容量チェック
        int nextFrom = path[linkIndex + 1];
        int nextTo   = path[linkIndex + 2];

        if (!capacityManager.tryConsumeCapacity(nextFrom, nextTo)) {
            onMidFlightWaiting(job, linkIndex + 1, nextFrom, nextTo);
            return;
        }

        // 次リンク進入を記録
        LinkStatusRecorder.getInstance().onLinkEnter(nextFrom, nextTo);
        job.setCurrentLinkStartTime(System.currentTimeMillis());

        scheduleNextLink(job, linkIndex + 1);
    }

    private void onMidFlightWaiting(UAVJob job, int waitingLinkIndex, int fromNode, int toNode) {
        job.setCurrentPathIndex(waitingLinkIndex);
        job.startWaiting();
        LinkStatusRecorder.getInstance().onWaitingStart(fromNode, toNode);
        waitingManager.enqueue(fromNode, toNode, job);

        int waitingCount = waitingManager.getWaitingCount(fromNode, toNode);
        activeFlights.decrementAndGet();

        LogManager.getInstance().log(
            "[EventDrivenMode] client" + job.getClientId() + " UAV" + job.getUavId()
            + " 途中待機 (link " + fromNode + "→" + toNode
            + ", linkIndex=" + waitingLinkIndex + ", 待機数=" + waitingCount + ")");
        System.out.printf("[EventDrivenMode] client%d UAV%d 途中待機 (link %d→%d, 待機数=%d)%n",
            job.getClientId(), job.getUavId(), fromNode, toNode, waitingCount);
    }

    private void onFlightCompleted(UAVJob job) {
        if (!job.markCompleted()) {
            LogManager.getInstance().log(
                "[EventDrivenMode] client" + job.getClientId() + " UAV" + job.getUavId()
                + " の二重完了を検出しスキップしました");
            return;
        }

        activeFlights.decrementAndGet();
        int completed = completedFlights.incrementAndGet();

        double realFlightTime    = job.getElapsedFlightTime();
        double flightStayingTime = job.getFlightStayingTime();
        double waitingTime       = job.getTotalWaitingTime();
        double totalTime         = job.getTotalTime();

        LogManager.getInstance().log(
            "[EventDrivenMode] client" + job.getClientId() + " UAV" + job.getUavId()
            + " 飛行完了 (総距離=" + String.format("%.2f", job.getTotalDistance()) + "m"
            + ", 飛行時間=" + String.format("%.2f", realFlightTime) + "s"
            + ", 飛行前待機=" + String.format("%.2f", flightStayingTime) + "s"
            + ", 飛行中待機=" + String.format("%.2f", waitingTime) + "s"
            + ", 合計=" + String.format("%.2f", totalTime) + "s"
            + ", 総完了数=" + completed + ")");
        System.out.printf("[EventDrivenMode] client%d UAV%d 到着完了 (飛行=%.2fs, 合計=%.2fs, 完了=%d)%n",
            job.getClientId(), job.getUavId(), realFlightTime, totalTime, completed);

        // 結果ファイルへ出力
        try {
            int runCounter = job.getClientId() - 1;
            ResultOutputManager.outputFlightTimeResult(job, runCounter);
        } catch (IOException e) {
            LogManager.getInstance().error("[EventDrivenMode] 結果ファイル出力エラー", e);
        }

        // 完了通知（Redis pub/sub の代替: MemoryCompletionTracker への直接通知）
        MemoryCompletionTracker.getInstance().onUAVCompleted(job.getUavId(), job.getClientId());
    }

    /**
     * 第一ホップ通過時に経路待ち UAV へ経路コピー
     * FlightScheduler.processFirstHopPathCopy() に相当
     */
    private void processFirstHopPathCopy(UAVJob job) {
        int clientId = job.getClientId();
        if (!pathWaitingManager.hasWaitingUAV(clientId)) return;

        UAVJob waitingJob = pathWaitingManager.dequeue(clientId);
        if (waitingJob == null) return;

        waitingJob.endPathWaiting();

        int[] copiedPath = job.getPath().clone();
        waitingJob.setPath(copiedPath);

        double[] originalDistances = job.getLinkDistances();
        if (originalDistances != null) {
            waitingJob.setLinkDistances(originalDistances.clone());
        }

        waitingJob.setCurrentPathIndex(0);
        waitingJob.setSessionId(job.getSessionId());

        // route.csv に記録
        try {
            ResultOutputManager.outputRouteAssignment(waitingJob, waitingJob.getClientId() - 1);
        } catch (IOException e) {
            LogManager.getInstance().error(
                "[EventDrivenMode] route.csv 書き込みエラー (client" + clientId
                + " UAV" + waitingJob.getUavId() + ")", e);
        }

        MemoryJobQueue.getInstance().enqueueJob(waitingJob);

        LogManager.getInstance().log(
            "[EventDrivenMode] [経路待ち→飛行]: client" + clientId + " UAV" + waitingJob.getUavId()
            + " が経路待ちから飛行開始 (経路コピー元=UAV" + job.getUavId()
            + ", 残り経路待ち=" + pathWaitingManager.getWaitingCount(clientId) + ")");
    }

    private static String formatPath(int[] path) {
        if (path == null || path.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < path.length; i++) {
            if (i > 0) sb.append("→");
            sb.append(path[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────
    // オートスケーリング（FlightScheduler と同じロジック）
    // ─────────────────────────────────────────────────────────────────

    private void startAutoScalingMonitor() {
        monitorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MemoryFlightScheduler-AutoScaler");
            t.setDaemon(true);
            return t;
        });
        monitorExecutor.scheduleAtFixedRate(
            this::checkAndScale,
            MONITOR_INTERVAL_MS,
            MONITOR_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
    }

    private void checkAndScale() {
        try {
            int currentPoolSize = scheduler.getCorePoolSize();
            int activeCount     = scheduler.getActiveCount();
            double usage        = (double) activeCount / currentPoolSize;

            if (usage >= SCALE_UP_THRESHOLD && currentPoolSize < MAX_POOL_SIZE) {
                int newSize = Math.min(currentPoolSize + SCALE_STEP, MAX_POOL_SIZE);
                scheduler.setCorePoolSize(newSize);
                lowUsageStartTime.set(0);
                LogManager.getInstance().log(
                    "[EventDrivenMode] スケールアップ " + currentPoolSize + " → " + newSize
                    + " (使用率=" + String.format("%.1f", usage * 100) + "%)");
                return;
            }

            if (usage < SCALE_DOWN_THRESHOLD && currentPoolSize > MIN_POOL_SIZE) {
                long now           = System.currentTimeMillis();
                long lowUsageStart = lowUsageStartTime.get();
                if (lowUsageStart == 0) {
                    lowUsageStartTime.set(now);
                } else if (now - lowUsageStart >= SCALE_DOWN_DELAY_SECONDS * 1000L) {
                    int newSize = Math.max(currentPoolSize - SCALE_STEP, MIN_POOL_SIZE);
                    scheduler.setCorePoolSize(newSize);
                    lowUsageStartTime.set(0);
                    LogManager.getInstance().log(
                        "[EventDrivenMode] スケールダウン " + currentPoolSize + " → " + newSize
                        + " (使用率=" + String.format("%.1f", usage * 100) + "%)");
                }
            } else {
                lowUsageStartTime.set(0);
            }
        } catch (Exception e) {
            LogManager.getInstance().error("[EventDrivenMode] オートスケーリングエラー", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // シャットダウン
    // ─────────────────────────────────────────────────────────────────

    public void shutdown() {
        shutdownInternal();
    }

    private void shutdownInternal() {
        if (monitorExecutor != null) {
            monitorExecutor.shutdownNow();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        LogManager.getInstance().log("[EventDrivenMode] MemoryFlightScheduler シャットダウン完了");
        System.out.println("[EventDrivenMode] MemoryFlightScheduler シャットダウン完了");
    }
}
