# Phase 1 実装議事録

**日付**: 2025-12-28
**フェーズ**: Phase 1 - 二重書き込みパターンによるデータ整合性検証
**目的**: メモリとRedisの両方にUAV状態を書き込み、定期的に整合性を検証する

---

## 1. Phase 1の概要

Phase 1は、Redis移行の第一段階として「二重書き込み（Dual Write）パターン」を実装しました。

### 二重書き込みパターンとは？

```
UAV状態変更
    ↓
メモリに書き込み（既存）
    ↓
Redisにも書き込み（新規）← Phase 1で追加
    ↓
定期的に整合性チェック ← Phase 1で追加
```

このパターンにより：
- **既存機能への影響ゼロ**: メモリベースの処理は変更なし
- **安全な検証**: Redisが失敗してもシミュレータは継続動作
- **整合性の確認**: 5回に1回、メモリとRedisの状態が一致しているか自動チェック

---

## 2. 実装内容の詳細

### 2.1 作成した新規クラス

#### **src/server/redis/UAVStateManager.java**

**目的**: UAV状態をRedisのHash構造で管理

**主な機能**:
- `saveUAVState(Uav uav)`: UAV状態をRedisに保存
- `getUAVState(int uavId)`: RedisからUAV状態を取得
- `deleteUAVState(int uavId)`: RedisからUAV状態を削除

**保存されるデータ**:
```java
// 基本情報
uavId: UAV ID
clientId: クライアントID
speed: 速度

// 状態情報
status: "flying" | "waiting" | "idle"
x, y: 座標

// リンク情報
currentLinkFrom: 現在のリンク始点
currentLinkTo: 現在のリンク終点

// 待機情報
stayedBeaconId: 待機中のビーコンID

// 経路情報
path: "0-1-4-5" （ハイフン区切り）

// 時間情報
flightTime: 飛行時間（ミリ秒）
waitingTime: 待機時間（ミリ秒）
lastUpdateTime: 最終更新時刻（ミリ秒）
```

**Redis上のキー構造**:
```
uav:0 → Hash {uavId: 0, status: "flying", ...}
uav:1 → Hash {uavId: 1, status: "waiting", ...}
uav:2 → Hash {uavId: 2, status: "flying", ...}
```

**フェイルセーフ機能**:
```java
if (!redisEnabled) {
    return; // Redis接続失敗時は何もしない
}

try {
    // Redis操作
} catch (Exception e) {
    LogManager.getInstance().error("Redis書き込み失敗", e);
    // 例外を投げずに継続
}
```

---

#### **src/server/redis/UAVStateValidator.java**

**目的**: メモリとRedisのUAV状態を比較して整合性を検証

**主な機能**:
- `validateUAVState(Uav uav)`: 単一UAVの整合性チェック
- `validateField()`: フィールド値の比較（Integer vs Long対応）
- `validateDoubleField()`: Double型フィールドの比較（誤差許容）

**検証項目**:
| 項目 | 比較方法 | 許容誤差 |
|------|----------|----------|
| uavId, clientId | 完全一致 | なし |
| status | 完全一致 | なし |
| speed | Double比較 | 0.001 |
| currentLinkFrom, currentLinkTo | 完全一致 | なし |
| stayedBeaconId | 完全一致 | なし |
| flightTime | 時間差 | 5000ms |

**検証結果の出力**:
```
整合性チェック: すべて正常 (40機)
整合性チェック: 3件の不一致を検出 (正常: 37, 不一致: 3)
```

**不一致検出時のログ例**:
```
不一致: UAV 10 status - メモリ: waiting, Redis: flying
不一致: UAV 12 currentLinkFrom - メモリ: 0, Redis: 2
警告: UAV 15 がRedisに存在しません
```

---

### 2.2 修正した既存クラス

#### **src/server/uav/UAVFlightController.java**

**修正内容**: UAV状態変更の全ての重要ポイントでRedis保存を追加

