package server.util;

import item.Link;

/**
 * EPSセーブポイントクラス
 * Extended Physarum Solver (EPS) の状態を保存・復元するためのユーティリティクラス
 * 
 * このクラスは、EPSの全状態（リンクのチューブ厚、フロー値、圧力係数等）を
 * 深いコピーで保存し、必要に応じて復元する機能を提供します。
 */
public class EPSSavePoint {
    
    // 保存されるEPS状態
    private Link[][] savedLink;
    private double[][] savedPressureCoefficient;
    private double[] savedP_tubePressure;
    private double[] savedQ_Kirchhoff;
    private double[][] savedD_tubeThickness_deltaT;
    private double[][] savedQ_tubeFlow_sigmoidOutput;
    
    // ネットワーク情報
    private int nodeCount;
    private static final double INF = Double.POSITIVE_INFINITY;

    /**
     * コンストラクタ
     * @param nodeCount ノード数
     */
    public EPSSavePoint(int nodeCount) {
        this.nodeCount = nodeCount;
    }

    /**
     * 現在のEPS状態を保存する
     * @param link リンク配列
     * @param pressureCoefficient 圧力係数配列
     * @param P_tubePressure チューブ圧力配列
     * @param Q_Kirchhoff キルヒホッフの法則用流量配列
     * @param D_tubeThickness_deltaT チューブ厚変化量配列
     * @param Q_tubeFlow_sigmoidOutput シグモイド関数出力配列
     */
    public void saveEPSState(Link[][] link, 
                           double[][] pressureCoefficient,
                           double[] P_tubePressure,
                           double[] Q_Kirchhoff,
                           double[][] D_tubeThickness_deltaT,
                           double[][] Q_tubeFlow_sigmoidOutput) {
        
        LogManager.getInstance().log("EPSSavePoint: Saving EPS state");

        // Link配列の深いコピー
        savedLink = new Link[nodeCount][nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            for (int j = 0; j < nodeCount; j++) {
                if (link[i][j] != null && link[i][j].getL_tubeLength() != INF) {
                    savedLink[i][j] = new Link();
                    savedLink[i][j].setDistance(link[i][j].getDistance());
                    savedLink[i][j].setCapacity(link[i][j].getCapacity());
                    savedLink[i][j].setL_tubeLength(link[i][j].getL_tubeLength());
                    savedLink[i][j].setD_tubeThickness(link[i][j].getD_tubeThickness());
                    savedLink[i][j].setQ_tubeFlow(link[i][j].getQ_tubeFlow());
                } else {
                    savedLink[i][j] = link[i][j]; // null またはINFの場合はそのまま
                }
            }
        }

        // 圧力係数配列の深いコピー
        savedPressureCoefficient = new double[nodeCount][nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            for (int j = 0; j < nodeCount; j++) {
                savedPressureCoefficient[i][j] = pressureCoefficient[i][j];
            }
        }

        // その他の配列の深いコピー
        savedP_tubePressure = new double[nodeCount];
        savedQ_Kirchhoff = new double[nodeCount];
        savedD_tubeThickness_deltaT = new double[nodeCount][nodeCount];
        savedQ_tubeFlow_sigmoidOutput = new double[nodeCount][nodeCount];

        for (int i = 0; i < nodeCount; i++) {
            savedP_tubePressure[i] = P_tubePressure[i];
            savedQ_Kirchhoff[i] = Q_Kirchhoff[i];
            for (int j = 0; j < nodeCount; j++) {
                savedD_tubeThickness_deltaT[i][j] = D_tubeThickness_deltaT[i][j];
                savedQ_tubeFlow_sigmoidOutput[i][j] = Q_tubeFlow_sigmoidOutput[i][j];
            }
        }

        LogManager.getInstance().log("EPSSavePoint: EPS state saved successfully (nodes: " + nodeCount + ")");
    }

