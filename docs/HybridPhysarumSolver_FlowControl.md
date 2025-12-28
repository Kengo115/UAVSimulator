# HybridPhysarumSolver フロー制御ロジック仕様書

## 概要

HybridPhysarumSolverは、適応的フロー制御により不安定性を予防的に検知し、UAVフローを動的に調整するアルゴリズムです。

## 基本パラメータ

| パラメータ | 値 | 説明 |
|-----------|-----|------|
| MAX_ITERATIONS | 10000 | 最大イテレーション数 |
| REQUIRED_STABLE_ITERATIONS | 500 | 収束判定に必要な連続安定回数 |
| STABILIZATION_GRACE_PERIOD | 50 | フロー変更後の安定化猶予期間（イテレーション数）|
| MINIMUM_FLOW_RATIO | 0.7 | 最小安全フロー（要求フローの70%） |

---

## フロー減少トリガー

### 1. ソースノード圧力変化率による段階的減少

**優先度: 最高（猶予期間に関係なく常時実行）**

| 変化率閾値 | 判定レベル | UAV減少量 | 説明 |
|-----------|-----------|----------|------|
| **7% ≤ 変化率 < 12%** | MODERATE | **1 UAV** | 小規模な圧力増加を検知 |
| **12% ≤ 変化率 < 15%** | HIGH | **3 UAV** | 中規模な圧力増加を検知 |
| **変化率 ≥ 15%** | CRITICAL | **5 UAV** | 大規模な圧力増加を検知 |

**定数:**
```java
SOURCE_PRESSURE_CHANGE_LEVEL1 = 0.07  // 7%
SOURCE_PRESSURE_CHANGE_LEVEL2 = 0.12  // 12%
SOURCE_PRESSURE_CHANGE_LEVEL3 = 0.15  // 15%
```

**計算方法:**
```
changeRate = (currentSourcePressure - previousSourcePressure) / previousSourcePressure
```

**重要な特徴:**
- **圧力増加時のみ反応**（圧力低下時は無視）
- 安定化猶予期間を**無視**して常時実行
- 実際のネットワーク圧力の変動に最も直接的に反応

---

### 2. ソースノード圧力絶対値による緊急減少

**優先度: 高（猶予期間に関係なく常時実行）**

| 圧力閾値 | 判定レベル | UAV減少量 | 説明 |
|---------|-----------|----------|------|
| 圧力 ≥ **85.0** | EMERGENCY | **4 UAV** | 緊急レベルの圧力異常 |

**定数:**
```java
SOURCE_PRESSURE_WARNING = 70.0     // 警告レベル（現在未使用）
SOURCE_PRESSURE_EMERGENCY = 85.0   // 緊急レベル
```

**参考データ:**
- 正常時のソース圧力: 51〜54
- 異常時のソース圧力: 87以上

---

### 3. 統合不安定性スコアによる段階的減少

**優先度: 中（猶予期間後に実行）**

| スコア閾値 | 判定レベル | UAV減少量 | 説明 |
|-----------|-----------|----------|------|
| スコア ≥ **3.0** | EARLY_WARNING | **2 UAV** | 早期警告レベル |
| スコア ≥ **5.0** | EMERGENCY | **4 UAV** | 緊急レベル |

**定数:**
```java
EARLY_WARNING_THRESHOLD = 3.0
EMERGENCY_THRESHOLD = 5.0
```

**統合スコア計算式:**
```java
totalScore = (pressureGradientScore × 2.5) +
             (pressureAbsoluteScore × 2.0) +
             (thicknessChangeScore × 1.5) +
             (sourcePressureScore × 3.0) +
             (sourcePressureChangeScore × 3.0)
```

**各スコアの閾値:**

#### 圧力勾配スコア
| 値 | 判定 |
|----|------|
| < 100.0 | 正常 |
| 100.0 〜 200.0 | 警告 |
| ≥ 200.0 | 緊急 |

#### 圧力絶対値スコア
| 値 | 判定 |
|----|------|
| < 150.0 | 正常 |
| 150.0 〜 300.0 | 警告 |
| ≥ 300.0 | 緊急 |

#### チューブ厚変化率スコア
| 値 | 判定 |
|----|------|
| < 5% | 正常 |
| 5% 〜 10% | 警告 |
| ≥ 10% | 緊急 |

---

## フロー増加トリガー

### 4. ソース圧力半減検知による増加

**優先度: 中（猶予期間に関係なく常時実行）**

| 条件 | UAV増加量 | 説明 |
|------|----------|------|
| **currentPressure < initialPressure / 2.0** | **1 UAV** | 初期圧力の半分以下で余裕あり判定 |

**定数:**
```java
// フロー増加量は固定で1 UAV（お試し実装）
```

**動作条件:**
1. 初期ソース圧力がキャプチャ済み
2. 現在の圧力が初期圧力の半分未満
3. 現在のフローが要求フロー未満

**制限:**
- 要求フローを超えて増加しない

---

## フロー制御の優先順位

実行順序（上から順に評価）：

1. **ソース圧力変化率チェック**（7%/12%/15%）
2. **ソース圧力絶対値チェック**（85以上）
3. **ソース圧力半減チェック**（増加ロジック）
4. **安定化猶予期間チェック**（50イテレーション）
5. **統合スコアチェック**（3.0/5.0）

---

## 安定化猶予期間の動作

フロー変更（減少または増加）後、50イテレーションは以下の動作：

- ✅ **実行される:** ソース圧力変化率/絶対値チェック
- ❌ **スキップされる:** 統合スコアによる不安定性検知
- ❌ **カウントされない:** `stableIterationCount`

