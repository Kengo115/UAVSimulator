package shared.util;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * ログを管理するクラス
 * コンソールへの出力とログファイルへの書き込みを管理する
 * 1時間ごとにログファイルをローテーションする機能を持つ
 * Phase 9: 環境変数LOG_DIRに対応
 */
public class LogManager {
    // シングルトンインスタンス
    private static LogManager instance;

    // Phase 9: ログファイルのパス（環境変数から取得、デフォルトは src/log）
    private static final String LOG_DIRECTORY = initLogDirectory();
    private static final String LOG_FILE_PREFIX = LOG_DIRECTORY + "/simulator_";
    private static final String LOG_FILE_SUFFIX = ".log";

    /**
     * Phase 9: 環境変数からログディレクトリを取得
     */
    private static String initLogDirectory() {
        String envLogDir = System.getenv("LOG_DIR");
        if (envLogDir != null && !envLogDir.isEmpty()) {
            return envLogDir;
        }
        return "src/log";  // デフォルト
    }

    /**
     * Phase 9: ログディレクトリを取得
     * @return ログディレクトリのパス
     */
    public static String getLogDirectory() {
        return LOG_DIRECTORY;
    }

    // ログモード
    private boolean loggingEnabled = false;

    // ファイルライター
    private PrintWriter logWriter;

    // シミュレーション時間管理
    private long simulationStartTime = 0;
    private int currentLogHour = 0;
    private String currentLogFilePath;

    // Phase 9: スレッド数遷移ログ（LOG_DIRECTORYを使用）
    private String threadCountLogFile;
    private PrintWriter threadCountWriter;
    private int lastRecordedThreadCount = -1;

    /**
     * プライベートコンストラクタ
     */
    private LogManager() {
        // ログディレクトリが存在しない場合は作成
        File logDir = new File(LOG_DIRECTORY);
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
    }
    
    /**
     * シングルトンインスタンスを取得する
     * @return LogManagerのインスタンス
     */
    public static synchronized LogManager getInstance() {
        if (instance == null) {
            instance = new LogManager();
        }
        return instance;
    }
    
    /**
     * シミュレーション開始時刻を設定する
     * これを呼び出すと、ログファイルが1時間ごとにローテーションされる
     * @param startTime シミュレーション開始時刻（ミリ秒）
     */
    public void setSimulationStartTime(long startTime) {
        this.simulationStartTime = startTime;
        this.currentLogHour = 0;

        // 既存のログファイルがあれば閉じる
        if (logWriter != null) {
            logWriter.close();
            logWriter = null;
        }

        // 最初のログファイルを開く
        if (loggingEnabled) {
            openLogFileForHour(0);
        }
    }

    /**
     * 指定した時間のログファイルを開く
     * @param hour シミュレーション開始からの時間（時）
     */
    private void openLogFileForHour(int hour) {
        // 既存のログファイルがあれば閉じる
        if (logWriter != null) {
            logWriter.flush();
            logWriter.close();
            logWriter = null;
        }

        try {
            currentLogFilePath = LOG_FILE_PREFIX + hour + LOG_FILE_SUFFIX;
            logWriter = new PrintWriter(new FileWriter(currentLogFilePath, false));
            currentLogHour = hour;

            // ヘッダーを書き込む
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            String timestamp = dateFormat.format(new Date());
            logWriter.println("# ログファイル: simulator_" + hour + ".log");
            logWriter.println("# 開始時刻: " + timestamp);
            logWriter.println("# シミュレーション時間: " + hour + "時間目〜" + (hour + 1) + "時間目");
            logWriter.println("#");
            logWriter.flush();

            System.out.println("[LogManager] ログファイルをローテーション: " + currentLogFilePath);
        } catch (IOException e) {
            System.err.println("ログファイルのオープンに失敗しました: " + e.getMessage());
        }
    }

