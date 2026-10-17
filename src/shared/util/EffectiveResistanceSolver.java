package shared.util;

import shared.network.TopologyFileReader.TopologyData;
import shared.network.TopologyFileReader.LinkInfo;

import java.io.*;
import java.util.Locale;

/**
 * グラフラプラシアンの擬似逆行列から等価抵抗 R_eff(s,d) を算出するソルバー
 *
 * R_eff_sim^(0)(s,d) = [L+]_ss - [L+]_sd
 *
 * L+（Moore-Penrose擬似逆行列）は正則化法で計算:
 *   L+ = (L + J/N)^{-1} - J/N
 *   J = 全要素1のN×N行列
 *
 * コンダクタンスは初期チューブ厚 D(0)=0.5 と lTubeLength から算出:
 *   W_e = D(0) / L_e = 0.5 / lTubeLength
 */
public class EffectiveResistanceSolver {

    private static final double INIT_THICKNESS = 0.5; // EPS初期チューブ厚

    private static double[][] reffMatrix = null; // N×N 等価抵抗行列
    private static int nodeCount = 0;

    /**
     * トポロジデータから L+ を計算し、全ペアの R_eff を算出して静的に保持する
     * @param topologyData 読み込み済みトポロジデータ
     */
    public static void compute(TopologyData topologyData) {
        int n = topologyData.nodeCount;
        nodeCount = n;

        // --- ラプラシアン行列の構築 ---
        double[][] L = new double[n][n];
        for (LinkInfo link : topologyData.links) {
            if (link.lTubeLength <= 0) continue;
            double w = INIT_THICKNESS / link.lTubeLength; // 初期コンダクタンス
            // 双方向リンク
            L[link.source][link.dest] -= w;
            L[link.dest][link.source] -= w;
            L[link.source][link.source] += w;
            L[link.dest][link.dest] += w;
        }

        // --- 正則化: L_reg = L + J/N（Jは全要素1のN×N行列）---
        double invN = 1.0 / n;
        double[][] L_reg = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                L_reg[i][j] = L[i][j] + invN;
            }
        }

        // --- L_reg の逆行列を Gauss-Jordan 法で算出 ---
        double[][] Linv = invertMatrix(L_reg, n);
        if (Linv == null) {
            System.err.println("EffectiveResistanceSolver: ラプラシアン正則化行列の逆行列計算に失敗しました");
            return;
        }

        // --- L+ = L_reg^{-1} - J/N ---
        double[][] Lplus = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                Lplus[i][j] = Linv[i][j] - invN;
            }
        }

        // --- R_eff(s,d) = L+[s][s] - L+[s][d] ---
        reffMatrix = new double[n][n];
        for (int s = 0; s < n; s++) {
            for (int d = 0; d < n; d++) {
                if (s != d) {
                    reffMatrix[s][d] = Lplus[s][s] - Lplus[s][d];
                }
            }
        }

        System.out.println("等価抵抗計算完了 (L+擬似逆行列, " + n + "×" + n + ")");
    }

    /**
     * Gauss-Jordan 法（部分ピボット）で N×N 行列の逆行列を算出する
     * @return 逆行列、または特異行列の場合 null
     */
    private static double[][] invertMatrix(double[][] A, int n) {
        double[][] aug = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) aug[i][j] = A[i][j];
            aug[i][n + i] = 1.0; // 単位行列を右に付加
        }

        for (int col = 0; col < n; col++) {
            // 部分ピボット選択
            int pivotRow = col;
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(aug[row][col]) > Math.abs(aug[pivotRow][col])) pivotRow = row;
            }
            double[] tmp = aug[col]; aug[col] = aug[pivotRow]; aug[pivotRow] = tmp;

            if (Math.abs(aug[col][col]) < 1e-12) return null; // 特異行列

            // ピボット行を正規化
            double pivot = aug[col][col];
            for (int j = 0; j < 2 * n; j++) aug[col][j] /= pivot;

            // 他の行から消去
            for (int row = 0; row < n; row++) {
                if (row != col) {
                    double factor = aug[row][col];
                    for (int j = 0; j < 2 * n; j++) aug[row][j] -= factor * aug[col][j];
                }
            }
        }

        double[][] result = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                result[i][j] = aug[i][n + j];
            }
        }
        return result;
    }

    // ===== アクセサ =====

    /** 事前計算済みの等価抵抗 R_eff(s,d) を返す */
    public static double getReff(int s, int d) {
        if (reffMatrix == null || s < 0 || d < 0 || s >= nodeCount || d >= nodeCount || s == d) return 0.0;
        return reffMatrix[s][d];
    }

    public static double[][] getReffMatrix()  { return reffMatrix; }
    public static boolean isInitialized()     { return reffMatrix != null; }

    /**
     * R_eff, R_eff×Q_max, p_limit_high, p_limit_low を
     * config/topology/p_limit/{small|large}_scale/ に出力する
     *
     * 出力列: s d R_eff R_eff*Q_max p_limit_high p_limit_low
     * R_eff は方向依存（非対称）のため s≠d の全ペアを出力する
     *
     * @param maxFlowMatrix 全ペア最大フロー行列（MaxFlowSolver.getMaxFlowMatrix()）
     * @param alpha         圧力上限の倍率係数（p_limit_high = α × Q_max × R_eff）
     * @param beta          圧力下限の係数    （p_limit_low  = β × p_limit_high）
     * @param topologyFilePath トポロジファイルパス（出力ファイル名の生成に使用）
     * @param isLargeScale  true=大規模, false=小規模
     */
    public static void outputPLimitFile(double[][] maxFlowMatrix,
                                        double alpha,
                                        double beta,
                                        String topologyFilePath,
                                        boolean isLargeScale) throws IOException {
        if (reffMatrix == null || maxFlowMatrix == null) {
            System.err.println("EffectiveResistanceSolver: p_limit出力スキップ（未初期化）");
            return;
        }

        String scaleDir = isLargeScale ? "large_scale" : "small_scale";
        String outDir = "config/topology/p_limit/" + scaleDir;
        new File(outDir).mkdirs();

        String baseName = new File(topologyFilePath).getName();
        if (baseName.contains(".")) baseName = baseName.substring(0, baseName.lastIndexOf('.'));
        String outputPath = outDir + "/" + baseName + "_p_limit.txt";

        try (PrintWriter writer = new PrintWriter(new FileWriter(outputPath))) {
            writer.printf("# alpha=%.4f  beta=%.4f%n", alpha, beta);
            writer.println("# s d R_eff R_eff*Q_max p_limit_high p_limit_low");

            int n = nodeCount;
            for (int s = 0; s < n; s++) {
                for (int d = 0; d < n; d++) {
                    if (s == d) continue;
                    double rEff     = reffMatrix[s][d];
                    double qMax     = maxFlowMatrix[s][d];
                    double reffQmax = rEff * qMax;
                    double pHigh    = alpha * reffQmax;
                    double pLow     = beta  * pHigh;
                    writer.printf(Locale.US, "%d %d %.6f %.6f %.6f %.6f%n",
                            s, d, rEff, reffQmax, pHigh, pLow);
                }
            }
        }

        System.out.println("p_limit ファイル出力完了 → " + outputPath);
    }
}
