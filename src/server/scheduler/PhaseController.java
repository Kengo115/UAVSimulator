package server.scheduler;

import controller.BoundaryController;
import controller.ClientScheduleLoader;
import server.config.SimulationConfig;
import server.util.LinkStatusRecorder;
import server.util.LogManager;

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
    private static final double INTERVAL_ADJUSTMENT_GAIN = 0.1;

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

        // Phase1の初期間隔を設定
        SimulationConfig.Phase1Settings phase1 = config.getPhases().getPhase1();
        this.currentIntervalSec = (phase1.getMinIntervalSec() + phase1.getMaxIntervalSec()) / 2.0;

        LogManager.getInstance().log("Phase 7-9: PhaseController初期化完了");
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

        // シミュレーション終了チェック
        if (currentTime >= simulationEndTime) {
            LogManager.getInstance().log("Phase 7-9: シミュレーション時間終了");
            stop();
            return;
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
                // 遷移条件: 指定時間経過
                SimulationConfig.Phase2Settings p2 = config.getPhases().getPhase2();
                if (phaseElapsedMs >= p2.getDurationMinutes() * 60 * 1000L) {
                    nextPhase = Phase.PHASE3_CONGESTION;
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
                // 遷移条件: シミュレーション終了まで継続
                // tick()でシミュレーション終了時に停止
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
        currentPhase = newPhase;
        phaseStartTime = System.currentTimeMillis();

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
                minUav = p2.getMinUavCount();
                maxUav = p2.getMaxUavCount();
                // 動的調整
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

        // Phase 7-10: フェーズ遷移時にメモリ使用量をログ
        LogManager.getInstance().logMemoryUsage();
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
        LogManager.getInstance().log("Phase 7-9: PhaseControllerリセット");
    }
}
