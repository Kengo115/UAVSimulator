package legacy.eventdriven;

import shared.redis.ClientTimeManager;
import shared.util.LogManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Option B (イベント駆動インメモリ方式) UAV 完了トラッカー
 *
 * Redis の UAVCompletionListener (RTopic pub/sub) を
 * インメモリのカウンタ + コールバックで置き換える。
 */
public class MemoryCompletionTracker {

    private static MemoryCompletionTracker instance;

    /** 総完了 UAV 数 */
    private final AtomicInteger completedCount = new AtomicInteger(0);

    /** 総飛行中 UAV 数 */
    private final AtomicInteger activeCount = new AtomicInteger(0);

    /** clientId → 完了 UAV 数 */
    private final ConcurrentHashMap<Integer, AtomicInteger> clientCompleted
            = new ConcurrentHashMap<>();

    /** 全完了時に呼ばれるコールバック */
    private Runnable onAllCompletedCallback;

    /** 総 UAV 数（全完了判定用） */
    private int totalExpected = 0;

    private MemoryCompletionTracker() {}

    public static synchronized MemoryCompletionTracker getInstance() {
        if (instance == null) {
            instance = new MemoryCompletionTracker();
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = new MemoryCompletionTracker();
    }

    // ─────────────────────────────────────────────────────────────────
    // 設定
    // ─────────────────────────────────────────────────────────────────

    /** シミュレーション開始時に期待総数を設定する */
    public void setTotalExpected(int total) {
        this.totalExpected = total;
    }

    /** 全完了コールバックを設定する */
    public void setOnAllCompleted(Runnable callback) {
        this.onAllCompletedCallback = callback;
    }

    // ─────────────────────────────────────────────────────────────────
    // MemoryFlightScheduler から呼ばれるイベント
    // ─────────────────────────────────────────────────────────────────

    /** UAV が飛行開始したとき */
    public void onFlightStarted() {
        activeCount.incrementAndGet();
    }

    /** UAV が目的地到着したとき */
    public void onUAVCompleted(int uavId, int clientId) {
        activeCount.decrementAndGet();
        completedCount.incrementAndGet();

        // クライアント別カウント（ClientTimeManager に委譲）
        ClientTimeManager.getInstance().onUAVCompleted(clientId);
        clientCompleted
                .computeIfAbsent(clientId, k -> new AtomicInteger(0))
                .incrementAndGet();

        LogManager.getInstance().log(
                "[EventDrivenMode] UAV" + uavId + " 目的地到着 (完了: "
                + completedCount.get() + "/" + totalExpected + ")");

        System.out.printf("[EventDrivenMode] UAV%d 到着完了 (完了: %d/%d)%n",
                uavId, completedCount.get(), totalExpected);

        // 全完了判定
        if (totalExpected > 0 && completedCount.get() >= totalExpected) {
            if (onAllCompletedCallback != null) {
                onAllCompletedCallback.run();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 状態取得
    // ─────────────────────────────────────────────────────────────────

    public int getCompletedCount() { return completedCount.get(); }
    public int getActiveCount()    { return activeCount.get(); }
    public int getTotalExpected()  { return totalExpected; }

    /** [active, completed, expected] を一括取得 */
    public int[] getStatus() {
        return new int[]{activeCount.get(), completedCount.get(), totalExpected};
    }
}