**追加箇所1**: メソッド開始時（全UAVの初期保存）
```java
// Phase 1: 飛行中のUAVの状態をRedisに保存（更新前）
for (Uav uav : flyingUavQueue) {
    try {
        uavStateManager.saveUAVState(uav);
    } catch (Exception e) {
        LogManager.getInstance().error("Redis書き込み失敗（飛行中UAV更新前）", e);
    }
}

// Phase 1: 待機中のUAVの状態をRedisに保存（更新前）
for (Uav uav : uavQueue) {
    try {
        uavStateManager.saveUAVState(uav);
    } catch (Exception e) {
        LogManager.getInstance().error("Redis書き込み失敗（待機中UAV更新前）", e);
    }
}
```

**追加箇所2**: UAV到着時
```java
if (flightDistance >= totalPathDistance) {
    uav.cancelTimer(link, totalPathDistance);
    clientController.getClient(uav.getClientId() - 1).incrementFinishFlyingCounter();
    FlightDataRecorder.saveFlightData(clientController, uav, totalPathDistance);

    // Phase 1: UAV状態をRedisに保存（到着時）
    try {
        uavStateManager.saveUAVState(uav);
    } catch (Exception e) {
        LogManager.getInstance().error("Redis書き込み失敗（UAV到着時）", e);
    }
}
```

**追加箇所3**: リンク移動時
```java
if (link[startNode][endNode].getCapacity() > 0) {
    uav.setFlyingLink(link[startNode][endNode]);
    flyingUAV[startNode][endNode]++;

    LogManager.getInstance().log("client" + uav.getClientId() + " UAV" + uav.getId() + " " + startNode + " → " + endNode + " へ移動");

    // Phase 1: UAV状態をRedisに保存（リンク移動時）
    try {
        uavStateManager.saveUAVState(uav);
    } catch (Exception e) {
        LogManager.getInstance().error("Redis書き込み失敗（リンク移動時）", e);
    }

    flyingUavQueue.add(uav);
}
```

**追加箇所4**: 待機状態突入時
```java
uav.stopTimer();
uav.startWaitingTimer();
uav.setStayedBeaconId(startNode);
beaconCluster.getBeacon(startNode).addUav(uav);
beaconCluster.getBeacon(startNode).incrementWaitingUavCount();

// Phase 1: UAV状態をRedisに保存（待機状態）
try {
    uavStateManager.saveUAVState(uav);
} catch (Exception e) {
    LogManager.getInstance().error("Redis書き込み失敗（待機状態）", e);
}

uavQueue.add(uav);
```

**追加箇所5**: 飛行再開時
```java
uav.stopWaitingTimer();
beaconCluster.getBeacon(startNode).removeUav(uav);
beaconCluster.getBeacon(startNode).decrementWaitingUavCount();
uav.startTimer();
uav.setFlyingLink(link[startNode][nextNode]);
uav.setStayedBeaconId(-1);

LogManager.getInstance().log("client" + uav.getClientId() + " UAV" + uav.getId() + " " + startNode + " → " + nextNode + " へ移動");

// Phase 1: UAV状態をRedisに保存（飛行再開時）
try {
    uavStateManager.saveUAVState(uav);
} catch (Exception e) {
    LogManager.getInstance().error("Redis書き込み失敗（飛行再開時）", e);
}

flyingUavQueue.add(uav);
```

**追加箇所6**: 待機継続時（容量不足）
```java
} else{
    // Phase 1: UAV状態をRedisに保存（待機継続時）
    try {
        uavStateManager.saveUAVState(uav);
    } catch (Exception e) {
        LogManager.getInstance().error("Redis書き込み失敗（待機継続時）", e);
    }

    uavQueue.add(uav);
    LogManager.getInstance().log("client" + uav.getClientId() + " UAV" + uav.getId() + " 容量不足のため待機継続 (" + startNode + " -> " + nextNode + ")");
}
```

**追加箇所7**: 待機継続時（リンクなし）
```java
} else {
    // Phase 1: UAV状態をRedisに保存（待機継続時・リンクなし）
    try {
        uavStateManager.saveUAVState(uav);
    } catch (Exception e) {
        LogManager.getInstance().error("Redis書き込み失敗（待機継続時・リンクなし）", e);
    }

    LogManager.getInstance().log("client" + uav.getClientId() + " UAV" + uav.getId() + " 移動できるリンクがないため待機継続");
    uavQueue.add(uav);
}
```

