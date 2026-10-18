#!/usr/bin/env python3
"""
空域全体の待機UAV台数の時系列グラフを生成するスクリプト

Usage:
    python plot_total_waiting.py <result_dir> <output_dir> [y_max] [y_interval] [--log]

Example:
    python plot_total_waiting.py src/result/sim_1/large_scale/Bisectional output/graphs
    python plot_total_waiting.py src/result/sim_1/large_scale/Bisectional output/graphs 50
    python plot_total_waiting.py src/result/sim_1/large_scale/Bisectional output/graphs 50 10
    python plot_total_waiting.py src/result/sim_1/large_scale/Bisectional output/graphs --log
"""

import argparse
import os
import sys

import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
import numpy as np
import pandas as pd

# 日本語フォント設定（Noto Sans CJK JP）
plt.rcParams['font.family'] = 'Noto Sans CJK JP'


def main():
    parser = argparse.ArgumentParser(
        description='空域全体の待機UAV台数の時系列グラフを生成'
    )
    parser.add_argument('result_dir', help='結果ディレクトリ（大規模/手法名まで）')
    parser.add_argument('output_dir', help='出力ディレクトリ')
    parser.add_argument('y_max',      nargs='?', default=None, help='Y軸最大値（省略時は自動）')
    parser.add_argument('y_interval', nargs='?', default=None, help='Y軸間隔（省略時は自動）')
    parser.add_argument('--log', action='store_true', help='Y軸を対数スケールで表示')

    args = parser.parse_args()

    result_dir  = args.result_dir
    output_dir  = args.output_dir
    y_max       = float(args.y_max)      if args.y_max      else None
    y_interval  = float(args.y_interval) if args.y_interval else None
    log_scale   = args.log

    # CSVファイルのパス
    csv_path = os.path.join(result_dir, "link_status", "link_status.csv")

    if not os.path.exists(csv_path):
        print(f"Error: CSV file not found: {csv_path}")
        sys.exit(1)

    # サーチャー名を取得（ディレクトリ名から）
    searcher_name = os.path.basename(result_dir)

    # データ読み込み
    df = pd.read_csv(csv_path)

    if 'waiting_count' not in df.columns:
        print(f"Error: 'waiting_count' column not found in {csv_path}")
        print(f"  Available columns: {list(df.columns)}")
        sys.exit(1)

    # タイムスタンプごとに全リンクの waiting_count を合計
    total_waiting = df.groupby('timestamp')['waiting_count'].sum().reset_index()
    total_waiting.columns = ['timestamp', 'total_waiting']
    total_waiting = total_waiting.sort_values('timestamp')

    # グラフ作成
    fig, ax = plt.subplots(figsize=(14, 8))

    for spine in ax.spines.values():
        spine.set_visible(True)
        spine.set_color('black')
        spine.set_linewidth(1.0)

    ax.plot(total_waiting['timestamp'], total_waiting['total_waiting'],
            linewidth=2.5, color='blue')

    # X軸
    ax.set_xlabel('Time (s)', fontsize=34, fontweight='bold', color='black')
    ax.tick_params(axis='both', labelsize=29, direction='in', colors='black')
    x_max_val = total_waiting['timestamp'].max()
    ax.set_xlim(0, x_max_val)
    ax.set_xticks(np.arange(0, x_max_val + 1, 6000))

    # Y軸
    if log_scale:
        ax.set_yscale('log')
        vals = total_waiting['total_waiting']
        nonzero = vals[vals > 0]
        y_bottom = 10 ** np.floor(np.log10(nonzero.min())) if len(nonzero) > 0 else 1.0
        if y_max is not None:
            ax.set_ylim(y_bottom, y_max)
        else:
            ax.set_ylim(bottom=y_bottom)
        ax.set_ylabel('待機UAV台数 [log scale]', fontsize=34, fontweight='bold', color='black')
    else:
        ax.set_ylabel('待機UAV台数', fontsize=34, fontweight='bold', color='black')
        if y_max is not None:
            ax.set_ylim(0, y_max)
            if y_interval is not None:
                ax.set_yticks(np.arange(0, y_max + 1, y_interval))
        else:
            ax.set_ylim(bottom=0)
            if y_interval is not None:
                data_max = total_waiting['total_waiting'].max()
                y_max_auto = np.ceil(data_max / y_interval) * y_interval
                ax.set_ylim(0, y_max_auto)
                ax.set_yticks(np.arange(0, y_max_auto + 1, y_interval))

    # グリッド
    ax.grid(True, alpha=0.3, linestyle='--', color='black')

    plt.tight_layout()

    # 出力
    os.makedirs(output_dir, exist_ok=True)
    suffix = "_log" if log_scale else ""
    output_path = os.path.join(output_dir, f"total_waiting_{searcher_name}{suffix}.png")
    plt.savefig(output_path, dpi=150, bbox_inches='tight')
    print(f"Graph saved to: {output_path}")

    plt.close()


if __name__ == "__main__":
    main()
