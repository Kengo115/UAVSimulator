package server.util;

import controller.BoundaryController;
import item.Link;
import server.controller.ServerController;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Phase 7-4: リンク状態を記録するクラス
 * Phase 7-5: 10秒ごとのスナップショット記録機能を追加
 * Phase 8-Fix: 双方向リンクを無向グラフとして扱い、正規化キーで出力
 * Link.javaを使用して各リンクの利用状況を時系列で記録
 */
public class LinkStatusRecorder {

    private static LinkStatusRecorder instance;

    // シミュレーション開始時刻
    private long simulationStartTime = 0;

    // 出力ファイルパス
    private String outputFilePath = null;

    // ヘッダー出力済みフラグ
    private boolean headerWritten = false;

    // Phase 7-5: スナップショット用スケジューラ
    private ScheduledExecutorService snapshotScheduler;
    // Phase 7-7: 設定ファイルから変更可能
    private int snapshotIntervalSeconds = 10;
    private int snapshotCount = 0;

    // 混雑率記録用
    private String congestionRateFilePath = null;
    private boolean congestionRateHeaderWritten = false;

    private LinkStatusRecorder() {
    }

    public static synchronized LinkStatusRecorder getInstance() {
        if (instance == null) {
            instance = new LinkStatusRecorder();
        }
        return instance;
    }

    /**
     * シミュレーション開始時に呼び出す
     * タイムスタンプの基準時刻を設定
     */
    public void startSimulation() {
        simulationStartTime = System.currentTimeMillis();
        headerWritten = false;
        outputFilePath = null;
        snapshotCount = 0;
        congestionRateFilePath = null;
        congestionRateHeaderWritten = false;

        // 全リンクの状態をリセット
        resetAllLinkStatus();

        // Phase 7-5: スナップショットスケジューラを開始
        startSnapshotScheduler();

        LogManager.getInstance().log("Phase 7-4: LinkStatusRecorder シミュレーション開始");
    }

