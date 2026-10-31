# Memory Mode 設計ドキュメント

## 1. 目的

Redis モードの実験環境をインメモリで再現し、以下の3モードで比較実験を可能にする。

| モード | 識別子 | 方式 | 目的 |
|--------|--------|------|------|
| Redis | `WorkerMode.REDIS` | イベント駆動（Redis） | 現行基準 |
| Option A | `WorkerMode.MEMORY_POLLING` | 2秒ポーリング | シンプルな方式差比較 |
| Option B | `WorkerMode.MEMORY_EVENT` | イベント駆動（インメモリ） | アルゴリズム公平比較 |

---

## 2. Redis パイプライン全体像（参照元）

```
BoundaryController
  └─ routeRequest() → ServerController.run_XXX()
       └─ RouteSearcher.runUAVFlowRedis()
            └─ UAVJobQueue.enqueueJob(UAVJob)  ← Redis RBlockingQueue
                 └─ AsyncUAVWorker (4 workers)
                      └─ LinkCapacityManager.tryConsumeCapacity()  ← Lua script
                           ├─ [OK]  → FlightScheduler.startFlight()
                           │          └─ ScheduledExecutorService: 時刻になったら onLinkPassed()
                           │               ├─ WaitingUAVManager から次の待機UAVを解放
                           │               ├─ LinkCapacityManager.recoverCapacity()
                           │               └─ onFlightCompleted() → RTopic.publish(completion)
                           └─ [NG]  → WaitingUAVManager.enqueue()  ← Redis RDeque
```

---

## 3. Option A: ポーリング方式

### アーキテクチャ

```
RouteSearcher (Polling variants)
  └─ runUAVFlowMemory() → Uav オブジェクト作成 → flyingUavQueue / uavQueue に追加

PollingUAVFlyScheduler (2秒間隔)
  └─ PollingUAVFlightController.flyUAV()
       ├─ 全飛行中UAVの位置更新 (flightTime × speed)
       ├─ 到着判定 → FlightDataRecorder.saveFlightData()
       ├─ 中継ノード待機判定
       └─ PollingCapacityManager.updateCapacity()
```

### 主要クラス

| クラス | パッケージ | 役割 |
|--------|-----------|------|
| `PollingCapacityManager` | `legacy.polling` | `link[][]` 直接更新 |
| `PollingUAVFlightController` | `legacy.polling` | 飛行・待機・到着処理 |
| `PollingUAVFlyScheduler` | `legacy.polling` | 2秒ポーリング制御 |
| `PollingMemorySimulator` | `legacy.polling` | 全体制御・結果出力 |
| `PollingDijkstraRouteSearcher` | `legacy.polling.route` | Dijkstra + インメモリ投入 |
| `PollingPSRouteSearcher` | `legacy.polling.route` | PS + インメモリ投入 |
| `PollingPGEPSRouteSearcher` | `legacy.polling.route` | PG-EPS + インメモリ投入 |

### スケジューリングロジック

```
[2秒ごと flyUAV() 実行]

for each uav in flyingUavQueue:
  flightDist = uav.getFlightTime() * uav.getSpeed()  // 経過実時間 × 速度
  totalDist  = Σ link[path[k]][path[k+1]].distance

  if flightDist >= totalDist:
    → 到着: cancelTimer(), saveFlightData(), incrementFinishCounter()
  else:
    → processUavMovement(): どのリンク上か特定 → flyingUAV[i][j]++

PollingCapacityManager.updateCapacity(flyingUAV, link, node)
  → link[i][j].capacity = initCapacity - flyingUAV[i][j]

processWaitingUAVs():
  → capacity > 0 のリンクがあれば待機UAVを flyingQueue へ移動
```

---

## 4. Option B: イベント駆動インメモリ方式

### アーキテクチャ（Redis と対応）

```
Redis 版               →   Option B 版
─────────────────────────────────────────────────
UAVJobQueue            →   MemoryJobQueue
  (RBlockingQueue)           (LinkedBlockingQueue)

LinkCapacityManager    →   MemoryCapacityManager
  (Redis Lua script)         (ConcurrentHashMap + synchronized)

WaitingUAVManager      →   MemoryWaitingManager
  (Redis RDeque/link)        (ConcurrentHashMap<String, Deque>)

PathWaitingManager     →   MemoryPathWaitingManager
  (Redis RDeque/client)      (ConcurrentHashMap<Integer, Deque>)

AsyncUAVWorker         →   MemoryUAVWorker
  (Redis 依存)               (MemoryJobQueue 依存)

FlightScheduler        →   MemoryFlightScheduler
  (RTopic pub/sub)           (直接コールバック)

UAVCompletionListener  →   MemoryCompletionTracker
  (Redis pub/sub)            (AtomicCounter + callback)
```

