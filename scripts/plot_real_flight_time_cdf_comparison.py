#!/usr/bin/env python3
"""
realFlightTime CDF比較グラフ生成スクリプト

複数の経路探索手法を同一グラフ上で比較する累積分布関数(CDF)グラフを生成します。
横軸: 時間(秒)、縦軸: 累積割合(%)

realFlightTime = 純飛行時間（待機時間を含まない）
"""

import glob
import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
import numpy as np
import os
import pandas as pd
import sys


# --- スタイル設定 (黒色化 & フォント & 目盛り) ---
def setup_style():
    plt.rcParams['text.color'] = 'black'
    plt.rcParams['axes.labelcolor'] = 'black'
    plt.rcParams['xtick.color'] = 'black'
    plt.rcParams['ytick.color'] = 'black'
    plt.rcParams['axes.edgecolor'] = 'black'
    plt.rcParams['xtick.direction'] = 'in'
    plt.rcParams['ytick.direction'] = 'in'

    target_fonts = [
        'IPAexGothic', 'IPAGothic', 'Noto Sans CJK JP', 'Noto Sans JP',
        'Hiragino Maru Gothic Pro', 'Hiragino Kaku Gothic ProN',
        'Yu Gothic', 'MS Gothic', 'Meiryo', 'DejaVu Sans'
    ]

    available_fonts = set(f.name for f in fm.fontManager.ttflist)
    detected_font = None

    for font_name in target_fonts:
        if font_name in available_fonts:
            detected_font = font_name
            break

    if detected_font:
        plt.rcParams['font.family'] = 'sans-serif'
        plt.rcParams['font.sans-serif'] = [detected_font] + plt.rcParams['font.sans-serif']

    plt.rcParams['axes.unicode_minus'] = False


setup_style()

# フォントサイズ設定 (plot_method_comparison_jp.pyと同等)
FONT_SIZE_TITLE = 34
FONT_SIZE_TICK = 29
FONT_SIZE_LEGEND = 29

# 線の太さ
LINE_WIDTH = 3.0

# 線の色リスト
COLORS = ['#3498db', '#e74c3c', '#2ecc71', '#9b59b6', '#f39c12', '#1abc9c', '#e91e63', '#795548']


def load_real_flight_times(time_dir: str) -> list:
    """全client分のflight_times.csvを読み込み、realFlightTimeのリストを返す"""
    csv_pattern = os.path.join(time_dir, "client*/flight_times.csv")
    csv_files = sorted(glob.glob(csv_pattern))

    if not csv_files:
        return []

    real_flight_times = []

    for csv_path in csv_files:
        df = pd.read_csv(csv_path)
        real_flight_times.extend(df['realFlightTime'].tolist())

    return real_flight_times


def interactive_input():
    """対話的に入力を受け取る"""
    print("=" * 80)
    print("realFlightTime CDF比較グラフ生成")
    print("=" * 80)
    print()

    # グループ数
    while True:
        try:
            num_groups = int(input("グループ数を入力してください (例: 3): "))
            if num_groups < 1:
                print("グループ数は1以上を指定してください")
                continue
            break
        except ValueError:
            print("数値を入力してください")

    print()

    groups = []

    # 各グループの情報を入力
    for i in range(num_groups):
        print(f"--- グループ{i+1} ---")

        # グループ表示名
        display_name = input(f"グループ{i+1}の表示名を入力してください (例: PG-EPS): ").strip()

        # ディレクトリ名
        directory_name = input(f"グループ{i+1}のディレクトリ名を入力してください (例: Bisectional): ").strip()

        # sim番号
        while True:
            try:
                sim_num = int(input(f"グループ{i+1}のsim番号を入力してください (例: 1): "))
                break
            except ValueError:
                print("数値を入力してください")

        print(f"  表示名: {display_name}")
        print(f"  データ読み込み元: src/result/sim_{sim_num}/large_scale/{directory_name}/time/")

        groups.append({
            'display_name': display_name,
            'directory_name': directory_name,
            'sim': sim_num
        })

        print()

    # X軸設定
    x_max_input = input("X軸の最大値を入力してください (空欄で自動): ").strip()
    x_max = float(x_max_input) if x_max_input else None

    x_interval_input = input("X軸の間隔を入力してください (空欄で自動): ").strip()
    x_interval = float(x_interval_input) if x_interval_input else None

    # 出力ファイル名
    default_output = "output/real_flight_time_cdf_comparison.png"
    output_file = input(f"出力ファイル名を入力してください (デフォルト: {default_output}): ").strip()
    if not output_file:
        output_file = default_output

    return groups, x_max, x_interval, output_file