---

#### **src/controller/BoundaryController.java**

**修正内容**: アプリケーション起動時と終了時のRedis接続管理

**追加箇所1**: main()メソッド開始時（Redis接続初期化）
```java
// Phase 1: Redis接続を確立
try {
    RedisConnectionManager redisManager = RedisConnectionManager.getInstance();
    redisManager.connect();
    System.out.println("✓ Redisに接続しました: " + redisManager.getConnectionInfo());
} catch (IOException e) {
    System.err.println("⚠ Redis接続に失敗しました。メモリベースで動作します。");
    LogManager.getInstance().error("Redis接続失敗", e);
}
```

**追加箇所2**: シャットダウンフック（Redis切断）
```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    System.out.println("\nシャットダウン処理を開始します...");

    // Phase 1: Redis接続を切断
    try {
        RedisConnectionManager.getInstance().disconnect();
    } catch (Exception e) {
        System.err.println("Redis切断中にエラーが発生しました: " + e.getMessage());
    }

    System.out.println("シャットダウン処理が完了しました。");
}));
```

---

#### **src/server/uav/UAVFlyScheduler.java**

**修正内容**: 定期的な整合性チェック機能の追加

**追加フィールド**:
```java
// Phase 1: 整合性チェック用のカウンター
private static int updateCounter = 0;
private static final int VALIDATION_INTERVAL = 5; // 5回に1回チェック
private static UAVStateValidator validator = new UAVStateValidator();
```

**スケジューラーへの統合**:
```java
scheduler.scheduleAtFixedRate(() -> {
    try {
        if (flyingUavQueue.isEmpty() && uavQueue.isEmpty()) {
            LogManager.getInstance().log("飛行中UAV, 待機中UAVが存在しません");
            clientController.stopTimer();
            stopFlyUAVUpdates(clientController);
        } else {
            server.controller.ServerController.flyUAV(clientController, flyingUavQueue, uavQueue);

            // Phase 1: 5回に1回、整合性チェック
            updateCounter++;
            if (updateCounter % VALIDATION_INTERVAL == 0) {
                validateAllUAVStates(flyingUavQueue, uavQueue);
            }
        }
    } catch (Exception e) {
        LogManager.getInstance().error("スケジューラー内で例外が発生しましたが, タスクは継続します", e);
    }
}, 0, UPDATE_INTERVAL_SECONDS, TimeUnit.SECONDS);
```

**検証メソッド**:
```java
private static void validateAllUAVStates(Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue) {
    try {
        int totalUavs = flyingUavQueue.size() + uavQueue.size();
        int validCount = 0;
        int invalidCount = 0;

        // 飛行中のUAVをチェック
        for (Uav uav : flyingUavQueue) {
            if (validator.validateUAVState(uav)) {
                validCount++;
            } else {
                invalidCount++;
            }
        }

        // 待機中のUAVをチェック
        for (Uav uav : uavQueue) {
            if (validator.validateUAVState(uav)) {
                validCount++;
            } else {
                invalidCount++;
            }
        }

        // 整合性チェックの結果をログ出力
        if (invalidCount == 0) {
            LogManager.getInstance().log("整合性チェック: すべて正常 (" + totalUavs + "機)");
        } else {
            LogManager.getInstance().log("整合性チェック: " + invalidCount + "件の不一致を検出 (正常: " + validCount + ", 不一致: " + invalidCount + ")");
        }
    } catch (Exception e) {
        LogManager.getInstance().error("整合性チェック中にエラーが発生しました", e);
    }
}
```

---

### 2.3 追加で修正したクラス（バグ修正）

#### **src/server/route/BinaryExtendedPhysarumSolverRouteSearcher.java**

**問題**: PSフロー計算時に台数がずれる（要求10台→実際11台）

**原因**:
```java
// MathUtils.roundWithConservation後
各リンクの小数値合計: 10.4台
Math.round(10.4) = 11台 ← 1台多い！
```

**解決策**: HybridPhysarumSolverRouteSearcherと同様の**PS流量制約の適用**ロジックを追加

