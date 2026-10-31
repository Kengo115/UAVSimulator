#!/usr/bin/env python3
"""
空域全体の飛行中UAV台数の時系列グラフを生成するスクリプト

【集計方式: LOCF (Last Observation Carried Forward)】
link_status.csv はイベント駆動記録のため、groupby-sum では
イベントが疎な終盤で実際より大幅に少ない値になるアーティファクトが生じる。
LOCF では各リンクの flying_count を「最後に記録された値」として保持し、
ビン境界時刻ごとに全リンクの最終既知値を合算する。

Usage:
    python plot_total_flying.py <result_dir> <output_dir> [y_max] [y_interval] [--log] [--bin N]

Example:
    python plot_total_flying.py src/result/sim_1/large_scale/Bisectional output/graphs
    python plot_total_flying.py src/result/sim_1/large_scale/Bisectional output/graphs 50
    python plot_total_flying.py src/result/sim_1/large_scale/Bisectional output/graphs 50 10
    python plot_total_flying.py src/result/sim_1/large_scale/Bisectional output/graphs --log
    python plot_total_flying.py src/result/sim_1/large_scale/Bisectional output/graphs --bin 60
"""

import argparse
import os
import sys

import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
import numpy as np
import pandas as pd


# --- スタイル設定 ---
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


# --- LOCF 集計 ---
def load_total_flying(csv_path: str, bin_s: float = 60.0):
    """link_status.csv を読み込み、各ビン境界時刻の合計飛行中UAV台数を返す。

    【算出方法: LOCF (Last Observation Carried Forward)】
    各リンクの flying_count を「最後に記録された値」として保持し、
    ビン境界時刻 t ごとに全リンクの最終既知値を合算する。
    """
    df = pd.read_csv(csv_path)
    if 'flying_count' not in df.columns:
        print(f"Error: 'flying_count' column not found in {csv_path}")
        print(f"  Available columns: {list(df.columns)}")
        sys.exit(1)

    # リンク・タイムスタンプ順にソート
    df = df.sort_values(['link_from', 'link_to', 'timestamp']).reset_index(drop=True)
    t_max = float(df['timestamp'].max())

    # ビン境界タイムスタンプ列を生成（0, bin_s, 2*bin_s, ..., t_max）
    bin_ts = np.arange(0.0, t_max, bin_s)
    if len(bin_ts) == 0 or bin_ts[-1] < t_max:
        bin_ts = np.append(bin_ts, t_max)

    total_per_bin = np.zeros(len(bin_ts), dtype=float)

    # 各リンクごとに LOCF でビン時刻の flying_count を取得して合算
    for _, link_df in df.groupby(['link_from', 'link_to'], sort=False):
        ts = link_df['timestamp'].values
        fc = link_df['flying_count'].values

        idxs = np.searchsorted(ts, bin_ts, side='right') - 1
        link_fc = np.where(idxs >= 0, fc[idxs], 0.0)
        total_per_bin += link_fc

    return pd.DataFrame({
        'timestamp':    bin_ts,
        'total_flying': total_per_bin.astype(int),
    })


def main():
    parser = argparse.ArgumentParser(
        description='空域全体の飛行中UAV台数の時系列グラフを生成'
    )
    parser.add_argument('result_dir', help='結果ディレクトリ（大規模/手法名まで）')
    parser.add_argument('output_dir', help='出力ディレクトリ')
    parser.add_argument('y_max',      nargs='?', default=None, help='Y軸最大値（省略時は自動）')
    parser.add_argument('y_interval', nargs='?', default=None, help='Y軸間隔（省略時は自動）')
    parser.add_argument('--log',      action='store_true', help='Y軸を対数スケールで表示')
    parser.add_argument('--bin',      type=float, default=60.0,
                        metavar='N', help='集計間隔（秒）[デフォルト: 60]')

    args = parser.parse_args()

    result_dir = args.result_dir
    output_dir = args.output_dir
    y_max      = float(args.y_max)      if args.y_max      else None
    y_interval = float(args.y_interval) if args.y_interval else None
    log_scale  = args.log
    bin_s      = args.bin

    csv_path = os.path.join(result_dir, "link_status", "link_status.csv")
    if not os.path.exists(csv_path):
        print(f"Error: CSV file not found: {csv_path}")
        sys.exit(1)

    searcher_name = os.path.basename(result_dir)

    print(f"集計間隔: {bin_s:.0f}s  (LOCF方式)")
    total_flying = load_total_flying(csv_path, bin_s)
    print(f"集計点数: {len(total_flying)}")

    # --- グラフ作成 ---
    fig, ax = plt.subplots(figsize=(14, 8))

    for spine in ax.spines.values():
        spine.set_visible(True)
        spine.set_color('black')
        spine.set_linewidth(1.0)

    ax.plot(total_flying['timestamp'], total_flying['total_flying'],
            linewidth=2.5, color='blue')

    # X軸
    ax.set_xlabel('Time (s)', fontsize=34, fontweight='bold', color='black')
    ax.tick_params(axis='both', labelsize=29, direction='in', colors='black')
    x_max_val = total_flying['timestamp'].max()
    ax.set_xlim(0, x_max_val)
    ax.set_xticks(np.arange(0, x_max_val + 1, 6000))

    # Y軸
    if log_scale:
        ax.set_yscale('log')
        vals = total_flying['total_flying']
        nonzero = vals[vals > 0]
        y_bottom = 10 ** np.floor(np.log10(nonzero.min())) if len(nonzero) > 0 else 1.0
        if y_max is not None:
            ax.set_ylim(y_bottom, y_max)
        else:
            ax.set_ylim(bottom=y_bottom)
        ax.set_ylabel('飛行中UAV台数 [log scale]', fontsize=34, fontweight='bold', color='black')
    else:
        ax.set_ylabel('飛行中UAV台数', fontsize=34, fontweight='bold', color='black')
        if y_max is not None:
            ax.set_ylim(0, y_max)
            if y_interval is not None:
                ax.set_yticks(np.arange(0, y_max + 1, y_interval))
        else:
            ax.set_ylim(bottom=0)
            if y_interval is not None:
                data_max = total_flying['total_flying'].max()
                y_max_auto = np.ceil(data_max / y_interval) * y_interval
                ax.set_ylim(0, y_max_auto)
                ax.set_yticks(np.arange(0, y_max_auto + 1, y_interval))

    # グリッド
    ax.grid(True, alpha=0.3, linestyle='--', color='black')
    ax.set_axisbelow(True)

    plt.tight_layout()

    # 出力
    os.makedirs(output_dir, exist_ok=True)
    suffix = "_log" if log_scale else ""
    output_path = os.path.join(output_dir, f"total_flying_{searcher_name}{suffix}.png")
    plt.savefig(output_path, dpi=150, bbox_inches='tight')
    print(f"Graph saved to: {output_path}")

    plt.close()


if __name__ == "__main__":
    main()