### 主要クラス

| クラス | パッケージ | 役割 |
|--------|-----------|------|
| `MemoryJobQueue` | `legacy.eventdriven` | `LinkedBlockingQueue<UAVJob>` ラッパー |
| `MemoryCapacityManager` | `legacy.eventdriven` | `ConcurrentHashMap` + atomic 容量管理 |
| `MemoryWaitingManager` | `legacy.eventdriven` | リンク単位の待機キュー |
| `MemoryPathWaitingManager` | `legacy.eventdriven` | client 単位の経路待ちキュー |
| `MemoryFlightScheduler` | `legacy.eventdriven` | `FlightScheduler` のインメモリ版 |
| `MemoryUAVWorker` | `legacy.eventdriven` | `AsyncUAVWorker` のインメモリ版 |
| `MemoryCompletionTracker` | `legacy.eventdriven` | 完了カウンタ + コールバック |
| `EventDrivenMemorySimulator` | `legacy.eventdriven` | 全体制御・結果出力 |
| `EventDrivenDijkstraRouteSearcher` | `legacy.eventdriven.route` | Dijkstra + MemoryJobQueue 投入 |
| `EventDrivenPSRouteSearcher` | `legacy.eventdriven.route` | PS + MemoryJobQueue 投入 |
| `EventDrivenPGEPSRouteSearcher` | `legacy.eventdriven.route` | PG-EPS + MemoryJobQueue 投入 |

---

## 5. 共通コンポーネント

| クラス | パッケージ | 役割 |
|--------|-----------|------|
| `RandomTopologyGenerator` | `legacy.common` | N ノードランダムトポロジ生成 |

---

## 6. 結果ディレクトリ

```
src/result/sim_N/
├── large_scale/          # Redis モード (既存)
│   └── <Method>/
│       ├── link_status/
│       │   ├── link_status.csv
│       │   └── congestion_rate.csv
│       └── time/
│           └── client<N>/flight_times.csv
│
├── polling_scale/        # Option A (新規)
│   └── <Method>/
│       ├── link_status/
│       └── time/
│
└── eventdriven_scale/    # Option B (新規)
    └── <Method>/
        ├── link_status/
        └── time/
```

`BoundaryController.getResultDir()` が `WorkerMode` に応じてスケールディレクトリを切り替える。

---

## 7. Redis 汚染防止策

### 変更範囲の厳格な制限

| ファイル | 変更種別 | 変更内容 | 安全性 |
|---------|---------|---------|--------|
| `DijkstraRouteSearcher.java` | 可視性変更のみ | フィールド `private` → `protected` (5フィールド) | 動作変化なし |
| `DijkstraRouteSearcher.java` | 可視性変更のみ | `runUAVFlow` `private` → `protected` (1行) | 動作変化なし |
| `BisectionalPressureGuidedEPSRouteSearcher.java` | リファクタリング | path-waiting 登録ロジックを `registerRemainingUAVsToPathWaiting()` に抽出 | デフォルト動作不変 |
| `BoundaryController.java` | 追記のみ | `customScaleDir` フィールド + `setCustomScaleDir()` / `getCustomScaleDir()` | 既存パスに影響なし |
| `ServerController.java` | 追記のみ | `getAdjMatrix()` getter 追加 | 既存パスに影響なし |
| `src/legacy/**` | 新規作成 | 全新規ファイル | 本番に無影響 |

### 本番コードへの影響ゼロの確認ポイント

1. `DijkstraRouteSearcher` フィールドを `protected` にしても呼び出し元の動作は変わらない（読み取り専用の可視性拡大のみ）
2. `BisectionalPressureGuidedEPSRouteSearcher.registerRemainingUAVsToPathWaiting()` のデフォルト実装は元のコードと完全に同一
3. `BoundaryController.getResultDir()` は `customScaleDir == null` のとき旧来の `isLargeScaleMode` による分岐を使う
4. `legacy.*` クラスは `operator.*` 既存クラスを**読み取るのみ**（ServerController.getLinkArray() 等）

### 結果ディレクトリ（実装済み）

