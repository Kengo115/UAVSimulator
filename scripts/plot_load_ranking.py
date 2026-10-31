#!/usr/bin/env python3
"""
全リンクの最大混雑率ランキンググラフを生成

Usage:
    python scripts/plot_load_ranking.py <summary_csv> <output_dir> [y_max] [y_interval] [--log]

Example:
    python scripts/plot_load_ranking.py src/result/sim_1/large_scale/PS/link_status/links/_summary.csv output/sim_1/graphs
    python scripts/plot_load_ranking.py src/result/sim_1/large_scale/PS/link_status/links/_summary.csv output/sim_1/graphs 700
    python scripts/plot_load_ranking.py src/result/sim_1/large_scale/PS/link_status/links/_summary.csv output/sim_1/graphs 700 100
    python scripts/plot_load_ranking.py src/result/sim_1/large_scale/PS/link_status/links/_summary.csv output/sim_1/graphs --log
    python scripts/plot_load_ranking.py src/result/sim_1/large_scale/PS/link_status/links/_summary.csv output/sim_1/graphs 700 "" --log
"""

import sys
import os
import csv
import argparse
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

def plot_load_ranking(data: list, output_path: str, y_max=None, y_interval=None, log_scale=False):
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

    # Y軸
    if log_scale:
        ax.set_yscale('log')
        # 対数スケール: 0を含む値を除外して下限を設定
        min_nonzero = min((v for v in max_loads if v > 0), default=1.0)
        y_bottom = 10 ** np.floor(np.log10(min_nonzero))
        if y_max is not None:
            ax.set_ylim(y_bottom, y_max)
        else:
            ax.set_ylim(bottom=y_bottom)
        ax.set_ylabel('Max Load Rate (%) [log scale]', fontsize=24)
    else:
        if y_max is not None:
            ax.set_ylim(0, y_max)
            if y_interval is not None:
                ax.set_yticks(np.arange(0, y_max + 1, y_interval))
        else:
            ax.set_ylim(bottom=0)
            if y_interval is not None:
                data_max = max_loads[0]
                y_max_auto = np.ceil(data_max / y_interval) * y_interval
                ax.set_ylim(0, y_max_auto)
                ax.set_yticks(np.arange(0, y_max_auto + 1, y_interval))

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
    parser = argparse.ArgumentParser(
        description='全リンクの最大混雑率ランキンググラフを生成',
        add_help=True
    )
    parser.add_argument('summary_csv', help='_summary.csv のパス')
    parser.add_argument('output_dir', help='出力ディレクトリ')
    parser.add_argument('y_max', nargs='?', default=None, help='Y軸最大値（省略時は自動）')
    parser.add_argument('y_interval', nargs='?', default=None, help='Y軸間隔（省略時は自動）')
    parser.add_argument('--log', action='store_true', help='Y軸を対数スケールで表示')

    args = parser.parse_args()

    summary_csv = args.summary_csv
    output_dir = args.output_dir
    y_max = float(args.y_max) if args.y_max else None
    y_interval = float(args.y_interval) if args.y_interval else None
    log_scale = args.log

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

    # 出力ファイル名（対数スケール時はサフィックスを付加）
    filename = 'load_ranking_log.png' if log_scale else 'load_ranking.png'
    output_path = os.path.join(output_dir, filename)

    # グラフ生成
    plot_load_ranking(data, output_path, y_max, y_interval, log_scale)

if __name__ == "__main__":
    main()
