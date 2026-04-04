package server.uav;

import item.Uav;

import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MEMORYモード用の経路待ちキュー管理クラス
 * REDISモードの PathWaitingManager に相当する Java ネイティブ実装。
 * PG-EPS で最大フロー超過分の UAV を保持し、先行 UAV が第一ホップを通過した
 * タイミングで経路をコピーして飛行待機状態に移行させる。
 */
public class MemoryPathWaitingManager {

    private static final MemoryPathWaitingManager INSTANCE = new MemoryPathWaitingManager();

    /** clientId → 経路待ちUAVキュー */
    private final Map<Integer, Queue<Uav>> waitingQueues = new ConcurrentHashMap<>();

    private MemoryPathWaitingManager() {}

    public static MemoryPathWaitingManager getInstance() {
        return INSTANCE;
    }

    /**
     * 指定クライアントの経路待ちキューに UAV を追加する
     */
    public void enqueue(int clientId, Uav uav) {
        waitingQueues.computeIfAbsent(clientId, k -> new LinkedList<>()).add(uav);
    }

    /**
     * 指定クライアントの経路待ちキューから UAV を1台取り出す
     * @return 待機UAV、またはキューが空の場合は null
     */
    public Uav dequeue(int clientId) {
        Queue<Uav> queue = waitingQueues.get(clientId);
        if (queue == null) return null;
        return queue.poll();
    }

    /**
     * 指定クライアントの経路待ちキューに UAV が存在するか確認する
     */
    public boolean hasWaiting(int clientId) {
        Queue<Uav> queue = waitingQueues.get(clientId);
        return queue != null && !queue.isEmpty();
    }

    /**
     * 指定クライアントのキューをクリアする
     */
    public void clear(int clientId) {
        waitingQueues.remove(clientId);
    }

    /**
     * 全クライアントのキューをクリアする（シミュレーションリセット時に使用）
     */
    public void clearAll() {
        waitingQueues.clear();
    }
}
