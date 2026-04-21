package operator.scheduler;
import network_manager.scheduler.FlightScheduler;

import operator.BoundaryController;
import operator.ClientScheduleLoader;
import shared.item.Link;
import operator.config.SimulationConfig;
import shared.util.LinkStatusRecorder;
import shared.util.LogManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Phase 7-9: 4フェーズ制御コントローラー
 *
 * シミュレーションを4つのフェーズで制御する：
 * - Phase1（生成期）: 混雑率≥30%かつ60秒経過で次へ
 * - Phase2（安定期）: 30分間、混雑率50%目標で動的調整
 * - Phase3（混雑期）: 5分間、高負荷でUAV投入
 * - Phase4（回復期）: シミュレーション終了まで、混雑率50%目標で動的調整
 */
public class PhaseController {

    /**
     * フェーズ列挙型
     */
    public enum Phase {
        PHASE1_GENERATION("生成期", 1),
        PHASE2_STABLE("安定期", 2),
        PHASE3_CONGESTION("混雑期", 3),
        PHASE4_RECOVERY("回復期", 4);

        private final String name;
        private final int number;

        Phase(String name, int number) {
            this.name = name;
            this.number = number;
        }

        public String getName() { return name; }
        public int getNumber() { return number; }
    }

    private static PhaseController instance;

    // 現在のフェーズ
    private Phase currentPhase = Phase.PHASE1_GENERATION;

    // フェーズ開始時刻（ミリ秒）
    private long phaseStartTime = 0;

    // シミュレーション開始時刻
    private long simulationStartTime = 0;

    // シミュレーション終了時刻
    private long simulationEndTime = 0;

    // 設定
    private SimulationConfig config;

    // ランダムクライアント生成器
    private RandomClientGenerator clientGenerator;

    // クライアント生成コールバック
    private Consumer<ClientScheduleLoader.ScheduleEntry> clientGenerationCallback;

    // スケジューラ
    private ScheduledExecutorService scheduler;

    // 実行中フラグ
    private AtomicBoolean running = new AtomicBoolean(false);

    // 動的間隔調整用の現在の間隔（秒）
    private double currentIntervalSec = 2.0;

    // 動的調整のゲイン（PID制御のP項に相当）
    private static final double INTERVAL_ADJUSTMENT_GAIN = 0.5;

    // リンク配列（容量操作用）
    private Link[][] link;

    // フェーズ遷移CSV出力用
    private String phaseTransitionFilePath = null;
    private boolean phaseTransitionHeaderWritten = false;
    private int nodeCount;

    // 元のリンク容量を保存（Phase2で復元するため）
    private double[][] originalCapacity;

    // Phase2安定カウンター（連続で目標混雑率を達成した回数）
    private int stableCounter = 0;
    private static final int STABLE_THRESHOLD = 10;  // 連続10回で安定とみなす
    private static final double STABLE_CONGESTION_MIN = 45.0;  // 安定判定の下限
    private static final double STABLE_CONGESTION_MAX = 55.0;  // 安定判定の上限

    // フェーズ統計情報
    private PhaseStatistics[] phaseStats = new PhaseStatistics[4];
    private String phaseStatsFilePath = null;

    /**
     * フェーズ統計情報クラス
     */
    public static class PhaseStatistics {
        public int phaseNumber;
        public String phaseName;
        public long startTimeMs;      // シミュレーション開始からの経過時間
        public long endTimeMs;        // シミュレーション開始からの経過時間
        public int clientCount;       // 生成したクライアント数
        public int totalUavCount;     // 生成した合計UAV数
        public double sumCongestionRate;  // 混雑率の合計（平均計算用）
        public int congestionSampleCount; // 混雑率サンプル数
        public double maxCongestionRate;  // 最大混雑率
        public double minCongestionRate;  // 最小混雑率
        public double endCongestionRate;  // 終了時混雑率

        public PhaseStatistics(int phaseNumber, String phaseName) {
            this.phaseNumber = phaseNumber;
            this.phaseName = phaseName;
            this.startTimeMs = 0;
            this.endTimeMs = 0;
            this.clientCount = 0;
            this.totalUavCount = 0;
            this.sumCongestionRate = 0;
            this.congestionSampleCount = 0;
            this.maxCongestionRate = 0;
            this.minCongestionRate = Double.MAX_VALUE;
            this.endCongestionRate = 0;
        }

