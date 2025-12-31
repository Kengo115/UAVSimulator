# Phase 3b-11 実装議事録：双方向リンク容量の同期問題を修正

**実施日**: 2025-12-30 〜 2025-12-31
**担当者**: Claude (Opus 4.5)
**コミット**: caf4944 双方向リンク容量の同期問題を修正

---

## 問題の概要

### 発生した問題

client3のUAV0とUAV1が`waiting:link:2:0`キューでスタックし、飛行を再開しない問題が発生しました。

### 原因分析

1. **双方向リンクの容量管理**:
   - リンク0→2とリンク2→0は双方向リンクとして容量を共有
   - `tryConsumeCapacity(0, 2)`は0→2と2→0の両方の容量を消費
   - `recoverCapacity(0, 2)`は0→2と2→0の両方の容量を回復

2. **待機キューの処理漏れ**:
   - `FlightScheduler.onLinkPassed()`で容量回復後、順方向（0→2）の待機キューのみをチェック
   - 逆方向（2→0）の待機キューはチェックされない
   - 結果：2→0で待機しているUAVが永久に再ジョブ化されない

---

## 問題の詳細

### 状況

```
client1, client2: link 0→2 を飛行
client3: link 2→0 を飛行したい

1. client1が0→2飛行開始 → 0→2と2→0の容量消費
2. client3が2→0飛行しようとする → 容量0で待機キュー登録
3. client1が0→2通過完了 → 0→2と2→0の容量回復
4. FlightScheduler: waiting:link:0:2をチェック → 空
5. FlightScheduler: waiting:link:2:0をチェックしない ← 問題！
6. client3のUAVは永久に待機
```

### 問題箇所（修正前）

```java
// FlightScheduler.onLinkPassed()
private void onLinkPassed(UAVJob job, int linkIndex) {
    // ...

    // 3. 容量回復（順方向・逆方向両方）
    double newCapacity = capacityManager.recoverCapacity(fromNode, toNode);

    // 4. 待機UAVがいれば再ジョブ化（順方向のみ）← 問題
    if (waitingManager.hasWaitingUAV(fromNode, toNode)) {
        UAVJob waitingJob = waitingManager.dequeue(fromNode, toNode);
        if (waitingJob != null) {
            jobQueue.enqueueJob(waitingJob);
        }
    }

    // 逆方向（toNode, fromNode）のチェックがない！
}
```

---

## 修正内容

### FlightScheduler.java（224-234行目）

```java
// 3. 容量回復（順方向・逆方向両方）
double newCapacity = capacityManager.recoverCapacity(fromNode, toNode);

// 4. 待機UAVがいれば再ジョブ化（順方向）
if (waitingManager.hasWaitingUAV(fromNode, toNode)) {
    UAVJob waitingJob = waitingManager.dequeue(fromNode, toNode);
    if (waitingJob != null) {
        jobQueue.enqueueJob(waitingJob);
        LogManager.getInstance().log(
            "Phase 3b-3: 待機 client" + waitingJob.getClientId() + " UAV" + waitingJob.getUavId() +
            " を再ジョブ化 (link " + fromNode + "→" + toNode + ")"
        );
    }
}

// 5. 待機UAVがいれば再ジョブ化（逆方向：双方向リンク対応）
if (waitingManager.hasWaitingUAV(toNode, fromNode)) {
    UAVJob waitingJob = waitingManager.dequeue(toNode, fromNode);
    if (waitingJob != null) {
        jobQueue.enqueueJob(waitingJob);
        LogManager.getInstance().log(
            "Phase 3b-3: 待機 client" + waitingJob.getClientId() + " UAV" + waitingJob.getUavId() +
            " を再ジョブ化 (link " + toNode + "→" + fromNode + ", 逆方向リンク回復)"
        );
    }
}
```

---

## 修正後の動作

```
1. client1が0→2飛行開始 → 0→2と2→0の容量消費
2. client3が2→0飛行しようとする → 容量0で待機キュー登録
3. client1が0→2通過完了 → 0→2と2→0の容量回復
4. FlightScheduler: waiting:link:0:2をチェック → 空
5. FlightScheduler: waiting:link:2:0をチェック → client3のUAVを発見！
6. client3のUAVを再ジョブ化 → 飛行再開
```

---

## 関連修正

### MathUtils.java: roundWithTargetSum追加

EPS/PS計算後のフロー丸め込み処理で、フロー保存則を維持しながら整数化するメソッドを追加。

```java
public static void roundWithTargetSum(double[] output, int targetSum) {
    // 小数部分が大きい順にfloor+1を割り当て
    // 合計がtargetSumになるよう調整
}
```

### BinaryExtendedPhysarumSolverRouteSearcher.java / HybridPhysarumSolverRouteSearcher.java

BFSベースの`roundSourceOutflowsAndPropagate()`を実装:
- ソースノードの出力フローを丸め込み
- BFSで中間ノードを順次処理
- 各ノードで入力フロー = 出力フローを保証
- 全リンクが整数フローになることを保証

---

## 検証ポイント

| 項目 | 結果 |
|------|------|
| 順方向待機キュー処理 | 従来通り動作 |
| 逆方向待機キュー処理 | 新規追加で正常動作 |
| 双方向リンクの整合性 | 容量消費・回復が両方向で同期 |
| スタックしたUAV | 問題解消 |

---

## .gitignore更新

以下のディレクトリを追跡対象外に設定:
- `src/log/` - ログファイル
- `topology/` - トポロジー設定ファイル

---

## 今後の注意点

双方向リンクを扱う際は、以下の点に注意が必要:

1. **容量操作**: 順方向と逆方向の両方を同時に更新
2. **待機キュー**: 順方向と逆方向の両方をチェック
3. **フロー計算**: 順方向フロー = -逆方向フローの関係を維持
