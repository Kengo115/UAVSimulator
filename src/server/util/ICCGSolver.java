package server.util;

import java.util.Arrays;

/**
 * ICCGアルゴリズムによる線形方程式ソルバーの実装
 */
public class ICCGSolver implements NumericalSolverService {

    /**
     * 線形方程式を解く
     * 
     * @param pressCoeff 圧力係数行列
     * @param dataAll 右辺ベクトル
     * @param output 解ベクトル
     * @param node ノード数
     * @param maxIter 最大反復回数
     * @param eps 収束判定閾値
     * @return 収束した場合は1、収束しなかった場合は0
     */
    @Override
    public int solve(double[][] pressCoeff, double[] dataAll, double[] output, int node, int maxIter, double eps) {
        if (node <= 0) return 0;

        double[] r = new double[node];
        double[] p = new double[node];
        double[] y = new double[node];
        double[] r2 = new double[node];

        Arrays.fill(output, 0.0); // outputを初期化

        // 初期化
        for (int i = 0; i < node; i++) {
            output[i] = 0.0; // 出力の初期化
            r[i] = 0.0;      // r の初期化
            p[i] = 0.0;      // p の初期化
            y[i] = 0.0;      // y の初期化
            r2[i] = 0.0;     // r2 の初期化
        }

        double[] d = new double[node];
        double[][] L = new double[node][node];
        incompleteCholeskyDecomp2(pressCoeff, L, d, node);

        // rの初期化
        for (int i = 0; i < node; ++i) {
            double ax = 0.0;
            for (int j = 0; j < node; ++j) {
                ax += pressCoeff[i][j] * output[j];
            }
            r[i] = dataAll[i] - ax;
        }

        // 初期値をpに設定
        icRes(L, d, r, p, node);

        double rr0 = dot(r, p, node);
        double rr1;
        double alpha, beta;

        double e = 0.0;
        int k;
        for (k = 0; k < maxIter; ++k) {
            // pressCoeff行列とpベクトルの掛け算
            for (int i = 0; i < node; ++i) {
                y[i] = dot(pressCoeff[i], p, node); // pressCoeff[i]を直接渡す
            }

            alpha = rr0 / dot(p, y, node);

            for (int i = 0; i < node; ++i) {
                output[i] += alpha * p[i];
                r[i] -= alpha * y[i];
            }

            icRes(L, d, r, r2, node);
            rr1 = dot(r, r2, node);

            e = Math.sqrt(rr1);
            if (e < eps) {
                k++;
                break;
            }

            beta = rr1 / rr0;
            for (int i = 0; i < node; i++) {
                p[i] = r2[i] + beta * p[i];
            }

            rr0 = rr1;
        }

        return 1;
    }

    /**
     * 残差を計算する
     * 
     * @param L L行列
     * @param d d配列
     * @param r 残差ベクトル
     * @param u 解ベクトル
     * @param n 次元
     */
    private void icRes(double[][] L, double[] d, double[] r, double[] u, int n) {
        double[] y = new double[n];
        for (int i = 0; i < n; ++i) {
            double rly = r[i];
            for (int j = 0; j < i; ++j) {
                rly -= L[i][j] * y[j];
            }
            y[i] = rly / L[i][i];
        }

        for (int i = n - 1; i >= 0; --i) {
            double lu = 0.0;
            for (int j = i + 1; j < n; ++j) {
                lu += L[j][i] * u[j];
            }
            u[i] = y[i] - d[i] * lu;
        }
    }

    /**
     * 不完全コレスキー分解を行う
     * 
     * @param A 行列
     * @param L L行列
     * @param d d配列
     * @param n 次元
     * @return 成功した場合は1、失敗した場合は0
     */
    private int incompleteCholeskyDecomp2(double[][] A, double[][] L, double[] d, int n) {
        if (n <= 0) return 0;

        L[0][0] = A[0][0];
        d[0] = 1.0 / L[0][0];

        for (int i = 1; i < n; ++i) {
            for (int j = 0; j <= i; ++j) {
                if (Math.abs(A[i][j]) < 1.0e-10) continue;

                double lld = A[i][j];
                for (int k = 0; k < j; ++k) {
                    lld -= L[i][k] * L[j][k] * d[k];
                }
                L[i][j] = lld;
            }

            d[i] = 1.0 / L[i][i];
        }

        return 1;
    }

    /**
     * ベクトルの内積を計算する
     * 
     * @param a ベクトルa
     * @param b ベクトルb
     * @return 内積
     */
    @Override
    public double dot(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; ++i) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /**
     * 特定の次元でベクトルの内積を計算する
     * 
     * @param a ベクトルa
     * @param b ベクトルb
     * @param n 次元
     * @return 内積
     */
    private double dot(double[] a, double[] b, int n) {
        double sum = 0.0;
        for (int i = 0; i < n; ++i) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /**
     * 行列とベクトルの積を計算する
     * 
     * @param A 行列
     * @param x ベクトル
     * @param result 結果を格納するベクトル
     */
    @Override
    public void matVecMult(double[][] A, double[] x, double[] result) {
        int n = A.length;
        for (int i = 0; i < n; i++) {
            result[i] = dot(A[i], x);
        }
    }

    /**
     * アルゴリズム名を取得する
     * 
     * @return アルゴリズム名
     */
    @Override
    public String getAlgorithmName() {
        return "ICCG";
    }
}
