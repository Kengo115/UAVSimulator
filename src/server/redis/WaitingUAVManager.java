package server.redis;

import org.redisson.api.RDeque;
import org.redisson.api.RedissonClient;
import server.util.LogManager;

/**
 * 待機UAV管理クラス
 * Phase 3b-2a: スタブ実装（ロジックなし）
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
        // Phase 3b-2a: スタブ実装（ログ出力のみ）
        LogManager.getInstance().log("[STUB] WaitingUAVManager.enqueue: " +
                "UAV " + job.getUavId() + " を待機キュー (link " + fromNode + "→" + toNode + ") に追加");

        // TODO: Phase 3b-2d で実装
        // String key = UAVEventChannels.getWaitingQueueKey(fromNode, toNode);
        // RDeque<UAVJob> queue = client.getDeque(key);
        // queue.addLast(job);
    }

    /**
     * 待機UAVをリンク別キューから取り出し（FIFO）
     *
     * @param fromNode リンクの始点ノード
     * @param toNode リンクの終点ノード
     * @return 待機していたジョブ、なければnull
     */
    public UAVJob dequeue(int fromNode, int toNode) {
        // Phase 3b-2a: スタブ実装（nullを返す）
        LogManager.getInstance().log("[STUB] WaitingUAVManager.dequeue: " +
                "link " + fromNode + "→" + toNode + " から取り出し試行");

        // TODO: Phase 3b-2d で実装
        // String key = UAVEventChannels.getWaitingQueueKey(fromNode, toNode);
        // RDeque<UAVJob> queue = client.getDeque(key);
        // return queue.pollFirst();

        return null;
    }

    /**
     * 指定リンクに待機UAVがいるか確認
     *
     * @param fromNode リンクの始点ノード
     * @param toNode リンクの終点ノード
     * @return 待機UAVがいる場合true
     */
    public boolean hasWaitingUAV(int fromNode, int toNode) {
        // Phase 3b-2a: スタブ実装（常にfalse）
        LogManager.getInstance().log("[STUB] WaitingUAVManager.hasWaitingUAV: " +
                "link " + fromNode + "→" + toNode + " を確認");

        // TODO: Phase 3b-2d で実装
        // String key = UAVEventChannels.getWaitingQueueKey(fromNode, toNode);
        // RDeque<UAVJob> queue = client.getDeque(key);
        // return !queue.isEmpty();

        return false;
    }

    /**
     * 指定リンクの待機キュー長を取得
     *
     * @param fromNode リンクの始点ノード
     * @param toNode リンクの終点ノード
     * @return 待機UAV数
     */
    public int getWaitingCount(int fromNode, int toNode) {
        // Phase 3b-2a: スタブ実装（常に0）
        // TODO: Phase 3b-2d で実装
        // String key = UAVEventChannels.getWaitingQueueKey(fromNode, toNode);
        // RDeque<UAVJob> queue = client.getDeque(key);
        // return queue.size();

        return 0;
    }

    /**
     * 指定リンクの待機キューをクリア
     *
     * @param fromNode リンクの始点ノード
     * @param toNode リンクの終点ノード
     */
    public void clear(int fromNode, int toNode) {
        // Phase 3b-2a: スタブ実装（ログ出力のみ）
        LogManager.getInstance().log("[STUB] WaitingUAVManager.clear: " +
                "link " + fromNode + "→" + toNode + " をクリア");

        // TODO: Phase 3b-2d で実装
        // String key = UAVEventChannels.getWaitingQueueKey(fromNode, toNode);
        // RDeque<UAVJob> queue = client.getDeque(key);
        // queue.clear();
    }

    /**
     * すべての待機キューをクリア
     * シミュレーションリセット時に使用
     */
    public void clearAll() {
        // Phase 3b-2a: スタブ実装（ログ出力のみ）
        LogManager.getInstance().log("[STUB] WaitingUAVManager.clearAll: 全待機キューをクリア");

        // TODO: Phase 3b-2d で実装
        // client.getKeys().deleteByPattern(UAVEventChannels.WAITING_QUEUE_PREFIX + "*");
    }

    /**
     * Redis接続状態を確認
     * @return 接続されている場合true
     */
    public boolean isConnected() {
        return client != null && RedisConnectionManager.getInstance().isConnected();
    }
}
