#!/usr/bin/env python3
"""
混雑率の時系列グラフを生成するスクリプト

Usage:
    python plot_congestion_rate.py <result_dir> <output_dir>

Example:
    python plot_congestion_rate.py src/result/large_scale/Bisectional_1 output/graphs
"""

import pandas as pd
import matplotlib.pyplot as plt
import os
import sys

def main():
    if len(sys.argv) < 3:
        print("Usage: python plot_congestion_rate.py <result_dir> <output_dir>")
        print("Example: python plot_congestion_rate.py src/result/large_scale/Bisectional_1 output/graphs")
        sys.exit(1)

    result_dir = sys.argv[1]
    output_dir = sys.argv[2]

    # CSVファイルのパス
    csv_path = os.path.join(result_dir, "link_status", "congestion_rate.csv")

    if not os.path.exists(csv_path):
        print(f"Error: CSV file not found: {csv_path}")
        sys.exit(1)

    # サーチャー名を取得（ディレクトリ名から）
    searcher_name = os.path.basename(result_dir)

    # データ読み込み
    df = pd.read_csv(csv_path)

    # 時間を秒から分に変換
    df['time_min'] = df['time'] / 60

    # グラフ作成
    plt.figure(figsize=(12, 6))

    plt.plot(df['time_min'], df['AverageLoadRate'], label='Average Load Rate', linewidth=2, color='blue')
    plt.plot(df['time_min'], df['CongestedLinkRate'], label='Congested Link Rate', linewidth=2, color='red')

    plt.xlabel('Time (minutes)', fontsize=12)
    plt.ylabel('Rate (%)', fontsize=12)
    plt.title(f'Congestion Rate Over Time ({searcher_name})', fontsize=14)
    plt.legend(loc='best', fontsize=10)
    plt.grid(True, alpha=0.3)
    plt.tight_layout()

    # 出力ディレクトリを作成
    os.makedirs(output_dir, exist_ok=True)

    # 出力
    output_path = os.path.join(output_dir, f"congestion_rate_{searcher_name}.png")
    plt.savefig(output_path, dpi=150)
    print(f"Graph saved to: {output_path}")

    plt.close()

if __name__ == "__main__":
    main()
