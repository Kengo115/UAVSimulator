package network_manager.redis;
import shared.redis.RedisConnectionManager;

import shared.item.Uav;
import shared.item.Link;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import shared.util.LogManager;

import java.util.HashMap;
import java.util.Map;

/**
 * UAV状態のRedis管理クラス
 * UAVの状態をRedisのHash構造で保存・取得する
 * Phase 1: 読み取り専用状態同期（二重書き込み）
 */
public class UAVStateManager {
    private RedissonClient client;
    private boolean redisEnabled = true;

    /**
     * コンストラクタ
     * RedisConnectionManagerからクライアントを取得
     */
    public UAVStateManager() {
        try {
            RedisConnectionManager manager = RedisConnectionManager.getInstance();
            if (manager.isConnected()) {
                this.client = manager.getClient();
            } else {
                this.redisEnabled = false;
                LogManager.getInstance().log("警告: Redis接続が確立されていません。UAV状態はメモリのみで管理されます。");
            }
        } catch (Exception e) {
            this.redisEnabled = false;
            LogManager.getInstance().error("UAVStateManager初期化エラー", e);
        }
    }

    /**
     * UAV状態をRedisに保存
     * @param uav 保存するUAVオブジェクト
     */
    public void saveUAVState(Uav uav) {
        if (!redisEnabled) {
            return;
        }

        try {
            String key = "uav:" + uav.getId();
            RMap<String, Object> map = client.getMap(key);

            // 基本情報
            map.put("uavId", uav.getId());
            map.put("clientId", uav.getClientId());
            map.put("speed", uav.getSpeed());

            // 状態情報
            String status = getStatusString(uav);
            map.put("status", status);

            // 位置情報
            map.put("x", uav.getX());
            map.put("y", uav.getY());

            // リンク情報
            Link flyingLink = uav.getFlyingLink();
            if (flyingLink != null && flyingLink.getBeacon1() != null && flyingLink.getBeacon2() != null) {
                map.put("currentLinkFrom", flyingLink.getBeacon1().getId());
                map.put("currentLinkTo", flyingLink.getBeacon2().getId());
            } else {
                map.put("currentLinkFrom", -1);
                map.put("currentLinkTo", -1);
            }

            // 待機情報
            map.put("stayedBeaconId", uav.getStayedBeaconId());

            // 経路情報
            map.put("path", pathToString(uav.getPath()));

            // 時間情報
            map.put("flightTime", uav.getFlightTime());
            map.put("waitingTime", uav.getWaitingTime());

            // 最終更新時刻
            map.put("lastUpdateTime", System.currentTimeMillis());

            // ログ出力（デバッグ用）
            if (LogManager.getInstance().isLoggingEnabled()) {
                LogManager.getInstance().log("UAV " + uav.getId() + " の状態をRedisに保存: " + status);
            }
        } catch (Exception e) {
            LogManager.getInstance().error("UAV状態保存エラー (UAV ID: " + uav.getId() + ")", e);
        }
    }

    /**
     * Redisから UAV状態を読み取り
     * @param uavId UAV ID
     * @return UAV状態のMap
     */
    public Map<String, Object> getUAVState(int uavId) {
        if (!redisEnabled) {
            return new HashMap<>();
        }

        try {
            String key = "uav:" + uavId;
            RMap<String, Object> map = client.getMap(key);

            if (map.isEmpty()) {
                return new HashMap<>();
            }

            return new HashMap<>(map);
        } catch (Exception e) {
            LogManager.getInstance().error("UAV状態取得エラー (UAV ID: " + uavId + ")", e);
            return new HashMap<>();
        }
    }

    /**
     * UAV状態をRedisから削除
     * @param uavId UAV ID
     */
    public void deleteUAVState(int uavId) {
        if (!redisEnabled) {
            return;
        }

        try {
            String key = "uav:" + uavId;
            client.getMap(key).delete();
            LogManager.getInstance().log("UAV " + uavId + " の状態をRedisから削除しました");
        } catch (Exception e) {
            LogManager.getInstance().error("UAV状態削除エラー (UAV ID: " + uavId + ")", e);
        }
    }

    /**
     * Redis接続が有効かどうか
     * @return 有効な場合true
     */
    public boolean isRedisEnabled() {
        return redisEnabled;
    }

    /**
     * UAVの状態を文字列で取得
     * @param uav UAVオブジェクト
     * @return 状態文字列 ("flying", "waiting", "completed")
     */
    private String getStatusString(Uav uav) {
        if (uav.isFlying()) {
            return "flying";
        } else if (uav.isWaiting()) {
            return "waiting";
        } else {
            // 飛行も待機もしていない場合は完了とみなす
            return "idle";
        }
    }

    /**
     * 経路配列を文字列に変換
     * @param path 経路配列
     * @return 経路文字列 (例: "0-1-4-5")
     */
    private String pathToString(int[] path) {
        if (path == null || path.length == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.length; i++) {
            sb.append(path[i]);
            if (i < path.length - 1) {
                sb.append("-");
            }
        }
        return sb.toString();
    }
}
