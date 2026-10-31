#!/usr/bin/env python3
"""
SelectionRate 箱ひげ図比較グラフ生成（複数手法 × 複数λ）

各手法・λごとの SelectionRate 分布を箱ひげ図で比較する。
手法ごとにグループを分け、λごとに色分けして並べる。
箱の幅（IQR）が選択率の偏りを直接表す。

データソース:
    src/result/sim_N/large_scale/{Method}/link_status/links/_summary.csv

Usage:
    python scripts/plot_selection_boxplot.py
"""

import csv
import os

import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
import matplotlib.patches as mpatches
import matplotlib.cm as cm
import numpy as np


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
    for font_name in target_fonts:
        if font_name in available_fonts:
            plt.rcParams['font.family'] = 'sans-serif'
            plt.rcParams['font.sans-serif'] = [font_name] + plt.rcParams['font.sans-serif']
            break
    plt.rcParams['axes.unicode_minus'] = False


setup_style()

FONT_SIZE_LABEL  = 34
FONT_SIZE_TICK   = 26
FONT_SIZE_LEGEND = 22
LINE_WIDTH       = 1.5


def load_selection_rates(summary_csv: str) -> list:
    rates = []
    with open(summary_csv, 'r', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            rates.append(float(row['selection_rate']) * 100)
    return rates


def ask_int(prompt: str) -> int:
    while True:
        try:
            return int(input(prompt).strip())
        except ValueError:
            print("  数値を入力してください")


def ask_float(prompt: str) -> float:
    while True:
        try:
            return float(input(prompt).strip())
        except ValueError:
            print("  数値を入力してください")


def interactive_input():
    print("=" * 80)
    print("  SelectionRate 箱ひげ図比較グラフ生成")
    print("=" * 80)
    print()

    num_lambdas = ask_int("λの種類数を入力してください (例: 3): ")
    lambda_values = []
    for i in range(num_lambdas):
        lambda_values.append(ask_float(f"  λ{i+1} の値 (例: 1.5): "))
    print()

    num_methods = ask_int("手法数を入力してください (例: 3): ")
    methods = []
    for i in range(num_methods):
        print(f"--- 手法{i+1} ---")
        display_name   = input(f"  表示名        (例: PG-EPS)      : ").strip()
        directory_name = input(f"  ディレクトリ名 (例: Bisectional) : ").strip()
        sim_nums = []
        for lv in lambda_values:
            sim_nums.append(ask_int(f"  λ={lv} の sim番号: "))
        methods.append({
            'display_name':   display_name,
            'directory_name': directory_name,
            'sim_nums':       sim_nums,
        })
        print()

    # Y軸スケール
    print("【Y軸スケール】")
    print("  1: 線形  2: 対数")
    scale_choice = input("  選択 [1]: ").strip()
    log_scale = (scale_choice == '2')
    print()

    default_output = "output/selection_boxplot.png"
    out = input(f"出力ファイル名 (デフォルト: {default_output}): ").strip()
    output_file = out if out else default_output

    return lambda_values, methods, log_scale, output_file


def plot_boxplot(lambda_values, methods, all_data, log_scale, output_file):
    num_methods = len(methods)
    num_lambdas = len(lambda_values)

    # λ色: Blues カラーマップで薄→濃
    cmap = cm.get_cmap('Blues')
    lambda_colors = [cmap(0.35 + 0.3 * i) for i in range(num_lambdas)]

    # ボックス位置計算: 手法グループ間に gap を設ける
    group_width = num_lambdas + 1  # λ本 + 間隔1
    box_positions = []
    group_centers = []
    for m in range(num_methods):
        base = m * group_width
        positions = [base + l + 1 for l in range(num_lambdas)]
        box_positions.append(positions)
        group_centers.append(base + (num_lambdas + 1) / 2)

    fig, ax = plt.subplots(figsize=(4 * num_methods * num_lambdas // 2 + 4, 8))

    for spine in ax.spines.values():
        spine.set_visible(True)
        spine.set_color('black')
        spine.set_linewidth(1.0)

    for m_idx, (method, rates_list) in enumerate(all_data):
        for l_idx, (lv, rates) in enumerate(zip(lambda_values, rates_list)):
            if not rates:
                continue
            pos = box_positions[m_idx][l_idx]
            bp = ax.boxplot(
                rates,
                positions=[pos],
                widths=0.6,
                patch_artist=True,
                showfliers=False,
                medianprops=dict(color='black', linewidth=2.0),
                whiskerprops=dict(color='black', linewidth=LINE_WIDTH),
                capprops=dict(color='black', linewidth=LINE_WIDTH),
                boxprops=dict(linewidth=LINE_WIDTH),
            )
            bp['boxes'][0].set_facecolor(lambda_colors[l_idx])
            bp['boxes'][0].set_alpha(0.85)

    # X軸: 手法名をグループ中央に
    ax.set_xticks(group_centers)
    ax.set_xticklabels([m['display_name'] for m in methods],
                       fontsize=FONT_SIZE_TICK)
    ax.tick_params(axis='x', direction='in', colors='black')

    # Y軸
    if log_scale:
        ax.set_yscale('log')
        ax.set_ylabel('選択率 [%] (対数スケール)',
                      fontsize=FONT_SIZE_LABEL, fontweight='bold', color='black')
    else:
        ax.set_ylabel('選択率 [%]',
                      fontsize=FONT_SIZE_LABEL, fontweight='bold', color='black')
        ax.set_ylim(bottom=0)
    ax.tick_params(axis='y', labelsize=FONT_SIZE_TICK, direction='in', colors='black')

    ax.grid(True, axis='y', alpha=0.3, linestyle='--', color='black')
    ax.set_axisbelow(True)

    # 凡例: λ色のパッチ
    legend_patches = [
        mpatches.Patch(facecolor=lambda_colors[i], edgecolor='black',
                       label=f'λ = {lv}')
        for i, lv in enumerate(lambda_values)
    ]
    legend = ax.legend(handles=legend_patches, loc='upper right',
                       framealpha=1.0, fontsize=FONT_SIZE_LEGEND, edgecolor='black')
    for text in legend.get_texts():
        text.set_color('black')

    plt.tight_layout()

    os.makedirs(os.path.dirname(output_file) if os.path.dirname(output_file) else '.', exist_ok=True)
    plt.savefig(output_file, dpi=300, bbox_inches='tight')
    print(f"\n✓ グラフを保存しました: {output_file}")

    pdf_file = output_file.rsplit('.', 1)[0] + '.pdf'
    plt.savefig(pdf_file, bbox_inches='tight')
    print(f"✓ PDFを保存しました: {pdf_file}")

    plt.close()


def main():
    lambda_values, methods, log_scale, output_file = interactive_input()

    print()
    print("=" * 80)
    print("データ読み込み中...")
    print("=" * 80)

    all_data = []
    for method in methods:
        rates_list = []
        for lv, sim_num in zip(lambda_values, method['sim_nums']):
            path = (f"src/result/sim_{sim_num}/large_scale"
                    f"/{method['directory_name']}/link_status/links/_summary.csv")
            if not os.path.exists(path):
                print(f"警告: ファイルが見つかりません → {path}")
                rates_list.append([])
            else:
                rates = load_selection_rates(path)
                print(f"✓ {method['display_name']} λ={lv} (sim_{sim_num}): {len(rates)} リンク読み込み完了")
                rates_list.append(rates)
        all_data.append((method, rates_list))

    print()
    print("=" * 80)
    print("グラフ生成中...")
    print("=" * 80)
    plot_boxplot(lambda_values, methods, all_data, log_scale, output_file)

    print()
    print("=" * 80)
    print("完了")
    print("=" * 80)


if __name__ == "__main__":
    main()
