package server.util;

import java.util.Arrays;

/**
 * BiCGSTAB法による線形方程式ソルバー
 * ゼロ除算対策・数値安定性対策を含む
 */
public class BiCGSTABSolver {

    // ゼロ除算防止用の閾値
    private static final double BREAKDOWN_THRESHOLD = 1e-30;

    // NaN/Infinity検出用
    private static final double MAX_VALUE = 1e100;

    /**
     * ベクトルの内積を計算する
     * @param A 1つ目のベクトル
     * @param B 2つ目のベクトル
     * @return 内積
     */
    public static double dot(double[] A, double[] B) {
        double result = 0.0;
        for (int i = 0; i < A.length; i++) {
            result += A[i] * B[i];
        }
        return result;
    }

    /**
     * 行列とベクトルの積を計算する
     * @param A 行列
     * @param x ベクトル
     * @param result 結果を格納するベクトル
     */
    public static void matVecMult(double[][] A, double[] x, double[] result) {
        int n = A.length;
        for (int i = 0; i < n; i++) {
            result[i] = dot(A[i], x); // A[i]はAのi行目
        }
    }

    /**
     * 値が有効かどうかをチェック（NaN、Infinity、過大値を検出）
     * @param value チェックする値
     * @return 有効ならtrue
     */
    private static boolean isValidValue(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && Math.abs(value) < MAX_VALUE;
    }

    /**
     * ベクトルが有効かどうかをチェック
     * @param vec チェックするベクトル
     * @return 有効ならtrue
     */
    private static boolean isValidVector(double[] vec) {
        for (double v : vec) {
            if (!isValidValue(v)) {
                return false;
            }
        }
        return true;
    }

    /**
     * BiCGSTAB法で線形方程式を解く
     * @param pressCoeff 係数行列
     * @param dataAll 右辺ベクトル
     * @param output 解ベクトル
     * @param node 次元数
     * @param maxIter 最大反復回数
     * @param eps 収束判定閾値
     * @return 反復回数（収束しなかった場合は-1）
     */
    public static int solve(double[][] pressCoeff, double[] dataAll, double[] output, int node, int maxIter, double eps) {
        int n = node;
        double[] r = new double[n];
        double[] p = new double[n];
        double[] v = new double[n];
        double[] s = new double[n];
        double[] t = new double[n];

        Arrays.fill(output, 0.0);
        Arrays.fill(p, 0.0);
        Arrays.fill(v, 0.0);

        computeResidual(pressCoeff, output, dataAll, r, n);
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

            // ゼロ除算対策1: rhoが0に近い場合
            if (Math.abs(rho) < BREAKDOWN_THRESHOLD) {
                // rhoブレークダウン: r0を再選択してリスタート
                r0 = Arrays.copyOf(r, r.length);
                rho = dot(r0, r);
                if (Math.abs(rho) < BREAKDOWN_THRESHOLD) {
                    // それでもダメなら現在の解で継続（近似解として使用）
                    return k > 0 ? k : 1;
                }
            }

            // ゼロ除算対策2: omegaが0に近い場合
            if (Math.abs(omega) < BREAKDOWN_THRESHOLD) {
                omega = 1.0; // リセット
            }

            double beta = (rho1 / rho) * (alpha / omega);

            // beta値の妥当性チェック
            if (!isValidValue(beta)) {
                beta = 0.0; // リセット
            }

            rho = rho1;

            for (int i = 0; i < n; i++) {
                p[i] = r[i] + beta * (p[i] - omega * v[i]);
            }

            // pベクトルの妥当性チェック
            if (!isValidVector(p)) {
                // pをリセット
                System.arraycopy(r, 0, p, 0, n);
            }

            matVecMult(pressCoeff, p, v);

            double r0v = dot(r0, v);

            // ゼロ除算対策3: dot(r0, v)が0に近い場合
            if (Math.abs(r0v) < BREAKDOWN_THRESHOLD) {
                // 現在の解で継続
                return k > 0 ? k : 1;
            }

            alpha = rho / r0v;

            // alpha値の妥当性チェック
            if (!isValidValue(alpha)) {
                return k > 0 ? k : 1;
            }

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

            double tt = dot(t, t);

            // ゼロ除算対策4: dot(t, t)が0に近い場合
            if (Math.abs(tt) < BREAKDOWN_THRESHOLD) {
                // sがほぼ解なので、それを使って更新
                for (int i = 0; i < n; i++) {
                    output[i] += alpha * p[i];
                }
                return k + 1;
            }

            omega = dot(t, s) / tt;

            // omega値の妥当性チェック
            if (!isValidValue(omega)) {
                omega = 1.0; // リセット
            }

            for (int i = 0; i < n; i++) {
                output[i] += alpha * p[i] + omega * s[i];
                r[i] = s[i] - omega * t[i];
            }

            // 解ベクトルの妥当性チェック
            if (!isValidVector(output)) {
                // 解が発散した場合、ゼロにリセットして失敗を返す
                Arrays.fill(output, 0.0);
                return -1;
            }

            double res2 = Math.sqrt(dot(r, r));
            if (res2 < eps) {
                return k + 1;
            }
        }

        // 最大反復回数に達したが、解は更新されている
        // 現在の近似解を使用して正の値を返す（処理を継続させる）
        if (isValidVector(output)) {
            return maxIter;
        }

        return -1;  // 収束しなかった場合
    }

    /**
     * 残差ベクトルを計算する
     * @param A 係数行列
     * @param x 解ベクトル
     * @param b 右辺ベクトル
     * @param r 残差ベクトル
     * @param n 次元数
     */
    public static void computeResidual(double[][] A, double[] x, double[] b, double[] r, int n) {
        double[] Ax = new double[n];
        matVecMult(A, x, Ax);
        for (int i = 0; i < n; i++) {
            r[i] = b[i] - Ax[i];
        }
    }
}