    /**
     * シミュレーション時間に基づいてログファイルをローテーションする
     */
    private void checkAndRotateLogFile() {
        if (simulationStartTime == 0) {
            return; // シミュレーション時間管理が有効でない場合は何もしない
        }

        long elapsedMs = System.currentTimeMillis() - simulationStartTime;
        int newHour = (int) (elapsedMs / (60 * 60 * 1000));

        if (newHour > currentLogHour) {
            openLogFileForHour(newHour);
        }
    }

    /**
     * ログモードを設定する
     * @param enabled ログを有効にするかどうか
     */
    public void setLoggingEnabled(boolean enabled) {
        this.loggingEnabled = enabled;

        if (enabled) {
            try {
                // 初期ログファイルを開く（シミュレーション時間が設定されていれば hour 0 から）
                if (simulationStartTime > 0) {
                    openLogFileForHour(0);
                } else {
                    currentLogFilePath = LOG_FILE_PREFIX + "0" + LOG_FILE_SUFFIX;
                    logWriter = new PrintWriter(new FileWriter(currentLogFilePath, false));
                }
                log("ログ記録を開始しました");
            } catch (IOException e) {
                System.err.println("ログファイルのオープンに失敗しました: " + e.getMessage());
                this.loggingEnabled = false;
            }
        } else if (logWriter != null) {
            log("ログ記録を終了しました");
            logWriter.close();
            logWriter = null;
        }
    }
    
    /**
     * ログモードが有効かどうかを取得する
     * @return ログモードが有効な場合はtrue
     */
    public boolean isLoggingEnabled() {
        return loggingEnabled;
    }
    
    /**
     * ログを記録する
     * @param message ログメッセージ
     */
    public void log(String message) {
        // タイムスタンプを追加
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        String timestamp = dateFormat.format(new Date());
        String formattedMessage = timestamp + " - " + message;

        // コンソールに出力
        System.out.println(formattedMessage);

        // ログファイルに書き込み
        if (loggingEnabled) {
            // ログファイルのローテーションチェック
            checkAndRotateLogFile();

            if (logWriter != null) {
                logWriter.println(formattedMessage);
                logWriter.flush();
            }
        }
    }
    
    /**
     * エラーログを記録する
     * @param message エラーメッセージ
     */
    public void error(String message) {
        // タイムスタンプを追加
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        String timestamp = dateFormat.format(new Date());
        String formattedMessage = timestamp + " - ERROR: " + message;

        // コンソールに出力
        System.err.println(formattedMessage);

        // ログファイルに書き込み
        if (loggingEnabled) {
            checkAndRotateLogFile();

            if (logWriter != null) {
                logWriter.println(formattedMessage);
                logWriter.flush();
            }
        }
    }

    /**
     * エラーログを記録する
     * @param message エラーメッセージ
     * @param e 例外
     */
    public void error(String message, Exception e) {
        // タイムスタンプを追加
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        String timestamp = dateFormat.format(new Date());
        String formattedMessage = timestamp + " - ERROR: " + message + ": " + e.getMessage();

        // コンソールに出力
        System.err.println(formattedMessage);

        // ログファイルに書き込み
        if (loggingEnabled) {
            checkAndRotateLogFile();

            if (logWriter != null) {
                logWriter.println(formattedMessage);
                e.printStackTrace(logWriter);
                logWriter.flush();
            }
        }

        // コンソールにスタックトレースを出力
        e.printStackTrace(System.err);
    }
    
    /**
     * リソースを解放する
     */
    public void close() {
        if (logWriter != null) {
            logWriter.close();
            logWriter = null;
        }
        // スレッド数遷移ログを閉じる
        closeThreadCountLog();
        // シミュレーション時間管理をリセット
        simulationStartTime = 0;
        currentLogHour = 0;
        currentLogFilePath = null;
    }

    /**
     * 現在のログファイルパスを取得する
     * @return 現在のログファイルパス
     */
    public String getCurrentLogFilePath() {
        return currentLogFilePath;
    }

