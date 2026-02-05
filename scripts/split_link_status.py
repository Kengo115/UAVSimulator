#!/usr/bin/env python3
"""
リンク状態CSVを各リンクごとに分割するスクリプト

link_status.csvを読み込み、各リンク（from-to）ごとに個別のCSVファイルに分割する。

Usage:
    python3 scripts/split_link_status.py <input_csv> [output_dir]

Examples:
    python3 scripts/split_link_status.py src/result/large_scale/Bisectional/link_status/link_status.csv
    python3 scripts/split_link_status.py src/result/large_scale/Bisectional/link_status/link_status.csv output/links/
"""

import sys
import os
import pandas as pd
from collections import defaultdict


def split_link_status(input_csv, output_dir=None):
    """
    link_status.csvを各リンクごとに分割する

    Args:
        input_csv: 入力CSVファイルパス
        output_dir: 出力ディレクトリ（省略時は入力ファイルと同じディレクトリにlinksサブディレクトリを作成）
    """

    if not os.path.exists(input_csv):
        print(f"Error: ファイルが見つかりません: {input_csv}")
        sys.exit(1)

    # 出力ディレクトリを決定
    if output_dir is None:
        output_dir = os.path.join(os.path.dirname(input_csv), "links")

    if not os.path.exists(output_dir):
        os.makedirs(output_dir)

    print(f"入力ファイル: {input_csv}")
    print(f"出力ディレクトリ: {output_dir}")

    # CSVを読み込み
    df = pd.read_csv(input_csv)
    print(f"総レコード数: {len(df)}")

    # リンクごとにグループ化
    grouped = df.groupby(['link_from', 'link_to'])
    link_count = len(grouped)
    print(f"リンク数: {link_count}")

    # 各リンクごとにファイル出力
    for (from_node, to_node), group_df in grouped:
        # ファイル名: link_0_1.csv
        filename = f"link_{from_node}_{to_node}.csv"
        filepath = os.path.join(output_dir, filename)

        # タイムスタンプでソート
        sorted_df = group_df.sort_values('timestamp')

        # link_from, link_to列は不要（ファイル名で識別）なので削除
        output_df = sorted_df[['timestamp', 'flying_count', 'waiting_count', 'capacity', 'load_rate', 'event']]

        # CSV出力
        output_df.to_csv(filepath, index=False)

    print(f"\n分割完了: {link_count} ファイルを出力しました")
    print(f"出力先: {output_dir}/link_<from>_<to>.csv")

    # 統計情報
    print("\n=== 統計情報 ===")
    for (from_node, to_node), group_df in grouped:
        event_counts = group_df['event'].value_counts()
        print(f"  link_{from_node}_{to_node}: {len(group_df)} イベント", end="")
        if 'ENTER' in event_counts:
            print(f" (ENTER={event_counts['ENTER']}", end="")
            if 'EXIT' in event_counts:
                print(f", EXIT={event_counts['EXIT']}", end="")
            print(")", end="")
        print()


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 split_link_status.py <input_csv> [output_dir]")
        print("\nExamples:")
        print("  python3 scripts/split_link_status.py src/result/large_scale/Bisectional/link_status/link_status.csv")
        print("  python3 scripts/split_link_status.py src/result/large_scale/Bisectional/link_status/link_status.csv output/links/")
        sys.exit(1)

    input_csv = sys.argv[1]
    output_dir = sys.argv[2] if len(sys.argv) > 2 else None

    split_link_status(input_csv, output_dir)


if __name__ == '__main__':
    main()
