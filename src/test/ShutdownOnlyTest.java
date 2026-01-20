package test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * shutdown()のみでスレッドが解放されるかテスト
 */
public class ShutdownOnlyTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== shutdown()のみでスレッド解放されるかテスト ===\n");

        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        int initialThreadCount = threadMXBean.getThreadCount();
        System.out.println("初期スレッド数: " + initialThreadCount);

        // 100個のScheduledExecutorServiceを作成してshutdown()のみ呼ぶ
        System.out.println("\n--- 100個のScheduledExecutorServiceを作成（shutdown()のみ）---");
        for (int i = 0; i < 100; i++) {
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

            // 短いタスクをスケジュール
            scheduler.schedule(() -> {
                // 何もしない
            }, 100, TimeUnit.MILLISECONDS);

            // shutdown()のみ（awaitTermination()なし）
            scheduler.shutdown();
        }

        // スレッド数を確認
        System.out.println("作成直後のスレッド数: " + threadMXBean.getThreadCount());

        // 1秒待機
        Thread.sleep(1000);
        System.out.println("1秒後のスレッド数: " + threadMXBean.getThreadCount());

        // 5秒待機
        Thread.sleep(4000);
        System.out.println("5秒後のスレッド数: " + threadMXBean.getThreadCount());

        // 10秒待機
        Thread.sleep(5000);
        System.out.println("10秒後のスレッド数: " + threadMXBean.getThreadCount());

        int finalCount = threadMXBean.getThreadCount();
        int leaked = finalCount - initialThreadCount;

        System.out.println("\n=== 結果 ===");
        System.out.println("リークしたスレッド数: " + leaked);

        if (leaked > 10) {
            System.out.println("⚠ 警告: スレッドリークが発生しています！");
            System.out.println("→ allowCoreThreadTimeOut(true)の設定が必要です");
        } else {
            System.out.println("✓ スレッドは適切に解放されています");
        }
    }
}
