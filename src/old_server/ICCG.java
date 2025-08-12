package old_server;

import java.util.Arrays;

public class ICCG {

    public static int iccg(double[][] pressCoeff, double[] dataAll, double[] output, int n, int maxIter, double eps) {
        if (n <= 0) return 0;

        double[] r = new double[n];
        double[] p = new double[n];
        double[] y = new double[n];
        double[] r2 = new double[n];

        Arrays.fill(output, 0.0); // outputを初期化

        // 初期化
        for (int i = 0; i < n; i++) {
            output[i] = 0.0; // 出力の初期化
            r[i] = 0.0;      // r の初期化
            p[i] = 0.0;      // p の初期化
            y[i] = 0.0;      // y の初期化
            r2[i] = 0.0;     // r2 の初期化
        }

        double[] d = new double[n];
        double[][] L = new double[n][n];
        incompleteCholeskyDecomp2(pressCoeff, L, d, n);

        // rの初期化
        for (int i = 0; i < n; ++i) {
            double ax = 0.0;
            for (int j = 0; j < n; ++j) {
                ax += pressCoeff[i][j] * output[j];
            }
            r[i] = dataAll[i] - ax;
        }

        // 初期値をpに設定
        icRes(L, d, r, p, n);

        double rr0 = dot(r, p, n);
        double rr1;
        double alpha, beta;

        double e = 0.0;
        int k;
        for (k = 0; k < maxIter; ++k) {
            // pressCoeff行列とpベクトルの掛け算
            for (int i = 0; i < n; ++i) {
                y[i] = dot(pressCoeff[i], p, n); // pressCoeff[i]を直接渡す
            }


            alpha = rr0 / dot(p, y, n);

            for (int i = 0; i < n; ++i) {
                output[i] += alpha * p[i];
                r[i] -= alpha * y[i];
            }

            icRes(L, d, r, r2, n);
            rr1 = dot(r, r2, n);

            e = Math.sqrt(rr1);
            if (e < eps) {
                k++;
                break;
            }

            beta = rr1 / rr0;
            for (int i = 0; i < n; i++) {
                p[i] = r2[i] + beta * p[i];
            }

            rr0 = rr1;
        }

        maxIter = k;
        eps = e;

        return 1;
    }

    public static void icRes(double[][] L, double[] d, double[] r, double[] u, int n) {
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

    public static int incompleteCholeskyDecomp2(double[][] A, double[][] L, double[] d, int n) {
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

    public static double dot(double[] A, double[] B, int n) {
        double sum = 0.0;
        for (int i = 0; i < n; ++i) {
            sum += A[i] * B[i];
        }
        return sum;
    }

    public static void main(String[] args) {
        // テスト用のデータ
        double[][] pressCoeff = {
                {4.0, 1.0, 0.0},
                {1.0, 3.0, 1.0},
                {0.0, 1.0, 2.0}
        };
        double[] dataAll = {1.0, 2.0, 3.0};
        double[] output = new double[3];
        int maxIter = 100;
        double eps = 1e-6;

        int result = iccg(pressCoeff, dataAll, output, 3, maxIter, eps);

        if (result == 0) {
            System.out.println("ICCG成功:");
            System.out.println("出力: " + Arrays.toString(output));
            System.out.println("反復回数: " + maxIter);
            System.out.println("誤差: " + eps);
        } else {
            System.out.println("ICCG失敗");
        }
    }
}
