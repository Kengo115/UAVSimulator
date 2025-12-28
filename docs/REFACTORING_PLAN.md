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

### Phase 3b: ワーカープロセス導入（3-4日）

#### ゴール
- **別JVMでUAV処理を実行**
- Redisジョブキューを使用
- メインプロセスとワーカープロセスの協調動作
- **⚡ 時間精度の改善（後述）**

#### 現状の課題

**Phase 3a完了時点:**
```
[メインプロセス - 1つのタイマー]
  │
  └─ 2秒ごとに全UAVを順次処理（直列処理）
     ├─ UAV 0の位置更新
     ├─ UAV 1の位置更新
     ├─ UAV 2の位置更新
     │   ...
     └─ UAV 99の位置更新

     ⚠️ UAV数が増えると処理時間が増加
     ⚠️ 処理時間 > 2秒 になると遅延発生
     ⚠️ 理論飛行時間と実測時間にズレが発生
```

**時間のズレの原因:**
- 2秒間隔の**離散的な**処理（連続的ではない）
- 全UAVを1つのスレッドで**直列処理**
- UAV数が増えると処理時間が線形増加
- 処理時間が2秒を超えると遅延が蓄積

**例:**
```
理論飛行時間: distance / speed = 1500m / 14.48m/s = 103.5秒
実測飛行時間: 104秒（2秒間隔なので切り上げ）
ズレ: 0.5秒

UAV数が100台の場合:
1回の処理に0.5秒かかると仮定
→ 100台 × 0.5秒 = 50秒（2秒を大幅超過！）
→ 次の処理サイクルまで48秒待機
→ 大幅な遅延が発生
```

#### アーキテクチャ

**Phase 3b完了後:**
```
┌────────────────────┐         ┌─────────────────┐         ┌─────────────────┐
│  Main Process      │         │     Redis       │         │ Worker Process  │
│  (Controller)      │         │   (State DB)    │         │   (Multiple)    │
│                    │         │                 │         │                 │
│  ┌──────────────┐ │  LPUSH  │ ┌─────────────┐ │  BRPOP  │ ┌─────────────┐ │
│  │経路探索       │ ├────────▶│ │ Job Queue   │ │◀────────┤ │UAV Worker 1 │ │
│  │- Dijkstra    │ │         │ │             │ │         │ │             │ │
│  │- EPS         │ │         │ │ jobs:uav    │ │         │ │ [Timer 1]   │ │
│  │- BinaryEPS   │ │         │ └─────────────┘ │         │ │ UAV 0       │ │
│  └──────────────┘ │         │                 │         │ │ UAV 1       │ │
│                    │  READ   │ ┌─────────────┐ │  READ/  │ └─────────────┘ │
│  ┌──────────────┐ │         │ │ UAV State   │ │  WRITE  │                 │
│  │UAV割り当て   │ │         │ │             │ │         │ ┌─────────────┐ │
│  │- 経路設定    │ │         │ │ uav:{id}    │ │◀────────┤ │UAV Worker 2 │ │
│  │- 速度設定    │ │         │ └─────────────┘ │         │ │             │ │
│  └──────────────┘ │         │                 │         │ │ [Timer 2]   │ │
│                    │  READ   │ ┌─────────────┐ │  READ/  │ │ UAV 2       │ │
│  ┌──────────────┐ │         │ │ Link        │ │  WRITE  │ │ UAV 3       │ │
│  │結果集約       │ │         │ │ Capacity    │ │         │ └─────────────┘ │
│  │- ログ出力    │ │         │ │             │ │         │                 │
│  │- 統計表示    │ │         │ │link:{i}:{j} │ │         │ ┌─────────────┐ │
│  └──────────────┘ │         │ │:capacity    │ │         │ │UAV Worker N │ │
│         ▲          │         │ │:flying_count│ │         │ │             │ │
│         │          │  SUB    │ └─────────────┘ │  PUB    │ │ [Timer N]   │ │
│         │          │         │                 │         │ │ UAV M       │ │
│         └──────────┼─────────│ ┌─────────────┐ │◀────────┤ │ UAV M+1     │ │
│     完了通知受信   │         │ │ Pub/Sub     │ │  発行    │ └─────────────┘ │
│                    │         │ │             │ │         │                 │
│                    │         │ │uav:completed│ │         │  各ワーカーが   │
└────────────────────┘         │ └─────────────┘ │         │  独立したタイマー│
                               │                 │         │  で処理         │
                               └─────────────────┘         └─────────────────┘
```

