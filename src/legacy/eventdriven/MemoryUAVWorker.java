package legacy.eventdriven;

import shared.item.UAVJob;
import shared.util.LogManager;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Option B (イベント駆動インメモリ方式) UAV ワーカー
 *
 * Redis の AsyncUAVWorker を MemoryJobQueue / MemoryCapacityManager /
 * MemoryWaitingManager / MemoryFlightScheduler で置き換える。
 *
 * MemoryJobQueue からジョブを取得し、容量チェック後に
 * MemoryFlightScheduler.startFlight() に委譲する。
 * 飛行処理はノンブロッキング（即座に次のジョブを取得可能）。
 */
public class MemoryUAVWorker {

    private final String workerId;
    private final MemoryCapacityManager capacityManager;
    private final MemoryWaitingManager waitingManager;

    private final AtomicBoolean running       = new AtomicBoolean(true);
    private final AtomicInteger processedJobs = new AtomicInteger(0);

    /** MemoryJobQueue.dequeueJob() のタイムアウト（秒）*/
    private static final int DEQUEUE_TIMEOUT_SECONDS = 1;

    /**
     * コンストラクタ
     *
     * @param workerId         ワーカー識別子（ログ用）
     * @param capacityManager  MemoryCapacityManager（tryConsumeCapacity に使用）
     * @param waitingManager   MemoryWaitingManager（容量不足時のキューイング）
     */
    public MemoryUAVWorker(
            String workerId,
            MemoryCapacityManager capacityManager,
            MemoryWaitingManager waitingManager) {
        this.workerId        = workerId;
        this.capacityManager = capacityManager;
        this.waitingManager  = waitingManager;
    }

    // ─────────────────────────────────────────────────────────────────
    // メインループ
    // ─────────────────────────────────────────────────────────────────

    /**
     * ワーカーのメインループを開始する（ブロッキング）。
     * 別スレッドで実行すること。
     */
    public void start() {
        LogManager.getInstance().log("[EventDrivenMode] Worker " + workerId + " 開始");
        System.out.println("[EventDrivenMode] Worker " + workerId + " 開始");

        while (running.get()) {
            try {
                UAVJob job = MemoryJobQueue.getInstance()
                    .dequeueJob(DEQUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                if (job != null) {
                    processJob(job);
                }
                // タイムアウト時は次のループへ

            } catch (InterruptedException e) {
                LogManager.getInstance().log(
                    "[EventDrivenMode] Worker " + workerId + " interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LogManager.getInstance().error(
                    "[EventDrivenMode] Worker " + workerId + " error", e);
                // エラーでもワーカーは継続
            }
        }

        LogManager.getInstance().log(
            "[EventDrivenMode] Worker " + workerId + " 停止 (処理済み=" + processedJobs.get() + "件)");
        System.out.println("[EventDrivenMode] Worker " + workerId + " 停止");
    }

    // ─────────────────────────────────────────────────────────────────
    // ジョブ処理
    // ─────────────────────────────────────────────────────────────────

    /**
     * ジョブを処理する（ノンブロッキング、即座に return）。
     * AsyncUAVWorker.processJob() に相当。
     */
    private void processJob(UAVJob job) {
        int[] path       = job.getPath();
        int startIndex   = job.getCurrentPathIndex();
        int fromNode     = path[startIndex];
        int toNode       = path[startIndex + 1];

        LogManager.getInstance().log(
            "[EventDrivenMode] Worker " + workerId + " ジョブ取得 client"
            + job.getClientId() + " UAV" + job.getUavId()
            + " (linkIndex=" + startIndex + ", link=" + fromNode + "→" + toNode + ")");

        // 容量チェック + 消費
        boolean acquired = capacityManager.tryConsumeCapacity(fromNode, toNode);

        if (!acquired) {
            // 容量不足 → 待機キューへ登録
            job.startWaiting();
            waitingManager.enqueue(fromNode, toNode, job);
            LogManager.getInstance().log(
                "[EventDrivenMode] Worker " + workerId + " client" + job.getClientId()
                + " UAV" + job.getUavId() + " 容量不足、待機 (link=" + fromNode + "→" + toNode + ")");
            System.out.printf("[EventDrivenMode] Worker %s: client%d UAV%d 容量不足 (%d→%d)%n",
                workerId, job.getClientId(), job.getUavId(), fromNode, toNode);
            return;
        }

        // 飛行開始（MemoryFlightScheduler に委譲、即座に return）
        MemoryFlightScheduler.getInstance().startFlight(job);
        processedJobs.incrementAndGet();
    }

    // ─────────────────────────────────────────────────────────────────
    // 制御
    // ─────────────────────────────────────────────────────────────────

    /** ワーカーを停止する */
    public void stop() {
        running.set(false);
        LogManager.getInstance().log(
            "[EventDrivenMode] Worker " + workerId + " 停止要求");
    }

    public boolean isRunning()       { return running.get(); }
    public int getProcessedJobs()    { return processedJobs.get(); }
    public String getWorkerId()      { return workerId; }
}
