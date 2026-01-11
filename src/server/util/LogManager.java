package server.util;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * ログを管理するクラス
 * コンソールへの出力とログファイルへの書き込みを管理する
 */
public class LogManager {
    // シングルトンインスタンス
    private static LogManager instance;
    
    // ログファイルのパス
    private static final String LOG_DIRECTORY = "src/log";
    private static final String LOG_FILE = LOG_DIRECTORY + "/simulator.log";
    
    // ログモード
    private boolean loggingEnabled = false;
    
    // ファイルライター
    private PrintWriter logWriter;
    
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
     * ログモードを設定する
     * @param enabled ログを有効にするかどうか
     */
    public void setLoggingEnabled(boolean enabled) {
        this.loggingEnabled = enabled;
        
        if (enabled) {
            try {
                // ログファイルを追記モードでオープン
                logWriter = new PrintWriter(new FileWriter(LOG_FILE, true));
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
        if (loggingEnabled && logWriter != null) {
            logWriter.println(formattedMessage);
            logWriter.flush();
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
        if (loggingEnabled && logWriter != null) {
            logWriter.println(formattedMessage);
            logWriter.flush();
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
        if (loggingEnabled && logWriter != null) {
            logWriter.println(formattedMessage);
            e.printStackTrace(logWriter);
            logWriter.flush();
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
