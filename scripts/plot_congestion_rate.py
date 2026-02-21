#!/usr/bin/env python3
"""
混雑率の時系列グラフを生成するスクリプト

Usage:
    python plot_congestion_rate.py <result_dir> <output_dir> [y_max] [y_interval]

Example:
    python plot_congestion_rate.py src/result/large_scale/Bisectional_1 output/graphs
    python plot_congestion_rate.py src/result/large_scale/Bisectional_1 output/graphs 100
    python plot_congestion_rate.py src/result/large_scale/Bisectional_1 output/graphs 100 20
"""

import pandas as pd
import matplotlib.pyplot as plt
import numpy as np
import os
import sys

def main():
    if len(sys.argv) < 3:
        print("Usage: python plot_congestion_rate.py <result_dir> <output_dir> [y_max] [y_interval]")
        print("Example: python plot_congestion_rate.py src/result/large_scale/Bisectional_1 output/graphs")
        print("         python plot_congestion_rate.py src/result/large_scale/Bisectional_1 output/graphs 100")
        print("         python plot_congestion_rate.py src/result/large_scale/Bisectional_1 output/graphs 100 20")
        sys.exit(1)

    result_dir = sys.argv[1]
    output_dir = sys.argv[2]
    y_max = float(sys.argv[3]) if len(sys.argv) > 3 and sys.argv[3] else None
    y_interval = float(sys.argv[4]) if len(sys.argv) > 4 and sys.argv[4] else None

    # CSVファイルのパス
    csv_path = os.path.join(result_dir, "link_status", "congestion_rate.csv")

    if not os.path.exists(csv_path):
        print(f"Error: CSV file not found: {csv_path}")
        sys.exit(1)

    # サーチャー名を取得（ディレクトリ名から）
    searcher_name = os.path.basename(result_dir)

    # データ読み込み
    df = pd.read_csv(csv_path)

    # グラフ作成
    fig, ax = plt.subplots(figsize=(14, 8))

    # クローズドグラフ設定（枠線を太く）
    for spine in ax.spines.values():
        spine.set_visible(True)
        spine.set_color('black')
        spine.set_linewidth(1.0)

    # 時間を分単位に変換（データが秒単位の場合は60で割る）
    time_raw = df['time']
    # 最大値が1000を超える場合は秒単位とみなして分に変換
    if time_raw.max() > 1000:
        time_min = time_raw / 60
    else:
        time_min = time_raw

    ax.plot(time_min, df['AverageLoadRate'], label='Average Load Rate', linewidth=2.5, color='blue')
    ax.plot(time_min, df['CongestedLinkRate'], label='Congested Link Rate', linewidth=2.5, color='red')

    # 軸ラベル（CDF比較スクリプトと同等のサイズ、太字）
    ax.set_xlabel('Time (minutes)', fontsize=34, fontweight='bold', color='black')
    ax.set_ylabel('Rate (%)', fontsize=34, fontweight='bold', color='black')

    # タイトルなし

    # 凡例（CDF比較スクリプトと同等のサイズ）
    ax.legend(loc='best', fontsize=29)

    # グリッド
    ax.grid(True, alpha=0.3, linestyle='--', color='black')

    # 軸の目盛りフォントサイズ（CDF比較スクリプトと同等）
    ax.tick_params(axis='both', labelsize=29, direction='in', colors='black')

    # X軸: 0からスタート、100分間隔
    x_max = time_min.max()
    ax.set_xlim(0, x_max)
    ax.set_xticks(np.arange(0, x_max + 1, 100))

    # Y軸: 0からスタート
    if y_max is not None:
        ax.set_ylim(0, y_max)
        if y_interval is not None:
            ax.set_yticks(np.arange(0, y_max + 1, y_interval))
    else:
        ax.set_ylim(bottom=0)
        if y_interval is not None:
            data_max = max(df['AverageLoadRate'].max(), df['CongestedLinkRate'].max())
            y_max_auto = np.ceil(data_max / y_interval) * y_interval
            ax.set_ylim(0, y_max_auto)
            ax.set_yticks(np.arange(0, y_max_auto + 1, y_interval))

    plt.tight_layout()

    # 出力ディレクトリを作成
    os.makedirs(output_dir, exist_ok=True)

    # 出力
    output_path = os.path.join(output_dir, f"congestion_rate_{searcher_name}.png")
    plt.savefig(output_path, dpi=150, bbox_inches='tight')
    print(f"Graph saved to: {output_path}")

    plt.close()

if __name__ == "__main__":
    main()
