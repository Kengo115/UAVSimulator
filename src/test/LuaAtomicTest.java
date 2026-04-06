package test;

import network_manager.redis.LinkCapacityManager;
import shared.redis.RedisConnectionManager;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 3b-4: Luaスクリプト原子操作の競合テスト
 *
 * テスト内容:
 * 1. 限られた容量（例: 2）に対して多数のスレッド（例: 20）が同時にアクセス
 * 2. 容量消費の成功/失敗が正確にカウントされることを確認
 * 3. 容量が負にならないことを確認
 * 4. 最終的な容量が正しいことを確認
 */
public class LuaAtomicTest {

    private static final int LINK_CAPACITY = 5;       // 初期容量
    private static final int THREAD_COUNT = 50;       // 同時アクセススレッド数
    private static final int SRC_NODE = 0;
    private static final int DST_NODE = 1;

    public static void main(String[] args) {
        System.out.println("=== Phase 3b-4: Luaスクリプト競合テスト ===");
        System.out.println();
        System.out.println("テスト条件:");
        System.out.println("  - 初期容量: " + LINK_CAPACITY);
        System.out.println("  - 同時アクセススレッド数: " + THREAD_COUNT);
        System.out.println("  - 期待成功数: " + LINK_CAPACITY);
        System.out.println("  - 期待失敗数: " + (THREAD_COUNT - LINK_CAPACITY));
        System.out.println();

        RedisConnectionManager manager = RedisConnectionManager.getInstance();
        LinkCapacityManager capacityManager = null;

        try {
            // 1. Redis接続
            System.out.println("[1/4] Redis接続...");
            manager.connect();
            System.out.println("✓ 接続成功");
            System.out.println();

            // 2. 容量初期化
            System.out.println("[2/4] 容量初期化...");
            capacityManager = new LinkCapacityManager();
            capacityManager.saveCapacity(SRC_NODE, DST_NODE, LINK_CAPACITY);
            double initialCapacity = capacityManager.getCapacity(SRC_NODE, DST_NODE);
            System.out.println("✓ 初期容量設定: " + initialCapacity);
            System.out.println();

            // 3. 競合テスト実行
            System.out.println("[3/4] 競合テスト実行...");
            System.out.println("  " + THREAD_COUNT + "スレッドが同時に容量消費を試行");
            System.out.println();

            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch startLatch = new CountDownLatch(1);  // 全スレッド同時開始用
            CountDownLatch endLatch = new CountDownLatch(THREAD_COUNT);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            final LinkCapacityManager finalCapacityManager = capacityManager;

            // 全スレッドを準備
            for (int i = 0; i < THREAD_COUNT; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        // 全スレッドが準備完了するまで待機
                        startLatch.await();

                        // 同時に容量消費を試行
                        boolean success = finalCapacityManager.tryConsumeCapacity(SRC_NODE, DST_NODE);

                        if (success) {
                            int count = successCount.incrementAndGet();
                            System.out.println("  Thread " + threadId + ": 成功 (累計成功: " + count + ")");
                        } else {
                            int count = failCount.incrementAndGet();
                            System.out.println("  Thread " + threadId + ": 失敗 (累計失敗: " + count + ")");
                        }
                    } catch (Exception e) {
                        System.err.println("  Thread " + threadId + ": エラー - " + e.getMessage());
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            // 全スレッド同時開始
            System.out.println("  全スレッド開始!");
            long startTime = System.currentTimeMillis();
            startLatch.countDown();

            // 全スレッド完了待ち
            endLatch.await(30, TimeUnit.SECONDS);
            long endTime = System.currentTimeMillis();

            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);

            System.out.println();
            System.out.println("  実行時間: " + (endTime - startTime) + "ms");
            System.out.println();

            // 4. 結果検証
            System.out.println("[4/4] 結果検証...");

            double finalCapacity = capacityManager.getCapacity(SRC_NODE, DST_NODE);
            int totalSuccess = successCount.get();
            int totalFail = failCount.get();

            System.out.println();
            System.out.println("=========================");
            System.out.println("テスト結果:");
            System.out.println("  成功数: " + totalSuccess + " (期待: " + LINK_CAPACITY + ")");
            System.out.println("  失敗数: " + totalFail + " (期待: " + (THREAD_COUNT - LINK_CAPACITY) + ")");
            System.out.println("  最終容量: " + finalCapacity + " (期待: 0)");
            System.out.println("  合計処理数: " + (totalSuccess + totalFail) + " (期待: " + THREAD_COUNT + ")");
            System.out.println();

            // 検証
            boolean successCountCorrect = (totalSuccess == LINK_CAPACITY);
            boolean failCountCorrect = (totalFail == THREAD_COUNT - LINK_CAPACITY);
            boolean capacityCorrect = (finalCapacity == 0.0);
            boolean totalCorrect = (totalSuccess + totalFail == THREAD_COUNT);
            boolean capacityNotNegative = (finalCapacity >= 0);

            boolean allPassed = successCountCorrect && failCountCorrect && capacityCorrect && totalCorrect && capacityNotNegative;

            if (allPassed) {
                System.out.println("✓ テスト成功！");
                System.out.println("  - 成功数が容量と一致");
                System.out.println("  - 失敗数が正確");
                System.out.println("  - 容量が負にならなかった");
                System.out.println("  - Luaスクリプトの原子性が確認された");
            } else {
                System.out.println("✗ テスト失敗");
                if (!successCountCorrect) {
                    System.out.println("  - 成功数が不正: " + totalSuccess + " != " + LINK_CAPACITY);
                }
                if (!failCountCorrect) {
                    System.out.println("  - 失敗数が不正: " + totalFail + " != " + (THREAD_COUNT - LINK_CAPACITY));
                }
                if (!capacityCorrect) {
                    System.out.println("  - 最終容量が不正: " + finalCapacity + " != 0");
                }
                if (!capacityNotNegative) {
                    System.out.println("  - 容量が負になった（競合発生）: " + finalCapacity);
                }
            }
            System.out.println("=========================");

        } catch (Exception e) {
            System.err.println("✗ テストエラー: " + e.getMessage());
            e.printStackTrace();
        } finally {
            manager.disconnect();
            System.out.println();
            System.out.println("✓ クリーンアップ完了");
        }
    }
}
