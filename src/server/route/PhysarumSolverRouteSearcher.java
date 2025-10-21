package server.route;

import client.Client;
import item.BeaconCluster;
import item.Link;
import server.controller.ServerController;
import server.util.ICCGSolver;
import server.util.ResultOutputManager;

import java.io.IOException;

/**
 * PhysarumSolverアルゴリズムによる経路探索を行うクラス
 */
public class PhysarumSolverRouteSearcher extends AbstractPhysarumSolverRouteSearcher {

    /**
     * コンストラクタ
     * @param serverController サーバーコントローラー
     * @param adjMatrix 隣接行列
     * @param link リンク情報
     * @param beaconCluster ビーコンクラスター
     * @param node ノード数
     */
    public PhysarumSolverRouteSearcher(ServerController serverController, int[][] adjMatrix, Link[][] link, BeaconCluster beaconCluster, int node) {
        super(serverController, adjMatrix, link, beaconCluster, node);
    }

    /**
     * チューブ厚を更新する
     * PSでは容量制約を考慮せず、シグモイド関数の出力を使用
     * @param ct 現在の反復回数
     */
    @Override
    protected void updateTubeThickness(int ct) {
        // チューブ厚の更新 - PSでは容量制約を考慮せず
        double degeneracyEffect = 0.5;
        
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    // シグモイド関数の出力を使用
                    double deltaThickness = (Q_tubeFlow_sigmoidOutput[i][j] - (degeneracyEffect * link[i][j].getD_tubeThickness())) * DELTA_TIME;
                    D_tubeThickness_deltaT[i][j] = deltaThickness;
                    // 容量制約を考慮せずに直接更新
                    double newThickness = link[i][j].getD_tubeThickness() + deltaThickness;
                    link[i][j].setD_tubeThickness(newThickness);
                }
            }
        }
    }

    /**
     * 線形方程式を解く
     * PhysarumSolverではICCGを使用
     * @param pressCoeff 係数行列
     * @param dataAll 右辺ベクトル
     * @param output 解ベクトル
     * @param n 次元数
     * @param maxIter 最大反復回数
     * @param eps 収束判定閾値
     * @return 反復回数（収束しなかった場合は-1）
     */
    @Override
    protected int solvePressureEquation(double[][] pressCoeff, double[] dataAll, double[] output, int n, int maxIter, double eps) {
        // ICCG法で圧力を解く
        int result = ICCGSolver.solve(pressCoeff, dataAll, output, n, maxIter, eps);
        return result == 1 ? 1 : -1; // ICCGSolverは成功時に1、失敗時に0を返すので、-1に変換
    }

    /**
     * 経路記録のタグを取得する
     * @return 経路記録のタグ
     */
    @Override
    protected String getRouteRecordTag() {
        return "runUAVFlow";
    }

    /**
     * 残りの経路記録のタグを取得する
     * @return 残りの経路記録のタグ
     */
    @Override
    protected String getRemainingRouteRecordTag() {
        return "remainingFlow";
    }
}
