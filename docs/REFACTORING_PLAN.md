# 段階的リファクタリング計画：Redis + Docker ワーカー化

## 🎯 全体目標

現在のシングルプロセス・同期処理から、**Redis + Docker + 複数ワーカープロセス**による分散非同期処理への移行

---

## 📊 現在のアーキテクチャ

```
┌─────────────────────────────────────────┐
│    Single JVM Process                   │
│                                         │
│  ┌──────────────────────────────┐      │
│  │  BoundaryController (Main)   │      │
│  └──────────┬───────────────────┘      │
│             │                           │
│  ┌──────────▼───────────────────┐      │
│  │  UAVFlyScheduler             │      │
│  │  (ScheduledExecutorService)  │      │
│  │  - 2秒間隔で実行              │      │
│  └──────────┬───────────────────┘      │
│             │                           │
│  ┌──────────▼───────────────────┐      │
│  │  UAVFlightController.flyUAV()│      │
│  │  - 飛行中UAV処理              │      │
│  │  - 待機UAV処理                │      │
│  │  - 容量更新                   │      │
│  └──────────────────────────────┘      │
│                                         │
│  全状態がメモリ上                        │
└─────────────────────────────────────────┘
```

---

## 🎯 目標アーキテクチャ

```
┌──────────────────┐      ┌──────────────┐      ┌──────────────────┐
│ Controller       │      │    Redis     │      │ Worker Process   │
│ (Main Process)   │─────▶│              │◀─────│ (Multiple)       │
│                  │      │ - Job Queue  │      │                  │
│ - 経路探索        │      │ - UAV State  │      │ - UAV移動処理     │
│ - UAV割り当て    │      │ - Link State │      │ - タイマー管理    │
│ - 結果集約        │      │ - Pub/Sub    │      │ - 状態更新        │
└──────────────────┘      └──────────────┘      └──────────────────┘
     ↑                                                    ↓
     └────────────────── 完了通知 ──────────────────────┘
```

---

## 🚀 段階的移行計画

### Phase 0: 準備フェーズ（1-2日）

#### ゴール
- 開発環境の整備
- 依存関係の追加
- 既存コードの理解強化

#### タスク

1. **Docker環境構築**
```yaml
# docker-compose.yml を作成
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
```

2. **Redisson依存関係追加**
```xml
<!-- pom.xml または build.gradle -->
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson</artifactId>
    <version>3.24.3</version>
</dependency>
```

3. **現状の動作テストケース作成**
```java
// 既存機能の振る舞いを記録
@Test
public void testCurrentUAVFlightBehavior() {
    // 1 UAVが2秒ごとに移動することを確認
    // 2 容量超過で待機することを確認
    // 3 目的地到着で停止することを確認
}
```

#### 成果物
- ✅ Docker Composeファイル
- ✅ Redisson設定クラス
- ✅ 既存動作の回帰テスト

#### ロールバック
- なし（準備のみ）

---

### Phase 1: 二重書き込みパターンによるデータ整合性検証（完了✅）

#### ゴール
- **既存機能に影響を与えず**、UAV状態をメモリとRedisの**両方に書き込み**
- 定期的な整合性チェックで二重書き込みの正確性を検証
- 既存のメモリ状態が真実のソース（Source of Truth）
- Redisが失敗してもシミュレータは継続動作

#### アーキテクチャ

```
┌────────────────────┐
│ UAVFlightController│
│  flyUAV()          │
└─────┬──────────────┘
      │
      ├─ [既存] メモリ上で状態更新
      │
      └─ [新規] Redisに状態コピー（非同期）
           ↓
      ┌────────────┐
      │   Redis    │
      │ (読み取り専用)│
      └────────────┘
```

#### 実装

**1. UAVStateManager クラスの作成**
```java
// src/server/redis/UAVStateManager.java
public class UAVStateManager {
    private RedissonClient client;
    private boolean redisEnabled = true;

    public UAVStateManager() {
        // RedisConnectionManagerからクライアントを取得
        this.client = RedisConnectionManager.getInstance().getClient();
    }

    // UAV状態を同期的に書き込み（try-catchでエラーハンドリング）
    public void saveUAVState(Uav uav) {
        if (!redisEnabled) return;

        try {
            String key = "uav:" + uav.getId();
            RMap<String, Object> map = client.getMap(key);

            map.put("uavId", uav.getId());
            map.put("clientId", uav.getClientId());
            map.put("status", getStatusString(uav));
            map.put("speed", uav.getSpeed());
            map.put("x", uav.getX());
            map.put("y", uav.getY());
            // ... その他の状態
            map.put("lastUpdateTime", System.currentTimeMillis());
        } catch (Exception e) {
            LogManager.getInstance().error("UAV状態保存エラー", e);
            // 例外を投げずに継続
        }
    }
}
```