        public double getDurationSeconds() {
            return (endTimeMs - startTimeMs) / 1000.0;
        }

        public double getAverageCongestionRate() {
            return congestionSampleCount > 0 ? sumCongestionRate / congestionSampleCount : 0;
        }

        public void recordCongestion(double rate) {
            sumCongestionRate += rate;
            congestionSampleCount++;
            if (rate > maxCongestionRate) maxCongestionRate = rate;
            if (rate < minCongestionRate) minCongestionRate = rate;
        }
    }

    private PhaseController() {
    }

    public static synchronized PhaseController getInstance() {
        if (instance == null) {
            instance = new PhaseController();
        }
        return instance;
    }

    /**
     * フェーズ制御を初期化
     * @param config シミュレーション設定
     * @param clientGenerator ランダムクライアント生成器
     * @param callback クライアント生成時のコールバック
     */
    public void initialize(SimulationConfig config, RandomClientGenerator clientGenerator,
                          Consumer<ClientScheduleLoader.ScheduleEntry> callback) {
        this.config = config;
        this.clientGenerator = clientGenerator;
        this.clientGenerationCallback = callback;
        this.currentPhase = Phase.PHASE1_GENERATION;
        this.phaseStartTime = 0;
        this.stableCounter = 0;

        // フェーズ統計情報を初期化
        phaseStats[0] = new PhaseStatistics(1, "生成期");
        phaseStats[1] = new PhaseStatistics(2, "安定期");
        phaseStats[2] = new PhaseStatistics(3, "混雑期");
        phaseStats[3] = new PhaseStatistics(4, "回復期");
        phaseStatsFilePath = null;

        // Phase1の初期間隔を設定
        SimulationConfig.Phase1Settings phase1 = config.getPhases().getPhase1();
        this.currentIntervalSec = (phase1.getMinIntervalSec() + phase1.getMaxIntervalSec()) / 2.0;

        LogManager.getInstance().log("Phase 7-9: PhaseController初期化完了");
    }

    /**
     * リンク配列を設定（容量操作用）
     * @param link リンク配列
     * @param nodeCount ノード数
     */
    public void setLinkArray(Link[][] link, int nodeCount) {
        this.link = link;
        this.nodeCount = nodeCount;

        // 元の容量を保存
        this.originalCapacity = new double[nodeCount][nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            for (int j = 0; j < nodeCount; j++) {
                if (link[i][j] != null) {
                    originalCapacity[i][j] = link[i][j].getCapacity();
                }
            }
        }

        LogManager.getInstance().log("Phase 7-9: リンク配列設定完了 (ノード数=" + nodeCount + ")");
    }

    /**
     * Phase1用: 全リンクの容量を半分にする
     */
    public void halveAllLinkCapacity() {
        if (link == null) {
            LogManager.getInstance().log("警告: リンク配列が設定されていません");
            return;
        }

        int modifiedCount = 0;
        for (int i = 0; i < nodeCount; i++) {
            for (int j = 0; j < nodeCount; j++) {
                if (link[i][j] != null && originalCapacity[i][j] > 0) {
                    double halfCapacity = originalCapacity[i][j] / 2.0;
                    link[i][j].setCapacity(halfCapacity);
                    modifiedCount++;
                }
            }
        }

        LogManager.getInstance().log("Phase 7-9: 全リンク容量を半分に設定 (変更リンク数=" + modifiedCount + ")");
    }

    /**
     * Phase2用: 全リンクの容量を元に戻す
     */
    public void restoreAllLinkCapacity() {
        if (link == null || originalCapacity == null) {
            LogManager.getInstance().log("警告: リンク配列または元の容量が設定されていません");
            return;
        }

        int restoredCount = 0;
        for (int i = 0; i < nodeCount; i++) {
            for (int j = 0; j < nodeCount; j++) {
                if (link[i][j] != null && originalCapacity[i][j] > 0) {
                    link[i][j].setCapacity(originalCapacity[i][j]);
                    restoredCount++;
                }
            }
        }

        LogManager.getInstance().log("Phase 7-9: 全リンク容量を復元 (復元リンク数=" + restoredCount + ")");
    }

