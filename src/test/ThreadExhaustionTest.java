package test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * スレッド枯渇問題の再現テスト
 *
 * runUAVFlowRedis()で発生する問題を再現：
 * - 各クライアントでScheduledExecutorServiceを作成
 * - shutdown()を呼ばないためスレッドが累積
 * - 約2000クライアントでmacOSのスレッド上限(2048)に到達
 */
public class ThreadExhaustionTest {

    // 問題を再現するための設定
    private static final int TARGET_CLIENT_COUNT = 2100;  // 上限(2048)を超える数
    private static final int THREAD_MONITOR_INTERVAL = 100;  // スレッド数表示間隔

    // スレッドプール累積リスト（shutdown()しないことをシミュレート）
    private static List<ScheduledExecutorService> leakedExecutors = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("=== スレッド枯渇問題 再現テスト ===");
        System.out.println("macOSスレッド上限: 2048 (kern.num_taskthreads)");
        System.out.println("目標クライアント数: " + TARGET_CLIENT_COUNT);
        System.out.println();

        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        int initialThreadCount = threadMXBean.getThreadCount();
        System.out.println("初期スレッド数: " + initialThreadCount);
        System.out.println();

        // 問題のあるコード（現状のrunUAVFlowRedis相当）をシミュレート
        System.out.println("--- 問題のあるコード（shutdown()なし）でテスト ---");
        testWithoutShutdown(threadMXBean, initialThreadCount);
    }

    /**
     * 問題のあるコード：shutdown()を呼ばない
     * → スレッドが累積して上限に達する
     */
    private static void testWithoutShutdown(ThreadMXBean threadMXBean, int initialThreadCount) {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger jobCount = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        try {
            for (int clientId = 1; clientId <= TARGET_CLIENT_COUNT; clientId++) {
                // runUAVFlowRedis()と同じ処理をシミュレート
                ScheduledExecutorService enqueueScheduler = Executors.newScheduledThreadPool(1);

                // ジョブをスケジュール（実際のコードと同様）
                final int cid = clientId;
                for (int uavId = 0; uavId < 10; uavId++) {
                    final int uid = uavId;
                    enqueueScheduler.schedule(() -> {
                        // ジョブ投入をシミュレート
                        jobCount.incrementAndGet();
                    }, uavId * 10, TimeUnit.MILLISECONDS);  // 短い遅延
                }

                // ★問題点: shutdown()を呼ばない！
                // enqueueScheduler.shutdown();
                // enqueueScheduler.awaitTermination(...);

                // リークしたExecutorを保持（GCで回収されないように）
                leakedExecutors.add(enqueueScheduler);

                successCount.set(clientId);

                // 進捗表示
                if (clientId % THREAD_MONITOR_INTERVAL == 0) {
                    int currentThreads = threadMXBean.getThreadCount();
                    System.out.printf("Client %4d: スレッド数=%d (累積: +%d)%n",
                        clientId, currentThreads, currentThreads - initialThreadCount);
                }
            }

            System.out.println();
            System.out.println("★ 予期せず全クライアント処理完了（問題が再現されませんでした）");

        } catch (OutOfMemoryError e) {
            long elapsed = System.currentTimeMillis() - startTime;
            int finalThreads = threadMXBean.getThreadCount();
            System.out.println();
            System.out.println("=== ★ 問題再現成功 ===");
            System.out.println("エラー: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.out.println("停止時のクライアントID: " + successCount.get());
            System.out.println("最終スレッド数: " + finalThreads);
            System.out.println("累積スレッド数: " + (finalThreads - initialThreadCount));
            System.out.println("投入されたジョブ数: " + jobCount.get());
            System.out.println("経過時間: " + elapsed + "ms");
            System.out.println();
            System.out.println("結論: スレッド上限(2048)に達してOutOfMemoryErrorが発生");
            System.out.println("原因: ScheduledExecutorServiceがshutdown()されず累積");

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            int finalThreads = threadMXBean.getThreadCount();
            System.out.println();
            System.out.println("=== ★ 問題再現成功（別のエラー） ===");
            System.out.println("エラー: " + e.getClass().getName() + ": " + e.getMessage());
            System.out.println("停止時のクライアントID: " + successCount.get());
            System.out.println("最終スレッド数: " + finalThreads);
            System.out.println("経過時間: " + elapsed + "ms");
            e.printStackTrace();

        } finally {
            // クリーンアップ
            System.out.println();
            System.out.println("クリーンアップ中...");
            for (ScheduledExecutorService exec : leakedExecutors) {
                try {
                    exec.shutdownNow();
                } catch (Exception ignored) {}
            }
            leakedExecutors.clear();
        }
    }
}
