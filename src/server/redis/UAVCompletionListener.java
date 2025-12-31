package server.redis;

import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import server.util.LogManager;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * UAV完了イベントリスナー
 * Phase 3b-2b: Workerからの完了通知を受信
 *
 * メインプロセスで起動し、UAVの飛行完了を検知する
 */
public class UAVCompletionListener {

    private RedissonClient client;
    private RTopic topic;
    private int listenerId = -1;

    // 統計情報（Phase 3b-2b: シンプルなカウンターのみ）
    private AtomicInteger completedCount = new AtomicInteger(0);

    /**
     * コンストラクタ
     */
    public UAVCompletionListener() {
        try {
            this.client = RedisConnectionManager.getInstance().getClient();
            this.topic = client.getTopic(UAVEventChannels.COMPLETION);
        } catch (IllegalStateException e) {
            LogManager.getInstance().log("UAVCompletionListener: Redis未接続状態で初期化");
            this.client = null;
            this.topic = null;
        }
    }

    /**
     * リスナーを開始する
     */
    public void startListening() {
        if (topic == null) {
            LogManager.getInstance().error("UAVCompletionListener: トピックが初期化されていません");
            return;
        }

        listenerId = topic.addListener(UAVCompletionEvent.class, new MessageListener<UAVCompletionEvent>() {
            @Override
            public void onMessage(CharSequence channel, UAVCompletionEvent event) {
                handleCompletionEvent(event);
            }
        });

        LogManager.getInstance().log("Phase 3b-2b: UAVCompletionListener 開始 (listenerId=" + listenerId + ")");
    }

    /**
     * 完了イベントを処理する
     * Phase 3b-2b: ログ出力とカウンターのみ
     *
     * @param event 完了イベント
     */
    private void handleCompletionEvent(UAVCompletionEvent event) {
        int count = completedCount.incrementAndGet();

        LogManager.getInstance().log(
            "Phase 3b-2b: [メイン] UAV " + event.getUavId() + " 完了通知受信 " +
            "(client=" + event.getClientId() + ", " +
            "distance=" + String.format("%.2f", event.getTotalDistance()) + "m, " +
            "time=" + String.format("%.2f", event.getActualFlightTime()) + "s, " +
            "efficiency=" + String.format("%.1f", event.getFlightEfficiency() * 100) + "%, " +
            "総完了数=" + count + ")"
        );

        // Phase 3b-2d以降: ここで容量回復と待機UAV再ジョブ化を行う
    }

    /**
     * リスナーを停止する
     */
    public void stopListening() {
        if (topic != null && listenerId >= 0) {
            topic.removeListener(listenerId);
            LogManager.getInstance().log("Phase 3b-2b: UAVCompletionListener 停止");
        }
    }

    /**
     * 完了したUAV数を取得
     * @return 完了数
     */
    public int getCompletedCount() {
        return completedCount.get();
    }

    /**
     * カウンターをリセット
     */
    public void resetCounter() {
        completedCount.set(0);
    }

    /**
     * 接続状態を確認
     * @return 接続されている場合true
     */
    public boolean isConnected() {
        return client != null && topic != null;
    }
}