**2. UAVStateValidator クラスの作成（整合性検証）**
```java
// src/server/redis/UAVStateValidator.java
public class UAVStateValidator {
    private UAVStateManager stateManager;

    // メモリとRedisのUAV状態を比較
    public boolean validateUAVState(Uav uav) {
        Map<String, Object> redisState = stateManager.getUAVState(uav.getId());

        if (redisState.isEmpty()) {
            LogManager.getInstance().log("警告: UAV " + uav.getId() + " がRedisに存在しません");
            return false;
        }

        boolean isValid = true;

        // 各フィールドを比較
        if (!validateField(uav.getId(), "status", getStatusString(uav), redisState.get("status"))) {
            isValid = false;
        }
        // ... その他のフィールド検証

        return isValid;
    }
}
```

**3. UAVFlightController への統合（7箇所）**
```java
// src/server/uav/UAVFlightController.java
public static void flyUAV(...) {
    // [新規] メソッド開始時に全UAVをRedisに保存（初期化）
    for (Uav uav : flyingUavQueue) {
        try {
            uavStateManager.saveUAVState(uav);
        } catch (Exception e) {
            LogManager.getInstance().error("Redis書き込み失敗", e);
        }
    }

    // [既存] メモリ上での処理
    for (Uav uav : flyingUavQueue) {
        // ... 既存の処理 ...

        // [新規] 重要なイベントでRedisに同期的に保存
        // - UAV到着時
        // - リンク移動時
        // - 待機状態突入時
        // - 飛行再開時
        // - 待機継続時
        try {
            uavStateManager.saveUAVState(uav);
        } catch (Exception e) {
            LogManager.getInstance().error("Redis書き込み失敗", e);
        }
    }
}
```

**4. UAVFlyScheduler への整合性チェック統合**
```java
// src/server/uav/UAVFlyScheduler.java
private static int updateCounter = 0;
private static final int VALIDATION_INTERVAL = 5; // 5回に1回チェック
private static UAVStateValidator validator = new UAVStateValidator();

scheduler.scheduleAtFixedRate(() -> {
    server.controller.ServerController.flyUAV(...);

    // 5回に1回、整合性チェック
    updateCounter++;
    if (updateCounter % VALIDATION_INTERVAL == 0) {
        validateAllUAVStates(flyingUavQueue, uavQueue);
    }
}, 0, 2, TimeUnit.SECONDS);
```

**3. モニタリングツール**
```bash
# Redis CLIでリアルタイム監視
redis-cli
> KEYS uav:*
> HGETALL uav:1
```

#### テスト結果

**初回テスト**: 29件の不整合を検出
- 原因1: メソッド開始時の初期保存が不足
- 原因2: 待機継続時のRedis保存が欠落

**修正後のテスト**: 不整合0件 ✅
```
2025-12-28 14:01:25.135 - 整合性チェック: すべて正常 (40機)
```

#### 成果物
- ✅ UAVStateManager.java（Redis書き込み管理）
- ✅ UAVStateValidator.java（整合性検証） ← **計画外だが追加**
- ✅ UAVFlightController.java修正（7箇所でRedis保存）
- ✅ BoundaryController.java修正（Redis接続管理）
- ✅ UAVFlyScheduler.java修正（定期的整合性チェック）
- ✅ Makefile作成（簡単な実行コマンド）
- ✅ Redis Commander文字化け修正（JSON形式に変更）

#### ロールバック
```java
// UAVStateManager.redisEnabled = false; で無効化可能
// メモリベース処理は残っているので安全
```

#### 追加で実施した内容（計画外）
- **BinaryExtendedPhysarumSolverRouteSearcher.java修正**: PS流量制約の適用ロジック追加（UAV台数ずれ問題を解決）
- **Redis Commander文字化け修正**: JsonJacksonCodecを設定して読みやすいJSON形式に変更

---

### Phase 2: 読み取り切り替え（部分的）（3-4日）

**注**: 当初の計画では「リンク容量のRedis移行」でしたが、段階的アプローチのため変更しました。

