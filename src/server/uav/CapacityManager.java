package server.uav;

import item.Link;
import server.redis.LinkCapacityManager;

/**
 * UAVの容量管理を行うクラス
 * Phase 3a: メモリとRedisの二重書き込み
 */
public class CapacityManager {

    // Phase 3a: Redis容量管理
    private static LinkCapacityManager linkCapacityManager = new LinkCapacityManager();

    /**
     * 管の容量を更新する
     * Phase 3a: メモリとRedisの両方を更新（二重書き込み）
     * @param flyingUAV 飛行中のUAV配列
     * @param link リンク情報
     * @param node ノード数
     */
    public static void updateCapacity(int[][] flyingUAV, Link[][] link, int node) {
        // [既存] Capacityを初期値に戻す（メモリ）
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY) {
                    link[i][j].setCapacity(link[i][j].getInitCapacity());
                    link[j][i].setCapacity(link[j][i].getInitCapacity());
                }
            }
        }

        // [既存] 各リンクの初期容量から飛行中のUAV分を減少（メモリ）
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY && flyingUAV[i][j] > 0) {
                    double newCapacity = link[i][j].getCapacity() - flyingUAV[i][j];
                    link[i][j].setCapacity(Math.max(0, newCapacity));
                    link[j][i].setCapacity(Math.max(0, newCapacity));
                }
            }
        }

        // [新規] Phase 3a: Redisにも同じ内容を保存（二重書き込み）
        linkCapacityManager.updateAllCapacities(link, flyingUAV, node);
    }
}
