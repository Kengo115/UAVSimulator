# Critical Fixes for Flow Reduction Cascade and Related Issues

**Date**: 2025年11月4日
**実装者**: Claude (Anthropic AI)
**緊急度**: 高
**状態**: 修正完了、テスト待ち

---

## 問題の概要

flow 35でのテスト実行時に、以下の深刻な問題が発見されました：

1. **フロー減少のカスケード**: flow 35 → 29 (わずか9イテレーション)
2. **負のフロー**: EPS結果に物理的に不可能な負の値
3. **Total flow sum計算エラー**: 105 (期待値: 35)
4. **ArrayIndexOutOfBoundsException**: UAV[35]へのアクセス試行
5. **欠落した出力ファイル**: test_topology_5.txt〜9.txtが存在しない

## 根本原因分析

すべての問題は**問題1: フロー減少のカスケード**から連鎖的に発生していました。

### 因果関係の連鎖

```
問題1: continue文による安定化スキップ
    ↓
フロー35→29へ急激に減少（9イテレーションで）
    ↓
EPS early termination → 負のフロー生成
    ↓
問題3: 全リンク合計によるflowSum=105の誤計算
    ↓
問題4: UAV[35]へのアクセス試行 → ArrayIndexOutOfBoundsException
```

---

## 実施した修正

### 修正1: 安定化猶予期間の実装

**ファイル**: `src/server/route/HybridPhysarumSolverRouteSearcher.java`

#### 追加した定数と変数

```java
private int iterationsSinceFlowChange = 0; // フロー変更後の経過イテレーション数
private static final int STABILIZATION_GRACE_PERIOD = 50; // 安定化猶予期間（イテレーション数）
```

#### メインループの変更

**変更前**:
```java
if (instabilityScore >= EMERGENCY_THRESHOLD) {
    if (applyUAVFlowReduction(EMERGENCY_FLOW_REDUCTION_UAV, "Emergency instability")) {
        ct++;
        continue; // ← 問題: 安定化をスキップ
    }
}
```

**変更後**:
```java
// フロー変更後の経過イテレーションをカウント
iterationsSinceFlowChange++;

// 安定化猶予期間のチェック
if (iterationsSinceFlowChange < STABILIZATION_GRACE_PERIOD) {
    // 猶予期間中：不安定性検知をスキップ
    stableIterationCount++;

    if ((ct + 1) % 10 == 0) {
        LogManager.getInstance().log("Stabilization grace period: iteration " + (ct + 1) +
                                  " (" + iterationsSinceFlowChange + "/" + STABILIZATION_GRACE_PERIOD +
                                  ") with flow " + currentFlow +
                                  " (score: " + String.format("%.3f", instabilityScore) + ")");
    }
} else if (instabilityScore >= EMERGENCY_THRESHOLD) {
    // 猶予期間終了後：通常の不安定性検知
    if (!applyUAVFlowReduction(EMERGENCY_FLOW_REDUCTION_UAV, "Emergency instability")) {
        LogManager.getInstance().log("HybridPhysarumSolver: Cannot reduce flow further. Terminating.");
        break;
    }
    // continue削除: 次のイテレーションで通常処理を継続
}
```

#### 主要な変更点

1. **`continue`文の削除**: フロー減少後も通常のイテレーション処理を継続
2. **猶予期間の導入**: フロー変更後50イテレーションは不安定性検知をスキップ
3. **カウンターのリセット**: `applyUAVFlowReduction()`内で`iterationsSinceFlowChange = 0`

#### 期待される効果

```
変更前:
Iteration 6: flow 35→34 (continue)
Iteration 7: flow 34→33 (continue)  ← すぐに次の減少
Iteration 8: flow 33→31 (continue)
Iteration 9: flow 31→29 (continue)

変更後:
Iteration 6: flow 35→34
Iterations 7-56: 安定化猶予期間 (flow 34維持)
Iteration 57以降: 不安定性検知再開
→ flow 34で安定、または必要に応じてさらに減少
```

---

### 修正2: 負のフロー検証の追加

**ファイル**: `src/server/route/HybridPhysarumSolverRouteSearcher.java`

#### 追加した検証ロジック

```java
// 全リンクの詳細ログ出力と整数チェック
for (int i = 0; i < node; i++) {
    for (int j = 0; j < node; j++) {
        if (link[i][j].getL_tubeLength() != INF && link[i][j].getQ_tubeFlow() != 0) {
            double flowValue = link[i][j].getQ_tubeFlow();

            if (Math.abs(flowValue - Math.floor(flowValue)) > 0.001) {
                LogManager.getInstance().log("WARNING: Non-integer flow detected! Link(" + i + "," + j + ") = " + flowValue);
            }

            if (flowValue < 0) {
                LogManager.getInstance().log("ERROR: Negative flow detected! Link(" + i + "," + j + ") = " + flowValue + " (physically impossible)");
            }
        }
    }
}
```

#### 効果

- 負のフロー検出時に明確なエラーログを出力
- デバッグと原因究明を容易化
- 将来的なバリデーション強化の基礎

---

### 修正3: Total Flow Sum計算の修正

**ファイル**: `src/server/route/HybridPhysarumSolverRouteSearcher.java`

#### 変更内容

**変更前** (誤った計算):
```java
double totalFlowSum = 0.0;
for (int i = 0; i < node; i++) {
    for (int j = 0; j < node; j++) {
        if (link[i][j].getL_tubeLength() != INF && link[i][j].getQ_tubeFlow() > 0) {
            totalFlowSum += link[i][j].getQ_tubeFlow(); // 全リンクを合計
        }
    }
}
// 結果: 31+5+31+5+31+1+1 = 105 (誤り)
```

