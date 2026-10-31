#!/usr/bin/env python3
"""
ポーリングラグ比較グラフ生成スクリプト

【ラグの定義】
  lag = realFlightTime - distance / speed

  Redis (イベント駆動): lag ≈ 0
  MemoryPolling      : lag ∈ [0, K × polling_interval]  (K = 経路リンク数)

  → 両モードの lag CDF を比較することでポーリングの検知遅延を可視化する。

【前提条件】
  - UAV速度が固定値（flight_times.csv の speed 列を使用）
  - Dijkstra法（常に同一経路 → distance は s-d 固有値）

Usage:
    python scripts/plot_lag_comparison.py
"""

import glob
import os
import sys

import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
import numpy as np
import pandas as pd


# =============================================================================
# スタイル設定
# =============================================================================
def setup_style():
    plt.rcParams['text.color'] = 'black'
    plt.rcParams['axes.labelcolor'] = 'black'
    plt.rcParams['xtick.color'] = 'black'
    plt.rcParams['ytick.color'] = 'black'
    plt.rcParams['axes.edgecolor'] = 'black'
    plt.rcParams['xtick.direction'] = 'in'
    plt.rcParams['ytick.direction'] = 'in'

    target_fonts = [
        'IPAexGothic', 'IPAGothic', 'Noto Sans CJK JP', 'Noto Sans JP',
        'Hiragino Maru Gothic Pro', 'Hiragino Kaku Gothic ProN',
        'Yu Gothic', 'MS Gothic', 'Meiryo', 'DejaVu Sans'
    ]
    available_fonts = set(f.name for f in fm.fontManager.ttflist)
    for font_name in target_fonts:
        if font_name in available_fonts:
            plt.rcParams['font.family'] = 'sans-serif'
            plt.rcParams['font.sans-serif'] = [font_name] + plt.rcParams['font.sans-serif']
            break
    plt.rcParams['axes.unicode_minus'] = False
    plt.rcParams['agg.path.chunksize'] = 10000


setup_style()

FONT_SIZE_LABEL  = 34
FONT_SIZE_TICK   = 29
FONT_SIZE_LEGEND = 22
LINE_WIDTH       = 3.0
COLORS = ['#3498db', '#e74c3c', '#2ecc71', '#9b59b6', '#f39c12', '#1abc9c', '#e91e63', '#795548']


# =============================================================================
# データ読み込み
# =============================================================================
def load_lag(result_dir: str):
    """flight_times.csv を集約し lag を計算して返す。

    lag = realFlightTime - distance / speed

    Returns
    -------
    pd.DataFrame or None
        columns: lag, realFlightTime, theoretical, distance, speed, n_links
    """
    time_dir = os.path.join(result_dir, "time")
    # Redis モード: time/client{N}/flight_times.csv (per-client)
    csv_files = sorted(glob.glob(os.path.join(time_dir, "client*/flight_times.csv")))
    # MemoryPolling モード: time/flight_times.csv (単一ファイル)
    if not csv_files:
        single = os.path.join(time_dir, "flight_times.csv")
        if os.path.exists(single):
            csv_files = [single]

    if not csv_files:
        return None

    dfs = []
    for f in csv_files:
        try:
            df = pd.read_csv(f)
            dfs.append(df)
        except Exception as e:
            print(f"  警告: {f} 読み込み失敗 ({e})")

    if not dfs:
        return None

    all_df = pd.concat(dfs, ignore_index=True)

    required = {'realFlightTime', 'distance', 'speed'}
    missing = required - set(all_df.columns)
    if missing:
        print(f"  エラー: 必須列が見つかりません: {missing}")
        return None

    all_df = all_df[all_df['speed'] > 0].copy()
    all_df['theoretical'] = all_df['distance'] / all_df['speed']
    all_df['lag'] = all_df['realFlightTime'] - all_df['theoretical']

    # 経路リンク数（path 列がある場合）
    if 'path' in all_df.columns:
        all_df['n_links'] = all_df['path'].astype(str).apply(
            lambda p: len(p.split('-')) - 1 if '-' in str(p) else np.nan
        )
    else:
        all_df['n_links'] = np.nan

    return all_df


# =============================================================================
# 対話入力
# =============================================================================
def ask_int(prompt: str) -> int:
    while True:
        try:
            return int(input(prompt).strip())
        except ValueError:
            print("  数値を入力してください")


def ask_optional_float(prompt: str):
    val = input(prompt).strip()
    if not val:
        return None
    try:
        return float(val)
    except ValueError:
        return None