#### ゴール
- **非クリティカルなデータの読み取りをRedisに切り替え**
- まずは統計情報とフライトログから開始
- UAVの位置情報など重要データはまだメモリから読み取り（Phase 3で対応）
- パフォーマンスと正確性を検証

#### アーキテクチャ

```
┌────────────────────────┐
│  統計情報・ログ参照     │
│  (新しい読み取りAPI)    │
└─────┬──────────────────┘
      │
      ├─ [既存] メモリから集計（Phase 1まで）
      │
      └─ [新規] Redisから読み取り（Phase 2）
           ↓
      ┌────────────┐
      │   Redis    │
      │ - uav:*    │← UAV状態（Phase 1で書き込み済み）
      │ - stats:*  │← 統計情報
      │ - logs:*   │← フライトログ
      └────────────┘

[重要] UAVのコア処理（位置更新、リンク容量）はまだメモリベース
```

#### 実装

**1. UAVStatisticsReader クラス（統計情報の読み取り）**
```java
// src/server/redis/UAVStatisticsReader.java
public class UAVStatisticsReader {
    private RedissonClient client;

    /**
     * 全UAVの状態カウントを取得
     * @return Map<状態, カウント> （例: {"flying": 25, "waiting": 10, "idle": 5}）
     */
    public Map<String, Integer> getUAVStatusCount() {
        Map<String, Integer> statusCount = new HashMap<>();
        statusCount.put("flying", 0);
        statusCount.put("waiting", 0);
        statusCount.put("idle", 0);

        // Redisから全UAV状態を取得
        RKeys keys = client.getKeys();
        Iterable<String> uavKeys = keys.getKeysByPattern("uav:*");

        for (String key : uavKeys) {
            RMap<String, Object> uavState = client.getMap(key);
            String status = (String) uavState.get("status");
            statusCount.put(status, statusCount.get(status) + 1);
        }

        return statusCount;
    }

    /**
     * クライアント別のUAV数を取得
     * @return Map<クライアントID, UAV数>
     */
    public Map<Integer, Integer> getUAVCountByClient() {
        // Redisから集計
    }

    /**
     * 平均飛行時間を取得
     * @return 平均飛行時間（ミリ秒）
     */
    public double getAverageFlightTime() {
        // Redisから全UAVのflightTimeを集計して平均
    }
}
```

**2. FlightLogReader クラス（フライトログの読み取り）**
```java
// src/server/redis/FlightLogReader.java
public class FlightLogReader {
    /**
     * UAVのフライト履歴を取得
     * @param uavId UAV ID
     * @return フライト履歴のリスト
     */
    public List<FlightRecord> getFlightHistory(int uavId) {
        String key = "uav:" + uavId + ":history";
        RList<FlightRecord> history = client.getList(key);
        return history.readAll();
    }

    /**
     * 最新のフライトイベントを取得
     * @param limit 取得件数
     * @return 最新のイベントリスト
     */
    public List<FlightEvent> getRecentEvents(int limit) {
        String key = "flight:events";
        RStream<FlightEvent> stream = client.getStream(key);
        return stream.readLast(limit);
    }
}
```

**3. PerformanceBenchmark クラス（パフォーマンス測定）**
```java
// src/server/redis/PerformanceBenchmark.java
public class PerformanceBenchmark {
    /**
     * 単一UAV読み取りのベンチマーク
     */
    public void benchmarkSingleRead() {
        // メモリから1000回読み取り → 平均時間測定
        long memoryStartTime = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            // メモリから読み取り
        }
        long memoryTime = (System.nanoTime() - memoryStartTime) / 1000000;

        // Redisから1000回読み取り → 平均時間測定
        long redisStartTime = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            uavStateManager.getUAVState(i);
        }
        long redisTime = (System.nanoTime() - redisStartTime) / 1000000;

        LogManager.getInstance().log("単一読み取り: メモリ=" + memoryTime + "ms, Redis=" + redisTime + "ms");
    }

    /**
     * 一括読み取りのベンチマーク
     */
    public void benchmarkBulkRead() {
        // 40機一括読み取りの比較
    }
}
```

**4. デュアルリード検証**
```java
// 既存の統計情報取得メソッドを修正
public class UAVStatisticsService {
    private boolean useRedis = false; // フラグで切り替え

    public Map<String, Integer> getStatusCount() {
        if (useRedis) {
            // Redisから読み取り
            return new UAVStatisticsReader().getUAVStatusCount();
        } else {
            // メモリから読み取り（既存）
            return getStatusCountFromMemory();
        }
    }
}
```

