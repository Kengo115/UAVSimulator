package server.redis;

import item.Uav;
import item.Link;
import server.util.LogManager;

import java.util.Map;

/**
 * UAV状態の整合性検証クラス
 * メモリ上のUAV状態とRedis上のUAV状態を比較し、差異を検出する
 * Phase 1: データ整合性の検証
 */
public class UAVStateValidator {
    private UAVStateManager stateManager;

    /**
     * コンストラクタ
     */
    public UAVStateValidator() {
        this.stateManager = new UAVStateManager();
    }

    /**
     * メモリとRedisのUAV状態を比較
     * @param uav メモリ上のUAVオブジェクト
     * @return 整合性がある場合true
     */
    public boolean validateUAVState(Uav uav) {
        if (!stateManager.isRedisEnabled()) {
            return true; // Redis無効時は常にtrue
        }

        try {
            Map<String, Object> redisState = stateManager.getUAVState(uav.getId());

            if (redisState.isEmpty()) {
                LogManager.getInstance().log("警告: UAV " + uav.getId() + " がRedisに存在しません");
                return false;
            }

            boolean isValid = true;

            // UAV IDの確認
            if (!validateField(uav.getId(), "uavId", uav.getId(), redisState.get("uavId"))) {
                isValid = false;
            }

            // クライアントIDの確認
            if (!validateField(uav.getId(), "clientId", uav.getClientId(), redisState.get("clientId"))) {
                isValid = false;
            }

            // 状態の確認
            String memoryStatus = getStatusString(uav);
            if (!validateField(uav.getId(), "status", memoryStatus, redisState.get("status"))) {
                isValid = false;
            }

            // 速度の確認
            if (!validateDoubleField(uav.getId(), "speed", uav.getSpeed(), redisState.get("speed"))) {
                isValid = false;
            }

            // リンク情報の確認
            Link flyingLink = uav.getFlyingLink();
            if (flyingLink != null && flyingLink.getBeacon1() != null && flyingLink.getBeacon2() != null) {
                int memoryFrom = flyingLink.getBeacon1().getId();
                int memoryTo = flyingLink.getBeacon2().getId();

                if (!validateField(uav.getId(), "currentLinkFrom", memoryFrom, redisState.get("currentLinkFrom"))) {
                    isValid = false;
                }
                if (!validateField(uav.getId(), "currentLinkTo", memoryTo, redisState.get("currentLinkTo"))) {
                    isValid = false;
                }
            }

            // 待機ビーコンIDの確認
            if (!validateField(uav.getId(), "stayedBeaconId", uav.getStayedBeaconId(), redisState.get("stayedBeaconId"))) {
                isValid = false;
            }

            // 時間情報の確認（時刻は変動するので大きな差異のみ検出）
            long memoryFlightTime = uav.getFlightTime();
            Object redisFlightTime = redisState.get("flightTime");
            if (redisFlightTime != null) {
                long redisTime = ((Number) redisFlightTime).longValue();
                long diff = Math.abs(memoryFlightTime - redisTime);
                if (diff > 5000) { // 5秒以上の差異があれば警告
                    LogManager.getInstance().log("警告: UAV " + uav.getId() + " flightTime 差異が大きい - メモリ: "
                        + memoryFlightTime + "ms, Redis: " + redisTime + "ms (差: " + diff + "ms)");
                    isValid = false;
                }
            }

            return isValid;
        } catch (Exception e) {
            LogManager.getInstance().error("整合性検証エラー (UAV ID: " + uav.getId() + ")", e);
            return false;
        }
    }

    /**
     * フィールドの値を比較
     * @param uavId UAV ID
     * @param fieldName フィールド名
     * @param memoryValue メモリ上の値
     * @param redisValue Redis上の値
     * @return 一致する場合true
     */
    private boolean validateField(int uavId, String fieldName, Object memoryValue, Object redisValue) {
        if (memoryValue == null && redisValue == null) {
            return true;
        }

        if (memoryValue == null || redisValue == null) {
            LogManager.getInstance().log("不一致: UAV " + uavId + " " + fieldName
                + " - メモリ: " + memoryValue + ", Redis: " + redisValue);
            return false;
        }

        if (!memoryValue.equals(redisValue)) {
            // Integer と Long の比較に対応
            if (memoryValue instanceof Number && redisValue instanceof Number) {
                long memoryLong = ((Number) memoryValue).longValue();
                long redisLong = ((Number) redisValue).longValue();
                if (memoryLong != redisLong) {
                    LogManager.getInstance().log("不一致: UAV " + uavId + " " + fieldName
                        + " - メモリ: " + memoryValue + ", Redis: " + redisValue);
                    return false;
                }
            } else {
                LogManager.getInstance().log("不一致: UAV " + uavId + " " + fieldName
                    + " - メモリ: " + memoryValue + ", Redis: " + redisValue);
                return false;
            }
        }

        return true;
    }

    /**
     * Double型フィールドの値を比較（誤差を考慮）
     * @param uavId UAV ID
     * @param fieldName フィールド名
     * @param memoryValue メモリ上の値
     * @param redisValue Redis上の値
     * @return 一致する場合true
     */
    private boolean validateDoubleField(int uavId, String fieldName, double memoryValue, Object redisValue) {
        if (redisValue == null) {
            LogManager.getInstance().log("不一致: UAV " + uavId + " " + fieldName + " - Redis値がnull");
            return false;
        }

        double redisDouble = ((Number) redisValue).doubleValue();
        double diff = Math.abs(memoryValue - redisDouble);

        if (diff > 0.001) { // 誤差0.001以上で不一致
            LogManager.getInstance().log("不一致: UAV " + uavId + " " + fieldName
                + " - メモリ: " + memoryValue + ", Redis: " + redisDouble + " (差: " + diff + ")");
            return false;
        }

        return true;
    }

    /**
     * UAVの状態を文字列で取得
     * @param uav UAVオブジェクト
     * @return 状態文字列
     */
    private String getStatusString(Uav uav) {
        if (uav.isFlying()) {
            return "flying";
        } else if (uav.isWaiting()) {
            return "waiting";
        } else {
            return "idle";
        }
    }
}
