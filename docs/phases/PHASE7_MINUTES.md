# Phase 7 実装議事録：4フェーズ制御とシミュレーション記録機能

**実施日**: 2025-01-13
**担当者**: Claude (Opus 4.5)

---

## 概要

Phase 7では、シミュレーションを4つのフェーズで制御する機構と、
詳細な記録・分析機能を実装しました。これにより、UAV空域の混雑状況を
段階的に変化させ、各フェーズの統計情報を収集・分析できるようになりました。

---

## 4フェーズ制御システム

### フェーズ構成

| フェーズ | 名称 | 目的 | 遷移条件 |
|---------|------|------|----------|
| Phase 1 | 生成期 | 空域を初期混雑状態へ | 混雑率≥50% かつ 60秒経過 |
| Phase 2 | 安定期 | 混雑率を50-60%で維持 | 最低20分経過 かつ 混雑率50-60%で連続10回安定 |
| Phase 3 | 混雑期 | 高負荷でUAV大量投入 | 設定時間（10分）経過 |
| Phase 4 | 回復期 | 新規生成停止、既存UAV完了待ち | シミュレーション終了まで |

### 設定ファイル

**場所**: `config/simulation_params.json`

```json
{
  "seed": 12345,
  "duration_minutes": 90,
  "snapshot_interval_sec": 10,
  "phases": {
    "phase1_generation": {
      "min_uav_count": 3,
      "max_uav_count": 10,
      "min_interval_sec": 1.0,
      "max_interval_sec": 3.0,
      "congestion_threshold_percent": 50.0,
      "min_duration_sec": 60
    },
    "phase2_stable": {
      "min_uav_count": 1,
      "max_uav_count": 5,
      "duration_minutes": 20,
      "dynamic_interval_enabled": true,
      "target_congestion_percent": 50.0,
      "min_interval_sec": 10.0,
      "max_interval_sec": 20.0
    },
    "phase3_congestion": {
      "min_uav_count": 5,
      "max_uav_count": 15,
      "duration_minutes": 10,
      "min_interval_sec": 1.0,
      "max_interval_sec": 2.0
    },
    "phase4_recovery": {
      "min_uav_count": 0,
      "max_uav_count": 0,
      "dynamic_interval_enabled": false
    }
  }
}
```

---

## 新規追加・修正ファイル

### 1. PhaseController.java

**場所**: `src/server/scheduler/PhaseController.java`

4フェーズ制御を管理するシングルトンクラス。

**主な機能**:
- フェーズ遷移の自動判定（100msごとにチェック）
- 混雑率に基づく動的クライアント生成制御
- Phase 2での安定判定（50-60%の範囲で連続10回）
- フェーズ統計情報の収集・出力

**Phase 2の動的調整ロジック**:
```java
// 混雑率が上限(60%)を超えている場合、クライアント生成を停止
if (currentCongestion > STABLE_CONGESTION_MAX) {
    return null;  // 生成停止
}

// 混雑率に応じてUAV数を調整
if (currentCongestion < STABLE_CONGESTION_MIN) {
    // 混雑率が低い → UAV数を多めに
    minUav = p2.getMaxUavCount();
    maxUav = p2.getMaxUavCount();
} else {
    // 安定範囲内 → 少なめに維持
    minUav = p2.getMinUavCount();
    maxUav = p2.getMinUavCount();
}
```

### 2. LinkStatusRecorder.java

**場所**: `src/server/util/LinkStatusRecorder.java`

リンク状態を時系列で記録するクラス。

**主な機能**:
- リンクごとのUAV数（飛行中・待機中）を記録
- 10秒ごとのスナップショット記録
- 混雑率（AverageLoadRate, CongestedLinkRate）の計算・記録

### 3. SimulationConfig.java

**場所**: `src/server/config/SimulationConfig.java`

JSONファイルからシミュレーション設定を読み込むクラス。

---

## 出力ファイル

### 1. フェーズ遷移記録

**ファイル**: `src/result/{scale}/{method}/phase_transitions.csv`

```csv
timestamp,phase,congestion_rate,congested_link_rate
1,1,0.00,0.00
1148532,2,50.04,30.17
3166092,3,50.00,28.33
3766092,4,65.79,35.92
```

### 2. フェーズ統計情報

**ファイル**: `src/result/{scale}/{method}/phase_statistics.csv`

```csv
phase,phase_name,start_time_sec,end_time_sec,duration_sec,client_count,total_uav_count,avg_congestion_rate,min_congestion_rate,max_congestion_rate,end_congestion_rate
1,生成期,0.0,1148.5,1148.5,91,574,23.56,0.00,50.04,50.00
2,安定期,1148.5,3166.1,2017.6,60,232,48.94,47.21,52.33,50.00
3,混雑期,3166.1,3609.6,443.5,35,272,56.12,50.00,65.79,65.79
4,回復期,3609.6,5400.0,1790.4,0,0,45.23,25.12,65.79,25.12
```

### 3. 混雑率記録

**ファイル**: `src/result/{scale}/{method}/link_status/congestion_rate.csv`