#### テスト計画

```java
@Test
public void testPhase2_StatisticsRead() {
    // 1. メモリから統計情報を取得
    Map<String, Integer> memoryStats = getStatusCountFromMemory();

    // 2. Redisから統計情報を取得
    Map<String, Integer> redisStats = new UAVStatisticsReader().getUAVStatusCount();

    // 3. 結果が一致することを確認
    assertEquals(memoryStats, redisStats);
}

@Test
public void testPhase2_Performance() {
    // パフォーマンス測定
    PerformanceBenchmark benchmark = new PerformanceBenchmark();
    benchmark.benchmarkSingleRead();
    benchmark.benchmarkBulkRead();
}
```

#### 成果物（予定）
- ⬜ UAVStatisticsReader.java
- ⬜ FlightLogReader.java
- ⬜ PerformanceBenchmark.java
- ⬜ デュアルリード検証機能
- ⬜ 読み取り切り替えフラグ実装

#### 成功基準
- ✅ 統計情報がRedisから正しく読み取れる
- ✅ メモリとRedisの結果が一致する
- ✅ パフォーマンスが許容範囲内（目標: 10ms以下）
- ✅ 1週間の運用で問題が発生しない

#### ロールバック
```java
// useRedis = false; でメモリ読み取りに戻せる
// Phase 1の二重書き込みは継続
```

---

### Phase 3: ワーカープロセスの導入（5-7日）

#### ゴール
- **別JVMでUAV処理を実行**
- Redisジョブキューを使用
- メインプロセスとワーカープロセスの協調動作

#### アーキテクチャ

```
┌──────────────────┐      ┌──────────────┐      ┌──────────────────┐
│ Main Process     │      │    Redis     │      │ Worker Process   │
│                  │      │              │      │                  │
│ ServerController │─────▶│ Job Queue    │◀─────│ UAVWorker        │
│  - 経路探索       │ LPUSH│ (List)       │ BRPOP│  - flyUAV()      │
│  - UAV割り当て   │      │              │      │  - タイマー管理   │
│                  │      │ Pub/Sub      │      │                  │
│                  │◀─────│ (完了通知)    │──────│                  │
└──────────────────┘      └──────────────┘      └──────────────────┘
```

#### 実装

**1. ジョブ定義**
```java
// src/server/redis/UAVJob.java
public class UAVJob implements Serializable {
    private int uavId;
    private int clientId;
    private int[] path;
    private double speed;
    private long startTime;
    private String flyingLinkKey;  // "0-1" 形式

    // getters/setters
}
```

**2. ジョブキュー管理**
```java
// src/server/redis/UAVJobQueue.java
public class UAVJobQueue {
    private static final String QUEUE_KEY = "uav:jobs";
    private RBlockingQueue<UAVJob> queue;

    public UAVJobQueue(RedissonClient redisson) {
        this.queue = redisson.getBlockingQueue(QUEUE_KEY);
    }

    // ジョブを追加（メインプロセス）
    public void enqueueJob(UAVJob job) {
        queue.offer(job);
        LogManager.getInstance().log("Enqueued UAV job: " + job.getUavId());
    }

    // ジョブを取得（ワーカープロセス）
    public UAVJob dequeueJob(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }
}
```

**3. ワーカープロセス**
```java
// src/server/worker/UAVWorker.java
public class UAVWorker {
    private UAVJobQueue jobQueue;
    private RedissonClient redisson;

    public static void main(String[] args) {
        // Redis接続
        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379");
        RedissonClient redisson = Redisson.create(config);

        UAVWorker worker = new UAVWorker(redisson);
        worker.start();
    }

    public void start() {
        LogManager.getInstance().log("UAV Worker started");

        while (true) {
            try {
                // ジョブを取得（ブロッキング）
                UAVJob job = jobQueue.dequeueJob(5, TimeUnit.SECONDS);

                if (job != null) {
                    processUAVJob(job);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void processUAVJob(UAVJob job) {
        LogManager.getInstance().log("Processing UAV: " + job.getUavId());

        // UAV状態をRedisから取得
        RMap<String, Object> uavState = redisson.getMap("uav:" + job.getUavId());

        // 2秒間隔で位置更新をシミュレート
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            // 飛行時間計算
            long elapsedTime = (System.currentTimeMillis() - job.getStartTime()) / 1000;
            double flightDistance = elapsedTime * job.getSpeed();

            // 状態更新
            uavState.put("flightTime", elapsedTime);
            uavState.put("flightDistance", flightDistance);

            // 目的地到着判定
            if (hasReachedDestination(job, flightDistance)) {
                // 完了通知
                RTopic topic = redisson.getTopic("uav:completed");
                topic.publish(job.getUavId());

                scheduler.shutdown();
            }
        }, 0, 2, TimeUnit.SECONDS);
    }

    private boolean hasReachedDestination(UAVJob job, double flightDistance) {
        // 経路の総距離を計算して比較
        // ... 実装 ...
        return false;
    }
}
```

