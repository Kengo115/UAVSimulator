package shared.redis;

import shared.item.UAVJob;
import network_manager.redis.UAVEventChannels;

import org.redisson.api.RDeque;
import org.redisson.api.RedissonClient;
import shared.util.LogManager;

/**
 * 経路待ちUAV管理クラス
 * Phase 4: PG-EPS用の経路待ちキュー管理
 *
 * クライアント別の経路待ちキュー（FIFO）を管理する
 * 待機キーの形式: waiting:path:{clientId}
 *
 * 経路待ちUAVは、同一クライアントの別UAVが第一ホップを通過した際に
 * 経路をコピーして飛行開始する
 */
public class PathWaitingManager {

    private RedissonClient client;

    /**
     * コンストラクタ
     */
    public PathWaitingManager() {
        try {
            this.client = RedisConnectionManager.getInstance().getClient();
        } catch (IllegalStateException e) {
            LogManager.getInstance().log("PathWaitingManager: Redis未接続状態で初期化");
            this.client = null;
        }
    }

    /**
     * 経路待ちUAVをクライアント別キューに登録（FIFO）
     *
     * @param clientId クライアントID
     * @param job 経路待ちするジョブ（path=null）
     */
    public void enqueue(int clientId, UAVJob job) {
        if (client == null) {
            LogManager.getInstance().error("PathWaitingManager.enqueue: Redis未接続");
            return;
        }

        String key = UAVEventChannels.getPathWaitingQueueKey(clientId);
        RDeque<UAVJob> queue = client.getDeque(key);
        queue.addLast(job);

        // 可視化: PRE_WAIT（経路割り当て待ち）として記録
        VizStateManager.getInstance().reportPreWait(job.getUavId(), clientId, job.getSourceBeaconId());

        LogManager.getInstance().log(
            "Phase 4 [経路待ち登録]: client" + clientId + " UAV" + job.getUavId() +
            " を経路待ちキューに追加 (s=" + job.getSourceBeaconId() + "→d=" + job.getDestinationBeaconId() +
            ", 速度=" + String.format("%.2f", job.getSpeed()) + "m/s, 待機数=" + queue.size() + ")"
        );
    }

    /**
     * 経路待ちUAVをクライアント別キューから取り出し（FIFO）
     *
     * @param clientId クライアントID
     * @return 経路待ちしていたジョブ、なければnull
     */
    public UAVJob dequeue(int clientId) {
        if (client == null) {
            LogManager.getInstance().error("PathWaitingManager.dequeue: Redis未接続");
            return null;
        }

        String key = UAVEventChannels.getPathWaitingQueueKey(clientId);
        RDeque<UAVJob> queue = client.getDeque(key);
        UAVJob job = queue.pollFirst();

        if (job != null) {
            LogManager.getInstance().log(
                "Phase 4 [経路待ち取出]: client" + clientId + " UAV" + job.getUavId() +
                " を経路待ちキューから取り出し (残り待機数=" + queue.size() + ")"
            );
        }

        return job;
    }

    /**
     * 指定クライアントに経路待ちUAVがいるか確認
     *
     * @param clientId クライアントID
     * @return 経路待ちUAVがいる場合true
     */
    public boolean hasWaitingUAV(int clientId) {
        if (client == null) {
            return false;
        }

        String key = UAVEventChannels.getPathWaitingQueueKey(clientId);
        RDeque<UAVJob> queue = client.getDeque(key);
        return !queue.isEmpty();
    }

    /**
     * 指定クライアントの経路待ちキュー長を取得
     *
     * @param clientId クライアントID
     * @return 経路待ちUAV数
     */
    public int getWaitingCount(int clientId) {
        if (client == null) {
            return 0;
        }

        String key = UAVEventChannels.getPathWaitingQueueKey(clientId);
        RDeque<UAVJob> queue = client.getDeque(key);
        return queue.size();
    }

    /**
     * 指定クライアントの経路待ちキューをクリア
     *
     * @param clientId クライアントID
     */
    public void clear(int clientId) {
        if (client == null) {
            return;
        }

        String key = UAVEventChannels.getPathWaitingQueueKey(clientId);
        RDeque<UAVJob> queue = client.getDeque(key);
        int count = queue.size();
        queue.clear();

        LogManager.getInstance().log(
            "Phase 4: client" + clientId + " の経路待ちキューをクリア (削除数=" + count + ")"
        );
    }

    /**
     * すべての経路待ちキューをクリア
     * シミュレーションリセット時に使用
     */
    public void clearAll() {
        if (client == null) {
            return;
        }

        long deletedCount = client.getKeys().deleteByPattern(UAVEventChannels.PATH_WAITING_QUEUE_PREFIX + "*");
        LogManager.getInstance().log(
            "Phase 4: 全経路待ちキューをクリア (削除キー数=" + deletedCount + ")"
        );
    }

    /**
     * 全経路待ちキューの合計待機UAV数を取得
     * Phase 7-11: Phase4終了判定用
     *
     * @return 全クライアントで経路待ち中のUAV総数
     */
    public int getTotalWaitingCount() {
        if (client == null) {
            return 0;
        }

        int total = 0;
        try {
            Iterable<String> keys = client.getKeys().getKeysByPattern(UAVEventChannels.PATH_WAITING_QUEUE_PREFIX + "*");
            for (String key : keys) {
                RDeque<UAVJob> queue = client.getDeque(key);
                total += queue.size();
            }
        } catch (Exception e) {
            LogManager.getInstance().error("PathWaitingManager.getTotalWaitingCount エラー", e);
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