**Key Point:**
- 各ワーカーが**独立したタイマー**を持つ
- UAVごとに**並列処理**（直列→並列）
- ワーカー数を増やせば処理能力が線形増加

#### 実装

**1. ジョブ定義**
```java
// src/server/redis/UAVJob.java
public class UAVJob implements Serializable {
    private int uavId;
    private int clientId;
    private int[] path;
    private double speed;
    private long startTime;
    private String flyingLinkKey;  // "0-1" 形式

    // getters/setters
}
```

**2. ジョブキュー管理**
```java
// src/server/redis/UAVJobQueue.java
public class UAVJobQueue {
    private static final String QUEUE_KEY = "uav:jobs";
    private RBlockingQueue<UAVJob> queue;

    public UAVJobQueue(RedissonClient redisson) {
        this.queue = redisson.getBlockingQueue(QUEUE_KEY);
    }

    // ジョブを追加（メインプロセス）
    public void enqueueJob(UAVJob job) {
        queue.offer(job);
        LogManager.getInstance().log("Enqueued UAV job: " + job.getUavId());
    }

    // ジョブを取得（ワーカープロセス）
    public UAVJob dequeueJob(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }
}
```

**3. ワーカープロセス**
```java
// src/server/worker/UAVWorker.java
public class UAVWorker {
    private UAVJobQueue jobQueue;
    private RedissonClient redisson;

    public static void main(String[] args) {
        // Redis接続
        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379");
        RedissonClient redisson = Redisson.create(config);

        UAVWorker worker = new UAVWorker(redisson);
        worker.start();
    }

    public void start() {
        LogManager.getInstance().log("UAV Worker started");

        while (true) {
            try {
                // ジョブを取得（ブロッキング）
                UAVJob job = jobQueue.dequeueJob(5, TimeUnit.SECONDS);

                if (job != null) {
                    processUAVJob(job);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void processUAVJob(UAVJob job) {
        LogManager.getInstance().log("Processing UAV: " + job.getUavId());

        // UAV状態をRedisから取得
        RMap<String, Object> uavState = redisson.getMap("uav:" + job.getUavId());

        // 2秒間隔で位置更新をシミュレート
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            // 飛行時間計算
            long elapsedTime = (System.currentTimeMillis() - job.getStartTime()) / 1000;
            double flightDistance = elapsedTime * job.getSpeed();

            // 状態更新
            uavState.put("flightTime", elapsedTime);
            uavState.put("flightDistance", flightDistance);

            // 目的地到着判定
            if (hasReachedDestination(job, flightDistance)) {
                // 完了通知
                RTopic topic = redisson.getTopic("uav:completed");
                topic.publish(job.getUavId());

                scheduler.shutdown();
            }
        }, 0, 2, TimeUnit.SECONDS);
    }

    private boolean hasReachedDestination(UAVJob job, double flightDistance) {
        // 経路の総距離を計算して比較
        // ... 実装 ...
        return false;
    }
}
```

**4. メインプロセスの修正**
```java
// src/server/controller/ServerController.java
public void run_EPS(...) throws IOException {
    // [既存] 経路探索
    extendedPhysarumSolverRouteSearcher.search(client, flyingUavQueue, uavQueue, numLoop);

    // [新規] ジョブをワーカーにエンキュー
    UAVJobQueue jobQueue = new UAVJobQueue(RedisManager.getRedisson());

    for (Uav uav : flyingUavQueue) {
        UAVJob job = new UAVJob();
        job.setUavId(uav.getId());
        job.setClientId(uav.getClientId());
        job.setPath(uav.getPath());
        job.setSpeed(uav.getSpeed());
        job.setStartTime(System.currentTimeMillis());

        jobQueue.enqueueJob(job);
    }

    // [新規] 完了通知を購読
    RTopic topic = RedisManager.getRedisson().getTopic("uav:completed");
    topic.addListener(Integer.class, (channel, uavId) -> {
        LogManager.getInstance().log("UAV " + uavId + " completed");
        // 完了カウンターを更新
    });
}
```

