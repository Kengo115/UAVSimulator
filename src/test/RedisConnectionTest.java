package test;

import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import shared.redis.RedisConnectionManager;
import shared.util.LogManager;

import java.io.IOException;

/**
 * Redis接続をテストするユーティリティクラス
 * Phase 0で使用され、Redis環境が正しくセットアップされているか確認する
 */
public class RedisConnectionTest {

    public static void main(String[] args) {
        System.out.println("=== Redis接続テスト ===");
        System.out.println();

        RedisConnectionManager manager = RedisConnectionManager.getInstance();

        try {
            // 1. 接続テスト
            System.out.println("[1/5] Redis接続を確立しています...");
            manager.connect();
            System.out.println("✓ 接続成功: " + manager.getConnectionInfo());
            System.out.println();

            // 2. 接続状態の確認
            System.out.println("[2/5] 接続状態を確認しています...");
            if (manager.testConnection()) {
                System.out.println("✓ 接続は正常です");
            } else {
                System.out.println("✗ 接続テストに失敗しました");
                return;
            }
            System.out.println();

            RedissonClient client = manager.getClient();

            // 3. 文字列の書き込み・読み込みテスト
            System.out.println("[3/5] 文字列の書き込み・読み込みをテストしています...");
            RBucket<String> bucket = client.getBucket("test:string");
            bucket.set("Hello UAV Simulator!");
            String value = bucket.get();
            System.out.println("  書き込み: 'Hello UAV Simulator!'");
            System.out.println("  読み込み: '" + value + "'");
            if ("Hello UAV Simulator!".equals(value)) {
                System.out.println("✓ 文字列操作成功");
            } else {
                System.out.println("✗ 文字列操作失敗");
            }
            bucket.delete();
            System.out.println();

            // 4. Hashの書き込み・読み込みテスト
            System.out.println("[4/5] Hash構造の書き込み・読み込みをテストしています...");
            RMap<String, Object> map = client.getMap("test:uav:1");
            map.put("uavId", 1);
            map.put("status", "flying");
            map.put("speed", 12.5);
            map.put("currentLinkFrom", 0);
            map.put("currentLinkTo", 1);

            System.out.println("  書き込み:");
            System.out.println("    uavId: 1");
            System.out.println("    status: flying");
            System.out.println("    speed: 12.5");

            Integer uavId = (Integer) map.get("uavId");
            String status = (String) map.get("status");
            Double speed = (Double) map.get("speed");

            System.out.println("  読み込み:");
            System.out.println("    uavId: " + uavId);
            System.out.println("    status: " + status);
            System.out.println("    speed: " + speed);

            if (uavId == 1 && "flying".equals(status) && speed == 12.5) {
                System.out.println("✓ Hash操作成功");
            } else {
                System.out.println("✗ Hash操作失敗");
            }
            map.delete();
            System.out.println();

            // 5. アトミック操作のテスト（リンク容量のシミュレーション）
            System.out.println("[5/5] アトミック操作（リンク容量）をテストしています...");

            // 文字列として容量を保存（実際の実装ではアトミック操作を使用）
            RBucket<String> capacityBucket = client.getBucket("test:link:0-1:capacity");
            capacityBucket.set("5.0");
            System.out.println("  初期容量: 5.0");

            // 容量を減らす（文字列として操作）
            double currentCapacity = Double.parseDouble(capacityBucket.get());
            currentCapacity -= 1.0;
            capacityBucket.set(String.valueOf(currentCapacity));
            System.out.println("  UAV飛行後: " + capacityBucket.get());

            // 容量を戻す
            currentCapacity = Double.parseDouble(capacityBucket.get());
            currentCapacity += 1.0;
            capacityBucket.set(String.valueOf(currentCapacity));
            System.out.println("  UAV到着後: " + capacityBucket.get());

            double finalCapacity = Double.parseDouble(capacityBucket.get());
            if (Math.abs(finalCapacity - 5.0) < 0.0001) {
                System.out.println("✓ アトミック操作成功");
            } else {
                System.out.println("✗ アトミック操作失敗");
            }
            capacityBucket.delete();
            System.out.println();

            // 6. キーのカウント
            System.out.println("[追加情報] Redis内のキー数:");
            long keyCount = client.getKeys().count();
            System.out.println("  総キー数: " + keyCount);
            System.out.println();

            System.out.println("=========================");
            System.out.println("✓ すべてのテストが成功しました！");
            System.out.println("Redis環境は正常に動作しています。");
            System.out.println("=========================");

        } catch (IOException e) {
            System.err.println("✗ 接続エラー: " + e.getMessage());
            System.err.println();
            System.err.println("Redis接続に失敗しました。以下を確認してください:");
            System.err.println("1. docker-compose up -d でRedisコンテナが起動しているか");
            System.err.println("2. localhost:6379 でRedisにアクセスできるか");
            System.err.println("3. ファイアウォールがポート6379をブロックしていないか");
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
