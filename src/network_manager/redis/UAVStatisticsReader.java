package network_manager.redis;
import shared.redis.RedisConnectionManager;

import shared.client.Client;
import shared.client.ClientController;
import shared.item.BeaconCluster;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import shared.util.LogManager;

import java.util.*;

/**
 * Redisから統計情報を読み取り、整合性を検証するクラス
 * Phase 2: 非クリティカルな統計データの読み取りと検証
 */
public class UAVStatisticsReader {

    private boolean redisEnabled = false;
    private RedissonClient client;

    /**
     * コンストラクタ
     */
    public UAVStatisticsReader() {
        try {
            RedisConnectionManager connectionManager = RedisConnectionManager.getInstance();
            if (connectionManager.isConnected()) {
                this.client = connectionManager.getClient();
                this.redisEnabled = true;
            } else {
                LogManager.getInstance().log("UAVStatisticsReader: Redis未接続のため、統計読み取り機能は無効です");
            }
        } catch (Exception e) {
            LogManager.getInstance().error("UAVStatisticsReader初期化エラー", e);
            this.redisEnabled = false;
        }
    }

    /**
     * グローバル統計情報をRedisから読み取る
     * @return 統計情報のMap（キーなし、または読み取り失敗時は空のMap）
     */
    public Map<String, Object> getGlobalStats() {
        if (!redisEnabled) {
            return new HashMap<>();
        }

        try {
            String key = "stats:global";
            RMap<String, Object> map = client.getMap(key);

            if (map.isEmpty()) {
                return new HashMap<>();
            }

            return new HashMap<>(map.readAllMap());
        } catch (Exception e) {
            LogManager.getInstance().error("グローバル統計読み取りエラー", e);
            return new HashMap<>();
        }
    }

    /**
     * クライアント統計情報をRedisから読み取る
     * @param clientId クライアントID
     * @return 統計情報のMap（キーなし、または読み取り失敗時は空のMap）
     */
    public Map<String, Object> getClientStats(int clientId) {
        if (!redisEnabled) {
            return new HashMap<>();
        }

        try {
            String key = "stats:client:" + clientId;
            RMap<String, Object> map = client.getMap(key);

            if (map.isEmpty()) {
                return new HashMap<>();
            }

            return new HashMap<>(map.readAllMap());
        } catch (Exception e) {
            LogManager.getInstance().error("クライアント統計読み取りエラー: client" + clientId, e);
            return new HashMap<>();
        }
    }

    /**
     * 全クライアントの統計情報をRedisから読み取る
     * @return クライアントIDをキーとした統計情報のMap
     */
    public Map<Integer, Map<String, Object>> getAllClientStats() {
        if (!redisEnabled) {
            return new HashMap<>();
        }

        try {
            Map<Integer, Map<String, Object>> result = new HashMap<>();
            Iterable<String> keys = client.getKeys().getKeysByPattern("stats:client:*");

            for (String key : keys) {
                // "stats:client:1" から "1" を抽出
                String[] parts = key.split(":");
                if (parts.length == 3) {
                    try {
                        int clientId = Integer.parseInt(parts[2]);
                        Map<String, Object> stats = getClientStats(clientId);
                        if (!stats.isEmpty()) {
                            result.put(clientId, stats);
                        }
                    } catch (NumberFormatException e) {
                        // スキップ
                    }
                }
            }

            return result;
        } catch (Exception e) {
            LogManager.getInstance().error("全クライアント統計読み取りエラー", e);
            return new HashMap<>();
        }
    }

    /**
     * ビーコン統計情報をRedisから読み取る
     * @param beaconId ビーコンID
     * @return 統計情報のMap（キーなし、または読み取り失敗時は空のMap）
     */
    public Map<String, Object> getBeaconStats(int beaconId) {
        if (!redisEnabled) {
            return new HashMap<>();
        }

        try {
            String key = "stats:beacon:" + beaconId;
            RMap<String, Object> map = client.getMap(key);

            if (map.isEmpty()) {
                return new HashMap<>();
            }

            return new HashMap<>(map.readAllMap());
        } catch (Exception e) {
            LogManager.getInstance().error("ビーコン統計読み取りエラー: beacon" + beaconId, e);
            return new HashMap<>();
        }
    }

