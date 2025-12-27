# Redis データ構造設計

## 概要

このドキュメントでは、UAVシミュレーターのRedis化における詳細なデータ構造を定義します。

---

## 1. UAV状態管理

### データ構造: Hash

```
Key: uav:{uavId}
Type: HASH

Fields:
- uavId: Integer          # UAV ID
- clientId: Integer       # クライアント ID
- status: String          # "flying" | "waiting" | "completed"
- flightTime: Long        # 累積飛行時間（秒）
- waitingTime: Long       # 累積待機時間（秒）
- speed: Double           # 速度 (m/s)
- currentLinkFrom: Integer # 現在のリンク始点 (-1: 未飛行)
- currentLinkTo: Integer   # 現在のリンク終点 (-1: 未飛行)
- stayedBeaconId: Integer  # 待機中のビーコン (-1: なし)
- path: String            # 経路 "0-1-4-5" 形式
- startTime: Long         # 飛行開始時刻（ミリ秒）
- lastUpdateTime: Long    # 最終更新時刻（ミリ秒）
```

### 使用例

```bash
# UAV状態の設定
HSET uav:1 uavId 1
HSET uav:1 clientId 1
HSET uav:1 status flying
HSET uav:1 flightTime 10
HSET uav:1 currentLinkFrom 0
HSET uav:1 currentLinkTo 1
HSET uav:1 path "0-1-4-5"

# UAV状態の取得
HGETALL uav:1

# 特定フィールドの取得
HGET uav:1 status
HGET uav:1 flightTime

# 複数フィールドの取得
HMGET uav:1 status flightTime waitingTime
```

### TTL設定

```bash
# 完了後1時間で自動削除
EXPIRE uav:1 3600
```

---

## 2. リンク容量管理

### データ構造: String (Atomic Double)

```
Key: link:{from}-{to}:capacity
Type: STRING (Atomic Double)

Value: Double  # 現在の容量
```

### 使用例

```bash
# 初期容量の設定
SET link:0-1:capacity 5.0

# アトミックな減算
DECRBYFLOAT link:0-1:capacity 1.0

# アトミックな加算
INCRBYFLOAT link:0-1:capacity 1.0

# 現在の容量取得
GET link:0-1:capacity

# Compare-and-Set (Lua script)
EVAL "
  local capacity = tonumber(redis.call('GET', KEYS[1]))
  if capacity > 0 then
    redis.call('DECRBYFLOAT', KEYS[1], 1.0)
    return 1
  else
    return 0
  end
" 1 link:0-1:capacity
```

### 初期容量の保存

```
Key: link:{from}-{to}:init_capacity
Type: STRING

Value: Double  # 初期容量（リセット用）
```

---

## 3. ジョブキュー

### データ構造: List

```
Key: uav:jobs
Type: LIST

Element: JSON String
{
  "uavId": 1,
  "clientId": 1,
  "path": [0, 1, 4, 5],
  "speed": 12.5,
  "startTime": 1234567890123,
  "flyingLinkFrom": 0,
  "flyingLinkTo": 1,
  "priority": 1
}
```

### 使用例

```bash
# ジョブのエンキュー（左端に追加）
LPUSH uav:jobs '{"uavId":1,"clientId":1,"path":[0,1,5],...}'

# ジョブのデキュー（右端から取得、ブロッキング）
BRPOP uav:jobs 5  # 5秒タイムアウト

# キュー長の取得
LLEN uav:jobs

# キューの内容確認（削除しない）
LRANGE uav:jobs 0 -1
```

---

## 4. 完了通知（Pub/Sub）

### チャネル: uav:completed

```
Channel: uav:completed
Message: JSON String
{
  "uavId": 1,
  "clientId": 1,
  "totalFlightTime": 45,
  "totalWaitingTime": 5,
  "completedAt": 1234567890123
}
```

### 使用例

```bash
# 購読（ワーカー）
SUBSCRIBE uav:completed

# 発行（メインプロセス）
PUBLISH uav:completed '{"uavId":1,"totalFlightTime":45,...}'

# パターン購読（すべてのUAVイベント）
PSUBSCRIBE uav:*
```

---

## 5. ビーコン状態

### データ構造: Hash + Set

