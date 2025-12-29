package test;

import org.redisson.api.RBucket;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import server.redis.RedisConnectionManager;
import server.redis.UAVCompletionEvent;
import server.redis.UAVEventChannels;
import server.redis.UAVJob;
import server.redis.UAVLinkPassedEvent;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * UAVイベントクラスのシリアライズ/デシリアライズをテストするユーティリティクラス
 * Phase 3b-2a: 基盤クラスの動作確認
 */
public class UAVEventSerializationTest {

    private static int passedTests = 0;
    private static int failedTests = 0;

    public static void main(String[] args) {
        System.out.println("=== UAVイベント シリアライズ/デシリアライズ テスト ===");
        System.out.println();

        RedisConnectionManager manager = RedisConnectionManager.getInstance();

        try {
            // 接続
            System.out.println("[準備] Redis接続を確立しています...");
            manager.connect();
            System.out.println("✓ 接続成功: " + manager.getConnectionInfo());
            System.out.println();

            RedissonClient client = manager.getClient();

            // テスト実行
            testUAVLinkPassedEventSerialization(client);
            testUAVCompletionEventSerialization(client);
            testUAVLinkPassedEventPubSub(client);
            testUAVCompletionEventPubSub(client);
            testUAVJobSerialization(client);

            // 結果サマリー
            System.out.println();
            System.out.println("=========================");
            System.out.println("テスト結果: " + passedTests + " 成功, " + failedTests + " 失敗");
            if (failedTests == 0) {
                System.out.println("✓ すべてのテストが成功しました！");
            } else {
                System.out.println("✗ 一部のテストが失敗しました");
            }
            System.out.println("=========================");

        } catch (IOException e) {
            System.err.println("✗ 接続エラー: " + e.getMessage());
            System.err.println("Redisが起動していることを確認してください: docker-compose up -d");
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("✗ テスト実行エラー: " + e.getMessage());
            e.printStackTrace();
        } finally {
            System.out.println();
            System.out.println("Redis接続を切断しています...");
            manager.disconnect();
            System.out.println("✓ 切断完了");
        }
    }

    /**
     * UAVLinkPassedEvent の RBucket シリアライズテスト
     */
    private static void testUAVLinkPassedEventSerialization(RedissonClient client) {
        System.out.println("[1/5] UAVLinkPassedEvent シリアライズテスト (RBucket)");

        try {
            // テストデータ作成
            int[] path = {0, 1, 4, 5};
            UAVLinkPassedEvent original = new UAVLinkPassedEvent(
                    1,          // uavId
                    2,          // clientId
                    0, 1,       // passedFromNode, passedToNode
                    1, 4,       // nextFromNode, nextToNode
                    path,       // path
                    0,          // currentLinkIndex
                    5.5         // elapsedFlightTime
            );

            System.out.println("  元データ: " + original);

            // Redisに保存
            RBucket<UAVLinkPassedEvent> bucket = client.getBucket("test:event:linkpassed");
            bucket.set(original);

            // Redisから読み込み
            UAVLinkPassedEvent restored = bucket.get();
            System.out.println("  復元データ: " + restored);

            // 検証
            boolean success = true;
            success &= checkEquals("uavId", original.getUavId(), restored.getUavId());
            success &= checkEquals("clientId", original.getClientId(), restored.getClientId());
            success &= checkEquals("passedFromNode", original.getPassedFromNode(), restored.getPassedFromNode());
            success &= checkEquals("passedToNode", original.getPassedToNode(), restored.getPassedToNode());
            success &= checkEquals("nextFromNode", original.getNextFromNode(), restored.getNextFromNode());
            success &= checkEquals("nextToNode", original.getNextToNode(), restored.getNextToNode());
            success &= checkEquals("currentLinkIndex", original.getCurrentLinkIndex(), restored.getCurrentLinkIndex());
            success &= checkEquals("elapsedFlightTime", original.getElapsedFlightTime(), restored.getElapsedFlightTime());
            success &= checkEquals("path", Arrays.toString(original.getPath()), Arrays.toString(restored.getPath()));
            success &= checkEquals("isLastLink", original.isLastLink(), restored.isLastLink());

            // クリーンアップ
            bucket.delete();

            if (success) {
                System.out.println("✓ UAVLinkPassedEvent シリアライズテスト成功");
                passedTests++;
            } else {
                System.out.println("✗ UAVLinkPassedEvent シリアライズテスト失敗");
                failedTests++;
            }
        } catch (Exception e) {
            System.out.println("✗ UAVLinkPassedEvent シリアライズテスト例外: " + e.getMessage());
            failedTests++;
        }
        System.out.println();
    }

