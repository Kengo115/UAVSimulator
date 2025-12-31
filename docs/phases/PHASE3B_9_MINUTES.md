# Phase 3b-9 実装議事録：スレッドオートスケーリング

**実施日**: 2025-12-30
**担当者**: Claude (Opus 4.5)
**コミット**: 8b2f1e8 Phase 3b-9: スレッドオートスケーリング

---

## 目的

**スレッドプールのオートスケーリング**

FlightSchedulerのスレッドプールサイズを負荷に応じて動的に調整し、リソース効率とパフォーマンスを両立させます。

---

## オートスケーリング設定

```java
private static final int MIN_POOL_SIZE = 16;           // 最小プールサイズ
private static final int MAX_POOL_SIZE = 32;           // 最大プールサイズ
private static final int SCALE_STEP = 4;               // スケーリング単位
private static final double SCALE_UP_THRESHOLD = 0.9;  // 90%でスケールアップ
private static final double SCALE_DOWN_THRESHOLD = 0.5;// 50%未満でスケールダウン
private static final int SCALE_DOWN_DELAY_SECONDS = 30;// 30秒継続でスケールダウン
private static final int MONITOR_INTERVAL_MS = 1000;   // 監視間隔1秒
```

---

## 実装内容

### 1. 監視スレッドの起動

```java
private void startAutoScalingMonitor() {
    monitorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "FlightScheduler-AutoScaler");
        t.setDaemon(true);
        return t;
    });

    monitorExecutor.scheduleAtFixedRate(
        this::checkAndScale,
        MONITOR_INTERVAL_MS,
        MONITOR_INTERVAL_MS,
        TimeUnit.MILLISECONDS
    );
}
```

### 2. スケーリング判定ロジック

```java
private void checkAndScale() {
    int currentPoolSize = scheduler.getCorePoolSize();
    int activeCount = scheduler.getActiveCount();
    double usage = (double) activeCount / currentPoolSize;

    // スケールアップ判定
    if (usage >= SCALE_UP_THRESHOLD && currentPoolSize < MAX_POOL_SIZE) {
        int newSize = Math.min(currentPoolSize + SCALE_STEP, MAX_POOL_SIZE);
        scheduler.setCorePoolSize(newSize);
        lowUsageStartTime.set(0);  // 低使用率カウンタをリセット
        LogManager.getInstance().log(
            "Phase 3b-10: スケールアップ " + currentPoolSize + " → " + newSize +
            " (使用率=" + String.format("%.1f", usage * 100) + "%)"
        );
        return;
    }

    // スケールダウン判定（30秒継続が必要）
    if (usage < SCALE_DOWN_THRESHOLD && currentPoolSize > MIN_POOL_SIZE) {
        long now = System.currentTimeMillis();
        long lowUsageStart = lowUsageStartTime.get();

        if (lowUsageStart == 0) {
            lowUsageStartTime.set(now);
        } else if (now - lowUsageStart >= SCALE_DOWN_DELAY_SECONDS * 1000L) {
            int newSize = Math.max(currentPoolSize - SCALE_STEP, MIN_POOL_SIZE);
            scheduler.setCorePoolSize(newSize);
            lowUsageStartTime.set(0);
            LogManager.getInstance().log(
                "Phase 3b-10: スケールダウン " + currentPoolSize + " → " + newSize +
                " (使用率=" + String.format("%.1f", usage * 100) + "%, " +
                SCALE_DOWN_DELAY_SECONDS + "秒継続)"
            );
        }
    } else {
        lowUsageStartTime.set(0);
    }
}
```

### 3. 統計取得メソッド

```java
public int getCurrentPoolSize() {
    return scheduler.getCorePoolSize();
}

public int getActiveThreadCount() {
    return scheduler.getActiveCount();
}

public double getPoolUsage() {
    int poolSize = scheduler.getCorePoolSize();
    int active = scheduler.getActiveCount();
    return poolSize > 0 ? (double) active / poolSize : 0.0;
}
```

---

## スケーリングの流れ

```
初期: poolSize = 16

高負荷時:
  使用率 92% → スケールアップ 16 → 20
  使用率 95% → スケールアップ 20 → 24
  使用率 90% → スケールアップ 24 → 28
  使用率 91% → スケールアップ 28 → 32 (上限)

低負荷時:
  使用率 45% → 30秒待機開始
  (30秒経過)
  使用率 40% → スケールダウン 32 → 28
  使用率 35% → スケールダウン 28 → 24
  ...
  使用率 20% → スケールダウン 20 → 16 (下限)
```

---

## ログ出力例

```
Phase 3b-10: FlightScheduler initialized (poolSize=16, autoScaling=16-32)
Phase 3b-10: オートスケーリング監視開始 (間隔=1000ms)
...
Phase 3b-10: スケールアップ 16 → 20 (使用率=92.5%)
Phase 3b-10: スケールアップ 20 → 24 (使用率=91.2%)
...
Phase 3b-10: スケールダウン 24 → 20 (使用率=42.3%, 30秒継続)
Phase 3b-10: FlightScheduler shutdown (最終poolSize=20)
```

---

## 検証ポイント

| 項目 | 結果 |
|------|------|
| 初期プールサイズ | 16スレッド |
| スケールアップ | 使用率90%超で即時 |
| スケールダウン | 使用率50%未満が30秒継続で実行 |
| 上限/下限 | 16〜32の範囲内で調整 |
| 監視間隔 | 1秒ごとにチェック |

---

## 次のステップ

Phase 3b-10: Redisの容量をメモリに同期へ進む
