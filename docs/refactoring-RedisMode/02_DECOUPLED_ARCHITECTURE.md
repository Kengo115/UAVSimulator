# Step 2/3: 疎結合アーキテクチャ設計

## 概要

Step 1（MEMORYモード削除）完了後、さらに**オペレータ側**と**ネットワーク管理側**を
Redis pub/sub を介して疎結合化する。
最終的に Network Manager 側を独立 OSS として公開する。

---

## 現状のアーキテクチャ（Step 1 完了時点）

```
BoundaryController
  └─ ServerController（経路探索・リクエスト受付）
       └─ RouteSearcher 実装群（経路計算）
            └─ FlightDataRecorder（結果記録）
  └─ AsyncUAVWorker × 4（UAV飛行処理）
       └─ FlightScheduler（ジョブスケジューリング）
            └─ Redis（UAVJob キュー・容量管理）
```

問題点:
- 経路探索（オペレータ）とUAV飛行制御（ネットワーク管理）が同一プロセスに混在
- `LinkCapacityManager` が双方向に参照される（探索側も飛行制御側も直接 Redis を操作）

---

## 目標アーキテクチャ（Step 3 完了時点）

```
[Operator Side]                    [Network Manager Side]
BoundaryController                 FlightScheduler
  └─ ServerController     Redis      └─ AsyncUAVWorker × 4
       └─ RouteSearcher   pub/sub         └─ UAVJob管理
            └─ FlightData  ←→             └─ LinkCapacityManager
               Recorder                   └─ PathWaitingManager
```

**通信方向**:
- `flight:submit` : Operator → NetworkManager（UAV飛行依頼）
- `network:topology` : Operator → NetworkManager（グラフ構造の通知）
- `network:capacity_update` : NetworkManager → Operator（容量状態の更新通知）
- `flight:completed` : NetworkManager → Operator（UAV飛行完了通知）

---

## Step 2: 同一リポジトリ内モジュール分割

### パッケージ構成の変更

**変更前（現状）**:
```
src/
  controller/         ← BoundaryController
  server/
    controller/       ← ServerController
    route/            ← RouteSearcher 群
    uav/              ← AsyncUAVWorker, FlightScheduler, FlightDataRecorder
    redis/            ← Redis管理クラス群
    scheduler/        ← SearcherRetryManager
```

**変更後（Step 2）**:
```
src/
  operator/
    controller/       ← BoundaryController, ServerController
    route/            ← RouteSearcher 群
    scheduler/        ← SearcherRetryManager
  network-manager/
    uav/              ← AsyncUAVWorker, FlightScheduler, UAVJob
    redis/            ← LinkCapacityManager, PathWaitingManager, UAVStateManager
    recorder/         ← FlightDataRecorder（移動後）
  shared/
    model/            ← Client, Uav, Link, Node, Flow（共通モデル）
    config/           ← RedisConnectionManager, LogManager
```

### 移動対象クラスの整理

| クラス | 現在の場所 | 移動先 | 理由 |
|---|---|---|---|
| `BoundaryController` | `controller/` | `operator/controller/` | オペレータ起点 |
| `ServerController` | `server/controller/` | `operator/controller/` | 経路探索制御 |
| `RouteSearcher` 群 | `server/route/` | `operator/route/` | 経路計算ロジック |
| `SearcherRetryManager` | `server/scheduler/` | `operator/scheduler/` | 探索リトライ管理 |
| `AsyncUAVWorker` | `server/uav/` | `network-manager/uav/` | UAV飛行制御 |
| `FlightScheduler` | `server/uav/` | `network-manager/uav/` | フライトスケジュール |
| `LinkCapacityManager` | `server/redis/` | `network-manager/redis/` | 容量管理（Redis） |
| `PathWaitingManager` | `server/redis/` | `network-manager/redis/` | PathWaiting管理 |
| `FlightDataRecorder` | `server/uav/` | `network-manager/recorder/` | 飛行データ記録 |