```
Key: beacon:{beaconId}
Type: HASH

Fields:
- beaconId: Integer
- x: Double
- y: Double
- waitingUavCount: Integer

Key: beacon:{beaconId}:waiting_uavs
Type: SET

Members: UAV IDのリスト
```

### 使用例

```bash
# ビーコン情報の設定
HMSET beacon:0 beaconId 0 x 0.1 y 0.4 waitingUavCount 0

# 待機UAVの追加
SADD beacon:0:waiting_uavs 1 2 3
HINCRBY beacon:0 waitingUavCount 3

# 待機UAVの削除
SREM beacon:0:waiting_uavs 1
HINCRBY beacon:0 waitingUavCount -1

# 待機UAV一覧の取得
SMEMBERS beacon:0:waiting_uavs
```

---

## 6. クライアント状態

### データ構造: Hash

```
Key: client:{clientId}
Type: HASH

Fields:
- clientId: Integer
- sourceId: Integer
- destinationId: Integer
- requiredUavCount: Integer
- completedUavCount: Integer
- startTime: Long
- status: String  # "active" | "completed"
```

### 使用例

```bash
# クライアント情報の設定
HMSET client:1 clientId 1 sourceId 0 destinationId 5 requiredUavCount 40

# 完了カウンターのインクリメント（アトミック）
HINCRBY client:1 completedUavCount 1

# 完了判定
EVAL "
  local required = tonumber(redis.call('HGET', KEYS[1], 'requiredUavCount'))
  local completed = tonumber(redis.call('HGET', KEYS[1], 'completedUavCount'))
  return completed >= required
" 1 client:1
```

---

## 7. メトリクス（時系列データ）

### データ構造: Sorted Set

```
Key: metrics:queue_length
Type: SORTED SET

Score: Timestamp (ミリ秒)
Member: Value (キュー長)
```

### 使用例

```bash
# メトリクスの追加
ZADD metrics:queue_length 1234567890123 10

# 時間範囲でメトリクス取得
ZRANGEBYSCORE metrics:queue_length 1234567890000 1234567899999

# 最新のメトリクス取得
ZRANGE metrics:queue_length -1 -1 WITHSCORES

# 古いメトリクスの削除（1時間前より古いもの）
ZREMRANGEBYSCORE metrics:queue_length 0 (現在時刻 - 3600000)
```

---

## 8. グローバル設定

### データ構造: Hash

```
Key: config:global
Type: HASH

Fields:
- nodeCount: Integer
- updateIntervalSeconds: Integer
- workerCount: Integer
- redisVersion: String
```

### 使用例

```bash
# 設定の保存
HMSET config:global nodeCount 6 updateIntervalSeconds 2 workerCount 3

# 設定の取得
HGETALL config:global
```

---

## 9. ロック機構

### データ構造: String (Redisson Lock)

```
Key: lock:link:{from}-{to}
Type: STRING

Value: Lock Token
TTL: 30秒（自動開放）
```

### 使用例（Redisson）

```java
RLock lock = redisson.getLock("lock:link:0-1");
try {
    // ロック取得（最大10秒待機、30秒後自動開放）
    boolean acquired = lock.tryLock(10, 30, TimeUnit.SECONDS);
    if (acquired) {
        // クリティカルセクション
        // リンク容量の複雑な操作
    }
} finally {
    lock.unlock();
}
```

---

## 10. トランザクション（Lua Script）

### UAV移動のアトミック処理

```lua
-- uav_move.lua
-- KEYS[1]: uav:{uavId}
-- KEYS[2]: link:{from}-{to}:capacity
-- ARGV[1]: from node
-- ARGV[2]: to node

local capacity = tonumber(redis.call('GET', KEYS[2]))

if capacity > 0 then
    -- 容量を減らす
    redis.call('DECRBYFLOAT', KEYS[2], 1.0)

    -- UAV状態を更新
    redis.call('HSET', KEYS[1], 'status', 'flying')
    redis.call('HSET', KEYS[1], 'currentLinkFrom', ARGV[1])
    redis.call('HSET', KEYS[1], 'currentLinkTo', ARGV[2])
    redis.call('HSET', KEYS[1], 'lastUpdateTime', ARGV[3])

    return 1  -- 成功
else
    -- UAV状態を待機に
    redis.call('HSET', KEYS[1], 'status', 'waiting')
    redis.call('HSET', KEYS[1], 'stayedBeaconId', ARGV[1])

    return 0  -- 容量不足
end
```

