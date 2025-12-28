package server.redis;

import client.Client;
import client.ClientController;
import item.BeaconCluster;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import server.util.LogManager;

import java.util.Queue;

/**
 * UAVシミュレーションの統計情報をRedisに保存・管理するクラス
 * Phase 2: 非クリティカルな統計データの書き込み
 */
public class UAVStatisticsManager {

    private boolean redisEnabled = false;
    private RedissonClient client;

    /**
     * コンストラクタ
     */
    public UAVStatisticsManager() {
        try {
            RedisConnectionManager connectionManager = RedisConnectionManager.getInstance();
            if (connectionManager.isConnected()) {
                this.client = connectionManager.getClient();
                this.redisEnabled = true;
                LogManager.getInstance().log("UAVStatisticsManager: Redis統計機能が有効化されました");
            } else {
                LogManager.getInstance().log("UAVStatisticsManager: Redis未接続のため、統計機能は無効です");
            }
        } catch (Exception e) {
            LogManager.getInstance().error("UAVStatisticsManager初期化エラー", e);
            this.redisEnabled = false;
        }
    }

    /**
     * グローバル統計情報をRedisに保存する
     * @param flyingUavCount 飛行中のUAV数
     * @param waitingUavCount 待機中のUAV数
     * @param totalElapsedTime 経過時間（ミリ秒）
     */
    public void saveGlobalStats(int flyingUavCount, int waitingUavCount, long totalElapsedTime) {
        if (!redisEnabled) {
            return;
        }

        try {
            String key = "stats:global";
            RMap<String, Object> map = client.getMap(key);

            map.put("flyingUavCount", flyingUavCount);
            map.put("waitingUavCount", waitingUavCount);
            map.put("totalElapsedTime", totalElapsedTime);
            map.put("lastUpdateTime", System.currentTimeMillis());

        } catch (Exception e) {
            LogManager.getInstance().error("グローバル統計保存エラー", e);
        }
    }

    /**
     * クライアント統計情報をRedisに保存する
     * @param clientController クライアントコントローラー
     */
    public void saveClientStats(ClientController clientController) {
        if (!redisEnabled) {
            return;
        }

        try {
            for (Client client : clientController.getClientList()) {
                String key = "stats:client:" + client.getId();
                RMap<String, Object> map = this.client.getMap(key);

                map.put("clientId", client.getId());
                map.put("finishedUavCount", client.getFinishFlyingCounter());
                map.put("totalRequestedUavs", (int) client.getFlow().getTheNumberOfUAV());
                map.put("sourceBeaconId", client.getFlow().getSource().getId());
                map.put("destinationBeaconId", client.getFlow().getDestination().getId());
                map.put("lastUpdateTime", System.currentTimeMillis());
            }
        } catch (Exception e) {
            LogManager.getInstance().error("クライアント統計保存エラー", e);
        }
    }

    /**
     * ビーコン統計情報をRedisに保存する
     * @param beaconCluster ビーコンクラスター
     * @param nodeCount ノード数
     */
    public void saveBeaconStats(BeaconCluster beaconCluster, int nodeCount) {
        if (!redisEnabled) {
            return;
        }

        try {
            for (int i = 0; i < nodeCount; i++) {
                String key = "stats:beacon:" + i;
                RMap<String, Object> map = client.getMap(key);

                map.put("beaconId", i);
                map.put("waitingUavCount", beaconCluster.getBeacon(i).getWaitingUavCount());
                map.put("lastUpdateTime", System.currentTimeMillis());
            }
        } catch (Exception e) {
            LogManager.getInstance().error("ビーコン統計保存エラー", e);
        }
    }

    /**
     * 全ての統計情報を一括でRedisに保存する
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param waitingUavQueue 待機中のUAVキュー
     * @param clientController クライアントコントローラー
     * @param beaconCluster ビーコンクラスター
     * @param nodeCount ノード数
     */
    public void saveAllStats(Queue<?> flyingUavQueue, Queue<?> waitingUavQueue,
                            ClientController clientController, BeaconCluster beaconCluster,
                            int nodeCount) {
        if (!redisEnabled) {
            return;
        }

        try {
            // グローバル統計を保存
            int flyingCount = flyingUavQueue.size();
            int waitingCount = waitingUavQueue.size();
            long elapsedTime = clientController.getFlightTime();
            saveGlobalStats(flyingCount, waitingCount, elapsedTime);

            // クライアント統計を保存
            saveClientStats(clientController);

            // ビーコン統計を保存
            saveBeaconStats(beaconCluster, nodeCount);

        } catch (Exception e) {
            LogManager.getInstance().error("統計情報一括保存エラー", e);
        }
    }

    /**
     * Redis統計機能が有効かどうかを確認する
     * @return 有効な場合true
     */
    public boolean isEnabled() {
        return redisEnabled;
    }

    /**
     * 指定されたキーの統計情報をクリアする
     * @param keyPattern キーパターン（例: "stats:*"）
     */
    public void clearStats(String keyPattern) {
        if (!redisEnabled) {
            return;
        }

        try {
            Iterable<String> keys = client.getKeys().getKeysByPattern(keyPattern);
            for (String key : keys) {
                client.getMap(key).delete();
            }
            LogManager.getInstance().log("統計情報をクリアしました: " + keyPattern);
        } catch (Exception e) {
            LogManager.getInstance().error("統計情報クリアエラー", e);
        }
    }
}
