# 段階的リファクタリング計画：Redis + Docker ワーカー化

## 🎯 全体目標

現在のシングルプロセス・同期処理から、**Redis + Docker + 複数ワーカープロセス**による分散非同期処理への移行

---

## 📊 現在のアーキテクチャ

```
┌─────────────────────────────────────────┐
│    Single JVM Process                   │
│                                         │
│  ┌──────────────────────────────┐      │
│  │  BoundaryController (Main)   │      │
│  └──────────┬───────────────────┘      │
│             │                           │
│  ┌──────────▼───────────────────┐      │
│  │  UAVFlyScheduler             │      │
│  │  (ScheduledExecutorService)  │      │
│  │  - 2秒間隔で実行              │      │
│  └──────────┬───────────────────┘      │
│             │                           │
│  ┌──────────▼───────────────────┐      │
│  │  UAVFlightController.flyUAV()│      │
│  │  - 飛行中UAV処理              │      │
│  │  - 待機UAV処理                │      │
│  │  - 容量更新                   │      │
│  └──────────────────────────────┘      │
│                                         │
│  全状態がメモリ上                        │
└─────────────────────────────────────────┘
```

---

## 🎯 目標アーキテクチャ

```
┌──────────────────┐      ┌──────────────┐      ┌──────────────────┐
│ Controller       │      │    Redis     │      │ Worker Process   │
│ (Main Process)   │─────▶│              │◀─────│ (Multiple)       │
│                  │      │ - Job Queue  │      │                  │
│ - 経路探索        │      │ - UAV State  │      │ - UAV移動処理     │
│ - UAV割り当て    │      │ - Link State │      │ - タイマー管理    │
│ - 結果集約        │      │ - Pub/Sub    │      │ - 状態更新        │
└──────────────────┘      └──────────────┘      └──────────────────┘
     ↑                                                    ↓
     └────────────────── 完了通知 ──────────────────────┘
```

---

## 🚀 段階的移行計画

### Phase 0: 準備フェーズ（1-2日）

#### ゴール
- 開発環境の整備
- 依存関係の追加
- 既存コードの理解強化

#### タスク

1. **Docker環境構築**
```yaml
# docker-compose.yml を作成
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
```

2. **Redisson依存関係追加**
```xml
<!-- pom.xml または build.gradle -->
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson</artifactId>
    <version>3.24.3</version>
</dependency>
```

3. **現状の動作テストケース作成**
```java
// 既存機能の振る舞いを記録
@Test
public void testCurrentUAVFlightBehavior() {
    // 1 UAVが2秒ごとに移動することを確認
    // 2 容量超過で待機することを確認
    // 3 目的地到着で停止することを確認
}
```

#### 成果物
- ✅ Docker Composeファイル
- ✅ Redisson設定クラス
- ✅ 既存動作の回帰テスト

#### ロールバック
- なし（準備のみ）

---

### Phase 1: 二重書き込みパターンによるデータ整合性検証（完了✅）

#### ゴール
- **既存機能に影響を与えず**、UAV状態をメモリとRedisの**両方に書き込み**
- 定期的な整合性チェックで二重書き込みの正確性を検証
- 既存のメモリ状態が真実のソース（Source of Truth）
- Redisが失敗してもシミュレータは継続動作

#### アーキテクチャ

```
┌────────────────────┐
│ UAVFlightController│
│  flyUAV()          │
└─────┬──────────────┘
      │
      ├─ [既存] メモリ上で状態更新
      │
      └─ [新規] Redisに状態コピー（非同期）
           ↓
      ┌────────────┐
      │   Redis    │
      │ (読み取り専用)│
      └────────────┘
```

#### 実装

**1. UAVStateManager クラスの作成**
```java
// src/server/redis/UAVStateManager.java
public class UAVStateManager {
    private RedissonClient client;
    private boolean redisEnabled = true;

    public UAVStateManager() {
        // RedisConnectionManagerからクライアントを取得
        this.client = RedisConnectionManager.getInstance().getClient();
    }

    // UAV状態を同期的に書き込み（try-catchでエラーハンドリング）
    public void saveUAVState(Uav uav) {
        if (!redisEnabled) return;

        try {
            String key = "uav:" + uav.getId();
            RMap<String, Object> map = client.getMap(key);

            map.put("uavId", uav.getId());
            map.put("clientId", uav.getClientId());
            map.put("status", getStatusString(uav));
            map.put("speed", uav.getSpeed());
            map.put("x", uav.getX());
            map.put("y", uav.getY());
            // ... その他の状態
            map.put("lastUpdateTime", System.currentTimeMillis());
        } catch (Exception e) {
            LogManager.getInstance().error("UAV状態保存エラー", e);
            // 例外を投げずに継続
        }
    }
}
```

**2. UAVStateValidator クラスの作成（整合性検証）**
```java
// src/server/redis/UAVStateValidator.java
public class UAVStateValidator {
    private UAVStateManager stateManager;

    // メモリとRedisのUAV状態を比較
    public boolean validateUAVState(Uav uav) {
        Map<String, Object> redisState = stateManager.getUAVState(uav.getId());

        if (redisState.isEmpty()) {
            LogManager.getInstance().log("警告: UAV " + uav.getId() + " がRedisに存在しません");
            return false;
        }

        boolean isValid = true;

        // 各フィールドを比較
        if (!validateField(uav.getId(), "status", getStatusString(uav), redisState.get("status"))) {
            isValid = false;
        }
        // ... その他のフィールド検証

        return isValid;
    }
}
```

**3. UAVFlightController への統合（7箇所）**
```java
// src/server/uav/UAVFlightController.java
public static void flyUAV(...) {
    // [新規] メソッド開始時に全UAVをRedisに保存（初期化）
    for (Uav uav : flyingUavQueue) {
        try {
            uavStateManager.saveUAVState(uav);
        } catch (Exception e) {
            LogManager.getInstance().error("Redis書き込み失敗", e);
        }
    }

    // [既存] メモリ上での処理
    for (Uav uav : flyingUavQueue) {
        // ... 既存の処理 ...

        // [新規] 重要なイベントでRedisに同期的に保存
        // - UAV到着時
        // - リンク移動時
        // - 待機状態突入時
        // - 飛行再開時
        // - 待機継続時
        try {
            uavStateManager.saveUAVState(uav);
        } catch (Exception e) {
            LogManager.getInstance().error("Redis書き込み失敗", e);
        }
    }
}
```

**4. UAVFlyScheduler への整合性チェック統合**
```java
// src/server/uav/UAVFlyScheduler.java
private static int updateCounter = 0;
private static final int VALIDATION_INTERVAL = 5; // 5回に1回チェック
private static UAVStateValidator validator = new UAVStateValidator();

scheduler.scheduleAtFixedRate(() -> {
    server.controller.ServerController.flyUAV(...);

    // 5回に1回、整合性チェック
    updateCounter++;
    if (updateCounter % VALIDATION_INTERVAL == 0) {
        validateAllUAVStates(flyingUavQueue, uavQueue);
    }
}, 0, 2, TimeUnit.SECONDS);
```

**3. モニタリングツール**
```bash
# Redis CLIでリアルタイム監視
redis-cli
> KEYS uav:*
> HGETALL uav:1
```

