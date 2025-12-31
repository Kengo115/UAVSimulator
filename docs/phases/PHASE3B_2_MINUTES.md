# Phase 3b-2 実装議事録：ジョブキュー・ワーカー・イベント駆動アーキテクチャ

**実施日**: 2025-12-28 〜 2025-12-29
**担当者**: Claude (Sonnet 4.5)
**関連コミット**:
- 85dea33 ジョブキューとワーカーの実装（3b-1）
- 0fddcb9 Phase3b-2a: イベントクラス枠組み作成
- c2d5235 Phase3b-2b: 単一リンクのworker処理完成
- 1e3690e Phase3b-2c: 複数リンクのworker処理完成
- 6f06efa Phase3b-2d: リンク容量制限と待機キューによる流量制御

---

## 目次

1. [Phase 3b-1: ジョブキューとワーカーの基本実装](#phase-3b-1-ジョブキューとワーカーの基本実装)
2. [Phase 3b-2a: イベントクラス枠組み作成](#phase-3b-2a-イベントクラス枠組み作成)
3. [Phase 3b-2b: Worker単一リンク飛行](#phase-3b-2b-worker単一リンク飛行)
4. [Phase 3b-2c: Worker複数リンク飛行](#phase-3b-2c-worker複数リンク飛行)
5. [Phase 3b-2d: 最初リンク待機・再開](#phase-3b-2d-最初リンク待機再開)

---

## Phase 3b-1: ジョブキューとワーカーの基本実装

### 実装方針

**Producer-Consumer パターン**
1. **メインプロセス (Producer)**: UAVジョブをRedisキューに投入
2. **ワーカープロセス (Consumer)**: キューからジョブを取得して処理
3. **Redis**: ジョブキューの永続化とプロセス間通信

### 実装した機能

#### 1. UAVジョブ定義（UAVJob.java）

**データ構造**:
```java
public class UAVJob implements Serializable {
    private int uavId;
    private int clientId;
    private int[] path;                    // 飛行経路（ビーコンIDの配列）
    private double speed;                  // 速度（m/s）
    private long startTime;                // 飛行開始時刻（ミリ秒）
    private int sourceBeaconId;            // 出発地ビーコンID
    private int destinationBeaconId;       // 目的地ビーコンID
    private int currentPathIndex;          // 現在の経路インデックス
}
```

#### 2. UAVジョブキュー（UAVJobQueue.java）

**キー構造**: `jobs:uav` - メインのジョブキュー（RBlockingQueue）

**主要メソッド**:
```java
public boolean enqueueJob(UAVJob job)
public UAVJob dequeueJob(long timeout, TimeUnit unit)
public int getQueueSize()
public void clearQueue()
```

#### 3. UAVワーカープロセス（UAVWorker.java）

**処理フロー**:
```java
public void start() {
    while (running.get()) {
        UAVJob job = jobQueue.dequeueJob(5, TimeUnit.SECONDS);
        if (job != null) {
            processUAVJob(job);
        }
    }
}
```

---

## Phase 3b-2a: イベントクラス枠組み作成

### 実装方針

**イベント駆動アーキテクチャの基盤構築**

Phase 3b-1で発生した問題（待機UAVが処理されない）を解決するため、ポーリングベースからイベント駆動アーキテクチャへの移行を決定。

### 実装した機能

#### 1. Pub/Subチャンネル名定数（UAVEventChannels.java）

```java
public final class UAVEventChannels {
    public static final String LINK_PASSED = "uav:link:passed";
    public static final String COMPLETION = "uav:completed";
    public static final String WAITING_QUEUE_PREFIX = "waiting:link:";

    public static String getWaitingQueueKey(int fromNode, int toNode) {
        return WAITING_QUEUE_PREFIX + fromNode + ":" + toNode;
    }
}
```

#### 2. リンク通過イベント（UAVLinkPassedEvent.java）

```java
public class UAVLinkPassedEvent implements Serializable {
    private int uavId;
    private int clientId;
    private int passedFromNode;      // 通過したリンクの始点
    private int passedToNode;        // 通過したリンクの終点
    private int nextFromNode;        // 次のリンク（-1 = 最終）
    private int nextToNode;
    private int[] path;
    private int currentLinkIndex;
    private double elapsedFlightTime;
}
```

#### 3. 飛行完了イベント（UAVCompletionEvent.java）

```java
public class UAVCompletionEvent implements Serializable {
    private int uavId;
    private int clientId;
    private double totalDistance;
    private double actualFlightTime;
    private double totalWaitingTime;
    private int[] path;
    private int sourceBeaconId;
    private int destinationBeaconId;
}
```

#### 4. 待機UAV管理（WaitingUAVManager.java）

```java
public class WaitingUAVManager {
    public void enqueue(int fromNode, int toNode, UAVJob job);
    public UAVJob dequeue(int fromNode, int toNode);
    public boolean hasWaitingUAV(int fromNode, int toNode);
    public int getWaitingCount(int fromNode, int toNode);
    public void clearAll();
}
```

### シリアライズテスト結果

| テスト | 結果 |
|-------|------|
| UAVLinkPassedEvent RBucket | OK |
| UAVCompletionEvent RBucket | OK |
| UAVLinkPassedEvent Pub/Sub | OK |
| UAVCompletionEvent Pub/Sub | OK |

---

## Phase 3b-2b: Worker単一リンク飛行

### 実装方針

単一リンク（0→1）でWorker処理を動作確認：
1. Worker がジョブを取得して飛行処理
2. Thread.sleep で飛行時間をシミュレート
3. 完了イベントを Pub/Sub で送信

### 実装した機能

#### 1. UAVJob.java 拡張

```java
private double[] linkDistances;  // 各リンクの距離（メートル）

public double getLinkDistance(int linkIndex);
public double getTotalDistance();
public double getRemainingDistance();
```

#### 2. UAVWorker.java 飛行処理

```java
private void processUAVJob(UAVJob job) {
    double totalDistance = job.getTotalDistance();
    double flightTimeSeconds = totalDistance / job.getSpeed();
    Thread.sleep((long)(flightTimeSeconds * 1000));
    publishCompletionEvent(job, totalDistance, flightTimeSeconds);
}
```

#### 3. UAVCompletionListener.java

メインプロセスで完了イベントを受信し、統計を更新。

### テスト結果

```
テスト結果: 3/3 完了
- Worker が正しく飛行をシミュレート
- Pub/Sub で完了イベントが正しく送受信
- 飛行時間が正確（50m / 10m/s = 5秒）
```

---

## Phase 3b-2c: Worker複数リンク飛行

### 実装方針

複数リンク経路（0→1→4→5）で、リンクごとに通過イベントを送信。

### Phase 3b-2bとの差分

| 項目 | 3b-2b（単一リンク） | 3b-2c（複数リンク） |
|------|---------------------|---------------------|
| 経路 | `[0, 1]`（1リンク） | `[0, 1, 4, 5]`（3リンク） |
| 飛行処理 | 一括 Thread.sleep | リンクごとに Thread.sleep |
| イベント | 完了のみ | リンク通過 + 完了 |

### 実装した機能

#### 1. UAVWorker.java 複数リンク対応

```java
private void processUAVJob(UAVJob job) {
    int[] path = job.getPath();
    double elapsedFlightTime = 0.0;
    int currentLinkIndex = 0;

    while (currentLinkIndex < path.length - 1) {
        int fromNode = path[currentLinkIndex];
        int toNode = path[currentLinkIndex + 1];
        double linkDistance = job.getLinkDistance(currentLinkIndex);
        double linkFlightTime = linkDistance / job.getSpeed();

        Thread.sleep((long)(linkFlightTime * 1000));
        elapsedFlightTime += linkFlightTime;

        publishLinkPassedEvent(job, fromNode, toNode, ...);
        currentLinkIndex++;
    }

    publishCompletionEvent(job, totalDistance, elapsedFlightTime);
}
```

#### 2. UAVLinkPassedListener.java

メインプロセスでリンク通過イベントを受信。

### テスト結果

```
完了UAV: 5/5
リンク通過イベント: 15/15 (5台 × 3リンク)
- リンクごとの飛行時間が正確
- イベント順序が正しい（0→1, 1→4, 4→5, 完了）
```

---

## Phase 3b-2d: 最初リンク待機・再開

### 実装方針

最初のリンクに容量制限を設け、容量不足時の待機・再開処理を実装。

### テスト条件

| 項目 | 値 |
|------|-----|
| 経路 | `[0, 1, 4, 5]`（3リンク） |
| UAV数 | 5台 |
| 最初のリンク容量 | **2**（待機発生） |
| Worker数 | 5（並列処理） |
| 期待待機数 | 3台（5 - 2） |

### 実装した機能

#### 1. WaitingUAVManager.java 本実装

Redis RDequeを使用したFIFO待機キュー：

```java
public void enqueue(int fromNode, int toNode, UAVJob job) {
    String key = UAVEventChannels.getWaitingQueueKey(fromNode, toNode);
    RDeque<UAVJob> queue = client.getDeque(key);
    queue.addLast(job);
}

public UAVJob dequeue(int fromNode, int toNode) {
    String key = UAVEventChannels.getWaitingQueueKey(fromNode, toNode);
    RDeque<UAVJob> queue = client.getDeque(key);
    return queue.pollFirst();
}
```

#### 2. LinkCapacityManager.java 追加メソッド

```java
// 容量消費（アトミック操作）
public boolean tryConsumeCapacity(int srcNode, int dstNode);

// 容量回復（アトミック操作）
public double recoverCapacity(int srcNode, int dstNode);

// 現在容量取得
public double getCapacity(int srcNode, int dstNode);
```

#### 3. UAVWorker.java 容量チェック

```java
private void processUAVJob(UAVJob job) {
    int firstLinkFrom = path[0];
    int firstLinkTo = path[1];

    if (!capacityManager.tryConsumeCapacity(firstLinkFrom, firstLinkTo)) {
        // 容量不足 → 待機キューに登録
        waitingManager.enqueue(firstLinkFrom, firstLinkTo, job);
        return;
    }

    // 容量確保成功 → 飛行開始
    // ...
}
```

#### 4. UAVLinkPassedListener.java 容量回復・再ジョブ化

```java
private void handleLinkPassedEvent(UAVLinkPassedEvent event) {
    int passedFromNode = event.getPassedFromNode();
    int passedToNode = event.getPassedToNode();

    // 容量回復
    capacityManager.recoverCapacity(passedFromNode, passedToNode);

    // 待機UAVがいれば再ジョブ化
    if (waitingManager.hasWaitingUAV(passedFromNode, passedToNode)) {
        UAVJob waitingJob = waitingManager.dequeue(passedFromNode, passedToNode);
        if (waitingJob != null) {
            jobQueue.enqueueJob(waitingJob);
        }
    }
}
```

### テスト結果

```
完了UAV: 5/5
リンク通過イベント: 15/15
再ジョブ化数: 3/3
- 容量制限が正常動作
- 待機→再開フローが正常動作
- 5 Worker並列でジョブ取得競合なし
```

---

## 作成・修正したファイル

### 新規作成
- `src/server/redis/UAVJob.java`
- `src/server/redis/UAVJobQueue.java`
- `src/server/worker/UAVWorker.java`
- `src/server/redis/UAVEventChannels.java`
- `src/server/redis/UAVLinkPassedEvent.java`
- `src/server/redis/UAVCompletionEvent.java`
- `src/server/redis/WaitingUAVManager.java`
- `src/server/redis/UAVCompletionListener.java`
- `src/server/redis/UAVLinkPassedListener.java`

### テストファイル
- `src/test/java/server/redis/UAVEventSerializationTest.java`
- `src/test/java/server/worker/SingleLinkFlightTest.java`
- `src/test/java/server/worker/MultiLinkFlightTest.java`
- `src/test/java/server/worker/FirstLinkWaitingTest.java`

---

## Redis Key構造

| Key | 型 | 説明 |
|-----|-----|------|
| `jobs:uav` | RBlockingQueue | UAVジョブキュー |
| `waiting:link:{from}:{to}` | RDeque | リンク別待機キュー |
| `uav:link:passed` | Pub/Sub | リンク通過イベント |
| `uav:completed` | Pub/Sub | 飛行完了イベント |

---

## 次のステップ

Phase 3b-3: 非同期イベントスケジューリングへ進む
