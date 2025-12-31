# UAVシミュレータ アーキテクチャドキュメント

**最終更新**: 2025-12-31
**対象バージョン**: Phase 3b-11

---

## 目次

1. [設計思想](#設計思想)
2. [アーキテクチャ概要](#アーキテクチャ概要)
3. [メモリベースアーキテクチャ](#メモリベースアーキテクチャ)
4. [Redisベースアーキテクチャ](#redisベースアーキテクチャ)
5. [シーケンス図：ユースケース別](#シーケンス図ユースケース別)
6. [コンポーネント詳細](#コンポーネント詳細)
7. [設計による効果](#設計による効果)

---

## 設計思想

### 1. イベント駆動アーキテクチャ

従来のポーリング方式からイベント駆動方式へ移行し、リソース効率と応答性を向上させました。

```
[従来] ポーリング方式
┌─────────────────────────────────────────┐
│  while (true) {                         │
│    checkAllUAVs();  // 全UAVを毎回確認   │
│    sleep(100ms);    // 待機             │
│  }                                      │
└─────────────────────────────────────────┘

[現在] イベント駆動方式
┌─────────────────────────────────────────┐
│  onLinkPassed(uav) {                    │
│    // リンク通過時のみ処理              │
│    recoverCapacity();                   │
│    notifyWaitingUAVs();                 │
│  }                                      │
└─────────────────────────────────────────┘
```

### 2. 分散処理対応

Redisを中央データストアとして使用し、将来的なマルチプロセス・マルチサーバー構成に対応可能な設計としました。

### 3. 原子操作によるデータ整合性

Luaスクリプトを使用して、容量チェック→消費を単一の原子操作として実行し、競合状態を防止します。

### 4. 双方向リンクの対称性保証

双方向リンクの容量を常に対称に管理し、順方向・逆方向の整合性を保証します。

---

## アーキテクチャ概要

### ワーカーモード選択

```
┌─────────────────────────────────────────────────────────────┐
│                    BoundaryController                       │
│                                                             │
│  ┌─────────────────┐     ┌─────────────────────────────┐   │
│  │ WorkerMode.MEMORY│     │ WorkerMode.REDIS            │   │
│  │ (従来方式)       │ OR  │ (Phase 3b 非同期ワーカー)    │   │
│  └─────────────────┘     └─────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## メモリベースアーキテクチャ

### 構成図

```
┌─────────────────────────────────────────────────────────────────────┐
│                        単一プロセス                                  │
│                                                                     │
│  ┌─────────────┐     ┌──────────────────┐     ┌──────────────────┐ │
│  │RouteSearcher│────▶│ UAVFlyScheduler  │────▶│    Link[][]      │ │
│  │             │     │                  │     │  (メモリ上の容量) │ │
│  └─────────────┘     └──────────────────┘     └──────────────────┘ │
│         │                    │                                      │
│         │                    ▼                                      │
│         │           ┌──────────────────┐                           │
│         └──────────▶│   flyingUAV[][]  │                           │
│                     │  (飛行中UAV管理)  │                           │
│                     └──────────────────┘                           │
└─────────────────────────────────────────────────────────────────────┘
```

### データフロー

1. `RouteSearcher` が経路を計算
2. `UAVFlyScheduler` がUAVの飛行をスケジュール
3. `Link[][]` 配列でリンク容量を直接管理
4. `flyingUAV[][]` 配列で飛行中UAV数を追跡

### 特徴

| 項目 | 説明 |
|------|------|
| データ格納 | JVMヒープメモリ |
| プロセス | 単一プロセス |
| スレッド | シングルスレッド（シーケンシャル処理） |
| 適用場面 | 小規模シミュレーション、デバッグ |

---

## Redisベースアーキテクチャ

### 構成図

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              メインプロセス                                  │
│                                                                             │
│  ┌─────────────────┐                                                        │
│  │ BoundaryController│                                                      │
│  └────────┬────────┘                                                        │
│           │                                                                 │
│           ▼                                                                 │
│  ┌─────────────────┐     ┌──────────────────────────────────────────────┐  │
│  │  RouteSearcher  │────▶│              UAVJobQueue                     │  │
│  │  (経路計算)      │     │         (jobs:uav キー)                      │  │
│  └─────────────────┘     └──────────────────────────────────────────────┘  │
│                                          │                                  │
│                                          ▼                                  │
│  ┌─────────────────┐     ┌──────────────────────────────────────────────┐  │
│  │UAVCompletion    │◀────│                Redis                         │  │
│  │    Listener     │     │                                              │  │
│  │  (完了通知受信)  │     │  ┌──────────┐  ┌──────────┐  ┌──────────┐   │  │
│  └─────────────────┘     │  │jobs:uav  │  │link:X:Y: │  │waiting:  │   │  │
│                          │  │(キュー)   │  │capacity  │  │link:X:Y  │   │  │
│                          │  └──────────┘  └──────────┘  └──────────┘   │  │
│                          │                                              │  │
│                          └──────────────────────────────────────────────┘  │
│                                          ▲                                  │
│                                          │                                  │
│  ┌───────────────────────────────────────┼──────────────────────────────┐  │
│  │              AsyncUAVWorker Pool (4 workers)                         │  │
│  │                                       │                               │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────┐ │  │
│  │  │  Worker #1   │  │  Worker #2   │  │  Worker #3   │  │Worker #4 │ │  │
│  │  └──────┬───────┘  └──────────────┘  └──────────────┘  └──────────┘ │  │
│  │         │                                                            │  │
│  │         ▼                                                            │  │
│  │  ┌───────────────────────────────────────────────────────────────┐  │  │
│  │  │                    FlightScheduler                             │  │  │
│  │  │                                                                │  │  │
│  │  │  ScheduledThreadPoolExecutor (16-32 threads, auto-scaling)    │  │  │
│  │  │                                                                │  │  │
│  │  │  ┌───────────────────────────────────────────────────────┐    │  │  │
│  │  │  │ onLinkPassed() → recoverCapacity() → requeueWaiting() │    │  │  │
│  │  │  └───────────────────────────────────────────────────────┘    │  │  │
│  │  └───────────────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 主要コンポーネント

| コンポーネント | 役割 |
|---------------|------|
| `BoundaryController` | エントリーポイント、ワーカーモード切替 |
| `RouteSearcher` | 経路計算、UAVJobの生成・投入 |
| `UAVJobQueue` | Redisブロッキングキューによるジョブ管理 |
| `AsyncUAVWorker` | ジョブ取得→容量チェック→FlightScheduler委譲 |
| `FlightScheduler` | イベントスケジューリング、容量回復、待機UAV再開 |
| `LinkCapacityManager` | Lua原子操作によるリンク容量管理 |
| `WaitingUAVManager` | リンク別待機キュー管理 |
| `UAVCompletionListener` | Pub/Subによる完了通知受信 |

### Redisキー構造

```
jobs:uav                          # UAVジョブキュー（BLPOP/BRPOP）
link:{src}:{dst}:capacity         # リンク容量（AtomicDouble）
link:{src}:{dst}:init_capacity    # 初期容量
link:{src}:{dst}:flying_count     # 飛行中UAV数
waiting:link:{from}:{to}          # 待機キュー（Deque）
uav:completion                    # 完了通知チャンネル（Pub/Sub）
uav:link_passed                   # リンク通過通知チャンネル（Pub/Sub）
```

---

## シーケンス図：ユースケース別

### 1. クライアント経路要求

```
Client          BoundaryController    RouteSearcher       UAVJobQueue        Redis
  │                    │                   │                   │               │
  │  createClient()    │                   │                   │               │
  │───────────────────▶│                   │                   │               │
  │                    │ runUAVFlowRedis() │                   │               │
  │                    │──────────────────▶│                   │               │
  │                    │                   │ new UAVJob()      │               │
  │                    │                   │───────────────────│               │
  │                    │                   │                   │               │
  │                    │                   │ setSessionId()    │               │
  │                    │                   │───────────────────│               │
  │                    │                   │                   │               │
  │                    │                   │ enqueueJob(job)   │               │
  │                    │                   │──────────────────▶│               │
  │                    │                   │                   │ LPUSH jobs:uav│
  │                    │                   │                   │──────────────▶│
  │                    │                   │                   │               │
```

**関連クラス・メソッド**:
- `BoundaryController.createClient1()` → クライアント生成
- `DijkstraRouteSearcher.runUAVFlowRedis()` → ジョブ投入
- `UAVJobQueue.enqueueJob(UAVJob)` → Redisキュー追加

---

### 2. UAV飛行可否判定（容量あり）

```
AsyncUAVWorker      UAVJobQueue      FlightScheduler    LinkCapacityManager    Redis
     │                  │                  │                    │                │
     │ dequeueJob()     │                  │                    │                │
     │─────────────────▶│                  │                    │                │
     │                  │ BRPOP jobs:uav   │                    │                │
     │                  │─────────────────────────────────────────────────────▶│
     │                  │◀─────────────────────────────────────────────────────│
     │◀─────────────────│                  │                    │                │
     │                  │                  │                    │                │
     │ isValidSession() │                  │                    │                │
     │─────────────────────────────────────▶│                    │                │
     │◀─────────────────────────────────────│                    │                │
     │                  │                  │                    │                │
     │ tryConsumeCapacity(from, to)        │                    │                │
     │─────────────────────────────────────────────────────────▶│                │
     │                  │                  │                    │ EVAL Lua消費   │
     │                  │                  │                    │───────────────▶│
     │                  │                  │                    │◀───────────────│
     │                  │                  │                    │ EVAL Lua消費   │
     │                  │                  │                    │───────────────▶│
     │                  │                  │                    │ (逆方向も消費) │
     │                  │                  │                    │◀───────────────│
     │◀─────────────────────────────────────────────────────────│ return true    │
     │                  │                  │                    │                │
     │ startFlight(job) │                  │                    │                │
     │─────────────────────────────────────▶│                    │                │
     │                  │                  │ scheduleNextLink() │                │
     │                  │                  │────────────────────│                │
```

**関連クラス・メソッド**:
- `AsyncUAVWorker.processJob(UAVJob)` → ジョブ処理
- `FlightScheduler.isValidSession(UAVJob)` → セッション検証
- `LinkCapacityManager.tryConsumeCapacity(src, dst)` → Lua原子操作で容量消費
- `FlightScheduler.startFlight(UAVJob)` → 飛行開始

---

### 3. UAV待機（容量不足）

```
AsyncUAVWorker    LinkCapacityManager    WaitingUAVManager    Redis
     │                    │                     │               │
     │ tryConsumeCapacity()                     │               │
     │───────────────────▶│                     │               │
     │                    │ EVAL Lua消費        │               │
     │                    │────────────────────────────────────▶│
     │                    │◀────────────────────────────────────│
     │◀───────────────────│ return false        │               │
     │ (容量不足)         │                     │               │
     │                    │                     │               │
     │ job.startWaiting() │                     │               │
     │────────────────────│                     │               │
     │                    │                     │               │
     │ enqueue(from, to, job)                   │               │
     │─────────────────────────────────────────▶│               │
     │                    │                     │ RPUSH waiting │
     │                    │                     │──────────────▶│
     │                    │                     │               │
```

**関連クラス・メソッド**:
- `LinkCapacityManager.tryConsumeCapacity()` → 容量不足でfalse
- `UAVJob.startWaiting()` → 待機開始時刻記録
- `WaitingUAVManager.enqueue(from, to, job)` → 待機キュー登録

---

### 4. リンク飛行完了（途中リンク）

```
FlightScheduler    LinkCapacityManager    WaitingUAVManager    UAVJobQueue    Redis
     │                    │                     │                   │           │
     │ onLinkPassed()     │                     │                   │           │
     │────────────────────│                     │                   │           │
     │                    │                     │                   │           │
     │ recoverCapacity(from, to)                │                   │           │
     │───────────────────▶│                     │                   │           │
     │                    │ EVAL Lua回復        │                   │           │
     │                    │────────────────────────────────────────────────────▶│
     │                    │ EVAL Lua回復(逆方向)│                   │           │
     │                    │────────────────────────────────────────────────────▶│
     │◀───────────────────│                     │                   │           │
     │                    │                     │                   │           │
     │ hasWaitingUAV(from, to)                  │                   │           │
     │─────────────────────────────────────────▶│                   │           │
     │                    │                     │ LLEN waiting      │           │
     │                    │                     │──────────────────────────────▶│
     │◀─────────────────────────────────────────│                   │           │
     │                    │                     │                   │           │
     │ hasWaitingUAV(to, from) ← 逆方向チェック │                   │           │
     │─────────────────────────────────────────▶│                   │           │
     │◀─────────────────────────────────────────│                   │           │
     │                    │                     │                   │           │
     │ (待機UAVがいれば)   │                     │                   │           │
     │ dequeue()          │                     │                   │           │
     │─────────────────────────────────────────▶│                   │           │
     │◀─────────────────────────────────────────│ waitingJob        │           │
     │                    │                     │                   │           │
     │ enqueueJob(waitingJob)                   │                   │           │
     │─────────────────────────────────────────────────────────────▶│           │
     │                    │                     │                   │ LPUSH     │
     │                    │                     │                   │──────────▶│
     │                    │                     │                   │           │
     │ tryConsumeCapacity(next)                 │                   │           │
     │───────────────────▶│                     │                   │           │
     │ (成功すれば)        │                     │                   │           │
     │ scheduleNextLink() │                     │                   │           │
     │────────────────────│                     │                   │           │
```

**関連クラス・メソッド**:
- `FlightScheduler.onLinkPassed(UAVJob, linkIndex)` → リンク通過処理
- `LinkCapacityManager.recoverCapacity(from, to)` → 順方向・逆方向の容量回復
- `WaitingUAVManager.hasWaitingUAV(from, to)` → 順方向待機確認
- `WaitingUAVManager.hasWaitingUAV(to, from)` → **逆方向待機確認（Phase 3b-11追加）**
- `WaitingUAVManager.dequeue(from, to)` → 待機UAV取得
- `UAVJobQueue.enqueueJob(job)` → 待機UAVの再ジョブ化

---

### 5. 待機終了・飛行再開

```
AsyncUAVWorker      UAVJobQueue      FlightScheduler    LinkCapacityManager
     │                  │                  │                    │
     │ dequeueJob()     │                  │                    │
     │─────────────────▶│                  │                    │
     │◀─────────────────│ waitingJob       │                    │
     │                  │                  │                    │
     │ tryConsumeCapacity()                │                    │
     │─────────────────────────────────────────────────────────▶│
     │◀─────────────────────────────────────────────────────────│ true
     │                  │                  │                    │
     │ startFlight(job) │                  │                    │
     │─────────────────────────────────────▶│                    │
     │                  │                  │ endWaiting()       │
     │                  │                  │ (待機時間確定)      │
     │                  │                  │ scheduleNextLink() │
     │                  │                  │ (再開位置から)      │
```

**関連クラス・メソッド**:
- `FlightScheduler.startFlight(UAVJob)` → `job.endWaiting()` で待機時間確定
- `UAVJob.getTotalWaitingTime()` → 累積待機時間取得

---

### 6. UAV飛行完了

```
FlightScheduler    ResultOutputManager    RTopic(completion)    UAVCompletionListener
     │                    │                      │                     │
     │ onFlightCompleted()│                      │                     │
     │────────────────────│                      │                     │
     │                    │                      │                     │
     │ outputFlightTimeResult()                  │                     │
     │───────────────────▶│                      │                     │
     │                    │ (CSV出力)             │                     │
     │                    │                      │                     │
     │ publishCompletionEvent()                  │                     │
     │──────────────────────────────────────────▶│                     │
     │                    │                      │ PUBLISH             │
     │                    │                      │ uav:completion      │
     │                    │                      │────────────────────▶│
     │                    │                      │                     │ handleCompletionEvent()
     │                    │                      │                     │─────────────────────────
     │                    │                      │                     │ (ログ出力、カウント)
```

**関連クラス・メソッド**:
- `FlightScheduler.onFlightCompleted(UAVJob)` → 飛行完了処理
- `ResultOutputManager.outputFlightTimeResult(job, runCounter)` → CSV出力
- `FlightScheduler.publishCompletionEvent(UAVJob)` → Pub/Sub送信
- `UAVCompletionListener.handleCompletionEvent(event)` → 完了イベント受信

---

### 7. 容量同期（Redis→メモリ）

```
RouteSearcher    LinkCapacityManager    Link[][]    Redis
     │                  │                  │          │
     │ (経路探索前)      │                  │          │
     │                  │                  │          │
     │ syncCapacitiesToMemory(link, node)  │          │
     │─────────────────▶│                  │          │
     │                  │                  │          │
     │                  │ for each link:   │          │
     │                  │ GET link:X:Y:capacity       │
     │                  │────────────────────────────▶│
     │                  │◀────────────────────────────│
     │                  │ setCapacity()    │          │
     │                  │─────────────────▶│          │
     │                  │                  │          │
     │◀─────────────────│ (同期完了)        │          │
     │                  │                  │          │
     │ (経路探索実行)    │                  │          │
```

**関連クラス・メソッド**:
- `LinkCapacityManager.syncCapacitiesToMemory(Link[][], node)` → Redis→メモリ同期
- `LinkCapacityManager.initializeCapacitiesToRedis(Link[][], node)` → メモリ→Redis初期化

---

## コンポーネント詳細

### FlightScheduler

```java
/**
 * 飛行スケジューラ（シングルトン）
 *
 * 責務:
 * - イベントスケジューリング（ScheduledThreadPoolExecutor）
 * - リンク通過時の容量回復
 * - 待機UAVの再ジョブ化（順方向・逆方向両方）
 * - 飛行完了イベントの発行
 * - スレッドプールのオートスケーリング（16-32スレッド）
 * - セッションID検証
 */
public class FlightScheduler {
    // 主要メソッド
    void startFlight(UAVJob job);           // 飛行開始
    void scheduleNextLink(UAVJob, index);   // 次リンクスケジュール
    void onLinkPassed(UAVJob, index);       // リンク通過処理
    void onMidFlightWaiting(UAVJob, ...);   // 途中待機
    void onFlightCompleted(UAVJob);         // 飛行完了
    boolean isValidSession(UAVJob);         // セッション検証
}
```

### AsyncUAVWorker

```java
/**
 * 非同期UAVワーカー
 *
 * 責務:
 * - ジョブキューからのジョブ取得（ブロッキング）
 * - 容量チェック→FlightScheduler委譲
 * - 容量不足時の待機キュー登録
 */
public class AsyncUAVWorker {
    void start();                  // メインループ開始
    void processJob(UAVJob job);   // ジョブ処理
    void stop();                   // 停止
}
```

### LinkCapacityManager

```java
/**
 * リンク容量管理（Lua原子操作）
 *
 * 責務:
 * - 容量消費（tryConsumeCapacity）- 双方向対応
 * - 容量回復（recoverCapacity）- 双方向対応
 * - Redis⇔メモリ間の同期
 */
public class LinkCapacityManager {
    boolean tryConsumeCapacity(src, dst);  // Lua原子消費
    double recoverCapacity(src, dst);      // Lua原子回復
    void syncCapacitiesToMemory(...);      // Redis→メモリ
    void initializeCapacitiesToRedis(...); // メモリ→Redis
}
```

### WaitingUAVManager

```java
/**
 * 待機UAV管理（Redis Deque）
 *
 * 責務:
 * - リンク別待機キュー管理（FIFO）
 * - 待機UAVの登録・取り出し
 */
public class WaitingUAVManager {
    void enqueue(from, to, job);      // 待機登録
    UAVJob dequeue(from, to);         // 待機取り出し
    boolean hasWaitingUAV(from, to);  // 待機確認
}
```

---

## 設計による効果

### 1. パフォーマンス向上

| 項目 | 従来（メモリ） | 現在（Redis） |
|------|---------------|---------------|
| ポーリング | 100msごと | イベント駆動（0ms） |
| スレッド数 | 固定1 | オートスケーリング16-32 |
| 処理効率 | シーケンシャル | 並列処理 |

### 2. スケーラビリティ

- **水平スケール**: ワーカー数を増やすことで処理能力向上
- **分散処理**: Redisを中央データストアとして複数プロセス対応可能

### 3. データ整合性

- **原子操作**: Luaスクリプトで競合状態を防止
- **双方向対称性**: 順方向・逆方向リンクの容量が常に同期
- **セッションID**: 古いプロセスからの残留ジョブを自動拒否

### 4. 観測性

- **詳細ログ**: 各フェーズでログ出力
- **統計情報**: 飛行時間、待機時間、完了数をトラッキング
- **結果出力**: CSV形式で飛行結果を保存

### 5. 保守性

- **関心の分離**: 各コンポーネントが単一責務
- **テスト容易性**: 依存性注入によるモック化対応
- **後方互換**: メモリモードとRedisモードの切り替え可能

---

## 関連ドキュメント

- [Phase 3a 議事録](./phases/PHASE3A_MINUTES.md) - リンク容量Redis移行
- [Phase 3b-2 議事録](./phases/PHASE3B_2_MINUTES.md) - ジョブキュー・ワーカー
- [Phase 3b-3 議事録](./phases/PHASE3B_3_MINUTES.md) - イベントスケジューリング
- [Phase 3b-4 議事録](./phases/PHASE3B_4_MINUTES.md) - Lua原子操作
- [Phase 3b-5 議事録](./phases/PHASE3B_5_MINUTES.md) - 途中待機・再開
- [Phase 3b-11 議事録](./phases/PHASE3B_11_MINUTES.md) - 双方向リンク同期修正
