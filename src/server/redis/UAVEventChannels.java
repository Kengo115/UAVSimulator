package server.redis;

/**
 * UAVイベント用Pub/Subチャンネル名定数
 * Phase 3b-2a: イベント駆動アーキテクチャの基盤
 */
public final class UAVEventChannels {

    private UAVEventChannels() {
        // インスタンス化禁止
    }

    /**
     * UAVリンク通過イベントチャンネル
     * Worker → メインプロセス
     * UAVがリンクを通過した際に発行
     */
    public static final String LINK_PASSED = "uav:link:passed";

    /**
     * UAV飛行完了イベントチャンネル
     * Worker → メインプロセス
     * UAVが目的地に到着した際に発行
     */
    public static final String COMPLETION = "uav:completed";

    /**
     * 待機キューのキープレフィックス（容量待ち）
     * 形式: waiting:link:{fromNode}:{toNode}
     * 例: waiting:link:0:1
     */
    public static final String WAITING_QUEUE_PREFIX = "waiting:link:";

    /**
     * 経路待ちキューのキープレフィックス
     * Phase 4: 経路待ちUAVをクライアント別に管理
     * 形式: waiting:path:{clientId}
     * 例: waiting:path:0
     */
    public static final String PATH_WAITING_QUEUE_PREFIX = "waiting:path:";

    /**
     * 待機キューのキーを生成（容量待ち）
     * @param fromNode 始点ノード
     * @param toNode 終点ノード
     * @return Redis キー
     */
    public static String getWaitingQueueKey(int fromNode, int toNode) {
        return WAITING_QUEUE_PREFIX + fromNode + ":" + toNode;
    }

    /**
     * 経路待ちキューのキーを生成
     * Phase 4: クライアント別の経路待ちキュー
     * @param clientId クライアントID
     * @return Redis キー
     */
    public static String getPathWaitingQueueKey(int clientId) {
        return PATH_WAITING_QUEUE_PREFIX + clientId;
    }
}
