package server.uav;

import item.Link;

/**
 * UAVの容量管理を行うクラス
 */
public class CapacityManager {

    /**
     * 管の容量を更新する
     * @param flyingUAV 飛行中のUAV配列
     * @param link リンク情報
     * @param node ノード数
     */
    public static void updateCapacity(int[][] flyingUAV, Link[][] link, int node) {
        // Capacityを初期値に戻す
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY) {
                    link[i][j].setCapacity(link[i][j].getInitCapacity());
                    link[j][i].setCapacity(link[j][i].getInitCapacity());
                }
            }
        }
        
        // 各リンクの初期容量から飛行中のUAV分を減少
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY && flyingUAV[i][j] > 0) {
                    double newCapacity = link[i][j].getCapacity() - flyingUAV[i][j];
                    link[i][j].setCapacity(Math.max(0, newCapacity));
                    link[j][i].setCapacity(Math.max(0, newCapacity));
                }
            }
        }
    }
}
