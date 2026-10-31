package legacy.eventdriven;

import shared.item.UAVJob;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Option B (イベント駆動インメモリ方式) ジョブキュー
 *
 * Redis の RBlockingQueue (UAVJobQueue) を
 * Java の LinkedBlockingQueue で置き換える。
 * Singleton で1シミュレーション中に共有される。
 */
public class MemoryJobQueue {

    private static MemoryJobQueue instance;

    private final LinkedBlockingQueue<UAVJob> queue = new LinkedBlockingQueue<>();

    private MemoryJobQueue() {}

    public static synchronized MemoryJobQueue getInstance() {
        if (instance == null) {
            instance = new MemoryJobQueue();
        }
        return instance;
    }

    /** キューをクリアして再利用できる状態にする（シミュレーション開始時） */
    public static synchronized void reset() {
        instance = new MemoryJobQueue();
    }

    // ─────────────────────────────────────────────────────────────────
    // UAVJobQueue 互換 API
    // ─────────────────────────────────────────────────────────────────

    /**
     * ジョブをキューへ追加する（ノンブロッキング）。
     * Redis の LPUSH jobs:uav に相当。
     */
    public boolean enqueueJob(UAVJob job) {
        return queue.offer(job);
    }

    /**
     * ジョブをキューから取得する（ブロッキング）。
     * Redis の BLPOP に相当。
     *
     * @param timeout タイムアウト値
     * @param unit    タイムアウト単位
     * @return UAVJob または null（タイムアウト）
     */
    public UAVJob dequeueJob(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    /** 現在のキューサイズ */
    public int getQueueSize() {
        return queue.size();
    }
}
