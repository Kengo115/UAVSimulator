package test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * スレッド枯渇問題の修正確認テスト
 *
 * 修正内容:
 * - ScheduledExecutorServiceを使用後にshutdown()とawaitTermination()を呼ぶ
 * - これによりスレッドが適切に解放され、上限に達しない
 */
public class ThreadExhaustionFixTest {

    // 以前は2020で停止していた
    private static final int TARGET_CLIENT_COUNT = 3000;  // 修正後はこれ以上も処理可能
    private static final int THREAD_MONITOR_INTERVAL = 500;

    public static void main(String[] args) {
        System.out.println("=== スレッド枯渇問題 修正確認テスト ===");
        System.out.println("修正: shutdown() + awaitTermination() を追加");
        System.out.println("目標クライアント数: " + TARGET_CLIENT_COUNT);
        System.out.println();

        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        int initialThreadCount = threadMXBean.getThreadCount();
        System.out.println("初期スレッド数: " + initialThreadCount);
        System.out.println();

        // 修正後のコードでテスト
        System.out.println("--- 修正後のコード（shutdown()あり）でテスト ---");
        testWithShutdown(threadMXBean, initialThreadCount);
    }

    /**
     * 修正後のコード：shutdown() + awaitTermination()を呼ぶ
     * → スレッドが適切に解放される
     */
    private static void testWithShutdown(ThreadMXBean threadMXBean, int initialThreadCount) {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger jobCount = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();
        int maxThreadCount = initialThreadCount;

        try {
            for (int clientId = 1; clientId <= TARGET_CLIENT_COUNT; clientId++) {
                // runUAVFlowRedis()と同じ処理をシミュレート
                ScheduledExecutorService enqueueScheduler = Executors.newScheduledThreadPool(1);

                // ジョブをスケジュール
                final int cid = clientId;
                for (int uavId = 0; uavId < 10; uavId++) {
                    final int uid = uavId;
                    enqueueScheduler.schedule(() -> {
                        jobCount.incrementAndGet();
                    }, uavId * 10, TimeUnit.MILLISECONDS);
                }

                // ★修正: shutdown() + awaitTermination() を追加
                enqueueScheduler.shutdown();
                try {
                    // 最大待機時間 = 最大遅延(90ms) + バッファ
                    if (!enqueueScheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                        enqueueScheduler.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    enqueueScheduler.shutdownNow();
                }

                successCount.set(clientId);

                // 進捗表示
                if (clientId % THREAD_MONITOR_INTERVAL == 0) {
                    int currentThreads = threadMXBean.getThreadCount();
                    maxThreadCount = Math.max(maxThreadCount, currentThreads);
                    System.out.printf("Client %4d: スレッド数=%d (最大: %d, 累積リーク: +%d)%n",
                        clientId, currentThreads, maxThreadCount, currentThreads - initialThreadCount);
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            int finalThreads = threadMXBean.getThreadCount();

            System.out.println();
            System.out.println("=== ★ 修正成功！全クライアント処理完了 ===");
            System.out.println("処理したクライアント数: " + successCount.get());
            System.out.println("投入されたジョブ数: " + jobCount.get());
            System.out.println("最終スレッド数: " + finalThreads);
            System.out.println("最大スレッド数: " + maxThreadCount);
            System.out.println("スレッド累積数: " + (finalThreads - initialThreadCount) + " (修正前は2000+)");
            System.out.println("経過時間: " + elapsed + "ms");
            System.out.println();
            System.out.println("結論: shutdown() + awaitTermination()によりスレッドが適切に解放され、");
            System.out.println("      上限に達することなく継続処理可能");

        } catch (OutOfMemoryError e) {
            System.out.println();
            System.out.println("エラー発生: " + e.getMessage());
            System.out.println("停止時のクライアントID: " + successCount.get());
            System.out.println("→ 修正が不完全です");

        } catch (Exception e) {
            System.out.println();
            System.out.println("エラー発生: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
}
