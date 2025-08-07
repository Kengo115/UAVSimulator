package server.util;

import java.util.Arrays;

/**
 * BiCGSTABアルゴリズムによる線形方程式ソルバーの実装
 */
public class BiCGSTABSolver implements NumericalSolverService {

    /**
     * ベクトルの内積を計算する
     * 
     * @param a ベクトルa
     * @param b ベクトルb
     * @return 内積
     */
    @Override
    public double dot(double[] a, double[] b) {
        double result = 0.0;
        for (int i = 0; i < a.length; i++) {
            result += a[i] * b[i];
        }
        return result;
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
            result[i] = dot(A[i], x); // A[i]はAのi行目
        }
    }

    /**
     * 線形方程式を解く
     * 
     * @param pressCoeff 圧力係数行列
     * @param dataAll 右辺ベクトル
     * @param output 解ベクトル
     * @param node ノード数
     * @param maxIter 最大反復回数
     * @param eps 収束判定閾値
     * @return 収束した場合は反復回数、収束しなかった場合は-1
     */
    @Override
    public int solve(double[][] pressCoeff, double[] dataAll, double[] output, int node, int maxIter, double eps) {
        int n = node;
        double[] r = new double[n];
        double[] p = new double[n];
        double[] v = new double[n];
        double[] s = new double[n];
        double[] t = new double[n];

        Arrays.fill(output, 0.0);

        BiCGSTABRes(pressCoeff, output, dataAll, r, n);
        double[] r0 = Arrays.copyOf(r, r.length);

        double rho = 1.0;
        double alpha = 1.0;
        double omega = 1.0;

        double res0 = Math.sqrt(dot(r, r));
        if (res0 < eps) {
            return 0;
        }

        for (int k = 0; k < maxIter; k++) {
            double rho1 = dot(r0, r);
            double beta = (rho1 / rho) * (alpha / omega);
            rho = rho1;

            for (int i = 0; i < n; i++) {
                p[i] = r[i] + beta * (p[i] - omega * v[i]);
            }

            matVecMult(pressCoeff, p, v);
            alpha = rho / dot(r0, v);

            for (int i = 0; i < n; i++) {
                s[i] = r[i] - alpha * v[i];
            }

            double res1 = Math.sqrt(dot(s, s));
            if (res1 < eps) {
                for (int i = 0; i < n; i++) {
                    output[i] += alpha * p[i];
                }
                return k + 1;
            }

            matVecMult(pressCoeff, s, t);
            omega = dot(t, s) / dot(t, t);

            for (int i = 0; i < n; i++) {
                output[i] += alpha * p[i] + omega * s[i];
                r[i] = s[i] - omega * t[i];
            }

            double res2 = Math.sqrt(dot(r, r));
            if (res2 < eps) {
                return k + 1;
            }
        }

        return -1;  // 収束しなかった場合
    }

    /**
     * 残差を計算する
     * 
     * @param A 行列
     * @param x 解ベクトル
     * @param b 右辺ベクトル
     * @param r 残差ベクトル
     * @param n 次元
     */
    private void BiCGSTABRes(double[][] A, double[] x, double[] b, double[] r, int n) {
        double[] Ax = new double[n];
        matVecMult(A, x, Ax);
        for (int i = 0; i < n; i++) {
            r[i] = b[i] - Ax[i];
        }
    }

    /**
     * アルゴリズム名を取得する
     * 
     * @return アルゴリズム名
     */
    @Override
    public String getAlgorithmName() {
        return "BiCGSTAB";
    }
}