    /**
     * 現在のログファイル番号（時間）を取得する
     * @return 現在のログファイル番号
     */
    public int getCurrentLogHour() {
        return currentLogHour;
    }

    /**
     * スレッド数遷移ログを初期化する
     * シミュレーション開始時に呼び出す
     */
    public void initThreadCountLog() {
        try {
            // Phase 9: LOG_DIRECTORYを使用
            threadCountLogFile = LOG_DIRECTORY + "/thread_count.log";
            threadCountWriter = new PrintWriter(new FileWriter(threadCountLogFile, false));
            threadCountWriter.println("# スレッド数遷移ログ");
            threadCountWriter.println("# timestamp,elapsed_sec,thread_count,event");
            threadCountWriter.flush();
            lastRecordedThreadCount = -1;
            System.out.println("[LogManager] スレッド数遷移ログを初期化: " + threadCountLogFile);
        } catch (IOException e) {
            System.err.println("スレッド数遷移ログのオープンに失敗しました: " + e.getMessage());
        }
    }

    /**
     * スレッド数の変更を記録する
     * 前回と異なる場合のみ出力する
     *
     * @param threadCount 現在のスレッド数
     * @param event イベント種別（SCALE_UP, SCALE_DOWN, INIT など）
     */
    public void logThreadCountChange(int threadCount, String event) {
        if (threadCount == lastRecordedThreadCount) {
            return; // 変更なしの場合は出力しない
        }

        lastRecordedThreadCount = threadCount;

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        String timestamp = dateFormat.format(new Date());

        double elapsedSec = 0.0;
        if (simulationStartTime > 0) {
            elapsedSec = (System.currentTimeMillis() - simulationStartTime) / 1000.0;
        }

        String logLine = String.format("%s,%.3f,%d,%s", timestamp, elapsedSec, threadCount, event);

        // コンソールにも出力
        System.out.println("[ThreadCount] " + logLine);

        // ファイルに書き込み
        if (threadCountWriter != null) {
            threadCountWriter.println(logLine);
            threadCountWriter.flush();
        }
    }

    /**
     * スレッド数遷移ログを閉じる
     */
    public void closeThreadCountLog() {
        if (threadCountWriter != null) {
            threadCountWriter.flush();
            threadCountWriter.close();
            threadCountWriter = null;
            System.out.println("[LogManager] スレッド数遷移ログを閉じました");
        }
    }

    /**
     * Phase 7-10: メモリ使用量をログに記録する
     */
    public void logMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();

        String message = String.format(
            "Phase 7-10 [メモリ]: 使用=%dMB, 空き=%dMB, 合計=%dMB, 最大=%dMB (使用率=%.1f%%)",
            usedMemory / (1024 * 1024),
            freeMemory / (1024 * 1024),
            totalMemory / (1024 * 1024),
            maxMemory / (1024 * 1024),
            (double) usedMemory / totalMemory * 100
        );

        log(message);
    }

    /**
     * Phase 7-10: メモリ使用量を取得する（MB単位）
     * @return 使用中メモリ（MB）
     */
    public long getUsedMemoryMB() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    }

    /**
     * Phase 7-10: 統計サマリーをログに出力
     * @param clientCount 生成クライアント数
     * @param elapsedMs 経過時間（ミリ秒）
     */
    public void logSimulationSummary(int clientCount, long elapsedMs) {
        log("=== Phase 7-10 シミュレーションサマリー ===");
        log("  生成クライアント数: " + clientCount);
        log("  経過時間: " + (elapsedMs / 1000) + "秒 (" + (elapsedMs / 60000) + "分)");
        log("  平均生成間隔: " + String.format("%.2f", (double) elapsedMs / clientCount / 1000) + "秒/クライアント");
        logMemoryUsage();
        log("==========================================");
    }
}
