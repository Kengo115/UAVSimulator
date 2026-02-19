#!/usr/bin/env python3
"""
容量超過経路割り当て率 比較グラフ生成スクリプト

複数の経路探索手法を同一グラフ上で比較する散布図を生成します。
横軸: λ値、縦軸: Over-capacity Assignment Rate [%]

各手法は異なる色と形のマーカーで表示されます。
"""

import csv
import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
import numpy as np
import os
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
FONT_SIZE_TITLE = 31
FONT_SIZE_TICK = 26
FONT_SIZE_LEGEND = 22

# マーカーの色リスト
COLORS = ['#3498db', '#e74c3c', '#2ecc71', '#9b59b6', '#f39c12', '#1abc9c', '#e91e63', '#795548']

# マーカーの形リスト
MARKERS = ['o', 's', '^', 'D', 'v', 'p', 'h', '*']

# マーカーサイズ
MARKER_SIZE = 200


def read_exceeded_rate(file_path):
    """capacity_exceeded.csvからexceeded_rateを読み取る"""
    if not os.path.exists(file_path):
        return None

    with open(file_path, 'r') as f:
        reader = csv.DictReader(f)
        for row in reader:
            return float(row['exceeded_rate'])

    return None


def interactive_input():
    """対話的に入力を受け取る"""
    print("=" * 80)
    print("容量超過経路割り当て率 比較グラフ生成")
    print("=" * 80)
    print()

    # λの種類数
    while True:
        try:
            num_lambdas = int(input("λの種類数を入力してください (例: 3): "))
            if num_lambdas < 1:
                print("λの種類数は1以上を指定してください")
                continue
            break
        except ValueError:
            print("数値を入力してください")

    print()

    # 各λの値を入力
    lambda_values = []
    for i in range(num_lambdas):
        while True:
            try:
                lambda_val = float(input(f"λ{i+1}の値を入力してください: "))
                lambda_values.append(lambda_val)
                break
            except ValueError:
                print("数値を入力してください")

    print()

    # 経路探索手法の数
    while True:
        try:
            num_methods = int(input("経路探索手法の数を入力してください (例: 4): "))
            if num_methods < 1:
                print("手法数は1以上を指定してください")
                continue
            break
        except ValueError:
            print("数値を入力してください")

    print()

    # 各手法の情報を入力
    methods = []
    for i in range(num_methods):
        print(f"--- 手法{i+1} ---")

        display_name = input(f"手法{i+1}の表示名を入力してください (例: PG-EPS): ").strip()
        directory_name = input(f"手法{i+1}のディレクトリ名を入力してください (例: Bisectional): ").strip()

        methods.append({
            'display_name': display_name,
            'directory_name': directory_name
        })

        print()

    # 各λ×手法のsim番号を入力
    print("各λ×手法のsim番号を入力してください:")
    sim_mapping = {}  # (method_idx, lambda_val) -> sim_num

    for method in methods:
        for lambda_val in lambda_values:
            while True:
                try:
                    sim_num = int(input(f"  {method['display_name']} (λ={lambda_val}) のsim番号: "))
                    sim_mapping[(method['display_name'], lambda_val)] = sim_num
                    break
                except ValueError:
                    print("  数値を入力してください")

    print()

    # Y軸設定
    y_max_input = input("Y軸の最大値を入力してください (空欄で自動): ").strip()
    y_max = float(y_max_input) if y_max_input else None

    y_interval_input = input("Y軸の間隔を入力してください (空欄で自動): ").strip()
    y_interval = float(y_interval_input) if y_interval_input else None

    # 出力ファイル名
    default_output = "output/exceeded_rate_comparison.png"
    output_file = input(f"出力ファイル名を入力してください (デフォルト: {default_output}): ").strip()
    if not output_file:
        output_file = default_output

    return lambda_values, methods, sim_mapping, y_max, y_interval, output_file


