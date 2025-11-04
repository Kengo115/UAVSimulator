# EPS異常検出メカニズムの実装

**日付**: 2025年11月4日
**実装者**: Claude (Anthropic AI)
**緊急度**: 最高
**状態**: 実装完了、テスト待ち

---

## 発見された深刻な問題

### 症状

テスト実行で以下の深刻な問題が発生：

1. **イテレーション1-279**: flow 35で継続、スコア0.15前後（「安定」と誤判定）
2. **イテレーション280**: Solver failure → flow 33へ減少
3. **イテレーション287**: Solver failure → flow 31へ減少（わずか7イテレーション後）
4. **イテレーション304**: Solver failure → flow 29へ減少（わずか17イテレーション後）
5. **イテレーション355**: Solver failure → 減少不可、早期終了

### 根本原因の分析

#### test_topology_279.txt (iteration 279, flow 35)
```
Link(0,2): 35.0424  ← 全フローが1つのリンクに集中（100%）
Link(0,3): -0.0424  ← 負のフロー（物理的に不可能）
Link(2,5): 35.0424
Link(3,5): -0.0424  ← 負のフロー
```

**経路**: 0 → 2 → 5（単一経路に全フロー集中）

#### test_topology_355.txt (iteration 355, flow 29)
```
Link(0,2): 29.0080  ← 依然として100%集中
Link(0,3): -0.0080  ← 負のフロー持続
Link(2,5): 29.0080
Link(3,5): -0.0080  ← 負のフロー持続
```

**同じパターン**: フローを減少させても流量分布は変わらない

### 問題の本質

1. **EPSが異常状態に陥っている**:
   - 管の太さが負またはゼロ近くになる
   - 単一経路に全フロー集中
   - 負のフローが発生・持続

2. **不安定性スコアが検出できない**:
   - スコア0.15前後で「安定」と誤判定
   - 圧力勾配、圧力絶対値、チューブ厚変化率では検出不可
   - EPSの構造的異常を反映していない

3. **ユーザーの指摘の正しさ**:
   > 「EPSが適切にバランスできていない」
   > 「管の太さが負になると、EPSをリセットしない限り適切にバランスできない」
   > 「EPSをリセットすることは考えていないので、不安定を検知してフローを適応的に減少させることが重要」

---

## 実装した解決策

### 1. EPS異常検出メカニズムの追加

新しい閾値定数：
```java
private static final double NEGATIVE_THICKNESS_THRESHOLD = 0.01;  // 管の太さ < 0.01で異常
private static final double FLOW_CONCENTRATION_RATIO = 0.7;      // 単一リンクに70%以上集中で異常
private static final double NEGATIVE_FLOW_THRESHOLD = -0.001;    // 負のフローの閾値
```

新しい検出メソッド：
```java
private boolean detectEPSAnomalies() {
    // 1. 負の管の太さ検出
    for (int i = 0; i < node; i++) {
        for (int j = 0; j < node; j++) {
            if (link[i][j].getL_tubeLength() != INF) {
                double thickness = link[i][j].getD_tubeThickness();
                if (thickness < NEGATIVE_THICKNESS_THRESHOLD) {
                    LogManager.getInstance().log("EPS anomaly detected: Negative or near-zero thickness at link(" + i + "," + j + ") = " + thickness);
                    return true;
                }
            }
        }
    }

    // 2. 負のフロー検出
    for (int i = 0; i < node; i++) {
        for (int j = 0; j < node; j++) {
            if (link[i][j].getL_tubeLength() != INF) {
                double flow = link[i][j].getQ_tubeFlow();
                if (flow < NEGATIVE_FLOW_THRESHOLD) {
                    LogManager.getInstance().log("EPS anomaly detected: Negative flow at link(" + i + "," + j + ") = " + flow);
                    return true;
                }
            }
        }
    }

    // 3. 流量集中度検出（ソースノードからの流出のみを考慮）
    if (sourceNode >= 0) {
        double maxOutflow = 0.0;
        double totalOutflow = 0.0;
        for (int j = 0; j < node; j++) {
            if (link[sourceNode][j].getL_tubeLength() != INF) {
                double flow = link[sourceNode][j].getQ_tubeFlow();
                if (flow > 0) {
                    if (flow > maxOutflow) {
                        maxOutflow = flow;
                    }
                    totalOutflow += flow;
                }
            }
        }

        if (totalOutflow > 0 && maxOutflow / totalOutflow > FLOW_CONCENTRATION_RATIO) {
            LogManager.getInstance().log("EPS anomaly detected: Flow concentration ratio = " + (maxOutflow / totalOutflow) +
                                      " (max=" + maxOutflow + ", total=" + totalOutflow + ", threshold: " + FLOW_CONCENTRATION_RATIO + ")");
            return true;
        }
    }

    return false;
}
```

