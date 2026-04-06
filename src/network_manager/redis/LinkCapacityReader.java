package network_manager.redis;
import shared.redis.RedisConnectionManager;

import shared.item.Link;
import org.redisson.api.RAtomicDouble;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import shared.util.LogManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Redisからリンク容量を読み取り、整合性を検証するクラス
 * Phase 3a: リンク容量Redis移行
 */
public class LinkCapacityReader {

    private boolean redisEnabled = false;
    private RedissonClient client;

    /**
     * コンストラクタ
     */
    public LinkCapacityReader() {
        try {
            RedisConnectionManager connectionManager = RedisConnectionManager.getInstance();
            if (connectionManager.isConnected()) {
                this.client = connectionManager.getClient();
                this.redisEnabled = true;
            } else {
                LogManager.getInstance().log("LinkCapacityReader: Redis未接続のため、読み取り機能は無効です");
            }
        } catch (Exception e) {
            LogManager.getInstance().error("LinkCapacityReader初期化エラー", e);
            this.redisEnabled = false;
        }
    }

    /**
     * リンク容量をRedisから読み取り
     *
     * @param srcNode 開始ノード
     * @param dstNode 終了ノード
     * @return 現在容量（キーなし、または読み取り失敗時は0.0）
     */
    public double getCapacity(int srcNode, int dstNode) {
        if (!redisEnabled) {
            return 0.0;
        }

        try {
            String key = "link:" + srcNode + ":" + dstNode + ":capacity";
            RAtomicDouble atomicCapacity = client.getAtomicDouble(key);

            if (!atomicCapacity.isExists()) {
                return 0.0;
            }

            return atomicCapacity.get();
        } catch (Exception e) {
            LogManager.getInstance().error("容量読み取りエラー: link[" + srcNode + "][" + dstNode + "]", e);
            return 0.0;
        }
    }

    /**
     * 初期容量をRedisから読み取り
     *
     * @param srcNode 開始ノード
     * @param dstNode 終了ノード
     * @return 初期容量（キーなし、または読み取り失敗時は0.0）
     */
    public double getInitCapacity(int srcNode, int dstNode) {
        if (!redisEnabled) {
            return 0.0;
        }

        try {
            String key = "link:" + srcNode + ":" + dstNode + ":init_capacity";
            RBucket<Double> bucket = client.getBucket(key);

            Double value = bucket.get();
            return value != null ? value : 0.0;
        } catch (Exception e) {
            LogManager.getInstance().error("初期容量読み取りエラー: link[" + srcNode + "][" + dstNode + "]", e);
            return 0.0;
        }
    }

    /**
     * 飛行中UAV数をRedisから読み取り
     *
     * @param srcNode 開始ノード
     * @param dstNode 終了ノード
     * @return 飛行中UAV数（キーなし、または読み取り失敗時は0）
     */
    public long getFlyingCount(int srcNode, int dstNode) {
        if (!redisEnabled) {
            return 0;
        }

        try {
            String key = "link:" + srcNode + ":" + dstNode + ":flying_count";
            RAtomicLong counter = client.getAtomicLong(key);

            if (!counter.isExists()) {
                return 0;
            }

            return counter.get();
        } catch (Exception e) {
            LogManager.getInstance().error("飛行中UAV数読み取りエラー: link[" + srcNode + "][" + dstNode + "]", e);
            return 0;
        }
    }

    /**
     * 全リンクの容量をRedisから読み取り
     *
     * @param node ノード数
     * @return リンクキーをキーとした容量のMap
     */
    public Map<String, Double> getAllCapacities(int node) {
        if (!redisEnabled) {
            return new HashMap<>();
        }

        try {
            Map<String, Double> result = new HashMap<>();

            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    double capacity = getCapacity(i, j);
                    if (capacity > 0 || getFlyingCount(i, j) > 0) {
                        String linkKey = i + "-" + j;
                        result.put(linkKey, capacity);
                    }
                }
            }

