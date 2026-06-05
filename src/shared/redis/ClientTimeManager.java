package shared.redis;

import operator.BoundaryController;
import shared.util.LogManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 事業者ごとの時間計測管理クラス
 * Phase 4: 経路割り当てから全UAV飛行完了までの時間を計測
 *
 * 計測開始: 事業者の最初のUAVに経路が割り当てられた時点
 * 計測終了: 事業者の全UAVが飛行完了した時点
 */
public class ClientTimeManager {

    private static ClientTimeManager instance;

    // 事業者ごとの経路割り当て開始時刻（ミリ秒）
    private final ConcurrentHashMap<Integer, Long> clientStartTime = new ConcurrentHashMap<>();

    // 事業者ごとの必要UAV数
    private final ConcurrentHashMap<Integer, Integer> clientRequiredUAVs = new ConcurrentHashMap<>();

    // 事業者ごとの完了UAV数
    private final ConcurrentHashMap<Integer, AtomicInteger> clientCompletedUAVs = new ConcurrentHashMap<>();

    // 事業者ごとの完了フラグ（重複出力防止）
    private final ConcurrentHashMap<Integer, Boolean> clientCompleted = new ConcurrentHashMap<>();

    private ClientTimeManager() {
    }

    public static synchronized ClientTimeManager getInstance() {
        if (instance == null) {
            instance = new ClientTimeManager();
        }
        return instance;
    }

    /**
     * 事業者の経路割り当て開始を記録
     * 最初のUAVに経路が割り当てられた時点で呼び出す
     *
     * @param clientId 事業者ID
     * @param requiredUAVs 必要UAV数（経路待ち含む全UAV）
     */
    public void startClientTime(int clientId, int requiredUAVs) {
        // 既に開始済みの場合はスキップ
        if (clientStartTime.containsKey(clientId)) {
            return;
        }

        long startTime = System.currentTimeMillis();
        clientStartTime.put(clientId, startTime);
        clientRequiredUAVs.put(clientId, requiredUAVs);
        clientCompletedUAVs.put(clientId, new AtomicInteger(0));
        clientCompleted.put(clientId, false);

        LogManager.getInstance().log(
            "Phase 4 [時間計測開始]: client" + clientId +
            " 経路割り当て開始 (必要UAV数=" + requiredUAVs + ")"
        );
    }

    /**
     * UAV飛行完了を記録
     * 全UAV完了時にclientTime.csvに出力
     *
     * @param clientId 事業者ID
     */
    public void onUAVCompleted(int clientId) {
        AtomicInteger completedCount = clientCompletedUAVs.get(clientId);
        if (completedCount == null) {
            // startClientTimeが呼ばれていない場合（従来の手法など）
            return;
        }

        int completed = completedCount.incrementAndGet();
        Integer required = clientRequiredUAVs.get(clientId);

        if (required == null) {
            return;
        }

        // 全UAV完了チェック
        if (completed >= required) {
            Boolean alreadyCompleted = clientCompleted.get(clientId);
            if (alreadyCompleted != null && !alreadyCompleted) {
                clientCompleted.put(clientId, true);
                outputClientTime(clientId);
            }
        }
    }

    /**
     * 事業者の時間をclientTime.csvに出力
     *
     * @param clientId 事業者ID
     */
    private void outputClientTime(int clientId) {
        Long startTime = clientStartTime.get(clientId);
        if (startTime == null) {
            return;
        }

        long endTime = System.currentTimeMillis();
        double elapsedSeconds = (endTime - startTime) / 1000.0;
        int requiredUAVs = clientRequiredUAVs.getOrDefault(clientId, 0);

        LogManager.getInstance().log(
            "Phase 4 [時間計測完了]: client" + clientId +
            " 全UAV飛行完了 (UAV数=" + requiredUAVs +
            ", 経過時間=" + String.format("%.2f", elapsedSeconds) + "s)"
        );

        // clientTime.csvに出力
        try {
            writeClientTimeCSV(clientId, requiredUAVs, elapsedSeconds);
        } catch (IOException e) {
            LogManager.getInstance().error("Phase 4: clientTime.csv出力エラー", e);
        }
    }

    /**
     * clientTime.csvファイルに出力
     * Phase 7-1: 出力先: src/result/{scale}/{method}/time/clientTime.csv
     */
    private void writeClientTimeCSV(int clientId, int uavCount, double elapsedSeconds) throws IOException {
        // 現在の経路探索手法に基づいてディレクトリパスを作成
        String dirPath = BoundaryController.getResultDir() + "/time";

        // ディレクトリが存在しない場合は作成
        File dir = new File(dirPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        String filePath = dirPath + "/clientTime.csv";
        File file = new File(filePath);
        boolean isNewFile = !file.exists();

        try (FileWriter writer = new FileWriter(filePath, true)) {
            // ヘッダー出力（新規ファイルの場合）
            if (isNewFile) {
                writer.write("clientId,uavCount,elapsedTime(s)\n");
            }

            // データ出力
            writer.write(String.format("%d,%d,%.2f\n", clientId, uavCount, elapsedSeconds));
        }

        LogManager.getInstance().log(
            "Phase 4: clientTime.csv出力完了 (" + filePath + ")"
        );
    }

    /**
     * リセット（シミュレーション開始時に呼び出す）
     */
    public void reset() {
        clientStartTime.clear();
        clientRequiredUAVs.clear();
        clientCompletedUAVs.clear();
        clientCompleted.clear();
        LogManager.getInstance().log("Phase 4: ClientTimeManager リセット");
    }

    /**
     * 事業者の経路割り当てが開始されているか確認
     *
     * @param clientId 事業者ID
     * @return 開始済みの場合true
     */
    public boolean isStarted(int clientId) {
        return clientStartTime.containsKey(clientId);
    }

    /**
     * 事業者の完了UAV数を取得
     *
     * @param clientId 事業者ID
     * @return 完了UAV数
     */
    public int getCompletedCount(int clientId) {
        AtomicInteger count = clientCompletedUAVs.get(clientId);
        return count != null ? count.get() : 0;
    }

    /**
     * 事業者の必要UAV数を取得
     *
     * @param clientId 事業者ID
     * @return 必要UAV数
     */
    public int getRequiredCount(int clientId) {
        return clientRequiredUAVs.getOrDefault(clientId, 0);
    }
}