**変更後** (正しい計算):
```java
// ソースノードからの流出を集計（正しい総フロー値）
double sourceOutflowSum = 0.0;
for (int j = 0; j < node; j++) {
    if (link[sourceNode][j].getL_tubeLength() != INF && link[sourceNode][j].getQ_tubeFlow() > 0) {
        sourceOutflowSum += link[sourceNode][j].getQ_tubeFlow();
    }
}
LogManager.getInstance().log("HybridPhysarumSolver: Source outflow sum = " + sourceOutflowSum + " (expected: " + requiredUAVs + ")");
// 結果: 31+5 = 36 (または要求値に近い値)
```

#### 理由

ネットワークフローでは、総フロー量は**ソースノードからの流出合計**で定義されます。
双方向リンクを持つネットワークで全リンクを合計すると、各フローが複数回カウントされます。

#### 効果

- 正確なフロー合計値の報告
- ArrayIndexOutOfBoundsExceptionの防止
- UAV割り当てロジックの正常動作

---

## 修正箇所の要約

| 修正項目 | 変更行数 | 影響範囲 |
|---------|---------|---------|
| 定数・変数追加 | +2 | クラスフィールド |
| 初期化処理 | +1 | search()メソッド開始部 |
| メインループ | +20, -10 | 不安定性検知ロジック |
| solver failure処理 | +3, -3 | エラーハンドリング |
| フロー減少メソッド | +1 | applyUAVFlowReduction() |
| フロー合計計算 | +25, -15 | 最終検証ロジック |
| **合計** | **+52, -28** | 主に search()メソッド |

---

## テスト計画

### テストケース1: 最大フロー超過 (flow 35)

**条件**:
- ネットワーク最大フロー: 30
- 要求フロー: 35

**期待結果**:
1. フロー35から開始
2. 数回の減少を経て、30前後で安定収束
3. 各フロー変更後、50イテレーションの安定化期間
4. 負のフローが発生しない
5. Source outflow sum = 要求値（または収束値）
6. ArrayIndexOutOfBoundsExceptionが発生しない
7. すべての出力ファイルが生成される

**実行前のログ（参考）**:
```
Iteration 6: flow 35→34 (score 3.976)
Iteration 7: flow 34→33 (score 4.756)
Iteration 8: flow 33→31 (score 5.653)
Iteration 9: flow 31→29 (score 6.0)
Terminated with flow 29 after 9 iterations
```

**期待されるログ**:
```
Iteration 6: flow 35→34 (score 3.976)
Iterations 7-10: Stabilization grace period with flow 34
Iterations 11-20: Stabilization grace period with flow 34
...
Iterations 51-56: Stabilization grace period with flow 34
Iteration 57: flow 34 (score < 3.0, 安定判定)
Iteration 58-607: flow 34で安定継続
Converged with flow 34
```

### テストケース2: 最大フロー以下 (flow 25)

**条件**:
- ネットワーク最大フロー: 30
- 要求フロー: 25

**期待結果**:
- flow 25で安定収束（減少なし）
- 500イテレーションで収束
- 負のフローなし
- 正確なフロー合計

### テストケース3: パフォーマンス測定

**測定項目**:
- 総イテレーション数
- 収束時間
- フロー減少回数
- 最終フロー値と最大フローの差

---

## 既知の制限事項

1. **固定の猶予期間**: STABILIZATION_GRACE_PERIOD = 50は固定値
   - ネットワークサイズに応じた動的調整は未実装
   - 将来的に適応的な猶予期間長の実装を検討

2. **負のフロー処理**: 検出のみで、自動修正は未実装
   - 現状は修正1により負のフローが発生しないことを期待
   - 万が一発生した場合はログ出力のみ

3. **PS統合時の検証**: PS結果の整数丸め込みとEPSとの統合
   - EPS+PS統合後の再検証は実装済み
   - ただし、負のフロー発生時の自動リカバリは未実装

---

## 次のステップ

### Phase 1: 基本検証（最優先）
- [x] コンパイル確認
- [ ] テストケース1実行（flow 35）
- [ ] ログ出力の確認
- [ ] 結果ファイルの確認

### Phase 2: 追加テスト（推奨）
- [ ] テストケース2実行（flow 25）
- [ ] 複数の要求フロー値でテスト（20, 30, 35, 40）
- [ ] 異なるネットワークトポロジーでテスト

### Phase 3: 性能評価（オプション）
- [ ] 収束時間の測定
- [ ] 最大フロー到達率の評価
- [ ] 猶予期間長の最適化実験

### Phase 4: 長期的改善（将来的）
- [ ] 適応的な猶予期間長の実装
- [ ] 負のフロー自動修正機能
- [ ] トレンドベース不安定性検知の再導入（改良版）

---

## 関連ドキュメント

- [2025-11-04-flow-reduction-stabilization-analysis.txt](2025-11-04-flow-reduction-stabilization-analysis.txt): 初回分析レポート
- [HybridPhysarumSolverRouteSearcher.java](../../src/server/route/HybridPhysarumSolverRouteSearcher.java): 修正済みソースコード

---

## 変更履歴

| 日付 | 変更内容 | 担当者 |
|-----|---------|--------|
| 2025-11-04 | 初版作成、全修正実装 | Claude |
| 2025-11-04 | コンパイル確認完了 | Claude |

---

**実施日時**: 2025年11月4日
**分析・実装者**: Claude (Anthropic AI)
**レビュー待ち**: 二宮研究室
**緊急度**: 高（flow 35テスト結果に深刻な影響）