**目的:** システムがフロー変更に適応する時間を与える

---

## 最小安全フロー制約

```
minimumFlow = max(1.0, requestedFlow × 0.7)
```

**動作:**
- フロー減少が最小安全フロー未満になる場合、減少を拒否
- その場合でも`stableIterationCount`を増加（改善不可能な状態として安定とみなす）

**例:**
- 要求フロー38の場合: 最小安全フロー = 26.6
- 要求フロー30の場合: 最小安全フロー = 21.0

---

## 収束判定

500回連続で安定（不安定性が検知されない、またはフロー減少不可）したら収束とみなす：

1. `stableIterationCount >= 500`に到達
2. `MathUtils.roundWithConservation()`で流量を整数に丸め込み
3. 結果を出力してEPSループを終了
4. 要求フロー未達の場合、PSで残りUAVを割り当て

---

## 実行フロー図

```
[イテレーション開始]
    ↓
[圧力・流量を計算]
    ↓
[初期ソース圧力をキャプチャ（初回のみ）]
    ↓
[ソース圧力変化率チェック] → 増加検知 → フロー減少（1/3/5 UAV）
    ↓                                     ↓
    ↓                                 猶予期間リセット
    ↓                                 安定カウントリセット
    ↓
[ソース圧力絶対値チェック] → 85以上 → フロー減少（4 UAV）
    ↓                                  ↓
    ↓                              猶予期間リセット
    ↓                              安定カウントリセット
    ↓
[ソース圧力半減チェック] → 半減検知 → フロー増加（1 UAV）
    ↓                                  ↓
    ↓                              猶予期間リセット
    ↓                              安定カウントリセット
    ↓
[猶予期間中？] → Yes → スキップ（カウント進めない）
    ↓ No
    ↓
[統合スコアチェック]
    ↓
    ├→ スコア ≥ 5.0 → 緊急 → フロー減少（4 UAV）
    ├→ スコア ≥ 3.0 → 警告 → フロー減少（2 UAV）
    └→ スコア < 3.0 → 安定 → stableIterationCount++
                                ↓
                        500回到達？ → Yes → 整数丸め込み → 終了
                                ↓ No
                                ↓
[次のイテレーションへ]
```

---

## フロー減少できない場合の処理

最小安全フロー制約により減少できない場合：

1. ログ出力: "Cannot reduce flow further. Treating as stable iteration."
2. `stableIterationCount++`を実行（改善不可能な状態として安定とみなす）
3. 500回到達で収束

**理由:**
- これ以上フローを減らせない = 最善の状態
- 不安定性を許容して収束を目指す

---

## 実装上の注意点

### 1. ソース圧力変化率の計算

```java
// 圧力低下時は無視（delta ≤ 0 なら score = 0.0）
double delta = currentPressure - previousSourcePressure;
if (delta <= 0.0) {
    return 0.0;
}
double changeRate = delta / previousSourcePressure;
```

### 2. 初期ソース圧力のキャプチャ

```java
if (!initialSourcePressureCaptured && sourceNode >= 0) {
    initialSourcePressure = Math.abs(P_tubePressure[sourceNode]);
    initialSourcePressureCaptured = true;
}
```

### 3. 前回ソース圧力の更新

```java
// イテレーション終了時に必ず更新
if (sourceNode >= 0) {
    previousSourcePressure = Math.abs(P_tubePressure[sourceNode]);
}
```

---

## チューニング指針

### フロー減少が積極的すぎる場合

1. **変化率閾値を上げる**
   - `SOURCE_PRESSURE_CHANGE_LEVEL1`: 0.07 → 0.10
   - `SOURCE_PRESSURE_CHANGE_LEVEL2`: 0.12 → 0.15
   - `SOURCE_PRESSURE_CHANGE_LEVEL3`: 0.15 → 0.20

2. **統合スコア閾値を上げる**
   - `EARLY_WARNING_THRESHOLD`: 3.0 → 4.0
   - `EMERGENCY_THRESHOLD`: 5.0 → 6.0

### フロー減少が消極的すぎる場合

1. **変化率閾値を下げる**
   - `SOURCE_PRESSURE_CHANGE_LEVEL1`: 0.07 → 0.05
   - `SOURCE_PRESSURE_CHANGE_LEVEL2`: 0.12 → 0.10
   - `SOURCE_PRESSURE_CHANGE_LEVEL3`: 0.15 → 0.12

2. **最小安全フロー比率を下げる**
   - `MINIMUM_FLOW_RATIO`: 0.7 → 0.6

### フロー増加が必要な場合

1. **増加量を大きくする**
   - 現在: 1 UAV → 2 UAV または 3 UAV

2. **半減閾値を緩和する**
   - 現在: 0.5（半分）→ 0.6（60%）

---

## バージョン履歴

| バージョン | 日付 | 変更内容 |
|-----------|------|---------|
| 1.0 | 2025-11-04 | 初版作成 |
| 1.1 | 2025-11-04 | ソース圧力変化率閾値を3%/5%/10%から7%/12%/15%に変更 |
| 1.2 | 2025-11-04 | 最小安全フロー比率を0.8から0.7に変更 |
| 1.3 | 2025-11-04 | フロー増加ロジック追加（初期圧力半減検知） |
| 1.4 | 2025-11-05 | MAX_FLOW_REDUCTIONS制限を廃止（振動防止は他のメカニズムで対応） |

---

## 関連ファイル

- 実装: `/src/server/route/HybridPhysarumSolverRouteSearcher.java`
- 結果出力: `/src/result/HYBRID/`
- ログ: `/src/log/simulator.log`
