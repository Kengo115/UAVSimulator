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
}