    /**
     * 全ビーコンの統計情報をRedisから読み取る
     * @param nodeCount ノード数
     * @return ビーコン統計のリスト
     */
    public List<Map<String, Object>> getAllBeaconStats(int nodeCount) {
        if (!redisEnabled) {
            return new ArrayList<>();
        }

        try {
            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = 0; i < nodeCount; i++) {
                Map<String, Object> stats = getBeaconStats(i);
                if (!stats.isEmpty()) {
                    result.add(stats);
                }
            }
            return result;
        } catch (Exception e) {
            LogManager.getInstance().error("全ビーコン統計読み取りエラー", e);
            return new ArrayList<>();
        }
    }

    /**
     * メモリとRedisのグローバル統計を比較し、整合性を検証する
     * @param memoryFlyingCount メモリ内の飛行中UAV数
     * @param memoryWaitingCount メモリ内の待機中UAV数
     * @param memoryElapsedTime メモリ内の経過時間
     * @return 整合性が取れている場合true
     */
    public boolean validateGlobalStats(int memoryFlyingCount, int memoryWaitingCount, long memoryElapsedTime) {
        if (!redisEnabled) {
            return true; // Redisが無効の場合は常にtrue
        }

        try {
            Map<String, Object> redisStats = getGlobalStats();

            if (redisStats.isEmpty()) {
                LogManager.getInstance().log("警告: グローバル統計がRedisに存在しません");
                return false;
            }

            int redisFlyingCount = (Integer) redisStats.get("flyingUavCount");
            int redisWaitingCount = (Integer) redisStats.get("waitingUavCount");
            long redisElapsedTime = (Long) redisStats.get("totalElapsedTime");

            boolean isValid = true;

            if (redisFlyingCount != memoryFlyingCount) {
                LogManager.getInstance().log("不整合: 飛行中UAV数 - Memory: " + memoryFlyingCount + ", Redis: " + redisFlyingCount);
                isValid = false;
            }

            if (redisWaitingCount != memoryWaitingCount) {
                LogManager.getInstance().log("不整合: 待機中UAV数 - Memory: " + memoryWaitingCount + ", Redis: " + redisWaitingCount);
                isValid = false;
            }

            // 経過時間は若干のズレを許容（100ミリ秒以内）
            long timeDiff = Math.abs(redisElapsedTime - memoryElapsedTime);
            if (timeDiff > 100) {
                LogManager.getInstance().log("不整合: 経過時間 - Memory: " + memoryElapsedTime + ", Redis: " + redisElapsedTime + " (差分: " + timeDiff + "ms)");
                isValid = false;
            }

            if (isValid) {
                LogManager.getInstance().log("✓ グローバル統計の整合性確認完了（飛行:" + memoryFlyingCount + ", 待機:" + memoryWaitingCount + "）");
            }

            return isValid;
        } catch (Exception e) {
            LogManager.getInstance().error("グローバル統計検証エラー", e);
            return false;
        }
    }

    /**
     * メモリとRedisのクライアント統計を比較し、整合性を検証する
     * @param clientController クライアントコントローラー
     * @return 整合性が取れている場合true
     */
    public boolean validateClientStats(ClientController clientController) {
        if (!redisEnabled) {
            return true;
        }

        try {
            boolean allValid = true;

            for (Client client : clientController.getClientList()) {
                Map<String, Object> redisStats = getClientStats(client.getId());

                if (redisStats.isEmpty()) {
                    LogManager.getInstance().log("警告: client" + client.getId() + " の統計がRedisに存在しません");
                    allValid = false;
                    continue;
                }

                int memoryFinishedCount = client.getFinishFlyingCounter();
                int redisFinishedCount = (Integer) redisStats.get("finishedUavCount");

                if (memoryFinishedCount != redisFinishedCount) {
                    LogManager.getInstance().log("不整合: client" + client.getId() + " 完了UAV数 - Memory: " + memoryFinishedCount + ", Redis: " + redisFinishedCount);
                    allValid = false;
                } else {
                    LogManager.getInstance().log("✓ client" + client.getId() + " 統計整合性確認完了（完了UAV: " + memoryFinishedCount + "）");
                }
            }

            return allValid;
        } catch (Exception e) {
            LogManager.getInstance().error("クライアント統計検証エラー", e);
            return false;
        }
    }

    /**
     * メモリとRedisのビーコン統計を比較し、整合性を検証する
     * @param beaconCluster ビーコンクラスター
     * @param nodeCount ノード数
     * @return 整合性が取れている場合true
     */
    public boolean validateBeaconStats(BeaconCluster beaconCluster, int nodeCount) {
        if (!redisEnabled) {
            return true;
        }

        try {
            boolean allValid = true;
            int mismatchCount = 0;

            for (int i = 0; i < nodeCount; i++) {
                Map<String, Object> redisStats = getBeaconStats(i);

                if (redisStats.isEmpty()) {
                    // ビーコン統計は多数あるため、警告は出さない
                    continue;
                }

                int memoryWaitingCount = beaconCluster.getBeacon(i).getWaitingUavCount();
                int redisWaitingCount = (Integer) redisStats.get("waitingUavCount");

                if (memoryWaitingCount != redisWaitingCount) {
                    LogManager.getInstance().log("不整合: beacon" + i + " 待機UAV数 - Memory: " + memoryWaitingCount + ", Redis: " + redisWaitingCount);
                    mismatchCount++;
                    allValid = false;
                }
            }

            if (allValid) {
                LogManager.getInstance().log("✓ 全ビーコン統計の整合性確認完了");
            } else {
                LogManager.getInstance().log("ビーコン統計に " + mismatchCount + " 件の不整合がありました");
            }

            return allValid;
        } catch (Exception e) {
            LogManager.getInstance().error("ビーコン統計検証エラー", e);
            return false;
        }
    }

    /**
     * 全ての統計情報の整合性を一括検証する
     * @param flyingUavCount 飛行中のUAV数
     * @param waitingUavCount 待機中のUAV数
     * @param clientController クライアントコントローラー
     * @param beaconCluster ビーコンクラスター
     * @param nodeCount ノード数
     * @return 全て整合性が取れている場合true
     */
    public boolean validateAllStats(int flyingUavCount, int waitingUavCount,
                                   ClientController clientController,
                                   BeaconCluster beaconCluster, int nodeCount) {
        if (!redisEnabled) {
            return true;
        }

        LogManager.getInstance().log("=== Phase 2: 統計情報の整合性検証開始 ===");

        boolean globalValid = validateGlobalStats(flyingUavCount, waitingUavCount, clientController.getFlightTime());
        boolean clientValid = validateClientStats(clientController);
        boolean beaconValid = validateBeaconStats(beaconCluster, nodeCount);

        boolean allValid = globalValid && clientValid && beaconValid;

        if (allValid) {
            LogManager.getInstance().log("=== Phase 2: 統計情報の整合性検証完了（不整合なし） ===");
        } else {
            LogManager.getInstance().log("=== Phase 2: 統計情報の整合性検証完了（不整合あり） ===");
        }

        return allValid;
    }

    /**
     * 統計サマリをコンソールに表示する
     */
    public void printStatisticsSummary() {
        if (!redisEnabled) {
            System.out.println("Redis統計機能が無効です。");
            return;
        }

        System.out.println("\n=== 統計サマリ (Redis) ===");

        // グローバル統計
        Map<String, Object> globalStats = getGlobalStats();
        if (!globalStats.isEmpty()) {
            System.out.println("\n[グローバル統計]");
            System.out.println("  飛行中UAV数: " + globalStats.get("flyingUavCount"));
            System.out.println("  待機中UAV数: " + globalStats.get("waitingUavCount"));
            System.out.println("  経過時間: " + globalStats.get("totalElapsedTime") + "ms");
        }

        // クライアント統計
        Map<Integer, Map<String, Object>> allClientStats = getAllClientStats();
        if (!allClientStats.isEmpty()) {
            System.out.println("\n[クライアント統計]");
            for (Map.Entry<Integer, Map<String, Object>> entry : allClientStats.entrySet()) {
                Map<String, Object> stats = entry.getValue();
                System.out.println("  Client " + entry.getKey() + ":");
                System.out.println("    完了UAV数: " + stats.get("finishedUavCount") + " / " + stats.get("totalRequestedUavs"));
                System.out.println("    経路: " + stats.get("sourceBeaconId") + " → " + stats.get("destinationBeaconId"));
            }
        }

        System.out.println("========================\n");
    }

    /**
     * Redis統計機能が有効かどうかを確認する
     * @return 有効な場合true
     */
    public boolean isEnabled() {
        return redisEnabled;
    }
}