#### Docker設定

```dockerfile
# Dockerfile.worker
FROM openjdk:17-slim
WORKDIR /app
COPY target/uav-simulator-worker.jar /app/
CMD ["java", "-jar", "uav-simulator-worker.jar"]
```

```yaml
# docker-compose.yml
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  uav-worker-1:
    build:
      context: .
      dockerfile: Dockerfile.worker
    depends_on:
      - redis
    environment:
      - REDIS_URL=redis://redis:6379
      - WORKER_ID=1

  uav-worker-2:
    build:
      context: .
      dockerfile: Dockerfile.worker
    depends_on:
      - redis
    environment:
      - REDIS_URL=redis://redis:6379
      - WORKER_ID=2
```

#### テスト

```java
@Test
public void testPhase3_WorkerProcessing() {
    // 1. Redisが起動していることを確認
    // 2. ワーカーを起動
    // 3. ジョブをエンキュー
    // 4. ワーカーが処理することを確認
    // 5. 完了通知が届くことを確認
}
```

#### ⚡ 時間精度の改善

**質問: ワーカー化で時間のズレは解決できますか？**

**回答: はい、大幅に改善できます。ただし完全にはゼロにはなりません。**

##### 現状の問題分析

**Phase 3a（メインプロセス・直列処理）:**
```
時刻t=0秒:
  → 全UAV（100台）を順次処理
     UAV 0処理: 10ms
     UAV 1処理: 10ms
     ...
     UAV 99処理: 10ms
  → 合計: 1000ms（1秒）

時刻t=2秒:
  → 再び全UAV処理（1秒）

時刻t=4秒:
  → 再び全UAV処理（1秒）

問題点:
1. 処理時間がUAV数に比例（O(N)）
2. UAV 0とUAV 99で処理タイミングが1秒ずれる
3. UAV数が増えると処理が2秒を超過する可能性
```

**Phase 3b（ワーカー・並列処理）:**
```
[Worker 1]               [Worker 2]               [Worker N]
  ├─ UAV 0 (Timer)        ├─ UAV 2 (Timer)        ├─ UAV M (Timer)
  │  └─ 2秒間隔           │  └─ 2秒間隔           │  └─ 2秒間隔
  │                       │                       │
  └─ UAV 1 (Timer)        └─ UAV 3 (Timer)        └─ UAV M+1 (Timer)
     └─ 2秒間隔              └─ 2秒間隔              └─ 2秒間隔

利点:
1. 各UAVが独立したタイマーで処理（並列）
2. 他のUAVの影響を受けない
3. 処理時間がワーカー数で割れる（O(N/W)、W=ワーカー数）
```

##### 時間精度の比較

| ケース | UAV数 | ワーカー数 | 処理時間/サイクル | 遅延 | 精度 |
|--------|------|----------|-----------------|------|------|
| **Phase 3a（現状）** | 10台 | 1 | 100ms | 0秒 | ✅ 良好 |
| **Phase 3a（現状）** | 100台 | 1 | 1000ms | 0秒 | ⚠️ やや悪化 |
| **Phase 3a（現状）** | 500台 | 1 | 5000ms | **3秒** | ❌ 大幅悪化 |
| **Phase 3b（ワーカー）** | 100台 | 10 | 100ms | 0秒 | ✅ 良好 |
| **Phase 3b（ワーカー）** | 500台 | 50 | 100ms | 0秒 | ✅ 良好 |
| **Phase 3b（ワーカー）** | 1000台 | 100 | 100ms | 0秒 | ✅ 良好 |

**計算式:**
```
処理時間/サイクル = (UAV数 × UAV処理時間) / ワーカー数

遅延 = max(0, 処理時間/サイクル - 2秒)
```

##### 並列数の制限

**理論上の最大並列数:**
```
ワーカー数 = UAV数

例: 100台のUAVなら100ワーカー
→ 各ワーカーが1台のUAVを専属で処理
```

