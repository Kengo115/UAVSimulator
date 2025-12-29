package test;

import server.redis.*;
import server.worker.UAVWorker;
import server.util.LogManager;

import java.io.IOException;
import java.util.concurrent.*;

/**
 * Phase 3b-2c: 複数リンク飛行テスト
 *
 * テスト内容:
 * 1. 複数リンク（0→1→4→5）の経路でUAVを飛行
 * 2. Workerがリンクごとに飛行し、リンク通過イベントを送信
 * 3. 完了イベントがメインプロセスに届く
 */
public class MultiLinkFlightTest {

    private static final int UAV_COUNT = 5;              // テストするUAV数
    private static final double[] LINK_DISTANCES = {50.0, 75.0, 60.0};  // 各リンクの距離
    private static final double UAV_SPEED = 10.0;        // UAV速度（m/s）
    private static final int[] PATH = {0, 1, 4, 5};      // 経路: 3リンク

    // 期待される飛行時間: (50 + 75 + 60) / 10 = 18.5秒
    private static final double EXPECTED_FLIGHT_TIME = 18.5;
    private static final double TOTAL_DISTANCE = 185.0;

    public static void main(String[] args) {
        System.out.println("=== Phase 3b-2c: 複数リンク飛行テスト ===");
        System.out.println();
        System.out.println("テスト条件:");
        System.out.println("  - UAV数: " + UAV_COUNT);
        System.out.println("  - 経路: [0, 1, 4, 5] (3リンク)");
        System.out.println("  - リンク距離: [50.0, 75.0, 60.0] (合計" + TOTAL_DISTANCE + "m)");
        System.out.println("  - UAV速度: " + UAV_SPEED + "m/s");
        System.out.println("  - 期待飛行時間: " + EXPECTED_FLIGHT_TIME + "秒/台");
        System.out.println();

        RedisConnectionManager manager = RedisConnectionManager.getInstance();
        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        UAVCompletionListener completionListener = null;
        UAVLinkPassedListener linkPassedListener = null;
        UAVWorker worker = null;

        try {
            // 1. Redis接続
            System.out.println("[1/5] Redis接続...");
            manager.connect();
            System.out.println("✓ 接続成功");
            System.out.println();

            // 2. リスナーを開始
            System.out.println("[2/5] リスナー開始...");
            completionListener = new UAVCompletionListener();
            completionListener.startListening();
            linkPassedListener = new UAVLinkPassedListener();
            linkPassedListener.startListening();
            System.out.println("✓ 完了リスナー + リンク通過リスナー開始");
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
            System.out.println("  期待: " + UAV_COUNT + "台のUAVが完了");
            System.out.println("  期待リンク通過数: " + (UAV_COUNT * (PATH.length - 1)) + "回");
            System.out.println();

            // タイムアウト付きで完了を待つ
            // 各UAVは18.5秒かかる。直列処理なので、5台 × 18.5秒 = 92.5秒 + バッファ
            int maxWaitSeconds = (int) (EXPECTED_FLIGHT_TIME * UAV_COUNT) + 30;
            int waitedSeconds = 0;

            while (completionListener.getCompletedCount() < UAV_COUNT && waitedSeconds < maxWaitSeconds) {
                Thread.sleep(1000);
                waitedSeconds++;
                System.out.println("  待機中... 完了数: " + completionListener.getCompletedCount() + "/" + UAV_COUNT +
                    ", リンク通過数: " + linkPassedListener.getLinkPassedCount() +
                    " (経過: " + waitedSeconds + "秒)");
            }

            System.out.println();

            // 結果判定
            int completedCount = completionListener.getCompletedCount();
            int linkPassedCount = linkPassedListener.getLinkPassedCount();
            int expectedLinkPassed = UAV_COUNT * (PATH.length - 1);  // 5台 × 3リンク = 15
            boolean success = completedCount == UAV_COUNT && linkPassedCount == expectedLinkPassed;

            System.out.println("=========================");
            System.out.println("テスト結果:");
            System.out.println("  完了UAV: " + completedCount + "/" + UAV_COUNT);
            System.out.println("  リンク通過イベント: " + linkPassedCount + "/" + expectedLinkPassed);
            if (success) {
                System.out.println("✓ テスト成功！");
            } else {
                System.out.println("✗ テスト失敗");
                if (completedCount < UAV_COUNT) {
                    System.out.println("  - 完了数不足");
                }
                if (linkPassedCount != expectedLinkPassed) {
                    System.out.println("  - リンク通過イベント数不一致");
                }
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

            if (completionListener != null) {
                completionListener.stopListening();
            }
            if (linkPassedListener != null) {
                linkPassedListener.stopListening();
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
        UAVJob job = new UAVJob(
            uavId,              // uavId
            1,                  // clientId
            PATH,               // path: [0, 1, 4, 5]
            UAV_SPEED,          // speed (10 m/s)
            System.currentTimeMillis(), // startTime
            PATH[0],            // sourceBeaconId
            PATH[PATH.length - 1]  // destinationBeaconId
        );
        job.setLinkDistances(LINK_DISTANCES);

        return job;
    }
}
