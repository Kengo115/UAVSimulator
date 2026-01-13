#!/usr/bin/env python3
"""
link_status.csv から各リンクのデータを抽出するスクリプト
（標準ライブラリのみ使用）

使用方法:
    python scripts/extract_links.py <input_csv> [output_dir]

例:
    python scripts/extract_links.py src/result/large_scale/Bisectional/link_status/link_status.csv
    python scripts/extract_links.py src/result/large_scale/Bisectional/link_status/link_status.csv ./output_links
"""

import csv
import sys
import os
from collections import defaultdict

def extract_links(input_csv: str, output_dir: str = None):
    """
    link_status.csv から各リンクのデータを個別のCSVファイルに抽出

    Args:
        input_csv: 入力CSVファイルのパス
        output_dir: 出力ディレクトリ（指定しない場合は入力ファイルと同じ場所にlinksディレクトリを作成）
    """
    # 入力ファイル確認
    if not os.path.exists(input_csv):
        print(f"Error: ファイルが見つかりません: {input_csv}")
        sys.exit(1)

    # 出力ディレクトリ設定
    if output_dir is None:
        output_dir = os.path.join(os.path.dirname(input_csv), "links")

    os.makedirs(output_dir, exist_ok=True)
    print(f"出力先: {output_dir}")

    # リンクごとにデータを分類
    link_data = defaultdict(list)
    header = None
    total_records = 0

    print(f"読み込み中: {input_csv}")
    with open(input_csv, 'r', encoding='utf-8') as f:
        reader = csv.reader(f)
        header = next(reader)  # ヘッダー行を取得

        for row in reader:
            total_records += 1
            link_from = row[1]  # link_from
            link_to = row[2]    # link_to
            key = (link_from, link_to)
            link_data[key].append(row)

    print(f"総レコード数: {total_records}")
    print(f"カラム: {header}")
    print(f"ユニークなリンク数: {len(link_data)}")

    # 各リンクのデータを個別ファイルに出力
    summary = []
    for (link_from, link_to), rows in link_data.items():
        filename = f"link_{link_from}_{link_to}.csv"
        filepath = os.path.join(output_dir, filename)

        with open(filepath, 'w', encoding='utf-8', newline='') as f:
            writer = csv.writer(f)
            writer.writerow(header)
            writer.writerows(rows)

        summary.append((link_from, link_to, len(rows)))

    # サマリー情報を出力
    summary_path = os.path.join(output_dir, "_summary.csv")
    with open(summary_path, 'w', encoding='utf-8', newline='') as f:
        writer = csv.writer(f)
        writer.writerow(['link_from', 'link_to', 'record_count'])
        writer.writerows(sorted(summary, key=lambda x: -x[2]))  # レコード数降順

    print(f"\n{len(link_data)} 個のリンクファイルを出力しました")
    print(f"サマリーファイル: {summary_path}")

    return summary

def print_link_stats(input_csv: str):
    """リンクの統計情報を表示"""
    link_stats = defaultdict(lambda: {
        'count': 0,
        'flying_sum': 0,
        'flying_max': 0,
        'waiting_sum': 0,
        'waiting_max': 0,
        'load_rate_sum': 0,
        'load_rate_max': 0
    })

    with open(input_csv, 'r', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            key = (row['link_from'], row['link_to'])
            stats = link_stats[key]
            stats['count'] += 1

            flying = int(row['flying_count'])
            waiting = int(row['waiting_count'])
            load_rate = float(row['load_rate'])

            stats['flying_sum'] += flying
            stats['flying_max'] = max(stats['flying_max'], flying)
            stats['waiting_sum'] += waiting
            stats['waiting_max'] = max(stats['waiting_max'], waiting)
            stats['load_rate_sum'] += load_rate
            stats['load_rate_max'] = max(stats['load_rate_max'], load_rate)

    # 最も記録が多いリンク Top10
    sorted_links = sorted(link_stats.items(), key=lambda x: -x[1]['count'])

    print("\n=== 最も記録が多いリンク Top10 ===")
    print(f"{'From':>6} {'To':>6} {'Count':>8} {'AvgFlying':>10} {'MaxLoad':>10}")
    print("-" * 50)
    for (link_from, link_to), stats in sorted_links[:10]:
        avg_flying = stats['flying_sum'] / stats['count'] if stats['count'] > 0 else 0
        print(f"{link_from:>6} {link_to:>6} {stats['count']:>8} {avg_flying:>10.2f} {stats['load_rate_max']:>10.2f}")

    return link_stats

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    input_csv = sys.argv[1]
    output_dir = sys.argv[2] if len(sys.argv) > 2 else None

    # 統計表示
    print_link_stats(input_csv)

    # 抽出実行
    print("\n" + "="*50)
    extract_links(input_csv, output_dir)