**追加コード**:
```java
// PS流量制約の適用（全ネットワークの流量バランスを保持）
double sourceOutflowSum = 0.0;
for (int j = 0; j < node; j++) {
    if (psLink[sourceNode][j].getL_tubeLength() != INF && psLink[sourceNode][j].getQ_tubeFlow() > 0) {
        sourceOutflowSum += psLink[sourceNode][j].getQ_tubeFlow();
    }
}

if (Math.abs(sourceOutflowSum - remainingUAVs) > 0.01) {
    LogManager.getInstance().log("BinaryExtendedPhysarumSolver: PS source outflow " + sourceOutflowSum + " != expected " + remainingUAVs + ". Applying network-wide correction.");

    // 全ネットワークの流量を比例調整
    double correctionFactor = (double)remainingUAVs / sourceOutflowSum;

    // 全てのリンクの流量を同じ比率で調整
    for (int i = 0; i < node; i++) {
        for (int j = 0; j < node; j++) {
            if (psLink[i][j].getL_tubeLength() != INF && psLink[i][j].getQ_tubeFlow() != 0) {
                double correctedFlow = psLink[i][j].getQ_tubeFlow() * correctionFactor;
                psLink[i][j].setQ_tubeFlow(correctedFlow);
            }
        }
    }

    // 修正後に再度roundWithConservation適用
    // ...（ソース流出のみを対象に再調整）
}
```

**結果**:
- 修正前: EPS 30台 + PS 11台 = 41台 → ArrayIndexOutOfBoundsException
- 修正後: EPS 30台 + PS 10台 = 40台 ✓

---

## 3. テスト結果

### 3.1 初回テスト（不整合29件検出）

**実行コマンド**: `make run`

**問題発見**:
```
整合性チェック: 29件の不一致を検出 (正常: 11, 不一致: 29)
```

**不一致の内訳**:
1. Redisに存在しないUAV: 8件（UAV 11, 13, 14, 17-23）
2. リンク情報の不一致: 12件
3. 待機状態の不一致: 9件

**原因分析**:
- **原因1**: メソッド開始時の初期保存が不足
- **原因2**: 待機継続時のRedis保存が欠落（2箇所）

### 3.2 修正後のテスト（不整合0件）

**追加修正**:
1. `flyUAV()`メソッド開始時に全UAVをRedisに保存
2. 待機継続時（容量不足）のRedis保存を追加
3. 待機継続時（リンクなし）のRedis保存を追加

**テスト結果**:
```
2025-12-28 14:01:25.135 - 整合性チェック: すべて正常 (40機)
```

✅ **不整合0件を達成！**

### 3.3 BinaryEPS修正後のテスト

**実行前の問題**:
```
BinaryExtendedPhysarumSolver: EPS assigned flow 30.0 out of 40 required UAVs
BinaryExtendedPhysarumSolver: Remaining UAVs to be assigned by PS: 10
Link (0,1): EPS=5.0 + PS=3.0 = 8.0
Link (0,2): EPS=15.0 + PS=3.0 = 18.0
Link (0,3): EPS=10.0 + PS=5.0 = 15.0
合計: 30 + 11 = 41台 ← 1台多い！

ERROR: Index 40 out of bounds for length 40
```

**修正後の結果**:
```
BinaryExtendedPhysarumSolver: Before correction - PS source outflow sum = 11.0 (expected: 10)
BinaryExtendedPhysarumSolver: Applying network-wide correction.
BinaryExtendedPhysarumSolver: Correction factor = 0.9090909...
BinaryExtendedPhysarumSolver: After correction - PS source outflow sum = 10.0 (target: 10)
Link (0,1): EPS=5.0 + PS=3.0 = 8.0
Link (0,2): EPS=15.0 + PS=3.0 = 18.0
Link (0,3): EPS=10.0 + PS=4.0 = 14.0
合計: 30 + 10 = 40台 ✓
```

✅ **ArrayIndexOutOfBoundsException解決！**

---

## 4. Redis Commander での確認

### 4.1 接続方法

ブラウザで以下のURLにアクセス：
```
http://localhost:8081
```

### 4.2 UAVデータの確認手順

1. 左上の検索バーに `uav:*` と入力
2. Enter キーを押す
3. 以下のようなキーが表示される：
   ```
   uav:0
   uav:1
   uav:2
   ...
   uav:39
   ```