**4. メインプロセスの修正**
```java
// src/server/controller/ServerController.java
public void run_EPS(...) throws IOException {
    // [既存] 経路探索
    extendedPhysarumSolverRouteSearcher.search(client, flyingUavQueue, uavQueue, numLoop);

    // [新規] ジョブをワーカーにエンキュー
    UAVJobQueue jobQueue = new UAVJobQueue(RedisManager.getRedisson());

    for (Uav uav : flyingUavQueue) {
        UAVJob job = new UAVJob();
        job.setUavId(uav.getId());
        job.setClientId(uav.getClientId());
        job.setPath(uav.getPath());
        job.setSpeed(uav.getSpeed());
        job.setStartTime(System.currentTimeMillis());

        jobQueue.enqueueJob(job);
    }

    // [新規] 完了通知を購読
    RTopic topic = RedisManager.getRedisson().getTopic("uav:completed");
    topic.addListener(Integer.class, (channel, uavId) -> {
        LogManager.getInstance().log("UAV " + uavId + " completed");
        // 完了カウンターを更新
    });
}
```

#### Docker設定

```dockerfile
# Dockerfile.worker
FROM openjdk:17-slim
WORKDIR /app
COPY target/uav-simulator-worker.jar /app/
CMD ["java", "-jar", "uav-simulator-worker.jar"]
```

```yaml
# docker-compose.yml
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  uav-worker-1:
    build:
      context: .
      dockerfile: Dockerfile.worker
    depends_on:
      - redis
    environment:
      - REDIS_URL=redis://redis:6379
      - WORKER_ID=1

  uav-worker-2:
    build:
      context: .
      dockerfile: Dockerfile.worker
    depends_on:
      - redis
    environment:
      - REDIS_URL=redis://redis:6379
      - WORKER_ID=2
```

#### テスト

```java
@Test
public void testPhase3_WorkerProcessing() {
    // 1. Redisが起動していることを確認
    // 2. ワーカーを起動
    // 3. ジョブをエンキュー
    // 4. ワーカーが処理することを確認
    // 5. 完了通知が届くことを確認
}
```

#### 成果物
- ✅ UAVWorker実装
- ✅ ジョブキュー実装
- ✅ Docker設定
- ✅ Pub/Sub実装

#### ロールバック
- ワーカーを起動しなければ既存処理で動作

---

### Phase 4: 完全移行とスケーリング（3-4日）

#### ゴール
- 既存のScheduledExecutorServiceを削除
- 完全にRedis + Workerベースの処理
- 水平スケール可能に

#### 実装

**1. UAVFlyScheduler の非推奨化**
```java
// src/server/uav/UAVFlyScheduler.java
@Deprecated
public class UAVFlyScheduler {
    // この実装は使用されない
    // すべてRedisワーカーで処理
}
```

**2. スケーリング設定**
```yaml
# docker-compose.yml
services:
  uav-worker:
    build:
      context: .
      dockerfile: Dockerfile.worker
    depends_on:
      - redis
    environment:
      - REDIS_URL=redis://redis:6379
    deploy:
      replicas: 5  # 5つのワーカープロセス
```

#### テスト

```java
@Test
public void testPhase4_FullMigration() {
    // 1. 100台のUAVをシミュレーション
    // 2. 複数ワーカーで並列処理
    // 3. すべてのUAVが正しく完了することを確認
    // 4. パフォーマンスを測定
}
```

#### 成果物
- ✅ 既存コードの削除
- ✅ スケーリング検証
- ✅ パフォーマンステスト

---

### Phase 5: モニタリングと最適化（2-3日）

#### ゴール
- Redisモニタリング
- パフォーマンス最適化
- 障害対応

#### 実装

**1. Redis Insightの導入**
```yaml
# docker-compose.yml
services:
  redis-insight:
    image: redislabs/redisinsight:latest
    ports:
      - "8001:8001"
```