def interactive_input():
    print("=" * 80)
    print("  ポーリングラグ比較グラフ生成")
    print("=" * 80)
    print()

    num_groups = ask_int("グループ数を入力してください (例: 8): ")
    print()

    groups = []
    for i in range(num_groups):
        print(f"--- グループ{i+1} ---")
        display_name   = input(f"  表示名        (例: Redis)                    : ").strip()
        directory_name = input(f"  ディレクトリ名 (例: Dijkstra)                : ").strip()
        sim_num        = ask_int(f"  sim番号       (例: 1)                      : ")
        mode_raw       = input(f"  モード名       (空欄→large_scale, 例: memory_eventdriven) : ").strip()
        mode_name      = mode_raw if mode_raw else "large_scale"
        groups.append({
            'display_name':   display_name,
            'directory_name': directory_name,
            'sim':            sim_num,
            'mode_name':      mode_name,
        })
        result_dir = f"src/result/sim_{sim_num}/{mode_name}/{directory_name}"
        print(f"  → データ読み込み元: {result_dir}/time/client*/")
        print()

    print("【X軸スケール設定（空欄で自動）】")
    x_max      = ask_optional_float("X軸 最大値 (s)  : ")
    x_interval = ask_optional_float("X軸 間隔   (s)  : ")
    print()

    suffix = ""
    default_output = f"output/lag_comparison{suffix}.png"
    out = input(f"出力ファイル名 (デフォルト: {default_output}): ").strip()
    output_file = out if out else default_output

    return groups, x_max, x_interval, output_file


# =============================================================================
# 統計サマリ
# =============================================================================
def print_summary(all_data):
    print()
    print("=" * 80)
    print("  統計サマリ (lag = realFlightTime - distance/speed)")
    print("=" * 80)
    header = f"{'グループ':<30} {'N':>6} {'mean':>8} {'median':>8} {'p95':>8} {'max':>8}"
    print(header)
    print("-" * 80)
    for display_name, df in all_data:
        if df is None or len(df) == 0:
            print(f"{display_name:<30} {'N/A':>6}")
            continue
        lag = df['lag'].dropna()
        n      = len(lag)
        mean   = lag.mean()
        median = lag.median()
        p95    = lag.quantile(0.95)
        vmax   = lag.max()
        print(f"{display_name:<30} {n:>6} {mean:>8.3f} {median:>8.3f} {p95:>8.3f} {vmax:>8.3f}")
    print()


# =============================================================================
# CDF グラフ描画
# =============================================================================
def plot_cdf(all_data, x_max, x_interval, output_file):
    fig, ax = plt.subplots(figsize=(14, 8))

    for spine in ax.spines.values():
        spine.set_visible(True)
        spine.set_color('black')
        spine.set_linewidth(1.0)

    data_max = 0
    for idx, (display_name, df) in enumerate(all_data):
        if df is None or len(df) == 0:
            print(f"  スキップ: {display_name} (データなし)")
            continue

        lag = np.sort(df['lag'].dropna().values)
        percentiles = np.linspace(0, 100, len(lag))
        color = COLORS[idx % len(COLORS)]

        ax.plot(lag, percentiles,
                label=display_name, linewidth=LINE_WIDTH, color=color)

        if lag[-1] > data_max:
            data_max = lag[-1]

    # X軸
    ax.set_xlabel('Lag [s]',
                  fontsize=FONT_SIZE_LABEL, fontweight='bold', color='black')
    ax.tick_params(axis='x', labelsize=FONT_SIZE_TICK, direction='in', colors='black', pad=10)

    if x_max is not None:
        left_pad = x_max * 0.002
        ax.set_xlim(-left_pad, x_max)
        if x_interval is not None:
            ticks = np.arange(0, x_max + x_interval * 0.01, x_interval)
            ax.set_xticks(ticks)
            ax.set_xticklabels([f'{t:g}' for t in ticks], fontsize=FONT_SIZE_TICK)
    else:
        x_max_auto = np.ceil(data_max / (x_interval if x_interval else 1)) * (x_interval if x_interval else 1)
        left_pad = x_max_auto * 0.002
        ax.set_xlim(-left_pad, x_max_auto if x_interval else None)
        if x_interval is not None:
            ticks = np.arange(0, x_max_auto + x_interval * 0.01, x_interval)
            ax.set_xticks(ticks)
            ax.set_xticklabels([f'{t:g}' for t in ticks], fontsize=FONT_SIZE_TICK)

    # Y軸
    ax.set_ylabel('Cumulative Ratio [%]',
                  fontsize=FONT_SIZE_LABEL, fontweight='bold', color='black')
    ax.set_ylim(0, 100)
    ax.set_yticks(np.arange(0, 101, 10))
    ax.tick_params(axis='y', labelsize=FONT_SIZE_TICK, direction='in', colors='black')

    # グリッド
    ax.grid(True, alpha=0.3, linestyle='--', color='black')
    ax.set_axisbelow(True)

    # 凡例
    legend = ax.legend(loc='lower right', framealpha=1.0,
                       fontsize=FONT_SIZE_LEGEND, edgecolor='black')
    for text in legend.get_texts():
        text.set_color('black')

    plt.tight_layout()

    os.makedirs(os.path.dirname(output_file) if os.path.dirname(output_file) else '.', exist_ok=True)
    plt.savefig(output_file, dpi=300, bbox_inches='tight')
    print(f"\n✓ CDFグラフを保存しました: {output_file}")

    pdf_file = output_file.rsplit('.', 1)[0] + '.pdf'
    plt.savefig(pdf_file, bbox_inches='tight')
    print(f"✓ PDFを保存しました: {pdf_file}")
    plt.close()


