package server.scheduler;

import server.util.LogManager;

import java.util.Random;

/**
 * Phase 8: 指数分布に基づく間隔生成器
 *
 * ポアソン過程のUAV到着をシミュレートするため、
 * 到着間隔を指数分布からサンプリングする。
 *
 * 指数分布: f(x) = λ * e^(-λx)
 * 平均 = 1/λ = meanIntervalSec
 */
public class ExponentialIntervalGenerator {

    private final Random random;
    private final double meanIntervalSec;
    private long sampleCount = 0;
    private double sumIntervals = 0.0;

    /**
     * コンストラクタ
     * @param seed 乱数シード
     * @param meanIntervalSec 平均間隔（秒）
     */
    public ExponentialIntervalGenerator(long seed, double meanIntervalSec) {
        this.random = new Random(seed);
        this.meanIntervalSec = meanIntervalSec;
        LogManager.getInstance().log("Phase 8: ExponentialIntervalGenerator初期化 (seed=" + seed +
                ", mean=" + meanIntervalSec + "s)");
    }

    /**
     * 次の間隔をサンプリング
     *
     * 逆関数法: X = -mean * ln(1 - U), U ~ Uniform(0,1)
     * または: X = -mean * ln(U) （Uが0を含まない場合）
     *
     * @return 次の間隔（秒）
     */
    public double nextIntervalSec() {
        // 0を避けるため、nextDouble()が0を返す場合の対策
        double u;
        do {
            u = random.nextDouble();
        } while (u == 0.0);

        double interval = -meanIntervalSec * Math.log(u);

        // 統計を記録
        sampleCount++;
        sumIntervals += interval;

        return interval;
    }

    /**
     * 次の間隔をミリ秒で取得
     * @return 次の間隔（ミリ秒）
     */
    public long nextIntervalMillis() {
        return (long) (nextIntervalSec() * 1000);
    }

    /**
     * 平均間隔を取得
     * @return 設定された平均間隔（秒）
     */
    public double getMeanIntervalSec() {
        return meanIntervalSec;
    }

    /**
     * 実測平均を取得
     * @return サンプリングされた間隔の実測平均（秒）
     */
    public double getObservedMeanSec() {
        return sampleCount > 0 ? sumIntervals / sampleCount : 0.0;
    }

    /**
     * サンプル数を取得
     * @return サンプリング回数
     */
    public long getSampleCount() {
        return sampleCount;
    }

    /**
     * 統計情報をログ出力
     */
    public void logStatistics() {
        LogManager.getInstance().log(String.format(
                "Phase 8: ExponentialIntervalGenerator統計 - サンプル数=%d, 設定平均=%.2fs, 実測平均=%.2fs",
                sampleCount, meanIntervalSec, getObservedMeanSec()));
    }

    /**
     * 統計をリセット
     */
    public void resetStatistics() {
        sampleCount = 0;
        sumIntervals = 0.0;
    }
}
