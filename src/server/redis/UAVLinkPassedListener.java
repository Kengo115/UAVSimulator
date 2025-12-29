package server.redis;

import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import server.util.LogManager;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * UAVリンク通過イベントリスナー
 * Phase 3b-2d: 容量回復・待機UAV再ジョブ化対応
 *
 * メインプロセスで起動し、UAVのリンク通過を検知する
 * リンク通過時に容量を回復し、待機UAVをジョブキューに再投入する
 */
public class UAVLinkPassedListener {

    private RedissonClient client;
    private RTopic topic;
    private int listenerId = -1;

    // Phase 3b-2d: 容量管理・待機UAV管理・ジョブキュー
    private LinkCapacityManager capacityManager;
    private WaitingUAVManager waitingManager;
    private UAVJobQueue jobQueue;

    // 統計情報
    private AtomicInteger linkPassedCount = new AtomicInteger(0);
    private AtomicInteger reEnqueuedCount = new AtomicInteger(0);  // 再ジョブ化した数

    /**
     * コンストラクタ
     */
    public UAVLinkPassedListener() {
        try {
            this.client = RedisConnectionManager.getInstance().getClient();
            this.topic = client.getTopic(UAVEventChannels.LINK_PASSED);
            // Phase 3b-2d: 容量管理・待機UAV管理・ジョブキュー
            this.capacityManager = new LinkCapacityManager();
            this.waitingManager = new WaitingUAVManager();
            this.jobQueue = new UAVJobQueue();
        } catch (IllegalStateException e) {
            LogManager.getInstance().log("UAVLinkPassedListener: Redis未接続状態で初期化");
            this.client = null;
            this.topic = null;
        }
    }

    /**
     * リスナーを開始する
     */
    public void startListening() {
        if (topic == null) {
            LogManager.getInstance().error("UAVLinkPassedListener: トピックが初期化されていません");
            return;
        }

        listenerId = topic.addListener(UAVLinkPassedEvent.class, new MessageListener<UAVLinkPassedEvent>() {
            @Override
            public void onMessage(CharSequence channel, UAVLinkPassedEvent event) {
                handleLinkPassedEvent(event);
            }
        });

        LogManager.getInstance().log("Phase 3b-2d: UAVLinkPassedListener 開始 (listenerId=" + listenerId + ")");
    }

    /**
     * リンク通過イベントを処理する
     * Phase 3b-2d: 容量回復と待機UAV再ジョブ化
     *
     * @param event リンク通過イベント
     */
    private void handleLinkPassedEvent(UAVLinkPassedEvent event) {
        int count = linkPassedCount.incrementAndGet();

        int passedFromNode = event.getPassedFromNode();
        int passedToNode = event.getPassedToNode();

        String nextLinkInfo = event.isLastLink() ? "最終リンク" :
            event.getNextFromNode() + "→" + event.getNextToNode();

        LogManager.getInstance().log(
            "Phase 3b-2d: [メイン] UAV " + event.getUavId() + " リンク通過 " +
            passedFromNode + "→" + passedToNode + " " +
            "(client=" + event.getClientId() + ", " +
            "経過=" + String.format("%.2f", event.getElapsedFlightTime()) + "s, " +
            "次=" + nextLinkInfo + ", " +
            "総通過数=" + count + ")"
        );

        // Phase 3b-2d: 容量回復
        double newCapacity = capacityManager.recoverCapacity(passedFromNode, passedToNode);

        // Phase 3b-2d: 待機UAVがいれば再ジョブ化
        if (waitingManager.hasWaitingUAV(passedFromNode, passedToNode)) {
            UAVJob waitingJob = waitingManager.dequeue(passedFromNode, passedToNode);
            if (waitingJob != null) {
                // ジョブキューに再投入
                boolean enqueued = jobQueue.enqueueJob(waitingJob);
                if (enqueued) {
                    int reEnqueued = reEnqueuedCount.incrementAndGet();
                    LogManager.getInstance().log(
                        "Phase 3b-2d: [メイン] 待機UAV " + waitingJob.getUavId() +
                        " を再ジョブ化 (link " + passedFromNode + "→" + passedToNode +
                        ", 総再ジョブ化数=" + reEnqueued + ")"
                    );
                } else {
                    LogManager.getInstance().error(
                        "Phase 3b-2d: 待機UAV " + waitingJob.getUavId() + " の再ジョブ化に失敗"
                    );
                }
            }
        }
    }

    /**
     * リスナーを停止する
     */
    public void stopListening() {
        if (topic != null && listenerId >= 0) {
            topic.removeListener(listenerId);
            LogManager.getInstance().log("Phase 3b-2d: UAVLinkPassedListener 停止");
        }
    }

    /**
     * 通過したリンク数を取得
     * @return 通過数
     */
    public int getLinkPassedCount() {
        return linkPassedCount.get();
    }

    /**
     * 再ジョブ化した数を取得
     * @return 再ジョブ化数
     */
    public int getReEnqueuedCount() {
        return reEnqueuedCount.get();
    }

    /**
     * カウンターをリセット
     */
    public void resetCounter() {
        linkPassedCount.set(0);
        reEnqueuedCount.set(0);
    }

    /**
     * 接続状態を確認
     * @return 接続されている場合true
     */
    public boolean isConnected() {
        return client != null && topic != null;
    }
}
