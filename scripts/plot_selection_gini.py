#!/usr/bin/env python3
"""
SelectionRate Gini係数比較グラフ生成（複数手法 × 複数λ）

各手法のリンク選択率（SelectionRate）の Gini 係数を λ ごとに計算し、
X軸=λ、Y軸=Gini係数の折れ線グラフとして複数手法を比較する。

Gini係数: 0=完全均等分布、1=完全集中
高負荷（高λ）時に PG-EPS の Gini 係数が低下すれば、
経路分散の改善が定量的に示される。

データソース:
    src/result/sim_N/large_scale/{Method}/link_status/links/_summary.csv

Usage:
    python scripts/plot_selection_gini.py
"""

import csv
import os

import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
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
FONT_SIZE_TICK   = 29
FONT_SIZE_LEGEND = 22
LINE_WIDTH       = 3.0
MARKER_SIZE      = 12
COLORS = ['#3498db', '#e74c3c', '#2ecc71', '#9b59b6', '#f39c12', '#1abc9c']


def load_selection_rates(summary_csv: str) -> list:
    rates = []
    with open(summary_csv, 'r', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            rates.append(float(row['selection_rate']))
    return rates


def gini_coefficient(values: list) -> float:
    arr = np.sort(np.array(values, dtype=float))
    n = len(arr)
    if n == 0 or arr.sum() == 0:
        return 0.0
    index = np.arange(1, n + 1)
    return float((2 * np.dot(index, arr)) / (n * arr.sum()) - (n + 1) / n)


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
    print("  SelectionRate Gini係数比較グラフ生成")
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

    default_output = "output/selection_gini.png"
    out = input(f"出力ファイル名 (デフォルト: {default_output}): ").strip()
    output_file = out if out else default_output

    return lambda_values, methods, output_file


def plot_gini(lambda_values, methods, all_data, output_file):
    fig, ax = plt.subplots(figsize=(12, 8))

    for spine in ax.spines.values():
        spine.set_visible(True)
        spine.set_color('black')
        spine.set_linewidth(1.0)

    for idx, (method, gini_values) in enumerate(all_data):
        valid = [(lv, g) for lv, g in zip(lambda_values, gini_values) if g is not None]
        if not valid:
            continue
        xs, ys = zip(*valid)
        color = COLORS[idx % len(COLORS)]
        ax.plot(xs, ys,
                label=method['display_name'],
                linewidth=LINE_WIDTH,
                color=color,
                marker='o',
                markersize=MARKER_SIZE,
                markerfacecolor=color,
                markeredgecolor='white',
                markeredgewidth=1.5)

    ax.set_xlabel('生起率 λ',
                  fontsize=FONT_SIZE_LABEL, fontweight='bold', color='black')
    ax.set_ylabel('Gini 係数',
                  fontsize=FONT_SIZE_LABEL, fontweight='bold', color='black')
    ax.set_xticks(lambda_values)
    ax.set_xticklabels([str(lv) for lv in lambda_values])
    ax.set_ylim(0, 1)
    ax.tick_params(axis='both', labelsize=FONT_SIZE_TICK, direction='in', colors='black')
    ax.grid(True, alpha=0.3, linestyle='--', color='black')
    ax.set_axisbelow(True)

    legend = ax.legend(loc='best', framealpha=1.0,
                       fontsize=FONT_SIZE_LEGEND, edgecolor='black')
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
    lambda_values, methods, output_file = interactive_input()

    print()
    print("=" * 80)
    print("データ読み込み・Gini係数計算中...")
    print("=" * 80)

    all_data = []
    for method in methods:
        gini_values = []
        for lv, sim_num in zip(lambda_values, method['sim_nums']):
            path = (f"src/result/sim_{sim_num}/large_scale"
                    f"/{method['directory_name']}/link_status/links/_summary.csv")
            if not os.path.exists(path):
                print(f"警告: ファイルが見つかりません → {path}")
                gini_values.append(None)
            else:
                rates = load_selection_rates(path)
                g = gini_coefficient(rates)
                print(f"✓ {method['display_name']} λ={lv} (sim_{sim_num}): "
                      f"Gini = {g:.4f} ({len(rates)} リンク)")
                gini_values.append(g)
        all_data.append((method, gini_values))

    print()
    print("=" * 80)
    print("グラフ生成中...")
    print("=" * 80)
    plot_gini(lambda_values, methods, all_data, output_file)

    print()
    print("=" * 80)
    print("完了")
    print("=" * 80)


if __name__ == "__main__":
    main()