#### テスト結果

**初回テスト**: 29件の不整合を検出
- 原因1: メソッド開始時の初期保存が不足
- 原因2: 待機継続時のRedis保存が欠落

**修正後のテスト**: 不整合0件 ✅
```
2025-12-28 14:01:25.135 - 整合性チェック: すべて正常 (40機)
```

#### 成果物
- ✅ UAVStateManager.java（Redis書き込み管理）
- ✅ UAVStateValidator.java（整合性検証） ← **計画外だが追加**
- ✅ UAVFlightController.java修正（7箇所でRedis保存）
- ✅ BoundaryController.java修正（Redis接続管理）
- ✅ UAVFlyScheduler.java修正（定期的整合性チェック）
- ✅ Makefile作成（簡単な実行コマンド）
- ✅ Redis Commander文字化け修正（JSON形式に変更）

#### ロールバック
```java
// UAVStateManager.redisEnabled = false; で無効化可能
// メモリベース処理は残っているので安全
```

#### 追加で実施した内容（計画外）
- **BinaryExtendedPhysarumSolverRouteSearcher.java修正**: PS流量制約の適用ロジック追加（UAV台数ずれ問題を解決）
- **Redis Commander文字化け修正**: JsonJacksonCodecを設定して読みやすいJSON形式に変更

---

### Phase 2: 読み取り切り替え（部分的）（3-4日） ✅ 完了（2025-12-28）

**注**: 当初の計画では「リンク容量のRedis移行」でしたが、段階的アプローチのため変更しました。

#### ゴール
- ✅ **非クリティカルなデータの読み取りをRedisに切り替え**
- ✅ 統計情報とフライトログをRedisに保存・読み取り
- ✅ UAVの位置情報など重要データはまだメモリから読み取り（Phase 3で対応）
- ✅ 整合性検証とパフォーマンスを確認

#### アーキテクチャ

```
┌────────────────────────┐
│  統計情報・ログ参照     │
│  (新しい読み取りAPI)    │
└─────┬──────────────────┘
      │
      ├─ [既存] メモリから集計（Phase 1まで）
      │
      └─ [新規] Redisから読み取り（Phase 2）
           ↓
      ┌──────────────────┐
      │   Redis          │
      │ - uav:*          │← UAV状態（Phase 1で書き込み済み）
      │ - stats:global   │← グローバル統計
      │ - stats:client:* │← クライアント統計
      │ - stats:beacon:* │← ビーコン統計
      │ - flightlog:uav:*│← フライトログ
      │ - flightpath:uav:*│← 飛行経路
      └──────────────────┘

[重要] UAVのコア処理（位置更新、リンク容量）はまだメモリベース
```

**実装結果**:
- ✅ Redis Keyが40件→90+件に増加
- ✅ 統計情報（グローバル、クライアント、ビーコン）を10秒ごとに保存
- ✅ フライトログ（到着時）とフライトパス（経路決定時）をRedisに保存
- ✅ メモリとRedisの整合性を10秒ごとに検証

#### 実装（実際に作成したクラス）

**Phase 2-1: 統計情報の保存・読み取り**

**1. UAVStatisticsManager.java**（新規作成）
```java
// src/server/redis/UAVStatisticsManager.java
public class UAVStatisticsManager {
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
}
```

**2. UAVStatisticsReader.java**（新規作成）
```java
// src/server/redis/UAVStatisticsReader.java
public class UAVStatisticsReader {
    // グローバル統計を読み取り
    public Map<String, Object> getGlobalStats()

    // クライアント統計を読み取り
    public Map<String, Object> getClientStats(int clientId)
    public Map<Integer, Map<String, Object>> getAllClientStats()

    // ビーコン統計を読み取り
    public Map<String, Object> getBeaconStats(int beaconId)
    public List<Map<String, Object>> getAllBeaconStats(int nodeCount)

    // 整合性検証
    public boolean validateGlobalStats(int memoryFlyingCount, int memoryWaitingCount, long memoryElapsedTime)
    public boolean validateClientStats(ClientController clientController)
    public boolean validateBeaconStats(BeaconCluster beaconCluster, int nodeCount)
    public boolean validateAllStats(...)  // 全統計を一括検証

    // サマリ表示
    public void printStatisticsSummary()
}
```

**Phase 2-2: フライトログの保存・読み取り**

**3. FlightDataRecorder.java**（修正）
- ファイル保存に加えてRedis保存を追加
- `saveRouteToRedis()`: 飛行経路をRedisに保存（flightpath:uav:*）
- `saveFlightDataToRedis()`: フライトログをRedisに保存（flightlog:uav:*）

**4. FlightLogReader.java**（新規作成）
```java
// src/server/redis/FlightLogReader.java
public class FlightLogReader {
    // フライトログを読み取り
    public Map<String, Object> getFlightLog(int uavId)
    public Map<Integer, Map<String, Object>> getAllFlightLogs()
    public List<Map<String, Object>> getFlightLogsByClient(int clientId)

    // 飛行経路を読み取り
    public Map<String, Object> getFlightPath(int uavId)
    public Map<Integer, Map<String, Object>> getAllFlightPaths()

    // サマリ表示
    public void printFlightLogSummary()
}
```

**5. UAVFlyScheduler.java**（修正）
- `startFlyUAVUpdates()`のシグネチャ変更（beaconCluster, nodeCountを追加）
- 5回に1回の処理に統計保存・検証を追加
- `saveStatistics()`, `validateStatistics()`メソッドを追加

**6. 関連ファイルの修正**
- `BoundaryController.java`: startFlyUAVUpdates()呼び出しに引数追加
- `ServerController.java`: startFlyUAVUpdates()の5箇所の呼び出しに引数追加

#### テスト結果

**テスト環境**:
- クライアント数: 3
- UAV総数: 60機（client1: 40機、client2: 10機、client3: 10機）
- ノード数: 6
- 経路探索手法: EPS
- 検証頻度: 10秒ごと（5回に1回）

**統計情報の整合性検証**:
```
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

**フライトログの保存確認**:
- ✅ 40機全てのフライトログが正常に保存（flightlog:uav:*）
- ✅ 40機全ての飛行経路が正常に保存（flightpath:uav:*）
- ✅ 飛行時間の理論値と実測値が一致（例: UAV12で104秒）

**Redis Commander確認**:
- Redis Keyが40件（uav:*のみ）→ **90+件**に増加
- stats:global, stats:client:*, stats:beacon:*, flightlog:uav:*, flightpath:uav:* が全て確認できる

#### 成果物
- ✅ **UAVStatisticsManager.java**（統計保存）
- ✅ **UAVStatisticsReader.java**（統計読み取り・検証）
- ✅ **FlightLogReader.java**（フライトログ読み取り）
- ✅ **FlightDataRecorder.java**（Redis保存機能追加）
- ✅ **UAVFlyScheduler.java**（統計保存・検証統合）
- ✅ **BoundaryController.java, ServerController.java**（引数追加）

#### 成功基準
- ✅ 統計情報がRedisから正しく読み取れる
- ✅ メモリとRedisの結果が一致する（不整合: 0件）
- ✅ フライトログが正常に保存される（40機全て保存）
- ✅ Redis Commanderで全データが確認できる
- ✅ 整合性検証が10秒ごとに自動実行される

#### 詳細ドキュメント
- 📄 [Phase 2議事録](./phases/PHASE2_MINUTES.md) - 実装の詳細、テスト結果、Redis Key構造など

---

### Phase 3: リンク容量Redis移行 + ワーカープロセス導入（5-7日）

**注**: Phase 3は段階的アプローチのため、Phase 3aとPhase 3bに分割して実装します。

---

### Phase 3a: リンク容量Redis移行（2-3日）

#### ゴール
- **リンク容量管理をRedisに移行**
- 飛行中UAV数をRedisで管理
- 容量計算をRedis上で実行
- メモリとRedisの整合性検証

#### 現状の課題

**Phase 2完了時点:**
```
[メインプロセス]
  ├─ UAV状態: ✅ Redis + メモリ（二重書き込み）
  ├─ 統計情報: ✅ Redis
  ├─ フライトログ: ✅ Redis
  └─ リンク容量: ⚠️ メモリのみ（link[][]配列、flyingUAV[][]配列）
