package operator.route;

import shared.client.Client;
import shared.item.BeaconCluster;
import shared.item.Link;
import operator.controller.ServerController;
import shared.util.BiCGSTABSolver;
import shared.util.Link117DebugLogger;
import shared.util.ResultOutputManager;

import java.io.IOException;

/**
 * ExtendedPhysarumSolverアルゴリズムによる経路探索を行うクラス
 * 容量制約を考慮した経路探索を行う
 */
public class ExtendedPhysarumSolverRouteSearcher extends AbstractPhysarumSolverRouteSearcher {

    /** 容量0で一時的に切断されたリンク数をカウント */
    protected int disconnectedLinkCount = 0;

    /**
     * コンストラクタ
     * @param serverController サーバーコントローラー
     * @param adjMatrix 隣接行列
     * @param link リンク情報
     * @param beaconCluster ビーコンクラスター
     * @param node ノード数
     */
    public ExtendedPhysarumSolverRouteSearcher(ServerController serverController, int[][] adjMatrix, Link[][] link, BeaconCluster beaconCluster, int node) {
        super(serverController, adjMatrix, link, beaconCluster, node);
    }

    /**
     * チューブ厚を更新する
     * ExtendedPhysarumSolverでは容量制約を考慮
     * @param ct 現在の反復回数
     */
    @Override
    protected void updateTubeThickness(int ct) {
        // チューブ厚の更新 - ExtendedPhysarumSolverでは容量制約を考慮
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    // 容量制約を考慮した更新式
                    double deltaThickness = (Math.abs(link[i][j].getQ_tubeFlow()) - link[i][j].getD_tubeThickness()) * DELTA_TIME;
                    D_tubeThickness_deltaT[i][j] = deltaThickness;
                }
            }
        }

        // ExtendedPhysarumSolverの特徴: 容量制約を考慮したチューブ厚の更新
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    // tanhを使用して容量制約を考慮
                    double oldThickness = link[i][j].getD_tubeThickness();
                    double capacity = link[i][j].getCapacity();
                    double flow = Math.abs(link[i][j].getQ_tubeFlow());
                    double tanhValue = Math.tanh((capacity - flow) * coefficient_tanh);
                    double newThickness = oldThickness + (D_tubeThickness_deltaT[i][j]) * tanhValue;
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
     * 追加のプロット処理を行う
     * @param client クライアント
     * @param ct 現在の反復回数
     * @throws IOException 入出力例外
     */
    @Override
    protected void additionalPlotting(Client client, int ct) throws IOException {
        if (ct % PLOT_2 == 0 || ct == 999) { // 999は最後のループの前
            ResultOutputManager.outputRouteToExcel(client, ct, link, serverController.getRunCounter());
        }
    }

    /**
     * 線形方程式を解く
     * ExtendedPhysarumSolverではBiCGSTABを使用
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
        // BiCGSTAB法で圧力を解く
        return BiCGSTABSolver.solve(pressCoeff, dataAll, output, n, maxIter, eps);
    }

    /**
     * 経路記録のタグを取得する
     * @return 経路記録のタグ
     */
    @Override
    protected String getRouteRecordTag() {
        return "runUAVFlow_EPS";
    }

    /**
     * 残りの経路記録のタグを取得する
     * @return 残りの経路記録のタグ
     */
    @Override
    protected String getRemainingRouteRecordTag() {
        return "remainingFlow_EPS";
    }

    /**
     * 容量0のリンクを一時的に切断する
     * EPSが容量0のリンクを選択しないようにするため、L_tubeLengthをINFに設定
     */
    protected void disconnectZeroCapacityLinks() {
        disconnectedLinkCount = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                // 元々接続があるリンク（initL_tubeLength != INF）で、現在の容量が0以下の場合
                if (link[i][j].getInitL_tubeLength() != INF &&
                    link[i][j].getInitL_tubeLength() > 0 &&
                    link[i][j].getCapacity() <= 0) {
                    // L_tubeLengthをINFに設定して切断
                    link[i][j].setL_tubeLength(INF);
                    disconnectedLinkCount++;

                    // DEBUG: 117-123リンクの場合はログ出力
                    if ((i == 117 && j == 123) || (i == 123 && j == 117)) {
                        Link117DebugLogger.getInstance().log("DISCONNECT",
                            String.format("link=%d-%d disconnected (capacity=%.2f)", i, j, link[i][j].getCapacity()));
                    }
                }
            }
        }
        if (disconnectedLinkCount > 0) {
            shared.util.LogManager.getInstance().log(
                "EPS: " + disconnectedLinkCount + " links disconnected due to zero capacity");
        }
    }

    /**
     * 一時的に切断したリンクを復元する
     * L_tubeLengthをinitL_tubeLengthに戻す
     */
    protected void restoreDisconnectedLinks() {
        int restoredCount = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                // initL_tubeLengthが設定されていて、現在INFになっている場合は復元
                if (link[i][j].getInitL_tubeLength() != INF &&
                    link[i][j].getInitL_tubeLength() > 0 &&
                    link[i][j].getL_tubeLength() == INF) {
                    link[i][j].setL_tubeLength(link[i][j].getInitL_tubeLength());
                    restoredCount++;

                    // DEBUG: 117-123リンクの場合はログ出力
                    if ((i == 117 && j == 123) || (i == 123 && j == 117)) {
                        Link117DebugLogger.getInstance().log("RESTORE",
                            String.format("link=%d-%d restored (L_tubeLength=%.2f)",
                                i, j, link[i][j].getL_tubeLength()));
                    }
                }
            }
        }
        if (restoredCount > 0) {
            shared.util.LogManager.getInstance().log(
                "EPS: " + restoredCount + " links restored after EPS calculation");
        }
    }
}
