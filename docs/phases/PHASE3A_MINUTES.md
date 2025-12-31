# Phase 3a 実装議事録：リンク容量Redis移行

**実施日**: 2025-12-28
**担当者**: Claude (Sonnet 4.5)
**コミット**: 7a56a2a リンク容量Redis移行

---

## 目的

リンク容量をRedisに移行し、将来のワーカープロセス並列処理の基盤を構築する。

## 実装方針

**二重書き込みパターン（Dual Write Pattern）**

1. **書き込み**: メモリ（既存）とRedis（新規）の両方に容量を保存
2. **読み取り**: Phase 3aではメモリから読み取り（Redisは検証用のみ）
3. **検証**: 5回に1回、メモリとRedisの整合性をチェック

---

## 実装した機能

### 1. リンク容量管理（LinkCapacityManager.java）

**役割**: リンク容量をRedisに保存・管理

**主要メソッド**:
```java
// 全リンクの容量を一括更新（二重書き込み）
public void updateAllCapacities(Link[][] link, int[][] flyingUAV, int node)

// 初期容量をRedisに保存
public void saveInitCapacity(int srcNode, int dstNode, double initCapacity)

// 現在容量をRedisに保存（アトミック操作）
public void saveCapacity(int srcNode, int dstNode, double capacity)

// 飛行中UAV数を設定（アトミック操作）
public void setFlyingCount(int srcNode, int dstNode, int count)

// 飛行中UAV数をインクリメント（Phase 3b用）
public long incrementFlyingCount(int srcNode, int dstNode)

// 飛行中UAV数をデクリメント（Phase 3b用）
public long decrementFlyingCount(int srcNode, int dstNode)

// 容量をアトミックに減算（Phase 3b用）
public double decrementCapacity(int srcNode, int dstNode, double amount)

// 容量をアトミックに加算（Phase 3b用）
public double incrementCapacity(int srcNode, int dstNode, double amount)
```

**アトミック操作の使用理由**:
- 将来のワーカープロセス並列処理での競合防止
- `RAtomicDouble` / `RAtomicLong` を使用してアトミックな増減を実現

---

### 2. リンク容量読み取り・検証（LinkCapacityReader.java）

**役割**: Redisからリンク容量を読み取り、整合性を検証

**主要メソッド**:
```java
// 容量をRedisから読み取り
public double getCapacity(int srcNode, int dstNode)

// 初期容量をRedisから読み取り
public double getInitCapacity(int srcNode, int dstNode)

// 飛行中UAV数をRedisから読み取り
public long getFlyingCount(int srcNode, int dstNode)

// 全リンクの容量を読み取り
public Map<String, Double> getAllCapacities(int node)

// メモリとRedisの整合性検証
public boolean validateCapacity(Link[][] link, int[][] flyingUAV, int node)

// リンク容量サマリを表示
public void printCapacitySummary(int node)
```

**整合性検証のロジック**:
```java
public boolean validateCapacity(Link[][] link, int[][] flyingUAV, int node) {
    for (int i = 0; i < node; i++) {
        for (int j = 0; j < node; j++) {
            if (link[i][j].getL_tubeLength() == Double.POSITIVE_INFINITY) {
                continue;
            }

            // メモリとRedisの容量を比較（誤差0.001以下は許容）
            double memoryCapacity = link[i][j].getCapacity();
            double redisCapacity = getCapacity(i, j);

            if (Math.abs(memoryCapacity - redisCapacity) > 0.001) {
                // 不整合を検出
            }

            // 飛行中UAV数も比較
            int memoryFlyingCount = flyingUAV[i][j];
            long redisFlyingCount = getFlyingCount(i, j);

            if (memoryFlyingCount != redisFlyingCount) {
                // 不整合を検出
            }
        }
    }
}
```

---

### 3. 容量管理の統合（CapacityManager.java）

**変更内容**: メモリ更新後にRedisにも二重書き込み

```java
public static void updateCapacity(int[][] flyingUAV, Link[][] link, int node) {
    // [既存] Capacityを初期値に戻す（メモリ）
    for (int i = 0; i < node; i++) {
        for (int j = 0; j < node; j++) {
            if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY) {
                link[i][j].setCapacity(link[i][j].getInitCapacity());
                link[j][i].setCapacity(link[j][i].getInitCapacity());
            }
        }
    }

    // [既存] 各リンクの初期容量から飛行中のUAV分を減少（メモリ）
    for (int i = 0; i < node; i++) {
        for (int j = 0; j < node; j++) {
            if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY && flyingUAV[i][j] > 0) {
                double newCapacity = link[i][j].getCapacity() - flyingUAV[i][j];
                link[i][j].setCapacity(Math.max(0, newCapacity));
                link[j][i].setCapacity(Math.max(0, newCapacity));
            }
        }
    }

    // [新規] Phase 3a: Redisにも同じ内容を保存（二重書き込み）
    linkCapacityManager.updateAllCapacities(link, flyingUAV, node);
}
```

---

### 4. 整合性チェックの統合（UAVFlightController.java）

**追加フィールド**:
```java
// Phase 3a: リンク容量整合性チェック用
private static server.redis.LinkCapacityReader linkCapacityReader = new server.redis.LinkCapacityReader();
private static int capacityValidationCounter = 0;
private static final int CAPACITY_VALIDATION_INTERVAL = 5;  // 5回に1回チェック
```

**追加処理**:
```java
// 容量の更新
CapacityManager.updateCapacity(flyingUAV, link, node);

// Phase 3a: リンク容量の整合性チェック（5回に1回）
capacityValidationCounter++;
if (capacityValidationCounter % CAPACITY_VALIDATION_INTERVAL == 0) {
    linkCapacityReader.validateCapacity(link, flyingUAV, node);
}
```

---

## 作成・修正したファイル

### 新規作成
- `src/server/redis/LinkCapacityManager.java` - リンク容量のRedis保存・管理
- `src/server/redis/LinkCapacityReader.java` - リンク容量のRedis読み取り・検証

### 修正
- `src/server/util/CapacityManager.java` - Redis二重書き込み追加
- `src/server/uav/UAVFlightController.java` - 整合性チェック追加

---

## Redis Key構造

| Key | 型 | 説明 |
|-----|-----|------|
| `link:{src}:{dst}:capacity` | double | 現在のリンク容量 |
| `link:{src}:{dst}:init_capacity` | double | 初期リンク容量 |
| `link:{src}:{dst}:flying_count` | long | 飛行中UAV数 |

---

## 次のステップ

Phase 3b-1: ジョブキューとワーカーの基本実装へ進む
