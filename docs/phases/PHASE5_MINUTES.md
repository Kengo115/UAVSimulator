# Phase 5 実装議事録：ソルバー失敗時の指数バックオフ再試行機構

**実施日**: 2025-01-08
**担当者**: Claude (Opus 4.5)

---

## 概要

Phase 5では、EPSソルバーが失敗した場合の再試行機構を実装しました。
従来のSavePoint復元方式に代わり、指数バックオフによる再試行管理を導入し、
ネットワーク容量の回復を待ってから再計算を行う仕組みを構築しました。

---

## 問題の背景

### 従来の課題

1. **ソルバー収束失敗**: EPSの圧力方程式ソルバー（BiCGSTAB）が収束しない場合がある
2. **負の圧力検出**: 数値不安定性により負の圧力が発生することがある
3. **容量不足**: ネットワーク容量が不足している状態では解が見つからない
4. **SavePoint制限**: 状態復元では根本的な容量不足を解決できない

### 解決方針

- **指数バックオフ再試行**: 失敗時に待機してから再計算（容量回復を期待）
- **SolverFailedException**: 再試行対象エラーを統一的に管理
- **排他制御**: サーチャーは1クライアントのみ使用可能
- **最大再試行制限**: 128秒待機後の失敗でスキップ

---

## 新規追加ファイル

### 1. SearcherRetryManager.java

**場所**: `src/server/scheduler/SearcherRetryManager.java`

サーチャー再試行を管理するシングルトンクラス。

```java
/**
 * Phase 5: サーチャー再試行管理クラス
 *
 * ソルバー失敗時の指数バックオフ再試行を管理
 * - 即時待機キュー（FIFO）
 * - 指数バックオフ（2→4→8→16→32→64→128秒）
 * - サーチャー排他制御（1クライアントのみ使用可能）
 * - 128秒後失敗でスキップ
 */
public class SearcherRetryManager {
    // 再試行設定
    private static final long INITIAL_DELAY_MS = 2000;   // 初回2秒
    private static final long MAX_DELAY_MS = 128000;     // 上限128秒
    private static final double BACKOFF_MULTIPLIER = 2.0;

    // 排他制御
    private final Object searcherLock = new Object();
    private AtomicBoolean searcherInUse = new AtomicBoolean(false);

    // 即時待機キュー（FIFO）
    private final LinkedList<SearchRequest> waitingQueue = new LinkedList<>();

    // 検索リクエスト
    public boolean requestSearch(SearchRequest request);
}
```

**主要機能**:
- サーチャー排他制御（1クライアントのみ）
- 即時待機キュー（FIFO）
- 指数バックオフ再試行（2→4→8→16→32→64→128秒）
- 再試行中のサーチャー一時解放（他クライアントに譲る）
- 再試行時のサーチャー優先再取得

---

### 2. SolverFailedException.java

**場所**: `src/server/route/SolverFailedException.java`

ソルバー失敗を表す例外クラス。

```java
/**
 * Phase 5: ソルバー失敗例外
 *
 * EPS/PG-EPSのソルバーが収束しなかった場合にスローされる例外
 * SearcherRetryManagerがこの例外をキャッチして再試行処理を行う
 */
public class SolverFailedException extends RuntimeException {
    private final int clientId;
    private final int iteration;
    private final String searcherType;

    public SolverFailedException(int clientId, int iteration, String searcherType);
}
```

**スロー条件**:
1. 圧力方程式ソルバーが収束しなかった（-1を返却）
2. 負の圧力が検出された
3. MAX_ITERATIONS（1000回）に到達して収束しなかった
4. MAX_BINARY_SEARCH_ITERATIONS（10回）に到達した

---

## 既存ファイルの変更

### 1. BisectionalPressureGuidedEPSRouteSearcher.java

**変更内容**:

1. **currentClientIdフィールド追加**:
```java
// Phase 5: 現在処理中のクライアントID
private int currentClientId = -1;
```

2. **binarySearchIterationのリセット追加**:
```java
// performDynamicBinarySearchEPS()内
stableIterationCount = 0;
previousSourcePressure = 0.0;
currentFlowBaselinePressure = 0.0;
currentFlowBaselineCaptured = false;
binarySearchIteration = 0; // Phase 5: 再試行時にリセット
```

3. **SolverFailedExceptionへの変換**:
```java
// 圧力方程式ソルバー失敗時
if (solvePressureEquation(...) == -1) {
    throw new SolverFailedException(currentClientId, ct + 1, "BisectionalPGEPS");
}

// 負の圧力検出時
if (P_tubePressure[sourceNode] < 0) {
    throw new SolverFailedException(currentClientId, ct + 1, "BisectionalPGEPS-NegativePressure");
}

// MAX_BINARY_SEARCH_ITERATIONS超過時
if (flowChanged) {
    binarySearchIteration++;
    if (binarySearchIteration >= MAX_BINARY_SEARCH_ITERATIONS) {
        throw new SolverFailedException(currentClientId, ct, "BisectionalPGEPS-MaxBinarySearch");
    }
}

// MAX_ITERATIONS超過時
if (ct >= MAX_ITERATIONS && stableIterationCount < REQUIRED_STABLE_ITERATIONS) {
    throw new SolverFailedException(currentClientId, ct, "BisectionalPGEPS-MaxIterations");
}
```

---

### 2. BinaryExtendedPhysarumSolverRouteSearcher.java

**変更内容**:
BisectionalPressureGuidedEPSRouteSearcherと同様の変更を適用。