### 2. 不安定性スコアへの統合

`calculatePreventiveInstabilityScore()`を修正：
```java
private double calculatePreventiveInstabilityScore() {
    double pressureGradientScore = calculatePressureGradientScore();
    double pressureAbsoluteScore = calculatePressureAbsoluteScore();
    double thicknessChangeScore = calculateThicknessChangeScore();

    // 統合スコア計算（重み付き合計）
    double totalScore = (pressureGradientScore * 2.5) +
                       (pressureAbsoluteScore * 2.0) +
                       (thicknessChangeScore * 1.5);

    // EPS異常状態が検出された場合は緊急レベルのスコアを返す
    if (detectEPSAnomalies()) {
        LogManager.getInstance().log("EPS anomaly detected, returning emergency-level instability score");
        return EMERGENCY_THRESHOLD + 1.0; // 緊急閾値（5.0）を超える値（6.0）を返す
    }

    return totalScore;
}
```

### 3. フィールドの追加

異常検出で使用するため：
```java
private int sourceNode = -1; // ソースノードID（異常検出で使用）
private int destNode = -1;   // デスティネーションノードID
```

`search()`メソッド内で初期化：
```java
this.sourceNode = client.getFlow().getSource().getId();
this.destNode = client.getFlow().getDestination().getId();
```

---

## 期待される動作

### 以前の動作（問題あり）

```
Iteration 1-279: flow 35, スコア 6.0 → 0.15 (徐々に低下)
  → 「安定」と誤判定
  → EPSは異常状態だが検出されない

Iteration 280: Solver failure → flow 33 (緊急対応)
Iteration 287: Solver failure → flow 31
Iteration 304: Solver failure → flow 29
Iteration 355: Solver failure → 終了
```

### 修正後の期待動作

```
Iteration 1-50: flow 35, 初期収束
Iteration 51-N: flow 35, 流量分布が単一経路に集中し始める
  → detectEPSAnomalies()が検出
  → 流量集中度 = maxOutflow / totalOutflow > 0.7
  → スコア = 6.0 (緊急レベル)
  → Emergency instability detected
  → flow 33へ減少

Iteration N+1 - N+50: 安定化猶予期間（flow 33）
Iteration N+51以降: flow 33で通常動作、またはさらなる調整
```

**キーポイント**:
- **より早期の検出**: イテレーション280より前に異常を検出
- **構造的異常の検出**: スコアの数値だけでなく、流量分布の構造を監視
- **予防的対応**: EPSが完全に崩壊する前にフロー減少

---

## test_topology_279での検証

### 検出される異常

1. **負のフロー**:
   ```
   Link(0,3): -0.0424 < -0.001  ✓ 検出
   Link(3,5): -0.0424 < -0.001  ✓ 検出
   ```

2. **流量集中度**:
   ```
   ソースノード0からの流出:
   - Link(0,2): 35.0424 (maxOutflow)
   - Link(0,3): -0.0424 (負なのでカウントしない)
   - totalOutflow = 35.0424
   - 集中度 = 35.0424 / 35.0424 = 1.0 > 0.7  ✓ 検出
   ```

### 期待されるログ出力

```
EPS anomaly detected: Negative flow at link(0,3) = -0.0424
EPS anomaly detected, returning emergency-level instability score
HybridPhysarumSolver: Emergency instability detected (score=6.0) at iteration X
HybridPhysarumSolver: Flow reduced by 2.0 UAVs due to Emergency instability. New flow: 33.0
```

