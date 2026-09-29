package operator.route;

import shared.item.BeaconCluster;
import shared.item.Link;
import operator.controller.ServerController;
import shared.util.Link117DebugLogger;

/**
 * シグモイド+tanh型二分法圧力誘導EPS (Sigmoid Bisectional PG-EPS)
 * BisectionalPressureGuidedEPSRouteSearcherを継承し、
 * チューブ厚更新にシグモイド関数とtanhを組み合わせた手法を使用する
 */
public class SigmoidBisectionalPGEPSRouteSearcher extends BisectionalPressureGuidedEPSRouteSearcher {

    /**
     * コンストラクタ
     * @param serverController サーバーコントローラー
     * @param adjMatrix 隣接行列
     * @param link リンク情報
     * @param beaconCluster ビーコンクラスター
     * @param node ノード数
     */
    public SigmoidBisectionalPGEPSRouteSearcher(ServerController serverController, int[][] adjMatrix, Link[][] link, BeaconCluster beaconCluster, int node) {
        super(serverController, adjMatrix, link, beaconCluster, node);
    }

    /**
     * チューブ厚を更新する
     * sigmoid出力とdegeneracyEffectによるデルタ計算 + tanhによる容量制約
     * @param ct 現在の反復回数
     */
    @Override
    protected void doUpdateTubeThickness(int ct) {
        double degeneracyEffect = 0.1;
        // sigmoid出力とdegeneracyEffectを使用してデルタ計算
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    double deltaThickness = (Q_tubeFlow_sigmoidOutput[i][j] - (degeneracyEffect * link[i][j].getD_tubeThickness())) * DELTA_TIME;
                    D_tubeThickness_deltaT[i][j] = deltaThickness;
                }
            }
        }

        // tanhによる容量制約（+TANH_DELTAで境界問題を回避）
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    double oldThickness = link[i][j].getD_tubeThickness();
                    double capacity = link[i][j].getCapacity();
                    double flow = Math.abs(link[i][j].getQ_tubeFlow());
                    double tanhValue = Math.tanh((capacity - flow + TANH_DELTA) * coefficient_tanh);
                    double newThickness = oldThickness + D_tubeThickness_deltaT[i][j] * tanhValue;
                    link[i][j].setD_tubeThickness(newThickness);

                    // DEBUG: 117-123リンクの詳細ログ（専用ログファイル、100回に1回）
                    if ((i == 117 && j == 123) || (i == 123 && j == 117)) {
                        if (ct % 100 == 0 || ct < 10) {
                            Link117DebugLogger.getInstance().logEPSUpdate(
                                i, j, ct, capacity, link[i][j].getInitCapacity(),
                                flow, tanhValue, oldThickness, newThickness);
                        }
                    }
                }
            }
        }
    }

    /**
     * 経路記録のタグを取得する
     * @return 経路記録のタグ
     */
    @Override
    protected String getRouteRecordTag() {
        return "runUAVFlow_SigmoidBisectionalPGEPS";
    }

    /**
     * 残りの経路記録のタグを取得する
     * @return 残りの経路記録のタグ
     */
    @Override
    protected String getRemainingRouteRecordTag() {
        return "remainingFlow_SigmoidBisectionalPGEPS";
    }
}
