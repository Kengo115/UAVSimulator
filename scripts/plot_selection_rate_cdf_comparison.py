#!/usr/bin/env python3
"""
SelectionRate CDF 比較グラフ生成（複数手法 × 複数λ）

複数の経路探索手法について、λ（生起率）ごとの SelectionRate（リンク選択率）の
ECDF を手法ごとのサブプロット横並びで比較する。

手法ごとにサブプロットを設け、各サブプロット内に λ ごとの CDF を重ね描きする。
PG-EPS では高負荷時に CDF が均等化方向にシフトすることを確認するためのグラフ。

データソース:
    src/result/sim_N/large_scale/{Method}/link_status/links/_summary.csv
    （make extract-links 実行後に生成される selection_rate 列を使用）

Usage:
    python scripts/plot_selection_rate_cdf_comparison.py
"""

import csv
import os

import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
import matplotlib.cm as cm
import numpy as np


# --- スタイル設定 ---
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

FONT_SIZE_LABEL  = 28
FONT_SIZE_TICK   = 23
FONT_SIZE_LEGEND = 20
FONT_SIZE_TITLE  = 28
LINE_WIDTH       = 3.0


# --- データ読み込み ---
def load_selection_rates(summary_csv: str) -> list:
    """_summary.csv から selection_rate 列を読み込み、% 変換した昇順リストを返す"""
    rates = []
    with open(summary_csv, 'r', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            rates.append(float(row['selection_rate']) * 100)
    return sorted(rates)


# --- 対話入力 ---
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


def ask_optional_float(prompt: str):
    val = input(prompt).strip()
    if not val:
        return None
    try:
        return float(val)
    except ValueError:
        print("  数値として解釈できません。自動スケーリングを使用します。")
        return None


def interactive_input():
    print("=" * 80)
    print("  SelectionRate CDF 比較グラフ生成（複数手法 × 複数λ）")
    print("=" * 80)
    print()

    # λ値入力
    num_lambdas = ask_int("λの種類数を入力してください (例: 3): ")
    lambda_values = []
    for i in range(num_lambdas):
        lv = ask_float(f"  λ{i+1} の値 (例: 1.5): ")
        lambda_values.append(lv)
    print()

    # 手法入力
    num_methods = ask_int("手法数を入力してください (例: 3): ")
    methods = []
    for i in range(num_methods):
        print(f"--- 手法{i+1} ---")
        display_name   = input(f"  表示名        (例: PG-EPS)      : ").strip()
        directory_name = input(f"  ディレクトリ名 (例: Bisectional) : ").strip()
        sim_nums = []
        for lv in lambda_values:
            sn = ask_int(f"  λ={lv} の sim番号: ")
            sim_nums.append(sn)
        methods.append({
            'display_name':   display_name,
            'directory_name': directory_name,
            'sim_nums':       sim_nums,
        })
        print()

    # X軸設定
    print("【X軸設定（空欄で自動スケーリング）】")
    x_max = ask_optional_float("X軸 最大値 [%] (例: 0.5): ")
    print()

    # 出力ファイル名
    default_output = "output/selection_rate_cdf_comparison.png"
    out = input(f"出力ファイル名 (デフォルト: {default_output}): ").strip()
    output_file = out if out else default_output

    return lambda_values, methods, x_max, output_file


# --- グラフ描画 ---
def plot_comparison(lambda_values, methods, all_data, x_max, output_file):
    """
    all_data : list of (method_dict, [rates_λ1, rates_λ2, ...])
               rates_λN は selection_rate を % 変換した昇順 list
    """
    num_methods = len(methods)

    # λ色: Blues カラーマップで薄→濃
    cmap = cm.get_cmap('Blues')
    lambda_colors = [cmap(0.35 + 0.3 * i) for i in range(len(lambda_values))]

    fig, axes = plt.subplots(
        1, num_methods,
        figsize=(7 * num_methods, 8),
        sharey=True
    )
    if num_methods == 1:
        axes = [axes]

    # X軸上限: 指定がなければ全データの最大値
    if x_max is None:
        all_vals = [v for _, rates_list in all_data for rates in rates_list for v in rates]
        x_max_val = max(all_vals) * 1.05 if all_vals else 1.0
    else:
        x_max_val = x_max

    for ax_idx, ((method, rates_list), ax) in enumerate(zip(all_data, axes)):
        for spine in ax.spines.values():
            spine.set_visible(True)
            spine.set_color('black')
            spine.set_linewidth(1.0)

        for lam_idx, (lv, rates) in enumerate(zip(lambda_values, rates_list)):
            if not rates:
                continue
            rates_arr = np.array(rates)
            n = len(rates_arr)
            cum_ratio = np.arange(1, n + 1) / n * 100

            ax.plot(rates_arr, cum_ratio,
                    label=f'λ = {lv}',
                    linewidth=LINE_WIDTH,
                    color=lambda_colors[lam_idx])

        # サブプロットタイトル
        ax.set_title(method['display_name'],
                     fontsize=FONT_SIZE_TITLE, fontweight='bold', color='black', pad=10)

        # X軸
        ax.set_xlabel('選択率 [%]',
                      fontsize=FONT_SIZE_LABEL, fontweight='bold', color='black')
        ax.set_xlim(0, x_max_val)
        ax.tick_params(axis='x', labelsize=FONT_SIZE_TICK, direction='in', colors='black')

        # Y軸
        ax.set_ylim(0, 100)
        ax.set_yticks(np.arange(0, 101, 10))
        ax.tick_params(axis='y', labelsize=FONT_SIZE_TICK, direction='in', colors='black')

        # グリッド
        ax.grid(True, alpha=0.3, linestyle='--', color='black')
        ax.set_axisbelow(True)

        # 凡例
        legend = ax.legend(loc='lower right', framealpha=1.0,
                           fontsize=FONT_SIZE_LEGEND, edgecolor='black')
        for text in legend.get_texts():
            text.set_color('black')

    # Y軸ラベルは左端サブプロットのみ
    axes[0].set_ylabel('累積比率 [%]',
                        fontsize=FONT_SIZE_LABEL, fontweight='bold', color='black')

    plt.tight_layout()

    os.makedirs(os.path.dirname(output_file) if os.path.dirname(output_file) else '.', exist_ok=True)
    plt.savefig(output_file, dpi=300, bbox_inches='tight')
    print(f"\n✓ グラフを保存しました: {output_file}")

    pdf_file = output_file.rsplit('.', 1)[0] + '.pdf'
    plt.savefig(pdf_file, bbox_inches='tight')
    print(f"✓ PDFを保存しました: {pdf_file}")

    plt.close()


# --- メイン ---
def main():
    lambda_values, methods, x_max, output_file = interactive_input()

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
                print(f"      先に make extract-links SIM_ID={sim_num}"
                      f" METHOD={method['directory_name']} を実行してください")
                rates_list.append([])
            else:
                rates = load_selection_rates(path)
                print(f"✓ {method['display_name']} λ={lv} (sim_{sim_num}): "
                      f"{len(rates)} リンク読み込み完了")
                rates_list.append(rates)
        all_data.append((method, rates_list))

    print()
    print("=" * 80)
    print("グラフ生成中...")
    print("=" * 80)
    plot_comparison(lambda_values, methods, all_data, x_max, output_file)

    print()
    print("=" * 80)
    print("完了")
    print("=" * 80)


if __name__ == "__main__":
    main()