def load_data(lambda_values, methods, sim_mapping, base_dir="src/result"):
    """各手法×λのデータを読み込む"""
    all_data = []

    for method in methods:
        method_data = {
            'display_name': method['display_name'],
            'points': []  # [(lambda_val, exceeded_rate), ...]
        }

        for lambda_val in lambda_values:
            sim_num = sim_mapping.get((method['display_name'], lambda_val))
            if sim_num is None:
                continue

            # ファイルパス構築
            file_path = os.path.join(
                base_dir,
                f"sim_{sim_num}",
                "large_scale",
                method['directory_name'],
                "capacity_exceeded.csv"
            )

            # データ読み込み
            exceeded_rate = read_exceeded_rate(file_path)

            if exceeded_rate is None:
                print(f"警告: {method['display_name']} (λ={lambda_val}, sim_{sim_num}) のデータが読み込めませんでした")
                print(f"      パス: {file_path}")
            else:
                print(f"✓ {method['display_name']} (λ={lambda_val}, sim_{sim_num}) データ読み込み成功: {exceeded_rate}%")
                method_data['points'].append((lambda_val, exceeded_rate))

        all_data.append(method_data)

    return all_data


def plot_scatter(all_data, lambda_values, y_max, y_interval, output_file):
    """散布図を生成"""
    fig, ax = plt.subplots(figsize=(14, 8))

    # クローズドグラフ設定
    for spine in ax.spines.values():
        spine.set_visible(True)
        spine.set_color('black')
        spine.set_linewidth(1.0)

    # 各手法のデータをプロット
    data_max = 0
    for idx, method_data in enumerate(all_data):
        if not method_data['points']:
            continue

        x_vals = [p[0] for p in method_data['points']]
        y_vals = [p[1] for p in method_data['points']]

        color = COLORS[idx % len(COLORS)]
        marker = MARKERS[idx % len(MARKERS)]

        ax.scatter(x_vals, y_vals,
                   label=method_data['display_name'],
                   s=MARKER_SIZE,
                   c=color,
                   marker=marker,
                   edgecolors='black',
                   linewidths=1.0,
                   zorder=3)

        if y_vals:
            data_max = max(data_max, max(y_vals))

    # X軸設定
    ax.set_xlabel('λ', fontsize=FONT_SIZE_TITLE, fontweight='bold', color='black')
    ax.tick_params(axis='x', direction='in', labelsize=FONT_SIZE_TICK, colors='black', which='both')

    # X軸の範囲と目盛りを設定
    lambda_min = min(lambda_values)
    lambda_max = max(lambda_values)
    x_margin = (lambda_max - lambda_min) * 0.1 if lambda_max > lambda_min else 0.5
    ax.set_xlim(lambda_min - x_margin, lambda_max + x_margin)
    ax.set_xticks(lambda_values)

    # Y軸設定
    ax.set_ylabel('Over-capacity Assignment Rate [%]', fontsize=FONT_SIZE_TITLE, fontweight='bold', color='black')
    ax.tick_params(axis='y', direction='in', labelsize=FONT_SIZE_TICK, colors='black', which='both')

    if y_max is not None:
        ax.set_ylim(0, y_max)
        if y_interval is not None:
            ax.set_yticks(np.arange(0, y_max + 1, y_interval))
    else:
        ax.set_ylim(bottom=0)
        if y_interval is not None:
            y_max_auto = np.ceil(data_max / y_interval) * y_interval
            ax.set_ylim(0, y_max_auto)
            ax.set_yticks(np.arange(0, y_max_auto + 1, y_interval))

    # グリッド
    ax.grid(True, alpha=0.3, linestyle='--', color='black', zorder=1)
    ax.set_axisbelow(True)

    # 凡例
    legend = ax.legend(loc='best', framealpha=1.0, fontsize=FONT_SIZE_LEGEND, edgecolor='black')
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
    lambda_values, methods, sim_mapping, y_max, y_interval, output_file = interactive_input()

    print()
    print("=" * 80)
    print("データ読み込み中...")
    print("=" * 80)

    all_data = load_data(lambda_values, methods, sim_mapping)

    print()
    print("=" * 80)
    print("グラフ生成中...")
    print("=" * 80)
    plot_scatter(all_data, lambda_values, y_max, y_interval, output_file)

    print()
    print("=" * 80)
    print("完了")
    print("=" * 80)


if __name__ == "__main__":
    main()
