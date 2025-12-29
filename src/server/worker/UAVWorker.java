package server.worker;

import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.Redisson;
import server.redis.RedisConnectionManager;
import server.redis.UAVCompletionEvent;
import server.redis.UAVEventChannels;
import server.redis.UAVJob;
import server.redis.UAVJobQueue;
import server.redis.UAVLinkPassedEvent;
import server.util.LogManager;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UAVワーカープロセス
 * Phase 3b-2c: 複数リンク飛行処理とリンク通過イベント送信
 *
 * 各ワーカーは独立したJVMプロセスとして動作し、
 * Redisジョブキューからジョブを取得してUAVを処理する
 */
public class UAVWorker {

    private UAVJobQueue jobQueue;
    private RedissonClient redisson;
    private RTopic completionTopic;   // 完了イベント送信用
    private RTopic linkPassedTopic;   // Phase 3b-2c: リンク通過イベント送信用
    private String workerId;
    private AtomicBoolean running = new AtomicBoolean(true);

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
                // イベント送信用トピック
                this.completionTopic = redisson.getTopic(UAVEventChannels.COMPLETION);
                this.linkPassedTopic = redisson.getTopic(UAVEventChannels.LINK_PASSED);
                LogManager.getInstance().log("Phase 3b-2c: UAV Worker " + workerId + " initialized");
            } else {
                LogManager.getInstance().log("Phase 3b-2c: Redis未接続のため、Worker " + workerId + " は起動できません");
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
     * Phase 3b-2c: 複数リンク飛行処理とリンク通過イベント送信
     *
     * @param job UAVジョブ
     */
    private void processUAVJob(UAVJob job) {
        int[] path = job.getPath();
        int sourceNode = path[0];
        int destNode = path[path.length - 1];

        LogManager.getInstance().log(
            "Phase 3b-2c: Worker " + workerId + " - UAV " + job.getUavId() +
            " 飛行開始 (" + sourceNode + "→" + destNode + ", " + (path.length - 1) + "リンク)"
        );

        // 総飛行距離を取得
        double totalDistance = job.getTotalDistance();

        // リンク距離が設定されていない場合は仮の値を使用（後方互換性）
        if (totalDistance <= 0) {
            totalDistance = (path.length - 1) * 100.0;  // 仮: 1リンク100m
            LogManager.getInstance().log(
                "Phase 3b-2c: Worker " + workerId + " - UAV " + job.getUavId() +
                " リンク距離未設定のため仮の値を使用: " + totalDistance + "m"
            );
        }

        // リンクごとに飛行処理
        double elapsedFlightTime = 0.0;
        int currentLinkIndex = 0;

        try {
            while (currentLinkIndex < path.length - 1) {
                int fromNode = path[currentLinkIndex];
                int toNode = path[currentLinkIndex + 1];

                // このリンクの距離を取得
                double linkDistance = job.getLinkDistance(currentLinkIndex);
                if (linkDistance <= 0) {
                    linkDistance = 100.0;  // 仮の値
                }

                // 飛行時間を計算
                double linkFlightTime = linkDistance / job.getSpeed();

                LogManager.getInstance().log(
                    "Phase 3b-2c: Worker " + workerId + " - UAV " + job.getUavId() +
                    " 飛行中 " + fromNode + "→" + toNode +
                    " (" + String.format("%.1f", linkDistance) + "m, " +
                    String.format("%.2f", linkFlightTime) + "s)"
                );

                // このリンクを飛行（Thread.sleepで待機）
                long flightTimeMs = (long) (linkFlightTime * 1000);
                Thread.sleep(flightTimeMs);

                elapsedFlightTime += linkFlightTime;

                // 次のリンク情報を計算
                int nextFromNode = -1;
                int nextToNode = -1;
                if (currentLinkIndex + 1 < path.length - 1) {
                    nextFromNode = path[currentLinkIndex + 1];
                    nextToNode = path[currentLinkIndex + 2];
                }

                // リンク通過イベントを送信
                publishLinkPassedEvent(job, fromNode, toNode, nextFromNode, nextToNode,
                                       currentLinkIndex, elapsedFlightTime);

                currentLinkIndex++;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LogManager.getInstance().error("Worker " + workerId + " - 飛行中に割り込み", e);
            return;
        }

        // 飛行完了
        LogManager.getInstance().log(
            "Phase 3b-2c: Worker " + workerId + " - UAV " + job.getUavId() + " 飛行完了" +
            " (総距離=" + String.format("%.1f", totalDistance) + "m, " +
            "総時間=" + String.format("%.2f", elapsedFlightTime) + "s)"
        );

        // 完了イベントを送信
        publishCompletionEvent(job, totalDistance, elapsedFlightTime);

        LogManager.getInstance().log(
            "Phase 3b-2c: Worker " + workerId + " - UAV " + job.getUavId() + " 処理完了"
        );
    }

    /**
     * リンク通過イベントをPub/Subで送信
     * Phase 3b-2c: メインプロセスにリンク通過を通知
     *
     * @param job ジョブ
     * @param passedFromNode 通過したリンクの始点
     * @param passedToNode 通過したリンクの終点
     * @param nextFromNode 次のリンクの始点（-1 = なし）
     * @param nextToNode 次のリンクの終点（-1 = なし）
     * @param currentLinkIndex 通過したリンクのインデックス
     * @param elapsedFlightTime 経過飛行時間
     */
    private void publishLinkPassedEvent(UAVJob job, int passedFromNode, int passedToNode,
                                        int nextFromNode, int nextToNode,
                                        int currentLinkIndex, double elapsedFlightTime) {
        UAVLinkPassedEvent event = new UAVLinkPassedEvent(
            job.getUavId(),
            job.getClientId(),
            passedFromNode,
            passedToNode,
            nextFromNode,
            nextToNode,
            job.getPath(),
            currentLinkIndex,
            elapsedFlightTime
        );

        long listeners = linkPassedTopic.publish(event);
        LogManager.getInstance().log(
            "Phase 3b-2c: Worker " + workerId + " - UAV " + job.getUavId() +
            " リンク通過イベント送信 " + passedFromNode + "→" + passedToNode +
            " (listeners=" + listeners + ")"
        );
    }

    /**
     * 完了イベントをPub/Subで送信
     * メインプロセスに完了を通知
     *
     * @param job 完了したジョブ
     * @param totalDistance 総飛行距離
     * @param actualFlightTime 実飛行時間
     */
    private void publishCompletionEvent(UAVJob job, double totalDistance, double actualFlightTime) {
        UAVCompletionEvent event = new UAVCompletionEvent(
            job.getUavId(),
            job.getClientId(),
            totalDistance,
            actualFlightTime,
            0.0,  // totalWaitingTime（Phase 3b-2cでは待機なし）
            job.getPath(),
            job.getSourceBeaconId(),
            job.getDestinationBeaconId()
        );

        long listeners = completionTopic.publish(event);
        LogManager.getInstance().log(
            "Phase 3b-2c: Worker " + workerId + " - UAV " + job.getUavId() +
            " 完了イベント送信 (listeners=" + listeners + ")"
        );
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