**実用上の推奨並列数:**
```
ワーカー数 = CPUコア数 × 1.5〜2

理由:
1. Redis接続数の制限（デフォルト: 10000接続）
2. メモリ使用量（各ワーカーがJVM起動）
3. コンテキストスイッチのオーバーヘッド

例:
- 8コアマシン → 12〜16ワーカー
- 16コアマシン → 24〜32ワーカー
- 32コアマシン → 48〜64ワーカー
```

**スケーリング例:**
```bash
# ワーカー数を動的に増減
docker-compose up -d --scale uav-worker=10   # 10ワーカー
docker-compose up -d --scale uav-worker=50   # 50ワーカー
docker-compose up -d --scale uav-worker=100  # 100ワーカー
```

##### 改善効果のシミュレーション

**実験設定:**
- UAV数: 100台
- 経路距離: 1500m
- 速度: 14.48m/s
- 理論飛行時間: 103.57秒

**Phase 3a（現状）:**
```
処理サイクル: 2秒ごと
サイクル数: 103.57 / 2 = 51.785回 → 52回
実測時間: 52 × 2 = 104秒
誤差: 0.43秒（0.4%）

UAV処理時間: 10ms × 100台 = 1000ms
→ まだ2秒以内なので遅延なし
```

**Phase 3a（大規模）:**
```
UAV数: 500台
処理時間: 10ms × 500台 = 5000ms（5秒）
→ 2秒を3秒超過！

タイムライン:
t=0秒: 処理開始
t=5秒: 処理完了（本来はt=2秒で開始すべき）
t=5秒: 次の処理開始
t=10秒: 処理完了（本来はt=4秒）

→ 遅延が蓄積し、実測時間が大幅に増加
```

**Phase 3b（ワーカー10台）:**
```
ワーカー数: 10台
各ワーカー担当: 100 / 10 = 10台のUAV
処理時間: 10ms × 10台 = 100ms
→ 2秒以内、遅延なし

タイムライン:
t=0秒: 全ワーカーが並列で処理開始
t=0.1秒: 全ワーカーが処理完了
t=2秒: 全ワーカーが次の処理開始
t=2.1秒: 全ワーカーが処理完了

→ 遅延ゼロ、理論値に近い精度
```

**Phase 3b（ワーカー50台で500UAV）:**
```
ワーカー数: 50台
各ワーカー担当: 500 / 50 = 10台のUAV
処理時間: 10ms × 10台 = 100ms
→ 2秒以内、遅延なし

Phase 3aでは不可能だった規模でも、
ワーカーを増やすことで対応可能
```

##### 残る誤差について

**2秒間隔による離散化誤差:**
```
理論飛行時間: 103.57秒
2秒間隔での実測: 104秒
誤差: 0.43秒

この誤差は2秒間隔処理の構造上、
ワーカー化しても残ります。
```

**完全に誤差をゼロにする方法（Phase 4以降の検討事項）:**
```java
// イベント駆動モデル（到着時刻を事前計算）
double arrivalTime = distance / speed;
scheduler.schedule(() -> {
    // 到着処理
}, (long)arrivalTime, TimeUnit.SECONDS);

→ 2秒間隔ではなく、到着予定時刻に処理
→ 理論値との誤差がほぼゼロ
```

##### まとめ

| 項目 | Phase 3a（現状） | Phase 3b（ワーカー） | 改善率 |
|-----|-----------------|-------------------|--------|
| **小規模（10台）** | 0.4%の誤差 | 0.4%の誤差 | 同等 |
| **中規模（100台）** | 0.4%の誤差 | 0.4%の誤差 | 同等 |
| **大規模（500台）** | 遅延蓄積で破綻 | 0.4%の誤差 | **大幅改善** |
| **超大規模（1000台）** | 処理不可能 | ワーカー増で対応可 | **質的改善** |
| **スケーラビリティ** | ❌ 限界あり | ✅ 水平スケール可 | **無限** |

**結論:**
- ワーカー化により、**大規模シミュレーションでの時間精度が大幅改善**
- 小規模では効果が見えにくいが、規模が大きいほど効果絶大
- 並列数は理論上UAV数まで、実用上はCPUコア数の1.5〜2倍を推奨
- 2秒間隔の離散化誤差（0.4%程度）はワーカー化でも残る
  - 完全にゼロにするにはイベント駆動モデルへの移行が必要（Phase 4以降）

