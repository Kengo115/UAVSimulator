package legacy.eventdriven;

import shared.item.Link;
import shared.util.LogManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Option B (イベント駆動インメモリ方式) 容量管理クラス
 *
 * Redis の LinkCapacityManager (Lua スクリプトによるアトミック操作) を
 * ConcurrentHashMap + synchronized による Java のアトミック操作で置き換える。
 */
public class MemoryCapacityManager {

    /** link:from:to → 残余容量 (整数) */
    private final ConcurrentHashMap<String, AtomicInteger> capacityMap
            = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────────
    // 初期化
    // ─────────────────────────────────────────────────────────────────

    /**
     * link[][] から初期容量を読み込み、インメモリに設定する。
     * シミュレーション開始時に1回呼ぶ。
     * Redis の initializeCapacitiesToRedis() に相当。
     */
    public void initializeCapacities(Link[][] link, int node) {
        capacityMap.clear();
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j] != null
                        && link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY) {
                    int cap = (int) link[i][j].getInitCapacity();
                    capacityMap.put(key(i, j), new AtomicInteger(cap));
                    capacityMap.put(key(j, i), new AtomicInteger(cap));
                }
            }
        }
        LogManager.getInstance().log(
                "[EventDrivenMode] MemoryCapacityManager: "
                + capacityMap.size() / 2 + " リンクを初期化");
    }

    // ─────────────────────────────────────────────────────────────────
    // LinkCapacityManager 互換 API
    // ─────────────────────────────────────────────────────────────────

    /**
     * 容量を1消費する。容量が0以下なら失敗。
     * Redis Lua スクリプト CONSUME_CAPACITY_SCRIPT のアトミック操作に相当。
     *
     * @return true: 消費成功 / false: 容量不足
     */
    public boolean tryConsumeCapacity(int fromNode, int toNode) {
        AtomicInteger fwd = capacityMap.get(key(fromNode, toNode));
        AtomicInteger rev = capacityMap.get(key(toNode, fromNode));
        if (fwd == null || rev == null) return false;

        synchronized (this) {
            if (fwd.get() <= 0) return false;
            fwd.decrementAndGet();
            rev.decrementAndGet();
        }

        // link[][] にも反映
        syncToLinkArray(fromNode, toNode, fwd.get());
        return true;
    }

    /**
     * 容量を1回復する。
     * Redis Lua スクリプト RECOVER_CAPACITY_SCRIPT に相当。
     */
    public void recoverCapacity(int fromNode, int toNode) {
        AtomicInteger fwd = capacityMap.get(key(fromNode, toNode));
        AtomicInteger rev = capacityMap.get(key(toNode, fromNode));
        if (fwd == null || rev == null) return;

        int newCap;
        synchronized (this) {
            newCap = fwd.incrementAndGet();
            rev.incrementAndGet();
        }

        syncToLinkArray(fromNode, toNode, newCap);
    }

    /**
     * 現在の容量を返す。
     */
    public int getCapacity(int fromNode, int toNode) {
        AtomicInteger cap = capacityMap.get(key(fromNode, toNode));
        return cap != null ? cap.get() : 0;
    }

    /**
     * インメモリ容量を link[][] に同期する。
     * Redis の syncCapacitiesToMemory() に相当。
     */
    public void syncToLinkMatrix(Link[][] link, int node) {
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j] != null
                        && link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY) {
                    AtomicInteger cap = capacityMap.get(key(i, j));
                    if (cap != null) {
                        link[i][j].setCapacity(cap.get());
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // private
    // ─────────────────────────────────────────────────────────────────

    private static String key(int from, int to) {
        return from + ":" + to;
    }

    private void syncToLinkArray(int from, int to, int newCap) {
        try {
            Link[][] link = operator.controller.ServerController.getLinkArray();
            if (link != null && link[from][to] != null) {
                link[from][to].setCapacity(newCap);
                link[to][from].setCapacity(newCap);
            }
        } catch (Exception e) {
            // LInk 配列未初期化などのエラーは無視
        }
    }
}
