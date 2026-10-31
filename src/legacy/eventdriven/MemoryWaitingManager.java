package legacy.eventdriven;

import shared.item.UAVJob;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Option B (イベント駆動インメモリ方式) リンク容量待機キュー管理クラス
 *
 * Redis の WaitingUAVManager (RDeque / link) を
 * ConcurrentHashMap&lt;String, Deque&lt;UAVJob&gt;&gt; で置き換える。
 * 双方向リンクを正規化キー（小さいノード:大きいノード）で管理する。
 */
public class MemoryWaitingManager {

    /** linkKey → 待機 UAVJob の FIFO キュー */
    private final ConcurrentHashMap<String, Deque<UAVJob>> waitingQueues
            = new ConcurrentHashMap<>();

    /** 全リンク合計の待機 UAV 数 */
    private final AtomicInteger totalCount = new AtomicInteger(0);

    // ─────────────────────────────────────────────────────────────────
    // WaitingUAVManager 互換 API
    // ─────────────────────────────────────────────────────────────────

    /**
     * 待機キューへ追加する（FIFO）。
     */
    public synchronized void enqueue(int fromNode, int toNode, UAVJob job) {
        String k = key(fromNode, toNode);
        waitingQueues.computeIfAbsent(k, x -> new ArrayDeque<>()).addLast(job);
        totalCount.incrementAndGet();
    }

    /**
     * 待機キューから先頭を取り出す。
     *
     * @return UAVJob または null（キュー空）
     */
    public synchronized UAVJob dequeue(int fromNode, int toNode) {
        Deque<UAVJob> q = waitingQueues.get(key(fromNode, toNode));
        if (q == null || q.isEmpty()) return null;
        totalCount.decrementAndGet();
        return q.pollFirst();
    }

    /** 指定リンクに待機 UAV が存在するか */
    public boolean hasWaitingUAV(int fromNode, int toNode) {
        Deque<UAVJob> q = waitingQueues.get(key(fromNode, toNode));
        return q != null && !q.isEmpty();
    }

    /** 指定リンクの待機 UAV 数 */
    public int getWaitingCount(int fromNode, int toNode) {
        Deque<UAVJob> q = waitingQueues.get(key(fromNode, toNode));
        return q == null ? 0 : q.size();
    }

    /** 全リンクの待機 UAV 合計数 */
    public int getTotalWaitingCount() {
        return totalCount.get();
    }

    /** 全キューをクリアする */
    public synchronized void clearAll() {
        waitingQueues.clear();
        totalCount.set(0);
    }

    // ─────────────────────────────────────────────────────────────────
    // private
    // ─────────────────────────────────────────────────────────────────

    /** 双方向リンクの正規化キー */
    private static String key(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return "waiting:link:" + lo + ":" + hi;
    }
}
