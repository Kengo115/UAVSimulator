# Phase 3b-3 実装議事録：非同期イベントスケジューリング

**実施日**: 2025-12-29
**担当者**: Claude (Sonnet 4.5)
**コミット**: 8fe4a86 Phase3b-3: Workerを非同期処理に変更

---

## 目的

**イベントスケジューリング方式による非同期飛行管理**

Phase 3b-2dまでは同期方式（1 Worker = 1 UAV同時飛行）でしたが、本番環境（200ノード、100+ UAV）ではWorker数がボトルネックになります。Phase 3b-3ではイベントスケジューリング方式を採用し、1 Workerで複数UAVの同時飛行を実現します。

---

## 同期方式 vs 非同期方式

| 項目 | 同期方式（Phase 3b-2dまで） | 非同期方式（Phase 3b-3） |
|------|---------------------------|-------------------------|
| 1 Workerで処理可能なUAV | 1台（Thread.sleepでブロック） | **無制限**（即座にreturn） |
| 飛行時間シミュレート | Thread.sleep | **ScheduledExecutorService** |
| 5台のUAV処理時間（1 Worker） | 92.5秒（順次処理） | **約19秒（同時飛行）** |
| スケーラビリティ | Worker数 = 同時飛行UAV数 | **Worker数は関係なし** |

---

## イベントスケジューリングの原理

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

---

## 実装した機能

### 1. UAVJob.java 拡張

```java
// Phase 3b-3: 非同期スケジューリング用
private double elapsedFlightTime;      // 経過飛行時間（秒）
private long currentLinkStartTime;     // 現在のリンク飛行開始時刻（ミリ秒）

public void addElapsedFlightTime(double time) {
    this.elapsedFlightTime += time;
}
```

### 2. FlightScheduler.java（新規作成）

**役割**: イベントスケジューリング方式による非同期飛行管理

```java
public class FlightScheduler {
    private static FlightScheduler instance;  // シングルトン
    private ScheduledExecutorService scheduler;
    private LinkCapacityManager capacityManager;
    private WaitingUAVManager waitingManager;
    private UAVJobQueue jobQueue;
    private RTopic linkPassedTopic;
    private RTopic completionTopic;
    private AtomicInteger activeFlights;
    private AtomicInteger completedFlights;

    public void startFlight(UAVJob job) {
        int linkIndex = job.getCurrentPathIndex();
        job.setCurrentLinkStartTime(System.currentTimeMillis());
        activeFlights.incrementAndGet();
        scheduleNextLink(job, linkIndex);
    }

    private void scheduleNextLink(UAVJob job, int linkIndex) {
        double distance = job.getLinkDistance(linkIndex);
        double flightTimeSec = distance / job.getSpeed();
        long flightTimeMs = (long)(flightTimeSec * 1000);

        scheduler.schedule(
            () -> onLinkPassed(job, linkIndex),
            flightTimeMs,
            TimeUnit.MILLISECONDS
        );
    }

    private void onLinkPassed(UAVJob job, int linkIndex) {
        // 1. 経過時間を更新
        // 2. リンク通過イベントを送信（Pub/Sub）
        // 3. 容量回復
        // 4. 待機UAVがいれば再ジョブ化
        // 5. 最終リンクか判定 → 完了処理
        // 6. 次のリンクの容量チェック → 不足なら途中待機
        // 7. 次のリンク飛行をスケジュール
    }
}
```

### 3. AsyncUAVWorker.java（新規作成）

**役割**: 非同期UAVワーカー（ジョブ取得→FlightSchedulerに委譲→即return）

| 項目 | UAVWorker（同期） | AsyncUAVWorker（非同期） |
|------|------------------|------------------------|
| 飛行処理 | Thread.sleep（ブロック） | FlightScheduler委譲（即return） |
| 1ジョブ処理時間 | 飛行時間と同じ | **ほぼ0秒** |
| 同時処理可能UAV | 1台 | **無制限** |

```java
private void processJob(UAVJob job) {
    boolean acquired = capacityManager.tryConsumeCapacity(fromNode, toNode);

    if (!acquired) {
        waitingManager.enqueue(fromNode, toNode, job);
        return;
    }

    // 飛行開始（FlightSchedulerに委譲、即座にreturn）
    flightScheduler.startFlight(job);
    // ← ここで即座にreturn、次のジョブを取得可能
}
```

---

## テスト結果

**テスト条件**:
- UAV数: 5
- 経路: [0, 1, 4, 5]（3リンク）
- リンク距離: [50.0, 75.0, 60.0]（合計185m）
- UAV速度: 10.0m/s
- Worker数: 1（非同期）

**結果**:
```
完了UAV: 5/5
リンク通過: 15/15
実行時間: 19.xx秒
期待時間: 18.5秒（非同期同時飛行）

- 1 Workerで5台同時飛行を確認
- イベントスケジューリング方式が正常動作
```

---

## アーキテクチャ図

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
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │     ScheduledExecutorService (8スレッド)                         │   │
│   │                                                                  │   │
│   │   5秒後: UAV0 onLinkPassed(link0)                                │   │
│   │   5秒後: UAV1 onLinkPassed(link0)                                │   │
│   │   5秒後: UAV2 onLinkPassed(link0)  ← 同時実行                    │   │
│   │   5秒後: UAV3 onLinkPassed(link0)                                │   │
│   │   5秒後: UAV4 onLinkPassed(link0)                                │   │
│   └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 作成・修正したファイル

### 新規作成
- `src/server/scheduler/FlightScheduler.java`
- `src/server/worker/AsyncUAVWorker.java`

### 修正
- `src/server/redis/UAVJob.java`（elapsedFlightTime追加）

### テスト
- `src/test/java/server/worker/AsyncFlightTest.java`

---

## 次のステップ

Phase 3b-4: Luaスクリプト原子操作へ進む
