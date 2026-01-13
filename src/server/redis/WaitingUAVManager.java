package server.redis;

import org.redisson.api.RDeque;
import org.redisson.api.RedissonClient;
import server.util.LogManager;

/**
 * 待機UAV管理クラス
 * Phase 3b-2d: Redis RDequeを使用した本実装
 *
 * リンク別の待機キュー（FIFO）を管理する
 * 待機キーの形式: waiting:link:{fromNode}:{toNode}
 */
public class WaitingUAVManager {

    private RedissonClient client;

    /**
     * コンストラクタ
     */
    public WaitingUAVManager() {
        try {
            this.client = RedisConnectionManager.getInstance().getClient();
        } catch (IllegalStateException e) {
            LogManager.getInstance().log("WaitingUAVManager: Redis未接続状態で初期化");
            this.client = null;
        }
    }

    /**
     * 待機UAVをリンク別キューに登録（FIFO）
     *
     * @param fromNode リンクの始点ノード
     * @param toNode リンクの終点ノード
     * @param job 待機するジョブ
     */
    public void enqueue(int fromNode, int toNode, UAVJob job) {
        if (client == null) {
            LogManager.getInstance().error("WaitingUAVManager.enqueue: Redis未接続");
            return;
        }

        String key = UAVEventChannels.getWaitingQueueKey(fromNode, toNode);
        RDeque<UAVJob> queue = client.getDeque(key);
        queue.addLast(job);

        LogManager.getInstance().log(
            "Phase 3b-2d: client" + job.getClientId() + " UAV" + job.getUavId() + " を待機キュー (" +
            fromNode + "→" + toNode + ") に追加 (待機数=" + queue.size() + ")"
        );
    }

    /**
     * 待機UAVをリンク別キューから取り出し（FIFO）
     *
     * @param fromNode リンクの始点ノード
     * @param toNode リンクの終点ノード
     * @return 待機していたジョブ、なければnull
     */
    public UAVJob dequeue(int fromNode, int toNode) {
        if (client == null) {
            LogManager.getInstance().error("WaitingUAVManager.dequeue: Redis未接続");
            return null;
        }

        String key = UAVEventChannels.getWaitingQueueKey(fromNode, toNode);
        RDeque<UAVJob> queue = client.getDeque(key);
        UAVJob job = queue.pollFirst();

        if (job != null) {
            LogManager.getInstance().log(
                "Phase 3b-2d: client" + job.getClientId() + " UAV" + job.getUavId() + " を待機キュー (" +
                fromNode + "→" + toNode + ") から取り出し (残り待機数=" + queue.size() + ")"
            );
        }

        return job;
    }

    /**
     * 指定リンクに待機UAVがいるか確認
     *
     * @param fromNode リンクの始点ノード
     * @param toNode リンクの終点ノード
     * @return 待機UAVがいる場合true
     */
    public boolean hasWaitingUAV(int fromNode, int toNode) {
        if (client == null) {
            return false;
        }

        String key = UAVEventChannels.getWaitingQueueKey(fromNode, toNode);
        RDeque<UAVJob> queue = client.getDeque(key);
        return !queue.isEmpty();
    }

    /**
     * 指定リンクの待機キュー長を取得
     *
     * @param fromNode リンクの始点ノード
     * @param toNode リンクの終点ノード
     * @return 待機UAV数
     */
    public int getWaitingCount(int fromNode, int toNode) {
        if (client == null) {
            return 0;
        }

        String key = UAVEventChannels.getWaitingQueueKey(fromNode, toNode);
        RDeque<UAVJob> queue = client.getDeque(key);
        return queue.size();
    }

    /**
     * 指定リンクの待機キューをクリア
     *
     * @param fromNode リンクの始点ノード
     * @param toNode リンクの終点ノード
     */
    public void clear(int fromNode, int toNode) {
        if (client == null) {
            return;
        }

        String key = UAVEventChannels.getWaitingQueueKey(fromNode, toNode);
        RDeque<UAVJob> queue = client.getDeque(key);
        int count = queue.size();
        queue.clear();

        LogManager.getInstance().log(
            "Phase 3b-2d: 待機キュー (" + fromNode + "→" + toNode + ") をクリア (削除数=" + count + ")"
        );
    }

    /**
     * すべての待機キューをクリア
     * シミュレーションリセット時に使用
     */
    public void clearAll() {
        if (client == null) {
            return;
        }

        long deletedCount = client.getKeys().deleteByPattern(UAVEventChannels.WAITING_QUEUE_PREFIX + "*");
        LogManager.getInstance().log(
            "Phase 3b-2d: 全待機キューをクリア (削除キー数=" + deletedCount + ")"
        );
    }

    /**
     * 全待機キューの合計待機UAV数を取得
     * Phase 7-11: Phase4終了判定用
     *
     * @return 全リンクで待機中のUAV総数
     */
    public int getTotalWaitingCount() {
        if (client == null) {
            return 0;
        }

        int total = 0;
        try {
            Iterable<String> keys = client.getKeys().getKeysByPattern(UAVEventChannels.WAITING_QUEUE_PREFIX + "*");
            for (String key : keys) {
                RDeque<UAVJob> queue = client.getDeque(key);
                total += queue.size();
            }
        } catch (Exception e) {
            LogManager.getInstance().error("getTotalWaitingCount エラー", e);
        }
        return total;
    }

    /**
     * Redis接続状態を確認
     * @return 接続されている場合true
     */
    public boolean isConnected() {
        return client != null && RedisConnectionManager.getInstance().isConnected();
    }
}
