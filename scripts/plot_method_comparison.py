#!/usr/bin/env python3
"""
経路探索手法比較グラフ生成スクリプト

縦軸に時間、横軸に経路探索手法のグループを配置した積み上げ棒グラフを生成します。
各グループ内でλ値を小さい順に左から配置します。
"""

import csv
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import numpy as np
import os
import sys

# 日本語フォント設定
plt.rcParams['font.sans-serif'] = ['DejaVu Sans', 'Arial', 'sans-serif']
plt.rcParams['axes.unicode_minus'] = False


def read_ave_flight_status(file_path):
    """ave_flightStatus.csvから必要なデータを読み取る"""
    data = {}

    if not os.path.exists(file_path):
        print(f"警告: {file_path} が見つかりません", file=sys.stderr)
        return None

    with open(file_path, 'r') as f:
        reader = csv.DictReader(f)
        for row in reader:
            metric = row['metric']
            value = float(row['value'])
            data[metric] = value

    # 必要な4つの時間要素を返す
    return {
        'realFlightTime': data.get('avg_real_flight_time', 0),
        'waitingTime': data.get('avg_waiting_time', 0),
        'pathWaitTime': data.get('avg_path_wait_time', 0),
        'stayingTime': data.get('avg_staying_time', 0)
    }


def interactive_input():
    """対話的に入力を受け取る"""
    print("=" * 80)
    print("経路探索手法比較グラフ生成")
    print("=" * 80)
    print()

    # グループ数
    while True:
        try:
            num_groups = int(input("グループ数を入力してください (例: 2): "))
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

        print(f"  表示名: {display_name}")
        print(f"  データ読み込み元: src/result/sim_X/large_scale/{directory_name}/")

        # 要素数（λの数）
        while True:
            try:
                num_elements = int(input(f"グループ{i+1}の要素数を入力してください (例: 3): "))
                if num_elements < 1:
                    print("要素数は1以上を指定してください")
                    continue
                break
            except ValueError:
                print("数値を入力してください")

        # 各要素のλ値とsim番号を入力
        elements = []
        for j in range(num_elements):
            while True:
                try:
                    lambda_val = float(input(f"  要素{j+1}のλ値を入力してください (例: 1.0): "))
                    sim_num = int(input(f"  要素{j+1}のsim番号を入力してください (例: 1): "))
                    elements.append({
                        'lambda': lambda_val,
                        'sim': sim_num
                    })
                    break
                except ValueError:
                    print("  正しい値を入力してください")

        # λ値でソート（小さい順）
        elements.sort(key=lambda x: x['lambda'])

        groups.append({
            'display_name': display_name,      # グラフ表示用
            'directory_name': directory_name,  # ファイル読み込み用
            'elements': elements
        })

        print()

    # 出力ファイル名
    default_output = "output/method_comparison.png"
    output_file = input(f"出力ファイル名を入力してください (デフォルト: {default_output}): ").strip()
    if not output_file:
        output_file = default_output

    return groups, output_file


def load_data(groups, base_dir="src/result"):
    """各グループの各要素のデータを読み込む"""
    all_data = []

    for group in groups:
        group_data = {
            'display_name': group['display_name'],      # 表示名
            'directory_name': group['directory_name'],  # ディレクトリ名
            'bars': []
        }

        for element in group['elements']:
            lambda_val = element['lambda']
            sim_num = element['sim']

            # ファイルパス構築（ディレクトリ名を使用）
            file_path = os.path.join(
                base_dir,
                f"sim_{sim_num}",
                "large_scale",
                group['directory_name'],  # ここでdirectory_nameを使用
                "time",
                "ave_flightStatus.csv"
            )

            # データ読み込み
            data = read_ave_flight_status(file_path)

            if data is None:
                print(f"警告: {group['display_name']} λ={lambda_val} (sim_{sim_num}) のデータが読み込めませんでした")
                print(f"      パス: {file_path}")
                data = {
                    'realFlightTime': 0,
                    'waitingTime': 0,
                    'pathWaitTime': 0,
                    'stayingTime': 0
                }
            else:
                print(f"✓ {group['display_name']} λ={lambda_val} (sim_{sim_num}) データ読み込み成功")

            group_data['bars'].append({
                'lambda': lambda_val,
                'data': data
            })

        all_data.append(group_data)

    return all_data


