package server.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import server.util.LogManager;

import java.io.File;
import java.io.IOException;

/**
 * Phase 7-7: シミュレーション設定ファイルローダー
 *
 * JSON形式の設定ファイルからシミュレーションパラメータを読み込む
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SimulationConfig {

    private static SimulationConfig instance;
    private static final String DEFAULT_CONFIG_PATH = "config/simulation_params.json";

    // 基本設定
    @JsonProperty("seed")
    private long seed = 12345L;

    @JsonProperty("duration_minutes")
    private int durationMinutes = 60;

    @JsonProperty("snapshot_interval_sec")
    private int snapshotIntervalSec = 10;

    // 3フェーズ設定
    @JsonProperty("phases")
    private PhaseSettings phases = new PhaseSettings();

    // トポロジ設定（オプション）
    @JsonProperty("topology")
    private String topologyPath = null;

    // 経路探索手法（オプション）
    @JsonProperty("route_search_method")
    private String routeSearchMethod = null;

    /**
     * 4フェーズ設定クラス
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PhaseSettings {
        @JsonProperty("phase1_generation")
        private Phase1Settings phase1 = new Phase1Settings();

        @JsonProperty("phase2_stable")
        private Phase2Settings phase2 = new Phase2Settings();

        @JsonProperty("phase3_congestion")
        private Phase3Settings phase3 = new Phase3Settings();

        @JsonProperty("phase4_recovery")
        private Phase4Settings phase4 = new Phase4Settings();

        public Phase1Settings getPhase1() { return phase1; }
        public Phase2Settings getPhase2() { return phase2; }
        public Phase3Settings getPhase3() { return phase3; }
        public Phase4Settings getPhase4() { return phase4; }
    }

    /**
     * Phase 1: 生成期設定
     * 遷移条件: 混雑率≥30%かつ60秒経過
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Phase1Settings {
        @JsonProperty("min_uav_count")
        private int minUavCount = 1;

        @JsonProperty("max_uav_count")
        private int maxUavCount = 5;

        @JsonProperty("min_interval_sec")
        private double minIntervalSec = 1.0;

        @JsonProperty("max_interval_sec")
        private double maxIntervalSec = 3.0;

        @JsonProperty("congestion_threshold_percent")
        private double congestionThresholdPercent = 30.0;

        @JsonProperty("min_duration_sec")
        private int minDurationSec = 60;

        public int getMinUavCount() { return minUavCount; }
        public int getMaxUavCount() { return maxUavCount; }
        public double getMinIntervalSec() { return minIntervalSec; }
        public double getMaxIntervalSec() { return maxIntervalSec; }
        public double getCongestionThresholdPercent() { return congestionThresholdPercent; }
        public int getMinDurationSec() { return minDurationSec; }
    }

    /**
     * Phase 2: 安定期設定
     * 遷移条件: 30分経過
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Phase2Settings {
        @JsonProperty("min_uav_count")
        private int minUavCount = 1;

        @JsonProperty("max_uav_count")
        private int maxUavCount = 5;

        @JsonProperty("duration_minutes")
        private int durationMinutes = 30;

        @JsonProperty("dynamic_interval_enabled")
        private boolean dynamicIntervalEnabled = true;

        @JsonProperty("target_congestion_percent")
        private double targetCongestionPercent = 50.0;

        @JsonProperty("min_interval_sec")
        private double minIntervalSec = 1.0;

        @JsonProperty("max_interval_sec")
        private double maxIntervalSec = 10.0;

        public int getMinUavCount() { return minUavCount; }
        public int getMaxUavCount() { return maxUavCount; }
        public int getDurationMinutes() { return durationMinutes; }
        public boolean isDynamicIntervalEnabled() { return dynamicIntervalEnabled; }
        public double getTargetCongestionPercent() { return targetCongestionPercent; }
        public double getMinIntervalSec() { return minIntervalSec; }
        public double getMaxIntervalSec() { return maxIntervalSec; }
    }

    /**
     * Phase 3: 混雑期設定
     * 遷移条件: 指定時間経過
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Phase3Settings {
        @JsonProperty("min_uav_count")
        private int minUavCount = 5;

        @JsonProperty("max_uav_count")
        private int maxUavCount = 10;

        @JsonProperty("duration_minutes")
        private int durationMinutes = 5;

        @JsonProperty("min_interval_sec")
        private double minIntervalSec = 1.0;

        @JsonProperty("max_interval_sec")
        private double maxIntervalSec = 2.0;

        public int getMinUavCount() { return minUavCount; }
        public int getMaxUavCount() { return maxUavCount; }
        public int getDurationMinutes() { return durationMinutes; }
        public double getMinIntervalSec() { return minIntervalSec; }
        public double getMaxIntervalSec() { return maxIntervalSec; }
    }

    /**
     * Phase 4: 回復期設定（安定期に戻す）
     * 遷移条件: シミュレーション終了まで
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Phase4Settings {
        @JsonProperty("min_uav_count")
        private int minUavCount = 1;

        @JsonProperty("max_uav_count")
        private int maxUavCount = 5;

        @JsonProperty("dynamic_interval_enabled")
        private boolean dynamicIntervalEnabled = true;

        @JsonProperty("target_congestion_percent")
        private double targetCongestionPercent = 50.0;

        @JsonProperty("min_interval_sec")
        private double minIntervalSec = 1.0;

        @JsonProperty("max_interval_sec")
        private double maxIntervalSec = 10.0;

        public int getMinUavCount() { return minUavCount; }
        public int getMaxUavCount() { return maxUavCount; }
        public boolean isDynamicIntervalEnabled() { return dynamicIntervalEnabled; }
        public double getTargetCongestionPercent() { return targetCongestionPercent; }
        public double getMinIntervalSec() { return minIntervalSec; }
        public double getMaxIntervalSec() { return maxIntervalSec; }
    }

    private SimulationConfig() {
    }

    /**
     * シングルトンインスタンスを取得
     */
    public static synchronized SimulationConfig getInstance() {
        if (instance == null) {
            instance = new SimulationConfig();
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
                LogManager.getInstance().error("Phase 7-7: 設定ファイルが見つかりません: " + filePath);
                return false;
            }

            instance = mapper.readValue(file, SimulationConfig.class);
            LogManager.getInstance().log("Phase 7-7: 設定ファイル読み込み成功: " + filePath);
            instance.logSettings();
            return true;

        } catch (IOException e) {
            LogManager.getInstance().error("Phase 7-7: 設定ファイル読み込みエラー: " + filePath, e);
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
     * 設定をリセット（デフォルト値に戻す）
     */
    public static void reset() {
        instance = new SimulationConfig();
        LogManager.getInstance().log("Phase 7-7: 設定をデフォルト値にリセット");
    }

    /**
     * 現在の設定をログ出力
     */
    public void logSettings() {
        LogManager.getInstance().log("Phase 7-7 [設定内容]:");
        LogManager.getInstance().log("  seed=" + seed);
        LogManager.getInstance().log("  duration_minutes=" + durationMinutes);
        LogManager.getInstance().log("  snapshot_interval_sec=" + snapshotIntervalSec);
        if (topologyPath != null) {
            LogManager.getInstance().log("  topology=" + topologyPath);
        }
        if (routeSearchMethod != null) {
            LogManager.getInstance().log("  route_search_method=" + routeSearchMethod);
        }
        LogManager.getInstance().log("  Phase1: UAV=" + phases.phase1.minUavCount + "-" + phases.phase1.maxUavCount +
                ", interval=" + phases.phase1.minIntervalSec + "-" + phases.phase1.maxIntervalSec + "s" +
                ", congestion>=" + phases.phase1.congestionThresholdPercent + "%" +
                ", minDuration=" + phases.phase1.minDurationSec + "s");
        LogManager.getInstance().log("  Phase2: UAV=" + phases.phase2.minUavCount + "-" + phases.phase2.maxUavCount +
                ", duration=" + phases.phase2.durationMinutes + "min" +
                ", dynamicInterval=" + phases.phase2.dynamicIntervalEnabled);
        LogManager.getInstance().log("  Phase3: UAV=" + phases.phase3.minUavCount + "-" + phases.phase3.maxUavCount +
                ", duration=" + phases.phase3.durationMinutes + "min" +
                ", interval=" + phases.phase3.minIntervalSec + "-" + phases.phase3.maxIntervalSec + "s");
        LogManager.getInstance().log("  Phase4: UAV=" + phases.phase4.minUavCount + "-" + phases.phase4.maxUavCount +
                ", dynamicInterval=" + phases.phase4.dynamicIntervalEnabled +
                ", targetCongestion=" + phases.phase4.targetCongestionPercent + "%");
    }

    // Getters
    public long getSeed() { return seed; }
    public int getDurationMinutes() { return durationMinutes; }
    public int getSnapshotIntervalSec() { return snapshotIntervalSec; }
    public PhaseSettings getPhases() { return phases; }
    public String getTopologyPath() { return topologyPath; }
    public String getRouteSearchMethod() { return routeSearchMethod; }

    // シミュレーション時間をミリ秒で取得
    public long getDurationMillis() {
        return (long) durationMinutes * 60 * 1000;
    }

    // スナップショット間隔をミリ秒で取得
    public long getSnapshotIntervalMillis() {
        return (long) snapshotIntervalSec * 1000;
    }
}
