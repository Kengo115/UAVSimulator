package server.redis;

import item.Link;
import org.redisson.api.RAtomicDouble;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import server.util.LogManager;

import java.util.Collections;

/**
 * リンク容量をRedisに保存・管理するクラス
 * Phase 3a: リンク容量Redis移行
 * Phase 3b-4: Luaスクリプトによる原子操作
 *
 * アトミック操作を使用して、複数ワーカーによる競合を防止
 */
public class LinkCapacityManager {

    private boolean redisEnabled = false;
    private RedissonClient client;
    private RScript script;

    /**
     * Phase 3b-4: 容量消費用Luaスクリプト
     * チェック→消費を1つの原子操作として実行
     *
     * KEYS[1]: 容量キー (link:X:Y:capacity)
     * 戻り値: 1 = 成功, 0 = 失敗（容量不足）
     */
    private static final String CONSUME_CAPACITY_SCRIPT =
        "local capacity = tonumber(redis.call('GET', KEYS[1])) or 0 " +
        "if capacity >= 1 then " +
        "    redis.call('INCRBYFLOAT', KEYS[1], -1) " +
        "    return 1 " +
        "else " +
        "    return 0 " +
        "end";

    /**
     * Phase 3b-4: 容量回復用Luaスクリプト
     * 回復後の容量を返す
     *
     * KEYS[1]: 容量キー (link:X:Y:capacity)
     * 戻り値: 回復後の容量
     */
    private static final String RECOVER_CAPACITY_SCRIPT =
        "local newCapacity = redis.call('INCRBYFLOAT', KEYS[1], 1) " +
        "return newCapacity";

    /**
     * コンストラクタ
     */
    public LinkCapacityManager() {
        try {
            RedisConnectionManager connectionManager = RedisConnectionManager.getInstance();
            if (connectionManager.isConnected()) {
                this.client = connectionManager.getClient();
                this.script = client.getScript();
                this.redisEnabled = true;
                LogManager.getInstance().log("Phase 3b-4: LinkCapacityManager初期化（Luaスクリプト有効）");
            } else {
                LogManager.getInstance().log("LinkCapacityManager: Redis未接続のため、容量管理機能は無効です");
            }
        } catch (Exception e) {
            LogManager.getInstance().error("LinkCapacityManager初期化エラー", e);
            this.redisEnabled = false;
        }
    }