    /**
     * 保存されたEPS状態を復元する
     * @param link リンク配列（復元先）
     * @param pressureCoefficient 圧力係数配列（復元先）
     * @param P_tubePressure チューブ圧力配列（復元先）
     * @param Q_Kirchhoff キルヒホッフの法則用流量配列（復元先）
     * @param D_tubeThickness_deltaT チューブ厚変化量配列（復元先）
     * @param Q_tubeFlow_sigmoidOutput シグモイド関数出力配列（復元先）
     */
    public void restoreEPSState(Link[][] link, 
                              double[][] pressureCoefficient,
                              double[] P_tubePressure,
                              double[] Q_Kirchhoff,
                              double[][] D_tubeThickness_deltaT,
                              double[][] Q_tubeFlow_sigmoidOutput) {
        
        if (!isAvailable()) {
            LogManager.getInstance().log("EPSSavePoint: No savepoint available for restoration");
            return;
        }

        LogManager.getInstance().log("EPSSavePoint: Restoring EPS state from savepoint");

        // Link配列の復元
        for (int i = 0; i < nodeCount; i++) {
            for (int j = 0; j < nodeCount; j++) {
                if (savedLink[i][j] != null && savedLink[i][j].getL_tubeLength() != INF) {
                    link[i][j].setD_tubeThickness(savedLink[i][j].getD_tubeThickness());
                    link[i][j].setQ_tubeFlow(savedLink[i][j].getQ_tubeFlow());
                }
                // INFやnullの場合は復元しない（元の状態を保持）
            }
        }

        // 圧力係数配列の復元
        for (int i = 0; i < nodeCount; i++) {
            for (int j = 0; j < nodeCount; j++) {
                pressureCoefficient[i][j] = savedPressureCoefficient[i][j];
            }
        }

        // その他の配列の復元
        for (int i = 0; i < nodeCount; i++) {
            P_tubePressure[i] = savedP_tubePressure[i];
            Q_Kirchhoff[i] = savedQ_Kirchhoff[i];
            for (int j = 0; j < nodeCount; j++) {
                D_tubeThickness_deltaT[i][j] = savedD_tubeThickness_deltaT[i][j];
                Q_tubeFlow_sigmoidOutput[i][j] = savedQ_tubeFlow_sigmoidOutput[i][j];
            }
        }

        LogManager.getInstance().log("EPSSavePoint: EPS state restored successfully");
    }

    /**
     * セーブポイントが存在するかチェックする
     * @return セーブポイントが存在する場合true
     */
    public boolean isAvailable() {
        return savedLink != null;
    }

    /**
     * セーブポイントをクリアする
     */
    public void clear() {
        savedLink = null;
        savedPressureCoefficient = null;
        savedP_tubePressure = null;
        savedQ_Kirchhoff = null;
        savedD_tubeThickness_deltaT = null;
        savedQ_tubeFlow_sigmoidOutput = null;
        LogManager.getInstance().log("EPSSavePoint: Savepoint cleared");
    }

    /**
     * セーブポイントの統計情報を取得する
     * @return 統計情報の文字列
     */
    public String getStatistics() {
        if (!isAvailable()) {
            return "No savepoint available";
        }

        int linkCount = 0;
        double totalThickness = 0.0;
        double totalFlow = 0.0;

        for (int i = 0; i < nodeCount; i++) {
            for (int j = 0; j < nodeCount; j++) {
                if (savedLink[i][j] != null && savedLink[i][j].getL_tubeLength() != INF) {
                    linkCount++;
                    totalThickness += savedLink[i][j].getD_tubeThickness();
                    totalFlow += Math.abs(savedLink[i][j].getQ_tubeFlow());
                }
            }
        }

        return String.format("Savepoint stats: %d links, avg thickness: %.4f, total flow: %.2f", 
                           linkCount, 
                           linkCount > 0 ? totalThickness / linkCount : 0.0, 
                           totalFlow);
    }
}