10秒ごとの混雑率を記録。

```csv
time,AverageLoadRate,CongestedLinkRate
10,0.00,0.00
20,0.42,0.42
30,0.67,0.67
...
3600,45.23,28.50
```

### 4. リンク状態記録

**ファイル**: `src/result/{scale}/{method}/link_status/link_status.csv`

各リンクのイベント（進入・退出・待機開始・待機終了）を記録。

```csv
timestamp,link_from,link_to,flying_count,waiting_count,capacity,load_rate,event
0.12,5,12,1,0,10.0,10.00,ENTER
0.45,5,12,2,0,10.0,20.00,ENTER
...
```

### 5. スナップショット

**ファイル**: `src/result/{scale}/{method}/snapshot/snapshot_{timestamp}.csv`

10秒ごとの全リンク状態のスナップショット。

```csv
link_from,link_to,flying_count,waiting_count,capacity,load_rate
0,1,3,0,10.0,30.00
0,5,2,1,8.0,25.00
...
```

---

## 分析ツール

### ヒートマップ生成

**スクリプト**: `scripts/plot_congestion_heatmap.py`

スナップショットからネットワーク混雑ヒートマップを生成。

```bash
# 単一スナップショット
make heatmap SNAPSHOT=src/result/large_scale/Bisectional/snapshot/snapshot_1420045.csv

# 全スナップショット一括生成
make heatmap-all RESULT_DIR=src/result/large_scale/Bisectional
```

**出力先**: `output/heatmap/{method}/phase{N}_heatmap_{timestamp}.png`

- ファイル名にフェーズ番号を含める（phase_transitions.csvから判定）
- サーチャー名ごとにサブディレクトリを自動生成

### 平均飛行ステータス集計

**スクリプト**: `scripts/aggregate_flight_status.py`

全クライアントのUAV飛行データを集計。

```bash
make aggregate-flight RESULT_DIR=src/result/large_scale/Bisectional
```

**出力**: `src/result/{scale}/{method}/time/ave_flightStatus.csv`

```csv
metric,value,unit
avg_flight_time,1054.38,seconds
avg_waiting_time,286.19,seconds
avg_distance,9192.01,meters
total_uav_count,683,count
total_client_count,186,count
```

---

## 混雑率の測定方法

### 1. AverageLoadRate（平均負荷率）

全リンクの負荷率の平均値。

```
AverageLoadRate = Σ(各リンクの負荷率) / リンク数
負荷率 = (飛行中UAV数 / 容量) × 100%
```

### 2. CongestedLinkRate（混雑リンク率）

負荷率80%以上のリンクの割合。

```
CongestedLinkRate = (負荷率≥80%のリンク数 / 全リンク数) × 100%
```

---

## Phase 2 安定化ロジック

### 遷移条件

1. **最低継続時間**: `duration_minutes`（デフォルト20分）経過
2. **安定判定**: 混雑率50-60%で連続10回（100ms×10 = 1秒）

### 動的調整

| 混雑率 | クライアント生成 | UAV数 |
|--------|-----------------|-------|
| < 50% | 継続 | maxUavCount（多め） |
| 50-60% | 継続 | minUavCount（少なめ） |
| > 60% | **停止** | - |

---

## 実行例

```bash
# シミュレーション実行
make run

# 出力例
=== フェーズ統計サマリー ===
Phase1 (生成期): 時間=1148.5秒, クライアント数=91, UAV数=574, 平均混雑率=23.6%, 最終混雑率=50.0%
Phase2 (安定期): 時間=2017.6秒, クライアント数=60, UAV数=232, 平均混雑率=48.9%, 最終混雑率=50.0%
Phase3 (混雑期): 時間=443.5秒, クライアント数=35, UAV数=272, 平均混雑率=56.1%, 最終混雑率=65.8%
Phase4 (回復期): 時間=1790.4秒, クライアント数=0, UAV数=0, 平均混雑率=45.2%, 最終混雑率=25.1%
===========================
```

---

## Makefile ターゲット

| ターゲット | 説明 |
|-----------|------|
| `make run` | シミュレーション実行（コンパイル込み） |
| `make run-quick` | シミュレーション実行（コンパイルなし） |
| `make heatmap` | 単一スナップショットのヒートマップ生成 |
| `make heatmap-all` | 全スナップショットのヒートマップ一括生成 |
| `make aggregate-flight` | 平均飛行ステータス集計 |

---

## 関連定数

**PhaseController.java内の定数**:

```java
private static final int STABLE_THRESHOLD = 10;        // 連続10回で安定とみなす
private static final double STABLE_CONGESTION_MIN = 50.0;  // 安定判定の下限
private static final double STABLE_CONGESTION_MAX = 60.0;  // 安定判定の上限
```

---

## 今後の改善案

1. **安定範囲の設定ファイル化**: `STABLE_CONGESTION_MIN/MAX`を設定ファイルから読み込み可能に
2. **Phase 2の早期収束**: 安定判定を緩和（連続回数削減、範囲拡大）
3. **リアルタイム可視化**: シミュレーション中のグラフ表示