    /**
     * 全リンクの容量を一括更新
     * Phase 3a: メモリからRedisへ二重書き込み
     *
     * @param link リンク情報配列
     * @param flyingUAV 飛行中UAV配列
     * @param node ノード数
     */
    public void updateAllCapacities(Link[][] link, int[][] flyingUAV, int node) {
        if (!redisEnabled) {
            return;
        }

        try {
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY) {
                        // 初期容量を保存
                        saveInitCapacity(i, j, link[i][j].getInitCapacity());

                        // 飛行中UAV数を保存（アトミック操作）
                        setFlyingCount(i, j, flyingUAV[i][j]);

                        // 現在容量を計算して保存
                        double currentCapacity = Math.max(0, link[i][j].getCapacity());
                        saveCapacity(i, j, currentCapacity);
                    }
                }
            }

            LogManager.getInstance().log("Phase 3a: 全リンク容量をRedisに保存しました");
        } catch (Exception e) {
            LogManager.getInstance().error("リンク容量一括更新エラー", e);
        }
    }

    /**
     * 初期容量をRedisに保存
     *
     * @param srcNode 開始ノード
     * @param dstNode 終了ノード
     * @param initCapacity 初期容量
     */
    public void saveInitCapacity(int srcNode, int dstNode, double initCapacity) {
        if (!redisEnabled) {
            return;
        }

        try {
            String key = "link:" + srcNode + ":" + dstNode + ":init_capacity";
            RBucket<Double> bucket = client.getBucket(key);
            bucket.set(initCapacity);
        } catch (Exception e) {
            LogManager.getInstance().error("初期容量保存エラー: link[" + srcNode + "][" + dstNode + "]", e);
        }
    }

    /**
     * 現在容量をRedisに保存
     *
     * @param srcNode 開始ノード
     * @param dstNode 終了ノード
     * @param capacity 現在容量
     */
    public void saveCapacity(int srcNode, int dstNode, double capacity) {
        if (!redisEnabled) {
            return;
        }

        try {
            String key = "link:" + srcNode + ":" + dstNode + ":capacity";
            RAtomicDouble atomicCapacity = client.getAtomicDouble(key);
            atomicCapacity.set(capacity);
        } catch (Exception e) {
            LogManager.getInstance().error("容量保存エラー: link[" + srcNode + "][" + dstNode + "]", e);
        }
    }

    /**
     * 飛行中UAV数を設定（アトミック操作）
     *
     * @param srcNode 開始ノード
     * @param dstNode 終了ノード
     * @param count 飛行中UAV数
     */
    public void setFlyingCount(int srcNode, int dstNode, int count) {
        if (!redisEnabled) {
            return;
        }

        try {
            String key = "link:" + srcNode + ":" + dstNode + ":flying_count";
            RAtomicLong counter = client.getAtomicLong(key);
            counter.set(count);
        } catch (Exception e) {
            LogManager.getInstance().error("飛行中UAV数設定エラー: link[" + srcNode + "][" + dstNode + "]", e);
        }
    }

    /**
     * 飛行中UAV数をアトミックにインクリメント
     * Phase 3b用: ワーカー並列処理での競合防止
     *
     * @param srcNode 開始ノード
     * @param dstNode 終了ノード
     * @return インクリメント後の値
     */
    public long incrementFlyingCount(int srcNode, int dstNode) {
        if (!redisEnabled) {
            return 0;
        }

        try {
            String key = "link:" + srcNode + ":" + dstNode + ":flying_count";
            RAtomicLong counter = client.getAtomicLong(key);
            long newCount = counter.incrementAndGet();
            LogManager.getInstance().log("Phase 3b: link[" + srcNode + "][" + dstNode + "] flying_count: " + (newCount - 1) + " → " + newCount);
            return newCount;
        } catch (Exception e) {
            LogManager.getInstance().error("飛行中UAV数インクリメントエラー: link[" + srcNode + "][" + dstNode + "]", e);
            return 0;
        }
    }

    /**
     * 飛行中UAV数をアトミックにデクリメント
     * Phase 3b用: ワーカー並列処理での競合防止
     *
     * @param srcNode 開始ノード
     * @param dstNode 終了ノード
     * @return デクリメント後の値
     */
    public long decrementFlyingCount(int srcNode, int dstNode) {
        if (!redisEnabled) {
            return 0;
        }

        try {
            String key = "link:" + srcNode + ":" + dstNode + ":flying_count";
            RAtomicLong counter = client.getAtomicLong(key);
            long newCount = counter.decrementAndGet();
            LogManager.getInstance().log("Phase 3b: link[" + srcNode + "][" + dstNode + "] flying_count: " + (newCount + 1) + " → " + newCount);
            return newCount;
        } catch (Exception e) {
            LogManager.getInstance().error("飛行中UAV数デクリメントエラー: link[" + srcNode + "][" + dstNode + "]", e);
            return 0;
        }
    }

    /**
     * リンク容量をアトミックに減算
     * Phase 3b用: ワーカー並列処理での競合防止
     *
     * @param srcNode 開始ノード
     * @param dstNode 終了ノード
     * @param amount 減算量
     * @return 減算後の値
     */
    public double decrementCapacity(int srcNode, int dstNode, double amount) {
        if (!redisEnabled) {
            return 0.0;
        }

        try {
            String key = "link:" + srcNode + ":" + dstNode + ":capacity";
            RAtomicDouble capacity = client.getAtomicDouble(key);
            double newCapacity = capacity.addAndGet(-amount);
            LogManager.getInstance().log("Phase 3b: link[" + srcNode + "][" + dstNode + "] capacity: " + (newCapacity + amount) + " → " + newCapacity);
            return newCapacity;
        } catch (Exception e) {
            LogManager.getInstance().error("容量減算エラー: link[" + srcNode + "][" + dstNode + "]", e);
            return 0.0;
        }
    }

    /**
     * リンク容量をアトミックに加算
     * Phase 3b用: ワーカー並列処理での競合防止
     *
     * @param srcNode 開始ノード
     * @param dstNode 終了ノード
     * @param amount 加算量
     * @return 加算後の値
     */
    public double incrementCapacity(int srcNode, int dstNode, double amount) {
        if (!redisEnabled) {
            return 0.0;
        }

        try {
            String key = "link:" + srcNode + ":" + dstNode + ":capacity";
            RAtomicDouble capacity = client.getAtomicDouble(key);
            double newCapacity = capacity.addAndGet(amount);
            LogManager.getInstance().log("Phase 3b: link[" + srcNode + "][" + dstNode + "] capacity: " + (newCapacity - amount) + " → " + newCapacity);
            return newCapacity;
        } catch (Exception e) {
            LogManager.getInstance().error("容量加算エラー: link[" + srcNode + "][" + dstNode + "]", e);
            return 0.0;
        }
    }

    /**
     * リンク容量を消費する（Luaスクリプトによる原子操作）
     * Phase 3b-4: チェック→消費を1つの原子操作として実行
     *
     * 1 UAV = 1 容量として消費
     * 容量が1未満の場合は消費せずfalseを返す
     *
     * 双方向リンク対応: 順方向と逆方向の両方の容量を消費
     *
     * @param srcNode 開始ノード
     * @param dstNode 終了ノード
     * @return 容量消費に成功した場合true、容量不足の場合false
     */
    public boolean tryConsumeCapacity(int srcNode, int dstNode) {
        if (!redisEnabled) {
            return false;
        }

        try {
            String forwardKey = "link:" + srcNode + ":" + dstNode + ":capacity";

            // 順方向: Luaスクリプトで原子的にチェック→消費
            Long result = script.eval(
                RScript.Mode.READ_WRITE,
                CONSUME_CAPACITY_SCRIPT,
                RScript.ReturnType.INTEGER,
                Collections.singletonList(forwardKey)
            );

            boolean success = (result != null && result == 1);

            if (success) {
                // 双方向リンク対応: 逆方向も消費
                String reverseKey = "link:" + dstNode + ":" + srcNode + ":capacity";
                script.eval(
                    RScript.Mode.READ_WRITE,
                    CONSUME_CAPACITY_SCRIPT,
                    RScript.ReturnType.INTEGER,
                    Collections.singletonList(reverseKey)
                );

                LogManager.getInstance().log(
                    "Phase 3b-4: link[" + srcNode + "][" + dstNode + "] 容量消費成功（双方向、Lua原子操作）"
                );
            } else {
                LogManager.getInstance().log(
                    "Phase 3b-4: link[" + srcNode + "][" + dstNode + "] 容量不足（Lua原子操作）"
                );
            }

            return success;
        } catch (Exception e) {
            LogManager.getInstance().error("容量消費エラー: link[" + srcNode + "][" + dstNode + "]", e);
            return false;
        }
    }

    /**
     * リンク容量を回復する（Luaスクリプトによる原子操作）
     * Phase 3b-4: UAVがリンクを通過した際に容量を回復
     *
     * 1 UAV = 1 容量として回復
     *
     * 双方向リンク対応: 順方向と逆方向の両方の容量を回復
     *
     * @param srcNode 開始ノード
     * @param dstNode 終了ノード
     * @return 回復後の容量
     */
    public double recoverCapacity(int srcNode, int dstNode) {
        if (!redisEnabled) {
            return 0.0;
        }

        try {
            String forwardKey = "link:" + srcNode + ":" + dstNode + ":capacity";

            // 順方向: Luaスクリプトで原子的に回復
            Object result = script.eval(
                RScript.Mode.READ_WRITE,
                RECOVER_CAPACITY_SCRIPT,
                RScript.ReturnType.VALUE,
                Collections.singletonList(forwardKey)
            );

            // 結果を適切にdoubleに変換
            double newCapacity;
            if (result instanceof Number) {
                newCapacity = ((Number) result).doubleValue();
            } else if (result instanceof String) {
                newCapacity = Double.parseDouble((String) result);
            } else {
                newCapacity = 0.0;
            }

            // 双方向リンク対応: 逆方向も回復
            String reverseKey = "link:" + dstNode + ":" + srcNode + ":capacity";
            script.eval(
                RScript.Mode.READ_WRITE,
                RECOVER_CAPACITY_SCRIPT,
                RScript.ReturnType.VALUE,
                Collections.singletonList(reverseKey)
            );

            LogManager.getInstance().log(
                "Phase 3b-4: link[" + srcNode + "][" + dstNode + "] 容量回復 → " + newCapacity + "（双方向、Lua原子操作）"
            );
            return newCapacity;
        } catch (Exception e) {
            LogManager.getInstance().error("容量回復エラー: link[" + srcNode + "][" + dstNode + "]", e);
            return 0.0;
        }
    }

    /**
     * 現在のリンク容量を取得する
     * Phase 3b-2d: 容量確認用
     *
     * @param srcNode 開始ノード
     * @param dstNode 終了ノード
     * @return 現在の容量
     */
    public double getCapacity(int srcNode, int dstNode) {
        if (!redisEnabled) {
            return 0.0;
        }

        try {
            String key = "link:" + srcNode + ":" + dstNode + ":capacity";
            RAtomicDouble capacity = client.getAtomicDouble(key);
            return capacity.get();
        } catch (Exception e) {
            LogManager.getInstance().error("容量取得エラー: link[" + srcNode + "][" + dstNode + "]", e);
            return 0.0;
        }
    }

    /**
     * Redis機能が有効かどうかを確認する
     *
     * @return 有効な場合true
     */
    public boolean isEnabled() {
        return redisEnabled;
    }

    /**
     * Redisの容量をメモリ（link配列）に同期する
     * Phase 3b-11: 経路探索前にRedisの最新容量をlinkに反映
     *
     * @param link リンク情報配列
     * @param node ノード数
     */
    public void syncCapacitiesToMemory(Link[][] link, int node) {
        if (!redisEnabled) {
            return;
        }

        try {
            int synced = 0;
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY) {
                        double redisCapacity = getCapacity(i, j);
                        link[i][j].setCapacity(redisCapacity);
                        synced++;
                    }
                }
            }

            LogManager.getInstance().log(
                "Phase 3b-11: Redis→メモリ容量同期完了 (" + synced + " リンク)"
            );
        } catch (Exception e) {
            LogManager.getInstance().error("Phase 3b-11: 容量同期エラー", e);
        }
    }

    /**
     * メモリ（link配列）の容量をRedisに同期する
     * 初期化時に使用：Redis側にキーが存在しない場合、メモリの値で初期化
     *
     * @param link リンク情報配列
     * @param node ノード数
     */
    public void initializeCapacitiesToRedis(Link[][] link, int node) {
        if (!redisEnabled) {
            return;
        }

        try {
            int initialized = 0;
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY) {
                        // 初期容量をRedisに保存
                        saveInitCapacity(i, j, link[i][j].getInitCapacity());
                        // 現在容量（=初期容量）をRedisに保存
                        saveCapacity(i, j, link[i][j].getCapacity());
                        initialized++;
                    }
                }
            }

            LogManager.getInstance().log(
                "Phase 3b-11: メモリ→Redis容量初期化完了 (" + initialized + " リンク)"
            );
        } catch (Exception e) {
            LogManager.getInstance().error("Phase 3b-11: 容量初期化エラー", e);
        }
    }
}