    /**
     * フェーズ制御を開始
     */
    public void start() {
        if (running.get()) {
            LogManager.getInstance().log("Phase 7-9: 既に実行中です");
            return;
        }

        simulationStartTime = System.currentTimeMillis();
        simulationEndTime = simulationStartTime + config.getDurationMillis();
        phaseStartTime = simulationStartTime;

        running.set(true);

        // Phase1の統計開始
        if (phaseStats[0] != null) {
            phaseStats[0].startTimeMs = 0;
        }

        // スケジューラを開始
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PhaseController-Scheduler");
            t.setDaemon(true);
            return t;
        });

        // 定期的にフェーズ制御を実行
        scheduler.scheduleAtFixedRate(this::tick, 0, 100, TimeUnit.MILLISECONDS);

        LogManager.getInstance().log("Phase 7-9: フェーズ制御開始 (Phase1: " + currentPhase.getName() + ")");
        logPhaseTransition(null, currentPhase);
    }

    /**
     * フェーズ制御を停止
     */
    public void stop() {
        running.set(false);

        // 現在のフェーズの統計を終了
        if (simulationStartTime > 0) {
            long elapsed = System.currentTimeMillis() - simulationStartTime;
            int phaseIndex = currentPhase.getNumber() - 1;
            if (phaseIndex >= 0 && phaseIndex < 4 && phaseStats[phaseIndex] != null) {
                phaseStats[phaseIndex].endTimeMs = elapsed;
                phaseStats[phaseIndex].endCongestionRate = LinkStatusRecorder.getInstance().getAverageLoadRate();
            }

            // 統計情報をファイルに出力
            writePhaseStatsToCSV();
        }

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
        }

        LogManager.getInstance().log("Phase 7-9: フェーズ制御停止");
    }

    /**
     * 定期実行されるティック処理
     */
    private void tick() {
        if (!running.get()) {
            return;
        }

        long currentTime = System.currentTimeMillis();

        // Phase 7-11: Phase4では時間制限を無視（全UAV完了まで継続）
        // Phase1-3では設定時間での終了チェック
        if (currentPhase != Phase.PHASE4_RECOVERY && currentTime >= simulationEndTime) {
            LogManager.getInstance().log("Phase 7-9: シミュレーション時間終了");
            stop();
            return;
        }

        // 現在のフェーズの混雑率をサンプリング（1秒ごと = 10 tick）
        int phaseIndex = currentPhase.getNumber() - 1;
        if (phaseIndex >= 0 && phaseIndex < 4 && phaseStats[phaseIndex] != null) {
            long tickCount = (currentTime - phaseStartTime) / 100;
            if (tickCount % 10 == 0) {  // 1秒ごと
                double congestionRate = LinkStatusRecorder.getInstance().getAverageLoadRate();
                phaseStats[phaseIndex].recordCongestion(congestionRate);
            }
        }

        // フェーズ遷移チェック
        checkPhaseTransition(currentTime);

        // クライアント生成（間隔に基づく）
        // 注: 実際の生成タイミングは別途管理が必要
    }

    /**
     * フェーズ遷移をチェック
     */
    private void checkPhaseTransition(long currentTime) {
        long phaseElapsedMs = currentTime - phaseStartTime;
        double congestionRate = LinkStatusRecorder.getInstance().getAverageLoadRate();

        Phase nextPhase = null;

        switch (currentPhase) {
            case PHASE1_GENERATION:
                // 遷移条件: 混雑率≥閾値 かつ 最低時間経過
                SimulationConfig.Phase1Settings p1 = config.getPhases().getPhase1();
                if (congestionRate >= p1.getCongestionThresholdPercent() &&
                    phaseElapsedMs >= p1.getMinDurationSec() * 1000L) {
                    nextPhase = Phase.PHASE2_STABLE;
                }
                break;

            case PHASE2_STABLE:
                // 遷移条件: 設定ファイルの最低時間経過 かつ 連続10回混雑率が安定範囲内（50-55%）
                SimulationConfig.Phase2Settings p2Config = config.getPhases().getPhase2();
                long phase2MinDurationMs = p2Config.getDurationMinutes() * 60 * 1000L;
                if (congestionRate >= STABLE_CONGESTION_MIN && congestionRate <= STABLE_CONGESTION_MAX) {
                    stableCounter++;
                    // 最低継続時間を経過し、かつ安定している場合のみ遷移
                    if (phaseElapsedMs >= phase2MinDurationMs && stableCounter >= STABLE_THRESHOLD) {
                        LogManager.getInstance().log(String.format(
                            "Phase 7-9: Phase2完了 (経過=%d分, 混雑率=%.1f%%, 連続安定=%d回)",
                            phaseElapsedMs / 60000, congestionRate, stableCounter));
                        nextPhase = Phase.PHASE3_CONGESTION;
                    }
                } else {
                    // 範囲外ならカウンターリセット
                    if (stableCounter > 0) {
                        LogManager.getInstance().log(String.format(
                            "Phase 7-9: 安定カウンターリセット (経過=%d分, 混雑率=%.1f%%)",
                            phaseElapsedMs / 60000, congestionRate));
                    }
                    stableCounter = 0;
                }
                break;

            case PHASE3_CONGESTION:
                // 遷移条件: 指定時間経過
                SimulationConfig.Phase3Settings p3 = config.getPhases().getPhase3();
                if (phaseElapsedMs >= p3.getDurationMinutes() * 60 * 1000L) {
                    nextPhase = Phase.PHASE4_RECOVERY;
                }
                break;

            case PHASE4_RECOVERY:
                // Phase 7-11: 遷移条件を「全UAV飛行完了」に変更
                // 飛行中・待機中・経路待ちのUAVがすべて0になったら終了
                try {
                    FlightScheduler scheduler = FlightScheduler.getInstance();
                    if (scheduler.isAllFlightsCompleted()) {
                        LogManager.getInstance().log("Phase 7-11: 全UAV飛行完了 - シミュレーション終了");
                        stop();
                        return;
                    }

                    // 10秒ごとに残りUAV数をログ出力
                    if (phaseElapsedMs % 10000 < 100) {
                        int[] status = scheduler.getFlightStatus();
                        int remaining = status[0] + status[1] + status[2];
                        LogManager.getInstance().log(String.format(
                            "Phase 7-11 [Phase4進捗]: 残りUAV=%d (飛行中=%d, 待機中=%d, 経路待ち=%d), 完了=%d",
                            remaining, status[0], status[1], status[2], status[3]
                        ));
                    }
                } catch (Exception e) {
                    // FlightScheduler未初期化の場合は時間ベースで終了
                    LogManager.getInstance().log("Phase 7-11: FlightScheduler未初期化、時間ベースで継続");
                }
                break;
        }

        if (nextPhase != null) {
            transitionTo(nextPhase);
        }
    }

    /**
     * フェーズ遷移を実行
     */
    private void transitionTo(Phase newPhase) {
        Phase oldPhase = currentPhase;
        long elapsed = System.currentTimeMillis() - simulationStartTime;
        double currentCongestion = LinkStatusRecorder.getInstance().getAverageLoadRate();

        // 旧フェーズの統計を終了
        int oldIndex = oldPhase.getNumber() - 1;
        if (oldIndex >= 0 && oldIndex < 4 && phaseStats[oldIndex] != null) {
            phaseStats[oldIndex].endTimeMs = elapsed;
            phaseStats[oldIndex].endCongestionRate = currentCongestion;
        }

        currentPhase = newPhase;
        phaseStartTime = System.currentTimeMillis();

        // 新フェーズの統計を開始
        int newIndex = newPhase.getNumber() - 1;
        if (newIndex >= 0 && newIndex < 4 && phaseStats[newIndex] != null) {
            phaseStats[newIndex].startTimeMs = elapsed;
        }

        // Phase2に遷移する際の処理
        if (newPhase == Phase.PHASE2_STABLE) {
            // 安定カウンターをリセット
            stableCounter = 0;
        }

        // Phase3に遷移する際の処理
        if (newPhase == Phase.PHASE3_CONGESTION) {
            // 安定カウンターをリセット
            stableCounter = 0;
        }

        // 新しいフェーズの初期間隔を設定
        updateIntervalForPhase(newPhase);

        logPhaseTransition(oldPhase, newPhase);
    }

    /**
     * フェーズに応じた間隔を設定
     */
    private void updateIntervalForPhase(Phase phase) {
        switch (phase) {
            case PHASE1_GENERATION:
                SimulationConfig.Phase1Settings p1 = config.getPhases().getPhase1();
                currentIntervalSec = (p1.getMinIntervalSec() + p1.getMaxIntervalSec()) / 2.0;
                break;
            case PHASE2_STABLE:
                SimulationConfig.Phase2Settings p2 = config.getPhases().getPhase2();
                currentIntervalSec = (p2.getMinIntervalSec() + p2.getMaxIntervalSec()) / 2.0;
                break;
            case PHASE3_CONGESTION:
                SimulationConfig.Phase3Settings p3 = config.getPhases().getPhase3();
                currentIntervalSec = (p3.getMinIntervalSec() + p3.getMaxIntervalSec()) / 2.0;
                break;
            case PHASE4_RECOVERY:
                SimulationConfig.Phase4Settings p4 = config.getPhases().getPhase4();
                currentIntervalSec = (p4.getMinIntervalSec() + p4.getMaxIntervalSec()) / 2.0;
                break;
        }
    }

    /**
     * 動的間隔調整を計算（Phase2, Phase4で使用）
     * 混雑率が目標より高い→間隔を長くする
     * 混雑率が目標より低い→間隔を短くする
     *
     * @return 調整後の間隔（秒）
     */
    public double calculateDynamicInterval() {
        double currentCongestion = LinkStatusRecorder.getInstance().getAverageLoadRate();
        double targetCongestion;
        double minInterval, maxInterval;

        if (currentPhase == Phase.PHASE2_STABLE) {
            SimulationConfig.Phase2Settings p2 = config.getPhases().getPhase2();
            if (!p2.isDynamicIntervalEnabled()) {
                return currentIntervalSec;
            }
            targetCongestion = p2.getTargetCongestionPercent();
            minInterval = p2.getMinIntervalSec();
            maxInterval = p2.getMaxIntervalSec();
        } else if (currentPhase == Phase.PHASE4_RECOVERY) {
            SimulationConfig.Phase4Settings p4 = config.getPhases().getPhase4();
            if (!p4.isDynamicIntervalEnabled()) {
                return currentIntervalSec;
            }
            targetCongestion = p4.getTargetCongestionPercent();
            minInterval = p4.getMinIntervalSec();
            maxInterval = p4.getMaxIntervalSec();
        } else {
            // Phase1, Phase3はランダム間隔
            return currentIntervalSec;
        }

        // 誤差を計算（正=混雑しすぎ、負=空きすぎ）
        double error = currentCongestion - targetCongestion;

        // 間隔を調整（混雑しすぎなら間隔を長く、空きすぎなら間隔を短く）
        double adjustment = error * INTERVAL_ADJUSTMENT_GAIN;
        currentIntervalSec = currentIntervalSec + adjustment;

        // 範囲内にクランプ
        currentIntervalSec = Math.max(minInterval, Math.min(maxInterval, currentIntervalSec));

        return currentIntervalSec;
    }

    /**
     * 次のクライアント生成エントリを取得
     * @return スケジュールエントリ
     */
    public ClientScheduleLoader.ScheduleEntry generateNextClient() {
        if (clientGenerator == null) {
            return null;
        }

        int minUav, maxUav;
        double minInterval, maxInterval;

        switch (currentPhase) {
            case PHASE1_GENERATION:
                SimulationConfig.Phase1Settings p1 = config.getPhases().getPhase1();
                minUav = p1.getMinUavCount();
                maxUav = p1.getMaxUavCount();
                minInterval = p1.getMinIntervalSec();
                maxInterval = p1.getMaxIntervalSec();
                break;
            case PHASE2_STABLE:
                SimulationConfig.Phase2Settings p2 = config.getPhases().getPhase2();
                double currentCongestion = LinkStatusRecorder.getInstance().getAverageLoadRate();

                // 混雑率が上限を超えている場合、クライアント生成を停止
                if (currentCongestion > STABLE_CONGESTION_MAX) {
                    LogManager.getInstance().log(String.format(
                        "Phase 7-9: 混雑率超過のためクライアント生成停止 (混雑率=%.1f%% > %.1f%%)",
                        currentCongestion, STABLE_CONGESTION_MAX));
                    return null;
                }

                // 混雑率に応じてUAV数を調整
                if (currentCongestion < STABLE_CONGESTION_MIN) {
                    // 混雑率が低い → UAV数を多めに
                    minUav = p2.getMaxUavCount();
                    maxUav = p2.getMaxUavCount();
                } else {
                    // 安定範囲内 → 少なめに維持
                    minUav = p2.getMinUavCount();
                    maxUav = p2.getMinUavCount();
                }

                // 動的間隔調整
                double dynamicInterval2 = calculateDynamicInterval();
                minInterval = dynamicInterval2;
                maxInterval = dynamicInterval2;
                break;
            case PHASE3_CONGESTION:
                SimulationConfig.Phase3Settings p3 = config.getPhases().getPhase3();
                minUav = p3.getMinUavCount();
                maxUav = p3.getMaxUavCount();
                minInterval = p3.getMinIntervalSec();
                maxInterval = p3.getMaxIntervalSec();
                break;
            case PHASE4_RECOVERY:
                SimulationConfig.Phase4Settings p4 = config.getPhases().getPhase4();
                minUav = p4.getMinUavCount();
                maxUav = p4.getMaxUavCount();
                // UAV数が0の場合はクライアント生成をスキップ（60分経過を待つだけ）
                if (maxUav <= 0) {
                    return null;
                }
                // 動的調整
                double dynamicInterval4 = calculateDynamicInterval();
                minInterval = dynamicInterval4;
                maxInterval = dynamicInterval4;
                break;
            default:
                return null;
        }

        return clientGenerator.generateEntryWithParams(minUav, maxUav, minInterval, maxInterval);
    }

    /**
     * フェーズ遷移をログ出力
     */
    private void logPhaseTransition(Phase from, Phase to) {
        long elapsed = System.currentTimeMillis() - simulationStartTime;
        double congestionRate = LinkStatusRecorder.getInstance().getAverageLoadRate();
        double congestedLinkRate = LinkStatusRecorder.getInstance().getCongestedLinkRate();

        if (from == null) {
            LogManager.getInstance().log(String.format(
                "Phase 7-9 [フェーズ開始]: %s (経過=%dms, 混雑率=%.1f%%, 混雑リンク率=%.1f%%)",
                to.getName(), elapsed, congestionRate, congestedLinkRate
            ));
        } else {
            LogManager.getInstance().log(String.format(
                "Phase 7-9 [フェーズ遷移]: %s → %s (経過=%dms, 混雑率=%.1f%%, 混雑リンク率=%.1f%%)",
                from.getName(), to.getName(), elapsed, congestionRate, congestedLinkRate
            ));
        }

        // フェーズ遷移をCSVに記録
        writePhaseTransitionToCSV(elapsed, to.getNumber(), congestionRate, congestedLinkRate);

        // Phase 7-10: フェーズ遷移時にメモリ使用量をログ
        LogManager.getInstance().logMemoryUsage();
    }

    /**
     * フェーズ遷移をCSVファイルに記録
     */
    private void writePhaseTransitionToCSV(long timestamp, int phase, double congestionRate, double congestedLinkRate) {
        try {
            if (phaseTransitionFilePath == null) {
                String dirPath = BoundaryController.getResultDir();

                File dir = new File(dirPath);
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                phaseTransitionFilePath = dirPath + "/phase_transitions.csv";
            }

            try (FileWriter writer = new FileWriter(phaseTransitionFilePath, true)) {
                if (!phaseTransitionHeaderWritten) {
                    writer.write("timestamp,phase,congestion_rate,congested_link_rate\n");
                    phaseTransitionHeaderWritten = true;
                }
                writer.write(String.format("%d,%d,%.2f,%.2f\n",
                    timestamp, phase, congestionRate, congestedLinkRate));
            }
        } catch (IOException e) {
            LogManager.getInstance().log("フェーズ遷移CSV書き込みエラー: " + e.getMessage());
        }
    }

    /**
     * クライアント生成を記録
     * @param uavCount 生成したUAV数
     */
    public void recordClientGeneration(int uavCount) {
        int phaseIndex = currentPhase.getNumber() - 1;
        if (phaseIndex >= 0 && phaseIndex < 4 && phaseStats[phaseIndex] != null) {
            phaseStats[phaseIndex].clientCount++;
            phaseStats[phaseIndex].totalUavCount += uavCount;
        }
    }

    /**
     * フェーズ統計情報をCSVファイルに出力
     */
    private void writePhaseStatsToCSV() {
        try {
            String dirPath = BoundaryController.getResultDir();

            File dir = new File(dirPath);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            phaseStatsFilePath = dirPath + "/phase_statistics.csv";

            try (FileWriter writer = new FileWriter(phaseStatsFilePath)) {
                // ヘッダー
                writer.write("phase,phase_name,start_time_sec,end_time_sec,duration_sec,");
                writer.write("client_count,total_uav_count,");
                writer.write("avg_congestion_rate,min_congestion_rate,max_congestion_rate,end_congestion_rate\n");

                // 各フェーズの統計
                for (PhaseStatistics stats : phaseStats) {
                    if (stats != null && stats.startTimeMs > 0 || (stats != null && stats.phaseNumber == 1)) {
                        double minCongestion = stats.minCongestionRate == Double.MAX_VALUE ? 0 : stats.minCongestionRate;
                        writer.write(String.format("%d,%s,%.1f,%.1f,%.1f,%d,%d,%.2f,%.2f,%.2f,%.2f\n",
                            stats.phaseNumber,
                            stats.phaseName,
                            stats.startTimeMs / 1000.0,
                            stats.endTimeMs / 1000.0,
                            stats.getDurationSeconds(),
                            stats.clientCount,
                            stats.totalUavCount,
                            stats.getAverageCongestionRate(),
                            minCongestion,
                            stats.maxCongestionRate,
                            stats.endCongestionRate
                        ));
                    }
                }
            }

            LogManager.getInstance().log("Phase 7-9: フェーズ統計情報を出力: " + phaseStatsFilePath);

            // ログにもサマリーを出力
            logPhaseStatsSummary();

        } catch (IOException e) {
            LogManager.getInstance().log("フェーズ統計CSV書き込みエラー: " + e.getMessage());
        }
    }

    /**
     * フェーズ統計サマリーをログ出力
     */
    private void logPhaseStatsSummary() {
        LogManager.getInstance().log("=== フェーズ統計サマリー ===");
        for (PhaseStatistics stats : phaseStats) {
            if (stats != null && (stats.endTimeMs > 0 || stats.phaseNumber == 1)) {
                double minCongestion = stats.minCongestionRate == Double.MAX_VALUE ? 0 : stats.minCongestionRate;
                LogManager.getInstance().log(String.format(
                    "Phase%d (%s): 時間=%.1f秒, クライアント数=%d, UAV数=%d, 平均混雑率=%.1f%%, 最終混雑率=%.1f%%",
                    stats.phaseNumber,
                    stats.phaseName,
                    stats.getDurationSeconds(),
                    stats.clientCount,
                    stats.totalUavCount,
                    stats.getAverageCongestionRate(),
                    stats.endCongestionRate
                ));
            }
        }
        LogManager.getInstance().log("===========================");
    }

    // Getters
    public Phase getCurrentPhase() { return currentPhase; }
    public boolean isRunning() { return running.get(); }
    public double getCurrentIntervalSec() { return currentIntervalSec; }

    public long getPhaseElapsedMs() {
        return System.currentTimeMillis() - phaseStartTime;
    }

    public long getSimulationElapsedMs() {
        return System.currentTimeMillis() - simulationStartTime;
    }

    /**
     * 現在の状態をログ出力
     */
    public void logStatus() {
        double congestionRate = LinkStatusRecorder.getInstance().getAverageLoadRate();
        LogManager.getInstance().log(String.format(
            "Phase 7-9 [状態]: Phase=%s, 経過=%dms, フェーズ内経過=%dms, 混雑率=%.1f%%, 間隔=%.1fs",
            currentPhase.getName(),
            getSimulationElapsedMs(),
            getPhaseElapsedMs(),
            congestionRate,
            currentIntervalSec
        ));
    }

    /**
     * リセット
     */
    public void reset() {
        stop();
        currentPhase = Phase.PHASE1_GENERATION;
        phaseStartTime = 0;
        simulationStartTime = 0;
        simulationEndTime = 0;
        currentIntervalSec = 2.0;
        phaseTransitionFilePath = null;
        phaseTransitionHeaderWritten = false;
        phaseStatsFilePath = null;
        // フェーズ統計をリセット
        for (int i = 0; i < phaseStats.length; i++) {
            phaseStats[i] = null;
        }
        LogManager.getInstance().log("Phase 7-9: PhaseControllerリセット");
    }
}
