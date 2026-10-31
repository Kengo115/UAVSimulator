#!/usr/bin/env python3
"""
複数手法の最大負荷率 CDF 比較グラフ生成

各手法の全リンクの最大負荷率を昇順ソートし、
ECDF（経験累積分布関数）として複数手法を重ね描きする。
横軸: 最大負荷率(%) 対数スケール、縦軸: 累積比率

Usage:
    python scripts/plot_load_cdf_comparison.py
"""

import csv
import os

import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
import matplotlib.ticker as ticker
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

FONT_SIZE_LABEL  = 34
FONT_SIZE_TICK   = 29
FONT_SIZE_LEGEND = 22
LINE_WIDTH       = 3.0
COLORS = ['#3498db', '#e74c3c', '#2ecc71', '#9b59b6', '#f39c12', '#1abc9c', '#e91e63', '#795548']


# --- データ読み込み ---
def load_summary(csv_path: str) -> list:
    """_summary.csv を読み込み、重複排除して max_load_rate の昇順リストを返す"""
    normalized = {}
    with open(csv_path, 'r', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            a = int(row['link_from'])
            b = int(row['link_to'])
            key = (min(a, b), max(a, b))
            val = float(row['max_load_rate'])
            if key not in normalized:
                normalized[key] = val
            else:
                normalized[key] = max(normalized[key], val)

    return sorted(normalized.values())


# --- 対話入力 ---
def ask_int(prompt: str) -> int:
    while True:
        try:
            return int(input(prompt).strip())
        except ValueError:
            print("  数値を入力してください")


def interactive_input():
    print("=" * 80)
    print("  最大負荷率 CDF 比較グラフ生成")
    print("=" * 80)
    print()

    # グループ数
    num_groups = ask_int("グループ数を入力してください (例: 3): ")
    print()

    groups = []
    for i in range(num_groups):
        print(f"--- グループ{i+1} ---")
        display_name   = input(f"  表示名        (例: PG-EPS)      : ").strip()
        directory_name = input(f"  ディレクトリ名 (例: Bisectional) : ").strip()
        sim_num        = ask_int(f"  sim番号       (例: 1)           : ")

        path = (f"src/result/sim_{sim_num}/large_scale"
                f"/{directory_name}/link_status/links/_summary.csv")
        print(f"  → データ読み込み元: {path}")
        groups.append({
            'display_name':   display_name,
            'directory_name': directory_name,
            'sim':            sim_num,
        })
        print()

    # X軸範囲
    print("【X軸スケール設定（空欄で自動スケーリング）】")
    x_min_input = input("X軸 最小値 (例: 1) : ").strip()
    x_min = float(x_min_input) if x_min_input else None
    x_max_input = input("X軸 最大値 (例: 1000) : ").strip()
    x_max = float(x_max_input) if x_max_input else None
    print()

    # 出力ファイル名
    default_output = "output/load_cdf_comparison.png"
    out = input(f"出力ファイル名 (デフォルト: {default_output}): ").strip()
    output_file = out if out else default_output

    return groups, x_min, x_max, output_file


# --- グラフ描画 ---
def plot_comparison(all_data, x_min, x_max, output_file):
    fig, ax = plt.subplots(figsize=(14, 8))

    for spine in ax.spines.values():
        spine.set_visible(True)
        spine.set_color('black')
        spine.set_linewidth(1.0)

    all_vals = []
    for idx, (display_name, loads) in enumerate(all_data):
        if not loads:
            continue
        loads_arr = np.array(loads)
        n = len(loads_arr)
        cum_ratio = np.arange(1, n + 1) / n * 100

        color = COLORS[idx % len(COLORS)]
        ax.plot(loads_arr, cum_ratio,
                label=display_name, linewidth=LINE_WIDTH, color=color)
        all_vals.extend(loads_arr[loads_arr > 0].tolist())

    # X軸（対数スケール）
    ax.set_xscale('log')
    ax.set_xlabel('最大負荷率 (%)', fontsize=FONT_SIZE_LABEL, fontweight='bold', color='black')
    ax.tick_params(axis='x', labelsize=FONT_SIZE_TICK, direction='in', colors='black', which='both')

    if all_vals:
        x_lo = x_min if x_min is not None else 1.0
        x_hi = x_max if x_max is not None else 10 ** np.ceil(np.log10(max(all_vals)))
    else:
        x_lo = x_min if x_min is not None else 1.0
        x_hi = x_max if x_max is not None else 100.0
    # 左端を対数空間で 0.1 分だけ手前に広げ、10^0 がやや右に見えるようにする
    ax.set_xlim(10 ** (np.log10(x_lo) - 0.1), x_hi)

    ax.xaxis.set_major_locator(ticker.LogLocator(base=10.0, subs=(1.0,), numticks=20))
    ax.xaxis.set_minor_locator(ticker.LogLocator(base=10.0, subs=np.arange(2, 10) * 0.1, numticks=100))
    ax.xaxis.set_minor_formatter(ticker.NullFormatter())

    # Y軸
    ax.set_ylabel('累積比率 [%]', fontsize=FONT_SIZE_LABEL, fontweight='bold', color='black')
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
    groups, x_min, x_max, output_file = interactive_input()

    print()
    print("=" * 80)
    print("データ読み込み中...")
    print("=" * 80)

    all_data = []
    for group in groups:
        sim_num = group['sim']
        path = (f"src/result/sim_{sim_num}/large_scale"
                f"/{group['directory_name']}/link_status/links/_summary.csv")
        if not os.path.exists(path):
            print(f"警告: ファイルが見つかりません → {path}")
            print(f"      先に make extract-links SIM_ID={sim_num}"
                  f" METHOD={group['directory_name']} を実行してください")
            all_data.append((group['display_name'], []))
        else:
            loads = load_summary(path)
            print(f"✓ {group['display_name']} (sim_{sim_num}): {len(loads)} リンク読み込み完了")
            all_data.append((group['display_name'], loads))

    print()
    print("=" * 80)
    print("グラフ生成中...")
    print("=" * 80)
    plot_comparison(all_data, x_min, x_max, output_file)

    print()
    print("=" * 80)
    print("完了")
    print("=" * 80)


if __name__ == "__main__":
    main()