def load_data(groups, base_dir="src/result"):
    """各グループのデータを読み込む"""
    all_data = []

    for group in groups:
        sim_num = group['sim']

        # ファイルパス構築
        time_dir = os.path.join(
            base_dir,
            f"sim_{sim_num}",
            "large_scale",
            group['directory_name'],
            "time"
        )

        # データ読み込み
        real_flight_times = load_real_flight_times(time_dir)

        if not real_flight_times:
            print(f"警告: {group['display_name']} (sim_{sim_num}) のデータが読み込めませんでした")
            print(f"      パス: {time_dir}")
        else:
            print(f"✓ {group['display_name']} (sim_{sim_num}) データ読み込み成功: {len(real_flight_times)} UAVs")

        all_data.append({
            'display_name': group['display_name'],
            'values': sorted(real_flight_times) if real_flight_times else []
        })

    return all_data


def plot_cdf(all_data, x_max, x_interval, output_file):
    """CDFグラフを生成"""
    fig, ax = plt.subplots(figsize=(14, 8))

    # クローズドグラフ設定
    for spine in ax.spines.values():
        spine.set_visible(True)
        spine.set_color('black')
        spine.set_linewidth(1.0)

    # 各グループのCDFを描画
    data_max = 0
    for idx, data in enumerate(all_data):
        if not data['values']:
            continue

        values = np.array(data['values'])
        n = len(values)
        percentiles = np.linspace(0, 100, n)

        color = COLORS[idx % len(COLORS)]
        ax.plot(values, percentiles, label=data['display_name'], linewidth=LINE_WIDTH, color=color)

        if values[-1] > data_max:
            data_max = values[-1]

    # X軸設定
    ax.set_xlabel('realFlightTime [s]', fontsize=FONT_SIZE_TITLE, fontweight='bold', color='black')
    ax.tick_params(axis='x', direction='in', labelsize=FONT_SIZE_TICK, colors='black', which='both')

    if x_max is not None:
        ax.set_xlim(0, x_max)
        if x_interval is not None:
            ax.set_xticks(np.arange(0, x_max + 1, x_interval))
    else:
        ax.set_xlim(left=0)
        if x_interval is not None:
            x_max_auto = np.ceil(data_max / x_interval) * x_interval
            ax.set_xlim(0, x_max_auto)
            ax.set_xticks(np.arange(0, x_max_auto + 1, x_interval))

    # Y軸設定
    ax.set_ylabel('Cumulative Ratio [%]', fontsize=FONT_SIZE_TITLE, fontweight='bold', color='black')
    ax.set_ylim(0, 100)
    ax.set_yticks(np.arange(0, 101, 10))
    ax.tick_params(axis='y', direction='in', labelsize=FONT_SIZE_TICK, colors='black', which='both')

    # グリッド
    ax.grid(True, alpha=0.3, linestyle='--', color='black')
    ax.set_axisbelow(True)

    # 凡例
    legend = ax.legend(loc='lower right', framealpha=1.0, fontsize=FONT_SIZE_LEGEND, edgecolor='black')
    for text in legend.get_texts():
        text.set_color("black")

    plt.tight_layout()

    # 保存
    os.makedirs(os.path.dirname(output_file) if os.path.dirname(output_file) else '.', exist_ok=True)
    plt.savefig(output_file, dpi=300, bbox_inches='tight')
    print(f"\n✓ グラフを保存しました: {output_file}")

    pdf_file = output_file.rsplit('.', 1)[0] + '.pdf'
    plt.savefig(pdf_file, bbox_inches='tight')
    print(f"✓ PDFを保存しました: {pdf_file}")

    plt.close()


def main():
    groups, x_max, x_interval, output_file = interactive_input()

    print()
    print("=" * 80)
    print("データ読み込み中...")
    print("=" * 80)

    all_data = load_data(groups)

    print()
    print("=" * 80)
    print("グラフ生成中...")
    print("=" * 80)
    plot_cdf(all_data, x_max, x_interval, output_file)

    print()
    print("=" * 80)
    print("完了")
    print("=" * 80)


if __name__ == "__main__":
    main()