```
src/result/
├── large_scale/           # Redis モード（isLargeScaleMode=true）
├── small_scale/           # Redis モード（isLargeScaleMode=false）
├── memory_polling/        # Option A: BoundaryController.setCustomScaleDir("memory_polling")
└── memory_eventdriven/    # Option B: EventDrivenMemorySimulator が自動設定
```

---

## 8. 標準出力ログ方針

### Option A ログ

```
[PollingMode] UAVFlyScheduler 起動 (2秒間隔)
[PollingMode] client{N} UAV{M}機 → flyingQueue 投入
[PollingMode] UAV{id} ノード{k} で待機（容量不足）
[PollingMode] UAV{id} 到着完了 (飛行時間: {t}s)
[PollingMode] 経過{N}分 / 飛行中{X}機 / 待機{Y}機 / 完了{Z}機  (5分ごと)
[PollingMode] 完了 / 総UAV数: {N} / 平均飛行時間: {t}s
```

### Option B ログ

```
[EventDrivenMode] MemoryUAVWorker x4 起動
[EventDrivenMode] MemoryFlightScheduler 起動
[EventDrivenMode] client{N}: UAV{M}機 → MemoryJobQueue 投入
[EventDrivenMode] UAV{id}: link {from}→{to} 通過完了
[EventDrivenMode] UAV{id}: 待機 (link {from}→{to}, 容量不足)
[EventDrivenMode] UAV{id}: 目的地到着 (飛行時間: {t}s)
[EventDrivenMode] 経過{N}分 / 飛行中{X}機 / 待機{Y}機 / 完了{Z}機  (5分ごと)
[EventDrivenMode] 完了 / 総UAV数: {N} / 平均飛行時間: {t}s
```

---

## 9. ファイル一覧（作成順）

### Phase 1: 本番コード最小変更
1. `src/operator/route/DijkstraRouteSearcher.java` - `private` → `protected`

### Phase 2: Option A
2. `src/legacy/polling/PollingCapacityManager.java`
3. `src/legacy/polling/PollingUAVFlightController.java`
4. `src/legacy/polling/PollingUAVFlyScheduler.java`
5. `src/legacy/polling/route/PollingDijkstraRouteSearcher.java`
6. `src/legacy/polling/route/PollingPSRouteSearcher.java`
7. `src/legacy/polling/route/PollingPGEPSRouteSearcher.java`
8. `src/legacy/polling/PollingMemorySimulator.java`

### Phase 3: Option B
9.  `src/legacy/eventdriven/MemoryJobQueue.java`
10. `src/legacy/eventdriven/MemoryCapacityManager.java`
11. `src/legacy/eventdriven/MemoryWaitingManager.java`
12. `src/legacy/eventdriven/MemoryPathWaitingManager.java`
13. `src/legacy/eventdriven/MemoryCompletionTracker.java`
14. `src/legacy/eventdriven/MemoryFlightScheduler.java`
15. `src/legacy/eventdriven/MemoryUAVWorker.java`
16. `src/legacy/eventdriven/route/EventDrivenDijkstraRouteSearcher.java`
17. `src/legacy/eventdriven/route/EventDrivenPSRouteSearcher.java`
18. `src/legacy/eventdriven/route/EventDrivenPGEPSRouteSearcher.java`
19. `src/legacy/eventdriven/EventDrivenMemorySimulator.java`

### Phase 4: 共通
20. `src/legacy/common/RandomTopologyGenerator.java`

### Phase 5: BoundaryController 統合（未実装）
21. `src/operator/BoundaryController.java` - WorkerMode enum + モード選択UI + 委譲ロジック
    - `EventDrivenMemorySimulator.initialize()` / `routeRequest()` への委譲
    - `PollingMemorySimulator.initialize()` / `routeRequest()` への委譲

---

## 10. 既知の制限事項

### Option A (Polling)
- `PollingPGEPSRouteSearcher.search()` は親の `BisectionalPressureGuidedEPSRouteSearcher.search()` を呼ぶため、内部で `new PathWaitingManager()` が生成される。Redis 未接続時は `PathWaitingManager.enqueue()` が無視され、EPS 割当外の残り UAV は飛行しない。
- 実験では EPS が全 UAV を割当できるケースが多いため通常は問題にならない。

### Option B (EventDriven)
- PS ルートサーチャーは `adjustRemainingFlowRedis()` を呼ばないため、フロー不足時の UAV 再割当が行われない（Polling 版と同一制限）。
- `MemoryFlightScheduler` はセッション ID を使用しない（`currentSessionId == null` = 全ジョブ許可）。
