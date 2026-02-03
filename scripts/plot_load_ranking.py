#!/usr/bin/env python3
"""
全リンクの最大混雑率ランキンググラフを生成

Usage:
    python scripts/plot_load_ranking.py <summary_csv> <output_dir>

Example:
    python scripts/plot_load_ranking.py src/result/sim_1/large_scale/PS/link_status/links/_summary.csv output/sim_1/graphs
"""

import sys
import os
import csv
import matplotlib.pyplot as plt
import numpy as np

def load_summary(csv_path: str) -> list:
    """_summary.csv を読み込んで、リンクを正規化（重複排除）してリストで返す"""
    normalized_data = {}

    with open(csv_path, 'r', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            link_from = int(row['link_from'])
            link_to = int(row['link_to'])

            # 正規化キー（小さいノードIDを先に）
            min_node = min(link_from, link_to)
            max_node = max(link_from, link_to)
            key = (min_node, max_node)

            max_load_rate = float(row['max_load_rate'])

            if key not in normalized_data:
                normalized_data[key] = {
                    'link_from': min_node,
                    'link_to': max_node,
                    'max_load_rate': max_load_rate,
                }
            else:
                # 既存データとマージ（両方向の最大値を取る）
                existing = normalized_data[key]
                existing['max_load_rate'] = max(existing['max_load_rate'], max_load_rate)

    return list(normalized_data.values())

def plot_load_ranking(data: list, output_path: str):
    """全リンクの最大混雑率ランキンググラフを生成"""

    # max_load_rate で降順ソート
    sorted_data = sorted(data, key=lambda x: x['max_load_rate'], reverse=True)

    # データ準備
    ranks = np.arange(1, len(sorted_data) + 1)
    max_loads = [d['max_load_rate'] for d in sorted_data]

    # グラフ作成
    fig, ax = plt.subplots(figsize=(14, 8))

    # 棒グラフ
    ax.bar(ranks, max_loads, width=1.0, color='steelblue', edgecolor='none')

    # 軸ラベル
    ax.set_xlabel('Rank', fontsize=24)
    ax.set_ylabel('Max Load Rate (%)', fontsize=24)

    # 軸の目盛りフォントサイズ
    ax.tick_params(axis='both', labelsize=20)

    # X軸: 適切な間隔で目盛りを表示
    total_links = len(sorted_data)
    if total_links <= 50:
        x_interval = 10
    elif total_links <= 100:
        x_interval = 20
    elif total_links <= 200:
        x_interval = 50
    else:
        x_interval = 100
    ax.set_xlim(0, total_links + 1)
    ax.set_xticks(np.arange(0, total_links + 1, x_interval))

    # Y軸: 0からスタート
    ax.set_ylim(bottom=0)

    # グリッド
    ax.grid(True, alpha=0.3, axis='y')

    # 余白調整
    plt.tight_layout()

    # 保存
    plt.savefig(output_path, dpi=150, bbox_inches='tight')
    plt.close()

    print(f"Graph saved: {output_path}")
    print(f"Total links: {total_links}")
    print(f"Max load rate: {max_loads[0]:.1f}% (Rank 1)")
    print(f"Min load rate: {max_loads[-1]:.1f}% (Rank {total_links})")

def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)

    summary_csv = sys.argv[1]
    output_dir = sys.argv[2]

    # 入力ファイル確認
    if not os.path.exists(summary_csv):
        print(f"Error: ファイルが見つかりません: {summary_csv}")
        print("\n先に extract-links を実行してください:")
        print("  make extract-links SIM_ID=N METHOD=X")
        sys.exit(1)

    # 出力ディレクトリ作成
    os.makedirs(output_dir, exist_ok=True)

    # データ読み込み
    data = load_summary(summary_csv)

    # 出力ファイル名
    output_path = os.path.join(output_dir, 'load_ranking.png')

    # グラフ生成
    plot_load_ranking(data, output_path)

if __name__ == "__main__":
    main()
