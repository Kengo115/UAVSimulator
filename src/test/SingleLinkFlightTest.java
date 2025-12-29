package test;

import server.redis.*;
import server.worker.UAVWorker;
import server.util.LogManager;

import java.io.IOException;
import java.util.concurrent.*;

/**
 * Phase 3b-2b: 単一リンク飛行テスト
 *
 * テスト内容:
 * 1. 単一リンク（0→1）の経路でUAVを飛行
 * 2. Workerが飛行を処理
 * 3. 完了イベントがメインプロセスに届く
 */
public class SingleLinkFlightTest {

    private static final int UAV_COUNT = 3;           // テストするUAV数
    private static final double LINK_DISTANCE = 50.0; // リンク距離（メートル）
    private static final double UAV_SPEED = 10.0;     // UAV速度（m/s）
    // 期待される飛行時間: 50m / 10m/s = 5秒

    public static void main(String[] args) {
        System.out.println("=== Phase 3b-2b: 単一リンク飛行テスト ===");
        System.out.println();
        System.out.println("テスト条件:");
        System.out.println("  - UAV数: " + UAV_COUNT);
        System.out.println("  - 経路: [0, 1] (単一リンク)");
        System.out.println("  - リンク距離: " + LINK_DISTANCE + "m");
        System.out.println("  - UAV速度: " + UAV_SPEED + "m/s");
        System.out.println("  - 期待飛行時間: " + (LINK_DISTANCE / UAV_SPEED) + "秒");
        System.out.println();

        RedisConnectionManager manager = RedisConnectionManager.getInstance();
        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        UAVCompletionListener listener = null;
        UAVWorker worker = null;

        try {
            // 1. Redis接続
            System.out.println("[1/5] Redis接続...");
            manager.connect();
            System.out.println("✓ 接続成功");
            System.out.println();

            // 2. 完了リスナーを開始
            System.out.println("[2/5] 完了リスナー開始...");
            listener = new UAVCompletionListener();
            listener.startListening();
            System.out.println("✓ リスナー開始");
            System.out.println();

            // 3. ジョブキューをクリアしてジョブを投入
            System.out.println("[3/5] ジョブ投入...");
            UAVJobQueue jobQueue = new UAVJobQueue();
            jobQueue.clearQueue();

            for (int i = 0; i < UAV_COUNT; i++) {
                UAVJob job = createTestJob(i);
                jobQueue.enqueueJob(job);
                System.out.println("  ジョブ投入: UAV " + i);
            }
            System.out.println("✓ " + UAV_COUNT + "件のジョブを投入");
            System.out.println();

            // 4. Workerを別スレッドで起動
            System.out.println("[4/5] Worker起動...");
            final UAVWorker testWorker = new UAVWorker("test-worker");
            worker = testWorker;

            workerExecutor.submit(() -> {
                testWorker.start();
            });
            System.out.println("✓ Worker起動");
            System.out.println();

            // 5. 完了を待機
            System.out.println("[5/5] 完了待機...");
            System.out.println("  期待: " + UAV_COUNT + "台のUAVが完了通知を送信");
            System.out.println();

            // タイムアウト付きで完了を待つ
            int maxWaitSeconds = (int) ((LINK_DISTANCE / UAV_SPEED) * UAV_COUNT) + 10;
            int waitedSeconds = 0;

            while (listener.getCompletedCount() < UAV_COUNT && waitedSeconds < maxWaitSeconds) {
                Thread.sleep(1000);
                waitedSeconds++;
                System.out.println("  待機中... 完了数: " + listener.getCompletedCount() + "/" + UAV_COUNT +
                    " (経過: " + waitedSeconds + "秒)");
            }

            System.out.println();

            // 結果判定
            int completedCount = listener.getCompletedCount();
            boolean success = completedCount == UAV_COUNT;

            System.out.println("=========================");
            System.out.println("テスト結果: " + completedCount + "/" + UAV_COUNT + " 完了");
            if (success) {
                System.out.println("✓ テスト成功！");
            } else {
                System.out.println("✗ テスト失敗（タイムアウトまたは完了数不足）");
            }
            System.out.println("=========================");

        } catch (IOException e) {
            System.err.println("✗ 接続エラー: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("✗ テスト実行エラー: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // クリーンアップ
            System.out.println();
            System.out.println("クリーンアップ中...");

            if (worker != null) {
                worker.stop();
            }

            workerExecutor.shutdownNow();
            try {
                workerExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // ignore
            }

            if (listener != null) {
                listener.stopListening();
            }

            manager.disconnect();
            System.out.println("✓ クリーンアップ完了");
        }
    }

    /**
     * テスト用のUAVJobを作成
     *
     * @param uavId UAV ID
     * @return UAVJob
     */
    private static UAVJob createTestJob(int uavId) {
        int[] path = {0, 1};  // 単一リンク: 0→1
        double[] linkDistances = {LINK_DISTANCE};  // 50m

        UAVJob job = new UAVJob(
            uavId,              // uavId
            1,                  // clientId
            path,               // path
            UAV_SPEED,          // speed (10 m/s)
            System.currentTimeMillis(), // startTime
            0,                  // sourceBeaconId
            1                   // destinationBeaconId
        );
        job.setLinkDistances(linkDistances);

        return job;
    }
}