```

**問題点:**
- リンク容量がメモリのみで管理されている
- Phase 3bでワーカー化する際に、ワーカー間で容量情報を共有できない
- スケーラビリティのボトルネック

#### アーキテクチャ

**Phase 3a完了後:**
```
[メインプロセス]                    [Redis]
  │                                  │
  ├─ UAV状態 ──────────────────────▶ uav:{id} (Hash)
  │                                  │
  ├─ リンク容量 ────────────────────▶ link:{i}:{j}:capacity (String)
  │   - 初期容量から飛行中UAV分減算    link:{i}:{j}:flying_count (String)
  │   - 2秒ごとに更新                 link:{i}:{j}:init_capacity (String)
  │                                  │
  └─ 統計情報 ──────────────────────▶ stats:* (Hash)
```

#### 実装

**1. リンク容量管理クラス**
```java
// src/server/redis/LinkCapacityManager.java
public class LinkCapacityManager {
    private RedissonClient client;

    /**
     * リンク容量をRedisに保存
     */
    public void saveCapacity(int srcNode, int dstNode, double capacity) {
        String key = "link:" + srcNode + ":" + dstNode + ":capacity";
        client.getBucket(key).set(capacity);
    }

    /**
     * 飛行中UAV数をインクリメント
     */
    public void incrementFlyingCount(int srcNode, int dstNode) {
        String key = "link:" + srcNode + ":" + dstNode + ":flying_count";
        RAtomicLong counter = client.getAtomicLong(key);
        counter.incrementAndGet();
    }

    /**
     * 全リンクの容量を一括更新
     */
    public void updateAllCapacities(Link[][] link, int[][] flyingUAV, int node) {
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY) {
                    // 初期容量をセット
                    double initCapacity = link[i][j].getInitCapacity();
                    String initKey = "link:" + i + ":" + j + ":init_capacity";
                    client.getBucket(initKey).set(initCapacity);

                    // 飛行中UAV数をセット
                    String countKey = "link:" + i + ":" + j + ":flying_count";
                    client.getBucket(countKey).set(flyingUAV[i][j]);

                    // 現在容量を計算して保存
                    double currentCapacity = Math.max(0, initCapacity - flyingUAV[i][j]);
                    saveCapacity(i, j, currentCapacity);
                }
            }
        }
    }
}

// src/server/redis/LinkCapacityReader.java
public class LinkCapacityReader {
    /**
     * リンク容量をRedisから読み取り
     */
    public double getCapacity(int srcNode, int dstNode) {
        String key = "link:" + srcNode + ":" + dstNode + ":capacity";
        RBucket<Double> bucket = client.getBucket(key);
        return bucket.get() != null ? bucket.get() : 0.0;
    }

    /**
     * メモリとRedisの容量を比較して整合性検証
     */
    public boolean validateCapacity(Link[][] link, int node) {
        int mismatchCount = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY) {
                    double memoryCapacity = link[i][j].getCapacity();
                    double redisCapacity = getCapacity(i, j);

                    if (Math.abs(memoryCapacity - redisCapacity) > 0.001) {
                        LogManager.getInstance().log(
                            "容量不整合: link[" + i + "][" + j + "] " +
                            "memory=" + memoryCapacity + ", redis=" + redisCapacity
                        );
                        mismatchCount++;
                    }
                }
            }
        }
        return mismatchCount == 0;
    }
}
```

**2. CapacityManagerの修正**
```java
// src/server/uav/CapacityManager.java
public static void updateCapacity(int[][] flyingUAV, Link[][] link, int node) {
    // Phase 3a: メモリとRedisの両方を更新

    // [既存] メモリで容量更新
    for (int i = 0; i < node; i++) {
        for (int j = 0; j < node; j++) {
            if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY) {
                link[i][j].setCapacity(link[i][j].getInitCapacity());
                link[j][i].setCapacity(link[j][i].getInitCapacity());
            }
        }
    }

    for (int i = 0; i < node; i++) {
        for (int j = 0; j < node; j++) {
            if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY && flyingUAV[i][j] > 0) {
                double newCapacity = link[i][j].getCapacity() - flyingUAV[i][j];
                link[i][j].setCapacity(Math.max(0, newCapacity));
                link[j][i].setCapacity(Math.max(0, newCapacity));
            }
        }
    }

    // [新規] Redisにも保存
    LinkCapacityManager capacityManager = new LinkCapacityManager();
    capacityManager.updateAllCapacities(link, flyingUAV, node);
}
```

**3. 整合性検証の追加**
```java
// src/server/uav/UAVFlyScheduler.java
private static void validateAllUAVStates(Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue) {
    // [既存] UAV状態の整合性チェック
    // ...

    // [新規] リンク容量の整合性チェック
    LinkCapacityReader reader = new LinkCapacityReader();
    boolean isValid = reader.validateCapacity(link, node);
    if (isValid) {
        LogManager.getInstance().log("Phase 3a: リンク容量整合性チェック正常");
    } else {
        LogManager.getInstance().log("Phase 3a: リンク容量に不整合を検出");
    }
}
```

#### 🔒 トランザクション制御とACID特性

**重要:** Phase 3bでワーカー並列処理を導入する際、複数ワーカーが同じリンク容量を更新すると**競合状態（race condition）**が発生します。

##### 競合が発生する具体的なシナリオ

**シナリオ1: リンク容量の更新競合**
```
時刻t=0秒: リンク0→1の容量 = 10

[Worker 1]                          [Worker 2]
  │                                   │
  ├─ UAV 0がリンク0→1を使用開始      │
  ├─ Redis GET link:0:1:capacity     │
  │  → 10を取得                       │
  │                                   ├─ UAV 1がリンク0→1を使用開始
  │                                   ├─ Redis GET link:0:1:capacity
  │                                   │  → 10を取得（まだ更新されていない）
  ├─ 計算: 10 - 1 = 9                │
  │                                   ├─ 計算: 10 - 1 = 9
  ├─ Redis SET link:0:1:capacity 9   │
  │                                   ├─ Redis SET link:0:1:capacity 9

結果: 容量が9になる（正しくは8であるべき）
⚠️ UAV 1のカウントが失われた（Lost Update）
```

**シナリオ2: 容量チェックの競合（オーバーブッキング）**
```
時刻t=0秒: リンク0→1の残容量 = 1

