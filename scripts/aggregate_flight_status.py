#!/usr/bin/env python3
"""
全クライアントの飛行データを集計して平均値を出力する
（Javaコードと同じ処理をPythonで実装）

Usage:
    python3 scripts/aggregate_flight_status.py [result_dir]

Example:
    python3 scripts/aggregate_flight_status.py src/result/large_scale/Bisectional
"""

import os
import sys
import csv


def aggregate_flight_status(result_dir):
    """全クライアントの飛行データを集計"""
    time_dir = os.path.join(result_dir, "time")

    if not os.path.exists(time_dir):
        print(f"エラー: timeディレクトリが存在しません: {time_dir}")
        return None

    # 集計用変数
    total_flight_time = 0
    total_waiting_time = 0
    total_distance = 0
    uav_count = 0
    client_count = 0

    # 全clientディレクトリを走査
    for entry in os.listdir(time_dir):
        client_dir = os.path.join(time_dir, entry)
        if entry.startswith("client") and os.path.isdir(client_dir):
            flight_times_file = os.path.join(client_dir, "flight_times.csv")
            if not os.path.exists(flight_times_file):
                continue

            client_count += 1

            with open(flight_times_file, 'r') as f:
                reader = csv.DictReader(f)
                for row in reader:
                    try:
                        total_flight_time += float(row['flightTime'])
                        total_waiting_time += float(row['waitingTime'])
                        total_distance += float(row['distance'])
                        uav_count += 1
                    except (KeyError, ValueError) as e:
                        print(f"警告: {flight_times_file} の行をスキップ: {e}")

    if uav_count == 0:
        print("警告: 集計対象のUAVデータがありません")
        return None

    # 平均値を計算
    result = {
        'avg_flight_time': total_flight_time / uav_count,
        'avg_waiting_time': total_waiting_time / uav_count,
        'avg_distance': total_distance / uav_count,
        'total_uav_count': uav_count,
        'total_client_count': client_count
    }

    return result


def save_result(result, output_path):
    """結果をCSVファイルに出力"""
    with open(output_path, 'w', newline='') as f:
        writer = csv.writer(f)
        writer.writerow(['metric', 'value', 'unit'])
        writer.writerow(['avg_flight_time', f"{result['avg_flight_time']:.2f}", 'seconds'])
        writer.writerow(['avg_waiting_time', f"{result['avg_waiting_time']:.2f}", 'seconds'])
        writer.writerow(['avg_distance', f"{result['avg_distance']:.2f}", 'meters'])
        writer.writerow(['total_uav_count', result['total_uav_count'], 'count'])
        writer.writerow(['total_client_count', result['total_client_count'], 'count'])


def main():
    # デフォルトのパス
    result_dir = "src/result/large_scale/Bisectional"

    if len(sys.argv) > 1:
        result_dir = sys.argv[1]

    print(f"集計対象: {result_dir}")

    result = aggregate_flight_status(result_dir)

    if result is None:
        sys.exit(1)

    # 結果を出力
    output_path = os.path.join(result_dir, "time", "ave_flightStatus.csv")
    save_result(result, output_path)

    print(f"\n=== 平均飛行ステータス ===")
    print(f"  集計クライアント数: {result['total_client_count']}")
    print(f"  集計UAV数: {result['total_uav_count']}")
    print(f"  平均飛行時間: {result['avg_flight_time']:.2f} 秒")
    print(f"  平均待機時間: {result['avg_waiting_time']:.2f} 秒")
    print(f"  平均飛行距離: {result['avg_distance']:.2f} m")
    print(f"\n出力: {output_path}")


if __name__ == '__main__':
    main()