---

## 実装の詳細

### 変更統計

| 修正項目 | 変更行数 | 影響範囲 |
|---------|---------|---------|
| 定数追加 | +3 | EPS異常検出閾値 |
| フィールド追加 | +2 | sourceNode, destNode |
| detectEPSAnomalies() | +55 | 新規メソッド |
| calculatePreventiveInstabilityScore() 修正 | +7 | 異常検出統合 |
| search() 初期化修正 | +2 | フィールド設定 |
| **合計** | **+69行** | HybridPhysarumSolverRouteSearcher.java |

### 検出の優先順位

1. **最優先**: 負のフロー（物理的に不可能）
2. **高優先**: 負の管の太さ（EPSの数学的破綻）
3. **中優先**: 流量集中度（ネットワークの不均衡）

いずれか1つでも検出されたら、即座に緊急レベルのスコアを返します。

---

## テスト計画

### テストケース1: flow 35（前回失敗したケース）

**期待結果**:
1. イテレーション50-100頃: 流量集中度または負のフロー検出
2. スコア = 6.0（緊急レベル）
3. flow 35 → 33への減少
4. 安定化猶予期間（50イテレーション）
5. flow 33で収束、または必要に応じてさらに減少
6. **Solver failureの回数が大幅に減少**

**検証項目**:
- [ ] 異常検出のログが出力される
- [ ] 280イテレーション前に検出される
- [ ] Solver failureが減少する
- [ ] 最終的にバランスの取れた流量分布になる

### テストケース2: flow 25

**期待結果**:
- 異常検出されない（ネットワーク容量内）
- flow 25で安定収束
- 500イテレーションで完了

### テストケース3: さまざまなflow値

- flow 30: 最大フロー付近、わずかな調整で収束
- flow 40: 大幅超過、複数回の減少が必要

---

## 制限事項と今後の改善

### 現在の制限事項

1. **閾値の固定値**:
   - FLOW_CONCENTRATION_RATIO = 0.7は経験的な値
   - ネットワークトポロジーに応じた動的調整は未実装

2. **検出頻度**:
   - 各イテレーションで3つのループを実行（O(N²)）
   - 大規模ネットワークではコスト増加の可能性

3. **EPSリセットの欠如**:
   - ユーザーの方針により、EPSリセットは実装しない
   - 異常検出後はフロー減少のみで対応

### 今後の改善案

1. **適応的閾値**:
   - ネットワークサイズに応じてFLOW_CONCENTRATION_RATIOを調整
   - 例: ノード数が多い場合は閾値を緩和

2. **段階的検出**:
   - 初期イテレーションは検出をスキップ（収束前の揺らぎを無視）
   - 例: iteration < 20では異常検出を無効化

3. **統計的アプローチ**:
   - 過去N回の流量分布を記録
   - トレンドや振動パターンを検出

4. **機械学習ベース**:
   - 過去の実行データから異常パターンを学習
   - より精度の高い早期検出

---

## 関連ドキュメント

- [2025-11-04-critical-fixes-flow-cascade.md](2025-11-04-critical-fixes-flow-cascade.md): 初回修正（安定化猶予期間）
- [2025-11-04-flow-reduction-stabilization-analysis.txt](2025-11-04-flow-reduction-stabilization-analysis.txt): 初期分析
- [HybridPhysarumSolverRouteSearcher.java](../../src/server/route/HybridPhysarumSolverRouteSearcher.java): 修正済みソースコード

---

## 結論

EPSの構造的異常（負のフロー、管の太さ異常、流量集中）を検出する新しいメカニズムを実装しました。これにより：

1. **より早期の検出**: スコアの数値だけでなく、流量分布の構造を監視
2. **予防的対応**: EPSが完全に崩壊する前にフロー減少
3. **Solver failure回避**: 異常状態に陥る前に対処

ユーザーが報告した「EPSが適切にバランスできていない」問題は、この修正により大幅に改善されると期待されます。

---

**実施日時**: 2025年11月4日
**分析・実装者**: Claude (Anthropic AI)
**レビュー待ち**: 二宮研究室
**緊急度**: 最高
**コンパイル**: 成功 ✓
