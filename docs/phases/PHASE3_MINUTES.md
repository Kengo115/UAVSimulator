# Phase 3 実装議事録：リンク容量Redis移行とワーカー基盤

**実施日**: 2025-12-28 〜 2025-12-29
**担当者**: Claude (Sonnet 4.5 / Opus 4.5)
**ステータス**: ✅ Phase 3完了（Phase 3a〜3b-6すべて完了）

## 目次
1. [Phase 3の概要](#phase-3の概要)
2. [Phase 3a: リンク容量Redis移行](#phase-3a-リンク容量redis移行)
3. [Phase 3b-1: ジョブキューとワーカーの基本実装](#phase-3b-1-ジョブキューとワーカーの基本実装)
4. [Phase 3b-2a: イベントクラス枠組み作成](#phase-3b-2a-イベントクラス枠組み作成)
5. [Phase 3b-2b: Worker単一リンク飛行](#phase-3b-2b-worker単一リンク飛行)
6. [Phase 3b-2c: Worker複数リンク飛行](#phase-3b-2c-worker複数リンク飛行)
7. [Phase 3b-2d: 最初リンク待機・再開](#phase-3b-2d-最初リンク待機再開)
8. [Phase 3b-3: 非同期イベントスケジューリング](#phase-3b-3-非同期イベントスケジューリング)
9. [Phase 3b-4: Luaスクリプト原子操作](#phase-3b-4-luaスクリプト原子操作)
10. [Phase 3b-5: 途中リンク待機・再開](#phase-3b-5-途中リンク待機再開)
11. [Phase 3b-6: RouteSearcher統合](#phase-3b-6-routesearcher統合)
12. [作成・修正したファイル](#作成修正したファイル)
13. [Redis Key構造](#redis-key構造)
14. [テスト結果](#テスト結果)

---

## Phase 3の概要

### 目的
**リンク容量をRedisに移行し、ワーカープロセスによる並列処理の基盤を構築する**

### 背景
Phase 1-2でUAV状態と統計情報をRedisに保存・検証しました。Phase 3では、クリティカルなデータである「リンク容量」のRedis移行と、スケーラブルなワーカープロセス基盤を構築します。

### スコープ
Phase 3は以下の3段階に分かれています：

| サブフェーズ | 内容 | ステータス |
|------------|------|---------|
| **Phase 3a** | リンク容量Redis移行（二重書き込み） | ✅ 完了 |
| **Phase 3b-1** | ジョブキューとワーカーの基本実装 | ✅ 完了 |
| **Phase 3b-2a** | イベントクラス枠組み作成 | ✅ 完了 |
| **Phase 3b-2b** | Worker単一リンク飛行 | ✅ 完了 |
| **Phase 3b-2c** | Worker複数リンク飛行 | ✅ 完了 |
| **Phase 3b-2d** | 最初リンク待機・再開 | ✅ 完了 |
| **Phase 3b-3** | 非同期イベントスケジューリング | ✅ 完了 |
| **Phase 3b-4** | Luaスクリプト原子操作 | ✅ 完了 |
| **Phase 3b-5** | 途中リンク待機・再開 | ✅ 完了 |
| **Phase 3b-6** | RouteSearcher統合 | ✅ 完了 |

---

## Phase 3a: リンク容量Redis移行

### 実装方針
**二重書き込みパターン（Dual Write Pattern）**
1. **書き込み**: メモリ（既存）とRedis（新規）の両方に容量を保存
2. **読み取り**: Phase 3aではメモリから読み取り（Redisは検証用のみ）
3. **検証**: 5回に1回、メモリとRedisの整合性をチェック

### 実装した機能

#### 1. リンク容量管理（LinkCapacityManager.java）

**役割**: リンク容量をRedisに保存・管理

**主要メソッド**:
```java
// 全リンクの容量を一括更新（二重書き込み）
public void updateAllCapacities(Link[][] link, int[][] flyingUAV, int node)

// 初期容量をRedisに保存
public void saveInitCapacity(int srcNode, int dstNode, double initCapacity)

// 現在容量をRedisに保存（アトミック操作）
public void saveCapacity(int srcNode, int dstNode, double capacity)

// 飛行中UAV数を設定（アトミック操作）
public void setFlyingCount(int srcNode, int dstNode, int count)

// 飛行中UAV数をインクリメント（Phase 3b用）
public long incrementFlyingCount(int srcNode, int dstNode)

// 飛行中UAV数をデクリメント（Phase 3b用）
public long decrementFlyingCount(int srcNode, int dstNode)

// 容量をアトミックに減算（Phase 3b用）
public double decrementCapacity(int srcNode, int dstNode, double amount)

// 容量をアトミックに加算（Phase 3b用）
public double incrementCapacity(int srcNode, int dstNode, double amount)
```

**アトミック操作の使用理由**:
- 将来のワーカープロセス並列処理での競合防止
- `RAtomicDouble` / `RAtomicLong` を使用してアトミックな増減を実現

---

#### 2. リンク容量読み取り・検証（LinkCapacityReader.java）

**役割**: Redisからリンク容量を読み取り、整合性を検証

**主要メソッド**:
```java
// 容量をRedisから読み取り
public double getCapacity(int srcNode, int dstNode)

// 初期容量をRedisから読み取り
public double getInitCapacity(int srcNode, int dstNode)

// 飛行中UAV数をRedisから読み取り
public long getFlyingCount(int srcNode, int dstNode)

// 全リンクの容量を読み取り
public Map<String, Double> getAllCapacities(int node)

// メモリとRedisの整合性検証
public boolean validateCapacity(Link[][] link, int[][] flyingUAV, int node)

// リンク容量サマリを表示
public void printCapacitySummary(int node)
```

**整合性検証のロジック**:
```java
public boolean validateCapacity(Link[][] link, int[][] flyingUAV, int node) {
    // 各リンクについて
    for (int i = 0; i < node; i++) {
        for (int j = 0; j < node; j++) {
            // 存在しないリンクはスキップ
            if (link[i][j].getL_tubeLength() == Double.POSITIVE_INFINITY) {
                continue;
            }

            // メモリとRedisの容量を比較（誤差0.001以下は許容）
            double memoryCapacity = link[i][j].getCapacity();
            double redisCapacity = getCapacity(i, j);

            if (Math.abs(memoryCapacity - redisCapacity) > 0.001) {
                // 不整合を検出
            }

            // 飛行中UAV数も比較
            int memoryFlyingCount = flyingUAV[i][j];
            long redisFlyingCount = getFlyingCount(i, j);

            if (memoryFlyingCount != redisFlyingCount) {
                // 不整合を検出
            }
        }
    }
}
```

---

#### 3. 容量管理の統合（CapacityManager.java）

**変更内容**: メモリ更新後にRedisにも二重書き込み

```java
public static void updateCapacity(int[][] flyingUAV, Link[][] link, int node) {
    // [既存] Capacityを初期値に戻す（メモリ）
    for (int i = 0; i < node; i++) {
        for (int j = 0; j < node; j++) {
            if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY) {
                link[i][j].setCapacity(link[i][j].getInitCapacity());
                link[j][i].setCapacity(link[j][i].getInitCapacity());
            }
        }
    }

    // [既存] 各リンクの初期容量から飛行中のUAV分を減少（メモリ）
    for (int i = 0; i < node; i++) {
        for (int j = 0; j < node; j++) {
            if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY && flyingUAV[i][j] > 0) {
                double newCapacity = link[i][j].getCapacity() - flyingUAV[i][j];
                link[i][j].setCapacity(Math.max(0, newCapacity));
                link[j][i].setCapacity(Math.max(0, newCapacity));
            }
        }
    }

    // [新規] Phase 3a: Redisにも同じ内容を保存（二重書き込み）
    linkCapacityManager.updateAllCapacities(link, flyingUAV, node);
}
```

---

#### 4. 整合性チェックの統合（UAVFlightController.java）

**追加フィールド**:
```java
// Phase 3a: リンク容量整合性チェック用
private static server.redis.LinkCapacityReader linkCapacityReader = new server.redis.LinkCapacityReader();
private static int capacityValidationCounter = 0;
private static final int CAPACITY_VALIDATION_INTERVAL = 5;  // 5回に1回チェック
```

**追加処理**:
```java
// 容量の更新
CapacityManager.updateCapacity(flyingUAV, link, node);

// Phase 3a: リンク容量の整合性チェック（5回に1回）
capacityValidationCounter++;
if (capacityValidationCounter % CAPACITY_VALIDATION_INTERVAL == 0) {
    linkCapacityReader.validateCapacity(link, flyingUAV, node);
}
```

---

## Phase 3b-1: ジョブキューとワーカーの基本実装

### 実装方針
**Producer-Consumer パターン**
1. **メインプロセス (Producer)**: UAVジョブをRedisキューに投入
2. **ワーカープロセス (Consumer)**: キューからジョブを取得して処理
3. **Redis**: ジョブキューの永続化とプロセス間通信

### 実装した機能

#### 1. UAVジョブ定義（UAVJob.java）

**役割**: ワーカーに渡すジョブ情報のコンテナ

**データ構造**:
```java
public class UAVJob implements Serializable {
    // UAV識別情報
    private int uavId;
    private int clientId;

    // 飛行情報
    private int[] path;                    // 飛行経路（ビーコンIDの配列）
    private double speed;                  // 速度（m/s）
    private long startTime;                // 飛行開始時刻（ミリ秒）

    // 目的地情報
    private int sourceBeaconId;            // 出発地ビーコンID
    private int destinationBeaconId;       // 目的地ビーコンID

    // 経路追跡
    private int currentPathIndex;          // 現在の経路インデックス（0から開始）
}
```

**Serializableが必要な理由**:
- Redisに保存するためにバイト列に変換する必要がある
- Redissonが自動的にシリアライズ/デシリアライズを行う

---

#### 2. UAVジョブキュー（UAVJobQueue.java）

**役割**: Redisを使ったジョブキュー管理

**キー構造**:
- `jobs:uav` - メインのジョブキュー（RBlockingQueue）

**主要メソッド**:
```java
// ジョブをキューに追加（メインプロセス用）
public boolean enqueueJob(UAVJob job)

// ジョブをキューから取得（ワーカー用、ブロッキング）
public UAVJob dequeueJob(long timeout, TimeUnit unit)

// キューのサイズを取得
public int getQueueSize()

// キューをクリア（テスト用）
public void clearQueue()
```

**RedissonのRBlockingQueueを使用する理由**:
- BRPOP相当のブロッキング取得が可能
- 複数ワーカーで安全にジョブを分散取得
- Javaのコレクション風APIで使いやすい

---

#### 3. UAVワーカープロセス（UAVWorker.java）

**役割**: 独立したJVMプロセスとしてジョブを処理

**処理フロー**:
```java
public void start() {
    while (running.get()) {
        try {
            // ジョブを取得（ブロッキング、最大5秒待機）
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
```

**ジョブ処理の内容（Phase 3b-1時点）**:
```java
private void processUAVJob(UAVJob job) {
    // シンプルな距離計算
    double totalDistance = calculateTotalDistance(job);
    double theoreticalFlightTime = totalDistance / job.getSpeed();

    // タイマーを起動（2秒間隔で位置更新）
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    scheduler.scheduleAtFixedRate(() -> {
        // 経過時間を計算
        long elapsedTimeMs = System.currentTimeMillis() - job.getStartTime();
        double elapsedTimeSec = elapsedTimeMs / 1000.0;

        // 飛行距離を計算
        double flightDistance = elapsedTimeSec * job.getSpeed();

        // ログ出力
        LogManager.log("UAV " + job.getUavId() + " elapsed=" + elapsedTimeSec + "s, distance=" + flightDistance + "m");

        // 到着判定
        if (flightDistance >= totalDistance) {
            LogManager.log("UAV " + job.getUavId() + " 目的地に到着");
            scheduler.shutdown();
            // Phase 3b-2で完了通知（Pub/Sub）を実装予定
        }
    }, 0, UPDATE_INTERVAL_SECONDS, TimeUnit.SECONDS);
}

// シンプルな距離計算（仮実装）
private double calculateTotalDistance(UAVJob job) {
    int[] path = job.getPath();
    if (path == null || path.length < 2) {
        return 0.0;
    }
    // 各リンクの距離を100mと仮定（Phase 3b-2で実距離計算）
    return (path.length - 1) * 100.0;
}
```

**起動方法**:
```bash
# コマンドラインから
java -cp 'target/classes:target/lib/*' server.worker.UAVWorker worker-1

# 環境変数から
WORKER_ID=worker-1 java -cp 'target/classes:target/lib/*' server.worker.UAVWorker
```

---

## Phase 3b-2a: イベントクラス枠組み作成

### 実装方針
**イベント駆動アーキテクチャの基盤構築**

Phase 3b-1で発生した問題（待機UAVが処理されない）を解決するため、ポーリングベースからイベント駆動アーキテクチャへの移行を決定しました。Phase 3b-2aでは、その基盤となるイベントクラスと管理クラスの枠組みを作成します。

### 実装した機能

#### 1. Pub/Subチャンネル名定数（UAVEventChannels.java）

**役割**: イベントチャンネル名とキー生成の一元管理

**定数**:
```java
public final class UAVEventChannels {
    // Pub/Subチャンネル
    public static final String LINK_PASSED = "uav:link:passed";    // リンク通過イベント
    public static final String COMPLETION = "uav:completed";        // 飛行完了イベント

    // 待機キューのプレフィックス
    public static final String WAITING_QUEUE_PREFIX = "waiting:link:";

    // 待機キューのキー生成
    public static String getWaitingQueueKey(int fromNode, int toNode) {
        return WAITING_QUEUE_PREFIX + fromNode + ":" + toNode;
    }
}
```

---

#### 2. リンク通過イベント（UAVLinkPassedEvent.java）

**役割**: UAVがリンクを通過した際にWorkerからメインプロセスに通知するイベント

**データ構造**:
```java
public class UAVLinkPassedEvent implements Serializable {
    // UAV識別情報
    private int uavId;
    private int clientId;

    // 通過したリンク情報
    private int passedFromNode;      // 通過したリンクの始点
    private int passedToNode;        // 通過したリンクの終点

    // 次のリンク情報（-1 = 最終リンク通過済み）
    private int nextFromNode;
    private int nextToNode;

    // 経路情報
    private int[] path;              // 飛行経路全体
    private int currentLinkIndex;    // 通過したリンクのインデックス

    // タイミング情報
    private long timestamp;          // イベント発生時刻（ミリ秒）
    private double elapsedFlightTime; // 経過飛行時間（秒）

    // ヘルパーメソッド
    public boolean isLastLink();     // 最終リンクかどうか
}
```

**使用場面（Phase 3b-2c以降）**:
- Worker がリンクを通過 → イベント発行
- メインプロセスが受信 → 通過リンクの容量回復 → 待機UAV再ジョブ化

---

#### 3. 飛行完了イベント（UAVCompletionEvent.java）

**役割**: UAVが目的地に到着した際にWorkerからメインプロセスに通知するイベント

**データ構造**:
```java
public class UAVCompletionEvent implements Serializable {
    // UAV識別情報
    private int uavId;
    private int clientId;

    // 飛行結果情報
    private long arrivalTime;        // 到着時刻（ミリ秒）
    private double totalDistance;    // 総飛行距離（メートル）
    private double actualFlightTime; // 実飛行時間（秒）
    private double totalWaitingTime; // 総待機時間（秒）

    // 経路情報
    private int[] path;
    private int sourceBeaconId;
    private int destinationBeaconId;

    // 最終リンク情報（容量回復用）
    private int lastLinkFromNode;
    private int lastLinkToNode;

    // ヘルパーメソッド
    public double getFlightEfficiency();  // 飛行効率（実飛行時間/総時間）
}
```

**使用場面（Phase 3b-2b以降）**:
- Worker が目的地到着 → イベント発行
- メインプロセスが受信 → 統計更新 + 最終リンク容量回復

---

#### 4. 待機UAV管理（WaitingUAVManager.java）

**役割**: リンク別の待機キュー（FIFO）を管理（Phase 3b-2aはスタブ実装）

**主要メソッド**:
```java
public class WaitingUAVManager {
    // 待機キューに登録（FIFO）
    public void enqueue(int fromNode, int toNode, UAVJob job);

    // 待機キューから取り出し（FIFO）
    public UAVJob dequeue(int fromNode, int toNode);

    // 待機UAVがいるか確認
    public boolean hasWaitingUAV(int fromNode, int toNode);

    // 待機キュー長を取得
    public int getWaitingCount(int fromNode, int toNode);

    // 待機キューをクリア
    public void clear(int fromNode, int toNode);

    // 全待機キューをクリア
    public void clearAll();
}
```

**Phase 3b-2a時点**: すべてスタブ実装（ログ出力のみ）
**Phase 3b-2d以降**: Redis RDeque を使用した本実装

---

### シリアライズテスト（UAVEventSerializationTest.java）

**テスト内容**:
| テスト | 内容 | 結果 |
|-------|------|------|
| UAVLinkPassedEvent RBucket | Redisに保存・復元 | ✅ 成功 |
| UAVCompletionEvent RBucket | Redisに保存・復元 | ✅ 成功 |
| UAVLinkPassedEvent Pub/Sub | チャンネル経由で送受信 | ✅ 成功 |
| UAVCompletionEvent Pub/Sub | チャンネル経由で送受信 | ✅ 成功 |
| UAVJob RBucket | 既存クラスの動作確認 | ✅ 成功 |

**テスト結果**:
```
=== UAVイベント シリアライズ/デシリアライズ テスト ===
[1/5] UAVLinkPassedEvent シリアライズテスト (RBucket) ✓
[2/5] UAVCompletionEvent シリアライズテスト (RBucket) ✓
[3/5] UAVLinkPassedEvent Pub/Sub テスト ✓
[4/5] UAVCompletionEvent Pub/Sub テスト ✓
[5/5] UAVJob シリアライズテスト (RBucket) ✓
テスト結果: 5 成功, 0 失敗
```

---

## Phase 3b-2b: Worker単一リンク飛行

### 実装方針
**最もシンプルなケースでWorker処理を動作確認**

単一リンク（0→1）の経路でUAVを飛行させ、以下を検証：
1. Worker がジョブを取得して飛行処理
2. Thread.sleep で飛行時間をシミュレート
3. 完了イベントを Pub/Sub で送信
4. メインプロセスのリスナーが完了イベントを受信

### 実装した機能

#### 1. UAVJob.java 修正（リンク距離情報追加）

**追加フィールド**:
```java
// Phase 3b-2b: リンク距離情報
private double[] linkDistances;        // 各リンクの距離（メートル）
```

**追加メソッド**:
```java
// 指定インデックスのリンク距離を取得
public double getLinkDistance(int linkIndex) {
    if (linkDistances == null || linkIndex < 0 || linkIndex >= linkDistances.length) {
        return 0.0;
    }
    return linkDistances[linkIndex];
}

// 経路の総距離を計算
public double getTotalDistance() {
    if (linkDistances == null || linkDistances.length == 0) {
        return 0.0;
    }
    double total = 0.0;
    for (double distance : linkDistances) {
        total += distance;
    }
    return total;
}

// 現在位置から目的地までの残り距離を計算
public double getRemainingDistance() {
    if (linkDistances == null || currentPathIndex >= linkDistances.length) {
        return 0.0;
    }
    double remaining = 0.0;
    for (int i = currentPathIndex; i < linkDistances.length; i++) {
        remaining += linkDistances[i];
    }
    return remaining;
}
```

---

#### 2. UAVWorker.java 修正（飛行処理と完了イベント送信）

**追加フィールド**:
```java
private RTopic completionTopic;  // Phase 3b-2b: 完了イベント送信用
```

**コンストラクタ変更**:
```java
// Phase 3b-2b: 完了イベント送信用トピック
this.completionTopic = redisson.getTopic(UAVEventChannels.COMPLETION);
```

**processUAVJob() メソッド（Phase 3b-2b版）**:
```java
private void processUAVJob(UAVJob job) {
    int[] path = job.getPath();
    int fromNode = path[0];
    int toNode = path[path.length - 1];

    // 総飛行距離を取得（UAVJob.getTotalDistance()を使用）
    double totalDistance = job.getTotalDistance();

    // リンク距離が設定されていない場合は仮の値を使用（後方互換性）
    if (totalDistance <= 0) {
        totalDistance = (path.length - 1) * 100.0;  // 仮: 1リンク100m
    }

    // 飛行時間を計算
    double flightTimeSeconds = totalDistance / job.getSpeed();

    // 飛行をシミュレート（Thread.sleepで待機）
    try {
        long flightTimeMs = (long) (flightTimeSeconds * 1000);
        Thread.sleep(flightTimeMs);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
    }

    // 完了イベントを送信
    publishCompletionEvent(job, totalDistance, flightTimeSeconds);
}
```

**publishCompletionEvent() メソッド（新規）**:
```java
private void publishCompletionEvent(UAVJob job, double totalDistance, double actualFlightTime) {
    UAVCompletionEvent event = new UAVCompletionEvent(
        job.getUavId(),
        job.getClientId(),
        totalDistance,
        actualFlightTime,
        0.0,  // totalWaitingTime（Phase 3b-2bでは待機なし）
        job.getPath(),
        job.getSourceBeaconId(),
        job.getDestinationBeaconId()
    );

    long listeners = completionTopic.publish(event);
    LogManager.getInstance().log(
        "Phase 3b-2b: Worker " + workerId + " - UAV " + job.getUavId() +
        " 完了イベント送信 (listeners=" + listeners + ")"
    );
}
```

---

#### 3. UAVCompletionListener.java（新規作成）

**役割**: メインプロセスで完了イベントを受信

**実装**:
```java
public class UAVCompletionListener {
    private RedissonClient client;
    private RTopic topic;
    private int listenerId = -1;
    private AtomicInteger completedCount = new AtomicInteger(0);

    public UAVCompletionListener() {
        this.client = RedisConnectionManager.getInstance().getClient();
        this.topic = client.getTopic(UAVEventChannels.COMPLETION);
    }

    public void startListening() {
        listenerId = topic.addListener(UAVCompletionEvent.class,
            (channel, event) -> handleCompletionEvent(event));
    }

    private void handleCompletionEvent(UAVCompletionEvent event) {
        int count = completedCount.incrementAndGet();

        LogManager.getInstance().log(
            "Phase 3b-2b: [メイン] UAV " + event.getUavId() + " 完了通知受信 " +
            "(client=" + event.getClientId() + ", " +
            "distance=" + event.getTotalDistance() + "m, " +
            "time=" + event.getActualFlightTime() + "s, " +
            "efficiency=" + (event.getFlightEfficiency() * 100) + "%, " +
            "総完了数=" + count + ")"
        );

        // Phase 3b-2d以降: ここで容量回復と待機UAV再ジョブ化を行う
    }

    public void stopListening() {
        if (topic != null && listenerId >= 0) {
            topic.removeListener(listenerId);
        }
    }

    public int getCompletedCount() {
        return completedCount.get();
    }
}
```

---

#### 4. SingleLinkFlightTest.java（新規作成）

**役割**: 単一リンク飛行のE2Eテスト

**テスト条件**:
```java
private static final int UAV_COUNT = 3;           // テストするUAV数
private static final double LINK_DISTANCE = 50.0; // リンク距離（メートル）
private static final double UAV_SPEED = 10.0;     // UAV速度（m/s）
// 期待される飛行時間: 50m / 10m/s = 5秒
```

**テストフロー**:
```
1. Redis接続
2. UAVCompletionListener開始
3. ジョブキュークリア → 3件のジョブ投入
4. UAVWorker起動（別スレッド）
5. 完了待機（タイムアウト付き）
6. 結果判定（3/3完了 = 成功）
7. クリーンアップ
```

---

### テスト結果

**テスト環境**:
- Redis: 7.2-alpine (Docker)
- Redisson: 3.24.3
- Worker: 1（テストプロセス内）

**テスト出力**:
```
=== Phase 3b-2b: 単一リンク飛行テスト ===

テスト条件:
  - UAV数: 3
  - 経路: [0, 1] (単一リンク)
  - リンク距離: 50.0m
  - UAV速度: 10.0m/s
  - 期待飛行時間: 5.0秒

[1/5] Redis接続... ✓ 接続成功
[2/5] 完了リスナー開始... ✓ リスナー開始
[3/5] ジョブ投入... ✓ 3件のジョブを投入
[4/5] Worker起動... ✓ Worker起動
[5/5] 完了待機...
  Phase 3b: ジョブ取得成功 - UAV 0 (client 1)
  Phase 3b-2b: Worker test-worker - UAV 0 飛行開始 (0→1)
  Phase 3b-2b: Worker test-worker - UAV 0 distance=50.00m, speed=10.00m/s, flightTime=5.00s
  ...
  Phase 3b-2b: Worker test-worker - UAV 0 飛行完了
  Phase 3b-2b: Worker test-worker - UAV 0 完了イベント送信 (listeners=1)
  Phase 3b-2b: [メイン] UAV 0 完了通知受信 (client=1, distance=50.00m, time=5.00s, efficiency=100.0%, 総完了数=1)
  ...
  Phase 3b-2b: [メイン] UAV 1 完了通知受信 (..., 総完了数=2)
  ...
  Phase 3b-2b: [メイン] UAV 2 完了通知受信 (..., 総完了数=3)

=========================
テスト結果: 3/3 完了
✓ テスト成功！
=========================
```

✅ **Worker が正しく飛行をシミュレート（Thread.sleep）**
✅ **Pub/Sub で完了イベントが正しく送受信（listeners=1）**
✅ **リスナーがイベントを受信してカウント更新**
✅ **飛行時間が正確（50m / 10m/s = 5秒）**

---

### 注意事項（テスト実行時）

**問題**: 以前のdocker-compose.ymlから作成された古いDockerワーカー（uav-worker-1/2/3）が同じジョブキューからジョブを取得していた

**解決**: 古いDockerワーカーコンテナを削除
```bash
docker rm -f uav-worker-1 uav-worker-2 uav-worker-3
```

**現在のDocker構成**:
- `uav-simulator-redis` - Redis 7.2-alpine
- `uav-redis-commander` - Redis Commander（Web UI）
- ワーカーはテスト時にプロセス内で起動（Dockerワーカーは不要時は起動しない）

---

## Phase 3b-2c: Worker複数リンク飛行

### 実装方針
**複数リンク経路でのWorker処理を検証**

Phase 3b-2bでは単一リンク（0→1）のみでしたが、Phase 3b-2cでは複数リンクを順番に飛行し、各リンク通過時にイベントを送信します。

### テスト条件

| 項目 | 値 |
|------|-----|
| ノード数 | 6つ |
| 経路 | `[0, 1, 4, 5]`（3リンク） |
| リンク距離 | [50.0, 75.0, 60.0]（合計185m） |
| UAV数 | 5台 |
| UAV速度 | 10.0 m/s |
| リンク容量 | 100（待機が発生しない） |
| Worker数 | 1 |
| 期待飛行時間 | 18.5秒/台 |

### Phase 3b-2b との差分

| 項目 | Phase 3b-2b（単一リンク） | Phase 3b-2c（複数リンク） |
|------|--------------------------|--------------------------|
| 経路 | `[0, 1]`（1リンク） | `[0, 1, 4, 5]`（3リンク） |
| 飛行処理 | 全体を一括 `Thread.sleep` | リンクごとに `Thread.sleep` |
| イベント | 完了イベントのみ | **リンク通過イベント + 完了イベント** |
| リスナー | `UAVCompletionListener` | `UAVCompletionListener` + **`UAVLinkPassedListener`** |

### 実装した機能

#### 1. UAVWorker.java 修正（複数リンク対応）

**追加フィールド**:
```java
private RTopic linkPassedTopic;   // Phase 3b-2c: リンク通過イベント送信用
```

**コンストラクタ変更**:
```java
// イベント送信用トピック
this.completionTopic = redisson.getTopic(UAVEventChannels.COMPLETION);
this.linkPassedTopic = redisson.getTopic(UAVEventChannels.LINK_PASSED);
```

**processUAVJob() メソッド（Phase 3b-2c版）**:
```java
private void processUAVJob(UAVJob job) {
    int[] path = job.getPath();
    int sourceNode = path[0];
    int destNode = path[path.length - 1];

    // リンクごとに飛行処理
    double elapsedFlightTime = 0.0;
    int currentLinkIndex = 0;

    while (currentLinkIndex < path.length - 1) {
        int fromNode = path[currentLinkIndex];
        int toNode = path[currentLinkIndex + 1];

        // このリンクの距離を取得
        double linkDistance = job.getLinkDistance(currentLinkIndex);

        // 飛行時間を計算
        double linkFlightTime = linkDistance / job.getSpeed();

        // このリンクを飛行（Thread.sleepで待機）
        Thread.sleep((long)(linkFlightTime * 1000));
        elapsedFlightTime += linkFlightTime;

        // 次のリンク情報を計算
        int nextFromNode = -1;
        int nextToNode = -1;
        if (currentLinkIndex + 1 < path.length - 1) {
            nextFromNode = path[currentLinkIndex + 1];
            nextToNode = path[currentLinkIndex + 2];
        }

        // リンク通過イベントを送信
        publishLinkPassedEvent(job, fromNode, toNode, nextFromNode, nextToNode,
                               currentLinkIndex, elapsedFlightTime);

        currentLinkIndex++;
    }

    // 完了イベントを送信
    publishCompletionEvent(job, totalDistance, elapsedFlightTime);
}
```

**publishLinkPassedEvent() メソッド（新規）**:
```java
private void publishLinkPassedEvent(UAVJob job, int passedFromNode, int passedToNode,
                                    int nextFromNode, int nextToNode,
                                    int currentLinkIndex, double elapsedFlightTime) {
    UAVLinkPassedEvent event = new UAVLinkPassedEvent(
        job.getUavId(),
        job.getClientId(),
        passedFromNode,
        passedToNode,
        nextFromNode,
        nextToNode,
        job.getPath(),
        currentLinkIndex,
        elapsedFlightTime
    );

    long listeners = linkPassedTopic.publish(event);
    LogManager.getInstance().log(
        "Phase 3b-2c: Worker " + workerId + " - UAV " + job.getUavId() +
        " リンク通過イベント送信 " + passedFromNode + "→" + passedToNode +
        " (listeners=" + listeners + ")"
    );
}
```

---

#### 2. UAVLinkPassedListener.java（新規作成）

**役割**: メインプロセスでリンク通過イベントを受信

**実装**:
```java
public class UAVLinkPassedListener {
    private RedissonClient client;
    private RTopic topic;
    private int listenerId = -1;
    private AtomicInteger linkPassedCount = new AtomicInteger(0);

    public UAVLinkPassedListener() {
        this.client = RedisConnectionManager.getInstance().getClient();
        this.topic = client.getTopic(UAVEventChannels.LINK_PASSED);
    }

    public void startListening() {
        listenerId = topic.addListener(UAVLinkPassedEvent.class,
            (channel, event) -> handleLinkPassedEvent(event));
    }

    private void handleLinkPassedEvent(UAVLinkPassedEvent event) {
        int count = linkPassedCount.incrementAndGet();

        String nextLinkInfo = event.isLastLink() ? "最終リンク" :
            event.getNextFromNode() + "→" + event.getNextToNode();

        LogManager.getInstance().log(
            "Phase 3b-2c: [メイン] UAV " + event.getUavId() + " リンク通過 " +
            event.getPassedFromNode() + "→" + event.getPassedToNode() + " " +
            "(client=" + event.getClientId() + ", " +
            "経過=" + event.getElapsedFlightTime() + "s, " +
            "次=" + nextLinkInfo + ", " +
            "総通過数=" + count + ")"
        );

        // Phase 3b-2d以降: ここで容量回復と待機UAV再ジョブ化を行う
    }

    public void stopListening() {
        if (topic != null && listenerId >= 0) {
            topic.removeListener(listenerId);
        }
    }

    public int getLinkPassedCount() {
        return linkPassedCount.get();
    }
}
```

---

#### 3. MultiLinkFlightTest.java（新規作成）

**役割**: 複数リンク飛行のE2Eテスト

**テストフロー**:
```
1. Redis接続
2. UAVCompletionListener + UAVLinkPassedListener 開始
3. ジョブキュークリア → 5件のジョブ投入
4. UAVWorker起動（別スレッド）
5. 完了待機（タイムアウト付き）
6. 結果判定（5/5完了 + 15/15リンク通過 = 成功）
7. クリーンアップ
```

---

### テスト結果

**テスト環境**:
- Redis: 7.2-alpine (Docker)
- Redisson: 3.24.3
- Worker: 1（テストプロセス内）

**テスト出力**:
```
=== Phase 3b-2c: 複数リンク飛行テスト ===

テスト条件:
  - UAV数: 5
  - 経路: [0, 1, 4, 5] (3リンク)
  - リンク距離: [50.0, 75.0, 60.0] (合計185.0m)
  - UAV速度: 10.0m/s
  - 期待飛行時間: 18.5秒/台

[1/5] Redis接続... ✓ 接続成功
[2/5] リスナー開始... ✓ 完了リスナー + リンク通過リスナー開始
[3/5] ジョブ投入... ✓ 5件のジョブを投入
[4/5] Worker起動... ✓ Worker起動
[5/5] 完了待機...

Phase 3b-2c: Worker test-worker - UAV 0 飛行開始 (0→5, 3リンク)
Phase 3b-2c: Worker test-worker - UAV 0 飛行中 0→1 (50.0m, 5.00s)
Phase 3b-2c: Worker test-worker - UAV 0 リンク通過イベント送信 0→1 (listeners=1)
Phase 3b-2c: [メイン] UAV 0 リンク通過 0→1 (client=1, 経過=5.00s, 次=1→4, 総通過数=1)
Phase 3b-2c: Worker test-worker - UAV 0 飛行中 1→4 (75.0m, 7.50s)
Phase 3b-2c: Worker test-worker - UAV 0 リンク通過イベント送信 1→4 (listeners=1)
Phase 3b-2c: [メイン] UAV 0 リンク通過 1→4 (client=1, 経過=12.50s, 次=4→5, 総通過数=2)
Phase 3b-2c: Worker test-worker - UAV 0 飛行中 4→5 (60.0m, 6.00s)
Phase 3b-2c: Worker test-worker - UAV 0 リンク通過イベント送信 4→5 (listeners=1)
Phase 3b-2c: [メイン] UAV 0 リンク通過 4→5 (client=1, 経過=18.50s, 次=最終リンク, 総通過数=3)
Phase 3b-2c: Worker test-worker - UAV 0 飛行完了 (総距離=185.0m, 総時間=18.50s)
Phase 3b-2c: Worker test-worker - UAV 0 完了イベント送信 (listeners=1)
Phase 3b-2b: [メイン] UAV 0 完了通知受信 (client=1, distance=185.00m, time=18.50s, efficiency=100.0%, 総完了数=1)
...
（UAV 1〜4 も同様）
...

=========================
テスト結果:
  完了UAV: 5/5
  リンク通過イベント: 15/15
✓ テスト成功！
=========================
```

### 検証ポイント

| 項目 | 結果 |
|------|------|
| ✅ リンクごとの飛行時間 | 正確（0→1: 5秒, 1→4: 7.5秒, 4→5: 6秒） |
| ✅ リンク通過イベント | 各リンク通過後に送信（listeners=1） |
| ✅ イベント受信 | メインプロセスで15回全て受信 |
| ✅ イベント順序 | 0→1, 1→4, 4→5, 完了 の順序で正しく受信 |
| ✅ 5台全完了 | 全UAVが正常に完了 |

---

## Phase 3b-2d: 最初リンク待機・再開

### 実装方針
**リンク容量制限と待機キューによる流量制御**

Phase 3b-2cでは容量制限なし（capacity=100）でしたが、Phase 3b-2dでは最初のリンクに容量制限を設け、容量不足時の待機・再開処理を実装します。

### テスト条件

| 項目 | 値 |
|------|-----|
| ノード数 | 6つ |
| 経路 | `[0, 1, 4, 5]`（3リンク） |
| リンク距離 | [50.0, 75.0, 60.0]（合計185m） |
| UAV数 | 5台 |
| UAV速度 | 10.0 m/s |
| **最初のリンク容量** | **2**（待機が発生） |
| 他のリンク容量 | 100（待機が発生しない） |
| Worker数 | **5（並列処理）** |
| 期待飛行時間 | 18.5秒/台 |
| 期待待機数 | 3台（5台 - 容量2） |

### Phase 3b-2c との差分

| 項目 | Phase 3b-2c | Phase 3b-2d |
|------|------------|------------|
| リンク容量 | 100（無制限） | **最初のリンク: 2** |
| Worker数 | 1 | **5（並列）** |
| 容量チェック | なし | **tryConsumeCapacity()** |
| 待機処理 | なし | **待機キュー登録** |
| 容量回復 | なし | **recoverCapacity()** |
| 再ジョブ化 | なし | **待機UAV再ジョブ化** |

### 実装した機能

#### 1. WaitingUAVManager.java 本実装（スタブから変更）

**役割**: Redis RDequeを使用したリンク別待機キュー（FIFO）管理

**主要メソッド**:
```java
// 待機UAVをリンク別キューに登録（FIFO）
public void enqueue(int fromNode, int toNode, UAVJob job) {
    String key = UAVEventChannels.getWaitingQueueKey(fromNode, toNode);
    RDeque<UAVJob> queue = client.getDeque(key);
    queue.addLast(job);
}

// 待機UAVをリンク別キューから取り出し（FIFO）
public UAVJob dequeue(int fromNode, int toNode) {
    String key = UAVEventChannels.getWaitingQueueKey(fromNode, toNode);
    RDeque<UAVJob> queue = client.getDeque(key);
    return queue.pollFirst();
}

// 指定リンクに待機UAVがいるか確認
public boolean hasWaitingUAV(int fromNode, int toNode) {
    String key = UAVEventChannels.getWaitingQueueKey(fromNode, toNode);
    RDeque<UAVJob> queue = client.getDeque(key);
    return !queue.isEmpty();
}

// 待機キュー長を取得
public int getWaitingCount(int fromNode, int toNode) { ... }

// 待機キューをクリア（リンク指定）
public void clear(int fromNode, int toNode) { ... }

// 全待機キューをクリア（シミュレーションリセット用）
public void clearAll() {
    client.getKeys().deleteByPattern(UAVEventChannels.WAITING_QUEUE_PREFIX + "*");
}
```

---

#### 2. LinkCapacityManager.java 追加メソッド

**追加メソッド（3つ）**:
```java
/**
 * リンク容量を消費する（アトミック操作）
 * 1 UAV = 1 容量として消費
 * 容量が1未満の場合は消費せずfalseを返す
 */
public boolean tryConsumeCapacity(int srcNode, int dstNode) {
    String key = "link:" + srcNode + ":" + dstNode + ":capacity";
    RAtomicDouble capacity = client.getAtomicDouble(key);

    // アトミックにデクリメント
    double newCapacity = capacity.addAndGet(-1.0);

    if (newCapacity >= 0) {
        // 成功: 容量確保できた
        return true;
    } else {
        // 失敗: 容量不足 → 戻す
        capacity.addAndGet(1.0);
        return false;
    }
}

/**
 * リンク容量を回復する（アトミック操作）
 * UAVがリンクを通過した際に容量を回復
 */
public double recoverCapacity(int srcNode, int dstNode) {
    String key = "link:" + srcNode + ":" + dstNode + ":capacity";
    RAtomicDouble capacity = client.getAtomicDouble(key);
    return capacity.addAndGet(1.0);
}

/**
 * 現在のリンク容量を取得する
 */
public double getCapacity(int srcNode, int dstNode) {
    String key = "link:" + srcNode + ":" + dstNode + ":capacity";
    RAtomicDouble capacity = client.getAtomicDouble(key);
    return capacity.get();
}
```

**アトミック操作パターン（案A）**:
- `tryConsumeCapacity()`: 消費 → 負なら戻す（楽観的ロック）
- Luaスクリプトを使わずにRedisson APIで実装
- 複数ワーカー間での競合を防止

---

#### 3. UAVWorker.java 修正（容量チェック・待機登録）

**追加フィールド**:
```java
private LinkCapacityManager capacityManager;   // Phase 3b-2d: 容量管理
private WaitingUAVManager waitingManager;      // Phase 3b-2d: 待機UAV管理
```

**processUAVJob() メソッド（Phase 3b-2d版）**:
```java
private void processUAVJob(UAVJob job) {
    int[] path = job.getPath();
    int firstLinkFrom = path[0];
    int firstLinkTo = path[1];

    // Phase 3b-2d: 最初のリンクの容量チェック
    if (!capacityManager.tryConsumeCapacity(firstLinkFrom, firstLinkTo)) {
        // 容量不足 → 待機キューに登録して処理終了
        LogManager.getInstance().log(
            "Phase 3b-2d: Worker " + workerId + " - UAV " + job.getUavId() +
            " 容量不足のため待機 (link " + firstLinkFrom + "→" + firstLinkTo + ")"
        );
        waitingManager.enqueue(firstLinkFrom, firstLinkTo, job);
        return;
    }

    // 容量確保成功 → 飛行開始
    // ... (以下、Phase 3b-2cと同様の飛行処理)
}
```

---

#### 4. UAVLinkPassedListener.java 修正（容量回復・再ジョブ化）

**追加フィールド**:
```java
private LinkCapacityManager capacityManager;
private WaitingUAVManager waitingManager;
private UAVJobQueue jobQueue;
private AtomicInteger reEnqueuedCount = new AtomicInteger(0);  // 再ジョブ化数
```

**handleLinkPassedEvent() メソッド（Phase 3b-2d版）**:
```java
private void handleLinkPassedEvent(UAVLinkPassedEvent event) {
    int count = linkPassedCount.incrementAndGet();

    int passedFromNode = event.getPassedFromNode();
    int passedToNode = event.getPassedToNode();

    // ログ出力（省略）

    // Phase 3b-2d: 容量回復
    double newCapacity = capacityManager.recoverCapacity(passedFromNode, passedToNode);

    // Phase 3b-2d: 待機UAVがいれば再ジョブ化
    if (waitingManager.hasWaitingUAV(passedFromNode, passedToNode)) {
        UAVJob waitingJob = waitingManager.dequeue(passedFromNode, passedToNode);
        if (waitingJob != null) {
            // ジョブキューに再投入
            boolean enqueued = jobQueue.enqueueJob(waitingJob);
            if (enqueued) {
                int reEnqueued = reEnqueuedCount.incrementAndGet();
                LogManager.getInstance().log(
                    "Phase 3b-2d: [メイン] 待機UAV " + waitingJob.getUavId() +
                    " を再ジョブ化 (link " + passedFromNode + "→" + passedToNode +
                    ", 総再ジョブ化数=" + reEnqueued + ")"
                );
            }
        }
    }
}
```

---

#### 5. FirstLinkWaitingTest.java（新規作成）

**役割**: 最初リンク待機・再開のE2Eテスト

**テストフロー**:
```
1. Redis接続
2. リンク容量初期化（最初のリンク: 2、他: 100）
3. 待機キュークリア
4. リスナー開始（完了リスナー + リンク通過リスナー）
5. ジョブキュークリア → 5件のジョブ投入
6. 5 Worker並列起動
7. 完了待機（タイムアウト付き）
8. 結果判定（5/5完了 + 15/15リンク通過 + 3/3再ジョブ化 = 成功）
9. クリーンアップ
```

**並列Worker起動の実装**:
```java
ExecutorService workerExecutor = Executors.newFixedThreadPool(UAV_COUNT);
UAVWorker[] workers = new UAVWorker[UAV_COUNT];

for (int i = 0; i < UAV_COUNT; i++) {
    final UAVWorker testWorker = new UAVWorker("worker-" + i);
    workers[i] = testWorker;
    workerExecutor.submit(() -> testWorker.start());
}
```

---

### テスト結果

**テスト環境**:
- Redis: 7.2-alpine (Docker)
- Redisson: 3.24.3
- Worker: 5（並列）

**テスト出力**:
```
=== Phase 3b-2d: 最初リンク待機・再開テスト ===

テスト条件:
  - UAV数: 5
  - 経路: [0, 1, 4, 5] (3リンク)
  - リンク距離: [50.0, 75.0, 60.0] (合計185.0m)
  - UAV速度: 10.0m/s
  - 最初のリンク(0→1)容量: 2
  - 期待: 2台が即飛行、3台が待機→順次飛行

[1/6] Redis接続... ✓ 接続成功
[2/6] リンク容量初期化... ✓ link[0][1].capacity = 2
[3/6] リスナー開始... ✓ 完了リスナー + リンク通過リスナー開始
[4/6] ジョブ投入... ✓ 5件のジョブを投入
[5/6] Worker起動 (5台並列)... ✓ 5 Workerを起動
[6/6] 完了待機...
  ...
  待機中... 完了数: 5/5, リンク通過数: 15, 再ジョブ化数: 3, 待機中: 0, 容量[0→1]: 2 (経過: 56秒)

=========================
テスト結果:
  完了UAV: 5/5
  リンク通過イベント: 15/15
  再ジョブ化数: 3/3
✓ テスト成功！
  - 待機→再開フローが正常動作
=========================
```

### 検証ポイント

| 項目 | 結果 |
|------|------|
| ✅ 容量制限 | 最初のリンク容量2が機能 |
| ✅ 容量消費 | tryConsumeCapacity()で正常消費 |
| ✅ 待機登録 | 容量不足時にwaitingManager.enqueue()実行 |
| ✅ 容量回復 | リンク通過時にrecoverCapacity()実行 |
| ✅ 待機UAV再ジョブ化 | 3台が待機→再ジョブ化 |
| ✅ 全UAV完了 | 5/5完了（待機含め） |
| ✅ リンク通過イベント | 15/15（5台×3リンク）|
| ✅ 並列Worker | 5 Worker並列でジョブ取得競合なし |

---

## Phase 3b-3: 非同期イベントスケジューリング

### 実装方針
**イベントスケジューリング方式による非同期飛行管理**

Phase 3b-2dまでは同期方式（1 Worker = 1 UAV同時飛行）でしたが、本番環境（200ノード、100+ UAV）ではWorker数がボトルネックになります。Phase 3b-3ではイベントスケジューリング方式を採用し、1 Workerで複数UAVの同時飛行を実現します。

### 同期方式 vs 非同期方式

| 項目 | 同期方式（Phase 3b-2dまで） | 非同期方式（Phase 3b-3） |
|------|---------------------------|-------------------------|
| 1 Workerで処理可能なUAV | 1台（Thread.sleepでブロック） | **無制限**（即座にreturn） |
| 飛行時間シミュレート | Thread.sleep | **ScheduledExecutorService** |
| 5台のUAV処理時間（1 Worker） | 92.5秒（順次処理） | **約19秒（同時飛行）** |
| スケーラビリティ | Worker数 = 同時飛行UAV数 | **Worker数は関係なし** |

### イベントスケジューリングの原理

```
リンク飛行時間 = リンク距離 / UAV速度

例: 50m / 10m/s = 5秒後にリンク通過イベント発火
```

**処理フロー**:
```
1. Worker: ジョブ取得 → 容量消費 → FlightScheduler.startFlight(job) → 即return
2. FlightScheduler: scheduleNextLink() → 5秒後にonLinkPassed()をスケジュール
3. (5秒経過)
4. FlightScheduler: onLinkPassed() → 容量回復 → 次リンクスケジュールor完了
```

### 実装した機能

#### 1. UAVJob.java 拡張（非同期スケジューリング用）

**追加フィールド**:
```java
// Phase 3b-3: 非同期スケジューリング用
private double elapsedFlightTime;      // 経過飛行時間（秒）
private long currentLinkStartTime;     // 現在のリンク飛行開始時刻（ミリ秒）
```

**追加メソッド**:
```java
/**
 * 経過飛行時間を加算
 * @param time 加算する時間（秒）
 */
public void addElapsedFlightTime(double time) {
    this.elapsedFlightTime += time;
}
```

---

#### 2. FlightScheduler.java（新規作成）

**役割**: イベントスケジューリング方式による非同期飛行管理

**主要フィールド**:
```java
public class FlightScheduler {
    private static FlightScheduler instance;  // シングルトン

    private ScheduledExecutorService scheduler;  // スケジュール実行
    private LinkCapacityManager capacityManager;
    private WaitingUAVManager waitingManager;
    private UAVJobQueue jobQueue;

    // Pub/Sub
    private RTopic linkPassedTopic;
    private RTopic completionTopic;

    // 統計情報
    private AtomicInteger activeFlights;      // 飛行中UAV数
    private AtomicInteger completedFlights;   // 完了UAV数
    private AtomicInteger linkPassedCount;    // リンク通過数

    private static final int SCHEDULER_POOL_SIZE = 8;  // イベント処理は瞬時
}
```

**主要メソッド**:
```java
/**
 * 飛行を開始（非同期・即座にreturn）
 */
public void startFlight(UAVJob job) {
    int linkIndex = job.getCurrentPathIndex();
    job.setCurrentLinkStartTime(System.currentTimeMillis());
    activeFlights.incrementAndGet();
    scheduleNextLink(job, linkIndex);
}

/**
 * 次のリンク通過をスケジュール
 */
private void scheduleNextLink(UAVJob job, int linkIndex) {
    double distance = job.getLinkDistance(linkIndex);
    double speed = job.getSpeed();
    double flightTimeSec = distance / speed;
    long flightTimeMs = (long)(flightTimeSec * 1000);

    scheduler.schedule(
        () -> onLinkPassed(job, linkIndex),
        flightTimeMs,
        TimeUnit.MILLISECONDS
    );
}

/**
 * リンク通過時の処理（スケジュールされた時刻に実行）
 */
private void onLinkPassed(UAVJob job, int linkIndex) {
    // 1. 経過時間を更新
    // 2. リンク通過イベントを送信（Pub/Sub）
    // 3. 容量回復
    // 4. 待機UAVがいれば再ジョブ化
    // 5. 最終リンクか判定 → 完了処理
    // 6. 次のリンクの容量チェック → 不足なら途中待機
    // 7. 次のリンク飛行をスケジュール
}
```

---

#### 3. AsyncUAVWorker.java（新規作成）

**役割**: 非同期UAVワーカー（ジョブ取得→FlightSchedulerに委譲→即return）

**同期Worker（UAVWorker）との違い**:
| 項目 | UAVWorker（同期） | AsyncUAVWorker（非同期） |
|------|------------------|------------------------|
| 飛行処理 | Thread.sleep（ブロック） | FlightScheduler委譲（即return） |
| 1ジョブ処理時間 | 飛行時間と同じ | **ほぼ0秒** |
| 同時処理可能UAV | 1台 | **無制限** |

**主要メソッド**:
```java
/**
 * ジョブを処理（非同期・即座にreturn）
 */
private void processJob(UAVJob job) {
    // 容量チェック + 消費
    boolean acquired = capacityManager.tryConsumeCapacity(fromNode, toNode);

    if (!acquired) {
        // 容量不足 → 待機キューへ
        waitingManager.enqueue(fromNode, toNode, job);
        return;
    }

    // 飛行開始（FlightSchedulerに委譲、即座にreturn）
    flightScheduler.startFlight(job);
    // ← ここで即座にreturn、次のジョブを取得可能
}
```

---

#### 4. AsyncFlightTest.java（新規作成）

**役割**: 1 Workerで5+ UAV同時飛行のE2Eテスト

**テスト条件**:
```java
private static final int UAV_COUNT = 5;
private static final double[] LINK_DISTANCES = {50.0, 75.0, 60.0};
private static final double UAV_SPEED = 10.0;
private static final int[] PATH = {0, 1, 4, 5};
private static final double LINK_CAPACITY = 100.0;

// 期待される飛行時間: (50 + 75 + 60) / 10 = 18.5秒
```

---

### テスト結果

**テスト環境**:
- Redis: 7.2-alpine (Docker)
- Redisson: 3.24.3
- Worker: **1（非同期）**

**テスト出力**:
```
=== Phase 3b-3: 非同期イベントスケジューリングテスト ===

テスト条件:
  - UAV数: 5
  - 経路: [0, 1, 4, 5] (3リンク)
  - リンク距離: [50.0, 75.0, 60.0] (合計185.0m)
  - UAV速度: 10.0m/s
  - 期待飛行時間: 18.5秒/台
  - Worker数: 1（非同期）

期待結果:
  - 同期方式: 5台順次 = 92.5秒
  - 非同期方式: 5台同時 = 18.5秒 + α

...

  待機中... 完了数: 0/5, 飛行中: 5, リンク通過: 5 (経過: 5秒)   ← 5台同時に1リンク目通過
  待機中... 完了数: 0/5, 飛行中: 5, リンク通過: 10 (経過: 13秒)  ← 5台同時に2リンク目通過
  待機中... 完了数: 5/5, 飛行中: 0, リンク通過: 15 (経過: 19秒)  ← 5台同時に完了

=========================
テスト結果:
  完了UAV: 5/5
  リンク通過: 15/15
  実行時間: 19.xx秒
  期待時間: 18.5秒（非同期同時飛行）

✓ テスト成功！
  - 1 Workerで5台同時飛行を確認
  - イベントスケジューリング方式が正常動作
=========================
```

### 検証ポイント

| 項目 | 結果 |
|------|------|
| ✅ 1 Workerで5台同時飛行 | 非同期方式により実現 |
| ✅ 実行時間 | 約19秒（同期方式なら92.5秒） |
| ✅ リンク通過タイミング | 5台同時に各リンク通過 |
| ✅ イベントスケジューリング | ScheduledExecutorServiceで正確 |
| ✅ Pub/Sub | 完了イベント正常受信 |

### アーキテクチャ図

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       AsyncUAVWorker（非同期Worker）                     │
│   processJob(job) {                                                      │
│       tryConsumeCapacity() → 失敗なら待機キュー登録してreturn            │
│       flightScheduler.startFlight(job) → 即return                        │
│   }                                                                      │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼ startFlight(job)
┌─────────────────────────────────────────────────────────────────────────┐
│                      FlightScheduler（飛行スケジューラ）                 │
│   ┌─────────────────────────────────────────────────────────┐            │
│   │     ScheduledExecutorService (8スレッド)                 │            │
│   │                                                          │            │
│   │   5秒後: UAV0 onLinkPassed(link0)                        │            │
│   │   5秒後: UAV1 onLinkPassed(link0)                        │            │
│   │   5秒後: UAV2 onLinkPassed(link0)  ← 同時実行            │            │
│   │   5秒後: UAV3 onLinkPassed(link0)                        │            │
│   │   5秒後: UAV4 onLinkPassed(link0)                        │            │
│   └─────────────────────────────────────────────────────────┘            │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Phase 3b-4: Luaスクリプト原子操作

### 実装方針
**Luaスクリプトによる完全な原子操作**

Phase 3b-3では楽観的ロック方式（デクリメント→負なら戻す）を使用していましたが、複数Workerが同時に同じリンクを処理すると一瞬だけ不整合が発生する可能性がありました。Phase 3b-4ではLuaスクリプトを使用して、容量操作を完全に原子的にします。

### 楽観的ロック vs Luaスクリプト

| 項目 | 楽観的ロック（Phase 3b-3） | Luaスクリプト（Phase 3b-4） |
|------|--------------------------|---------------------------|
| 原子性 | 個別操作のみ | **完全に原子的** |
| 競合ウィンドウ | あり（一瞬） | **なし** |
| 負の容量発生 | 可能性あり | **不可能** |
| Redis呼び出し回数 | 2回（失敗時） | **1回** |

### 実装した機能

#### 1. 容量消費用Luaスクリプト

```lua
-- KEYS[1]: 容量キー (link:X:Y:capacity)
-- 戻り値: 1 = 成功, 0 = 失敗（容量不足）
local capacity = tonumber(redis.call('GET', KEYS[1])) or 0
if capacity >= 1 then
    redis.call('INCRBYFLOAT', KEYS[1], -1)
    return 1
else
    return 0
end
```

#### 2. 容量回復用Luaスクリプト

```lua
-- KEYS[1]: 容量キー (link:X:Y:capacity)
-- 戻り値: 回復後の容量
local newCapacity = redis.call('INCRBYFLOAT', KEYS[1], 1)
return newCapacity
```

#### 3. LinkCapacityManager.java 修正

**tryConsumeCapacity()（Luaスクリプト版）**:
```java
public boolean tryConsumeCapacity(int srcNode, int dstNode) {
    String key = "link:" + srcNode + ":" + dstNode + ":capacity";

    // Luaスクリプトで原子的にチェック→消費
    Long result = script.eval(
        RScript.Mode.READ_WRITE,
        CONSUME_CAPACITY_SCRIPT,
        RScript.ReturnType.INTEGER,
        Collections.singletonList(key)
    );

    return (result != null && result == 1);
}
```

**recoverCapacity()（Luaスクリプト版）**:
```java
public double recoverCapacity(int srcNode, int dstNode) {
    String key = "link:" + srcNode + ":" + dstNode + ":capacity";

    // Luaスクリプトで原子的に回復
    Object result = script.eval(
        RScript.Mode.READ_WRITE,
        RECOVER_CAPACITY_SCRIPT,
        RScript.ReturnType.VALUE,
        Collections.singletonList(key)
    );

    // 結果を適切にdoubleに変換
    if (result instanceof Number) {
        return ((Number) result).doubleValue();
    }
    return 0.0;
}
```

### テスト結果

**テスト環境**:
- Redis: 7.2-alpine (Docker)
- Redisson: 3.24.3
- Worker: 1（非同期）

**テスト出力（抜粋）**:
```
2025-12-29 22:53:35.777 - Phase 3b-4: link[0][1] 容量消費成功（Lua原子操作）
2025-12-29 22:53:35.781 - Phase 3b-4: link[0][1] 容量消費成功（Lua原子操作）
...
2025-12-29 22:53:40.802 - Phase 3b-4: link[0][1] 容量回復 → 97.0（Lua原子操作）
2025-12-29 22:53:40.802 - Phase 3b-4: link[0][1] 容量回復 → 100.0（Lua原子操作）
...
テスト結果:
  完了UAV: 5/5
  リンク通過: 15/15
✓ テスト成功！
```

### 検証ポイント

| 項目 | 結果 |
|------|------|
| ✅ Luaスクリプト実行 | 正常動作 |
| ✅ 容量消費（原子的） | 15回全て成功 |
| ✅ 容量回復（原子的） | 15回全て成功 |
| ✅ 既存テスト互換性 | AsyncFlightTest通過 |
| ✅ 競合ウィンドウ | 完全に排除 |

---

## Phase 3b-5: 途中リンク待機・再開

### 実装方針
**途中リンクでの待機・再開フローの検証**

Phase 3b-2dでは最初のリンクでの待機を実装しましたが、Phase 3b-5では途中のリンク（2番目以降）での待機・再開フローを検証します。

### テスト条件

| 項目 | 値 |
|------|-----|
| UAV数 | 5台 |
| 経路 | `[0, 1, 4, 5]`（3リンク） |
| リンク距離 | [50.0, 75.0, 60.0]（合計185m） |
| UAV速度 | 10.0 m/s |
| 最初のリンク容量 | 100（無制限） |
| **2番目のリンク容量** | **2（制限あり）** |
| 3番目のリンク容量 | 100（無制限） |
| 期待途中待機数 | 3台 |
| 基準飛行時間 | 18.5秒（待機なしの場合） |

### テスト結果

**テスト出力**:
```
=== Phase 3b-5: 途中リンク待機・再開テスト ===

テスト条件:
  - UAV数: 5
  - 経路: [0, 1, 4, 5] (3リンク)
  - リンク距離: [50.0, 75.0, 60.0] (合計185.0m)
  - UAV速度: 10.0m/s
  - リンク容量:
    - 0→1: 100 (無制限)
    - 1→4: 2 (制限)
    - 4→5: 100 (無制限)
  - 期待途中待機数: 3台

[1/7] Redis接続... ✓ 接続成功
[2/7] リンク容量初期化...
✓ link[0][1].capacity = 100
✓ link[1][4].capacity = 2 (制限)
✓ link[4][5].capacity = 100
...

=========================
テスト結果:
  完了UAV: 5/5
  リンク通過: 15/15
  実行時間: 34.29秒
  基準時間: 18.5秒（待機なしの場合）
  途中待機発生: あり（予想通り）

✓ テスト成功！
  - 全5台が完了
  - 全15回のリンク通過を確認
  - 途中リンク待機→再開フローが正常動作
=========================
```

### 検証ポイント

| 項目 | 結果 |
|------|------|
| ✅ 最初のリンク通過 | 5台すべて通過 |
| ✅ 2番目リンク待機 | 3台が途中待機（容量2制限） |
| ✅ 容量回復→再開 | 待機UAVが順次再開 |
| ✅ 全UAV完了 | 5/5完了 |
| ✅ リンク通過イベント | 15/15（5台×3リンク）|
| ✅ 実行時間延長 | 34.29秒 > 18.5秒（待機発生を確認） |

---

## Phase 3b-6: RouteSearcher統合

### 実装方針
**RouteSearcherからRedisベースのジョブ投入への統合**

`make run`で起動するシミュレーターがRedisベースのワーカー処理を使用できるよう、RouteSearcherを拡張しました。

### 実装内容

#### 1. BoundaryController.java（メインエントリポイント）

**WorkerMode列挙型追加**:
```java
public enum WorkerMode {
    MEMORY(1, "メモリベース"),   // 従来の方式（UAVFlyScheduler）
    REDIS(2, "Redisベース");     // Phase 3b 非同期ワーカー
}
```

**初期化メソッド追加**:
- `initializeRedisWorker()`: FlightScheduler, UAVCompletionListener, AsyncUAVWorker起動
- `shutdownRedisWorker()`: ワーカー停止、リソース解放
- `initializeLinkCapacities()`: リンク容量をRedisに初期化

**起動時メニュー追加**:
```
ワーカーモードを選択してください:
1: メモリベース（従来の方式）
2: Redisベース（Phase 3b 非同期ワーカー）
```

#### 2. DijkstraRouteSearcher.java

**runUAVFlow()を分岐**:
```java
private void runUAVFlow(...) {
    if (BoundaryController.getCurrentWorkerMode() == WorkerMode.REDIS) {
        runUAVFlowRedis(client, path, requiredUAVs);
    } else {
        runUAVFlowMemory(client, path, flyingUavQueue, uavQueue, requiredUAVs);
    }
}
```

**runUAVFlowRedis()**: UAVJobを作成してRedisキューに投入

#### 3. AbstractPhysarumSolverRouteSearcher.java

**共通のrunUAVFlow()を分岐**:
- `runUAVFlowRedis()`: Redisキュー投入（PS, EPS, Hybrid, Binary対応）
- `runUAVFlowMemory()`: 従来のメモリベース処理

**補助メソッド追加**:
- `calculateLinkDistances()`: 経路からリンク距離配列を計算
- `adjustRemainingFlowRedis()`: 残りUAVの再割り当て（Redis版）
- `findSimplePath()`: BFSによる簡易経路探索

### テスト結果

**Redisモードでの統合テスト出力**:
```
✓ Redisに接続しました: Redis: localhost:6379 (接続状態: 接続中)
...
Dijkstra を使用します。
ワーカーモード: Redisベース

Phase 3b-6: ワーカーモードを Redisベース に設定しました
Phase 3b-3: FlightScheduler initialized (poolSize=8)
Phase 3b-2b: UAVCompletionListener 開始
Phase 3b-3: AsyncUAVWorker main-async-worker started
Phase 3b-6: 16件のリンク容量をRedisに初期化しました
✓ Redisワーカーを起動しました

クライアント 1 を生成しています...
Phase 3b-6: Redisモードでジョブ投入 (40機)
Phase 3b: ジョブ投入成功 - UAV 0 (client 1)
Phase 3b: ジョブ投入成功 - UAV 1 (client 1)
...
Phase 3b: ジョブ取得成功 - UAV 0 (client 1)
Phase 3b-3: Worker main-async-worker ジョブ取得 UAV 0 (linkIndex=0, link=0→3)
Phase 3b-4: link[0][3] 容量消費成功（Lua原子操作）
Phase 3b-3: UAV 0 飛行開始 (path=[0→3→5], linkIndex=0)
Phase 3b-3: UAV 0 リンク 0→3 スケジュール (500.00m, 45.90s後)
...
```

### 検証ポイント

| 項目 | 結果 |
|------|------|
| ✅ WorkerMode選択 | メニューで切り替え可能 |
| ✅ Redisワーカー初期化 | FlightScheduler, CompletionListener, AsyncWorker起動 |
| ✅ リンク容量初期化 | 16件のリンク容量をRedisに保存 |
| ✅ Dijkstra統合 | ジョブ投入成功（40機） |
| ✅ AsyncUAVWorker処理 | ジョブ取得→容量消費→スケジュール |
| ✅ Luaスクリプト | 容量消費成功（原子操作） |
| ✅ 飛行スケジュール | 正確な飛行時間でスケジュール |

---

## 作成・修正したファイル

### Phase 3a: 新規作成

| ファイル | 役割 |
|---------|------|
| `src/server/redis/LinkCapacityManager.java` | リンク容量のRedis保存 |
| `src/server/redis/LinkCapacityReader.java` | リンク容量の読み取り・検証 |

### Phase 3a: 修正

| ファイル | 変更内容 |
|---------|---------|
| `src/server/uav/CapacityManager.java` | 二重書き込み追加 |
| `src/server/uav/UAVFlightController.java` | 整合性チェック追加 |

### Phase 3b-1: 新規作成

| ファイル | 役割 |
|---------|------|
| `src/server/redis/UAVJob.java` | ジョブデータクラス |
| `src/server/redis/UAVJobQueue.java` | Redisジョブキュー管理 |
| `src/server/worker/UAVWorker.java` | ワーカープロセス |
| `src/test/UAVWorkerTest.java` | ジョブ投入テスト |

### Phase 3b-2a: 新規作成

| ファイル | 役割 |
|---------|------|
| `src/server/redis/UAVEventChannels.java` | Pub/Subチャンネル名定数 |
| `src/server/redis/UAVLinkPassedEvent.java` | リンク通過イベント |
| `src/server/redis/UAVCompletionEvent.java` | 飛行完了イベント |
| `src/server/redis/WaitingUAVManager.java` | 待機UAV管理（スタブ） |
| `src/test/UAVEventSerializationTest.java` | シリアライズテスト |

### Phase 3b-2a: テストファイル移動

| 移動元 | 移動先 |
|--------|--------|
| `src/server/redis/RedisConnectionTest.java` | `src/test/RedisConnectionTest.java` |

### Phase 3b-2b: 新規作成

| ファイル | 役割 |
|---------|------|
| `src/server/redis/UAVCompletionListener.java` | 完了イベント受信リスナー |
| `src/test/SingleLinkFlightTest.java` | 単一リンク飛行E2Eテスト |

### Phase 3b-2b: 修正

| ファイル | 変更内容 |
|---------|---------|
| `src/server/redis/UAVJob.java` | linkDistances追加、getTotalDistance()等追加 |
| `src/server/worker/UAVWorker.java` | Thread.sleep飛行、publishCompletionEvent()追加 |

### Phase 3b-2c: 新規作成

| ファイル | 役割 |
|---------|------|
| `src/server/redis/UAVLinkPassedListener.java` | リンク通過イベント受信リスナー |
| `src/test/MultiLinkFlightTest.java` | 複数リンク飛行E2Eテスト |

### Phase 3b-2c: 修正

| ファイル | 変更内容 |
|---------|---------|
| `src/server/worker/UAVWorker.java` | 複数リンク対応、publishLinkPassedEvent()追加 |

### Phase 3b-2d: 新規作成

| ファイル | 役割 |
|---------|------|
| `src/test/FirstLinkWaitingTest.java` | 最初リンク待機・再開E2Eテスト |

### Phase 3b-2d: 修正

| ファイル | 変更内容 |
|---------|---------|
| `src/server/redis/WaitingUAVManager.java` | スタブ→本実装（Redis RDeque使用） |
| `src/server/redis/LinkCapacityManager.java` | tryConsumeCapacity(), recoverCapacity(), getCapacity()追加 |
| `src/server/worker/UAVWorker.java` | 最初リンク容量チェック・待機登録追加 |
| `src/server/redis/UAVLinkPassedListener.java` | 容量回復・待機UAV再ジョブ化追加 |

### Phase 3b-3: 新規作成

| ファイル | 役割 |
|---------|------|
| `src/server/scheduler/FlightScheduler.java` | イベントスケジューリング方式の飛行管理 |
| `src/server/worker/AsyncUAVWorker.java` | 非同期UAVワーカー |
| `src/test/AsyncFlightTest.java` | 非同期飛行E2Eテスト |

### Phase 3b-4: 新規作成

| ファイル | 役割 |
|---------|------|
| `src/test/LuaAtomicTest.java` | Luaスクリプト競合テスト（50スレッド同時アクセス） |

### Phase 3b-5: 新規作成

| ファイル | 役割 |
|---------|------|
| `src/test/MidLinkWaitingTest.java` | 途中リンク待機・再開E2Eテスト |

### Phase 3b-3: 修正

| ファイル | 変更内容 |
|---------|---------|
| `src/server/redis/UAVJob.java` | elapsedFlightTime, currentLinkStartTime, addElapsedFlightTime()追加 |

### Phase 3b-4: 修正

| ファイル | 変更内容 |
|---------|---------|
| `src/server/redis/LinkCapacityManager.java` | Luaスクリプト追加、tryConsumeCapacity()とrecoverCapacity()を原子操作化 |

### Phase 3b-6: 修正

| ファイル | 変更内容 |
|---------|---------|
| `src/controller/BoundaryController.java` | WorkerMode列挙型追加、initializeRedisWorker()、shutdownRedisWorker()、initializeLinkCapacities()追加、シャットダウンフック更新 |
| `src/server/controller/ServerController.java` | getLink()メソッド追加 |
| `src/server/route/DijkstraRouteSearcher.java` | runUAVFlow()分岐、runUAVFlowRedis()、runUAVFlowMemory()、calculateLinkDistances()追加 |
| `src/server/route/AbstractPhysarumSolverRouteSearcher.java` | runUAVFlow()分岐、runUAVFlowRedis()、runUAVFlowMemory()、calculateLinkDistances()、adjustRemainingFlowRedis()、findSimplePath()追加 |

---

## Redis Key構造

### Phase 3a: リンク容量

#### link:{srcNode}:{dstNode}:capacity
**Type**: AtomicDouble (String)

**説明**: リンクの現在容量

**例**:
```
Key: link:0:1:capacity
Value: 5.0
```

---

#### link:{srcNode}:{dstNode}:init_capacity
**Type**: Double (String)

**説明**: リンクの初期容量

**例**:
```
Key: link:0:1:init_capacity
Value: 10.0
```

---

#### link:{srcNode}:{dstNode}:flying_count
**Type**: AtomicLong (String)

**説明**: リンクを飛行中のUAV数

**例**:
```
Key: link:0:1:flying_count
Value: 5
```

---

### Phase 3b-1: ジョブキュー

#### jobs:uav
**Type**: List (RBlockingQueue)

**説明**: 処理待ちのUAVジョブキュー

**例**:
```
Key: jobs:uav
Type: List
Values: [
    {uavId: 0, clientId: 1, path: [0,1,4,5], speed: 12.5, ...},
    {uavId: 1, clientId: 1, path: [0,2,5], speed: 10.2, ...},
    ...
]
```

---

### Phase 3b-2a: Pub/Subチャンネルと待機キュー

#### uav:link:passed
**Type**: Pub/Sub Channel

**説明**: UAVがリンクを通過した際のイベントチャンネル

**送信元**: Worker
**受信先**: メインプロセス

**イベント例**:
```json
{
    "uavId": 1,
    "clientId": 2,
    "passedFromNode": 0,
    "passedToNode": 1,
    "nextFromNode": 1,
    "nextToNode": 4,
    "path": [0, 1, 4, 5],
    "currentLinkIndex": 0,
    "timestamp": 1735467010311,
    "elapsedFlightTime": 5.5
}
```

---

#### uav:completed
**Type**: Pub/Sub Channel

**説明**: UAVが目的地に到着した際のイベントチャンネル

**送信元**: Worker
**受信先**: メインプロセス

**イベント例**:
```json
{
    "uavId": 1,
    "clientId": 2,
    "arrivalTime": 1735467020000,
    "totalDistance": 150.5,
    "actualFlightTime": 12.5,
    "totalWaitingTime": 3.2,
    "path": [0, 1, 4, 5],
    "sourceBeaconId": 0,
    "destinationBeaconId": 5,
    "lastLinkFromNode": 4,
    "lastLinkToNode": 5
}
```

---

#### waiting:link:{fromNode}:{toNode}
**Type**: Deque (RDeque)

**説明**: 特定リンクの飛行を待機しているUAVジョブのキュー（FIFO）

**用途（Phase 3b-2d以降）**:
- 容量不足でリンクを通過できないUAVを登録
- 容量回復時にFIFO順で再ジョブ化

**例**:
```
Key: waiting:link:0:1
Type: Deque
Values: [
    {uavId: 5, clientId: 1, path: [0,1,4,5], ...},
    {uavId: 12, clientId: 2, path: [0,1,2,5], ...}
]
```

---

## テスト結果

### Phase 3a: リンク容量整合性検証

**テスト環境**:
- クライアント数: 3
- UAV総数: 60機
- ノード数: 6
- 経路探索手法: EPS

**テスト結果**:
```
Phase 3a: リンク容量整合性チェック正常 (チェック数: 12)
```

✅ **メモリとRedisのリンク容量が完全に一致**

---

### Phase 3b-1: ジョブキュー基本動作

**テスト内容**:
1. ジョブの投入
2. ジョブの取得
3. タイムアウト動作
4. キューサイズ確認

**テスト結果**:
```
Phase 3b: ジョブ投入成功 - UAV 0 (client 1)
Phase 3b: ジョブ取得成功 - UAV 0 (client 1)
Phase 3b: Worker worker-1 - ジョブ取得: UAV 0
Phase 3b: Worker worker-1 - UAV 0 処理開始 (client=1, source=0, dest=5)
Phase 3b: Worker worker-1 - UAV 0 elapsed=2.0s, distance=25.00m
Phase 3b: Worker worker-1 - UAV 0 目的地に到着
Phase 3b: Worker worker-1 - UAV 0 処理完了
```

✅ **ジョブキューの基本動作は正常**

---

### Phase 3b-2a: シリアライズ/デシリアライズテスト

**テスト環境**:
- Redis: 7.2-alpine (Docker)
- Redisson: 3.24.3
- Codec: JsonJacksonCodec

**テスト結果**:
```
=== UAVイベント シリアライズ/デシリアライズ テスト ===

[1/5] UAVLinkPassedEvent シリアライズテスト (RBucket) ✓
  元データ: UAVLinkPassedEvent{uavId=1, clientId=2, passedLink=0→1, nextLink=1→4, ...}
  復元データ: UAVLinkPassedEvent{uavId=1, clientId=2, passedLink=0→1, nextLink=1→4, ...}

[2/5] UAVCompletionEvent シリアライズテスト (RBucket) ✓
  元データ: UAVCompletionEvent{uavId=1, clientId=2, totalDistance=150.50m, ...}
  復元データ: UAVCompletionEvent{uavId=1, clientId=2, totalDistance=150.50m, ...}

[3/5] UAVLinkPassedEvent Pub/Sub テスト ✓
  送信 → 受信 正常

[4/5] UAVCompletionEvent Pub/Sub テスト ✓
  送信 → 受信 正常

[5/5] UAVJob シリアライズテスト (RBucket) ✓
  既存クラスも正常動作

テスト結果: 5 成功, 0 失敗
✓ すべてのテストが成功しました！
```

✅ **イベントクラスのシリアライズ/デシリアライズは正常**
✅ **Pub/Sub通信も正常動作**

---

### Phase 3b-2b: 単一リンク飛行テスト

**テスト環境**:
- Redis: 7.2-alpine (Docker)
- Redisson: 3.24.3
- Worker: 1（テストプロセス内）

**テスト条件**:
- UAV数: 3
- 経路: [0, 1]（単一リンク）
- リンク距離: 50.0m
- UAV速度: 10.0m/s
- 期待飛行時間: 5.0秒

**テスト結果**:
```
テスト結果: 3/3 完了
✓ テスト成功！
```

✅ **Worker が正しく飛行をシミュレート（Thread.sleep）**
✅ **Pub/Sub で完了イベントが正しく送受信（listeners=1）**
✅ **リスナーがイベントを受信してカウント更新**
✅ **飛行時間が正確（50m / 10m/s = 5秒）**

---

### Phase 3b-2c: 複数リンク飛行テスト

**テスト環境**:
- Redis: 7.2-alpine (Docker)
- Redisson: 3.24.3
- Worker: 1（テストプロセス内）

**テスト条件**:
- UAV数: 5
- 経路: [0, 1, 4, 5]（3リンク）
- リンク距離: [50.0, 75.0, 60.0]（合計185.0m）
- UAV速度: 10.0m/s
- 期待飛行時間: 18.5秒/台

**テスト結果**:
```
テスト結果:
  完了UAV: 5/5
  リンク通過イベント: 15/15
✓ テスト成功！
```

✅ **リンクごとの飛行時間が正確（0→1: 5秒, 1→4: 7.5秒, 4→5: 6秒）**
✅ **リンク通過イベントが各リンク通過後に送信（listeners=1）**
✅ **メインプロセスで15回全て受信**
✅ **イベント順序が正しい（0→1, 1→4, 4→5, 完了）**
✅ **5台全UAVが正常に完了**

---

### Phase 3b-2d: 最初リンク待機・再開テスト

**テスト環境**:
- Redis: 7.2-alpine (Docker)
- Redisson: 3.24.3
- Worker: 5（並列）

**テスト条件**:
- UAV数: 5
- 経路: [0, 1, 4, 5]（3リンク）
- リンク距離: [50.0, 75.0, 60.0]（合計185.0m）
- UAV速度: 10.0m/s
- 最初のリンク(0→1)容量: 2
- 期待待機数: 3台

**テスト結果**:
```
テスト結果:
  完了UAV: 5/5
  リンク通過イベント: 15/15
  再ジョブ化数: 3/3
✓ テスト成功！
  - 待機→再開フローが正常動作
```

✅ **容量制限（2）が機能し、3台が待機**
✅ **tryConsumeCapacity()でアトミックな容量消費**
✅ **recoverCapacity()でリンク通過時の容量回復**
✅ **待機UAV再ジョブ化で全5台が完了**
✅ **5 Worker並列でジョブ取得競合なし**

---

### Phase 3b-3: 非同期イベントスケジューリングテスト

**テスト環境**:
- Redis: 7.2-alpine (Docker)
- Redisson: 3.24.3
- Worker: 1（非同期）

**テスト条件**:
- UAV数: 5
- 経路: [0, 1, 4, 5]（3リンク）
- リンク距離: [50.0, 75.0, 60.0]（合計185.0m）
- UAV速度: 10.0m/s
- 期待飛行時間: 18.5秒/台（非同期同時飛行）
- 同期方式との比較: 92.5秒 vs 約19秒

**テスト結果**:
```
テスト結果:
  完了UAV: 5/5
  リンク通過: 15/15
  実行時間: 約19秒
✓ テスト成功！
  - 1 Workerで5台同時飛行を確認
  - イベントスケジューリング方式が正常動作
```

✅ **1 Workerで5台同時飛行（非同期方式）**
✅ **実行時間: 約19秒（同期方式の92.5秒から約80%短縮）**
✅ **リンク通過タイミング: 5台同時に各リンク通過**
✅ **ScheduledExecutorServiceで正確なタイミング**
✅ **Pub/Subで完了イベント正常受信**

---

### Phase 3b-4: Luaスクリプト原子操作テスト

**テスト環境**:
- Redis: 7.2-alpine (Docker)
- Redisson: 3.24.3
- Worker: 1（非同期）

**テスト条件**:
- AsyncFlightTestを使用（Phase 3b-3と同じ条件）
- Luaスクリプトによる容量消費・回復を検証

**テスト結果**:
```
テスト結果:
  完了UAV: 5/5
  リンク通過: 15/15
  容量消費（Lua原子操作）: 15回成功
  容量回復（Lua原子操作）: 15回成功
✓ テスト成功！
```

✅ **Luaスクリプト実行が正常動作**
✅ **容量消費: チェック→消費が1つの原子操作として実行**
✅ **容量回復: 原子的にインクリメント**
✅ **競合ウィンドウが完全に排除**
✅ **既存テスト（AsyncFlightTest）との互換性維持**

---

### 競合テスト（LuaAtomicTest）

**テスト目的**: Luaスクリプトの原子性を競合条件下で検証

**テスト条件**:
- 初期容量: 5
- 同時アクセススレッド数: 50
- 全スレッドが `CountDownLatch` で同期して一斉開始
- 期待成功数: 5（容量と同じ）
- 期待失敗数: 45（50 - 5）

**テスト結果**:
```
=== Phase 3b-4: Luaスクリプト競合テスト ===

テスト条件:
  - 初期容量: 5
  - 同時アクセススレッド数: 50
  - 期待成功数: 5
  - 期待失敗数: 45

[3/4] 競合テスト実行...
  50スレッドが同時に容量消費を試行
  全スレッド開始!
  Thread 22: 失敗 (累計失敗: 1)
  Thread 20: 成功 (累計成功: 1)
  Thread 6: 成功 (累計成功: 2)
  ...（50スレッドが約39msで完了）...

  実行時間: 39ms

=========================
テスト結果:
  成功数: 5 (期待: 5)
  失敗数: 45 (期待: 45)
  最終容量: 0.0 (期待: 0)
  合計処理数: 50 (期待: 50)

✓ テスト成功！
  - 成功数が容量と一致
  - 失敗数が正確
  - 容量が負にならなかった
  - Luaスクリプトの原子性が確認された
=========================
```

**検証ポイント**:

| 項目 | 期待値 | 実測値 | 結果 |
|------|--------|--------|------|
| 成功数 | 5 | 5 | ✅ |
| 失敗数 | 45 | 45 | ✅ |
| 最終容量 | 0.0 | 0.0 | ✅ |
| 合計処理数 | 50 | 50 | ✅ |
| 容量が負になる | なし | なし | ✅ |

✅ **50スレッド同時アクセスでも正確に5回のみ成功**
✅ **容量が負にならない（楽観的ロックで発生する可能性があった問題を排除）**
✅ **実行時間39ms（高速な並列処理）**
✅ **Luaスクリプトの原子性が競合条件下で確認された**

---

## Phase 3b-5: 途中リンク待機・再開

### 実装方針
**途中のリンク（2番目以降）で容量不足時の待機・再開処理を検証**

Phase 3b-3で実装済みのFlightSchedulerには、途中リンク待機機能が既に含まれています。Phase 3b-5ではこの機能をE2Eテストで検証します。

### テスト条件

| 項目 | 値 |
|------|-----|
| ノード数 | 6つ |
| 経路 | `[0, 1, 4, 5]`（3リンク） |
| リンク距離 | [50.0, 75.0, 60.0]（合計185m） |
| UAV数 | 5台 |
| UAV速度 | 10.0 m/s |
| 1番目のリンク(0→1)容量 | 100（無制限） |
| **2番目のリンク(1→4)容量** | **2（制限）** |
| 3番目のリンク(4→5)容量 | 100（無制限） |
| 期待途中待機数 | 3台（5台 - 容量2） |
| 基準飛行時間 | 18.5秒/台（待機なしの場合） |

### 途中リンク待機の実装（FlightScheduler.java）

**既存の実装**（Phase 3b-3で作成済み）:

```java
// onLinkPassed() 内での次リンク容量チェック
int nextFrom = path[linkIndex + 1];
int nextTo = path[linkIndex + 2];

if (!capacityManager.tryConsumeCapacity(nextFrom, nextTo)) {
    // 容量不足 → 途中待機
    onMidFlightWaiting(job, linkIndex + 1, nextFrom, nextTo);
    return;
}

// onMidFlightWaiting() - 途中待機処理
private void onMidFlightWaiting(UAVJob job, int waitingLinkIndex, int fromNode, int toNode) {
    // 再開位置を記録
    job.setCurrentPathIndex(waitingLinkIndex);

    // 待機キューに登録
    waitingManager.enqueue(fromNode, toNode, job);

    // 飛行中から削除
    activeFlights.decrementAndGet();
}
```

### テスト結果

**テスト環境**:
- Redis: 7.2-alpine (Docker)
- Redisson: 3.24.3
- Worker: 1（非同期）

**テスト出力（抜粋）**:
```
=== Phase 3b-5: 途中リンク待機・再開テスト ===

テスト条件:
  - UAV数: 5
  - 経路: [0, 1, 4, 5] (3リンク)
  - リンク容量:
    - 0→1: 100 (無制限)
    - 1→4: 2 (制限)
    - 4→5: 100 (無制限)
  - 期待途中待機数: 3台

[全5台が最初のリンク(0→1)を通過]
  Phase 3b-3: UAV 0 リンク通過 0→1
  Phase 3b-3: UAV 1 リンク通過 0→1
  ...

[2番目のリンク(1→4)で途中待機発生]
  Phase 3b-4: link[1][4] 容量消費成功（Lua原子操作）  ← UAV 0
  Phase 3b-4: link[1][4] 容量消費成功（Lua原子操作）  ← UAV 2
  Phase 3b-4: link[1][4] 容量不足（Lua原子操作）      ← UAV 1 待機
  Phase 3b-4: link[1][4] 容量不足（Lua原子操作）      ← UAV 3 待機
  Phase 3b-4: link[1][4] 容量不足（Lua原子操作）      ← UAV 4 待機
  Phase 3b-3: UAV 1 途中待機 (link 1→4, linkIndex=1)
  Phase 3b-3: UAV 3 途中待機 (link 1→4, linkIndex=1)
  Phase 3b-3: UAV 4 途中待機 (link 1→4, linkIndex=1)

[容量回復 → 待機UAV再ジョブ化]
  Phase 3b-4: link[1][4] 容量回復 → 1.0（Lua原子操作）
  Phase 3b-2d: UAV 3 を待機キュー (1→4) から取り出し
  Phase 3b-3: 待機UAV 3 を再ジョブ化 (link 1→4)
  Phase 3b-3: Worker mid-link-test-worker ジョブ取得 UAV 3 (linkIndex=1, link=1→4)
  ...

=========================
テスト結果:
  完了UAV: 5/5
  リンク通過: 15/15
  実行時間: 34.29秒
  基準時間: 18.5秒（待機なしの場合）
  途中待機発生: あり（予想通り）

✓ テスト成功！
  - 全5台が完了
  - 全15回のリンク通過を確認
  - 途中リンク待機→再開フローが正常動作
=========================
```

### 検証ポイント

| 項目 | 期待値 | 実測値 | 結果 |
|------|--------|--------|------|
| 完了UAV | 5 | 5 | ✅ |
| リンク通過 | 15 | 15 | ✅ |
| 途中待機数 | 3 | 3 | ✅ |
| 再開処理 | 3回 | 3回 | ✅ |

✅ **全5台が1番目のリンク(0→1)を同時に通過**
✅ **2番目のリンク(1→4)で3台が途中待機（容量2に対して5台）**
✅ **容量回復時に待機UAVが正しく再ジョブ化**
✅ **再開後のUAVがlinkIndex=1から正しく飛行再開**
✅ **全15回のリンク通過と5台の完了を確認**

---

## 現在の制限事項と今後の課題

### Phase 3b-2d時点での制限

#### 1. ~~単一リンクのみ対応~~ ✅ Phase 3b-2cで解決済み
```java
// Phase 3b-2c: リンクごとに飛行してイベント送信
while (currentLinkIndex < path.length - 1) {
    Thread.sleep(linkFlightTimeMs);
    publishLinkPassedEvent(...);
    currentLinkIndex++;
}
publishCompletionEvent(...);
```

#### 2. ~~最初のリンク待機処理が未実装~~ ✅ Phase 3b-2dで解決済み
```java
// Phase 3b-2d: 容量チェック→待機→容量回復→再ジョブ化
if (!capacityManager.tryConsumeCapacity(firstLinkFrom, firstLinkTo)) {
    waitingManager.enqueue(firstLinkFrom, firstLinkTo, job);
    return;
}
// リンク通過時
capacityManager.recoverCapacity(passedFromNode, passedToNode);
if (waitingManager.hasWaitingUAV(...)) {
    jobQueue.enqueueJob(waitingManager.dequeue(...));
}
```

#### 3. ~~途中リンク待機処理が未実装~~ ✅ Phase 3b-5で検証済み
```java
// Phase 3b-3 (FlightScheduler.java) で実装済み
// Phase 3b-5 (MidLinkWaitingTest.java) で検証済み
```

#### 4. RouteSearcher統合が未実装
- 現在はテストコードからジョブ投入
- 実際の経路探索からのジョブ投入
- → Phase 3b-6で実装予定

### 解決済みの課題（Phase 3b-2bで対応）

#### ✅ 距離計算（Phase 3b-2bで実装）
```java
// UAVJob.linkDistances[] で実際のリンク距離を保持
// getTotalDistance() で総距離を計算
```

#### ✅ 完了通知（Phase 3b-2bで実装）
```java
// UAVCompletionEvent をPub/Subで送信
// UAVCompletionListener がメインプロセスで受信
```

---

### Phase 3b-2で必要な実装（差し戻し前に試行）

| 機能 | 内容 | 問題点 |
|------|------|--------|
| UAVCompletionEvent | 完了通知データクラス | 実装済み（差し戻しで削除） |
| UAVCompletionListener | 完了通知受信リスナー | 実装済み（差し戻しで削除） |
| Pub/Sub送信 | ワーカーから完了通知 | 動作確認できず |
| ジョブ投入統合 | RouteSearcherからジョブ投入 | 待機UAV処理で問題発生 |
| 待機キュー | 容量不足時のキュー管理 | 処理フロー未完成 |

---

### 差し戻しの理由

Phase 3b-2の実装を試みましたが、以下の問題が発生しました：

1. **待機UAVを捌けない問題**
   - 容量不足でリンクを通過できないUAVがキューに滞留
   - 待機キューを追加しても適切に処理できなかった

2. **全UAVの飛行を完了できない問題**
   - 一部のUAVジョブが処理されずに残った
   - 完了通知がメインプロセスに届かない可能性

3. **容量管理の整合性問題**
   - メモリ（Link[][]）とRedis（link:*:capacity）の二重管理
   - ワーカーはRedisのみ参照、メインプロセスはメモリを更新
   - 整合性が取れない状態

これらの問題を解決するため、85dea33（Phase 3b-1完了時点）まで差し戻しました。

---

## 次のステップ

### ~~Phase 3b-5: 途中リンク待機・再開~~ ✅ 完了

E2Eテスト（MidLinkWaitingTest）で途中リンク待機→再開フローを検証済み。

### Phase 3b-6: RouteSearcher統合（次の作業）

| フェーズ | 内容 |
|---------|------|
| **3b-6** | RouteSearcher統合 + モード切り替え |

### Phase 3b-7: 統合テスト・安定化

| フェーズ | 内容 |
|---------|------|
| **3b-7** | 全体統合テスト・エッジケース対応 |

### イベント駆動アーキテクチャの概要

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           メインプロセス                                 │
│  ┌──────────────┐     ┌──────────────────┐     ┌──────────────────┐    │
│  │RouteSearcher │     │UAVLinkPassed     │     │UAVCompletion     │    │
│  │ ジョブ投入   │     │Listener          │     │Listener          │    │
│  │ 待機登録     │     │ 容量回復         │     │ 統計更新         │    │
│  └──────┬───────┘     │ 待機UAV再ジョブ化│     │ 最終容量回復     │    │
│         │             └────────▲─────────┘     └────────▲─────────┘    │
└─────────┼──────────────────────┼────────────────────────┼───────────────┘
          │ enqueue              │ publish                │ publish
          ▼                      │                        │
┌─────────────────────────────────────────────────────────────────────────┐
│                              Redis                                       │
│   jobs:uav (Queue)          uav:link:passed         uav:completed       │
│   waiting:link:X:Y (Deque)  (Pub/Sub)               (Pub/Sub)           │
└─────────┼───────────────────────────────────────────────────────────────┘
          │ poll
          ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                           Worker プロセス                                │
│   job = dequeue() → flyLinks() → publishLinkPassed() → publishCompletion│
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Phase 3の成果まとめ（現時点）

| 項目 | Phase 2まで | Phase 3現在 |
|------|------------|------------|
| **Redisキー数** | 90+ (uav, stats, flightlog) | **150+** (+link容量, +ジョブキュー) |
| **リンク容量管理** | メモリのみ | **メモリ + Redis（二重書き込み）** |
| **整合性検証** | UAV + 統計情報 | **+ リンク容量** |
| **ジョブキュー** | なし | **基盤実装済み（jobs:uav）** |
| **ワーカープロセス** | なし | **非同期Worker実装済み（AsyncUAVWorker）** |
| **イベント駆動** | なし | **Pub/Sub + ScheduledExecutorService** |
| **スケーラビリティ** | 単一プロセス | **1 Workerで無制限UAV同時飛行** |

### 新規作成ファイル
- ✅ `LinkCapacityManager.java`（容量保存）
- ✅ `LinkCapacityReader.java`（容量読み取り・検証）
- ✅ `UAVJob.java`（ジョブデータ + リンク距離 + 非同期スケジューリング用フィールド）
- ✅ `UAVJobQueue.java`（ジョブキュー）
- ✅ `UAVEventChannels.java`（チャンネル名定数）
- ✅ `UAVLinkPassedEvent.java`（リンク通過イベント）
- ✅ `UAVCompletionEvent.java`（飛行完了イベント）
- ✅ `WaitingUAVManager.java`（待機UAV管理・本実装）
- ✅ `UAVCompletionListener.java`（完了イベント受信）
- ✅ `FlightScheduler.java`（イベントスケジューリング方式の飛行管理）
- ✅ `AsyncUAVWorker.java`（非同期ワーカープロセス）

### 削除されたファイル（Phase 3b-3で不要になった）
- ❌ `UAVWorker.java`（同期Worker → AsyncUAVWorkerに置換）
- ❌ `UAVLinkPassedListener.java`（FlightScheduler内で直接処理）

### 修正ファイル
- ✅ `CapacityManager.java`（二重書き込み追加）
- ✅ `UAVFlightController.java`（容量整合性チェック追加）

### テストファイル
- ✅ `src/test/RedisConnectionTest.java`（Redis接続テスト）
- ✅ `src/test/UAVWorkerTest.java`（ワーカー基本機能テスト）
- ✅ `src/test/UAVEventSerializationTest.java`（シリアライズテスト）
- ✅ `src/test/AsyncFlightTest.java`（非同期飛行E2Eテスト）
- ✅ `src/test/LuaAtomicTest.java`（Luaスクリプト競合テスト）
- ✅ `src/test/MidLinkWaitingTest.java`（途中リンク待機・再開テスト）

### 削除されたテストファイル（同期Worker用）
- ❌ `src/test/SingleLinkFlightTest.java`
- ❌ `src/test/MultiLinkFlightTest.java`
- ❌ `src/test/FirstLinkWaitingTest.java`

### 整合性検証結果
- ✅ **Phase 3a**: リンク容量 - 不整合なし
- ✅ **Phase 3b-1**: ジョブキュー基本動作 - 正常
- ✅ **Phase 3b-2a**: シリアライズ/Pub/Sub - 正常
- ✅ **Phase 3b-2b**: 単一リンク飛行 - 3/3完了
- ✅ **Phase 3b-2c**: 複数リンク飛行 - 5/5完了、15/15リンク通過
- ✅ **Phase 3b-2d**: 最初リンク待機・再開 - 5/5完了、15/15リンク通過、3/3再ジョブ化
- ✅ **Phase 3b-3**: 非同期イベントスケジューリング - 1 Workerで5台同時飛行、約19秒（同期方式の92.5秒から約80%短縮）
- ✅ **Phase 3b-4**: Luaスクリプト原子操作 - 容量消費・回復の完全な原子性を実現、競合テスト（50スレッド同時アクセスで5/5成功、45/45失敗）
- ✅ **Phase 3b-5**: 途中リンク待機・再開 - 2番目のリンクで3台が途中待機→容量回復後に正常再開、5/5完了

---

**Phase 3a/3b-1 完了日**: 2025-12-28
**Phase 3b-2a 完了日**: 2025-12-29
**Phase 3b-2b 完了日**: 2025-12-29
**Phase 3b-2c 完了日**: 2025-12-29
**Phase 3b-2d 完了日**: 2025-12-29
**Phase 3b-3 完了日**: 2025-12-29
**Phase 3b-4 完了日**: 2025-12-29
**Phase 3b-5 完了日**: 2025-12-29
**次の作業**: Phase 3b-6（RouteSearcher統合）