[Worker 1]                          [Worker 2]
  │                                   │
  ├─ UAV 0が容量チェック             │
  ├─ GET link:0:1:capacity           │
  │  → 1（空きあり）                  │
  │                                   ├─ UAV 1が容量チェック
  │                                   ├─ GET link:0:1:capacity
  │                                   │  → 1（空きあり）
  ├─ UAV 0を投入                     │
  ├─ SET capacity = 0                │
  │                                   ├─ UAV 1を投入
  │                                   ├─ SET capacity = -1 ⚠️

結果: 容量が-1になる（オーバーブッキング）
⚠️ リンク容量制約が破綻
```

##### 解決策: Redisのアトミック操作

**Phase 3aでの実装方針:**

**1. アトミックカウンター（推奨）**
```java
// src/server/redis/LinkCapacityManager.java
public class LinkCapacityManager {
    private RedissonClient client;

    /**
     * 飛行中UAV数をアトミックにインクリメント
     * ACID特性のA（Atomicity）を保証
     */
    public long incrementFlyingCount(int srcNode, int dstNode) {
        String key = "link:" + srcNode + ":" + dstNode + ":flying_count";
        RAtomicLong counter = client.getAtomicLong(key);
        return counter.incrementAndGet();  // Redisがアトミック性を保証
    }

    /**
     * 飛行中UAV数をアトミックにデクリメント
     */
    public long decrementFlyingCount(int srcNode, int dstNode) {
        String key = "link:" + srcNode + ":" + dstNode + ":flying_count";
        RAtomicLong counter = client.getAtomicLong(key);
        return counter.decrementAndGet();  // Redisがアトミック性を保証
    }

    /**
     * リンク容量を計算して保存
     * flying_countは既にアトミックに更新済みなので、
     * 容量計算は競合しない
     */
    public void updateCapacity(int srcNode, int dstNode) {
        // 初期容量を取得
        String initKey = "link:" + srcNode + ":" + dstNode + ":init_capacity";
        RBucket<Double> initBucket = client.getBucket(initKey);
        double initCapacity = initBucket.get();

        // 飛行中UAV数を取得（アトミックカウンターから）
        String countKey = "link:" + srcNode + ":" + dstNode + ":flying_count";
        RAtomicLong counter = client.getAtomicLong(countKey);
        long flyingCount = counter.get();

        // 容量を計算
        double newCapacity = Math.max(0, initCapacity - flyingCount);

        // 容量を保存
        String capacityKey = "link:" + srcNode + ":" + dstNode + ":capacity";
        RAtomicDouble capacityAtomic = client.getAtomicDouble(capacityKey);
        capacityAtomic.set(newCapacity);
    }
}
```

**利点:**
- ✅ 競合なし（Redisのシングルスレッド実行が保証）
- ✅ 実装がシンプル
- ✅ パフォーマンスが良い（< 1ms）
- ✅ デッドロックの心配なし

**2. Lua Scriptによる複合操作（Phase 3b用）**

容量チェック→減算を**1つのアトミック操作**で実行：

```java
/**
 * 容量チェック＆UAV投入をアトミックに実行
 * ACID特性のA（Atomicity）とI（Isolation）を保証
 *
 * @return true: 成功、false: 容量不足
 */
public boolean tryAllocateCapacity(int srcNode, int dstNode) {
    String capacityKey = "link:" + srcNode + ":" + dstNode + ":capacity";
    String countKey = "link:" + srcNode + ":" + dstNode + ":flying_count";

    // Lua Script（Redis上で実行されるため、アトミック）
    String luaScript =
        "local capacity = tonumber(redis.call('GET', KEYS[1])) or 0 " +
        "if capacity >= 1 then " +
        "  redis.call('DECRBYFLOAT', KEYS[1], 1) " +
        "  redis.call('INCR', KEYS[2]) " +
        "  return 1 " +
        "else " +
        "  return 0 " +
        "end";

    RScript script = client.getScript(StringCodec.INSTANCE);
    Long result = script.eval(
        RScript.Mode.READ_WRITE,
        luaScript,
        RScript.ReturnType.INTEGER,
        Arrays.asList(capacityKey, countKey)
    );

    return result == 1;
}
```

**利点:**
- ✅ 容量チェックと更新が**1つのアトミック操作**
- ✅ オーバーブッキングを完全に防止
- ✅ ロックより高速（1-2ms）

**使用例:**
```java
// src/server/worker/UAVWorker.java (Phase 3b)
if (capacityManager.tryAllocateCapacity(srcNode, dstNode)) {
    // UAV投入成功
    LogManager.getInstance().log("UAV " + uavId + " allocated on link " + srcNode + "->" + dstNode);
} else {
    // 容量不足、待機
    LogManager.getInstance().log("UAV " + uavId + " waiting for capacity");
    // 待機処理...
}
```

**3. 分散ロック（非推奨・重い処理用）**

複数の操作をロックで保護（最終手段）：

```java
/**
 * 分散ロックを使用した容量更新
 * ACID特性のI（Isolation）を完全に保証
 * ⚠️ パフォーマンスが悪いため、通常は使用しない
 */
