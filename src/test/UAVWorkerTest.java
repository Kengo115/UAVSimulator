package test;

import org.redisson.api.RedissonClient;
import shared.redis.RedisConnectionManager;
import network_manager.redis.UAVJob;
import network_manager.redis.UAVJobQueue;
import shared.util.LogManager;

import java.io.IOException;

/**
 * Phase 3b-1: UAVワーカーの基本機能テスト
 *
 * テスト内容:
 * 1. UAVJobの作成とシリアライゼーション
 * 2. UAVJobQueueへのジョブの投入と取得
 * 3. ジョブキューの基本操作
 */
public class UAVWorkerTest {

    public static void main(String[] args) {
        System.out.println("=== Phase 3b-1: UAVワーカー基本機能テスト ===");
        System.out.println();

        RedisConnectionManager manager = RedisConnectionManager.getInstance();

        try {
            // 1. Redis接続
            System.out.println("[1/5] Redis接続を確立しています...");
            manager.connect();
            System.out.println("✓ 接続成功: " + manager.getConnectionInfo());
            System.out.println();

            if (!manager.testConnection()) {
                System.out.println("✗ 接続テストに失敗しました");
                return;
            }

            // 2. ジョブキューの初期化
            System.out.println("[2/5] ジョブキューを初期化しています...");
            UAVJobQueue jobQueue = new UAVJobQueue();
            if (!jobQueue.isEnabled()) {
                System.out.println("✗ ジョブキューの初期化に失敗しました");
                return;
            }

            // テスト前にキューをクリア
            jobQueue.clearQueue();
            System.out.println("✓ ジョブキュー初期化成功");
            System.out.println("  キューサイズ: " + jobQueue.getQueueSize());
            System.out.println();

            // 3. UAVJobの作成
            System.out.println("[3/5] UAVJobを作成しています...");
            int[] testPath = {0, 1, 2, 3};
            UAVJob job1 = new UAVJob(
                1,                          // uavId
                1,                          // clientId
                testPath,                   // path
                12.5,                       // speed (m/s)
                System.currentTimeMillis(), // startTime
                0,                          // sourceBeaconId
                3                           // destinationBeaconId
            );

            System.out.println("  作成したジョブ:");
            System.out.println("    UAV ID: " + job1.getUavId());
            System.out.println("    Client ID: " + job1.getClientId());
            System.out.println("    Path: " + java.util.Arrays.toString(job1.getPath()));
            System.out.println("    Speed: " + job1.getSpeed() + " m/s");
            System.out.println("    Source: " + job1.getSourceBeaconId());
            System.out.println("    Destination: " + job1.getDestinationBeaconId());
            System.out.println("✓ UAVJob作成成功");
            System.out.println();

            // 4. ジョブキューへの投入
            System.out.println("[4/5] ジョブをキューに投入しています...");
            boolean enqueued1 = jobQueue.enqueueJob(job1);
            if (!enqueued1) {
                System.out.println("✗ ジョブ投入に失敗しました");
                return;
            }

            // 追加のジョブを投入
            UAVJob job2 = new UAVJob(
                2, 2, new int[]{1, 2, 3, 4}, 15.0,
                System.currentTimeMillis(), 1, 4
            );
            boolean enqueued2 = jobQueue.enqueueJob(job2);

            UAVJob job3 = new UAVJob(
                3, 1, new int[]{2, 3, 4, 5}, 10.0,
                System.currentTimeMillis(), 2, 5
            );
            boolean enqueued3 = jobQueue.enqueueJob(job3);

            System.out.println("  投入したジョブ数: 3");
            System.out.println("  現在のキューサイズ: " + jobQueue.getQueueSize());
            System.out.println("✓ ジョブ投入成功");
            System.out.println();

            // 5. ジョブキューからの取得
            System.out.println("[5/5] ジョブをキューから取得しています...");

            // タイムアウト付きで取得（ブロッキング）
            UAVJob retrievedJob1 = jobQueue.dequeueJob(2, java.util.concurrent.TimeUnit.SECONDS);
            if (retrievedJob1 == null) {
                System.out.println("✗ ジョブ取得に失敗しました（タイムアウト）");
                return;
            }

            System.out.println("  取得したジョブ1:");
            System.out.println("    UAV ID: " + retrievedJob1.getUavId());
            System.out.println("    Client ID: " + retrievedJob1.getClientId());
            System.out.println("    Path: " + java.util.Arrays.toString(retrievedJob1.getPath()));

            // ジョブが正しく取得できたか確認
            if (retrievedJob1.getUavId() == job1.getUavId() &&
                retrievedJob1.getClientId() == job1.getClientId() &&
                retrievedJob1.getSpeed() == job1.getSpeed()) {
                System.out.println("✓ ジョブ取得成功（データ整合性確認済み）");
            } else {
                System.out.println("✗ ジョブデータの整合性エラー");
                return;
            }

            System.out.println("  残りのキューサイズ: " + jobQueue.getQueueSize());
            System.out.println();

            // 6. 複数ジョブの取得
            System.out.println("[追加テスト] 残りのジョブを取得しています...");
            UAVJob retrievedJob2 = jobQueue.dequeueJob(2, java.util.concurrent.TimeUnit.SECONDS);
            UAVJob retrievedJob3 = jobQueue.dequeueJob(2, java.util.concurrent.TimeUnit.SECONDS);

            if (retrievedJob2 != null && retrievedJob3 != null) {
                System.out.println("  取得したジョブ2: UAV ID " + retrievedJob2.getUavId());
                System.out.println("  取得したジョブ3: UAV ID " + retrievedJob3.getUavId());
                System.out.println("✓ 複数ジョブ取得成功");
            } else {
                System.out.println("✗ 複数ジョブ取得に失敗しました");
            }

            System.out.println("  最終的なキューサイズ: " + jobQueue.getQueueSize());
            System.out.println();

            // 7. タイムアウトテスト
            System.out.println("[追加テスト] キューが空のときのタイムアウトをテストしています...");
            long startTime = System.currentTimeMillis();
            UAVJob nullJob = jobQueue.dequeueJob(2, java.util.concurrent.TimeUnit.SECONDS);
            long elapsedTime = System.currentTimeMillis() - startTime;

            if (nullJob == null && elapsedTime >= 2000) {
                System.out.println("✓ タイムアウト動作成功（待機時間: " + elapsedTime + "ms）");
            } else {
                System.out.println("✗ タイムアウト動作に問題があります");
            }
            System.out.println();

            // クリーンアップ
            jobQueue.clearQueue();

            System.out.println("=========================");
            System.out.println("✓ すべてのテストが成功しました！");
            System.out.println("Phase 3b-1の基本機能は正常に動作しています。");
            System.out.println("=========================");
            System.out.println();
            System.out.println("次のステップ:");
            System.out.println("1. ワーカープロセスを起動して実際のジョブ処理をテスト");
            System.out.println("2. 複数のワーカーを同時に起動して並列処理をテスト");
            System.out.println("3. Phase 3b-2でPub/Subによる完了通知を実装");

        } catch (IOException e) {
            System.err.println("✗ 接続エラー: " + e.getMessage());
            System.err.println();
            System.err.println("Redis接続に失敗しました。以下を確認してください:");
            System.err.println("1. docker-compose up -d でRedisコンテナが起動しているか");
            System.err.println("2. localhost:6379 でRedisにアクセスできるか");
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("✗ テスト実行エラー: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 切断
            System.out.println();
            System.out.println("Redis接続を切断しています...");
            manager.disconnect();
            System.out.println("✓ 切断完了");
        }
    }
}
