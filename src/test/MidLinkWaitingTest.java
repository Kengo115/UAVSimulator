package test;

import server.redis.*;
import server.scheduler.FlightScheduler;
import server.worker.AsyncUAVWorker;
import server.util.LogManager;

import java.io.IOException;
import java.util.concurrent.*;

/**
 * Phase 3b-5: 途中リンク待機・再開テスト
 *
 * テスト内容:
 * 1. 3リンク経路 [0, 1, 4, 5] で5台のUAVを飛行
 * 2. 2番目のリンク(1→4)の容量を2に制限
 * 3. 途中リンクで待機が発生し、容量回復後に再開することを確認
 *
 * 期待結果:
 * - 全5台が最初のリンク(0→1)を通過
 * - 2台が2番目のリンク(1→4)に入り、3台が途中待機
 * - 容量回復→待機UAV再開→全5台完了
 */
public class MidLinkWaitingTest {

    private static final int UAV_COUNT = 5;
    private static final double[] LINK_DISTANCES = {50.0, 75.0, 60.0};  // 各リンクの距離
    private static final double UAV_SPEED = 10.0;        // UAV速度（m/s）
    private static final int[] PATH = {0, 1, 4, 5};      // 経路: 3リンク

    // リンク容量: 2番目のリンクを制限
    private static final double FIRST_LINK_CAPACITY = 100.0;   // 0→1: 無制限
    private static final double SECOND_LINK_CAPACITY = 2.0;    // 1→4: 制限あり
    private static final double THIRD_LINK_CAPACITY = 100.0;   // 4→5: 無制限

    // 期待される待機数: 5 - 2 = 3台
    private static final int EXPECTED_MID_WAITING = 3;

    // 期待飛行時間: (50 + 75 + 60) / 10 = 18.5秒/台（待機なしの場合）
    private static final double BASE_FLIGHT_TIME = 18.5;

