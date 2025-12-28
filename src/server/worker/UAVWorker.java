package server.worker;

import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.Redisson;
import server.redis.RedisConnectionManager;
import server.redis.UAVJob;
import server.redis.UAVJobQueue;
import server.util.LogManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UAVワーカープロセス
 * Phase 3b-1: 基本機能（ジョブ取得、タイマー処理、ログ出力）
 *
 * 各ワーカーは独立したJVMプロセスとして動作し、
 * Redisジョブキューからジョブを取得してUAVを処理する
 */
public class UAVWorker {

    private UAVJobQueue jobQueue;
    private RedissonClient redisson;
    private String workerId;
    private AtomicBoolean running = new AtomicBoolean(true);

    private static final int UPDATE_INTERVAL_SECONDS = 2;  // 2秒間隔で更新

    /**
     * コンストラクタ
     *
     * @param workerId ワーカーID
     */
    public UAVWorker(String workerId) {
        this.workerId = workerId;

        try {
            // Redis接続
            RedisConnectionManager connectionManager = RedisConnectionManager.getInstance();
            if (connectionManager.isConnected()) {
                this.redisson = connectionManager.getClient();
                this.jobQueue = new UAVJobQueue();
                LogManager.getInstance().log("Phase 3b: UAV Worker " + workerId + " initialized");
            } else {
                LogManager.getInstance().log("Phase 3b: Redis未接続のため、Worker " + workerId + " は起動できません");
                throw new RuntimeException("Redis connection failed");
            }
        } catch (Exception e) {
            LogManager.getInstance().error("Worker " + workerId + " 初期化エラー", e);
            throw new RuntimeException("Worker initialization failed", e);
        }
    }

    /**
     * メインループを開始する
     * ジョブキューからジョブを取得し、処理する
     */
    public void start() {
        LogManager.getInstance().log("Phase 3b: UAV Worker " + workerId + " started");

        while (running.get()) {
            try {
                // ジョブを取得（ブロッキング、最大5秒待機）
                UAVJob job = jobQueue.dequeueJob(5, TimeUnit.SECONDS);

                if (job != null) {
                    LogManager.getInstance().log("Phase 3b: Worker " + workerId + " - ジョブ取得: UAV " + job.getUavId());
                    processUAVJob(job);
                } else {
                    // タイムアウト（ジョブなし）
                    // ログは出さずに次のループへ
                }

            } catch (InterruptedException e) {
                LogManager.getInstance().log("Phase 3b: Worker " + workerId + " - 割り込みを受信しました");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LogManager.getInstance().error("Worker " + workerId + " - ジョブ処理中にエラーが発生しました", e);
                // エラーが発生してもワーカーは継続
            }
        }

        LogManager.getInstance().log("Phase 3b: UAV Worker " + workerId + " stopped");
    }

    /**
     * UAVジョブを処理する
     * Phase 3b-1: 基本機能（タイマーとログ出力のみ）
     *
     * @param job UAVジョブ
     */
    private void processUAVJob(UAVJob job) {
        LogManager.getInstance().log(
            "Phase 3b: Worker " + workerId + " - UAV " + job.getUavId() + " 処理開始 " +
            "(client=" + job.getClientId() + ", " +
            "source=" + job.getSourceBeaconId() + ", " +
            "dest=" + job.getDestinationBeaconId() + ")"
        );

        // Phase 3b-1: シンプルな距離計算
        double totalDistance = calculateTotalDistance(job);
        double theoreticalFlightTime = totalDistance / job.getSpeed();

        LogManager.getInstance().log(
            "Phase 3b: Worker " + workerId + " - UAV " + job.getUavId() + " " +
            "distance=" + String.format("%.2f", totalDistance) + "m, " +
            "speed=" + String.format("%.2f", job.getSpeed()) + "m/s, " +
            "theoretical time=" + String.format("%.2f", theoreticalFlightTime) + "s"
        );

        // タイマーを起動（2秒間隔で位置更新）
        final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                // 経過時間を計算
                long elapsedTimeMs = System.currentTimeMillis() - job.getStartTime();
                double elapsedTimeSec = elapsedTimeMs / 1000.0;

                // 飛行距離を計算
                double flightDistance = elapsedTimeSec * job.getSpeed();

                LogManager.getInstance().log(
                    "Phase 3b: Worker " + workerId + " - UAV " + job.getUavId() + " " +
                    "elapsed=" + String.format("%.1f", elapsedTimeSec) + "s, " +
                    "distance=" + String.format("%.2f", flightDistance) + "m"
                );

                // Phase 3b-1: シンプルな到着判定
                if (flightDistance >= totalDistance) {
                    LogManager.getInstance().log(
                        "Phase 3b: Worker " + workerId + " - UAV " + job.getUavId() + " 目的地に到着"
                    );

                    // タイマーを停止
                    scheduler.shutdown();

                    // Phase 3b-2で完了通知（Pub/Sub）を実装予定
                    LogManager.getInstance().log(
                        "Phase 3b: Worker " + workerId + " - UAV " + job.getUavId() + " 処理完了"
                    );
                }

            } catch (Exception e) {
                LogManager.getInstance().error("Worker " + workerId + " - タイマー処理エラー", e);
                scheduler.shutdown();
            }
        }, 0, UPDATE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Phase 3b-1: シンプルな距離計算
     * 経路の各セグメント距離を仮定して計算
     *
     * @param job UAVジョブ
     * @return 総距離（メートル）
     */
    private double calculateTotalDistance(UAVJob job) {
        // Phase 3b-1: 仮の実装
        // 各リンクの距離を100mと仮定
        int[] path = job.getPath();
        if (path == null || path.length < 2) {
            return 0.0;
        }

        // セグメント数 = ノード数 - 1
        int segmentCount = path.length - 1;

        // 仮の距離（1セグメント = 100m）
        double distancePerSegment = 100.0;

        return segmentCount * distancePerSegment;
    }

    /**
     * ワーカーを停止する
     */
    public void stop() {
        running.set(false);
        LogManager.getInstance().log("Phase 3b: Worker " + workerId + " - 停止要求を受信しました");
    }

    /**
     * メインメソッド（別JVMプロセスとして起動）
     *
     * @param args コマンドライン引数（ワーカーID）
     */
    public static void main(String[] args) {
        // ワーカーIDを取得（環境変数またはコマンドライン引数）
        final String workerId;
        String envWorkerId = System.getenv("WORKER_ID");
        if (envWorkerId == null || envWorkerId.isEmpty()) {
            workerId = args.length > 0 ? args[0] : "worker-1";
        } else {
            workerId = envWorkerId;
        }

        LogManager.getInstance().log("Phase 3b: Starting UAV Worker " + workerId);

        try {
            final UAVWorker worker = new UAVWorker(workerId);

            // シャットダウンフック（Ctrl+Cなどで停止時）
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LogManager.getInstance().log("Phase 3b: Shutdown hook - stopping worker " + workerId);
                worker.stop();
            }));

            // ワーカー開始
            worker.start();

        } catch (Exception e) {
            LogManager.getInstance().error("Worker " + workerId + " 起動エラー", e);
            System.exit(1);
        }
    }
}