---

### 3. ServerController.java

**変更内容**:

SearcherRetryManagerを使用した検索リクエストに変更:

```java
// run_BisectionalPGEPS()内
SearcherRetryManager.SearchRequest request = new SearcherRetryManager.SearchRequest(
    client, clientController, flyingUavQueue, uavQueue, numLoop,
    bisectionalPGEPSRouteSearcher,
    () -> {
        // preSearchAction: 容量同期
        linkCapacityManager.syncCapacityFromRedis();
    },
    () -> {
        // postSearchAction: UAVFlyScheduler開始
        scheduler.submitLoop(clientController, flyingUavQueue, uavQueue, numLoop,
                           client.getId(), flightScheduler);
    }
);
boolean success = SearcherRetryManager.getInstance().requestSearch(request);
```

---

## 処理フロー

### 正常時の検索フロー

```
1. クライアントが検索リクエスト
2. SearcherRetryManager.requestSearch()呼び出し
3. サーチャーが空いていれば即時実行
4. サーチャーが使用中ならFIFO待機キューに追加
5. 検索実行 → 成功
6. postSearchAction実行（UAV飛行開始）
7. サーチャー解放 → 次のクライアントに通知
```

### 再試行時のフロー

```
1. 検索実行 → SolverFailedException発生
2. 現在の待機時間をチェック（例: 2秒）
3. サーチャーを一時解放（他クライアントに譲る）
4. 2秒待機
5. サーチャーを優先的に再取得（待機キュー先頭に追加）
6. 待機時間を2倍に更新（2秒 → 4秒）
7. 再度検索実行
8. 成功するか、128秒待機後の失敗でスキップ
```

### 指数バックオフシーケンス

```
失敗1回目 → 2秒待機 → 再試行
失敗2回目 → 4秒待機 → 再試行
失敗3回目 → 8秒待機 → 再試行
失敗4回目 → 16秒待機 → 再試行
失敗5回目 → 32秒待機 → 再試行
失敗6回目 → 64秒待機 → 再試行
失敗7回目 → 128秒待機 → 再試行
失敗8回目 → スキップ（最大再試行回数超過）

合計最大待機時間: 2+4+8+16+32+64+128 = 254秒（約4分14秒）
```

---

## 失敗パターンと対処

| 失敗パターン | 検出方法 | 対処 |
|-------------|----------|------|
| ソルバー収束失敗 | solvePressureEquation() == -1 | 再試行 |
| 負の圧力検出 | P_tubePressure[sourceNode] < 0 | 再試行 |
| MAX_ITERATIONS超過 | ct >= 1000 && !収束 | 再試行 |
| MAX_BINARY_SEARCH超過 | binarySearchIteration >= 10 | 再試行 |
| 128秒待機後の失敗 | currentDelayMs >= MAX_DELAY_MS | スキップ |

---

## ログ出力例

### 検索リクエスト受付
```
Phase 5: client0 検索リクエスト受付
```

### 待機キュー追加
```
Phase 5: client1 待機キューに追加 (キュー長=1)
```

### 検索成功
```
Phase 5: client0 検索開始
Phase 5: client0 検索成功
```

### ソルバー失敗・再試行
```
Phase 5: client0 ソルバー失敗 (現在の待機時間=2000ms)
Phase 5: client0 2秒後に再試行
Phase 5: client0 サーチャー一時解放（2秒後に再取得予定）
Phase 5: client0 サーチャー再取得完了
Phase 5: client0 検索開始 (再試行1回目)
```

### 最大再試行超過でスキップ
```
Phase 5: client0 ソルバー失敗 (現在の待機時間=128000ms)
Phase 5: client0の経路割り当てが行えませんでした（最大再試行回数超過）
```

---

## 設計上の考慮点

### なぜSavePoint復元ではなく再試行か

1. **容量依存の問題**: ソルバー失敗の主因はネットワーク容量不足
2. **状態復元の限界**: SavePointで状態を戻しても容量は回復しない
3. **時間経過による回復**: 他UAVの飛行完了で容量が回復する可能性
4. **シンプルな設計**: 再試行の方が実装・デバッグが容易

### なぜ指数バックオフか

1. **効率的なリソース利用**: 短い間隔での連続失敗を避ける
2. **容量回復の時間確保**: 待機時間が増えることで回復確率が上がる
3. **他クライアントへの配慮**: 待機中はサーチャーを解放
4. **業界標準**: 分散システムで広く採用されているパターン

### なぜ128秒でスキップか

1. **合理的な上限**: 4分以上待機しても回復しない場合は諦める
2. **全体の遅延防止**: 1クライアントの失敗が全体をブロックしない
3. **調整可能**: MAX_DELAY_MSを変更するだけで調整可能

---

## 今後の課題

1. **フロー減少再試行**: 再試行時に要求フローを減らして成功率を上げる
2. **優先度制御**: 待機キューの優先度管理
3. **メトリクス収集**: 再試行回数、成功率などの統計情報
4. **動的バックオフ調整**: ネットワーク状態に応じた待機時間の調整

---

## 変更ファイル一覧

### 新規ファイル
- `src/server/scheduler/SearcherRetryManager.java`
- `src/server/route/SolverFailedException.java`

### 変更ファイル
- `src/server/route/BisectionalPressureGuidedEPSRouteSearcher.java`
- `src/server/route/BinaryExtendedPhysarumSolverRouteSearcher.java`
- `src/server/controller/ServerController.java`
- `src/controller/BoundaryController.java`