    /**
     * UAVCompletionEvent の RBucket シリアライズテスト
     */
    private static void testUAVCompletionEventSerialization(RedissonClient client) {
        System.out.println("[2/5] UAVCompletionEvent シリアライズテスト (RBucket)");

        try {
            // テストデータ作成
            int[] path = {0, 1, 4, 5};
            UAVCompletionEvent original = new UAVCompletionEvent(
                    1,          // uavId
                    2,          // clientId
                    150.5,      // totalDistance
                    12.5,       // actualFlightTime
                    3.2,        // totalWaitingTime
                    path,       // path
                    0,          // sourceBeaconId
                    5           // destinationBeaconId
            );

            System.out.println("  元データ: " + original);

            // Redisに保存
            RBucket<UAVCompletionEvent> bucket = client.getBucket("test:event:completion");
            bucket.set(original);

            // Redisから読み込み
            UAVCompletionEvent restored = bucket.get();
            System.out.println("  復元データ: " + restored);

            // 検証
            boolean success = true;
            success &= checkEquals("uavId", original.getUavId(), restored.getUavId());
            success &= checkEquals("clientId", original.getClientId(), restored.getClientId());
            success &= checkEquals("totalDistance", original.getTotalDistance(), restored.getTotalDistance());
            success &= checkEquals("actualFlightTime", original.getActualFlightTime(), restored.getActualFlightTime());
            success &= checkEquals("totalWaitingTime", original.getTotalWaitingTime(), restored.getTotalWaitingTime());
            success &= checkEquals("sourceBeaconId", original.getSourceBeaconId(), restored.getSourceBeaconId());
            success &= checkEquals("destinationBeaconId", original.getDestinationBeaconId(), restored.getDestinationBeaconId());
            success &= checkEquals("lastLinkFromNode", original.getLastLinkFromNode(), restored.getLastLinkFromNode());
            success &= checkEquals("lastLinkToNode", original.getLastLinkToNode(), restored.getLastLinkToNode());
            success &= checkEquals("path", Arrays.toString(original.getPath()), Arrays.toString(restored.getPath()));

            // クリーンアップ
            bucket.delete();

            if (success) {
                System.out.println("✓ UAVCompletionEvent シリアライズテスト成功");
                passedTests++;
            } else {
                System.out.println("✗ UAVCompletionEvent シリアライズテスト失敗");
                failedTests++;
            }
        } catch (Exception e) {
            System.out.println("✗ UAVCompletionEvent シリアライズテスト例外: " + e.getMessage());
            failedTests++;
        }
        System.out.println();
    }

    /**
     * UAVLinkPassedEvent の Pub/Sub テスト
     */
    private static void testUAVLinkPassedEventPubSub(RedissonClient client) {
        System.out.println("[3/5] UAVLinkPassedEvent Pub/Sub テスト");

        try {
            // テストデータ作成
            int[] path = {0, 1, 4, 5};
            UAVLinkPassedEvent original = new UAVLinkPassedEvent(
                    3, 1, 1, 4, 4, 5, path, 1, 10.0
            );

            // 受信用の変数
            AtomicReference<UAVLinkPassedEvent> received = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            // Subscriber設定
            RTopic topic = client.getTopic(UAVEventChannels.LINK_PASSED);
            int listenerId = topic.addListener(UAVLinkPassedEvent.class, (channel, event) -> {
                System.out.println("  受信: " + event);
                received.set(event);
                latch.countDown();
            });

            // Publish
            System.out.println("  送信: " + original);
            topic.publish(original);

            // 受信待ち（最大3秒）
            boolean receivedInTime = latch.await(3, TimeUnit.SECONDS);

            // リスナー削除
            topic.removeListener(listenerId);

            // 検証
            boolean success = receivedInTime && received.get() != null;
            if (success) {
                UAVLinkPassedEvent restored = received.get();
                success &= checkEquals("uavId", original.getUavId(), restored.getUavId());
                success &= checkEquals("passedFromNode", original.getPassedFromNode(), restored.getPassedFromNode());
                success &= checkEquals("passedToNode", original.getPassedToNode(), restored.getPassedToNode());
            }

            if (success) {
                System.out.println("✓ UAVLinkPassedEvent Pub/Sub テスト成功");
                passedTests++;
            } else {
                System.out.println("✗ UAVLinkPassedEvent Pub/Sub テスト失敗 (タイムアウトまたはデータ不一致)");
                failedTests++;
            }
        } catch (Exception e) {
            System.out.println("✗ UAVLinkPassedEvent Pub/Sub テスト例外: " + e.getMessage());
            failedTests++;
        }
        System.out.println();
    }

