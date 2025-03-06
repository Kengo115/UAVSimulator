
package server;
import java.util.Arrays;

public class BiCGSTAB {

    // dotメソッドの変更：ArrayList -> 配列
    public static double dot(double[] A, double[] B) {
        double result = 0.0;
        for (int i = 0; i < A.length; i++) {
            result += A[i] * B[i];
        }
        return result;
    }

    // matVecMultメソッドの変更：ArrayList -> 配列
    public static void matVecMult(double[][] A, double[] x, double[] result) {
        int n = A.length;
        for (int i = 0; i < n; i++) {
            result[i] = dot(A[i], x); // A[i]はAのi行目
        }
    }

    // BiCGSTABメソッドの変更：ArrayList -> 配列
    public static int BiCGSTAB(double[][] pressCoeff, double[] dataAll, double[] output, int node, int maxIter, double eps) {
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

    // BiCGSTABResメソッドの変更：ArrayList -> 配列
    public static void BiCGSTABRes(double[][] A, double[] x, double[] b, double[] r, int n) {
        double[] Ax = new double[n];
        matVecMult(A, x, Ax);
        for (int i = 0; i < n; i++) {
            r[i] = b[i] - Ax[i];
        }
    }

    // テスト用のmainメソッド
    public static void main(String[] args) {
        // 2次元配列の初期化
        double[][] pressCoeff = {
                {4.0, 1.0, 0.0},
                {1.0, 3.0, 1.0},
                {0.0, 1.0, 2.0}
        };

        double[] dataAll = {1.0, 2.0, 3.0};
        double[] output = new double[dataAll.length];

        int maxIter = 100;
        double eps = 1e-6;

        int result = BiCGSTAB(pressCoeff, dataAll, output, 3, maxIter, eps);

        if (result >= 0) {
            System.out.println("BiCGSTAB成功:");
            System.out.println("出力: " + Arrays.toString(output));
            System.out.println("反復回数: " + result);
        } else {
            System.out.println("BiCGSTAB失敗");
        }
    }
}
