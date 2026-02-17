#!/usr/bin/env python3
"""
容量超過率を分析するスクリプト

経路探索手法が経路要求に対して、容量を超過する経路探索結果を出した割合を計算する。
判定基準: そのクライアントのUAVの中で flightStayingTime > 0 のUAVが1台でも存在するか

Usage:
    python3 scripts/analyze_capacity_exceeded.py <result_dir> [--output <output_file>]

Example:
    # 単一ディレクトリの分析
    python3 scripts/analyze_capacity_exceeded.py src/result/sim_1/large_scale/PS

    # 出力ファイルを指定
    python3 scripts/analyze_capacity_exceeded.py src/result/sim_1/large_scale/PS --output output/capacity_exceeded.csv

    # 複数ディレクトリを一括分析（シェルで実行）
    for sim in 1 2 3; do
        python3 scripts/analyze_capacity_exceeded.py src/result/sim_$sim/large_scale/PS
    done
"""

import os
import sys
import csv
import argparse
from collections import defaultdict


def analyze_capacity_exceeded(result_dir):
    """
    flight_times.csv から容量超過率を分析する

    Returns:
        dict: {
            'total_clients': int,           # 総クライアント数
            'exceeded_clients': int,        # 容量超過したクライアント数
            'exceeded_rate': float,         # 容量超過率 (%)
        }
    """
    time_dir = os.path.join(result_dir, "time")

    if not os.path.exists(time_dir):
        print(f"エラー: timeディレクトリが存在しません: {time_dir}")
        return None

    # クライアントごとの flightStayingTime を集計
    client_staying_times = defaultdict(list)

    # 全clientディレクトリを走査
    client_dirs = []
    for entry in sorted(os.listdir(time_dir)):
        client_dir = os.path.join(time_dir, entry)
        if entry.startswith("client") and os.path.isdir(client_dir):
            client_dirs.append((entry, client_dir))

    if not client_dirs:
        print(f"警告: clientディレクトリが見つかりません: {time_dir}")
        return None

    for client_name, client_dir in client_dirs:
        flight_times_file = os.path.join(client_dir, "flight_times.csv")
        if not os.path.exists(flight_times_file):
            continue

        with open(flight_times_file, 'r') as f:
            reader = csv.DictReader(f)
            for row in reader:
                try:
                    client_id = int(row.get('clientId', 0))
                    staying_time = float(row.get('flightStayingTime', 0))
                    client_staying_times[client_id].append(staying_time)
                except (KeyError, ValueError) as e:
                    print(f"警告: {flight_times_file} の行をスキップ: {e}")

    if not client_staying_times:
        print("警告: 集計対象のデータがありません")
        return None

    # 各クライアントで容量超過があったかを判定
    total_clients = len(client_staying_times)
    exceeded_clients = 0

    for client_id in sorted(client_staying_times.keys()):
        staying_times = client_staying_times[client_id]
        has_exceeded = any(t > 0 for t in staying_times)

        if has_exceeded:
            exceeded_clients += 1

    exceeded_rate = (exceeded_clients / total_clients * 100) if total_clients > 0 else 0

    return {
        'total_clients': total_clients,
        'exceeded_clients': exceeded_clients,
        'exceeded_rate': exceeded_rate,
    }


def save_result(result, output_path, method_name):
    """結果をCSVファイルに出力"""
    output_dir = os.path.dirname(output_path)
    if output_dir:
        os.makedirs(output_dir, exist_ok=True)

    with open(output_path, 'w', newline='') as f:
        writer = csv.writer(f)
        writer.writerow(['method', 'total_clients', 'exceeded_clients', 'exceeded_rate'])
        writer.writerow([
            method_name,
            result['total_clients'],
            result['exceeded_clients'],
            f"{result['exceeded_rate']:.2f}"
        ])


def main():
    parser = argparse.ArgumentParser(
        description='容量超過率を分析する'
    )
    parser.add_argument(
        'result_dir',
        help='入力ディレクトリ（time/clientN/flight_times.csvを含む）'
    )
    parser.add_argument(
        '--output', '-o',
        dest='output_file',
        default=None,
        help='出力ファイルパス（デフォルト: {result_dir}/capacity_exceeded.csv）'
    )

    args = parser.parse_args()

    result_dir = args.result_dir

    # ディレクトリ名から手法名を抽出
    method_name = os.path.basename(result_dir)

    # 出力ファイルパスを決定
    if args.output_file:
        output_file = args.output_file
    else:
        output_file = os.path.join(result_dir, "capacity_exceeded.csv")

    print(f"分析対象: {result_dir}")

    result = analyze_capacity_exceeded(result_dir)

    if result is None:
        sys.exit(1)

    # 結果を出力
    save_result(result, output_file, method_name)

    # 結果を表示
    print(f"\n=== 容量超過率分析 ===")
    print(f"  総クライアント数: {result['total_clients']}")
    print(f"  容量超過クライアント数: {result['exceeded_clients']}")
    print(f"  容量超過率: {result['exceeded_rate']:.2f}%")
    print(f"\n出力: {output_file}")


if __name__ == '__main__':
    main()
