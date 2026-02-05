#!/usr/bin/env python3
"""
全クライアントの飛行データを集計して平均値を出力する
（Javaコードと同じ処理をPythonで実装）

Usage:
    python3 scripts/aggregate_flight_status.py <result_dir> [--output <output_dir>]

Example:
    # 通常使用（result_dir/time/ave_flightStatus.csvに出力）
    python3 scripts/aggregate_flight_status.py src/result/sim_1/large_scale/PS

    # 出力先を指定
    python3 scripts/aggregate_flight_status.py src/result/sim_1/large_scale/PS --output src/result/sim_1

Options:
    result_dir      入力ディレクトリ（time/clientN/flight_times.csvを含む）
    --output, -o    出力先ディレクトリ（time/ave_flightStatus.csvとして出力）
"""

import os
import sys
import csv
import argparse


def aggregate_flight_status(result_dir):
    """
    全クライアントの飛行データを集計
    Java版ResultOutputManager.outputAverageFlightStatus()と同等の処理
    """
    time_dir = os.path.join(result_dir, "time")

    if not os.path.exists(time_dir):
        print(f"エラー: timeディレクトリが存在しません: {time_dir}")
        return None

    # 集計用変数（Phase 11対応: Java版と同じフィールド）
    total_flight_time = 0       # flightTime (realFlightTime + waitingTime + flightStayingTime)
    total_real_flight_time = 0  # 実飛行時間
    total_waiting_time = 0      # 飛行中待機時間
    total_path_wait_time = 0    # 経路待ち時間
    total_staying_time = 0      # 1ホップ目待機時間
    total_distance = 0
    uav_count = 0

    # 評価・デバッグ用統計
    fully_completed_client_count = 0   # 10台全て完了したclient数
    partially_completed_client_count = 0  # 1-9台完了したclient数
    max_flight_time = 0
    min_flight_time = float('inf')
    max_waiting_time = 0
    min_waiting_time = float('inf')

    client_dirs = []

    # 全clientディレクトリを走査
    for entry in sorted(os.listdir(time_dir)):
        client_dir = os.path.join(time_dir, entry)
        if entry.startswith("client") and os.path.isdir(client_dir):
            client_dirs.append(client_dir)

    if not client_dirs:
        print(f"警告: clientディレクトリが見つかりません: {time_dir}")
        return None

    for client_dir in client_dirs:
        flight_times_file = os.path.join(client_dir, "flight_times.csv")
        if not os.path.exists(flight_times_file):
            continue

        client_uav_count = 0  # このclientのUAV完了数

        with open(flight_times_file, 'r') as f:
            reader = csv.DictReader(f)
            for row in reader:
                try:
                    # Phase 11: 新しいCSVフォーマット
                    # source,dest,flightTime,realFlightTime,waitingTime,pathWaitTime,flightStayingTime,...
                    flight_time = float(row.get('flightTime', 0))
                    real_flight_time = float(row.get('realFlightTime', 0))
                    waiting_time = float(row.get('waitingTime', 0))
                    path_wait_time = float(row.get('pathWaitTime', 0))
                    staying_time = float(row.get('flightStayingTime', 0))
                    distance = float(row.get('distance', 0))

                    total_flight_time += flight_time
                    total_real_flight_time += real_flight_time
                    total_waiting_time += waiting_time
                    total_path_wait_time += path_wait_time
                    total_staying_time += staying_time
                    total_distance += distance
                    uav_count += 1
                    client_uav_count += 1

                    # 最大/最小値の更新
                    if flight_time > max_flight_time:
                        max_flight_time = flight_time
                    if flight_time < min_flight_time:
                        min_flight_time = flight_time
                    if waiting_time > max_waiting_time:
                        max_waiting_time = waiting_time
                    if waiting_time < min_waiting_time:
                        min_waiting_time = waiting_time

                except (KeyError, ValueError) as e:
                    print(f"警告: {flight_times_file} の行をスキップ: {e}")

        # このclientのUAV完了数をカウント
        if client_uav_count == 10:
            fully_completed_client_count += 1
        elif client_uav_count > 0:
            partially_completed_client_count += 1

    if uav_count == 0:
        print("警告: 集計対象のUAVデータがありません")
        return None

    # 最小値が更新されていない場合は0にする
    if min_flight_time == float('inf'):
        min_flight_time = 0
    if min_waiting_time == float('inf'):
        min_waiting_time = 0

    total_client_count = len(client_dirs)

    # 平均値を計算
    result = {
        # 平均値
        'avg_flight_time': total_flight_time / uav_count,
        'avg_real_flight_time': total_real_flight_time / uav_count,
        'avg_waiting_time': total_waiting_time / uav_count,
        'avg_path_wait_time': total_path_wait_time / uav_count,
        'avg_staying_time': total_staying_time / uav_count,
        'avg_distance': total_distance / uav_count,

        # 最大/最小値
        'max_flight_time': max_flight_time,
        'min_flight_time': min_flight_time,
        'max_waiting_time': max_waiting_time,
        'min_waiting_time': min_waiting_time,

        # 総数・完了率
        'total_uav_count': uav_count,
        'total_client_count': total_client_count,
        'fully_completed_client_count': fully_completed_client_count,
        'partially_completed_client_count': partially_completed_client_count,

        # 完了率（評価用）
        'avg_uav_per_client': uav_count / total_client_count if total_client_count > 0 else 0,
        'uav_completion_rate': (uav_count * 100.0) / (total_client_count * 10) if total_client_count > 0 else 0,
        'fully_completed_client_rate': (fully_completed_client_count * 100.0) / total_client_count if total_client_count > 0 else 0,
    }

    return result


