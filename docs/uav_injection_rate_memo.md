# UAV投入率(λ)と事業者発生間隔の関係

## 概要

統計的シミュレーション (`config/statistical_params.json`) において、UAV投入率 λ (lambda) と事業者発生間隔 (`mean_interval_sec`) の関係をまとめる。

## 計算式

```
λ (UAV/秒) = uav_count_per_batch / mean_interval_sec
```

したがって：

```
mean_interval_sec = uav_count_per_batch / λ
```

## UAV_count = 10 の場合

### 実験用パラメータ (λ = 0.5, 1, 2, 3)

| λ (UAV/秒) | uav_count_per_batch | mean_interval_sec | 備考 |
|------------|---------------------|-------------------|------|
| 0.5 | 10 | 20.0 秒 | 低負荷 |
| 1 | 10 | 10.0 秒 | 中負荷 |
| 2 | 10 | 5.0 秒 | 高負荷 |
| 3 | 10 | 3.333 秒 | 超高負荷 |

### 追加パラメータ (λ = 4, 6)

| λ (UAV/秒) | uav_count_per_batch | mean_interval_sec | 備考 |
|------------|---------------------|-------------------|------|
| 4 | 10 | 2.5 秒 | 極高負荷 |
| 6 | 10 | 1.667 秒 | 限界負荷 |

## 設定例

### λ = 0.5 (低負荷)
```json
{
  "arrival": {
    "mean_interval_sec": 20.0,
    "uav_count_per_batch": 10,
    "distribution": "exponential"
  }
}
```

### λ = 1 (中負荷)
```json
{
  "arrival": {
    "mean_interval_sec": 10.0,
    "uav_count_per_batch": 10,
    "distribution": "exponential"
  }
}
```

### λ = 2 (高負荷)
```json
{
  "arrival": {
    "mean_interval_sec": 5.0,
    "uav_count_per_batch": 10,
    "distribution": "exponential"
  }
}
```

### λ = 3 (超高負荷)
```json
{
  "arrival": {
    "mean_interval_sec": 3.333,
    "uav_count_per_batch": 10,
    "distribution": "exponential"
  }
}
```

### λ = 4 (極高負荷)
```json
{
  "arrival": {
    "mean_interval_sec": 2.5,
    "uav_count_per_batch": 10,
    "distribution": "exponential"
  }
}
```

### λ = 6 (限界負荷)
```json
{
  "arrival": {
    "mean_interval_sec": 1.667,
    "uav_count_per_batch": 10,
    "distribution": "exponential"
  }
}
```

## 注意事項

- `distribution: "exponential"` の場合、`mean_interval_sec` は指数分布の平均値（期待値）となる
- 実際の発生間隔はランダムに変動する（指数分布に従う）
- λ が大きいほどネットワーク負荷が高くなり、EPS収束が困難になる可能性がある

## 関連ファイル

- `config/statistical_params.json` - 統計的シミュレーション設定
- `src/server/scheduler/StatisticalSimulationController.java` - シミュレーション制御

---
作成日: 2026-01-25
