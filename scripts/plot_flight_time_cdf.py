#!/usr/bin/env python3
"""
全UAVのflightTimeとrealFlightTimeの累積分布グラフを生成

横軸: 割合 (0〜100%)  「左→右で早いUAV→遅いUAV」
縦軸: Time (秒)
2線: flightTime (合計時間), realFlightTime (純飛行時間)

flightTime = realFlightTime + waitingTime + pathWaitTime + flightStayingTime
           = flight_times.csv の flightTime列 + pathWaitTime列

Usage:
    python scripts/plot_flight_time_cdf.py <time_dir> <output_dir> [y_max] [y_interval]

Example:
    python scripts/plot_flight_time_cdf.py src/result/sim_4/large_scale/Bisectional/time output/sim_4/graphs
    python scripts/plot_flight_time_cdf.py src/result/sim_4/large_scale/Bisectional/time output/sim_4/graphs 2000
    python scripts/plot_flight_time_cdf.py src/result/sim_4/large_scale/Bisectional/time output/sim_4/graphs 2000 200
"""

import sys
import os
import glob
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt


def load_all_flight_times(time_dir: str) -> tuple:
    """全client分のflight_times.csvを読み込み、flightTimeとrealFlightTimeのリストを返す"""
    csv_pattern = os.path.join(time_dir, "client*/flight_times.csv")
    csv_files = sorted(glob.glob(csv_pattern))

    if not csv_files:
        print(f"Error: flight_times.csv が見つかりません: {csv_pattern}")
        sys.exit(1)

    flight_times = []
    real_flight_times = []

    for csv_path in csv_files:
        df = pd.read_csv(csv_path)
        # flightTime = CSV既存のflightTime + pathWaitTime
        ft = df['flightTime'] + df['pathWaitTime']
        flight_times.extend(ft.tolist())
        real_flight_times.extend(df['realFlightTime'].tolist())

    return flight_times, real_flight_times


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)

    time_dir = sys.argv[1]
    output_dir = sys.argv[2]
    y_max = float(sys.argv[3]) if len(sys.argv) > 3 and sys.argv[3] else None
    y_interval = float(sys.argv[4]) if len(sys.argv) > 4 and sys.argv[4] else None

    if not os.path.isdir(time_dir):
        print(f"Error: ディレクトリが見つかりません: {time_dir}")
        sys.exit(1)

    os.makedirs(output_dir, exist_ok=True)

    # データ収集
    flight_times, real_flight_times = load_all_flight_times(time_dir)
    total_uavs = len(flight_times)
    print(f"Total UAVs: {total_uavs}")

    # ソート
    flight_times_sorted = np.sort(flight_times)
    real_flight_times_sorted = np.sort(real_flight_times)

    # 横軸: 割合 (0〜100%)
    percentiles = np.linspace(0, 100, total_uavs)

    # グラフ
    fig, ax = plt.subplots(figsize=(14, 8))

    ax.plot(percentiles, flight_times_sorted, label='flightTime', linewidth=2, color='blue')
    ax.plot(percentiles, real_flight_times_sorted, label='realFlightTime', linewidth=2, color='green')

    ax.set_xlabel('Cumulative Ratio (%)', fontsize=24)
    ax.set_ylabel('Time (s)', fontsize=24)
    ax.tick_params(axis='both', labelsize=20)

    # X軸
    ax.set_xlim(0, 100)
    ax.set_xticks(np.arange(0, 101, 10))

    # Y軸
    if y_max is not None:
        ax.set_ylim(0, y_max)
        if y_interval is not None:
            ax.set_yticks(np.arange(0, y_max + 1, y_interval))
    else:
        ax.set_ylim(bottom=0)
        if y_interval is not None:
            data_max = max(flight_times_sorted[-1], real_flight_times_sorted[-1])
            y_max_auto = np.ceil(data_max / y_interval) * y_interval
            ax.set_ylim(0, y_max_auto)
            ax.set_yticks(np.arange(0, y_max_auto + 1, y_interval))

    ax.legend(fontsize=20)
    ax.grid(True, alpha=0.3)
    plt.tight_layout()

    output_path = os.path.join(output_dir, 'flight_time_cdf.png')
    plt.savefig(output_path, dpi=150, bbox_inches='tight')
    plt.close()

    print(f"Graph saved: {output_path}")
    print(f"flightTime    - min: {flight_times_sorted[0]:.1f}s, median: {flight_times_sorted[total_uavs//2]:.1f}s, max: {flight_times_sorted[-1]:.1f}s")
    print(f"realFlightTime - min: {real_flight_times_sorted[0]:.1f}s, median: {real_flight_times_sorted[total_uavs//2]:.1f}s, max: {real_flight_times_sorted[-1]:.1f}s")


if __name__ == "__main__":
    main()