4. 任意のキー（例: `uav:0`）をクリック
5. Hash構造のフィールドが表示される：
   ```
   uavId: 0
   clientId: 1
   status: flying
   speed: 185.205
   x: 0.25
   y: 0.75
   currentLinkFrom: 1
   currentLinkTo: 4
   stayedBeaconId: -1
   path: 0-1-4-5
   flightTime: 2000
   waitingTime: 0
   lastUpdateTime: 1735365677102
   ```

### 4.3 整合性チェックの確認

シミュレータのログに以下が表示されることを確認：
```
2025-12-28 14:01:25.135 - 整合性チェック: すべて正常 (40機)
```

10秒ごと（5サイクルごと）に自動チェックが実行されます。

---

## 5. Phase 1で得られた知見

### 5.1 二重書き込みパターンの利点

✅ **既存機能への影響ゼロ**
- メモリベースの処理は一切変更なし
- Redis失敗時もシミュレータは正常動作

✅ **段階的な検証が可能**
- 整合性チェックでデータの正確性を確認
- 問題があれば早期発見・早期修正

✅ **安全なリファクタリング**
- Phase 2（読み取り切り替え）に進む前に十分な検証が可能
- データ損失のリスクなし

### 5.2 実装時の注意点

⚠️ **すべての状態変更点でRedis保存が必要**
- UAV到着時
- リンク移動時
- 待機状態突入時
- 飛行再開時
- **待機継続時も必須**（初回実装で漏れていた）

⚠️ **初期状態の保存も重要**
- 処理開始時に全UAVをRedisに保存
- これがないと「Redisに存在しない」エラーが発生

⚠️ **フロー計算の整合性**
- roundWithConservation後の合計値チェックが重要
- ソースノードからの流出合計が要求値と一致するか検証

### 5.3 Redissonの便利機能

**RMap（Hash）の活用**:
```java
RMap<String, Object> map = client.getMap("uav:0");
map.put("status", "flying");
map.put("speed", 185.2);
map.put("flightTime", 2000L);
```

Redissonが自動的に：
- 型変換を処理（Java型 ↔ Redis型）
- 接続管理を実行
- エラーハンドリングを提供

---

## 6. 次のステップ（Phase 2への準備）

Phase 1が完了したことで、Phase 2に進む準備が整いました。

### Phase 2の目標

**読み取り切り替え（部分的）**:
- メモリからの読み取り → Redisからの読み取りに変更
- まずは統計情報など非クリティカルなデータから開始
- UAVの主要データはまだメモリから読み取り

### Phase 2で実装する機能

1. **読み取り専用のRedisアクセス**
   - UAV統計情報の取得
   - フライトログの取得

2. **パフォーマンス測定**
   - Redis読み取りの速度測定
   - メモリ読み取りとの比較

3. **段階的な切り替え**
   - 統計データ → Redis
   - ログデータ → Redis
   - UAVコアデータ → まだメモリ（Phase 3で切り替え）

---

## 7. まとめ

### 達成したこと

✅ UAVStateManager.java作成（Redis書き込み管理）
✅ UAVStateValidator.java作成（整合性検証）
✅ UAVFlightController.java修正（7箇所でRedis保存）
✅ BoundaryController.java修正（Redis接続管理）
✅ UAVFlyScheduler.java修正（定期的な整合性チェック）
✅ 整合性テスト成功（不整合0件）
✅ BinaryEPS修正（PS流量制約の適用）
✅ Makefile作成（簡単な実行コマンド）

### Phase 1の成果

- **データ整合性**: メモリとRedisが常に同期
- **安定性**: Redis失敗時もシミュレータは継続動作
- **検証済み**: 40台のUAVで完全な整合性を確認
- **準備完了**: Phase 2（読み取り切り替え）に進む準備が整った

### 実行コマンド

```bash
# Redisコンテナ起動
make up

# シミュレータ実行
make run

# Redis Commander確認
# ブラウザで http://localhost:8081 を開く

# Redisコンテナ停止
make down
```

---

**Phase 1 完了日**: 2025-12-28
**次のフェーズ**: Phase 2 - 読み取り切り替え（部分的）