---

## Step 3: Redis pub/sub インターフェースへの置き換え

### 現在の直接呼び出し → pub/sub への変換

**現在（直接呼び出し）**:
```java
// ServerController.run_EPS() から直接 FlightScheduler を操作
flightScheduler.scheduleUAVJob(uavJob);
```

**変更後（pub/sub 経由）**:
```java
// Operator 側: Redisにメッセージをパブリッシュ
redisPublisher.publish("flight:submit", uavJobJson);

// NetworkManager 側: Redisからサブスクライブ
redisSubscriber.subscribe("flight:submit", message -> {
    UAVJob job = deserialize(message);
    flightScheduler.scheduleUAVJob(job);
});
```

### pub/sub チャンネル仕様

#### `flight:submit` (Operator → NetworkManager)

```json
{
  "clientId": "client_001",
  "sessionId": "abc12345",
  "uavId": 42,
  "path": [0, 3, 7, 12],
  "requiredAt": 1712345678000
}
```

#### `network:topology` (Operator → NetworkManager)

```json
{
  "sessionId": "abc12345",
  "nodeCount": 20,
  "links": [
    { "from": 0, "to": 1, "capacity": 5.0, "length": 100.0 },
    ...
  ]
}
```

#### `network:capacity_update` (NetworkManager → Operator)

```json
{
  "sessionId": "abc12345",
  "from": 0,
  "to": 1,
  "currentCapacity": 3.0,
  "delta": -2
}
```

**Operator の使用用途**: 経路探索時の容量チェック（`LinkCapacityManager.syncCapacitiesToMemory()` の代替）

#### `flight:completed` (NetworkManager → Operator)

```json
{
  "sessionId": "abc12345",
  "clientId": "client_001",
  "uavId": 42,
  "completedAt": 1712345999000,
  "flightTimeMs": 321000
}
```

**Operator の使用用途**: 統計記録 (`FlightDataRecorder` 相当処理)

---

## PathWaiting の扱い

PathWaiting（PG-EPS の UAV 待機メカニズム）は**NetworkManager 側に完全に残す**。

**根拠**:
- PathWaiting はリンク容量が回復するまで UAV を待機させる飛行制御の一部
- 容量の消費・回復をリアルタイム監視するのは NetworkManager の責務
- Operator 側は「いつ飛んだか」という結果だけを `flight:completed` で受け取れば十分

**関連クラス（NetworkManager 側に残る）**:
- `PathWaitingManager` (Redis RDeque ベース)
- `FlightScheduler.processFirstHopPathCopy()`

---

## 分離後の OSS 公開方針

**公開対象（NetworkManager）**:
- 仮想 UAV ネットワーク飛行シミュレーション環境
- UAV ジョブキュー（Redis ベース）
- リンク容量管理（Lua Atomic スクリプト）
- PathWaiting メカニズム

**非公開（Operator）**:
- PG-EPS などの経路探索アルゴリズム（研究成果）
- 実験設定・評価スクリプト

**インターフェース仕様のみ公開**:
- pub/sub チャンネル定義（本ドキュメントの内容）
- メッセージスキーマ（JSON Schema）

---

## 実装優先順位

```
Priority 1 (必須・Step 1 に含む)
  └─ MEMORYモード削除（別ドキュメント参照）

Priority 2 (Step 2)
  └─ パッケージ分割（クラス移動のみ、動作変更なし）
  └─ 依存方向の整理（operator → shared は OK、network-manager → operator は NG）

Priority 3 (Step 3)
  └─ FlightScheduler への直接呼び出し → pub/sub 置き換え
  └─ 容量同期 → capacity_update チャンネル受信に変更

Priority 4 (別リポジトリ化)
  └─ network-manager/ を独立リポジトリへ
  └─ shared/ を Maven/Gradle 共有ライブラリへ
```
