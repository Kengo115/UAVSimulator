package test;

import server.redis.*;
import server.worker.UAVWorker;
import server.util.LogManager;

import java.io.IOException;
import java.util.concurrent.*;

/**
 * Phase 3b-2d: 最初リンク待機・再開テスト
 *
 * テスト内容:
 * 1. 最初のリンク(0→1)の容量を2に制限
 * 2. 5台のUAVを投入し、3台が待機状態になることを確認
 * 3. 飛行中のUAVがリンクを通過すると容量回復
 * 4. 待機UAVが再ジョブ化されて飛行開始
 * 5. 最終的に全5台が完了
 */
public class FirstLinkWaitingTest {

    private static final int UAV_COUNT = 5;              // テストするUAV数
    private static final double[] LINK_DISTANCES = {50.0, 75.0, 60.0};  // 各リンクの距離
    private static final double UAV_SPEED = 10.0;        // UAV速度（m/s）
    private static final int[] PATH = {0, 1, 4, 5};      // 経路: 3リンク
    private static final double FIRST_LINK_CAPACITY = 2.0;  // 最初のリンクの容量

    // 期待される飛行時間: (50 + 75 + 60) / 10 = 18.5秒
    private static final double EXPECTED_FLIGHT_TIME = 18.5;
    private static final double TOTAL_DISTANCE = 185.0;

