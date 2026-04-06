package network_manager.redis;
import shared.redis.RedisConnectionManager;

import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import shared.util.LogManager;

import java.util.*;

/**
 * Redisからフライトログを読み取り、整合性を検証するクラス
 * Phase 2: フライトログの読み取りと検証
 */
public class FlightLogReader {

    private boolean redisEnabled = false;
    private RedissonClient client;

    /**
     * コンストラクタ
     */
    public FlightLogReader() {
        try {
            RedisConnectionManager connectionManager = RedisConnectionManager.getInstance();
            if (connectionManager.isConnected()) {
                this.client = connectionManager.getClient();
                this.redisEnabled = true;
            } else {
                LogManager.getInstance().log("FlightLogReader: Redis未接続のため、ログ読み取り機能は無効です");
            }
        } catch (Exception e) {
            LogManager.getInstance().error("FlightLogReader初期化エラー", e);
            this.redisEnabled = false;
        }
    }

    /**
     * フライトログをRedisから読み取る
     * @param uavId UAV ID
     * @return フライトログのMap（キーなし、または読み取り失敗時は空のMap）
     */
    public Map<String, Object> getFlightLog(int uavId) {
        if (!redisEnabled) {
            return new HashMap<>();
        }

        try {
            String key = "flightlog:uav:" + uavId;
            RMap<String, Object> map = client.getMap(key);

            if (map.isEmpty()) {
                return new HashMap<>();
            }

            return new HashMap<>(map.readAllMap());
        } catch (Exception e) {
            LogManager.getInstance().error("フライトログ読み取りエラー: UAV" + uavId, e);
            return new HashMap<>();
        }
    }

    /**
     * 飛行経路をRedisから読み取る
     * @param uavId UAV ID
     * @return 飛行経路のMap（キーなし、または読み取り失敗時は空のMap）
     */
    public Map<String, Object> getFlightPath(int uavId) {
        if (!redisEnabled) {
            return new HashMap<>();
        }

        try {
            String key = "flightpath:uav:" + uavId;
            RMap<String, Object> map = client.getMap(key);

            if (map.isEmpty()) {
                return new HashMap<>();
            }

            return new HashMap<>(map.readAllMap());
        } catch (Exception e) {
            LogManager.getInstance().error("飛行経路読み取りエラー: UAV" + uavId, e);
            return new HashMap<>();
        }
    }

    /**
     * 全UAVのフライトログをRedisから読み取る
     * @return UAV IDをキーとしたフライトログのMap
     */
    public Map<Integer, Map<String, Object>> getAllFlightLogs() {
        if (!redisEnabled) {
            return new HashMap<>();
        }

        try {
            Map<Integer, Map<String, Object>> result = new HashMap<>();
            Iterable<String> keys = client.getKeys().getKeysByPattern("flightlog:uav:*");

            for (String key : keys) {
                // "flightlog:uav:0" から "0" を抽出
                String[] parts = key.split(":");
                if (parts.length == 3) {
                    try {
                        int uavId = Integer.parseInt(parts[2]);
                        Map<String, Object> log = getFlightLog(uavId);
                        if (!log.isEmpty()) {
                            result.put(uavId, log);
                        }
                    } catch (NumberFormatException e) {
                        // スキップ
                    }
                }
            }

            return result;
        } catch (Exception e) {
            LogManager.getInstance().error("全フライトログ読み取りエラー", e);
            return new HashMap<>();
        }
    }

    /**
     * 全UAVの飛行経路をRedisから読み取る
     * @return UAV IDをキーとした飛行経路のMap
     */
    public Map<Integer, Map<String, Object>> getAllFlightPaths() {
        if (!redisEnabled) {
            return new HashMap<>();
        }

        try {
            Map<Integer, Map<String, Object>> result = new HashMap<>();
            Iterable<String> keys = client.getKeys().getKeysByPattern("flightpath:uav:*");

            for (String key : keys) {
                // "flightpath:uav:0" から "0" を抽出
                String[] parts = key.split(":");
                if (parts.length == 3) {
                    try {
                        int uavId = Integer.parseInt(parts[2]);
                        Map<String, Object> path = getFlightPath(uavId);
                        if (!path.isEmpty()) {
                            result.put(uavId, path);
                        }
                    } catch (NumberFormatException e) {
                        // スキップ
                    }
                }
            }

            return result;
        } catch (Exception e) {
            LogManager.getInstance().error("全飛行経路読み取りエラー", e);
            return new HashMap<>();
        }
    }

    /**
     * 特定のクライアントのフライトログをRedisから読み取る
     * @param clientId クライアントID
     * @return フライトログのリスト
     */
    public List<Map<String, Object>> getFlightLogsByClient(int clientId) {
        if (!redisEnabled) {
            return new ArrayList<>();
        }

        try {
            List<Map<String, Object>> result = new ArrayList<>();
            Map<Integer, Map<String, Object>> allLogs = getAllFlightLogs();

            for (Map<String, Object> log : allLogs.values()) {
                Integer logClientId = (Integer) log.get("clientId");
                if (logClientId != null && logClientId == clientId) {
                    result.add(log);
                }
            }

            return result;
        } catch (Exception e) {
            LogManager.getInstance().error("クライアント別フライトログ読み取りエラー: client" + clientId, e);
            return new ArrayList<>();
        }
    }

    /**
     * フライトログサマリを表示する
     */
    public void printFlightLogSummary() {
        if (!redisEnabled) {
            System.out.println("Redis機能が無効です。");
            return;
        }

        System.out.println("\n=== フライトログサマリ (Redis) ===");

        // フライトログ
        Map<Integer, Map<String, Object>> allLogs = getAllFlightLogs();
        if (!allLogs.isEmpty()) {
            System.out.println("\n[フライトログ]");
            System.out.println("  保存済みUAV数: " + allLogs.size());

            for (Map.Entry<Integer, Map<String, Object>> entry : allLogs.entrySet()) {
                Map<String, Object> log = entry.getValue();
                System.out.println("  UAV " + entry.getKey() + ":");
                System.out.println("    Client: " + log.get("clientId"));
                System.out.println("    経路: " + log.get("sourceBeaconId") + " → " + log.get("destinationBeaconId"));
                System.out.println("    飛行時間: " + log.get("uavFlightTime") + "ms, 待機時間: " + log.get("uavWaitingTime") + "ms");
                System.out.println("    速度: " + log.get("speed") + ", 距離: " + log.get("distance"));
            }
        }

        // 飛行経路
        Map<Integer, Map<String, Object>> allPaths = getAllFlightPaths();
        if (!allPaths.isEmpty()) {
            System.out.println("\n[飛行経路]");
            System.out.println("  保存済み経路数: " + allPaths.size());

            int displayCount = Math.min(5, allPaths.size());
            int count = 0;
            for (Map.Entry<Integer, Map<String, Object>> entry : allPaths.entrySet()) {
                if (count >= displayCount) break;
                Map<String, Object> path = entry.getValue();
                System.out.println("  UAV " + entry.getKey() + ": " + path.get("flightPath") + " (" + path.get("method") + ")");
                count++;
            }
            if (allPaths.size() > displayCount) {
                System.out.println("  ... 他 " + (allPaths.size() - displayCount) + " 件");
            }
        }

        System.out.println("================================\n");
    }

    /**
     * Redis機能が有効かどうかを確認する
     * @return 有効な場合true
     */
    public boolean isEnabled() {
        return redisEnabled;
    }
}