**2. メトリクス収集**
```java
// src/server/monitoring/MetricsCollector.java
public class MetricsCollector {
    public static void recordUAVProcessingTime(int uavId, long duration) {
        RTimeSeries<Long> timeSeries = redisson.getTimeSeries("metrics:processing_time");
        timeSeries.add(System.currentTimeMillis(), duration);
    }

    public static void recordQueueLength() {
        RBlockingQueue<UAVJob> queue = redisson.getBlockingQueue("uav:jobs");
        int queueLength = queue.size();

        RTimeSeries<Integer> timeSeries = redisson.getTimeSeries("metrics:queue_length");
        timeSeries.add(System.currentTimeMillis(), queueLength);
    }
}
```

#### 成果物
- ✅ モニタリングダッシュボード
- ✅ メトリクス収集
- ✅ アラート設定

---

## 📅 全体スケジュール

| Phase | 期間 | 主要成果物 | リスク | ステータス |
|-------|------|-----------|--------|-----------|
| **Phase 0** | 1-2日 | Docker環境、Redisson設定、Redis Commander | 低 | ✅ **完了** (2025-12-27) |
| **Phase 1** | 3-4日 | 二重書き込み、整合性検証、BinaryEPS修正 | 低 | ✅ **完了** (2025-12-28) |
| **Phase 2** | 3-4日 | 統計情報読み取り、ログ読み取り、パフォーマンス測定 | 中 | 🔄 **準備中** |
| **Phase 3** | 5-7日 | リンク容量Redis移行、ワーカープロセス | 高 | ⬜ 未着手 |
| **Phase 4** | 3-4日 | 完全移行 | 中 | ⬜ 未着手 |
| **Phase 5** | 2-3日 | モニタリング | 低 | ⬜ 未着手 |
| **合計** | **18-25日** | | | **進捗: 2/6完了** |

### 実績
- Phase 0: 実施日数 **約0.5日**（2025-12-27）
- Phase 1: 実施日数 **約1日**（2025-12-28）
- 合計: **約1.5日**（計画3-6日に対して効率的に完了）

### Phase 2以降の変更
- **Phase 2**: 「リンク容量Redis移行」→「統計情報読み取り切り替え」に変更（段階的アプローチのため）
- **Phase 3**: リンク容量Redis移行とワーカープロセス導入を統合予定

---

## ⚠️ リスクと対策

### リスク1: Redis障害
**対策**：
- Redis Sentinelで高可用性
- フェイルオーバー自動化
- メモリベース処理を残す（Phase 3まで）

### リスク2: パフォーマンス劣化
**対策**：
- Phase 1でベンチマーク取得
- 各Phaseでパフォーマンステスト
- Redisパイプライニング活用

### リスク3: 状態不整合
**対策**：
- トランザクション使用
- 楽観的ロック
- 定期的な整合性チェック

---

## 🎯 成功基準

### 機能要件
- ✅ 既存と同じシミュレーション結果
- ✅ UAVの飛行・待機・完了が正常動作
- ✅ リンク容量制約が正しく機能

### 非機能要件
- ✅ 100台のUAVを同時処理可能
- ✅ ワーカーを動的にスケール可能
- ✅ Redis障害時のグレースフルデグラデーション

### 開発要件
- ✅ 各Phaseでロールバック可能
- ✅ テストカバレッジ80%以上
- ✅ ドキュメント完備

---

## 🔧 開発ガイドライン

### コーディング規約
```java
// [Phase X] タグでフェーズを明記
// [Phase 1] Redis状態同期
RedisManager.publishUAVState(uav);

// [既存] タグで既存コードを保護
// [既存] メモリベースの処理
link[i][j].setCapacity(capacity);
```

### テスト方針
- 各Phase完了時に回帰テスト
- Phase 3以降は統合テスト必須
- 本番相当のデータでロードテスト

### レビュープロセス
- Phase完了ごとにコードレビュー
- アーキテクチャレビュー（Phase 2, 3）
- パフォーマンスレビュー（Phase 4）

---

## 📚 次のアクション

1. **Phase 0を開始する場合**：
   - `docker-compose.yml` の作成
   - Redisson依存関係の追加
   - Redis接続テスト

2. **詳細設計が必要な場合**：
   - 各Phaseの詳細設計書作成
   - データモデル定義
   - API仕様書作成

3. **プロトタイプを作りたい場合**：
   - Phase 1の簡易版実装
   - 動作デモ
   - フィードバック収集

**どこから始めますか？**
