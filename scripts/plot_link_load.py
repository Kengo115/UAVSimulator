#!/usr/bin/env python3
"""
指定リンクのload_rate時系列グラフを生成するスクリプト

Usage:
    python plot_link_load.py <links_dir> <output_dir> <from_node> <to_node>

Example:
    python plot_link_load.py src/result/large_scale/Bisectional/link_status/links output/graphs 0 85
    python plot_link_load.py src/result/large_scale/Bisectional/link_status/links output/graphs 157 176
"""

import pandas as pd
import matplotlib.pyplot as plt
import os
import sys

def main():
    if len(sys.argv) < 5:
        print("Usage: python plot_link_load.py <links_dir> <output_dir> <from_node> <to_node>")
        print("Example: python plot_link_load.py src/result/large_scale/Bisectional/link_status/links output/graphs 0 85")
        sys.exit(1)

    links_dir = sys.argv[1]
    output_dir = sys.argv[2]
    from_node = sys.argv[3]
    to_node = sys.argv[4]

    # CSVファイルのパス
    csv_path = os.path.join(links_dir, f"link_{from_node}_{to_node}.csv")

    if not os.path.exists(csv_path):
        print(f"Error: CSV file not found: {csv_path}")
        # 利用可能なリンクを表示
        print("\nAvailable links:")
        files = sorted([f for f in os.listdir(links_dir) if f.startswith('link_') and f.endswith('.csv') and f != '_summary.csv'])[:20]
        for f in files:
            print(f"  {f}")
        if len(os.listdir(links_dir)) > 20:
            print("  ...")
        sys.exit(1)

    # データ読み込み
    df = pd.read_csv(csv_path)

    # 時間を秒から分に変換
    df['time_min'] = df['timestamp'] / 60

    # グラフ作成
    fig, ax = plt.subplots(figsize=(14, 6))

    # load_rateをステップグラフで描画（イベント駆動のデータなので）
    ax.step(df['time_min'], df['load_rate'], where='post', linewidth=1.5, color='blue', label='Load Rate')

    # 100%ラインを追加
    ax.axhline(y=100, color='red', linestyle='--', alpha=0.5, label='Capacity (100%)')

    ax.set_xlabel('Time (minutes)', fontsize=12)
    ax.set_ylabel('Load Rate (%)', fontsize=12)
    ax.set_title(f'Link Load Rate Over Time (Link {from_node} → {to_node})', fontsize=14)
    ax.legend(loc='best', fontsize=10)
    ax.grid(True, alpha=0.3)
    ax.set_ylim(bottom=0)

    plt.tight_layout()

    # 出力ディレクトリを作成
    os.makedirs(output_dir, exist_ok=True)

    # 出力
    output_path = os.path.join(output_dir, f"link_load_{from_node}_{to_node}.png")
    plt.savefig(output_path, dpi=150)
    print(f"Graph saved to: {output_path}")

    # 統計情報を表示
    print(f"\n--- Statistics for Link {from_node} → {to_node} ---")
    print(f"  Records: {len(df)}")
    print(f"  Time range: {df['timestamp'].min():.1f}s - {df['timestamp'].max():.1f}s ({df['time_min'].max():.1f} min)")
    print(f"  Load rate: min={df['load_rate'].min():.1f}%, max={df['load_rate'].max():.1f}%, avg={df['load_rate'].mean():.1f}%")
    print(f"  Capacity: {df['capacity'].iloc[0]}")

    plt.close()

if __name__ == "__main__":
    main()