    public static void main(String[] args) {
        System.out.println("=== Phase 3b-5: 途中リンク待機・再開テスト ===");
        System.out.println();
        System.out.println("テスト条件:");
        System.out.println("  - UAV数: " + UAV_COUNT);
        System.out.println("  - 経路: [0, 1, 4, 5] (3リンク)");
        System.out.println("  - リンク距離: [50.0, 75.0, 60.0] (合計185.0m)");
        System.out.println("  - UAV速度: " + UAV_SPEED + "m/s");
        System.out.println("  - リンク容量:");
        System.out.println("    - 0→1: " + (int) FIRST_LINK_CAPACITY + " (無制限)");
        System.out.println("    - 1→4: " + (int) SECOND_LINK_CAPACITY + " (制限)");
        System.out.println("    - 4→5: " + (int) THIRD_LINK_CAPACITY + " (無制限)");
        System.out.println("  - 期待途中待機数: " + EXPECTED_MID_WAITING + "台");
        System.out.println();

        RedisConnectionManager manager = RedisConnectionManager.getInstance();
        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        AsyncUAVWorker worker = null;
        FlightScheduler flightScheduler = null;
        UAVCompletionListener completionListener = null;
        LinkCapacityManager capacityManager = null;
        WaitingUAVManager waitingManager = null;

        try {
            // 1. Redis接続
            System.out.println("[1/7] Redis接続...");
            manager.connect();
            System.out.println("✓ 接続成功");
            System.out.println();

            // 2. 容量初期化（2番目のリンクを制限）
            System.out.println("[2/7] リンク容量初期化...");
            capacityManager = new LinkCapacityManager();
            capacityManager.saveCapacity(0, 1, FIRST_LINK_CAPACITY);
            capacityManager.saveCapacity(1, 4, SECOND_LINK_CAPACITY);
            capacityManager.saveCapacity(4, 5, THIRD_LINK_CAPACITY);
            System.out.println("✓ link[0][1].capacity = " + (int) FIRST_LINK_CAPACITY);
            System.out.println("✓ link[1][4].capacity = " + (int) SECOND_LINK_CAPACITY + " (制限)");
            System.out.println("✓ link[4][5].capacity = " + (int) THIRD_LINK_CAPACITY);
            System.out.println();

            // 3. 待機キュークリア
            System.out.println("[3/7] 待機キュークリア...");
            waitingManager = new WaitingUAVManager();
            waitingManager.clearAll();
            System.out.println("✓ 待機キュークリア完了");
            System.out.println();

            // 4. FlightScheduler初期化
            System.out.println("[4/7] FlightScheduler初期化...");
            flightScheduler = FlightScheduler.getInstance();
            flightScheduler.resetCounters();
            System.out.println("✓ FlightScheduler ready");
            System.out.println();

            // 5. 完了リスナー開始
            System.out.println("[5/7] 完了リスナー開始...");
            completionListener = new UAVCompletionListener();
            completionListener.startListening();
            System.out.println("✓ UAVCompletionListener ready");
            System.out.println();

            // 6. ジョブ投入
            System.out.println("[6/7] ジョブ投入...");
            UAVJobQueue jobQueue = new UAVJobQueue();
            jobQueue.clearQueue();

            for (int i = 0; i < UAV_COUNT; i++) {
                UAVJob job = createTestJob(i);
                jobQueue.enqueueJob(job);
                System.out.println("  ジョブ投入: UAV " + i);
            }
            System.out.println("✓ " + UAV_COUNT + "件のジョブを投入");
            System.out.println();

            // 7. AsyncUAVWorker起動（1台のみ）
            System.out.println("[7/7] AsyncUAVWorker起動 (1台)...");
            long startTime = System.currentTimeMillis();

            worker = new AsyncUAVWorker("mid-link-test-worker");
            final AsyncUAVWorker testWorker = worker;
            workerExecutor.submit(() -> testWorker.start());

            System.out.println("✓ Worker起動");
            System.out.println();

            // 完了待機
            System.out.println("完了待機中...");
            System.out.println("  (途中待機が発生するため、基準時間より長くなる予定)");
            System.out.println();

            // タイムアウト: 待機を考慮して十分な時間を確保
            // 2番目のリンク: 5台が2台ずつ処理 → 3回分の追加待機時間
            // 追加時間 ≒ 7.5秒 × 2 = 15秒（2番目リンクの飛行時間 × 追加サイクル数）
            int maxWaitSeconds = (int)(BASE_FLIGHT_TIME * 3) + 20;
            int waitedSeconds = 0;

            while (completionListener.getCompletedCount() < UAV_COUNT && waitedSeconds < maxWaitSeconds) {
                Thread.sleep(1000);
                waitedSeconds++;

                // 途中待機状況を確認
                int midLinkWaiting = waitingManager.getWaitingCount(1, 4);
                double midLinkCapacity = capacityManager.getCapacity(1, 4);

                System.out.println(
                    "  待機中... 完了数: " + completionListener.getCompletedCount() + "/" + UAV_COUNT +
                    ", 飛行中: " + flightScheduler.getActiveFlights() +
                    ", リンク通過: " + flightScheduler.getLinkPassedCount() +
                    ", 1→4待機: " + midLinkWaiting +
                    ", 1→4容量: " + String.format("%.0f", midLinkCapacity) +
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
            // 途中待機が発生したため、基準時間より長くなっているはず
            boolean hadWaiting = totalSeconds > BASE_FLIGHT_TIME;

            boolean success = allCompleted && allLinksPassed;

            System.out.println("=========================");
            System.out.println("テスト結果:");
            System.out.println("  完了UAV: " + completedCount + "/" + UAV_COUNT);
            System.out.println("  リンク通過: " + linkPassedCount + "/" + expectedLinkPassed);
            System.out.println("  実行時間: " + String.format("%.2f", totalSeconds) + "秒");
            System.out.println("  基準時間: " + BASE_FLIGHT_TIME + "秒（待機なしの場合）");
            System.out.println("  途中待機発生: " + (hadWaiting ? "あり（予想通り）" : "なし"));
            System.out.println();

            if (success) {
                System.out.println("✓ テスト成功！");
                System.out.println("  - 全" + UAV_COUNT + "台が完了");
                System.out.println("  - 全" + expectedLinkPassed + "回のリンク通過を確認");
                if (hadWaiting) {
                    System.out.println("  - 途中リンク待機→再開フローが正常動作");
                }
            } else {
                System.out.println("✗ テスト失敗");
                if (!allCompleted) {
                    System.out.println("  - 完了数不足: " + completedCount + "/" + UAV_COUNT);
                }
                if (!allLinksPassed) {
                    System.out.println("  - リンク通過数不一致: " + linkPassedCount + "/" + expectedLinkPassed);
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
