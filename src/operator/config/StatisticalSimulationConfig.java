package operator.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import shared.util.LogManager;

import java.io.File;
import java.io.IOException;

/**
 * Phase 8: 統計的シミュレーション設定ファイルローダー
 *
 * JSON形式の設定ファイルから統計的シミュレーションパラメータを読み込む
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StatisticalSimulationConfig {

    private static StatisticalSimulationConfig instance;
    private static final String DEFAULT_CONFIG_PATH = "config/statistical_params.json";

    // 基本設定
    @JsonProperty("seed")
    private long seed = 12345L;

    @JsonProperty("simulation_duration_hours")
    private double simulationDurationHours = 6.0;

    @JsonProperty("snapshot_interval_sec")
    private int snapshotIntervalSec = 10;

    // 到着設定
    @JsonProperty("arrival")
    private ArrivalSettings arrival = new ArrivalSettings();

    // 定常状態設定
    @JsonProperty("steady_state")
    private SteadyStateSettings steadyState = new SteadyStateSettings();

    // ウォームアップ設定
    @JsonProperty("warmup")
    private WarmupSettings warmup = new WarmupSettings();

    // 混雑イベント設定
    @JsonProperty("congestion_event")
    private CongestionEventSettings congestionEvent = new CongestionEventSettings();

    /**
     * 到着設定クラス
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ArrivalSettings {
        @JsonProperty("mean_interval_sec")
        private double meanIntervalSec = 7.7;

        @JsonProperty("uav_count_per_batch")
        private int uavCountPerBatch = 10;

        @JsonProperty("distribution")
        private String distribution = "exponential";

        public double getMeanIntervalSec() { return meanIntervalSec; }
        public int getUavCountPerBatch() { return uavCountPerBatch; }
        public String getDistribution() { return distribution; }
    }

    /**
     * 定常状態設定クラス
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SteadyStateSettings {
        @JsonProperty("target_load_percent")
        private double targetLoadPercent = 50.0;

        @JsonProperty("tolerance_percent")
        private double tolerancePercent = 5.0;

        @JsonProperty("observation_window_minutes")
        private int observationWindowMinutes = 5;

        public double getTargetLoadPercent() { return targetLoadPercent; }
        public double getTolerancePercent() { return tolerancePercent; }
        public int getObservationWindowMinutes() { return observationWindowMinutes; }
    }

    /**
     * ウォームアップ設定クラス
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WarmupSettings {
        @JsonProperty("duration_minutes")
        private int durationMinutes = 30;

        @JsonProperty("discard_data")
        private boolean discardData = true;

        public int getDurationMinutes() { return durationMinutes; }
        public boolean isDiscardData() { return discardData; }
    }

    /**
     * 混雑イベント設定クラス
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CongestionEventSettings {
        @JsonProperty("enabled")
        private boolean enabled = false;

        @JsonProperty("interval_minutes")
        private int intervalMinutes = 30;

        @JsonProperty("duration_minutes")
        private int durationMinutes = 10;

        @JsonProperty("target_load_percent")
        private double targetLoadPercent = 80.0;

        public boolean isEnabled() { return enabled; }
        public int getIntervalMinutes() { return intervalMinutes; }
        public int getDurationMinutes() { return durationMinutes; }
        public double getTargetLoadPercent() { return targetLoadPercent; }
    }

    private StatisticalSimulationConfig() {
    }

    /**
     * シングルトンインスタンスを取得
     */
    public static synchronized StatisticalSimulationConfig getInstance() {
        if (instance == null) {
            instance = new StatisticalSimulationConfig();
        }
        return instance;
    }

    /**
     * 設定ファイルを読み込む
     * @param filePath JSONファイルパス
     * @return 読み込み成功時true
     */
    public static boolean load(String filePath) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            File file = new File(filePath);

            if (!file.exists()) {
                LogManager.getInstance().error("Phase 8: 設定ファイルが見つかりません: " + filePath);
                return false;
            }

            instance = mapper.readValue(file, StatisticalSimulationConfig.class);
            LogManager.getInstance().log("Phase 8: 設定ファイル読み込み成功: " + filePath);
            instance.logSettings();
            return true;

        } catch (IOException e) {
            LogManager.getInstance().error("Phase 8: 設定ファイル読み込みエラー: " + filePath, e);
            return false;
        }
    }

    /**
     * デフォルトパスから設定ファイルを読み込む
     * @return 読み込み成功時true
     */
    public static boolean loadDefault() {
        return load(DEFAULT_CONFIG_PATH);
    }

    /**
     * 設定が読み込まれているかチェック
     */
    public static boolean isLoaded() {
        return instance != null;
    }

    /**
     * 設定をリセット
     */
    public static void reset() {
        instance = null;
        LogManager.getInstance().log("Phase 8: 設定をリセット");
    }

    /**
     * 現在の設定をログ出力
     */
    public void logSettings() {
        LogManager.getInstance().log("Phase 8 [統計的シミュレーション設定]:");
        LogManager.getInstance().log("  seed=" + seed);
        LogManager.getInstance().log("  simulation_duration_hours=" + simulationDurationHours);
        LogManager.getInstance().log("  snapshot_interval_sec=" + snapshotIntervalSec);
        LogManager.getInstance().log("  [Arrival]");
        LogManager.getInstance().log("    mean_interval_sec=" + arrival.meanIntervalSec);
        LogManager.getInstance().log("    uav_count_per_batch=" + arrival.uavCountPerBatch);
        LogManager.getInstance().log("    distribution=" + arrival.distribution);
        LogManager.getInstance().log("  [SteadyState]");
        LogManager.getInstance().log("    target_load_percent=" + steadyState.targetLoadPercent + "%");
        LogManager.getInstance().log("    tolerance_percent=" + steadyState.tolerancePercent + "%");
        LogManager.getInstance().log("    observation_window_minutes=" + steadyState.observationWindowMinutes);
        LogManager.getInstance().log("  [Warmup]");
        LogManager.getInstance().log("    duration_minutes=" + warmup.durationMinutes);
        LogManager.getInstance().log("    discard_data=" + warmup.discardData);
        LogManager.getInstance().log("  [CongestionEvent]");
        LogManager.getInstance().log("    enabled=" + congestionEvent.enabled);
        if (congestionEvent.enabled) {
            LogManager.getInstance().log("    interval_minutes=" + congestionEvent.intervalMinutes);
            LogManager.getInstance().log("    duration_minutes=" + congestionEvent.durationMinutes);
            LogManager.getInstance().log("    target_load_percent=" + congestionEvent.targetLoadPercent + "%");
        }
    }

    // Getters
    public long getSeed() { return seed; }
    public double getSimulationDurationHours() { return simulationDurationHours; }
    public int getSnapshotIntervalSec() { return snapshotIntervalSec; }
    public ArrivalSettings getArrival() { return arrival; }
    public SteadyStateSettings getSteadyState() { return steadyState; }
    public WarmupSettings getWarmup() { return warmup; }
    public CongestionEventSettings getCongestionEvent() { return congestionEvent; }

    // シミュレーション時間をミリ秒で取得
    public long getDurationMillis() {
        return (long) (simulationDurationHours * 60 * 60 * 1000);
    }

    // シミュレーション時間を秒で取得
    public long getDurationSeconds() {
        return (long) (simulationDurationHours * 60 * 60);
    }

    // ウォームアップ時間をミリ秒で取得
    public long getWarmupMillis() {
        return (long) warmup.durationMinutes * 60 * 1000;
    }

    // スナップショット間隔をミリ秒で取得
    public long getSnapshotIntervalMillis() {
        return (long) snapshotIntervalSec * 1000;
    }
}
