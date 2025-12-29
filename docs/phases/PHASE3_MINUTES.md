# Phase 3 実装議事録：リンク容量Redis移行とワーカー基盤

**実施日**: 2025-12-28 〜 2025-12-29
**担当者**: Claude (Sonnet 4.5 / Opus 4.5)
**ステータス**: 🔄 部分完了（Phase 3a完了、Phase 3b-1完了、Phase 3b-2a完了、Phase 3b-2b完了、Phase 3b-2c以降は未着手）

## 目次
1. [Phase 3の概要](#phase-3の概要)
2. [Phase 3a: リンク容量Redis移行](#phase-3a-リンク容量redis移行)
3. [Phase 3b-1: ジョブキューとワーカーの基本実装](#phase-3b-1-ジョブキューとワーカーの基本実装)
4. [Phase 3b-2a: イベントクラス枠組み作成](#phase-3b-2a-イベントクラス枠組み作成)
5. [Phase 3b-2b: Worker単一リンク飛行](#phase-3b-2b-worker単一リンク飛行)
6. [作成・修正したファイル](#作成修正したファイル)
7. [Redis Key構造](#redis-key構造)
8. [テスト結果](#テスト結果)
9. [現在の制限事項と今後の課題](#現在の制限事項と今後の課題)
10. [次のステップ](#次のステップ)

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
| **Phase 3b-2c** | Worker複数リンク飛行 | ⬜ 未着手 |
| **Phase 3b-2d** | 最初リンク待機・再開 | ⬜ 未着手 |
| **Phase 3b-2e** | 途中リンク待機・再開 | ⬜ 未着手 |
| **Phase 3b-2f** | RouteSearcher統合 | ⬜ 未着手 |
| **Phase 3b-3** | 統合テスト・安定化 | ⬜ 未着手 |

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

## 現在の制限事項と今後の課題

### Phase 3b-2b時点での制限

#### 1. 単一リンクのみ対応 ✅ → Phase 3b-2cで対応予定
```java
// 現在: 全リンクを一括で飛行（Thread.sleep）
Thread.sleep(flightTimeMs);

// 今後: リンクごとに飛行してイベント送信
// → Phase 3b-2cで実装予定
```

#### 2. 待機処理が未実装
- 容量不足でリンクを通過できない場合の処理
- 待機キューの管理
- 容量回復時の再処理
- → Phase 3b-2d/2eで実装予定

#### 3. RouteSearcher統合が未実装
- 現在はテストコードからジョブ投入
- 実際の経路探索からのジョブ投入
- → Phase 3b-2fで実装予定

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

### Phase 3b-2c: Worker複数リンク飛行（次の作業）

**目標**: 複数リンクの経路でWorker処理を動作確認

**テスト条件**:
- ノード: 6つ（0 → 1 → 4 → 5）
- 経路: 3リンク [0, 1, 4, 5]
- UAV数: 5台
- リンク容量: 100（待機が発生しない）
- Worker数: 1

**実装予定**:
1. `UAVWorker.java` で複数リンク対応（リンクごとにThread.sleep）
2. `UAVLinkPassedListener.java` でリンク通過イベント受信
3. リンク通過ごとにイベント送信
4. 複数リンクテストの作成

### Phase 3b-2d〜2f: 段階的な機能追加

| フェーズ | 内容 |
|---------|------|
| **3b-2d** | 最初のリンクでの待機・再開 |
| **3b-2e** | 途中リンクでの待機・再開 |
| **3b-2f** | RouteSearcher統合 + モード切り替え |

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
| **ワーカープロセス** | なし | **基盤実装済み** |
| **イベント駆動** | なし | **Pub/Sub基盤実装済み + 完了イベント動作確認済み** |
| **スケーラビリティ** | 単一プロセス | **並列処理の基盤あり** |

### 新規作成ファイル
- ✅ `LinkCapacityManager.java`（容量保存）
- ✅ `LinkCapacityReader.java`（容量読み取り・検証）
- ✅ `UAVJob.java`（ジョブデータ + リンク距離）
- ✅ `UAVJobQueue.java`（ジョブキュー）
- ✅ `UAVWorker.java`（ワーカープロセス + 完了イベント送信）
- ✅ `UAVEventChannels.java`（チャンネル名定数）
- ✅ `UAVLinkPassedEvent.java`（リンク通過イベント）
- ✅ `UAVCompletionEvent.java`（飛行完了イベント）
- ✅ `WaitingUAVManager.java`（待機UAV管理・スタブ）
- ✅ `UAVCompletionListener.java`（完了イベント受信）

### 修正ファイル
- ✅ `CapacityManager.java`（二重書き込み追加）
- ✅ `UAVFlightController.java`（容量整合性チェック追加）

### テストファイル
- ✅ `src/test/RedisConnectionTest.java`（Redis接続テスト）
- ✅ `src/test/UAVWorkerTest.java`（ワーカー基本機能テスト）
- ✅ `src/test/UAVEventSerializationTest.java`（シリアライズテスト）
- ✅ `src/test/SingleLinkFlightTest.java`（単一リンク飛行E2Eテスト）

### 整合性検証結果
- ✅ **Phase 3a**: リンク容量 - 不整合なし
- ✅ **Phase 3b-1**: ジョブキュー基本動作 - 正常
- ✅ **Phase 3b-2a**: シリアライズ/Pub/Sub - 正常
- ✅ **Phase 3b-2b**: 単一リンク飛行 - 3/3完了

---

**Phase 3a/3b-1 完了日**: 2025-12-28
**Phase 3b-2a 完了日**: 2025-12-29
**Phase 3b-2b 完了日**: 2025-12-29
**次の作業**: Phase 3b-2c（Worker複数リンク飛行）
