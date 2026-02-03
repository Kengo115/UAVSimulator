#!/usr/bin/env python3
"""
_summary.csv から max_load_rate Top10 のリンクを棒グラフで可視化

使用方法:
    python scripts/plot_top_load_links.py <summary_csv> <output_dir> [top_n]

例:
    python scripts/plot_top_load_links.py src/result/sim_1/large_scale/PS/link_status/links/_summary.csv output/sim_1/graphs
    python scripts/plot_top_load_links.py src/result/sim_1/large_scale/PS/link_status/links/_summary.csv output/sim_1/graphs 20
"""

import sys
import os
import csv
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import numpy as np

def load_summary(csv_path: str) -> list:
    """_summary.csv を読み込んで、リンクを正規化（重複排除）してリストで返す"""
    # 正規化されたリンクごとに統計を集約
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

            record_count = int(row['record_count'])
            avg_flying = float(row['avg_flying'])
            max_load_rate = float(row['max_load_rate'])
            max_waiting = int(row['max_waiting'])

            if key not in normalized_data:
                normalized_data[key] = {
                    'link_from': min_node,
                    'link_to': max_node,
                    'record_count': record_count,
                    'avg_flying': avg_flying,
                    'max_load_rate': max_load_rate,
                    'max_waiting': max_waiting,
                    'direction_count': 1
                }
            else:
                # 既存データとマージ（両方向の統計を統合）
                existing = normalized_data[key]
                existing['record_count'] += record_count
                existing['avg_flying'] = (existing['avg_flying'] + avg_flying) / 2  # 平均
                existing['max_load_rate'] = max(existing['max_load_rate'], max_load_rate)  # 最大値
                existing['max_waiting'] = max(existing['max_waiting'], max_waiting)  # 最大値
                existing['direction_count'] += 1

    return list(normalized_data.values())

def plot_top_load_links(data: list, output_path: str, top_n: int = 10):
    """max_load_rate Top N のリンクを棒グラフで可視化"""

    # max_load_rate で降順ソート
    sorted_data = sorted(data, key=lambda x: x['max_load_rate'], reverse=True)[:top_n]

    # グラフ用データ準備（正規化済みなので双方向を示す "-" を使用）
    labels = [f"{d['link_from']} - {d['link_to']}" for d in sorted_data]
    max_loads = [d['max_load_rate'] for d in sorted_data]
    max_waitings = [d['max_waiting'] for d in sorted_data]
    avg_flyings = [d['avg_flying'] for d in sorted_data]

    # 色を負荷率に応じて設定
    colors = []
    for load in max_loads:
        if load >= 5000:
            colors.append('#d62728')  # 赤: 5000%以上
        elif load >= 1000:
            colors.append('#ff7f0e')  # オレンジ: 1000-5000%
        elif load >= 500:
            colors.append('#ffbb78')  # 薄オレンジ: 500-1000%
        else:
            colors.append('#2ca02c')  # 緑: 500%未満

    # グラフ作成
    fig, ax = plt.subplots(figsize=(14, 8))

    y_pos = np.arange(len(labels))
    bars = ax.barh(y_pos, max_loads, color=colors, edgecolor='black', linewidth=0.5)

    # 値をバーの横に表示（位置を調整して重ならないようにする）
    max_load_value = max(max_loads)
    for i, (bar, load, waiting, flying) in enumerate(zip(bars, max_loads, max_waitings, avg_flyings)):
        # max_load_rate の値（バーの右端から一定距離）
        text_x = bar.get_width() + max_load_value * 0.02
        ax.text(text_x, bar.get_y() + bar.get_height()/2,
                f'{load:.0f}%',
                va='center', ha='left', fontsize=10, fontweight='bold')
        # 追加情報（待機数、平均飛行数）- %の後ろに十分なスペースを確保
        info_x = bar.get_width() + max_load_value * 0.12
        ax.text(info_x, bar.get_y() + bar.get_height()/2,
                f'(wait:{waiting}, fly:{flying:.1f})',
                va='center', ha='left', fontsize=9, color='gray')

    # 軸設定
    ax.set_yticks(y_pos)
    ax.set_yticklabels(labels, fontsize=11)
    ax.invert_yaxis()  # 上から順に表示
    ax.set_xlabel('Max Load Rate (%)', fontsize=12)
    ax.set_title(f'Link Max Load Rate - Top {top_n}', fontsize=14, fontweight='bold')

    # X軸の範囲を広げてラベル表示スペースを確保
    x_max = max(max_loads) * 1.35
    ax.set_xlim(0, x_max)

    # グリッド
    ax.xaxis.grid(True, linestyle='--', alpha=0.7)
    ax.set_axisbelow(True)

    # 凡例
    legend_elements = [
        mpatches.Patch(facecolor='#d62728', edgecolor='black', label='>= 5000%'),
        mpatches.Patch(facecolor='#ff7f0e', edgecolor='black', label='1000-5000%'),
        mpatches.Patch(facecolor='#ffbb78', edgecolor='black', label='500-1000%'),
        mpatches.Patch(facecolor='#2ca02c', edgecolor='black', label='< 500%'),
    ]
    ax.legend(handles=legend_elements, loc='lower right', fontsize=9)

    # 余白調整
    plt.tight_layout()

    # 保存
    plt.savefig(output_path, dpi=150, bbox_inches='tight')
    plt.close()

    print(f"Graph saved: {output_path}")

    # コンソールにも情報表示
    print(f"\n=== max_load_rate Top {top_n} (Normalized Links) ===")
    print(f"{'Rank':<5} {'Link':<12} {'MaxLoad':>10} {'MaxWait':>8} {'AvgFly':>8}")
    print("-" * 50)
    for i, d in enumerate(sorted_data, 1):
        print(f"{i:<5} {d['link_from']:>4}-{d['link_to']:<4} {d['max_load_rate']:>9.0f}% {d['max_waiting']:>8} {d['avg_flying']:>8.1f}")

def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)

    summary_csv = sys.argv[1]
    output_dir = sys.argv[2]
    top_n = int(sys.argv[3]) if len(sys.argv) > 3 else 10

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
    output_path = os.path.join(output_dir, f'top{top_n}_max_load_links.png')

    # グラフ生成
    plot_top_load_links(data, output_path, top_n)

if __name__ == "__main__":
    main()