# =============================================================================
# ラグ vs リンク数 散布図
# =============================================================================
def plot_lag_vs_links(all_data, output_file):
    """lag と経路リンク数の相関散布図。path 列がある場合のみ生成。"""
    has_links = any(
        df is not None and 'n_links' in df.columns and df['n_links'].notna().any()
        for _, df in all_data
    )
    if not has_links:
        return

    fig, ax = plt.subplots(figsize=(12, 8))
    for spine in ax.spines.values():
        spine.set_visible(True); spine.set_color('black'); spine.set_linewidth(1.0)

    for idx, (display_name, df) in enumerate(all_data):
        if df is None or 'n_links' not in df.columns:
            continue
        sub = df[['n_links', 'lag']].dropna()
        if sub.empty:
            continue
        color = COLORS[idx % len(COLORS)]
        ax.scatter(sub['n_links'], sub['lag'], label=display_name,
                   color=color, alpha=0.7, s=10)

    ax.set_xlabel('Number of Hops', fontsize=FONT_SIZE_LABEL, fontweight='bold', color='black')
    ax.set_ylabel('Lag [s]', fontsize=FONT_SIZE_LABEL, fontweight='bold', color='black')
    ax.tick_params(labelsize=FONT_SIZE_TICK, direction='in', colors='black')
    ax.grid(True, alpha=0.3, linestyle='--', color='black')
    ax.set_axisbelow(True)
    legend = ax.legend(loc='upper right', framealpha=1.0,
                       fontsize=FONT_SIZE_LEGEND, edgecolor='black')
    for text in legend.get_texts():
        text.set_color('black')

    plt.tight_layout()

    scatter_file = output_file.rsplit('.', 1)[0] + '_vs_links.png'
    plt.savefig(scatter_file, dpi=300, bbox_inches='tight')
    print(f"✓ 散布図を保存しました: {scatter_file}")

    pdf_file = scatter_file.rsplit('.', 1)[0] + '.pdf'
    plt.savefig(pdf_file, bbox_inches='tight')
    print(f"✓ PDFを保存しました: {pdf_file}")
    plt.close()


# =============================================================================
# CSV 出力
# =============================================================================
def save_summary_csv(all_data, output_file):
    rows = []
    for display_name, df in all_data:
        if df is None or len(df) == 0:
            continue
        lag = df['lag'].dropna()
        rows.append({
            'group':    display_name,
            'n':        len(lag),
            'mean':     round(lag.mean(), 4),
            'median':   round(lag.median(), 4),
            'std':      round(lag.std(), 4),
            'p25':      round(lag.quantile(0.25), 4),
            'p75':      round(lag.quantile(0.75), 4),
            'p95':      round(lag.quantile(0.95), 4),
            'p99':      round(lag.quantile(0.99), 4),
            'max':      round(lag.max(), 4),
        })

    if not rows:
        return

    csv_path = output_file.rsplit('.', 1)[0] + '_stats.csv'
    pd.DataFrame(rows).to_csv(csv_path, index=False)
    print(f"✓ 統計CSVを保存しました: {csv_path}")


# =============================================================================
# メイン
# =============================================================================
def main():
    groups, x_max, x_interval, output_file = interactive_input()

    print()
    print("=" * 80)
    print("データ読み込み中...")
    print("=" * 80)

    all_data = []
    for group in groups:
        result_dir = (f"src/result/sim_{group['sim']}/{group['mode_name']}"
                      f"/{group['directory_name']}")
        df = load_lag(result_dir)
        if df is None:
            print(f"警告: データが読み込めませんでした → {result_dir}")
        else:
            print(f"✓ {group['display_name']} (sim_{group['sim']}): "
                  f"{len(df)} UAV  "
                  f"lag_mean={df['lag'].mean():.3f}s  "
                  f"lag_p95={df['lag'].quantile(0.95):.3f}s")
        all_data.append((group['display_name'], df))

    print_summary(all_data)

    print("=" * 80)
    print("グラフ生成中...")
    print("=" * 80)

    plot_cdf(all_data, x_max, x_interval, output_file)
    plot_lag_vs_links(all_data, output_file)
    save_summary_csv(all_data, output_file)

    print()
    print("=" * 80)
    print("完了")
    print("=" * 80)


if __name__ == "__main__":
    main()
