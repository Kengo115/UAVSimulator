package server.redis;

import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import server.util.LogManager;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * UAVリンク通過イベントリスナー
 * Phase 3b-2c: Workerからのリンク通過通知を受信
 *
 * メインプロセスで起動し、UAVのリンク通過を検知する
 */
public class UAVLinkPassedListener {

    private RedissonClient client;
    private RTopic topic;
    private int listenerId = -1;

    // 統計情報（Phase 3b-2c: シンプルなカウンターのみ）
    private AtomicInteger linkPassedCount = new AtomicInteger(0);

    /**
     * コンストラクタ
     */
    public UAVLinkPassedListener() {
        try {
            this.client = RedisConnectionManager.getInstance().getClient();
            this.topic = client.getTopic(UAVEventChannels.LINK_PASSED);
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

        LogManager.getInstance().log("Phase 3b-2c: UAVLinkPassedListener 開始 (listenerId=" + listenerId + ")");
    }

    /**
     * リンク通過イベントを処理する
     * Phase 3b-2c: ログ出力とカウンターのみ
     *
     * @param event リンク通過イベント
     */
    private void handleLinkPassedEvent(UAVLinkPassedEvent event) {
        int count = linkPassedCount.incrementAndGet();

        String nextLinkInfo = event.isLastLink() ? "最終リンク" :
            event.getNextFromNode() + "→" + event.getNextToNode();

        LogManager.getInstance().log(
            "Phase 3b-2c: [メイン] UAV " + event.getUavId() + " リンク通過 " +
            event.getPassedFromNode() + "→" + event.getPassedToNode() + " " +
            "(client=" + event.getClientId() + ", " +
            "経過=" + String.format("%.2f", event.getElapsedFlightTime()) + "s, " +
            "次=" + nextLinkInfo + ", " +
            "総通過数=" + count + ")"
        );

        // Phase 3b-2d以降: ここで容量回復と待機UAV再ジョブ化を行う
    }

    /**
     * リスナーを停止する
     */
    public void stopListening() {
        if (topic != null && listenerId >= 0) {
            topic.removeListener(listenerId);
            LogManager.getInstance().log("Phase 3b-2c: UAVLinkPassedListener 停止");
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
     * カウンターをリセット
     */
    public void resetCounter() {
        linkPassedCount.set(0);
    }

    /**
     * 接続状態を確認
     * @return 接続されている場合true
     */
    public boolean isConnected() {
        return client != null && topic != null;
    }
}
