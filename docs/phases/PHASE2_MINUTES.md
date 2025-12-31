# Phase 2 実装議事録：読み取り切り替え（部分的）

**実施日**: 2025-12-28
**担当者**: Claude (Sonnet 4.5)
**ステータス**: ✅ 完了

## 目次
1. [Phase 2の概要](#phase-2の概要)
2. [実装内容](#実装内容)
3. [作成・修正したファイル](#作成修正したファイル)
4. [Redis Key構造](#redis-key構造)
5. [テスト結果](#テスト結果)
6. [Redis Commanderでの確認方法](#redis-commanderでの確認方法)
7. [学んだこと](#学んだこと)
8. [次のステップ](#次のステップ)

---

## Phase 2の概要

### 目的
**非クリティカルなデータをRedisから読み取る機能を追加し、Phase 1の書き込みが正しく機能していることを検証する**

### 背景
Phase 1でUAV状態をRedisに書き込む機能を実装しましたが、統計情報やフライトログはまだメモリ（またはファイル）のみに存在していました。Phase 2では、非クリティカルなデータをRedisに保存し、読み取る機能を追加することで、外部からのシミュレーション状態監視を可能にします。

### スコープ
- ✅ **対象**: 統計情報、フライトログ（読み取り専用、失敗してもシミュレーションに影響しない）
- ❌ **対象外**: UAVコアデータ、リンク容量（クリティカルなデータはPhase 3以降）

### 実装方針
**二重読み取りパターン（Dual Read Pattern）**
1. **書き込み**: 統計情報をメモリ（既存）とRedis（新規）の両方に保存
2. **読み取り**: メモリから計算した統計とRedisから読み取った統計を比較
3. **検証**: 10秒ごとに整合性をチェックし、不整合があればログに出力

---

## 実装内容

### Phase 2-1: 統計情報のRedis保存・読み取り

#### 実装した機能
1. **グローバル統計の保存**
   - 飛行中のUAV数
   - 待機中のUAV数
   - 経過時間（ミリ秒）

2. **クライアント統計の保存**
   - 完了UAV数
   - 要求UAV数
   - 送信元・送信先ビーコンID

3. **ビーコン統計の保存**
   - 各ビーコンの待機UAV数

4. **統計情報の整合性検証**
   - メモリとRedisの統計を比較
   - 10秒ごとに自動検証
   - 不整合があればログに出力

### Phase 2-2: フライトログのRedis保存・読み取り

#### 実装した機能
1. **フライトログの保存**
   - UAV到着時にフライトデータをRedisに保存
   - ファイル（CSV）とRedisの両方に保存
   - 飛行時間、待機時間、速度、距離などの詳細情報

2. **飛行経路の保存**
   - UAVの飛行経路をRedisに保存
   - 経路探索手法（EPS、PS、HYBRID等）も記録

3. **フライトログの読み取り**
   - Redisから個別UAVのログを読み取り
   - クライアント別のログ取得
   - 全UAVのログ一括取得

---

## 作成・修正したファイル

### 新規作成

#### 1. UAVStatisticsManager.java
**パス**: `src/server/redis/UAVStatisticsManager.java`

**役割**: 統計情報をRedisに保存

**主要メソッド**:
```java
// グローバル統計を保存
public void saveGlobalStats(int flyingUavCount, int waitingUavCount, long totalElapsedTime)

// クライアント統計を保存
public void saveClientStats(ClientController clientController)

// ビーコン統計を保存
public void saveBeaconStats(BeaconCluster beaconCluster, int nodeCount)

// 全統計を一括保存
public void saveAllStats(Queue<?> flyingUavQueue, Queue<?> waitingUavQueue,
                        ClientController clientController, BeaconCluster beaconCluster,
                        int nodeCount)
```

**Redis Key構造**:
- `stats:global` → Hash（グローバル統計）
- `stats:client:{clientId}` → Hash（クライアント統計）
- `stats:beacon:{beaconId}` → Hash（ビーコン統計）

**コード例**:
```java
public void saveGlobalStats(int flyingUavCount, int waitingUavCount, long totalElapsedTime) {
    if (!redisEnabled) {
        LogManager.getInstance().log("UAVStatisticsManager: Redis統計機能が無効なため、グローバル統計を保存しません");
        return;
    }

    try {
        String key = "stats:global";
        RMap<String, Object> map = client.getMap(key);

        map.put("flyingUavCount", flyingUavCount);
        map.put("waitingUavCount", waitingUavCount);
        map.put("totalElapsedTime", totalElapsedTime);
        map.put("lastUpdateTime", System.currentTimeMillis());

        LogManager.getInstance().log("Phase 2: グローバル統計をRedisに保存しました (飛行:" + flyingUavCount + ", 待機:" + waitingUavCount + ")");
    } catch (Exception e) {
        LogManager.getInstance().error("グローバル統計保存エラー", e);
    }
}
```

---

#### 2. UAVStatisticsReader.java
**パス**: `src/server/redis/UAVStatisticsReader.java`

**役割**: Redisから統計情報を読み取り、整合性を検証

**主要メソッド**:
```java
// グローバル統計を読み取り
public Map<String, Object> getGlobalStats()

// クライアント統計を読み取り
public Map<String, Object> getClientStats(int clientId)

// 全クライアント統計を読み取り
public Map<Integer, Map<String, Object>> getAllClientStats()

// ビーコン統計を読み取り
public Map<String, Object> getBeaconStats(int beaconId)

// グローバル統計の整合性を検証
public boolean validateGlobalStats(int memoryFlyingCount, int memoryWaitingCount, long memoryElapsedTime)

// クライアント統計の整合性を検証
public boolean validateClientStats(ClientController clientController)

// ビーコン統計の整合性を検証
public boolean validateBeaconStats(BeaconCluster beaconCluster, int nodeCount)

// 全統計の整合性を一括検証
public boolean validateAllStats(int flyingUavCount, int waitingUavCount,
                               ClientController clientController,
                               BeaconCluster beaconCluster, int nodeCount)

// 統計サマリを表示
public void printStatisticsSummary()
```

**整合性検証のロジック**:
```java
public boolean validateGlobalStats(int memoryFlyingCount, int memoryWaitingCount, long memoryElapsedTime) {
    if (!redisEnabled) {
        return true; // Redisが無効の場合は常にtrue
    }

    try {
        Map<String, Object> redisStats = getGlobalStats();

        if (redisStats.isEmpty()) {
            LogManager.getInstance().log("警告: グローバル統計がRedisに存在しません");
            return false;
        }

        int redisFlyingCount = (Integer) redisStats.get("flyingUavCount");
        int redisWaitingCount = (Integer) redisStats.get("waitingUavCount");
        long redisElapsedTime = (Long) redisStats.get("totalElapsedTime");

        boolean isValid = true;

        if (redisFlyingCount != memoryFlyingCount) {
            LogManager.getInstance().log("不整合: 飛行中UAV数 - Memory: " + memoryFlyingCount + ", Redis: " + redisFlyingCount);
            isValid = false;
        }

        if (redisWaitingCount != memoryWaitingCount) {
            LogManager.getInstance().log("不整合: 待機中UAV数 - Memory: " + memoryWaitingCount + ", Redis: " + redisWaitingCount);
            isValid = false;
        }

        // 経過時間は若干のズレを許容（100ミリ秒以内）
        long timeDiff = Math.abs(redisElapsedTime - memoryElapsedTime);
        if (timeDiff > 100) {
            LogManager.getInstance().log("不整合: 経過時間 - Memory: " + memoryElapsedTime + ", Redis: " + redisElapsedTime + " (差分: " + timeDiff + "ms)");
            isValid = false;
        }

        if (isValid) {
            LogManager.getInstance().log("✓ グローバル統計の整合性確認完了（飛行:" + memoryFlyingCount + ", 待機:" + memoryWaitingCount + "）");
        }

        return isValid;
    } catch (Exception e) {
        LogManager.getInstance().error("グローバル統計検証エラー", e);
        return false;
    }
}
```

---

#### 3. FlightLogReader.java
**パス**: `src/server/redis/FlightLogReader.java`

**役割**: Redisからフライトログを読み取り

**主要メソッド**:
```java
// フライトログを読み取り
public Map<String, Object> getFlightLog(int uavId)

// 飛行経路を読み取り
public Map<String, Object> getFlightPath(int uavId)

// 全UAVのフライトログを読み取り
public Map<Integer, Map<String, Object>> getAllFlightLogs()

// 全UAVの飛行経路を読み取り
public Map<Integer, Map<String, Object>> getAllFlightPaths()

// 特定クライアントのフライトログを読み取り
public List<Map<String, Object>> getFlightLogsByClient(int clientId)

// フライトログサマリを表示
public void printFlightLogSummary()
```

**コード例**:
```java
public Map<String, Object> getFlightLog(int uavId) {
    if (!redisEnabled) {
        return new HashMap<>();
    }

    try {
        String key = "flightlog:uav:" + uavId;
        RMap<String, Object> map = client.getMap(key);

        if (map.isEmpty()) {
            return new HashMap<>();
        }

        return new HashMap<>(map.readAllMap());
    } catch (Exception e) {
        LogManager.getInstance().error("フライトログ読み取りエラー: UAV" + uavId, e);
        return new HashMap<>();
    }
}
```

---

### 修正したファイル

#### 4. UAVFlyScheduler.java
**パス**: `src/server/uav/UAVFlyScheduler.java`

**変更内容**:
1. インポートを追加：
   ```java
   import item.BeaconCluster;
   import server.redis.UAVStatisticsManager;
   import server.redis.UAVStatisticsReader;
   ```

2. 統計情報管理用のインスタンスを追加：
   ```java
   // Phase 2: 統計情報管理
   private static UAVStatisticsManager statisticsManager = new UAVStatisticsManager();
   private static UAVStatisticsReader statisticsReader = new UAVStatisticsReader();
   ```

3. `startFlyUAVUpdates()`メソッドのシグネチャ変更：
   ```java
   // 変更前
   public static synchronized void startFlyUAVUpdates(Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, ClientController clientController)

   // 変更後（beaconClusterとnodeCountを追加）
   public static synchronized void startFlyUAVUpdates(Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, ClientController clientController, BeaconCluster beaconCluster, int nodeCount)
   ```

4. 5回に1回の処理に統計保存・検証を追加：
   ```java
   // Phase 1 & Phase 2: 5回に1回、整合性チェックと統計情報保存
   updateCounter++;
   if (updateCounter % VALIDATION_INTERVAL == 0) {
       // Phase 1: UAV状態の整合性チェック
       validateAllUAVStates(flyingUavQueue, uavQueue);

       // Phase 2: 統計情報をRedisに保存
       saveStatistics(flyingUavQueue, uavQueue, clientController, beaconCluster, nodeCount);

       // Phase 2: 統計情報の整合性検証
       validateStatistics(flyingUavQueue, uavQueue, clientController, beaconCluster, nodeCount);
   }
   ```

5. 統計保存メソッドを追加：
   ```java
   private static void saveStatistics(Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue,
                                     ClientController clientController, BeaconCluster beaconCluster,
                                     int nodeCount) {
       try {
           LogManager.getInstance().log("Phase 2: saveStatistics()が呼ばれました");
           statisticsManager.saveAllStats(flyingUavQueue, uavQueue, clientController, beaconCluster, nodeCount);
       } catch (Exception e) {
           LogManager.getInstance().error("統計情報保存中にエラーが発生しました", e);
       }
   }
   ```

6. 統計検証メソッドを追加：
   ```java
   private static void validateStatistics(Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue,
                                         ClientController clientController, BeaconCluster beaconCluster,
                                         int nodeCount) {
       try {
           int flyingCount = flyingUavQueue.size();
           int waitingCount = uavQueue.size();
           statisticsReader.validateAllStats(flyingCount, waitingCount, clientController, beaconCluster, nodeCount);
       } catch (Exception e) {
           LogManager.getInstance().error("統計情報検証中にエラーが発生しました", e);
       }
   }
   ```

---

#### 5. BoundaryController.java
**パス**: `src/controller/BoundaryController.java`

**変更内容**:
`startFlyUAVUpdates()`の呼び出しに`beaconCluster`と`nodeNum`を追加：
```java
// 変更前
UAVFlyScheduler.startFlyUAVUpdates(flyingUavQueue, uavQueue, clientController);

// 変更後（429行目）
// UAVスケジューリングを更新（Phase 2: beaconClusterとnodeNumを渡す）
UAVFlyScheduler.startFlyUAVUpdates(flyingUavQueue, uavQueue, clientController, beaconCluster, nodeNum);
```

---

#### 6. ServerController.java
**パス**: `src/server/controller/ServerController.java`

**変更内容**:
5箇所の`startFlyUAVUpdates()`呼び出しに`beaconCluster`と`node`を追加：
```java
// 変更前
if (runCounter != 0) {
    // UAVFlySchedulerを開始
    UAVFlyScheduler.startFlyUAVUpdates(flyingUavQueue, uavQueue, clientController);
}

// 変更後（206, 236, 266, 296, 326行目）
if (runCounter != 0) {
    // UAVFlySchedulerを開始（Phase 2: beaconClusterとnodeを渡す）
    UAVFlyScheduler.startFlyUAVUpdates(flyingUavQueue, uavQueue, clientController, beaconCluster, node);
}
```

---

#### 7. FlightDataRecorder.java
**パス**: `src/server/uav/FlightDataRecorder.java`

**変更内容**:
1. インポートを追加：
   ```java
   import org.redisson.api.RMap;
   import org.redisson.api.RedissonClient;
   import server.redis.RedisConnectionManager;
   import server.util.LogManager;
   ```

2. Redis機能の初期化を追加：
   ```java
   // Phase 2: Redis機能
   private static boolean redisEnabled = false;
   private static RedissonClient client;

   static {
       try {
           RedisConnectionManager connectionManager = RedisConnectionManager.getInstance();
           if (connectionManager.isConnected()) {
               client = connectionManager.getClient();
               redisEnabled = true;
               LogManager.getInstance().log("FlightDataRecorder: Redis保存機能が有効化されました");
           }
       } catch (Exception e) {
           LogManager.getInstance().error("FlightDataRecorder初期化エラー", e);
       }
   }
   ```

3. `recordRoute()`メソッドにRedis保存を追加：
   ```java
   public static void recordRoute(Uav currentUAV, String method) {
       // ファイルに保存（既存の処理）
       // ...

       // Phase 2: Redisに保存
       saveRouteToRedis(currentUAV, method);
   }

   private static void saveRouteToRedis(Uav uav, String method) {
       if (!redisEnabled) {
           return;
       }

       try {
           String key = "flightpath:uav:" + uav.getId();
           RMap<String, Object> map = client.getMap(key);

           String pathString = Arrays.stream(uav.getPath()).mapToObj(String::valueOf).collect(Collectors.joining("-"));

           map.put("clientId", uav.getClientId());
           map.put("uavId", uav.getId());
           map.put("flightPath", pathString);
           map.put("method", method);
           map.put("savedTime", System.currentTimeMillis());

           LogManager.getInstance().log("Phase 2: UAV" + uav.getId() + " の飛行経路をRedisに保存しました");
       } catch (Exception e) {
           LogManager.getInstance().error("飛行経路Redis保存エラー", e);
       }
   }
   ```

4. `saveFlightData()`メソッドにRedis保存を追加：
   ```java
   public static void saveFlightData(ClientController clientController, Uav uav, double totalPathDistance) {
       // ファイルに保存（既存の処理）
       // ...

       // Phase 2: Redisに保存
       saveFlightDataToRedis(clientController, uav, totalPathDistance);
   }

   private static void saveFlightDataToRedis(ClientController clientController, Uav uav, double totalPathDistance) {
       if (!redisEnabled) {
           return;
       }

       try {
           String key = "flightlog:uav:" + uav.getId();
           RMap<String, Object> map = client.getMap(key);

           long flightTime = clientController.getFlightTime();
           long UAV_flightTime = uav.getFlightTime();
           long UAV_waitingTime = uav.getWaitingTime();
           String pathString = Arrays.stream(uav.getPath()).mapToObj(String::valueOf).collect(Collectors.joining("-"));

           map.put("sourceBeaconId", uav.getSource().getId());
           map.put("destinationBeaconId", uav.getDistination().getId());
           map.put("passedTime", flightTime);
           map.put("uavFlightTime", UAV_flightTime);
           map.put("uavWaitingTime", UAV_waitingTime);
           map.put("clientId", uav.getClientId());
           map.put("uavId", uav.getId());
           map.put("speed", uav.getSpeed());
           map.put("distance", totalPathDistance);
           map.put("path", pathString);
           map.put("savedTime", System.currentTimeMillis());

           LogManager.getInstance().log("Phase 2: UAV" + uav.getId() + " のフライトデータをRedisに保存しました");
       } catch (Exception e) {
           LogManager.getInstance().error("フライトデータRedis保存エラー", e);
       }
   }
   ```

---

## Redis Key構造

### 統計情報

#### 1. stats:global（グローバル統計）
**Type**: Hash

**Fields**:
| Field | Type | 説明 | 例 |
|-------|------|------|-----|
| flyingUavCount | Integer | 飛行中のUAV数 | 15 |
| waitingUavCount | Integer | 待機中のUAV数 | 5 |
| totalElapsedTime | Long | 経過時間（ミリ秒） | 45000 |
| lastUpdateTime | Long | 最終更新時刻（Unixタイムスタンプ） | 1735368000000 |

**用途**: シミュレーション全体の状態をリアルタイムで監視

---

#### 2. stats:client:{clientId}（クライアント統計）
**Type**: Hash

**Fields**:
| Field | Type | 説明 | 例 |
|-------|------|------|-----|
| clientId | Integer | クライアントID | 1 |
| finishedUavCount | Integer | 完了したUAV数 | 32 |
| totalRequestedUavs | Integer | 要求したUAV総数 | 40 |
| sourceBeaconId | Integer | 送信元ビーコンID | 0 |
| destinationBeaconId | Integer | 送信先ビーコンID | 5 |
| lastUpdateTime | Long | 最終更新時刻 | 1735368000000 |

**用途**: クライアント別の進捗状況を監視（例: 32/40機完了）

---

#### 3. stats:beacon:{beaconId}（ビーコン統計）
**Type**: Hash

**Fields**:
| Field | Type | 説明 | 例 |
|-------|------|------|-----|
| beaconId | Integer | ビーコンID | 2 |
| waitingUavCount | Integer | 待機中のUAV数 | 3 |
| lastUpdateTime | Long | 最終更新時刻 | 1735368000000 |

**用途**: どのビーコンで渋滞が発生しているか監視

---

### フライトログ

#### 4. flightlog:uav:{uavId}（フライトログ）
**Type**: Hash

**Fields**:
| Field | Type | 説明 | 例 |
|-------|------|------|-----|
| sourceBeaconId | Integer | 送信元ビーコンID | 0 |
| destinationBeaconId | Integer | 送信先ビーコンID | 5 |
| passedTime | Long | 経過時間（ミリ秒） | 78 |
| uavFlightTime | Long | UAVの飛行時間（秒） | 104 |
| uavWaitingTime | Long | UAVの待機時間（秒） | 0 |
| clientId | Integer | クライアントID | 1 |
| uavId | Integer | UAV ID | 12 |
| speed | Double | UAVの速度 | 14.481850187723758 |
| distance | Double | 飛行距離 | 1500.0 |
| path | String | 飛行経路 | "0-2-5" |
| savedTime | Long | 保存時刻 | 1766906205111 |

**用途**: 各UAVの詳細な飛行実績を記録・分析

**実際のRedis Commanderでの表示例**:
```
Key: flightlog:uav:12
TTL: -1
Type: Hash
Field    Value
"sourceBeaconId"    0
"destinationBeaconId"    5
"passedTime"    ["java.lang.Long",78]
"uavFlightTime"    ["java.lang.Long",104]
"uavWaitingTime"    ["java.lang.Long",0]
"clientId"    1
"uavId"    12
"speed"    14.481850187723758
"distance"    1500.0
"path"    "0-2-5"
"savedTime"    ["java.lang.Long",1766906205111]
```

---

#### 5. flightpath:uav:{uavId}（飛行経路）
**Type**: Hash

**Fields**:
| Field | Type | 説明 | 例 |
|-------|------|------|-----|
| clientId | Integer | クライアントID | 1 |
| uavId | Integer | UAV ID | 0 |
| flightPath | String | 飛行経路 | "0-1-4-5" |
| method | String | 経路探索手法 | "EPS" |
| savedTime | Long | 保存時刻 | 1735368000000 |

**用途**: どの経路探索手法でどの経路を選択したか記録

---

## テスト結果

### 統計情報の整合性検証

#### テスト環境
- **クライアント数**: 3
- **UAV総数**: 60機（client1: 40機、client2: 10機、client3: 10機）
- **ノード数**: 6
- **経路探索手法**: EPS
- **検証頻度**: 10秒ごと（5回に1回）

#### テスト結果
✅ **全ての整合性チェックで不整合なし**

**ログ出力例**:
```
Phase 2: saveStatistics()が呼ばれました
Phase 2: 統計情報の一括保存を開始します
Phase 2: グローバル統計をRedisに保存しました (飛行:15, 待機:5)
Phase 2: 統計情報の一括保存が完了しました
=== Phase 2: 統計情報の整合性検証開始 ===
✓ グローバル統計の整合性確認完了（飛行:15, 待機:5）
✓ client1 統計整合性確認完了（完了UAV: 32）
✓ client2 統計整合性確認完了（完了UAV: 8）
✓ client3 統計整合性確認完了（完了UAV: 6）
✓ 全ビーコン統計の整合性確認完了
=== Phase 2: 統計情報の整合性検証完了（不整合なし） ===
```

#### 検証項目
1. ✅ **グローバル統計**: 飛行中UAV数、待機中UAV数、経過時間
2. ✅ **クライアント統計**: 完了UAV数（client1, 2, 3）
3. ✅ **ビーコン統計**: 各ビーコンの待機UAV数

---

### フライトログの保存確認

#### テスト結果
✅ **40機全てのフライトログが正常に保存**

**Redis Commanderでの確認**:
- `flightlog:uav:*`: 40件
- `flightpath:uav:*`: 40件

**サンプルデータ**（UAV 12）:
```json
{
  "sourceBeaconId": 0,
  "destinationBeaconId": 5,
  "passedTime": 78,
  "uavFlightTime": 104,
  "uavWaitingTime": 0,
  "clientId": 1,
  "uavId": 12,
  "speed": 14.481850187723758,
  "distance": 1500.0,
  "path": "0-2-5",
  "savedTime": 1766906205111
}
```

**飛行時間の検証**:
- 理論飛行時間: 1500.0 / 14.48 ≈ **103.5秒**
- 実測飛行時間: **104秒**
- ✅ **理論値と一致！**

---

## Redis Commanderでの確認方法

### 1. Redis Commanderを起動
```bash
# Docker経由でRedisを起動
make up

# ブラウザでアクセス
open http://localhost:8081
```

### 2. 統計情報の確認

#### グローバル統計
1. 検索ボックスに `stats:global` と入力
2. キーをクリック
3. 以下のフィールドを確認：
   - `flyingUavCount`: 飛行中のUAV数
   - `waitingUavCount`: 待機中のUAV数
   - `totalElapsedTime`: 経過時間（ミリ秒）

#### クライアント統計
1. 検索ボックスに `stats:client:*` と入力
2. `stats:client:1`、`stats:client:2` などをクリック
3. 以下のフィールドを確認：
   - `finishedUavCount`: 完了UAV数
   - `totalRequestedUavs`: 要求UAV数
   - 進捗状況: `finishedUavCount / totalRequestedUavs`

#### ビーコン統計
1. 検索ボックスに `stats:beacon:*` と入力
2. `stats:beacon:0`、`stats:beacon:1` などをクリック
3. `waitingUavCount`を確認して渋滞箇所を特定

### 3. フライトログの確認

#### 個別UAVのログ
1. 検索ボックスに `flightlog:uav:*` と入力
2. `flightlog:uav:0`、`flightlog:uav:1` などをクリック
3. 飛行実績を確認：
   - `uavFlightTime`: 飛行時間（秒）
   - `uavWaitingTime`: 待機時間（秒）
   - `speed`: 速度
   - `distance`: 飛行距離
   - `path`: 飛行経路

#### 飛行経路
1. 検索ボックスに `flightpath:uav:*` と入力
2. `flightpath:uav:0` などをクリック
3. 経路情報を確認：
   - `flightPath`: 経路（例: "0-1-4-5"）
   - `method`: 経路探索手法（例: "EPS"）

### 4. redis-cliでの確認

```bash
# グローバル統計を取得
redis-cli HGETALL stats:global

# クライアント1の統計を取得
redis-cli HGETALL stats:client:1

# UAV 12のフライトログを取得
redis-cli HGETALL flightlog:uav:12

# 全てのフライトログキーを表示
redis-cli KEYS "flightlog:uav:*"

# 全ての統計キーを表示
redis-cli KEYS "stats:*"
```

---

## 学んだこと

### 1. 二重読み取りパターンの有効性
**Phase 1**: 二重書き込み（メモリ + Redis）
**Phase 2**: 二重読み取り（メモリ vs Redis）

この組み合わせにより、以下が実現できました：
- ✅ メモリベースの高速処理を維持
- ✅ Redisへの保存で永続化と外部アクセスを実現
- ✅ 定期的な整合性検証でデータの信頼性を確保

### 2. 非クリティカルデータの段階的移行
Phase 2では**非クリティカルなデータ**のみを対象にしました：
- 統計情報（失敗してもシミュレーション継続可能）
- フライトログ（記録用、シミュレーションには影響しない）

これにより、以下のリスクを回避：
- ❌ クリティカルなUAV状態やリンク容量の読み取りエラー
- ❌ シミュレーション停止のリスク

### 3. 整合性検証の重要性
10秒ごとの自動整合性検証により、以下が可能になりました：
- ✅ リアルタイムでデータの正確性を確認
- ✅ 不整合が発生した場合の早期発見
- ✅ Redis保存ロジックのデバッグが容易

### 4. JsonJacksonCodecの型情報保持
`JsonJacksonCodec`を使用することで：
- ✅ Java型情報が保持される（Long, Integer, Double等）
- ✅ Java側での読み取りが型安全
- ⚠️ Redis Commanderでの表示が`["java.lang.Long",104]`となる

ただし、実用上は問題なし：
- 実際の値（104）は判別可能
- Java側からの読み取りは型安全で便利

### 5. 段階的なメソッドシグネチャ変更
`startFlyUAVUpdates()`に`beaconCluster`と`nodeCount`を追加する際：
- ✅ 全ての呼び出し箇所を一括修正（6箇所）
- ✅ コンパイルエラーで漏れを防止
- ✅ 後方互換性よりも明示的な依存関係を優先

---

## 次のステップ

### Phase 3: リンク容量のRedis移行（予定）

Phase 2で非クリティカルなデータのRedis化が完了したため、次はクリティカルなデータの移行を検討します。

#### 予定される実装内容
1. **リンク容量の読み取り切り替え**
   - メモリからRedisへの段階的な移行
   - 二重読み取りパターンで整合性検証
   - フォールバック機構（Redis失敗時はメモリから読み取り）

2. **UAVコアデータの読み取り最適化**
   - Phase 1で書き込んだUAV状態の活用
   - メモリ vs Redisの性能比較

3. **パフォーマンスベンチマーク**
   - メモリのみ vs Redis併用の比較
   - レイテンシとスループットの測定

#### Phase 3の課題
- リンク容量はクリティカルなデータのため、読み取りエラーがシミュレーションに影響
- フォールバック機構の実装が必須
- 性能劣化の許容範囲を決定

---

## Phase 2の成果まとめ

| 項目 | Phase 1まで | Phase 2 |
|------|-------------|---------|
| **Redisキー数** | 40 (uav:*のみ) | **90+** (uav, stats, flightlog, flightpath) |
| **監視可能データ** | UAV個別状態のみ | UAV + 統計 + ログ |
| **外部アクセス** | 不可 | **可能（Redis API経由）** |
| **整合性検証** | UAV状態のみ | **UAV + 統計情報** |
| **リアルタイム性** | なし | **10秒ごとに更新** |
| **分析機能** | ファイル解析のみ | **Redis + ファイル両方** |

### 新規作成ファイル
- ✅ `UAVStatisticsManager.java`（統計保存）
- ✅ `UAVStatisticsReader.java`（統計読み取り・検証）
- ✅ `FlightLogReader.java`（ログ読み取り）

### 修正ファイル
- ✅ `UAVFlyScheduler.java`（統計保存・検証の統合）
- ✅ `BoundaryController.java`（引数追加）
- ✅ `ServerController.java`（引数追加）
- ✅ `FlightDataRecorder.java`（Redis保存追加）

### 整合性検証結果
- ✅ **Phase 1**: UAV状態 - 不整合なし
- ✅ **Phase 2**: 統計情報 - 不整合なし
- ✅ **Phase 2**: フライトログ - 40機全て正常保存

---

**Phase 2完了日**: 2025-12-28
**次の作業**: Phase 3の設計とREFACTORING_PLAN.mdの更新
