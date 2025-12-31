# Phase 3b-10 実装議事録：Redisの容量をメモリに同期・メモリモード分離

**実施日**: 2025-12-30
**担当者**: Claude (Opus 4.5)
**関連コミット**:
- 4b34ec3 ログの出力強化
- f425b04 Phase3b-10: Redisの容量をメモリに同期する
- 591d2f5 メモリモードの時Redisと切り離す

---

## 目的

1. **Redis→メモリ容量同期**: 経路探索前にRedisの最新リンク容量をメモリ（link配列）に反映
2. **メモリモード分離**: メモリモード時にRedis操作を完全にスキップし、依存を除去

---

## Phase 3b-10: Redis→メモリ容量同期

### 問題背景

Redisモードでは、リンク容量がRedisで管理されていますが、経路探索アルゴリズム（Dijkstra, Physarum等）はメモリ上のlink配列を参照します。そのため、経路探索前にRedisの最新容量をメモリに同期する必要があります。

### 実装内容

**LinkCapacityManager.java 追加メソッド**:
```java
/**
 * Redisの容量をメモリ（link配列）に同期する
 * Phase 3b-11: 経路探索前にRedisの最新容量をlinkに反映
 */
public void syncCapacitiesToMemory(Link[][] link, int node) {
    if (!redisEnabled) {
        return;
    }

    try {
        int synced = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY) {
                    double redisCapacity = getCapacity(i, j);
                    link[i][j].setCapacity(redisCapacity);
                    synced++;
                }
            }
        }

        LogManager.getInstance().log(
            "Phase 3b-11: Redis→メモリ容量同期完了 (" + synced + " リンク)"
        );
    } catch (Exception e) {
        LogManager.getInstance().error("Phase 3b-11: 容量同期エラー", e);
    }
}

/**
 * メモリ（link配列）の容量をRedisに同期する
 * 初期化時に使用
 */
public void initializeCapacitiesToRedis(Link[][] link, int node) {
    if (!redisEnabled) {
        return;
    }

    try {
        int initialized = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY) {
                    saveInitCapacity(i, j, link[i][j].getInitCapacity());
                    saveCapacity(i, j, link[i][j].getCapacity());
                    initialized++;
                }
            }
        }

        LogManager.getInstance().log(
            "Phase 3b-11: メモリ→Redis容量初期化完了 (" + initialized + " リンク)"
        );
    } catch (Exception e) {
        LogManager.getInstance().error("Phase 3b-11: 容量初期化エラー", e);
    }
}
```

### 使用箇所

RouteSearcherの経路探索前に呼び出し:
```java
// Redisモード時は最新容量を同期
if (BoundaryController.getCurrentWorkerMode() == WorkerMode.REDIS) {
    capacityManager.syncCapacitiesToMemory(link, node);
}
```

---

## メモリモード分離

### 問題背景

メモリモード（従来方式）で実行時に、Redisへの接続・操作が発生すると：
1. Redis未起動時にエラーが発生
2. 不要なオーバーヘッドが発生
3. デバッグが複雑化

### 実装内容

**LinkCapacityManager.java**:
- `redisEnabled`フラグでRedis操作をスキップ
- 各メソッドの先頭で`if (!redisEnabled) return;`チェック

**FlightScheduler.java**:
- メモリモード時はインスタンス生成をスキップ

**BoundaryController.java**:
```java
if (workerMode == WorkerMode.REDIS) {
    initializeRedisWorker();
} else {
    // メモリモード時はRedis関連の初期化をスキップ
    LogManager.getInstance().log("メモリモード: Redis操作をスキップ");
}
```

---

## ログ出力の強化

### 追加されたログ

```
Phase 3b-11: Redis→メモリ容量同期完了 (16 リンク)
Phase 3b-11: メモリ→Redis容量初期化完了 (16 リンク)
```

### 既存ログの改善

- 容量操作時に双方向リンク対応を明示
- 待機キュー操作時に待機数を表示
- エラー発生時のスタックトレース出力

---

## 検証ポイント

| 項目 | 結果 |
|------|------|
| Redis→メモリ同期 | 全リンクの容量が正しく反映 |
| メモリ→Redis初期化 | 起動時に全リンク容量を保存 |
| メモリモード分離 | Redis未起動でもエラーなし |
| ログ出力 | 同期件数を確認可能 |

---

## 次のステップ

Phase 3b-11: 双方向リンク容量の同期問題を修正へ進む
