package shared.util;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Link 117-123 専用デバッグロガー
 * 問題調査のための詳細ログを専用ファイルに出力
 * Phase 9: 環境変数LOG_DIRに対応
 */
public class Link117DebugLogger {
    private static Link117DebugLogger instance;
    private String logFilePath;
    private PrintWriter writer;
    private long startTime = 0;

    private Link117DebugLogger() {
        try {
            // Phase 9: LogManager.getLogDirectory()を使用
            String logDir = LogManager.getLogDirectory();
            File logDirFile = new File(logDir);
            if (!logDirFile.exists()) {
                logDirFile.mkdirs();
            }
            logFilePath = logDir + "/link_117_123_debug.log";
            writer = new PrintWriter(new FileWriter(logFilePath, false));
            startTime = System.currentTimeMillis();
            writer.println("# Link 117-123 Debug Log");
            writer.println("# Started at: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            writer.println("# Format: elapsed_sec,event_type,details");
            writer.println("#");
            writer.flush();
        } catch (IOException e) {
            System.err.println("Failed to create Link117DebugLogger: " + e.getMessage());
        }
    }

    public static synchronized Link117DebugLogger getInstance() {
        if (instance == null) {
            instance = new Link117DebugLogger();
        }
        return instance;
    }

    /**
     * ログを出力
     * @param eventType イベント種別 (SYNC, EPS_UPDATE, etc.)
     * @param details 詳細情報
     */
    public synchronized void log(String eventType, String details) {
        if (writer == null) return;

        double elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0;
        String line = String.format("%.3f,%s,%s", elapsedSec, eventType, details);
        writer.println(line);
        writer.flush();
    }

    /**
     * syncCapacitiesToMemory時のログ
     */
    public void logSync(int from, int to, double redisCapacity, double oldMemory, double initCap) {
        log("SYNC", String.format("link=%d-%d,redis=%.2f,oldMem=%.2f,initCap=%.2f",
            from, to, redisCapacity, oldMemory, initCap));
    }

    /**
     * EPS updateTubeThickness時のログ
     */
    public void logEPSUpdate(int from, int to, int iteration, double capacity, double initCap,
                              double flow, double tanh, double oldThickness, double newThickness) {
        log("EPS_UPDATE", String.format("link=%d-%d,iter=%d,cap=%.2f,initCap=%.2f,flow=%.4f,tanh=%.4f,thick=%.4f->%.4f",
            from, to, iteration, capacity, initCap, flow, tanh, oldThickness, newThickness));
    }

    /**
     * 経路割り当て時のログ
     */
    public void logRouteAssign(int clientId, int uavId, int[] path, int flow) {
        StringBuilder pathStr = new StringBuilder();
        for (int i = 0; i < path.length; i++) {
            if (i > 0) pathStr.append("->");
            pathStr.append(path[i]);
        }

        // 117-123を含むか確認
        boolean contains117_123 = false;
        for (int i = 0; i < path.length - 1; i++) {
            if ((path[i] == 117 && path[i+1] == 123) || (path[i] == 123 && path[i+1] == 117)) {
                contains117_123 = true;
                break;
            }
        }

        if (contains117_123) {
            log("ROUTE_ASSIGN", String.format("client=%d,uav=%d,flow=%d,path=%s,CONTAINS_117_123=true",
                clientId, uavId, flow, pathStr.toString()));
        }
    }

    /**
     * 容量消費/回復時のログ
     */
    public void logCapacityChange(int from, int to, String operation, double oldCap, double newCap) {
        if ((from == 117 && to == 123) || (from == 123 && to == 117)) {
            log("CAP_CHANGE", String.format("link=%d-%d,op=%s,old=%.2f,new=%.2f",
                from, to, operation, oldCap, newCap));
        }
    }

    /**
     * 待機キュー操作のログ
     */
    public void logWaitingQueue(int from, int to, String operation, int waitingCount) {
        if ((from == 117 && to == 123) || (from == 123 && to == 117)) {
            log("WAITING_QUEUE", String.format("link=%d-%d,op=%s,count=%d",
                from, to, operation, waitingCount));
        }
    }

    /**
     * ロガーを閉じる
     */
    public void close() {
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
    }

    /**
     * リセット（新しいシミュレーション開始時）
     */
    public static synchronized void reset() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }
}