def plot_stacked_bar(all_data, output_file):
    """積み上げ棒グラフを生成"""

    # 色の設定（4色）
    colors = {
        'realFlightTime': '#3498db',    # 青
        'waitingTime': '#e74c3c',       # 赤
        'pathWaitTime': '#f39c12',      # オレンジ
        'stayingTime': '#2ecc71'        # 緑
    }

    # ラベル（英語表記で文字化け回避）
    labels = {
        'realFlightTime': 'Real Flight Time',
        'waitingTime': 'Waiting Time (In-flight)',
        'pathWaitTime': 'Path Wait Time',
        'stayingTime': 'Pre-flight Staying Time'
    }

    # グラフの準備
    fig, ax = plt.subplots(figsize=(14, 7))

    # x軸の位置計算
    bar_width = 0.6
    bar_gap = 0.6       # 棒グラフ間のギャップ（1本分の幅）
    group_gap = 1.8     # グループ間のギャップ（短縮）

    x_positions = []
    x_labels = []
    group_info = []  # グループ名とその位置を記録
    current_x = 0

    for group in all_data:
        num_bars = len(group['bars'])
        group_start = current_x

        for i, bar in enumerate(group['bars']):
            x_positions.append(current_x)
            x_labels.append(f"λ={bar['lambda']}")
            current_x += bar_width + bar_gap  # 棒 + ギャップ

        # グループの中央位置を計算（λ=1.5の下あたり）
        group_center = group_start + (num_bars * (bar_width + bar_gap) - bar_gap) / 2
        group_info.append({
            'name': group['display_name'],
            'center': group_center
        })

        current_x = current_x - bar_gap + group_gap  # 最後のギャップを削除してグループギャップを追加

    # 各時間要素ごとに棒を描画（積み上げ）
    time_keys = ['realFlightTime', 'waitingTime', 'pathWaitTime', 'stayingTime']

    # 全バーのデータを平坦化
    all_bars = []
    for group in all_data:
        all_bars.extend(group['bars'])

    # 各時間要素の累積値を計算
    bottoms = np.zeros(len(all_bars))

    for time_key in time_keys:
        values = [bar['data'][time_key] for bar in all_bars]
        ax.bar(x_positions, values, bar_width,
               bottom=bottoms,
               label=labels[time_key],
               color=colors[time_key],
               edgecolor='white',
               linewidth=0.5)
        bottoms += values

    # x軸の設定（λ値）
    ax.set_xticks(x_positions)
    ax.set_xticklabels(x_labels, rotation=0, ha='center', fontsize=18)
    ax.tick_params(axis='x', labelsize=18)
    ax.set_xlabel('', fontsize=20, fontweight='bold')  # Lambda Valueラベルは削除

    # y軸の設定
    ax.set_ylabel('Time [s]', fontsize=20, fontweight='bold')
    ax.tick_params(axis='y', labelsize=18)

    # Y軸の範囲を設定（キリの良い数字に）
    max_value = max(bottoms)
    # 200刻みのキリの良い数字に切り上げ
    y_max = int(np.ceil(max_value / 200) * 200)
    # 凡例が重ならないよう、最低でも最大値+200は確保
    if y_max < max_value + 200:
        y_max += 200
    ax.set_ylim(0, y_max)

    # Y軸の目盛りを200刻みに設定
    ax.set_yticks(np.arange(0, y_max + 1, 200))

    # グリッド
    ax.grid(axis='y', alpha=0.3, linestyle='--')
    ax.set_axisbelow(True)

    # 凡例
    ax.legend(loc='upper left', framealpha=0.9, fontsize=18)

    # グループ名をλ値の下に追加
    for group in group_info:
        ax.text(group['center'], -0.12, group['name'],
                ha='center', va='top', fontsize=22, fontweight='bold',
                transform=ax.get_xaxis_transform())

    # レイアウト調整
    plt.tight_layout()
    plt.subplots_adjust(bottom=0.15)  # 下部の余白を確保（グループ名用）

    # 出力ディレクトリ作成
    os.makedirs(os.path.dirname(output_file) if os.path.dirname(output_file) else '.', exist_ok=True)

    # 保存
    plt.savefig(output_file, dpi=300, bbox_inches='tight')
    print(f"\n✓ グラフを保存しました: {output_file}")

    # PDFも保存
    pdf_file = output_file.rsplit('.', 1)[0] + '.pdf'
    plt.savefig(pdf_file, bbox_inches='tight')
    print(f"✓ PDFを保存しました: {pdf_file}")

    plt.close()


def main():
    # 対話的入力
    groups, output_file = interactive_input()

    print()
    print("=" * 80)
    print("データ読み込み中...")
    print("=" * 80)

    # データ読み込み
    all_data = load_data(groups)

    # グラフ生成
    print()
    print("=" * 80)
    print("グラフ生成中...")
    print("=" * 80)
    plot_stacked_bar(all_data, output_file)

    print()
    print("=" * 80)
    print("完了")
    print("=" * 80)


if __name__ == "__main__":
    main()
