# Step 3: Redis pub/sub化 実装計画

## 方針

**ライフサイクル管理（変更なし）**: BoundaryController → AsyncUAVWorker/FlightScheduler の起動・停止は同一JVMなので直接呼び出しのまま（Step4プロセス分離時に対応）。

**ランタイム通信（Redis化対象）**: シミュレーション実行中の cross-boundary 直接参照をゼロにする。

---

## Phase A: データクラスを shared/ へ移動（ゼロロジック変更）

すべて Redis バックエンドのクラス。複数インスタンスを生成しても Redis 上の同一データを参照するため整合性は保たれる。

| 移動元 | 移動先 |
|---|---|
| `network_manager/redis/UAVJob.java` | `shared/item/UAVJob.java` |
| `network_manager/redis/UAVJobQueue.java` | `shared/redis/UAVJobQueue.java` |
| `network_manager/redis/PathWaitingManager.java` | `shared/redis/PathWaitingManager.java` |
| `network_manager/redis/LinkCapacityManager.java` | `shared/redis/LinkCapacityManager.java` |

**A3 の追加修正**: StepControlledPGEPS / BisectionalPGEPS / StatisticalSimulationController が
`FlightScheduler.getInstance().getPathWaitingManager()` 経由で取得していた PathWaitingManager を、
直接インスタンス化に変更し FlightScheduler import を除去する。

```java
// 変更前
PathWaitingManager pwm = FlightScheduler.getInstance().getPathWaitingManager();
// 変更後
PathWaitingManager pwm = new PathWaitingManager(RedisConnectionManager.getInstance().getClient());
```

→ **make compile 確認**

---

## Phase B: 逆方向依存の除去（trivial）

`FlightScheduler.onMidFlightWaiting()` 内の `statController.logDiagnostic(...)` を
`LogManager.getInstance().log(...)` に置き換え、`import operator.scheduler.StatisticalSimulationController` を削除。

→ **make compile 確認**

---

## Phase C: FlightScheduler 直接参照を Redis pub/sub に置き換え

### 新規 Redis リソース

| リソース | 型 | Publisher | Subscriber | 用途 |
|---|---|---|---|---|
| `"flight:all_completed"` | RTopic\<String\> | FlightScheduler | PhaseController | 全UAV完了通知 |
| `"flight:status:{sessionId}"` | RMap\<String,Integer\> | FlightScheduler | PhaseController | 飛行状況ログ用 |
| `"operator:cancel_flight"` | RTopic\<Integer\> | SearcherRetryManager | FlightScheduler | UAVキャンセル命令 |

### C1: PhaseController → FlightScheduler 置き換え

**FlightScheduler 側**:
- `isAllFlightsCompleted()` が true になったとき RTopic `"flight:all_completed"` に sessionId を publish
- 飛行状況を Redis Hash `"flight:status:{sessionId}"` に書き込む内部メソッドを追加し、適切な箇所で呼び出す

**PhaseController 側**:
- フィールドに `volatile boolean allFlightsCompleted = false` を追加
- 初期化時に RTopic `"flight:all_completed"` を subscribe し、受信時にフラグを true にセット
- PHASE4_RECOVERY ループでフラグを確認（FlightScheduler import を削除）
- getFlightStatus() のログは Redis Hash から読み取る

### C2: SearcherRetryManager → FlightScheduler 置き換え

**FlightScheduler 側**:
- 初期化時に RTopic `"operator:cancel_flight"` を subscribe
- メッセージ受信時に既存の内部メソッド `cancelClientFlights(clientId)` を呼び出す

**SearcherRetryManager 側**:
- `FlightScheduler.getInstance().cancelClientFlights(clientId)` を
  `redisClient.getTopic("operator:cancel_flight").publish(clientId)` に置き換え
- `import network_manager.scheduler.FlightScheduler` を削除

→ **make compile 確認**
→ **統計シミュレーション1回実行して動作確認**

---

## 完了後の cross-boundary import 状態

### operator → network_manager（残留・ライフサイクルのみ）

| ファイル | 残留 import | 理由 |
|---|---|---|
| `BoundaryController` | `FlightScheduler` | setSessionId / resetCounters / resetInstance |
| `BoundaryController` | `AsyncUAVWorker` | ワーカーの生成・start / stop |

### network_manager → operator: **ゼロ（完全除去）**

---

## 注意事項

- Phase A は1ファイルずつ移動・compile 確認を繰り返す（まとめて行わない）
- RTopic の at-most-once を補完するため、PhaseController は受信フラグをチェックしつつ既存の try-catch フォールバックを残す
- cancel pub/sub は同一JVMのため信頼性は十分だが、FlightScheduler 側の subscribe は初期化時に必ず設定すること
