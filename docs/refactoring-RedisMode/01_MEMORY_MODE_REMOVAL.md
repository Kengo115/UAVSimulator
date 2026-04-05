# Step 1: MEMORYモード完全削除

## 概要

MEMORYモードに関わる全てのコードを削除し、REDISモード専用のクリーンな状態にする。
これにより、後続の疎結合化リファクタリングの土台を整える。

---

## 削除対象ファイル（3つ）

| ファイル | 役割 | 削除理由 |
|---|---|---|
| `src/server/uav/UAVFlyScheduler.java` | MEMORYモードの2秒ポーリングスケジューラ | MEMORYモード専用 |
| `src/server/uav/UAVFlightController.java` | MEMORYモードのリンク-by-リンク飛行制御 | MEMORYモード専用 |
| `src/server/uav/CapacityManager.java` | `UAVFlightController` からのみ呼ばれる容量更新クラス | MEMORYモード専用 |

---

## 修正対象ファイル（11ファイル）

### A. BoundaryController.java（最重要・最大の変更）

**削除する要素**:
- `WorkerMode` enum（`MEMORY(1)` / `REDIS(2)`）全体
- `currentWorkerMode` フィールドと getter/setter
- 対話型モード選択UI（"ワーカーモードを選択してください"プロンプト、行866-891）
- `flyingUavQueue`, `uavQueue` インスタンスフィールド（MEMORYモード用）
- `import server.uav.UAVFlyScheduler;`
- 全ての `UAVFlyScheduler.startFlyUAVUpdates(...)` / `stopFlyUAVUpdates(...)` 呼び出し
  - 行1058付近（統計シミュレーションのrouteRequestAsync後）
  - 行1149付近（UAVFlyScheduler強制停止）
  - 行1240-1251付近（通常シミュレーションループ）
  - 行1384-1396付近（通常シミュレーション開始時）
  - 行1433付近（終了処理）

**変更する要素**:
- `initializeRedisWorker()` を常に無条件で呼び出す（成功前提）
- Redis初期化失敗時の MEMORY フォールバック（行418）→ エラーメッセージ出力 + 強制終了
- `if (currentWorkerMode == WorkerMode.REDIS)` ガード（行1441）→ 無条件化
- `routeRequest()`, `routeRequestAsync()` から `flyingUavQueue`/`uavQueue` を除去

---

### B. ServerController.java（7つのrun_*メソッドを一括修正）

**削除する要素（各 run_* メソッドに7箇所ずつ）**:

```java
// 削除: UFlyScheduler停止（各メソッド冒頭）
if (!flyingUavQueue.isEmpty()) {
    UAVFlyScheduler.stopFlyUAVUpdates(clientController);
}

// 削除: preSearchAction 内の WorkerMode.REDIS ガード
// → if ブロックの中身だけを残す（常時実行）
if (BoundaryController.getCurrentWorkerMode() == BoundaryController.WorkerMode.REDIS) {
    ...  // ← この中身だけ残す
}

// 削除: postSearchAction 内の UAVFlyScheduler 呼び出し
if (currentRunCounter != 0) {
    UAVFlyScheduler.startFlyUAVUpdates(...);  // ← 全部削除
}
```

**削除するメソッド**:
- `flyUAV()` メソッド（`UAVFlightController` への委譲のみ、MEMORYモード専用）

**シグネチャ変更**:
- 全 `run_*()` メソッドから `Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue` パラメータを削除

**削除するimport**:
- `import server.uav.UAVFlyScheduler;`
- `import server.uav.UAVFlightController;`
- `import server.uav.CapacityManager;`

---

### C. RouteSearcher.java（インターフェース）

**シグネチャ変更**:
```java
// 変更前
void search(Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, int numLoop) throws IOException;

// 変更後
void search(Client client, int numLoop) throws IOException;
```

---

### D. AbstractPhysarumSolverRouteSearcher.java（最大の実装変更）

**削除するメソッド**:
- `runUAVFlowMemory()` メソッド全体（約70行）
- `adjustRemainingFlow()` メソッド全体（`runUAVFlowMemory` からのみ呼ばれる）

**変更するメソッド**:
- `runUAVFlow()` の WorkerMode 分岐を削除 → `runUAVFlowRedis()` を直接呼び出す
- `runUAVFlowRedis()` → `runUAVFlow()` へリネーム（分岐不要になるため）
- `search()`, `runUAVFlow()` シグネチャから `flyingUavQueue`, `uavQueue` を削除

