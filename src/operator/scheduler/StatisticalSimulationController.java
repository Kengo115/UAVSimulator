package operator.scheduler;
import shared.redis.ClientTimeManager;
import network_manager.scheduler.FlightScheduler;

import operator.BoundaryController;
import operator.ClientScheduleLoader;
import shared.item.BeaconCluster;
import operator.config.StatisticalSimulationConfig;
import network_manager.redis.PathWaitingManager;
import shared.util.LinkStatusRecorder;
import shared.util.LogManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Random;

/**
 * Phase 8: 統計的シミュレーションコントローラー
 *
 * 指数分布に基づくUAV発生間隔でシミュレーションを実行する。
 * 定常状態の確認と6時間の長時間シミュレーションを管理。
 */
public class StatisticalSimulationController {

    // シミュレーション状態（シンプル化：実行中/完了のみ）
    public enum SimulationState {
        RUNNING("実行中"),
        COMPLETED("完了");

        private final String name;

        SimulationState(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    // シングルトンインスタンス
    private static StatisticalSimulationController instance;

    // 設定
    private StatisticalSimulationConfig config;
    private ExponentialIntervalGenerator intervalGenerator;
    private Random random;

    // 状態
    private SimulationState currentState = SimulationState.RUNNING;
    private boolean running = false;
    private long simulationStartTime;

    // 統計
    private int generatedClientCount = 0;
    private int totalUavCount = 0;

    // ノード情報
    private int nodeCount;
    private BeaconCluster beaconCluster;

    // Phase 8: クライアント生成ログ用FileWriter
    // 注意: clientTime.csvはPhase 4のClientTimeManagerが使用するため、
    // Phase 8ではclientGeneration.csvを使用する
    private FileWriter clientGenerationWriter;
    private String clientGenerationCsvPath;

    // Phase 8: シミュレーションイベントログ用FileWriter
    private FileWriter eventLogWriter;
    private String eventLogCsvPath;

    // Phase 8: 1時間ごとの進捗ログ用
    private int lastHourlyLogHour = 0;

    private StatisticalSimulationController() {
    }

    public static synchronized StatisticalSimulationController getInstance() {
        if (instance == null) {
            instance = new StatisticalSimulationController();
        }
        return instance;
    }

    /**
     * コントローラーを初期化
     * @param config 統計シミュレーション設定
     * @param nodeCount ノード数
     * @param beaconCluster ビーコンクラスター
     */
    public void initialize(StatisticalSimulationConfig config, int nodeCount, BeaconCluster beaconCluster) {
        this.config = config;
        this.nodeCount = nodeCount;
        this.beaconCluster = beaconCluster;

        // 乱数生成器を初期化
        long seed = config.getSeed();
        this.random = new Random(seed);
        this.intervalGenerator = new ExponentialIntervalGenerator(
                seed,
                config.getArrival().getMeanIntervalSec()
        );

        // 状態をリセット
        this.currentState = SimulationState.RUNNING;
        this.running = false;
        this.generatedClientCount = 0;
        this.totalUavCount = 0;
        this.lastHourlyLogHour = 0;

        LogManager.getInstance().log("Phase 8: StatisticalSimulationController初期化完了");
        LogManager.getInstance().log("  シミュレーション時間: " + config.getSimulationDurationHours() + "時間");
        LogManager.getInstance().log("  平均発生間隔: " + config.getArrival().getMeanIntervalSec() + "秒");
        LogManager.getInstance().log("  1回あたりUAV数: " + config.getArrival().getUavCountPerBatch() + "機");
    }

    /**
     * シミュレーションを開始
     */
    public void start() {
        if (config == null) {
            throw new IllegalStateException("Phase 8: コントローラーが初期化されていません");
        }

        running = true;
        simulationStartTime = System.currentTimeMillis();
        currentState = SimulationState.RUNNING;

        // LogManagerにシミュレーション開始時刻を設定（1時間ごとのログローテーション用）
        LogManager.getInstance().setSimulationStartTime(simulationStartTime);

        // Phase 12-Fix: PathWaitingManagerを完全クリア（前回実行の残留UAV削除）
        // これにより、client3やclient5358のような「リトライなしでの重複」を防ぐ
        try {
            FlightScheduler flightScheduler = FlightScheduler.getInstance();
            PathWaitingManager pathWaitingManager = flightScheduler.getPathWaitingManager();
            if (pathWaitingManager != null && pathWaitingManager.isConnected()) {
                int totalWaiting = pathWaitingManager.getTotalWaitingCount();
                if (totalWaiting > 0) {
                    LogManager.getInstance().log(
                        "Phase 12-Fix: シミュレーション開始前に残留UAVを検出 (数=" + totalWaiting + ")"
                    );
                    pathWaitingManager.clearAll();
                    LogManager.getInstance().log("Phase 12-Fix: 残留UAVをクリアしました");
                } else {
                    LogManager.getInstance().log(
                        "Phase 12-Fix: PathWaitingManager初期状態確認 (残留UAV=0, クリーンな状態)"
                    );
                }
            }
        } catch (Exception e) {
            LogManager.getInstance().error("Phase 12-Fix: PathWaitingManagerクリア失敗", e);
            // エラーでもシミュレーション続行（致命的ではない）
        }

        // Phase 8: ログファイルを初期化
        initializeLogFiles();

        LogManager.getInstance().log("Phase 8: 統計的シミュレーション開始");
        LogManager.getInstance().log("  開始時刻: " + new java.util.Date(simulationStartTime));
        LogManager.getInstance().log("  シミュレーション終了予定: " +
                new java.util.Date(simulationStartTime + config.getDurationMillis()));
    }

    /**
     * Phase 8: ログファイルを初期化
     */
    private void initializeLogFiles() {
        try {
            // 出力ディレクトリを取得（BoundaryControllerから）
            String baseDir = BoundaryController.getResultDir();
            if (baseDir == null || baseDir.isEmpty()) {
                baseDir = "src/result/large_scale/Bisectional";
            }

            // ディレクトリが存在しない場合は作成
            File timeDir = new File(baseDir + "/time");
            if (!timeDir.exists()) {
                timeDir.mkdirs();
            }

            // 1. clientGeneration.csv を作成（クライアント生成記録）
            // 注意: clientTime.csvはPhase 4のClientTimeManagerが飛行完了時間を記録するために使用
            clientGenerationCsvPath = baseDir + "/time/clientGeneration.csv";
            clientGenerationWriter = new FileWriter(clientGenerationCsvPath, false);
            clientGenerationWriter.write("client_id,timestamp_sec,source_node,dest_node,uav_count,interval_sec,state\n");
            clientGenerationWriter.flush();
            LogManager.getInstance().log("Phase 8: clientGeneration.csv を初期化しました: " + clientGenerationCsvPath);

            // 2. simulationEvents.csv を作成（イベントログ）
            eventLogCsvPath = baseDir + "/time/simulationEvents.csv";
            eventLogWriter = new FileWriter(eventLogCsvPath, false);
            eventLogWriter.write("timestamp_sec,event_type,details\n");
            eventLogWriter.flush();
            LogManager.getInstance().log("Phase 8: simulationEvents.csv を初期化しました: " + eventLogCsvPath);

            // シミュレーション開始イベントを記録
            writeEventLog(0, "SIMULATION_START",
                    String.format("duration=%.1fh,mean_interval=%.1fs,uav_per_batch=%d",
                            config.getSimulationDurationHours(),
                            config.getArrival().getMeanIntervalSec(),
                            config.getArrival().getUavCountPerBatch()));

        } catch (IOException e) {
            LogManager.getInstance().error("Phase 8: ログファイルの初期化に失敗しました", e);
        }
    }

    /**
     * Phase 8: イベントログに書き込む（内部用）
     */
    private void writeEventLog(double timestampSec, String eventType, String details) {
        if (eventLogWriter != null) {
            try {
                eventLogWriter.write(String.format("%.3f,%s,\"%s\"\n", timestampSec, eventType, details));
                eventLogWriter.flush();
            } catch (IOException e) {
                LogManager.getInstance().error("Phase 8: イベントログの書き込みに失敗しました", e);
            }
        }
    }

    /**
     * Phase 8: 外部から診断ログを出力するための公開メソッド
     * BoundaryControllerなど外部クラスから呼び出し可能
     * @param eventType イベントタイプ
     * @param details 詳細情報
     */
    public void logDiagnostic(String eventType, String details) {
        double timestampSec = 0.0;
        if (simulationStartTime > 0) {
            timestampSec = (System.currentTimeMillis() - simulationStartTime) / 1000.0;
        }
        writeEventLog(timestampSec, eventType, details);
    }

    /**
     * Phase 8: 1時間ごとの進捗ログを出力（必要に応じて）
     */
    private void checkAndLogHourlyProgress(long now) {
        long elapsedMs = now - simulationStartTime;
        int currentHour = (int) (elapsedMs / (60 * 60 * 1000));

        // 新しい時間に達した場合のみログ出力
        if (currentHour > lastHourlyLogHour) {
            lastHourlyLogHour = currentHour;

            double timestampSec = elapsedMs / 1000.0;
            double loadRate = LinkStatusRecorder.getInstance().getAverageLoadRate();
            double congestedRate = LinkStatusRecorder.getInstance().getCongestedLinkRate();

            writeEventLog(timestampSec, "HOURLY_PROGRESS",
                    String.format("hour=%d,elapsed_sec=%.0f,clients=%d,uav=%d,load_rate=%.2f%%,congested_rate=%.2f%%",
                            currentHour, timestampSec, generatedClientCount, totalUavCount, loadRate, congestedRate));

            // コンソールにも出力
            System.out.println(String.format(
                    "[Phase 8] %d時間経過 | クライアント: %d | UAV: %d | 負荷率: %.1f%% | 混雑リンク率: %.1f%%",
                    currentHour, generatedClientCount, totalUavCount, loadRate, congestedRate));
        }
    }

    /**
     * シミュレーションを停止
     */
    public void stop() {
        running = false;
        currentState = SimulationState.COMPLETED;

        // シミュレーション終了イベントを記録
        double endTimestamp = (System.currentTimeMillis() - simulationStartTime) / 1000.0;
        double finalLoadRate = LinkStatusRecorder.getInstance().getAverageLoadRate();
        double finalCongestedRate = LinkStatusRecorder.getInstance().getCongestedLinkRate();
        writeEventLog(endTimestamp, "SIMULATION_STOP",
                String.format("elapsed_sec=%.0f,total_clients=%d,total_uav=%d,final_load_rate=%.2f%%,final_congested_rate=%.2f%%",
                        endTimestamp, generatedClientCount, totalUavCount, finalLoadRate, finalCongestedRate));

        // コンソールにも最終統計を出力
        System.out.println(String.format(
                "\n[Phase 8] シミュレーション完了 | 経過: %.0f秒 | クライアント: %d | UAV: %d | 最終負荷率: %.1f%% | 最終混雑率: %.1f%%",
                endTimestamp, generatedClientCount, totalUavCount, finalLoadRate, finalCongestedRate));

        // Phase 8: すべてのログファイルを閉じる
        closeAllLogFiles();

        // 統計をログ出力
        logStatistics();
        intervalGenerator.logStatistics();

        LogManager.getInstance().log("Phase 8: 統計的シミュレーション停止");
    }

    /**
     * Phase 8: すべてのログファイルを閉じる
     */
    private void closeAllLogFiles() {
        // clientGeneration.csv を閉じる
        if (clientGenerationWriter != null) {
            try {
                clientGenerationWriter.flush();
                clientGenerationWriter.close();
                LogManager.getInstance().log("Phase 8: clientGeneration.csv を閉じました（" + generatedClientCount + "件書き込み）");
            } catch (IOException e) {
                LogManager.getInstance().error("Phase 8: clientGeneration.csv のクローズに失敗しました", e);
            }
            clientGenerationWriter = null;
        }

        // simulationEvents.csv を閉じる
        if (eventLogWriter != null) {
            try {
                eventLogWriter.flush();
                eventLogWriter.close();
                LogManager.getInstance().log("Phase 8: simulationEvents.csv を閉じました");
            } catch (IOException e) {
                LogManager.getInstance().error("Phase 8: simulationEvents.csv のクローズに失敗しました", e);
            }
            eventLogWriter = null;
        }
    }

    /**
     * 次のクライアント生成エントリを取得
     * @return スケジュールエントリ（シミュレーション終了時はnull）
     */
    public ClientScheduleLoader.ScheduleEntry generateNextClient() {
        if (!running) {
            return null;
        }

        // シミュレーション終了チェック
        long now = System.currentTimeMillis();
        if (now - simulationStartTime >= config.getDurationMillis()) {
            stop();
            return null;
        }

        // 始点・終点をランダム選択
        int source = random.nextInt(nodeCount);
        int dest;
        do {
            dest = random.nextInt(nodeCount);
        } while (dest == source);

        // UAV数（固定）
        int uavCount = config.getArrival().getUavCountPerBatch();

        // 次の発生間隔（指数分布）
        double intervalSec = intervalGenerator.nextIntervalSec();

        // カウンターを更新
        generatedClientCount++;
        totalUavCount += uavCount;

        // エントリを作成
        ClientScheduleLoader.ScheduleEntry entry = new ClientScheduleLoader.ScheduleEntry(
                generatedClientCount,
                source,
                dest,
                uavCount,
                intervalSec  // 浮動小数点で渡す
        );

        // Phase 8: clientGeneration.csv にクライアント生成情報を書き込む
        double timestampSec = (now - simulationStartTime) / 1000.0;
        writeClientGenerationCsv(generatedClientCount, timestampSec, source, dest, uavCount, intervalSec, currentState);

        // Phase 8: 1時間ごとの進捗ログをチェック
        checkAndLogHourlyProgress(now);

        return entry;
    }

    /**
     * Phase 8: clientGeneration.csv にクライアント生成情報を書き込む
     */
    private void writeClientGenerationCsv(int clientId, double timestampSec, int source, int dest,
                                          int uavCount, double intervalSec, SimulationState state) {
        if (clientGenerationWriter != null) {
            try {
                clientGenerationWriter.write(String.format("%d,%.3f,%d,%d,%d,%.3f,%s\n",
                        clientId, timestampSec, source, dest, uavCount, intervalSec, state.getName()));

                // 100件ごとにフラッシュ（パフォーマンスと信頼性のバランス）
                if (clientId % 100 == 0) {
                    clientGenerationWriter.flush();
                }
            } catch (IOException e) {
                LogManager.getInstance().error("Phase 8: clientGeneration.csv への書き込みに失敗しました (client=" + clientId + ")", e);
            }
        }
    }

    /**
     * 経過時間を取得（秒）
     */
    public long getElapsedSeconds() {
        if (simulationStartTime == 0) return 0;
        return (System.currentTimeMillis() - simulationStartTime) / 1000;
    }

    /**
     * 経過時間を取得（分）
     */
    public double getElapsedMinutes() {
        return getElapsedSeconds() / 60.0;
    }

    /**
     * 残り時間を取得（秒）
     */
    public long getRemainingSeconds() {
        if (simulationStartTime == 0) return config.getDurationSeconds();
        long elapsed = System.currentTimeMillis() - simulationStartTime;
        long remaining = config.getDurationMillis() - elapsed;
        return Math.max(0, remaining / 1000);
    }

    /**
     * 進捗率を取得（%）
     */
    public double getProgressPercent() {
        if (simulationStartTime == 0) return 0.0;
        long elapsed = System.currentTimeMillis() - simulationStartTime;
        return Math.min(100.0, (elapsed * 100.0) / config.getDurationMillis());
    }

    /**
     * 統計情報をログ出力
     */
    public void logStatistics() {
        LogManager.getInstance().log("Phase 8 [統計的シミュレーション統計]:");
        LogManager.getInstance().log("  経過時間: " + String.format("%.1f", getElapsedMinutes()) + "分");
        LogManager.getInstance().log("  進捗率: " + String.format("%.1f%%", getProgressPercent()));
        LogManager.getInstance().log("  総クライアント数: " + generatedClientCount);
        LogManager.getInstance().log("  総UAV数: " + totalUavCount);
        LogManager.getInstance().log("  現在の平均リンク負荷率: " +
                String.format("%.2f%%", LinkStatusRecorder.getInstance().getAverageLoadRate()));
    }

    /**
     * 定期的な進捗ログを出力（5分ごとに呼び出すことを想定）
     */
    public void logProgress() {
        double loadRate = LinkStatusRecorder.getInstance().getAverageLoadRate();
        double congestedRate = LinkStatusRecorder.getInstance().getCongestedLinkRate();

        // システムリソース情報
        Runtime runtime = Runtime.getRuntime();
        long usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMemoryMB = runtime.maxMemory() / (1024 * 1024);

        // ディスク空き容量（結果出力ディレクトリ）
        java.io.File resultDir = new java.io.File(BoundaryController.getResultDir());
        long freeSpaceMB = resultDir.exists() ? resultDir.getFreeSpace() / (1024 * 1024) : -1;

        System.out.println(String.format(
                "[Phase 8] 経過: %.0f分 | 進捗: %.1f%% | クライアント: %d | UAV: %d | 負荷率: %.1f%% | 混雑リンク率: %.1f%% | メモリ: %d/%dMB | ディスク: %dMB",
                getElapsedMinutes(),
                getProgressPercent(),
                generatedClientCount,
                totalUavCount,
                loadRate,
                congestedRate,
                usedMemoryMB,
                maxMemoryMB,
                freeSpaceMB
        ));

        // Phase 8-Debug: simulationEvents.csvにも5分ごとの詳細情報を記録
        double timestampSec = getElapsedSeconds();
        writeEventLog(timestampSec, "PERIODIC_STATUS",
                String.format("elapsed_min=%.0f,progress=%.1f%%,clients=%d,uav=%d,load_rate=%.2f%%,congested_rate=%.2f%%,memory_mb=%d/%d,disk_free_mb=%d",
                        getElapsedMinutes(), getProgressPercent(), generatedClientCount, totalUavCount,
                        loadRate, congestedRate, usedMemoryMB, maxMemoryMB, freeSpaceMB));
    }

    // Getters
    /**
     * シミュレーションが実行中かどうかを返す
     * シミュレーション時間を超過している場合は自動的にstop()を呼び出す
     */
    public boolean isRunning() {
        if (!running) {
            return false;
        }
        // シミュレーション時間を超過しているかチェック
        if (simulationStartTime > 0 && config != null) {
            long now = System.currentTimeMillis();
            if (now - simulationStartTime >= config.getDurationMillis()) {
                LogManager.getInstance().log("Phase 8: シミュレーション時間超過を検出 - 停止処理を開始");
                stop();
                return false;
            }
        }
        return true;
    }
    public SimulationState getCurrentState() { return currentState; }
    public int getGeneratedClientCount() { return generatedClientCount; }
    public int getTotalUavCount() { return totalUavCount; }
    public StatisticalSimulationConfig getConfig() { return config; }
    public ExponentialIntervalGenerator getIntervalGenerator() { return intervalGenerator; }

    /**
     * インスタンスをリセット
     */
    public static void resetInstance() {
        instance = null;
    }
}