public boolean updateCapacityWithLock(int srcNode, int dstNode) {
    String lockKey = "lock:link:" + srcNode + ":" + dstNode;
    RLock lock = client.getLock(lockKey);

    try {
        // ロック取得（最大10秒待機、30秒でタイムアウト）
        boolean acquired = lock.tryLock(10, 30, TimeUnit.SECONDS);
        if (!acquired) return false;

        // クリティカルセクション
        String capacityKey = "link:" + srcNode + ":" + dstNode + ":capacity";
        RBucket<Double> bucket = client.getBucket(capacityKey);
        double currentCapacity = bucket.get();

        if (currentCapacity >= 1) {
            bucket.set(currentCapacity - 1);
            return true;
        }
        return false;

    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

**欠点:**
- ⚠️ パフォーマンスが悪い（10-50ms）
- ⚠️ デッドロックのリスク
- ⚠️ 実装が複雑

##### ACID特性との対応

| ACID特性 | 実装方法 | 保証内容 | Phase |
|---------|---------|---------|-------|
| **A (Atomicity)** | RAtomicLong, RAtomicDouble | カウンター操作が不可分 | 3a, 3b |
| **A (Atomicity)** | Lua Script | 複合操作が不可分 | 3b |
| **C (Consistency)** | 初期容量 - 飛行中UAV数 = 現在容量 | 容量計算の整合性維持 | 3a, 3b |
| **I (Isolation)** | Redisのシングルスレッド実行 | 他のワーカーの影響を受けない | 3a, 3b |
| **I (Isolation)** | 分散ロック（RLock） | 完全な排他制御 | 3b（必要時のみ） |
| **D (Durability)** | Redis AOF/RDB永続化 | サーバー再起動後もデータ保持 | 3a, 3b |

##### 実装方針まとめ

| Phase | 実装方法 | 理由 |
|-------|---------|------|
| **Phase 3a** | アトミックカウンター（INCR/DECR） | メインプロセス単独なので競合少ない |
| **Phase 3b** | アトミックカウンター + Lua Script | 複数ワーカーで競合が発生するため |
| **Phase 4以降** | イベント駆動 + Lua Script | 完全な並列処理 |

##### 性能比較

| 方法 | スループット | レイテンシ | 実装難易度 | 競合制御 | 推奨度 |
|-----|------------|-----------|-----------|---------|-------|
| **アトミック操作（INCR/DECR）** | ⭐⭐⭐⭐⭐ | < 1ms | 簡単 | ✅ 完全 | ✅ 最推奨 |
| **Lua Script** | ⭐⭐⭐⭐ | 1-2ms | 中 | ✅ 完全 | ✅ 複合操作に使用 |
| **分散ロック（RLock）** | ⭐⭐ | 10-50ms | 難 | ✅ 完全 | ⚠️ 最終手段 |
| **GET→計算→SET（NG）** | ⭐⭐⭐⭐⭐ | < 1ms | 簡単 | ❌ 競合あり | ❌ 使用禁止 |

**結論:**
- Phase 3aでは**アトミックカウンター**を使用
- Phase 3bでは**Lua Script**でcheck-and-setをアトミック化
- 分散ロックは避ける（パフォーマンス劣化）
- これにより**重複カウント、オーバーブッキングを完全に防止**

#### Redis Key構造

```
link:0:1:capacity = "10.0"           # リンク0→1の現在容量
link:0:1:flying_count = "3"          # リンク0→1の飛行中UAV数
link:0:1:init_capacity = "10.0"      # リンク0→1の初期容量

link:1:2:capacity = "5.5"
link:1:2:flying_count = "1"
link:1:2:init_capacity = "8.0"
```

#### テスト

```java
@Test
public void testPhase3a_LinkCapacityRedis() {
    // 1. リンク容量をRedisに保存
    // 2. メモリとRedisの値を比較
    // 3. 整合性が保たれていることを確認
    // 4. 容量更新後も整合性が維持されることを確認
}
```

#### 成果物
- ✅ LinkCapacityManager実装
- ✅ LinkCapacityReader実装
- ✅ CapacityManager修正
- ✅ 整合性検証追加
- ✅ Redis Key構造ドキュメント

#### ロールバック
- メモリベースの処理は残っているため、Redis無効化で既存処理に戻せる

---

### Phase 3b: イベント駆動ワーカーアーキテクチャ

**更新日**: 2025-12-29
**ステータス**: 🔄 設計完了・実装待ち

---

#### 設計変更の背景

Phase 3b-1で基本的なジョブキューとワーカーを実装しましたが、以下の問題が発生しました：

**発生した問題（差し戻し前のコミットで確認）:**
1. 40台のUAVのうち、10台しか処理されなかった
2. 待機UAVが適切に再処理されなかった
3. 完了通知がメインプロセスに届いても、待機UAVの再ジョブ化ができなかった

**根本原因:**
- 現在のポーリングベースの待機処理をそのままWorkerモードに移植しようとした
- メインプロセスの`processWaitingUAVs()`がWorkerモードでは動作しない
- 容量管理がメモリとRedisで分離していた

**解決策:**
- **イベント駆動アーキテクチャ**への全面移行
- UAVの全アクション（飛行開始、リンク通過、待機開始、待機終了、飛行完了）をイベントで管理
- 2秒間隔のポーリングループを廃止

---

#### 目標アーキテクチャ（イベント駆動）

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           メインプロセス                                 │
│                                                                          │
│  ┌──────────────┐     ┌──────────────────┐     ┌──────────────────┐    │
│  │RouteSearcher │     │UAVLinkPassed     │     │UAVCompletion     │    │
│  │              │     │Listener          │     │Listener          │    │
│  │ 経路探索     │     │                  │     │                  │    │
│  │ ジョブ投入   │     │ 容量回復         │     │ 統計更新         │    │
│  │ 待機登録     │     │ 待機UAV再ジョブ化│     │ 最終容量回復     │    │
│  └──────┬───────┘     └────────▲─────────┘     └────────▲─────────┘    │
│         │                      │                        │               │
└─────────┼──────────────────────┼────────────────────────┼───────────────┘
          │                      │                        │
          │ enqueue              │ publish                │ publish
          ▼                      │                        │
┌─────────────────────────────────────────────────────────────────────────┐
│                              Redis                                       │
│                                                                          │
│   jobs:uav (Queue)          uav:link:passed         uav:completed       │
│   ┌─────────────┐           (Pub/Sub Channel)       (Pub/Sub Channel)   │
│   │ [Jobs...]   │                                                        │
│   └──────┬──────┘                                                        │
│          │                                                               │
│   waiting:link:X:Y (Deque)   ← リンク別待機キュー（FIFO）                │
│   ┌─────────────┐                                                        │
│   │ [Jobs...]   │                                                        │
│   └─────────────┘                                                        │
└─────────┼───────────────────────────────────────────────────────────────┘
          │ poll
          ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                           Worker プロセス                                │
│                                                                          │
│   while (running) {                                                      │
│       job = jobQueue.dequeue()        ←── 【飛行開始】                   │
│       │                                                                  │
│       for each link in path:                                             │
│           │                                                              │
│           ├─ flyLink(from, to)        ←── 【飛行中】タイマーで計測       │
│           │                                                              │
│           └─ linkPassedEvent.publish() ──→ 【リンク通過】イベント送信    │
│                                                                          │
│       completionEvent.publish()       ──→ 【飛行完了】イベント送信       │
│   }                                                                      │
└─────────────────────────────────────────────────────────────────────────┘
```

---

#### UAVアクションのイベント一覧

| アクション | トリガー | イベント/処理 | 担当 |
|-----------|---------|--------------|------|
| **経路割当** | 経路探索完了 | ジョブ投入 or 待機登録 | メインプロセス |
| **飛行開始** | ジョブ取得 | `jobs:uav` からpoll | Worker |
| **リンク通過** | 飛行距離到達 | `UAVLinkPassedEvent` 送信 | Worker |
| **待機開始** | 容量不足検知 | `waiting:link:X:Y` に登録 | Worker/メイン |
| **待機終了** | 容量回復検知 | 待機キューからdequeue | メインプロセス |
| **飛行再開** | 再ジョブ化 | `jobs:uav` に再投入 | メインプロセス |
| **飛行完了** | 最終リンク通過 | `UAVCompletionEvent` 送信 | Worker |

---

#### 段階的実装計画

##### Phase 3b-2a: 基盤クラス作成

**目標**: イベントクラスと管理クラスの枠組みを作成（ロジックなし）

**作成ファイル**:
```
src/server/redis/
├── UAVLinkPassedEvent.java      # リンク通過イベント（データクラス）
├── UAVCompletionEvent.java      # 完了イベント（データクラス）
├── WaitingUAVManager.java       # 待機UAV管理（スタブ）
└── UAVEventChannels.java        # Pub/Subチャンネル名定数
```

**イベントクラス設計**:
```java
// UAVLinkPassedEvent.java
public class UAVLinkPassedEvent implements Serializable {
    private int uavId;
    private int clientId;
    private int passedFromNode;      // 通過したリンクの始点
    private int passedToNode;        // 通過したリンクの終点
    private int nextFromNode;        // 次のリンクの始点（-1 = 最終リンク）
    private int nextToNode;          // 次のリンクの終点（-1 = 最終リンク）
    private int[] path;              // 経路情報
    private int currentLinkIndex;    // 現在の経路インデックス
    private long timestamp;          // イベント発生時刻
    private double elapsedFlightTime; // 経過飛行時間
    // getter/setter/toString
}

// UAVCompletionEvent.java
public class UAVCompletionEvent implements Serializable {
    private int uavId;
    private int clientId;
    private long arrivalTime;        // 到着時刻
    private double totalDistance;    // 総飛行距離
    private double actualFlightTime; // 実飛行時間
    private double totalWaitingTime; // 総待機時間
    private int[] path;              // 飛行経路
    private int sourceBeaconId;      // 出発地
    private int destinationBeaconId; // 目的地
    // getter/setter/toString
}
```

**テスト条件**:
- コンパイルが通ること
- 既存機能に影響がないこと（`make run`で従来通り動作）

**コミット**: `Phase 3b-2a: イベントクラスと管理クラスの枠組み作成`

---

##### Phase 3b-2b: Worker飛行処理（待機なし・単一リンク）

**目標**: 最もシンプルなケースでWorker処理を動作確認

**テスト条件**:
```
- ノード: 2つのみ（0 → 1）
- 経路: 単一リンク [0, 1]
- UAV数: 5台
- リンク容量: 100（待機が発生しない）
- Worker数: 1
```

**実装内容**:

1. **UAVJob.java 修正** - リンク距離情報を追加
```java
private double[] linkDistances;  // 各リンクの距離

public double getLinkDistance(int linkIndex) {
    return linkDistances[linkIndex];
}
```

2. **UAVWorker.java 修正** - 単一リンク飛行処理
```java
private void processUAVJob(UAVJob job) {
    int[] path = job.getPath();
    int fromNode = path[0];
    int toNode = path[1];

    // リンク距離を取得
    double linkDistance = job.getLinkDistance(0);

    // 飛行時間を計算
    double flightTime = linkDistance / job.getSpeed();

    // タイマーで待機（実際の飛行をシミュレート）
    Thread.sleep((long)(flightTime * 1000));

    // 完了イベント送信
    publishCompletionEvent(job);
}
```

3. **UAVCompletionListener.java 作成**
```java
public class UAVCompletionListener {
    public void startListening() {
        RTopic topic = client.getTopic(UAVEventChannels.COMPLETION);
        topic.addListener(UAVCompletionEvent.class, (channel, event) -> {
            handleCompletionEvent(event);
        });
    }

    private void handleCompletionEvent(UAVCompletionEvent event) {
        LogManager.getInstance().log("UAV " + event.getUavId() + " 完了通知受信");
        // 統計更新（ログ出力のみ）
    }
}
```

**期待結果**:
```
Worker: "UAV 0 飛行開始 (0→1)"
Worker: "UAV 0 飛行完了"
Main: "UAV 0 完了通知受信"
```

**コミット**: `Phase 3b-2b: Worker単一リンク飛行処理`

---

##### Phase 3b-2c: Worker飛行処理（待機なし・複数リンク）

**目標**: 複数リンクの経路でWorker処理を動作確認

**テスト条件**:
```
- ノード: 6つ（0 → 1 → 4 → 5）
- 経路: 3リンク [0, 1, 4, 5]
- UAV数: 5台
- リンク容量: 100（待機が発生しない）
- Worker数: 1
```

**実装内容**:

1. **UAVWorker.java 修正** - 複数リンク対応
```java
private void processUAVJob(UAVJob job) {
    int[] path = job.getPath();
    int currentLinkIndex = job.getCurrentLinkIndex();

    while (currentLinkIndex < path.length - 1) {
        int fromNode = path[currentLinkIndex];
        int toNode = path[currentLinkIndex + 1];

        // このリンクを飛行
        double linkDistance = job.getLinkDistance(currentLinkIndex);
        double flightTime = linkDistance / job.getSpeed();

        LogManager.log("UAV " + job.getUavId() + " 飛行中 " + fromNode + "→" + toNode);
        Thread.sleep((long)(flightTime * 1000));

        // リンク通過イベント送信
        publishLinkPassedEvent(job, fromNode, toNode, currentLinkIndex);

        currentLinkIndex++;
    }

    // 全リンク通過 → 完了イベント
    publishCompletionEvent(job);
}
```

2. **UAVLinkPassedListener.java 作成**
```java
public class UAVLinkPassedListener {
    public void startListening() {
        RTopic topic = client.getTopic(UAVEventChannels.LINK_PASSED);
        topic.addListener(UAVLinkPassedEvent.class, (channel, event) -> {
            handleLinkPassedEvent(event);
        });
    }

    private void handleLinkPassedEvent(UAVLinkPassedEvent event) {
        LogManager.log("UAV " + event.getUavId() +
            " リンク通過 " + event.getPassedFromNode() + "→" + event.getPassedToNode());
        // 容量管理は後のフェーズで実装
    }
}
```

**期待結果**:
```
Worker: "UAV 0 飛行中 0→1"
Main: "UAV 0 リンク通過 0→1"
Worker: "UAV 0 飛行中 1→4"
Main: "UAV 0 リンク通過 1→4"
Worker: "UAV 0 飛行中 4→5"
Main: "UAV 0 リンク通過 4→5"
Worker: "UAV 0 飛行完了"
Main: "UAV 0 完了通知受信"
```

**コミット**: `Phase 3b-2c: Worker複数リンク飛行処理`

---

##### Phase 3b-2d: 待機キュー管理（最初のリンクのみ）

**目標**: 最初のリンクでの待機・再開を実装

**テスト条件**:
```
- UAV数: 10台
- 最初のリンク（0→1）の容量: 5
- 期待: 5台が飛行、5台が待機、順次再開
```

**実装内容**:

1. **WaitingUAVManager.java 本実装**
```java
public class WaitingUAVManager {
    private RedissonClient client;

    /**
     * 待機UAVをリンク別キューに登録（FIFO）
     */
    public void enqueue(int fromNode, int toNode, UAVJob job) {
        String key = "waiting:link:" + fromNode + ":" + toNode;
        RDeque<UAVJob> queue = client.getDeque(key);
        queue.addLast(job);
        LogManager.log("UAV " + job.getUavId() + " 待機開始 (link " + fromNode + "→" + toNode + ")");
    }

    /**
     * 待機UAVをリンク別キューから取り出し（FIFO）
     */
    public UAVJob dequeue(int fromNode, int toNode) {
        String key = "waiting:link:" + fromNode + ":" + toNode;
        RDeque<UAVJob> queue = client.getDeque(key);
        return queue.pollFirst();
    }

    /**
     * 指定リンクに待機UAVがいるか確認
     */
    public boolean hasWaitingUAV(int fromNode, int toNode) {
        String key = "waiting:link:" + fromNode + ":" + toNode;
        RDeque<UAVJob> queue = client.getDeque(key);
        return !queue.isEmpty();
    }
}
```

2. **UAVCompletionListener.java 修正** - 待機UAV再ジョブ化
```java
private void handleCompletionEvent(UAVCompletionEvent event) {
    // 統計更新
    incrementFinishCounter(event);

    // 最終リンクの容量回復 + 待機UAV再ジョブ化
    int[] path = event.getPath();
    int lastFrom = path[path.length - 2];
    int lastTo = path[path.length - 1];

    // 容量回復
    linkCapacityManager.incrementCapacity(lastFrom, lastTo, 1.0);

    // 待機UAVがいれば再ジョブ化
    if (waitingManager.hasWaitingUAV(lastFrom, lastTo)) {
        UAVJob waitingJob = waitingManager.dequeue(lastFrom, lastTo);
        if (waitingJob != null) {
            linkCapacityManager.decrementCapacity(lastFrom, lastTo, 1.0);
            jobQueue.enqueueJob(waitingJob);
            LogManager.log("UAV " + waitingJob.getUavId() + " 飛行再開");
        }
    }
}
```

**期待結果**:
```
UAV 0-4: 飛行開始
UAV 5-9: 待機開始 (link 0→1)
UAV 0 完了 → UAV 5 飛行再開
UAV 1 完了 → UAV 6 飛行再開
...
最終的に10台すべて完了
```

**コミット**: `Phase 3b-2d: 最初のリンクでの待機・再開`

---

##### Phase 3b-2e: 途中リンクでの待機・再開

**目標**: 途中のリンクでも待機・再開が動作

**テスト条件**:
```
- 経路: [0, 1, 4, 5]
- リンク0→1の容量: 100（待機なし）
- リンク1→4の容量: 3（待機発生）
- UAV数: 10台
```

**実装内容**:

1. **UAVWorker.java 修正** - 途中リンク容量チェック
```java
while (currentLinkIndex < path.length - 1) {
    int fromNode = path[currentLinkIndex];
    int toNode = path[currentLinkIndex + 1];

    // リンク飛行
    flyLink(job, fromNode, toNode);

    // リンク通過イベント送信
    publishLinkPassedEvent(job, fromNode, toNode, currentLinkIndex);

    currentLinkIndex++;

    // 次のリンクがあれば容量チェック
    if (currentLinkIndex < path.length - 1) {
        int nextFrom = path[currentLinkIndex];
        int nextTo = path[currentLinkIndex + 1];

        // Redisから容量を確認
        double capacity = linkCapacityManager.getCapacity(nextFrom, nextTo);

        if (capacity > 0) {
            // 容量あり → 継続
            linkCapacityManager.decrementCapacity(nextFrom, nextTo, 1.0);
        } else {
            // 容量なし → 待機キューに登録してジョブ終了
            UAVJob continuationJob = createContinuationJob(job, currentLinkIndex);
            waitingManager.enqueue(nextFrom, nextTo, continuationJob);
            return;  // このジョブは終了
        }
    }
}
```

2. **UAVLinkPassedListener.java 修正** - 通過リンクの容量回復
```java
private void handleLinkPassedEvent(UAVLinkPassedEvent event) {
    int passedFrom = event.getPassedFromNode();
    int passedTo = event.getPassedToNode();

    // 通過リンクの容量回復
    linkCapacityManager.incrementCapacity(passedFrom, passedTo, 1.0);

    // このリンクで待機中のUAVがいれば再ジョブ化
    if (waitingManager.hasWaitingUAV(passedFrom, passedTo)) {
        double capacity = linkCapacityManager.getCapacity(passedFrom, passedTo);
        if (capacity > 0) {
            UAVJob waitingJob = waitingManager.dequeue(passedFrom, passedTo);
            if (waitingJob != null) {
                linkCapacityManager.decrementCapacity(passedFrom, passedTo, 1.0);
                jobQueue.enqueueJob(waitingJob);
                LogManager.log("UAV " + waitingJob.getUavId() + " 飛行再開 (途中リンク)");
            }
        }
    }
}
```

**コミット**: `Phase 3b-2e: 途中リンクでの待機・再開`

---

##### Phase 3b-2f: RouteSearcher統合

**目標**: 実際の経路探索からWorkerモードで飛行

**実装内容**:

1. **AbstractPhysarumSolverRouteSearcher.java 修正**
```java
// runUAVFlow() 内
if (workerModeEnabled) {
    // Workerモード
    UAVJob job = createJob(uav, pathArray, linkDistances);

    if (link[u][v].getCapacity() > 0) {
        link[u][v].decrementCapacity();
        linkCapacityManager.decrementCapacity(u, v, 1.0);
        jobQueue.enqueueJob(job);
    } else {
        waitingManager.enqueue(u, v, job);
    }
} else {
    // 従来モード（既存コード）
    flyingUavQueue.add(uav);
}
```

2. **ServerController.java 修正**
```java
// Workerモード切り替えフラグ
private boolean workerModeEnabled = false;

public void setWorkerModeEnabled(boolean enabled) {
    this.workerModeEnabled = enabled;
    if (enabled) {
        initializeCompletionListener();
        initializeLinkPassedListener();
    }
}
```

3. **BoundaryController.java 修正**
```java
// 起動オプションでWorkerモード切り替え
if (args.contains("--worker-mode")) {
    serverController.setWorkerModeEnabled(true);
    System.out.println("Workerモードで起動します");
}
```

**テスト**:
```bash
# 従来モード（デフォルト）
make run

# Workerモード
java -cp ... controller.BoundaryController --worker-mode
```

**コミット**: `Phase 3b-2f: RouteSearcher統合とモード切り替え`

---

#### Redis Key構造（Phase 3b追加分）

```
# ジョブキュー
jobs:uav  →  List [Job1, Job2, ...]

# リンク別待機キュー（FIFO）
waiting:link:0:1  →  Deque [Job5, Job12, Job15]
waiting:link:0:2  →  Deque [Job3]
waiting:link:1:4  →  Deque [Job8]

# Pub/Subチャンネル
uav:link:passed  →  Channel (UAVLinkPassedEvent)
uav:completed    →  Channel (UAVCompletionEvent)
```

---

#### 切り戻し方法

各コミット後に問題が発生した場合:

```bash
# 直前のコミットに戻す
git reset --hard HEAD~1

# 特定のコミットに戻す
git reset --hard <commit-hash>

# Workerモードを無効にして従来モードで動作確認
# （--worker-mode オプションを付けずに起動）
make run
```

---

#### 成果物（Phase 3b完了時）

**新規作成**:
- ✅ UAVLinkPassedEvent.java
- ✅ UAVCompletionEvent.java
- ✅ UAVEventChannels.java
- ✅ WaitingUAVManager.java
- ✅ UAVLinkPassedListener.java
- ✅ UAVCompletionListener.java

**修正**:
- ✅ UAVJob.java（リンク距離情報追加）
- ✅ UAVWorker.java（イベント駆動処理）
- ✅ AbstractPhysarumSolverRouteSearcher.java（Workerモード対応）
- ✅ ServerController.java（モード切り替え）
- ✅ BoundaryController.java（起動オプション）

---

### Phase 3b-3: 統合テスト・安定化

**目標**: 本番相当の条件でテスト

**テスト項目**:

| テスト | 条件 | 期待結果 |
|-------|------|---------|
| 単一リンク・少数UAV | 5台, 容量100 | 全UAV完了 |
| 複数リンク・少数UAV | 5台, 容量100 | 全UAV完了 |
| 待機あり・最初のリンク | 10台, 容量5 | 全UAV完了（待機→再開） |
| 待機あり・途中リンク | 10台, 容量3 | 全UAV完了（途中待機→再開） |
| 本番相当 | 40台, 容量30 | 全UAV完了 |
| 全経路探索手法 | Dijkstra/EPS/HYBRID/BINARY | 全UAV完了 |

**コミット**: `Phase 3b-3: 統合テスト完了`

---

### Phase 4: 完全移行とスケーリング

**目標**: 完全にイベント駆動ベースの処理に移行

#### 実装内容

**1. 従来のポーリングループを削除**
```java
// UAVFlyScheduler.java を非推奨化または削除
// flyUAV() の2秒間隔ポーリングは不要に
```

**2. Docker Compose設定**
```yaml
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  uav-worker-1:
    build:
      context: .
      dockerfile: Dockerfile
    command: ["java", "-cp", "/app/classes:/app/lib/*", "server.worker.UAVWorker", "worker-1"]
    depends_on:
      - redis
    environment:
      - REDIS_HOST=redis

  uav-worker-2:
    build:
      context: .
      dockerfile: Dockerfile
    command: ["java", "-cp", "/app/classes:/app/lib/*", "server.worker.UAVWorker", "worker-2"]
    depends_on:
      - redis
    environment:
      - REDIS_HOST=redis
```

**3. スケーリング**
```bash
# ワーカー数を動的に増減
docker-compose up -d --scale uav-worker=5   # 5ワーカー
docker-compose up -d --scale uav-worker=10  # 10ワーカー
```

#### 成果物
- ✅ 既存ポーリングコードの削除
- ✅ Docker設定完成
- ✅ スケーリング検証

---

### Phase 5: モニタリングと最適化

**目標**: 本番運用に向けた監視と最適化

#### 実装内容

**1. Redis Commanderでの監視**
- ジョブキュー長（`jobs:uav`）
- 待機キュー長（`waiting:link:*`）
- リンク容量（`link:*:capacity`）

**2. メトリクス収集**
```java
public class MetricsCollector {
    public static void recordProcessingTime(int uavId, long duration) {
        // 処理時間を記録
    }

    public static void recordQueueLength() {
        // キュー長を記録
    }
}
```

**3. エラーハンドリング**
- Worker異常終了時のジョブ復帰
- Redis接続断時のフォールバック

#### 成果物
- ✅ モニタリング設定
- ✅ メトリクス収集
- ✅ エラーハンドリング強化

---

## 📅 全体スケジュール

| Phase | 期間 | 主要成果物 | リスク | ステータス |
|-------|------|-----------|--------|-----------|
| **Phase 0** | 1-2日 | Docker環境、Redisson設定、Redis Commander | 低 | ✅ **完了** (2025-12-27) |
| **Phase 1** | 3-4日 | 二重書き込み、整合性検証、BinaryEPS修正 | 低 | ✅ **完了** (2025-12-28) |
| **Phase 2** | 3-4日 | 統計情報読み取り、ログ読み取り、パフォーマンス測定 | 中 | ✅ **完了** (2025-12-28) |
| **Phase 3a** | 2-3日 | リンク容量Redis移行 | 中 | ✅ **完了** (2025-12-28) |
| **Phase 3b-1** | 1-2日 | ジョブキュー・ワーカー基盤 | 中 | ✅ **完了** (2025-12-28) |
| **Phase 3b-2a** | 1日 | イベントクラス枠組み | 低 | ⬜ 未着手 |
| **Phase 3b-2b** | 1日 | Worker単一リンク飛行 | 低 | ⬜ 未着手 |
| **Phase 3b-2c** | 1日 | Worker複数リンク飛行 | 中 | ⬜ 未着手 |
| **Phase 3b-2d** | 1-2日 | 最初リンク待機・再開 | 中 | ⬜ 未着手 |
| **Phase 3b-2e** | 1-2日 | 途中リンク待機・再開 | 高 | ⬜ 未着手 |
| **Phase 3b-2f** | 1-2日 | RouteSearcher統合 | 高 | ⬜ 未着手 |
| **Phase 3b-3** | 2-3日 | 統合テスト・安定化 | 中 | ⬜ 未着手 |
| **Phase 4** | 3-4日 | 完全移行・スケーリング | 中 | ⬜ 未着手 |
| **Phase 5** | 2-3日 | モニタリング・最適化 | 低 | ⬜ 未着手 |

### 実績
- Phase 0: 実施日数 **約0.5日**（2025-12-27）
- Phase 1: 実施日数 **約1日**（2025-12-28）
- Phase 2: 実施日数 **約1日**（2025-12-28）
- Phase 3a: 実施日数 **約0.5日**（2025-12-28）
- Phase 3b-1: 実施日数 **約0.5日**（2025-12-28）
- 合計: **約3.5日**

### 設計変更履歴
- **2025-12-28**: Phase 3b-1実装後に問題発生（待機UAVが処理されない）
- **2025-12-29**: Phase 3b-2以降をイベント駆動アーキテクチャに再設計
  - ポーリングベース → イベント駆動
  - 全UAVキュー走査 → リンク別待機キュー
  - 2秒間隔処理 → 即時イベント応答

---

## ⚠️ リスクと対策

### リスク1: Redis障害
**対策**：
- Redis Sentinelで高可用性
- フェイルオーバー自動化
- メモリベース処理を残す（Phase 3まで）

### リスク2: パフォーマンス劣化
**対策**：
- Phase 1でベンチマーク取得
- 各Phaseでパフォーマンステスト
- Redisパイプライニング活用

### リスク3: 状態不整合
**対策**：
- トランザクション使用
- 楽観的ロック
- 定期的な整合性チェック

---

## 🎯 成功基準

### 機能要件
- ✅ 既存と同じシミュレーション結果
- ✅ UAVの飛行・待機・完了が正常動作
- ✅ リンク容量制約が正しく機能

### 非機能要件
- ✅ 100台のUAVを同時処理可能
- ✅ ワーカーを動的にスケール可能
- ✅ Redis障害時のグレースフルデグラデーション

### 開発要件
- ✅ 各Phaseでロールバック可能
- ✅ テストカバレッジ80%以上
- ✅ ドキュメント完備

---

## 🔧 開発ガイドライン

### コーディング規約
```java
// [Phase X] タグでフェーズを明記
// [Phase 1] Redis状態同期
RedisManager.publishUAVState(uav);

// [既存] タグで既存コードを保護
// [既存] メモリベースの処理
link[i][j].setCapacity(capacity);
```

### テスト方針
- 各Phase完了時に回帰テスト
- Phase 3以降は統合テスト必須
- 本番相当のデータでロードテスト

### レビュープロセス
- Phase完了ごとにコードレビュー
- アーキテクチャレビュー（Phase 2, 3）
- パフォーマンスレビュー（Phase 4）

---

## 📚 次のアクション

1. **Phase 0を開始する場合**：
   - `docker-compose.yml` の作成
   - Redisson依存関係の追加
   - Redis接続テスト

2. **詳細設計が必要な場合**：
   - 各Phaseの詳細設計書作成
   - データモデル定義
   - API仕様書作成

3. **プロトタイプを作りたい場合**：
   - Phase 1の簡易版実装
   - 動作デモ
   - フィードバック収集

**どこから始めますか？**