    public static void main(String[] args) {
        System.out.println("=== Phase 3b-2d: 最初リンク待機・再開テスト ===");
        System.out.println();
        System.out.println("テスト条件:");
        System.out.println("  - UAV数: " + UAV_COUNT);
        System.out.println("  - 経路: [0, 1, 4, 5] (3リンク)");
        System.out.println("  - リンク距離: [50.0, 75.0, 60.0] (合計" + TOTAL_DISTANCE + "m)");
        System.out.println("  - UAV速度: " + UAV_SPEED + "m/s");
        System.out.println("  - 最初のリンク(0→1)容量: " + (int) FIRST_LINK_CAPACITY);
        System.out.println("  - 期待: 2台が即飛行、3台が待機→順次飛行");
        System.out.println();

        RedisConnectionManager manager = RedisConnectionManager.getInstance();
        // 複数Workerを並列実行するためのスレッドプール
        ExecutorService workerExecutor = Executors.newFixedThreadPool(UAV_COUNT);
        UAVCompletionListener completionListener = null;
        UAVLinkPassedListener linkPassedListener = null;
        UAVWorker[] workers = new UAVWorker[UAV_COUNT];
        LinkCapacityManager capacityManager = null;
        WaitingUAVManager waitingManager = null;

        try {
            // 1. Redis接続
            System.out.println("[1/6] Redis接続...");
            manager.connect();
            System.out.println("✓ 接続成功");
            System.out.println();

            // 2. 容量の初期化（最初のリンクのみ容量制限）
            System.out.println("[2/6] リンク容量初期化...");
            capacityManager = new LinkCapacityManager();
            waitingManager = new WaitingUAVManager();

            // 最初のリンク(0→1)の容量を設定
            capacityManager.saveCapacity(0, 1, FIRST_LINK_CAPACITY);
            // 他のリンクは十分な容量を設定
            capacityManager.saveCapacity(1, 4, 100.0);
            capacityManager.saveCapacity(4, 5, 100.0);

            // 待機キューをクリア
            waitingManager.clearAll();

            System.out.println("✓ link[0][1].capacity = " + (int) FIRST_LINK_CAPACITY);
            System.out.println("✓ link[1][4].capacity = 100");
            System.out.println("✓ link[4][5].capacity = 100");
            System.out.println();

            // 3. リスナーを開始
            System.out.println("[3/6] リスナー開始...");
            completionListener = new UAVCompletionListener();
            completionListener.startListening();
            linkPassedListener = new UAVLinkPassedListener();
            linkPassedListener.startListening();
            System.out.println("✓ 完了リスナー + リンク通過リスナー開始");
            System.out.println();

            // 4. ジョブキューをクリアしてジョブを投入
            System.out.println("[4/6] ジョブ投入...");
            UAVJobQueue jobQueue = new UAVJobQueue();
            jobQueue.clearQueue();

            for (int i = 0; i < UAV_COUNT; i++) {
                UAVJob job = createTestJob(i);
                jobQueue.enqueueJob(job);
                System.out.println("  ジョブ投入: UAV " + i);
            }
            System.out.println("✓ " + UAV_COUNT + "件のジョブを投入");
            System.out.println();

            // 5. 複数Workerを別スレッドで起動
            System.out.println("[5/6] Worker起動 (" + UAV_COUNT + "台並列)...");
            for (int i = 0; i < UAV_COUNT; i++) {
                final UAVWorker testWorker = new UAVWorker("worker-" + i);
                workers[i] = testWorker;
                workerExecutor.submit(() -> {
                    testWorker.start();
                });
            }
            System.out.println("✓ " + UAV_COUNT + " Workerを起動");
            System.out.println();

            // 6. 完了を待機
            System.out.println("[6/6] 完了待機...");
            System.out.println("  期待: " + UAV_COUNT + "台のUAVが完了");
            System.out.println("  期待待機数: " + (UAV_COUNT - (int) FIRST_LINK_CAPACITY) + "台");
            System.out.println();

            // タイムアウト付きで完了を待つ
            // 容量2で5台を処理: 2台ずつ → ceil(5/2) = 3バッチ × 18.5秒 + バッファ
            int maxWaitSeconds = (int) (EXPECTED_FLIGHT_TIME * 3) + 30;
            int waitedSeconds = 0;

            while (completionListener.getCompletedCount() < UAV_COUNT && waitedSeconds < maxWaitSeconds) {
                Thread.sleep(1000);
                waitedSeconds++;

                // 現在の容量を確認
                double currentCapacity = capacityManager.getCapacity(0, 1);
                int waitingCount = waitingManager.getWaitingCount(0, 1);

                System.out.println("  待機中... 完了数: " + completionListener.getCompletedCount() + "/" + UAV_COUNT +
                    ", リンク通過数: " + linkPassedListener.getLinkPassedCount() +
                    ", 再ジョブ化数: " + linkPassedListener.getReEnqueuedCount() +
                    ", 待機中: " + waitingCount +
                    ", 容量[0→1]: " + String.format("%.0f", currentCapacity) +
                    " (経過: " + waitedSeconds + "秒)");
            }

            System.out.println();

            // 結果判定
            int completedCount = completionListener.getCompletedCount();
            int linkPassedCount = linkPassedListener.getLinkPassedCount();
            int reEnqueuedCount = linkPassedListener.getReEnqueuedCount();
            int expectedLinkPassed = UAV_COUNT * (PATH.length - 1);  // 5台 × 3リンク = 15
            int expectedReEnqueued = UAV_COUNT - (int) FIRST_LINK_CAPACITY;  // 5 - 2 = 3

            boolean success = completedCount == UAV_COUNT &&
                              linkPassedCount == expectedLinkPassed &&
                              reEnqueuedCount == expectedReEnqueued;

            System.out.println("=========================");
            System.out.println("テスト結果:");
            System.out.println("  完了UAV: " + completedCount + "/" + UAV_COUNT);
            System.out.println("  リンク通過イベント: " + linkPassedCount + "/" + expectedLinkPassed);
            System.out.println("  再ジョブ化数: " + reEnqueuedCount + "/" + expectedReEnqueued);
            if (success) {
                System.out.println("✓ テスト成功！");
                System.out.println("  - 待機→再開フローが正常動作");
            } else {
                System.out.println("✗ テスト失敗");
                if (completedCount < UAV_COUNT) {
                    System.out.println("  - 完了数不足");
                }
                if (linkPassedCount != expectedLinkPassed) {
                    System.out.println("  - リンク通過イベント数不一致");
                }
                if (reEnqueuedCount != expectedReEnqueued) {
                    System.out.println("  - 再ジョブ化数不一致（期待: " + expectedReEnqueued + "）");
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

            // 全Workerを停止
            for (UAVWorker w : workers) {
                if (w != null) {
                    w.stop();
                }
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

            // 待機キューをクリア
            if (waitingManager != null) {
                waitingManager.clearAll();
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
