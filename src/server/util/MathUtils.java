package server.util;

import java.util.Arrays;

/**
 * 数学的なユーティリティメソッドを提供するクラス
 */
public class MathUtils {

    /**
     * 小数部分と元の配列のインデックスを保持するためのクラス
     */
    private static class Pair implements Comparable<Pair> {
        double fractional;
        int index;

        Pair(double f, int i) {
            this.fractional = f;
            this.index = i;
        }

        @Override
        public int compareTo(Pair other) {
            return Double.compare(other.fractional, this.fractional);
        }
    }

    /**
     * 配列の合計値を保持しながら、各要素を整数に丸め込む
     * 小数部分の大きい順に切り上げることで、合計値を保持する
     * @param output 丸め込む配列
     */
    public static void roundWithConservation(double[] output) {
        int n = output.length;
        double sum_frac = 0.0;
        for (double val : output) {
            sum_frac += val;
        }
        long sum_int = Math.round(sum_frac);

        long[] output_floor = new long[n];
        double[] fractional_parts = new double[n];
        long sum_floor = 0;

        for (int i = 0; i < n; i++) {
            output_floor[i] = (long) Math.floor(output[i]);
            fractional_parts[i] = output[i] - output_floor[i];
            sum_floor += output_floor[i];
        }

        long diff = sum_int - sum_floor;

        Pair[] pairs = new Pair[n];
        for (int i = 0; i < n; i++) {
            pairs[i] = new Pair(fractional_parts[i], i);
        }

        Arrays.sort(pairs);

        for (int i = 0; i < diff; i++) {
            output_floor[pairs[i].index]++;
        }

        for (int i = 0; i < n; i++) {
            output[i] = output_floor[i];
        }
    }
}