def save_result(result, output_path):
    """結果をCSVファイルに出力（Java版と同じフォーマット）"""
    # 出力ディレクトリが存在しない場合は作成
    os.makedirs(os.path.dirname(output_path), exist_ok=True)

    with open(output_path, 'w', newline='') as f:
        writer = csv.writer(f)
        writer.writerow(['metric', 'value', 'unit'])

        # 平均値
        writer.writerow(['avg_flight_time', f"{result['avg_flight_time']:.2f}", 'seconds'])
        writer.writerow(['avg_real_flight_time', f"{result['avg_real_flight_time']:.2f}", 'seconds'])
        writer.writerow(['avg_waiting_time', f"{result['avg_waiting_time']:.2f}", 'seconds'])
        writer.writerow(['avg_path_wait_time', f"{result['avg_path_wait_time']:.2f}", 'seconds'])
        writer.writerow(['avg_staying_time', f"{result['avg_staying_time']:.2f}", 'seconds'])
        writer.writerow(['avg_distance', f"{result['avg_distance']:.2f}", 'meters'])

        # 最大/最小値（デバッグ用）
        writer.writerow(['max_flight_time', f"{result['max_flight_time']:.2f}", 'seconds'])
        writer.writerow(['min_flight_time', f"{result['min_flight_time']:.2f}", 'seconds'])
        writer.writerow(['max_waiting_time', f"{result['max_waiting_time']:.2f}", 'seconds'])
        writer.writerow(['min_waiting_time', f"{result['min_waiting_time']:.2f}", 'seconds'])

        # 総数・完了率
        writer.writerow(['total_uav_count', result['total_uav_count'], 'count'])
        writer.writerow(['total_client_count', result['total_client_count'], 'count'])
        writer.writerow(['fully_completed_client_count', result['fully_completed_client_count'], 'count'])
        writer.writerow(['partially_completed_client_count', result['partially_completed_client_count'], 'count'])

        # 完了率（評価用）
        writer.writerow(['avg_uav_per_client', f"{result['avg_uav_per_client']:.2f}", 'count'])
        writer.writerow(['uav_completion_rate', f"{result['uav_completion_rate']:.2f}", 'percent'])
        writer.writerow(['fully_completed_client_rate', f"{result['fully_completed_client_rate']:.2f}", 'percent'])


def main():
    parser = argparse.ArgumentParser(
        description='全クライアントの飛行データを集計して平均値を出力する'
    )
    parser.add_argument(
        'result_dir',
        nargs='?',
        default='src/result/large_scale/Bisectional',
        help='入力ディレクトリ（time/clientN/flight_times.csvを含む）'
    )
    parser.add_argument(
        '--output', '-o',
        dest='output_dir',
        default=None,
        help='出力先ディレクトリ（time/ave_flightStatus.csvとして出力）'
    )

    args = parser.parse_args()

    result_dir = args.result_dir
    output_dir = args.output_dir if args.output_dir else result_dir

    print(f"集計対象: {result_dir}")

    result = aggregate_flight_status(result_dir)

    if result is None:
        sys.exit(1)

    # 結果を出力
    output_path = os.path.join(output_dir, "time", "ave_flightStatus.csv")
    save_result(result, output_path)

    print(f"\n=== 平均飛行ステータス ===")
    print(f"  集計クライアント数: {result['total_client_count']}")
    print(f"  集計UAV数: {result['total_uav_count']}")
    print(f"  完全完了クライアント: {result['fully_completed_client_count']} ({result['fully_completed_client_rate']:.1f}%)")
    print(f"  UAV完了率: {result['uav_completion_rate']:.1f}%")
    print(f"  ---")
    print(f"  平均総飛行時間: {result['avg_flight_time']:.2f} 秒")
    print(f"  平均飛行時間: {result['avg_real_flight_time']:.2f} 秒")
    print(f"  平均上空待機時間: {result['avg_waiting_time']:.2f} 秒")
    print(f"  平均経路待ち時間: {result['avg_path_wait_time']:.2f} 秒")
    print(f"  平均飛行前待機時間: {result['avg_staying_time']:.2f} 秒")
    print(f"  平均飛行距離: {result['avg_distance']:.2f} m")
    print(f"\n出力: {output_path}")


if __name__ == '__main__':
    main()
