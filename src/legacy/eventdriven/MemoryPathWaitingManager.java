package legacy.eventdriven;

import shared.item.UAVJob;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Option B (イベント駆動インメモリ方式) 経路待ち UAV 管理クラス
 *
 * Redis の PathWaitingManager (RDeque / client) を
 * ConcurrentHashMap&lt;Integer, Deque&lt;UAVJob&gt;&gt; で置き換える。
 *
 * 先頭 UAV が第1リンクを通過したタイミングで
 * 後続 UAV の経路をコピーしてフライト投入する仕組みに対応する。
 */
public class MemoryPathWaitingManager {

    /** clientId → 経路待ち UAVJob の FIFO キュー */
    private final ConcurrentHashMap<Integer, Deque<UAVJob>> queues
            = new ConcurrentHashMap<>();

    /** 全クライアント合計の経路待ち UAV 数 */
    private final AtomicInteger totalCount = new AtomicInteger(0);

    // ─────────────────────────────────────────────────────────────────
    // PathWaitingManager 互換 API
    // ─────────────────────────────────────────────────────────────────

    /**
     * 経路待ちキューへ追加する（FIFO）。
     */
    public synchronized void enqueue(int clientId, UAVJob job) {
        queues.computeIfAbsent(clientId, k -> new ArrayDeque<>()).addLast(job);
        totalCount.incrementAndGet();
    }

    /**
     * 経路待ちキューから先頭を取り出す。
     *
     * @return UAVJob または null（キュー空）
     */
    public synchronized UAVJob dequeue(int clientId) {
        Deque<UAVJob> q = queues.get(clientId);
        if (q == null || q.isEmpty()) return null;
        totalCount.decrementAndGet();
        return q.pollFirst();
    }

    /** 指定クライアントに経路待ち UAV が存在するか */
    public boolean hasWaitingUAV(int clientId) {
        Deque<UAVJob> q = queues.get(clientId);
        return q != null && !q.isEmpty();
    }

    /** 指定クライアントの経路待ち UAV 数 */
    public int getWaitingCount(int clientId) {
        Deque<UAVJob> q = queues.get(clientId);
        return q == null ? 0 : q.size();
    }

    /** 全クライアントの経路待ち UAV 合計数 */
    public int getTotalWaitingCount() {
        return totalCount.get();
    }

    /** 全キューをクリアする */
    public synchronized void clearAll() {
        queues.clear();
        totalCount.set(0);
    }
}
