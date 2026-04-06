package test;
import shared.redis.RedisConnectionManager;

import network_manager.redis.*;
import network_manager.scheduler.FlightScheduler;
import network_manager.worker.AsyncUAVWorker;
import shared.util.LogManager;

import java.io.IOException;
import java.util.concurrent.*;

/**
 * Phase 3b-3: 非同期イベントスケジューリングテスト
 *
 * テスト内容:
 * 1. 1つのAsyncUAVWorkerで5台以上のUAVを同時飛行
 * 2. イベントスケジューリング方式で正確なタイミングでリンク通過
 * 3. 全UAVが完了することを確認
 *
 * 期待結果:
 * - 同期方式（旧）: 1 Worker = 1 UAV同時飛行 → 5台順次 = 約92.5秒
 * - 非同期方式（新）: 1 Worker = 5 UAV同時飛行 → 約18.5秒
 */
public class AsyncFlightTest {

    private static final int UAV_COUNT = 5;
    private static final double[] LINK_DISTANCES = {50.0, 75.0, 60.0};  // 各リンクの距離
    private static final double UAV_SPEED = 10.0;        // UAV速度（m/s）
    private static final int[] PATH = {0, 1, 4, 5};      // 経路: 3リンク
    private static final double LINK_CAPACITY = 100.0;   // 容量制限なし（並列飛行テスト）

    // 期待される飛行時間: (50 + 75 + 60) / 10 = 18.5秒
    private static final double EXPECTED_FLIGHT_TIME = 18.5;
    private static final double TOTAL_DISTANCE = 185.0;

