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

### Phase 1: 状態の可視化とRedis読み取り（3-4日）

#### ゴール
- **既存機能に影響を与えず**、UAV状態をRedisに**書き込むのみ**
- Redisを「状態モニタリング」として使用
- 既存のメモリ状態が真実のソース（Source of Truth）

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

**1. RedisManager クラスの作成**
```java
// src/server/redis/RedisManager.java
public class RedisManager {
    private static RedissonClient redisson;
    private static boolean enabled = false;

    public static void initialize(String redisUrl) {
        Config config = new Config();
        config.useSingleServer().setAddress(redisUrl);
        redisson = Redisson.create(config);
        enabled = true;
    }

    // UAV状態を非同期で書き込み（既存処理に影響なし）
    public static void publishUAVState(Uav uav) {
        if (!enabled) return;

        CompletableFuture.runAsync(() -> {
            try {
                RMap<String, Object> uavState = redisson.getMap("uav:" + uav.getId());
                uavState.put("clientId", uav.getClientId());
                uavState.put("status", uav.isFlying() ? "flying" : "waiting");
                uavState.put("flightTime", uav.getFlightTime());
                uavState.put("waitingTime", uav.getWaitingTime());
                // ... その他の状態
            } catch (Exception e) {
                // Redisエラーでも既存処理に影響させない
                LogManager.getInstance().error("Redis write error", e);
            }
        });
    }
}
```

**2. UAVFlightController への統合**
```java
// src/server/uav/UAVFlightController.java
public static void flyUAV(...) {
    // [既存] メモリ上での処理
    for (Uav uav : flyingUavQueue) {
        // ... 既存の処理 ...

        // [新規] Redisに状態をコピー（非ブロッキング）
        RedisManager.publishUAVState(uav);
    }
}
```

**3. モニタリングツール**
```bash
# Redis CLIでリアルタイム監視
redis-cli
> KEYS uav:*
> HGETALL uav:1
```

#### テスト

```java
@Test
public void testPhase1_RedisStateSync() {
    // 1. UAVを飛ばす
    // 2. Redisに状態が書き込まれることを確認
    // 3. Redisが落ちても既存機能が動作することを確認
}
```

#### 成果物
- ✅ RedisManagerクラス
- ✅ 状態同期の実装
- ✅ モニタリングダッシュボード（簡易）

#### ロールバック
```java
// RedisManager.enabled = false; で無効化
```

---

### Phase 2: リンク容量のRedis移行（4-5日）

#### ゴール
- リンク容量管理を**Redisのアトミック操作**で実装
- 複数プロセスからの同時アクセスに対応
- 既存のメモリベース処理と並行稼働（ダブルライト）

#### アーキテクチャ

```
┌────────────────────┐
│ CapacityManager    │
└─────┬──────────────┘
      │
      ├─ [既存] メモリ配列で容量管理
      │
      └─ [新規] Redisでも容量管理（同期）
           ↓
      ┌────────────┐
      │   Redis    │
      │ DECR/INCR  │← アトミック操作
      └────────────┘
```

#### 実装

**1. RedisCapacityManager クラス**
```java
// src/server/redis/RedisCapacityManager.java
public class RedisCapacityManager {
    private static RedissonClient redisson;

    // リンク容量の初期化
    public static void initializeLinkCapacity(int from, int to, double capacity) {
        String key = "link:" + from + "-" + to + ":capacity";
        RAtomicDouble atomicCapacity = redisson.getAtomicDouble(key);
        atomicCapacity.set(capacity);
    }

    // 容量を減らす（アトミック）
    public static boolean decrementCapacity(int from, int to) {
        String key = "link:" + from + "-" + to + ":capacity";
        RAtomicDouble atomicCapacity = redisson.getAtomicDouble(key);

        // Compare-and-Set で安全に減少
        double current = atomicCapacity.get();
        if (current > 0) {
            atomicCapacity.decrementAndGet();
            return true;
        }
        return false;
    }

    // 容量を増やす（アトミック）
    public static void incrementCapacity(int from, int to) {
        String key = "link:" + from + "-" + to + ":capacity";
        RAtomicDouble atomicCapacity = redisson.getAtomicDouble(key);
        atomicCapacity.incrementAndGet();
    }

    // 現在の容量を取得
    public static double getCapacity(int from, int to) {
        String key = "link:" + from + "-" + to + ":capacity";
        RAtomicDouble atomicCapacity = redisson.getAtomicDouble(key);
        return atomicCapacity.get();
    }
}
```

**2. CapacityManager の修正（ダブルライト）**
```java
// src/server/uav/CapacityManager.java
public static void updateCapacity(int[][] flyingUAV, Link[][] link, int node) {
    // [既存] メモリベースの処理
    for (int i = 0; i < node; i++) {
        for (int j = 0; j < node; j++) {
            if (link[i][j].getL_tubeLength() != INF) {
                link[i][j].setCapacity(link[i][j].getInitCapacity());
                // ... 既存処理 ...
            }
        }
    }

    // [新規] Redisでも同じ操作（検証用）
    for (int i = 0; i < node; i++) {
        for (int j = 0; j < node; j++) {
            if (link[i][j].getL_tubeLength() != INF) {
                double memoryCapacity = link[i][j].getCapacity();
                double redisCapacity = RedisCapacityManager.getCapacity(i, j);

                // 不整合をログ出力
                if (Math.abs(memoryCapacity - redisCapacity) > 0.01) {
                    LogManager.getInstance().warn(
                        "Capacity mismatch: Memory=" + memoryCapacity +
                        ", Redis=" + redisCapacity);
                }
            }
        }
    }
}
```

#### テスト

```java
@Test
public void testPhase2_RedisCapacityAtomic() {
    // 複数スレッドから同時にDECRを実行
    ExecutorService executor = Executors.newFixedThreadPool(10);
    for (int i = 0; i < 100; i++) {
        executor.submit(() -> {
            RedisCapacityManager.decrementCapacity(0, 1);
        });
    }

    // 容量が正しく減少していることを確認
    assertEquals(initialCapacity - 100,
                 RedisCapacityManager.getCapacity(0, 1));
}
```

#### 成果物
- ✅ RedisCapacityManager
- ✅ アトミック操作の実装
- ✅ 同時実行テスト

#### ロールバック
- メモリベース処理が残っているので安全

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

| Phase | 期間 | 主要成果物 | リスク |
|-------|------|-----------|--------|
| **Phase 0** | 1-2日 | Docker環境、Redisson設定 | 低 |
| **Phase 1** | 3-4日 | 状態同期、モニタリング | 低 |
| **Phase 2** | 4-5日 | 容量管理のRedis化 | 中 |
| **Phase 3** | 5-7日 | ワーカープロセス | 高 |
| **Phase 4** | 3-4日 | 完全移行 | 中 |
| **Phase 5** | 2-3日 | モニタリング | 低 |
| **合計** | **18-25日** | | |

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