            return result;
        } catch (Exception e) {
            LogManager.getInstance().error("全容量読み取りエラー", e);
            return new HashMap<>();
        }
    }

    /**
     * メモリとRedisのリンク容量を比較して整合性検証
     * Phase 3a: 二重書き込みの検証
     *
     * @param link リンク情報配列
     * @param flyingUAV 飛行中UAV配列
     * @param node ノード数
     * @return 整合性が保たれている場合true
     */
    public boolean validateCapacity(Link[][] link, int[][] flyingUAV, int node) {
        if (!redisEnabled) {
            return true;  // Redis無効時は検証スキップ
        }

        try {
            int mismatchCount = 0;
            int checkedCount = 0;

            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    // 存在しないリンクはスキップ
                    if (link[i][j].getL_tubeLength() == Double.POSITIVE_INFINITY) {
                        continue;
                    }

                    checkedCount++;

                    // メモリの容量
                    double memoryCapacity = link[i][j].getCapacity();

                    // Redisの容量
                    double redisCapacity = getCapacity(i, j);

                    // 飛行中UAV数
                    int memoryFlyingCount = flyingUAV[i][j];
                    long redisFlyingCount = getFlyingCount(i, j);

                    // 容量の比較（誤差0.001以下は許容）
                    if (Math.abs(memoryCapacity - redisCapacity) > 0.001) {
                        LogManager.getInstance().log(
                            "Phase 3a: 容量不整合 link[" + i + "][" + j + "] " +
                            "memory=" + String.format("%.3f", memoryCapacity) +
                            ", redis=" + String.format("%.3f", redisCapacity)
                        );
                        mismatchCount++;
                    }

                    // 飛行中UAV数の比較
                    if (memoryFlyingCount != redisFlyingCount) {
                        LogManager.getInstance().log(
                            "Phase 3a: 飛行中UAV数不整合 link[" + i + "][" + j + "] " +
                            "memory=" + memoryFlyingCount +
                            ", redis=" + redisFlyingCount
                        );
                        mismatchCount++;
                    }
                }
            }

            if (mismatchCount == 0) {
                LogManager.getInstance().log("Phase 3a: リンク容量整合性チェック正常 (チェック数: " + checkedCount + ")");
                return true;
            } else {
                LogManager.getInstance().log(
                    "Phase 3a: リンク容量に不整合を検出 (不整合: " + mismatchCount +
                    ", チェック数: " + checkedCount + ")"
                );
                return false;
            }

        } catch (Exception e) {
            LogManager.getInstance().error("容量整合性検証エラー", e);
            return false;
        }
    }

    /**
     * リンク容量サマリを表示する
     *
     * @param node ノード数
     */
    public void printCapacitySummary(int node) {
        if (!redisEnabled) {
            System.out.println("Redis機能が無効です。");
            return;
        }

        System.out.println("\n=== リンク容量サマリ (Redis) ===");

        Map<String, Double> allCapacities = getAllCapacities(node);

        if (allCapacities.isEmpty()) {
            System.out.println("リンク容量データなし");
        } else {
            System.out.println("保存済みリンク数: " + allCapacities.size());

            int displayCount = Math.min(10, allCapacities.size());
            int count = 0;

            for (Map.Entry<String, Double> entry : allCapacities.entrySet()) {
                if (count >= displayCount) break;

                String linkKey = entry.getKey();
                String[] parts = linkKey.split("-");
                int i = Integer.parseInt(parts[0]);
                int j = Integer.parseInt(parts[1]);

                double capacity = entry.getValue();
                long flyingCount = getFlyingCount(i, j);
                double initCapacity = getInitCapacity(i, j);

                System.out.println(
                    "  link[" + i + "][" + j + "]: " +
                    "capacity=" + String.format("%.2f", capacity) +
                    ", flying=" + flyingCount +
                    ", init=" + String.format("%.2f", initCapacity)
                );
                count++;
            }

            if (allCapacities.size() > displayCount) {
                System.out.println("  ... 他 " + (allCapacities.size() - displayCount) + " 件");
            }
        }

        System.out.println("================================\n");
    }

    /**
     * Redis機能が有効かどうかを確認する
     *
     * @return 有効な場合true
     */
    public boolean isEnabled() {
        return redisEnabled;
    }
}