    public static void main(String[] args) {
        System.out.println("=== Phase 3b-3: 非同期イベントスケジューリングテスト ===");
        System.out.println();
        System.out.println("テスト条件:");
        System.out.println("  - UAV数: " + UAV_COUNT);
        System.out.println("  - 経路: [0, 1, 4, 5] (3リンク)");
        System.out.println("  - リンク距離: [50.0, 75.0, 60.0] (合計" + TOTAL_DISTANCE + "m)");
        System.out.println("  - UAV速度: " + UAV_SPEED + "m/s");
        System.out.println("  - 期待飛行時間: " + EXPECTED_FLIGHT_TIME + "秒/台");
        System.out.println("  - Worker数: 1（非同期）");
        System.out.println();
        System.out.println("期待結果:");
        System.out.println("  - 同期方式: 5台順次 = " + (EXPECTED_FLIGHT_TIME * UAV_COUNT) + "秒");
        System.out.println("  - 非同期方式: 5台同時 = " + EXPECTED_FLIGHT_TIME + "秒 + α");
        System.out.println();

        RedisConnectionManager manager = RedisConnectionManager.getInstance();
        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        AsyncUAVWorker worker = null;
        FlightScheduler flightScheduler = null;
        UAVCompletionListener completionListener = null;
        LinkCapacityManager capacityManager = null;

        try {
            // 1. Redis接続
            System.out.println("[1/6] Redis接続...");
            manager.connect();
            System.out.println("✓ 接続成功");
            System.out.println();

            // 2. 容量初期化（制限なし）
            System.out.println("[2/6] リンク容量初期化...");
            capacityManager = new LinkCapacityManager();
            capacityManager.saveCapacity(0, 1, LINK_CAPACITY);
            capacityManager.saveCapacity(1, 4, LINK_CAPACITY);
            capacityManager.saveCapacity(4, 5, LINK_CAPACITY);
            System.out.println("✓ 全リンク容量 = " + (int) LINK_CAPACITY);
            System.out.println();

            // 3. FlightScheduler初期化
            System.out.println("[3/6] FlightScheduler初期化...");
            flightScheduler = FlightScheduler.getInstance();
            flightScheduler.resetCounters();
            System.out.println("✓ FlightScheduler ready");
            System.out.println();

            // 4. 完了リスナー開始
            System.out.println("[4/6] 完了リスナー開始...");
            completionListener = new UAVCompletionListener();
            completionListener.startListening();
            System.out.println("✓ UAVCompletionListener ready");
            System.out.println();

            // 5. ジョブ投入
            System.out.println("[5/6] ジョブ投入...");
            UAVJobQueue jobQueue = new UAVJobQueue();
            jobQueue.clearQueue();

            for (int i = 0; i < UAV_COUNT; i++) {
                UAVJob job = createTestJob(i);
                jobQueue.enqueueJob(job);
                System.out.println("  ジョブ投入: UAV " + i);
            }
            System.out.println("✓ " + UAV_COUNT + "件のジョブを投入");
            System.out.println();

            // 6. AsyncUAVWorker起動（1台のみ）
            System.out.println("[6/6] AsyncUAVWorker起動 (1台)...");
            long startTime = System.currentTimeMillis();

            worker = new AsyncUAVWorker("async-test-worker");
            final AsyncUAVWorker testWorker = worker;
            workerExecutor.submit(() -> testWorker.start());

            System.out.println("✓ Worker起動");
            System.out.println();

            // 完了待機
            System.out.println("完了待機中...");
            System.out.println("  (非同期方式: 全UAV同時飛行のため約" + EXPECTED_FLIGHT_TIME + "秒で完了予定)");
            System.out.println();

            // タイムアウト: 期待時間の2倍 + バッファ
            int maxWaitSeconds = (int)(EXPECTED_FLIGHT_TIME * 2) + 10;
            int waitedSeconds = 0;

            while (completionListener.getCompletedCount() < UAV_COUNT && waitedSeconds < maxWaitSeconds) {
                Thread.sleep(1000);
                waitedSeconds++;

                System.out.println(
                    "  待機中... 完了数: " + completionListener.getCompletedCount() + "/" + UAV_COUNT +
                    ", 飛行中: " + flightScheduler.getActiveFlights() +
                    ", リンク通過: " + flightScheduler.getLinkPassedCount() +
                    " (経過: " + waitedSeconds + "秒)"
                );
            }

            long endTime = System.currentTimeMillis();
            double totalSeconds = (endTime - startTime) / 1000.0;

            System.out.println();

            // 結果判定
            int completedCount = completionListener.getCompletedCount();
            int linkPassedCount = flightScheduler.getLinkPassedCount();
            int expectedLinkPassed = UAV_COUNT * (PATH.length - 1);  // 5台 × 3リンク = 15

            boolean allCompleted = completedCount == UAV_COUNT;
            boolean allLinksPassed = linkPassedCount == expectedLinkPassed;
            // 非同期なので、実行時間は期待時間の1.5倍以内ならOK
            boolean fastEnough = totalSeconds < EXPECTED_FLIGHT_TIME * 1.5;

            boolean success = allCompleted && allLinksPassed && fastEnough;

            System.out.println("=========================");
            System.out.println("テスト結果:");
            System.out.println("  完了UAV: " + completedCount + "/" + UAV_COUNT);
            System.out.println("  リンク通過: " + linkPassedCount + "/" + expectedLinkPassed);
            System.out.println("  実行時間: " + String.format("%.2f", totalSeconds) + "秒");
            System.out.println("  期待時間: " + EXPECTED_FLIGHT_TIME + "秒（非同期同時飛行）");
            System.out.println();

            if (success) {
                System.out.println("✓ テスト成功！");
                System.out.println("  - 1 Workerで" + UAV_COUNT + "台同時飛行を確認");
                System.out.println("  - イベントスケジューリング方式が正常動作");
                if (totalSeconds < EXPECTED_FLIGHT_TIME * 1.2) {
                    System.out.println("  - 実行時間が期待値に近い（同時飛行成功）");
                }
            } else {
                System.out.println("✗ テスト失敗");
                if (!allCompleted) {
                    System.out.println("  - 完了数不足");
                }
                if (!allLinksPassed) {
                    System.out.println("  - リンク通過数不一致");
                }
                if (!fastEnough) {
                    System.out.println("  - 実行時間が長すぎる（順次処理の可能性）");
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

            // FlightSchedulerのシャットダウン
            FlightScheduler.resetInstance();

            manager.disconnect();
            System.out.println("✓ クリーンアップ完了");
        }
    }

    /**
     * テスト用のUAVJobを作成
     */
    private static UAVJob createTestJob(int uavId) {
        UAVJob job = new UAVJob(
            uavId,
            1,                  // clientId
            PATH,               // path
            UAV_SPEED,          // speed
            System.currentTimeMillis(),
            PATH[0],            // sourceBeaconId
            PATH[PATH.length - 1]  // destinationBeaconId
        );
        job.setLinkDistances(LINK_DISTANCES);
        return job;
    }
}