    /**
     * Phase 7-5: スナップショットスケジューラを開始
     */
    private void startSnapshotScheduler() {
        // 既存のスケジューラがあれば停止
        stopSnapshotScheduler();

        snapshotScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LinkStatusRecorder-Snapshot");
            t.setDaemon(true);
            return t;
        });

        // スナップショット間隔でスナップショットを記録
        snapshotScheduler.scheduleAtFixedRate(
            this::takeSnapshot,
            snapshotIntervalSeconds,
            snapshotIntervalSeconds,
            TimeUnit.SECONDS
        );

        LogManager.getInstance().log("Phase 7-5: スナップショットスケジューラ開始 (間隔=" + snapshotIntervalSeconds + "秒)");
    }

    /**
     * Phase 7-5: スナップショットスケジューラを停止
     */
    private void stopSnapshotScheduler() {
        if (snapshotScheduler != null && !snapshotScheduler.isShutdown()) {
            snapshotScheduler.shutdown();
            try {
                if (!snapshotScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    snapshotScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                snapshotScheduler.shutdownNow();
            }
            LogManager.getInstance().log("Phase 7-5: スナップショットスケジューラ停止");
        }
    }

    /**
     * Phase 7-5: スナップショットを記録
     */
    public void takeSnapshot() {
        if (simulationStartTime == 0) {
            return; // シミュレーション未開始
        }

        long timestamp = System.currentTimeMillis() - simulationStartTime;
        snapshotCount++;

        try {
            writeSnapshotToCSV(timestamp);

            // 混雑率をCSVに記録（10秒ごと）
            writeCongestionRateToCSV(snapshotCount * snapshotIntervalSeconds);

            LogManager.getInstance().log(String.format(
                "Phase 7-5: スナップショット #%d 記録 (timestamp=%dms, 平均負荷率=%.2f%%, 混雑リンク率=%.2f%%)",
                snapshotCount, timestamp, getAverageLoadRate(), getCongestedLinkRate()
            ));
        } catch (IOException e) {
            LogManager.getInstance().error("Phase 7-5: スナップショット記録エラー", e);
        }
    }

    /**
     * 混雑率をCSVファイルに追記
     * @param timeSeconds シミュレーション開始からの経過時間（秒）
     */
    private synchronized void writeCongestionRateToCSV(int timeSeconds) throws IOException {
        if (congestionRateFilePath == null) {
            BoundaryController.RouteSearchMethod method = BoundaryController.getCurrentMethod();
            String scaleDir = BoundaryController.isLargeScaleMode() ? "large_scale" : "small_scale";
            String dirPath = "src/result/" + scaleDir + "/" + method.getName() + "/link_status";

            File dir = new File(dirPath);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            congestionRateFilePath = dirPath + "/congestion_rate.csv";
        }

        try (FileWriter writer = new FileWriter(congestionRateFilePath, true)) {
            if (!congestionRateHeaderWritten) {
                writer.write("time,AverageLoadRate,CongestedLinkRate\n");
                congestionRateHeaderWritten = true;
            }
            writer.write(String.format("%d,%.2f,%.2f\n",
                timeSeconds, getAverageLoadRate(), getCongestedLinkRate()));
        }
    }

    /**
     * Phase 7-5: スナップショットをCSVファイルに書き込む
     * Phase 8-Fix: 正規化キー（小さいノードIDを先）で出力し、冗長な逆方向リンクを除外
     */
    private synchronized void writeSnapshotToCSV(long timestamp) throws IOException {
        Link[][] links = ServerController.getLinkArray();
        int nodeCount = ServerController.getNodeCount();

        if (links == null || nodeCount == 0) {
            return;
        }

        // 出力先パスを決定
        BoundaryController.RouteSearchMethod method = BoundaryController.getCurrentMethod();
        String scaleDir = BoundaryController.isLargeScaleMode() ? "large_scale" : "small_scale";
        String dirPath = "src/result/" + scaleDir + "/" + method.getName() + "/snapshot";

        File dir = new File(dirPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        String filename = dirPath + "/snapshot_" + timestamp + ".csv";

        try (FileWriter writer = new FileWriter(filename)) {
            // ヘッダー
            writer.write("node_a,node_b,flying_count,waiting_count,current_capacity,init_capacity,load_rate\n");

            // Phase 8-Fix: 正規化キーで出力（i < j のみ、双方向リンクを1行にまとめる）
            for (int i = 0; i < nodeCount; i++) {
                for (int j = i + 1; j < nodeCount; j++) {
                    Link link = links[i][j];
                    if (link != null && link.getInitCapacity() > 0) {
                        int flying = (int) link.getFlyingUAV();
                        int waiting = link.getWaitingUAV();
                        double initCapacity = link.getInitCapacity();
                        double currentCapacity = link.getCapacity();
                        double loadRate = link.getLoadRate();

                        // Phase 8-Fix: 正規化キー (node_a < node_b) で出力
                        writer.write(String.format("%d,%d,%d,%d,%.1f,%.1f,%.2f\n",
                                i, j, flying, waiting, currentCapacity, initCapacity, loadRate));
                    }
                }
            }
        }
    }

    /**
     * 全リンクの状態をリセット
     */
    private void resetAllLinkStatus() {
        Link[][] links = ServerController.getLinkArray();
        int nodeCount = ServerController.getNodeCount();

        if (links == null || nodeCount == 0) {
            return;
        }

        for (int i = 0; i < nodeCount; i++) {
            for (int j = 0; j < nodeCount; j++) {
                if (links[i][j] != null) {
                    links[i][j].resetStatus();
                }
            }
        }
    }

    /**
     * UAVがリンクに進入した時に呼び出す
     * @param fromNode 始点ノード
     * @param toNode 終点ノード
     */
    public void onLinkEnter(int fromNode, int toNode) {
        Link link = ServerController.getLinkStatic(fromNode, toNode);
        if (link != null) {
            link.incrementFlyingUAV();
            recordStatus(fromNode, toNode, link, "ENTER");
        }
        // 双方向リンク対応: 逆方向も更新
        Link reverseLink = ServerController.getLinkStatic(toNode, fromNode);
        if (reverseLink != null) {
            reverseLink.incrementFlyingUAV();
        }
    }

    /**
     * UAVがリンクを通過（退出）した時に呼び出す
     * @param fromNode 始点ノード
     * @param toNode 終点ノード
     */
    public void onLinkExit(int fromNode, int toNode) {
        Link link = ServerController.getLinkStatic(fromNode, toNode);
        if (link != null) {
            link.decrementFlyingUAV();
            recordStatus(fromNode, toNode, link, "EXIT");
        }
        // 双方向リンク対応: 逆方向も更新
        Link reverseLink = ServerController.getLinkStatic(toNode, fromNode);
        if (reverseLink != null) {
            reverseLink.decrementFlyingUAV();
        }
    }

    /**
     * UAVがリンクで待機開始した時に呼び出す
     * Phase 8-Fix: 双方向リンク対応（両方向を更新）
     * @param fromNode 始点ノード
     * @param toNode 終点ノード
     */
    public void onWaitingStart(int fromNode, int toNode) {
        Link link = ServerController.getLinkStatic(fromNode, toNode);
        if (link != null) {
            link.incrementWaitingUAV();
            recordStatus(fromNode, toNode, link, "WAIT_START");
        }
        // Phase 8-Fix: 双方向リンク対応（逆方向も更新）
        Link reverseLink = ServerController.getLinkStatic(toNode, fromNode);
        if (reverseLink != null) {
            reverseLink.incrementWaitingUAV();
        }
    }

    /**
     * UAVがリンクでの待機終了した時に呼び出す
     * Phase 8-Fix: 双方向リンク対応（両方向を更新）
     * @param fromNode 始点ノード
     * @param toNode 終点ノード
     */
    public void onWaitingEnd(int fromNode, int toNode) {
        Link link = ServerController.getLinkStatic(fromNode, toNode);
        if (link != null) {
            link.decrementWaitingUAV();
            recordStatus(fromNode, toNode, link, "WAIT_END");
        }
        // Phase 8-Fix: 双方向リンク対応（逆方向も更新）
        Link reverseLink = ServerController.getLinkStatic(toNode, fromNode);
        if (reverseLink != null) {
            reverseLink.decrementWaitingUAV();
        }
    }

    /**
     * リンク状態をCSVに記録
     */
    private void recordStatus(int fromNode, int toNode, Link link, String event) {
        if (simulationStartTime == 0) {
            return; // シミュレーション未開始
        }

        long timestamp = System.currentTimeMillis() - simulationStartTime;
        int flying = (int) link.getFlyingUAV();
        int waiting = link.getWaitingUAV();
        double initCapacity = link.getInitCapacity();
        double currentCapacity = link.getCapacity();
        double loadRate = link.getLoadRate();

        try {
            writeToCSV(timestamp, fromNode, toNode, flying, waiting, initCapacity, currentCapacity, loadRate, event);
        } catch (IOException e) {
            LogManager.getInstance().error("Phase 7-4: リンク状態記録エラー", e);
        }
    }

    /**
     * CSVファイルに書き込む
     * @param timestampMs タイムスタンプ（ミリ秒）
     */
    private synchronized void writeToCSV(long timestampMs, int fromNode, int toNode,
                                          int flying, int waiting, double initCapacity,
                                          double currentCapacity, double loadRate, String event) throws IOException {
        if (outputFilePath == null) {
            // 出力先パスを決定
            BoundaryController.RouteSearchMethod method = BoundaryController.getCurrentMethod();
            String scaleDir = BoundaryController.isLargeScaleMode() ? "large_scale" : "small_scale";
            String dirPath = "src/result/" + scaleDir + "/" + method.getName() + "/link_status";

            File dir = new File(dirPath);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            outputFilePath = dirPath + "/link_status.csv";
        }

        // ミリ秒を秒に変換
        double timestampSec = timestampMs / 1000.0;

        try (FileWriter writer = new FileWriter(outputFilePath, true)) {
            if (!headerWritten) {
                writer.write("timestamp,link_from,link_to,flying_count,waiting_count,init_capacity,current_capacity,load_rate,event\n");
                headerWritten = true;
            }
            writer.write(String.format("%.2f,%d,%d,%d,%d,%.1f,%.1f,%.2f,%s\n",
                    timestampSec, fromNode, toNode, flying, waiting, initCapacity, currentCapacity, loadRate, event));
        }
    }

    /**
     * 現在のリンク負荷率を取得
     * @param fromNode 始点ノード
     * @param toNode 終点ノード
     * @return 負荷率（%）
     */
    public double getLinkLoadRate(int fromNode, int toNode) {
        Link link = ServerController.getLinkStatic(fromNode, toNode);
        if (link != null) {
            return link.getLoadRate();
        }
        return 0.0;
    }

    /**
     * 全リンクの平均負荷率を取得
     * Phase 8-Fix: 正規化キー（i < j）で重複カウントを防止
     * @return 平均負荷率（%）
     */
    public double getAverageLoadRate() {
        Link[][] links = ServerController.getLinkArray();
        int nodeCount = ServerController.getNodeCount();

        if (links == null || nodeCount == 0) {
            return 0.0;
        }

        double totalLoadRate = 0.0;
        int linkCount = 0;

        // Phase 8-Fix: i < j のみカウント（双方向リンクを1回だけカウント）
        for (int i = 0; i < nodeCount; i++) {
            for (int j = i + 1; j < nodeCount; j++) {
                Link link = links[i][j];
                if (link != null && link.getInitCapacity() > 0) {
                    totalLoadRate += link.getLoadRate();
                    linkCount++;
                }
            }
        }

        return linkCount > 0 ? totalLoadRate / linkCount : 0.0;
    }

    /**
     * 混雑リンク率を取得（負荷率80%以上のリンクの割合）
     * @return 混雑リンク率（%）
     */
    public double getCongestedLinkRate() {
        return getCongestedLinkRate(80.0);
    }

    /**
     * 混雑リンク率を取得（指定閾値以上のリンクの割合）
     * Phase 8-Fix: 正規化キー（i < j）で重複カウントを防止
     * @param threshold 混雑判定閾値（%）
     * @return 混雑リンク率（%）
     */
    public double getCongestedLinkRate(double threshold) {
        Link[][] links = ServerController.getLinkArray();
        int nodeCount = ServerController.getNodeCount();

        if (links == null || nodeCount == 0) {
            return 0.0;
        }

        int congestedCount = 0;
        int linkCount = 0;

        // Phase 8-Fix: i < j のみカウント（双方向リンクを1回だけカウント）
        for (int i = 0; i < nodeCount; i++) {
            for (int j = i + 1; j < nodeCount; j++) {
                Link link = links[i][j];
                if (link != null && link.getInitCapacity() > 0) {
                    if (link.getLoadRate() >= threshold) {
                        congestedCount++;
                    }
                    linkCount++;
                }
            }
        }

        return linkCount > 0 ? (double) congestedCount / linkCount * 100.0 : 0.0;
    }

    /**
     * リセット（シミュレーション終了時に呼び出す）
     */
    public void reset() {
        // Phase 7-5: スナップショットスケジューラを停止
        stopSnapshotScheduler();

        simulationStartTime = 0;
        outputFilePath = null;
        headerWritten = false;
        snapshotCount = 0;
        congestionRateFilePath = null;
        congestionRateHeaderWritten = false;

        // 全リンクの状態をリセット
        resetAllLinkStatus();

        LogManager.getInstance().log("Phase 7-4: LinkStatusRecorder リセット");
    }

    /**
     * シミュレーション終了時に呼び出す
     * 最終スナップショットを記録してからリセット
     */
    public void stopSimulation() {
        // 最終スナップショットを記録
        takeSnapshot();

        // サマリー出力
        logSummary();

        // リセット
        reset();

        LogManager.getInstance().log("Phase 7-5: シミュレーション終了 (総スナップショット数=" + snapshotCount + ")");
    }

    /**
     * サマリー情報をログ出力
     */
    public void logSummary() {
        double avgLoadRate = getAverageLoadRate();
        double congestedRate = getCongestedLinkRate();

        LogManager.getInstance().log(String.format(
            "Phase 7-4 [空域混雑サマリー]: 平均負荷率=%.2f%%, 混雑リンク率(>=80%%)=%.2f%%",
            avgLoadRate, congestedRate
        ));
    }

    /**
     * Phase 7-5: スナップショット数を取得
     * @return スナップショット数
     */
    public int getSnapshotCount() {
        return snapshotCount;
    }

    /**
     * Phase 7-7: スナップショット間隔を設定
     * @param seconds スナップショット間隔（秒）
     */
    public void setSnapshotIntervalSeconds(int seconds) {
        if (seconds > 0) {
            this.snapshotIntervalSeconds = seconds;
            LogManager.getInstance().log("Phase 7-7: スナップショット間隔を " + seconds + " 秒に設定");
        }
    }

    /**
     * Phase 7-7: スナップショット間隔を取得
     * @return スナップショット間隔（秒）
     */
    public int getSnapshotIntervalSeconds() {
        return snapshotIntervalSeconds;
    }
}