### 使用例

```java
String script = loadLuaScript("uav_move.lua");
List<Object> keys = Arrays.asList("uav:1", "link:0-1:capacity");
List<Object> args = Arrays.asList(0, 1, System.currentTimeMillis());

Object result = redisson.getScript().eval(
    RScript.Mode.READ_WRITE,
    script,
    RScript.ReturnType.INTEGER,
    keys,
    args
);
```

---

## メモリ使用量見積もり

### 前提条件
- ノード数: 6
- リンク数: 16（双方向8本）
- UAV数: 100

### データサイズ

| データ種別 | キー数 | 1キーあたり | 合計 |
|-----------|--------|-------------|------|
| UAV状態 | 100 | 500 bytes | 50 KB |
| リンク容量 | 16 | 50 bytes | 0.8 KB |
| ビーコン | 6 | 200 bytes | 1.2 KB |
| クライアント | 3 | 300 bytes | 0.9 KB |
| ジョブキュー | 100 | 300 bytes | 30 KB |
| **合計** | | | **約83 KB** |

### 推奨Redis設定

```conf
# redis.conf
maxmemory 256mb
maxmemory-policy allkeys-lru  # メモリ不足時にLRU削除

# AOF永続化（データ保護）
appendonly yes
appendfsync everysec

# スナップショット
save 900 1
save 300 10
save 60 10000
```

---

## パフォーマンス最適化

### 1. パイプライニング

```java
RBatch batch = redisson.createBatch();

// 複数の操作をバッチ実行
batch.getMap("uav:1").putAsync("status", "flying");
batch.getMap("uav:1").putAsync("flightTime", 10);
batch.getAtomicDouble("link:0-1:capacity").decrementAndGetAsync();

// 一括実行
BatchResult result = batch.execute();
```

### 2. キャッシング

```java
// UAV状態をローカルキャッシュ
LoadingCache<Integer, Map<String, Object>> uavCache = Caffeine.newBuilder()
    .maximumSize(1000)
    .expireAfterWrite(2, TimeUnit.SECONDS)
    .build(uavId -> {
        RMap<String, Object> map = redisson.getMap("uav:" + uavId);
        return new HashMap<>(map);
    });
```

### 3. 接続プーリング

```java
Config config = new Config();
config.useSingleServer()
    .setAddress("redis://localhost:6379")
    .setConnectionPoolSize(50)  // 接続プールサイズ
    .setConnectionMinimumIdleSize(10);
```

---

## エラーハンドリング

### Redis接続エラー

```java
try {
    RMap<String, Object> map = redisson.getMap("uav:1");
    map.put("status", "flying");
} catch (RedisConnectionException e) {
    // フォールバック: メモリベース処理
    LogManager.getInstance().error("Redis connection failed, fallback to memory", e);
    uav.setStatus("flying");  // ローカル処理
}
```

### タイムアウト

```java
try {
    RBlockingQueue<UAVJob> queue = redisson.getBlockingQueue("uav:jobs");
    UAVJob job = queue.poll(5, TimeUnit.SECONDS);  // 5秒タイムアウト

    if (job == null) {
        // タイムアウト処理
        LogManager.getInstance().log("No jobs available");
    }
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

---

## モニタリング

### Redis INFO コマンド

```bash
# メモリ使用量
redis-cli INFO memory

# 接続数
redis-cli INFO clients

# コマンド統計
redis-cli INFO commandstats

# キースペース統計
redis-cli INFO keyspace
```

### Redissonメトリクス

```java
// Redissonの統計情報
RedissonClient redisson = Redisson.create(config);
Config cfg = redisson.getConfig();

// 接続プール統計
ConnectionManager connManager = redisson.getConnectionManager();
int activeConnections = connManager.getConnectionPool().getIdleSize();
```

---

## 次のステップ

このデータ設計に基づいて：

1. **Phase 0**: Redisセットアップと接続テスト
2. **Phase 1**: UAV状態の書き込みテスト
3. **Phase 2**: リンク容量のアトミック操作テスト
4. **Phase 3**: ジョブキューとワーカーの実装

**質問や懸念点はありますか？**