#### 成果物
- ✅ UAVWorker実装
- ✅ ジョブキュー実装
- ✅ Docker設定
- ✅ Pub/Sub実装

#### ロールバック
- ワーカーを起動しなければ既存処理で動作

---

### Phase 4: 完全移行とスケーリング（3-4日）

#### ゴール
- 既存のScheduledExecutorServiceを削除
- 完全にRedis + Workerベースの処理
- 水平スケール可能に

#### 実装

**1. UAVFlyScheduler の非推奨化**
```java
// src/server/uav/UAVFlyScheduler.java
@Deprecated
public class UAVFlyScheduler {
    // この実装は使用されない
    // すべてRedisワーカーで処理
}
```

**2. スケーリング設定**
```yaml
# docker-compose.yml
services:
  uav-worker:
    build:
      context: .
      dockerfile: Dockerfile.worker
    depends_on:
      - redis
    environment:
      - REDIS_URL=redis://redis:6379
    deploy:
      replicas: 5  # 5つのワーカープロセス
```

#### テスト

```java
@Test
public void testPhase4_FullMigration() {
    // 1. 100台のUAVをシミュレーション
    // 2. 複数ワーカーで並列処理
    // 3. すべてのUAVが正しく完了することを確認
    // 4. パフォーマンスを測定
}
```

#### 成果物
- ✅ 既存コードの削除
- ✅ スケーリング検証
- ✅ パフォーマンステスト

---

### Phase 5: モニタリングと最適化（2-3日）

#### ゴール
- Redisモニタリング
- パフォーマンス最適化
- 障害対応

#### 実装

**1. Redis Insightの導入**
```yaml
# docker-compose.yml
services:
  redis-insight:
    image: redislabs/redisinsight:latest
    ports:
      - "8001:8001"
```

**2. メトリクス収集**
```java
// src/server/monitoring/MetricsCollector.java
public class MetricsCollector {
    public static void recordUAVProcessingTime(int uavId, long duration) {
        RTimeSeries<Long> timeSeries = redisson.getTimeSeries("metrics:processing_time");
        timeSeries.add(System.currentTimeMillis(), duration);
    }

    public static void recordQueueLength() {
        RBlockingQueue<UAVJob> queue = redisson.getBlockingQueue("uav:jobs");
        int queueLength = queue.size();

        RTimeSeries<Integer> timeSeries = redisson.getTimeSeries("metrics:queue_length");
        timeSeries.add(System.currentTimeMillis(), queueLength);
    }
}
```

#### 成果物
- ✅ モニタリングダッシュボード
- ✅ メトリクス収集
- ✅ アラート設定

---

## 📅 全体スケジュール

| Phase | 期間 | 主要成果物 | リスク | ステータス |
|-------|------|-----------|--------|-----------|
| **Phase 0** | 1-2日 | Docker環境、Redisson設定、Redis Commander | 低 | ✅ **完了** (2025-12-27) |
| **Phase 1** | 3-4日 | 二重書き込み、整合性検証、BinaryEPS修正 | 低 | ✅ **完了** (2025-12-28) |
| **Phase 2** | 3-4日 | 統計情報読み取り、ログ読み取り、パフォーマンス測定 | 中 | ✅ **完了** (2025-12-28) |
| **Phase 3** | 5-7日 | リンク容量Redis移行、ワーカープロセス | 高 | ⬜ 未着手 |
| **Phase 4** | 3-4日 | 完全移行 | 中 | ⬜ 未着手 |
| **Phase 5** | 2-3日 | モニタリング | 低 | ⬜ 未着手 |
| **合計** | **18-25日** | | | **進捗: 3/6完了** |

### 実績
- Phase 0: 実施日数 **約0.5日**（2025-12-27）
- Phase 1: 実施日数 **約1日**（2025-12-28）
- Phase 2: 実施日数 **約1日**（2025-12-28）
- 合計: **約2.5日**（計画6-10日に対して効率的に完了）

### Phase 2以降の変更
- **Phase 2**: 「リンク容量Redis移行」→「統計情報読み取り切り替え」に変更（段階的アプローチのため）
- **Phase 3**: リンク容量Redis移行とワーカープロセス導入を統合予定

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
