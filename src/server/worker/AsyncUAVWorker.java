package server.worker;

import server.redis.*;
import server.scheduler.FlightScheduler;
import server.util.LogManager;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 非同期UAVワーカー
 * Phase 3b-3: イベントスケジューリング方式の非同期Worker
 *
 * ジョブを取得し、容量チェック後にFlightSchedulerに委譲する
 * 飛行処理自体はブロックせず、即座に次のジョブを取得可能
 */
public class AsyncUAVWorker {

    private String workerId;
    private UAVJobQueue jobQueue;
    private LinkCapacityManager capacityManager;
    private WaitingUAVManager waitingManager;
    private FlightScheduler flightScheduler;

    private AtomicBoolean running = new AtomicBoolean(true);
    private AtomicInteger processedJobs = new AtomicInteger(0);

    // ジョブ取得タイムアウト（秒）
    private static final int DEQUEUE_TIMEOUT_SECONDS = 1;

    /**
     * コンストラクタ
     *
     * @param workerId ワーカーID
     */
    public AsyncUAVWorker(String workerId) {
        this.workerId = workerId;

        try {
            RedisConnectionManager connectionManager = RedisConnectionManager.getInstance();
            if (connectionManager.isConnected()) {
                this.jobQueue = new UAVJobQueue();
                this.capacityManager = new LinkCapacityManager();
                this.waitingManager = new WaitingUAVManager();
                this.flightScheduler = FlightScheduler.getInstance();

                LogManager.getInstance().log("Phase 3b-3: AsyncUAVWorker " + workerId + " initialized");
            } else {
                throw new RuntimeException("Redis connection required");
            }
        } catch (Exception e) {
            LogManager.getInstance().error("AsyncUAVWorker " + workerId + " initialization failed", e);
            throw new RuntimeException("AsyncUAVWorker initialization failed", e);
        }
    }

    /**
     * テスト用コンストラクタ（依存性注入）
     */
    public AsyncUAVWorker(String workerId, UAVJobQueue jobQueue, LinkCapacityManager capacityManager,
                          WaitingUAVManager waitingManager, FlightScheduler flightScheduler) {
        this.workerId = workerId;
        this.jobQueue = jobQueue;
        this.capacityManager = capacityManager;
        this.waitingManager = waitingManager;
        this.flightScheduler = flightScheduler;

        LogManager.getInstance().log("Phase 3b-3: AsyncUAVWorker " + workerId + " initialized (test mode)");
    }

    /**
     * メインループを開始
     */
    public void start() {
        LogManager.getInstance().log("Phase 3b-3: AsyncUAVWorker " + workerId + " started");

        while (running.get()) {
            try {
                // ジョブを取得（ブロッキング、最大1秒待機）
                UAVJob job = jobQueue.dequeueJob(DEQUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                if (job != null) {
                    processJob(job);
                }
                // タイムアウト時は次のループへ

            } catch (InterruptedException e) {
                LogManager.getInstance().log("Phase 3b-3: AsyncUAVWorker " + workerId + " interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LogManager.getInstance().error("AsyncUAVWorker " + workerId + " error", e);
                // エラーでもワーカーは継続
            }
        }

        LogManager.getInstance().log("Phase 3b-3: AsyncUAVWorker " + workerId + " stopped");
    }

    /**
     * ジョブを処理（非同期・即座にreturn）
     *
     * @param job UAVジョブ
     */
    private void processJob(UAVJob job) {
        // Phase 3b-8: セッションID検証
        if (!flightScheduler.isValidSession(job)) {
            LogManager.getInstance().log(
                "Phase 3b-8: Worker " + workerId + " 別セッションのジョブをスキップ (UAV " + job.getUavId() + ")"
            );
            return;
        }

        int[] path = job.getPath();
        int startIndex = job.getCurrentPathIndex();
        int fromNode = path[startIndex];
        int toNode = path[startIndex + 1];

        LogManager.getInstance().log(
            "Phase 3b-3: Worker " + workerId + " ジョブ取得 UAV " + job.getUavId() +
            " (linkIndex=" + startIndex + ", link=" + fromNode + "→" + toNode + ")"
        );

        // 容量チェック + 消費
        boolean acquired = capacityManager.tryConsumeCapacity(fromNode, toNode);

        if (!acquired) {
            // 容量不足 → 待機キューへ
            // Phase 3b-9: 待機開始時刻を記録
            job.startWaiting();
            waitingManager.enqueue(fromNode, toNode, job);
            LogManager.getInstance().log(
                "Phase 3b-3: Worker " + workerId + " UAV " + job.getUavId() +
                " 容量不足、待機 (link=" + fromNode + "→" + toNode + ")"
            );
            return;
        }

        // 飛行開始（FlightSchedulerに委譲、即座にreturn）
        flightScheduler.startFlight(job);

        processedJobs.incrementAndGet();

        // ← ここで即座にreturn、次のジョブを取得可能
    }

    /**
     * ワーカーを停止
     */
    public void stop() {
        running.set(false);
        LogManager.getInstance().log("Phase 3b-3: AsyncUAVWorker " + workerId + " stop requested");
    }

    /**
     * 処理したジョブ数を取得
     */
    public int getProcessedJobs() {
        return processedJobs.get();
    }

    /**
     * ワーカーIDを取得
     */
    public String getWorkerId() {
        return workerId;
    }

    /**
     * 実行中かどうか
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * メインメソッド（別プロセスとして起動時）
     */
    public static void main(String[] args) {
        // ワーカーIDを取得
        String workerId = System.getenv("WORKER_ID");
        if (workerId == null || workerId.isEmpty()) {
            workerId = args.length > 0 ? args[0] : "async-worker-1";
        }

        LogManager.getInstance().log("Phase 3b-3: Starting AsyncUAVWorker " + workerId);

        try {
            // Redis接続
            RedisConnectionManager.getInstance().connect();

            final AsyncUAVWorker worker = new AsyncUAVWorker(workerId);

            // シャットダウンフック
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LogManager.getInstance().log("Phase 3b-3: Shutdown hook - stopping " + worker.getWorkerId());
                worker.stop();
                FlightScheduler.getInstance().shutdown();
                RedisConnectionManager.getInstance().disconnect();
            }));

            // ワーカー開始
            worker.start();

        } catch (Exception e) {
            LogManager.getInstance().error("AsyncUAVWorker startup failed", e);
            System.exit(1);
        }
    }
}
