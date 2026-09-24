# PS・EPS 実装分析と収束問題

## 1. PS（Physarum Solver）の理論式

### 基本方程式（tex/PS.tex より）

**（1）流量則**
```
Q_ij(t) = D_ij(t) / L_ij × (p_i(t) - p_j(t))
```

**（2）フロー保存則（Kirchhoff 則）**
```
Σ Q_ij = -Q_all  (始点ノード)
       = +Q_all  (終点ノード)
       =  0      (その他)
```

**（3）管径更新則**
```
dD_ij/dt = f(|Q_ij|) - a × D_ij

D_ij(t+Δt) = D_ij(t) + Δt × { f(|Q_ij(t)|) - a × D_ij(t) }

f(|Q|) = |Q|^μ / (1 + |Q|^μ)    (μ > 1.0 のシグモイド関数)
```

- `a`: 減衰係数（通常 1.0）
- `μ`: シグモイドの急峻さを制御するパラメータ
- 高μ → 低フロー経路を積極的に切り捨て → winner-takes-all が強く働く

### PS の収束メカニズム

シグモイド関数の非線形性により強い正のフィードバックが生まれる：
1. ある経路にわずかに多くフローが流れる
2. `f(|Q|)` が大きくなりチューブが太くなる
3. 太いチューブにさらにフローが集中
4. → 最短経路の単一チューブに全フローが収束（winner-takes-all）

---

## 2. EPS（Extended Physarum Solver）の理論式

### 文献の定義（tex/EPS/three.tex より）

EPS は PS の式 (1)(2) はそのままで、管径更新則のみを変更：

```
D_ij(t+Δt) = D_ij(t) + y_ij(t) × Δt × { f(|Q_ij(t)|) - r × D_ij(t) }

y_ij(t) = tanh( C_ij - |Q_ij(t)| )
```

**⚠️ 文献の EPS では `f(|Q|) = |Q|`（線形）、`r = 1` を使用している。**
シグモイド関数は文献の EPS には登場しない。

### y_ij の挙動

| |Q| の状態 | y_ij の値 | 挙動 |
|-----------|----------|------|
| `\|Q\| << C` | `tanh(大きな正値) ≈ 1` | PS と同一に近い動作 |
| `\|Q\| → C` | `tanh(0) → 0` | チューブ更新を制限・安定化 |
| `\|Q\| > C` | `tanh(負値) < 0` | D を縮退させ Q を C に収束 |

### EPS が意図する容量制約の実現方法

`tanh(C - |Q|)` の **符号** が容量超過を自然に検出する：
- `|Q| < C` → 正の値 → 成長促進
- `|Q| > C` → 負の値 → チューブ縮退

条件分岐（`if flow > capacity`）は不要。tanh の符号変化が自動的に境界を区別する。

---

## 3. 現在の実装（first fix 後）

```java
// ExtendedPhysarumSolverRouteSearcher.java
double degeneracyEffect = 0.5;  // 減衰係数（PSより小さい）
double tanhValue = Math.tanh((capacity - flow) * coefficient_tanh);
double deltaThickness = (Q_tubeFlow_sigmoidOutput[i][j]
                         - (degeneracyEffect * oldThickness))
                        * tanhValue * DELTA_TIME;
```

### PS 実装との対応

```java
// PhysarumSolverRouteSearcher.java
double deltaThickness = (Q_tubeFlow_sigmoidOutput[i][j]
                         - (degeneracyEffect * oldThickness)) * DELTA_TIME;
```

| パラメータ | PS 文献 | PS 実装 | EPS 文献 | EPS 実装（現在）|
|-----------|--------|--------|---------|--------------|
| `f(|Q|)` | sigmoid(|Q|^μ) | sigmoid(|Q|^4) | **|Q|（線形）** | sigmoid(|Q|^4) ※変更済 |
| 減衰係数 `a` | 1.0 | **0.5** | 1.0 | **0.5** |
| 容量制約 | なし | なし | tanh(C-|Q|) | tanh(C-|Q|) |
| GAMMA (μ) | >1 | **4.0** | - | 4.0（継承） |

---

## 4. 問題の根本原因

### 問題1：first fix 前（オリジナル EPS の実装バグ）

オリジナルの EPS 実装では GAMMA/sigmoid が計算されていたが `updateTubeThickness()` で**使用されていなかった**。
実質的に `f(|Q|) = |Q|`（線形）が使われており、文献通りではあるが PS との継続性が断絶していた。

**first fix** でシグモイドを復元 → PS と EPS が同じ `f(|Q|)` を使うように修正済み。

---

### 問題2：需要 ≈ 容量 での収束失敗（現在の問題）

`coefficient_tanh = 1.0` の場合：

| |Q| (C=5) | tanh(C-|Q|) | PS と比べた成長率 |
|----------|------------|------------|
| 1.0 | tanh(4.0) = 0.999 | ≈ 100% |
| 3.0 | tanh(2.0) = 0.964 | 96% |
| 4.0 | tanh(1.0) = 0.762 | **76%** |
| 4.5 | tanh(0.5) = 0.462 | **46%** |
| 5.0 = C | tanh(0) = **0** | **0%（停止）** |

**結果：需要 = 容量 = 5 の場合、最短経路に全 UAV が集中しようとすると `|Q| → C → tanh → 0` となり、チューブの強化が停止する。**