    /**
     * UAVCompletionEvent の Pub/Sub テスト
     */
    private static void testUAVCompletionEventPubSub(RedissonClient client) {
        System.out.println("[4/5] UAVCompletionEvent Pub/Sub テスト");

        try {
            // テストデータ作成
            int[] path = {0, 1, 4, 5};
            UAVCompletionEvent original = new UAVCompletionEvent(
                    5, 2, 200.0, 15.0, 5.0, path, 0, 5
            );

            // 受信用の変数
            AtomicReference<UAVCompletionEvent> received = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            // Subscriber設定
            RTopic topic = client.getTopic(UAVEventChannels.COMPLETION);
            int listenerId = topic.addListener(UAVCompletionEvent.class, (channel, event) -> {
                System.out.println("  受信: " + event);
                received.set(event);
                latch.countDown();
            });

            // Publish
            System.out.println("  送信: " + original);
            topic.publish(original);

            // 受信待ち（最大3秒）
            boolean receivedInTime = latch.await(3, TimeUnit.SECONDS);

            // リスナー削除
            topic.removeListener(listenerId);

            // 検証
            boolean success = receivedInTime && received.get() != null;
            if (success) {
                UAVCompletionEvent restored = received.get();
                success &= checkEquals("uavId", original.getUavId(), restored.getUavId());
                success &= checkEquals("totalDistance", original.getTotalDistance(), restored.getTotalDistance());
                success &= checkEquals("destinationBeaconId", original.getDestinationBeaconId(), restored.getDestinationBeaconId());
            }

            if (success) {
                System.out.println("✓ UAVCompletionEvent Pub/Sub テスト成功");
                passedTests++;
            } else {
                System.out.println("✗ UAVCompletionEvent Pub/Sub テスト失敗 (タイムアウトまたはデータ不一致)");
                failedTests++;
            }
        } catch (Exception e) {
            System.out.println("✗ UAVCompletionEvent Pub/Sub テスト例外: " + e.getMessage());
            failedTests++;
        }
        System.out.println();
    }

    /**
     * UAVJob の RBucket シリアライズテスト（既存クラスの確認）
     */
    private static void testUAVJobSerialization(RedissonClient client) {
        System.out.println("[5/5] UAVJob シリアライズテスト (RBucket)");

        try {
            // テストデータ作成
            int[] path = {0, 1, 4, 5};
            UAVJob original = new UAVJob(
                    1,          // uavId
                    2,          // clientId
                    path,       // path
                    12.5,       // speed
                    System.currentTimeMillis(), // startTime
                    0,          // sourceBeaconId
                    5           // destinationBeaconId
            );

            System.out.println("  元データ: " + original);

            // Redisに保存
            RBucket<UAVJob> bucket = client.getBucket("test:job");
            bucket.set(original);

            // Redisから読み込み
            UAVJob restored = bucket.get();
            System.out.println("  復元データ: " + restored);

            // 検証
            boolean success = true;
            success &= checkEquals("uavId", original.getUavId(), restored.getUavId());
            success &= checkEquals("clientId", original.getClientId(), restored.getClientId());
            success &= checkEquals("speed", original.getSpeed(), restored.getSpeed());
            success &= checkEquals("sourceBeaconId", original.getSourceBeaconId(), restored.getSourceBeaconId());
            success &= checkEquals("destinationBeaconId", original.getDestinationBeaconId(), restored.getDestinationBeaconId());
            success &= checkEquals("path", Arrays.toString(original.getPath()), Arrays.toString(restored.getPath()));

            // クリーンアップ
            bucket.delete();

            if (success) {
                System.out.println("✓ UAVJob シリアライズテスト成功");
                passedTests++;
            } else {
                System.out.println("✗ UAVJob シリアライズテスト失敗");
                failedTests++;
            }
        } catch (Exception e) {
            System.out.println("✗ UAVJob シリアライズテスト例外: " + e.getMessage());
            failedTests++;
        }
        System.out.println();
    }

    /**
     * 値の比較とログ出力
     */
    private static boolean checkEquals(String fieldName, Object expected, Object actual) {
        boolean equals;
        if (expected instanceof Double && actual instanceof Double) {
            equals = Math.abs((Double) expected - (Double) actual) < 0.0001;
        } else {
            equals = expected.equals(actual);
        }

        if (!equals) {
            System.out.println("    ✗ " + fieldName + ": expected=" + expected + ", actual=" + actual);
        }
        return equals;
    }
}