```java
// 変更前
protected void runUAVFlow(int start, int goal, int count, Client client,
                           Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue) {
    if (BoundaryController.getCurrentWorkerMode() == WorkerMode.REDIS) {
        runUAVFlowRedis(start, goal, count, client);
    } else {
        runUAVFlowMemory(start, goal, count, client, flyingUavQueue, uavQueue);
    }
}

// 変更後
protected void runUAVFlow(int start, int goal, int count, Client client) {
    // runUAVFlowRedis の中身をここに直接記述（またはprivateメソッドに残す）
}
```

---

### E. DijkstraRouteSearcher.java

- `search()` シグネチャから `flyingUavQueue`, `uavQueue` を削除
- `runUAVFlow()` 内の WorkerMode 分岐を削除 → Redis側の処理のみ残す
- `runUAVFlowMemory()` を削除

---

### F. HybridPhysarumSolverRouteSearcher.java

- DijkstraRouteSearcher と同様

---

### G. BinaryExtendedPhysarumSolverRouteSearcher.java

- `search()` シグネチャ変更（パラメータ削除）
- WorkerMode ログ出力部分（行633-636付近）を削除または単純化

---

### H. BisectionalPressureGuidedEPSRouteSearcher.java

- `search()` シグネチャ変更
- PathWaiting登録の WorkerMode 分岐（MEMORYブランチ）を削除 → REDISブランチのみ残す
- WorkerMode ログ出力部分（行669-672付近）を削除

---

### I. StepControlledPressureGuidedEPSRouteSearcher.java

- BisectionalPGEPS と同様

---

### J. SearcherRetryManager.java

- `SearchRequest` クラスから `flyingUavQueue`, `uavQueue` フィールドを削除
- `search()` 呼び出し部分から `flyingUavQueue`, `uavQueue` を削除

---

### K. FlightDataRecorder.java

- `saveRouteToRedis()` の冒頭ガード（WorkerMode.REDIS チェック）を削除 → 常時実行
- `saveFlightDataToRedis()` の同様のガードを削除

---

## 懸念事項と対応策

### 懸念1: `flyingUavQueue`/`uavQueue` パラメータのカスケード削除

**影響範囲**: 11ファイル、RouteSearcher インターフェースを含む
**リスク**: 中途半端な変更でコンパイルエラーが多発
**対応**: 以下の順序で一括変更し、最後にまとめてコンパイル確認
1. RouteSearcher.java（インターフェース）
2. AbstractPhysarumSolverRouteSearcher.java（基底クラス）
3. 各実装クラス（D~I）
4. SearcherRetryManager.java
5. ServerController.java, BoundaryController.java

### 懸念2: Redis初期化失敗時のフォールバック削除

**現在の動作**: Redis接続失敗 → MEMORYモードにフォールバック（行418）
**変更後の動作**: Redis接続失敗 → エラーメッセージ出力 + `System.exit(1)`
**前提**: `make run` で Redis が自動起動済みであること

### 懸念3: テストファイルへの影響

`src/test/` 以下のファイル（`LuaAtomicTest`, `MidLinkWaitingTest`, `AsyncFlightTest`）は
`LinkCapacityManager`（Redis系）のみを使用しており、削除対象クラスを参照していない。
→ **影響なし**

### 懸念4: `UAVStatisticsManager` の呼び出し元消滅

`UAVStatisticsManager.saveAllStats()` の唯一の呼び出し元は `UAVFlyScheduler`（削除対象）。
しかし `UAVFlyScheduler` 内の呼び出し条件は `WorkerMode.REDIS` だったため、
実質的には**デッドコード**だった。
クラス自体は Redis統計機能として将来使える可能性があるため残す。

### 懸念5: `BoundaryController` の flyingUavQueue/uavQueue 除去範囲

統計シミュレーションループ（clientGenMode=4）内で `UAVFlyScheduler` 呼び出し時に渡している箇所が複数ある。
REDISモードではこれらのキューは `AsyncUAVWorker` → Redis 経由で管理されるため不要。
フィールドごと削除する。

---

## 検証手順

```bash
# 1. コンパイル確認
make compile

# 2. 起動確認（モード選択UIが消えて自動でREDIS起動）
make run

# 3. EPS で通常シミュレーション実行 → 結果ファイル確認
ls src/result/

# 4. PG-EPS で PathWaiting を含むシミュレーション実行
# → src/result/*/large_scale/BisectionalPGEPS/time/*/flight_times.csv 確認
```