他の低フロー経路は `tanh ≈ 1` のまま自然減衰するが、主経路が tanh=0 で強化できないため、複数経路への分散が起きる（1 UAV ずつ別の経路になる現象）。

### 問題の本質：2つの抑制機構の競合

```
PS の winner-takes-all：
  |Q| ↑ → sigmoid ↑ → チューブ成長 ↑ → さらに |Q| ↑（正のフィードバック）

EPS の tanh による抑制：
  |Q| → C のとき tanh → 0 → チューブ成長が停止 → 正のフィードバック破綻
```

- **sigmoid** は高フロー経路を強化する（PS の核心）
- **tanh** は高フロー経路の成長を抑制する（EPS の核心）
- 需要 ≈ 容量 では両者が競合し、winner-takes-all の収束が阻害される

---

## 5. 文献の EPS が想定していたシナリオ

| シナリオ | 需要 vs 容量 | y_ij の状態 | 動作 |
|---------|------------|-----------|------|
| Scenario 1（小トラフィック） | 需要 << 容量 | tanh ≈ 1 全経路 | **PS と同一** → 最短経路に収束 |
| Scenario 2（大トラフィック） | 需要 > 単経路容量 | |Q|>C → tanh<0 | **多経路分散** → 容量制約内に収束 |
| **UAV 実験（問題）** | **需要 ≈ 容量** | tanh ≈ 0 主経路 | **収束失敗** → 1 UAV ずつ別経路 |

文献の EPS は「需要 ≈ 容量」のケースを直接検証していない。

---

## 6. 対処方針の選択肢

### 案A：`coefficient_tanh` を大きくする（例：5〜10）

```java
// AbstractPhysarumSolverRouteSearcher.java
protected double coefficient_tanh = 5.0;  // 1.0 → 5.0
```

- `tanh(5 × (C - |Q|))` ≒ 1 for `|Q| < C - 0.2`
- `|Q| = C` では依然として tanh = 0（根本解決にはならない）
- 実装変更が最小限

**評価：部分的改善のみ。|Q| = C での停止問題は残る。**

---

### 案B：tanh をシフトする

```
y_ij = tanh( C_ij - |Q_ij| + δ )    (δ > 0)
```

- `|Q| = C` のとき `y_ij = tanh(δ) > 0`（停止しない）
- `|Q| = C + δ` のとき `y_ij = 0`（停止点が容量を超えた位置にシフト）
- δ の値がパラメータとして残る

**評価：収束改善が期待できるが、厳密な容量制約が δ 分だけ緩まる。**

---

### 案C：EPS 文献の本来の式（線形 f）に戻す

```java
// EPS のみ f(|Q|) = |Q|（線形）を使用
double linearOutput = flow;  // sigmoid ではなく生の流量
double deltaThickness = (linearOutput - oldThickness) * tanhValue * DELTA_TIME;
```

- 文献の EPS に完全に忠実
- PS（sigmoid使用）と EPS（線形使用）が別アルゴリズムになる
- 「PS の上位互換」という要件は満たせない

**評価：文献的に正確だが設計要件と相反する。**

---

### 案D：領域別切り替え（検討中）

```
|Q| ≤ C のとき：PS と完全に同一（tanh を使わない）
|Q| > C のとき：tanh によるペナルティを付与
```

```java
double deltaThickness = (Q_tubeFlow_sigmoidOutput[i][j]
                         - degeneracyEffect * oldThickness) * DELTA_TIME;  // PS と同一
if (flow > capacity) {
    double tanhPenalty = Math.tanh((flow - capacity) * coefficient_tanh);
    deltaThickness -= tanhPenalty * degeneracyEffect * oldThickness * DELTA_TIME;
}
```

- 容量範囲内：PS と完全に同一 → winner-takes-all が正常に働く
- 容量超過時：tanh ペナルティで縮退
- `|Q| = C` で不連続にならない（`flow > capacity` のときのみペナルティ）

**課題：文献の式とは異なる。容量超過の「検出」が `flow` の計算精度に依存する。**

**評価：要件「PS の完全上位互換」を最も直接的に満たす設計。文献との乖離は要注意。**

---

## 7. 実装の現状まとめ

```
[文献 PS]    ΔD = (|Q|^μ / (1+|Q|^μ) - D) × Δt
[実装 PS]    ΔD = (sigmoid(|Q|^4) - 0.5D) × Δt                    ✓（μ=4, a=0.5）

[文献 EPS]   ΔD = tanh(C-|Q|) × (|Q| - D) × Δt                   （f=線形, r=1）
[実装 EPS]   ΔD = tanh(C-|Q|) × (sigmoid(|Q|^4) - 0.5D) × Δt     ※問題あり
```

現在の EPS 実装は構造的には文献と整合するが、**sigmoid + tanh の組み合わせが需要≈容量のシナリオで収束失敗を引き起こす**。この組み合わせは文献が想定していない（EPS 文献は線形 f を使用）。

---

## 8. 次のアクション

- [ ] 案D（領域別切り替え）を試験実装して EPS の収束を確認する
- [ ] 案B（tanh シフト）と案D を比較実験する
- [ ] coefficient_tanh の感度分析を実施する
- [ ] 「需要 ≈ 容量」シナリオで PS と EPS の収束挙動を定量比較する
