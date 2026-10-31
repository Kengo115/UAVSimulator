#!/usr/bin/env python3
"""
複数手法の最大負荷率ランキング比較グラフ生成

各手法の全リンクを最大負荷率の降順にソートし、
ランク vs 最大負荷率の曲線を複数手法で重ね描きする。

Usage:
    python scripts/plot_load_ranking_comparison.py
"""

import csv
import os
import sys

import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
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
    """_summary.csv を読み込み、重複排除して max_load_rate の降順リストを返す"""
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

    return sorted(normalized.values(), reverse=True)


# --- 対話入力 ---
def ask_int(prompt: str) -> int:
    while True:
        try:
            return int(input(prompt).strip())
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
    print("  最大負荷率ランキング比較グラフ生成")
    print("=" * 80)
    print()

    # グループ数
    num_groups = ask_int("グループ数を入力してください (例: 3): ")
    print()

    groups = []
    for i in range(num_groups):
        print(f"--- グループ{i+1} ---")
        display_name    = input(f"  表示名        (例: PG-EPS)      : ").strip()
        directory_name  = input(f"  ディレクトリ名 (例: Bisectional) : ").strip()
        sim_num         = ask_int(f"  sim番号       (例: 1)           : ")

        path = (f"src/result/sim_{sim_num}/large_scale"
                f"/{directory_name}/link_status/links/_summary.csv")
        print(f"  → データ読み込み元: {path}")
        groups.append({
            'display_name':   display_name,
            'directory_name': directory_name,
            'sim':            sim_num,
        })
        print()

    # Y軸スケール
    print("【Y軸スケール】")
    print("  1: 線形  2: 対数")
    scale_choice = input("  選択 [1]: ").strip()
    log_scale = (scale_choice == '2')
    print()

    # 軸スケール入力
    print("【軸スケール設定（空欄で自動スケーリング）】")
    x_max      = ask_optional_float("X軸 最大値       : ")
    print()
    if log_scale:
        y_max      = ask_optional_float("Y軸 最大値       : ")
        y_interval = None
        print("  ※ 対数スケールのため Y軸間隔は自動設定されます")
    else:
        y_max      = ask_optional_float("Y軸 最大値       : ")
        y_interval = ask_optional_float("Y軸 間隔         : ")
    print()

    # 出力ファイル名
    suffix = "_log" if log_scale else ""
    default_output = f"output/load_ranking_comparison{suffix}.png"
    out = input(f"出力ファイル名 (デフォルト: {default_output}): ").strip()
    output_file = out if out else default_output

    return groups, log_scale, x_max, y_max, y_interval, output_file


# --- グラフ描画 ---
def plot_comparison(all_data, log_scale, x_max, y_max, y_interval, output_file):
    fig, ax = plt.subplots(figsize=(14, 8))

    for spine in ax.spines.values():
        spine.set_visible(True)
        spine.set_color('black')
        spine.set_linewidth(1.0)

    total_links_max = 0

    for idx, (display_name, loads) in enumerate(all_data):
        if not loads:
            continue
        ranks = np.arange(1, len(loads) + 1)
        color = COLORS[idx % len(COLORS)]
        ax.plot(ranks, loads, label=display_name, linewidth=LINE_WIDTH, color=color)
        if len(loads) > total_links_max:
            total_links_max = len(loads)

    # X軸
    ax.set_xlabel('リンク順位', fontsize=FONT_SIZE_LABEL, fontweight='bold', color='black')
    ax.tick_params(axis='x', labelsize=FONT_SIZE_TICK, direction='in', colors='black')
    x_limit = x_max if x_max is not None else total_links_max
    ax.set_xlim(1, x_limit)

    # X軸目盛り間隔（リンク総数に応じて自動）
    if x_limit <= 50:
        x_tick_interval = 10
    elif x_limit <= 100:
        x_tick_interval = 20
    elif x_limit <= 200:
        x_tick_interval = 50
    else:
        x_tick_interval = 100
    ax.set_xticks(np.arange(0, x_limit + 1, x_tick_interval))

    # Y軸
    ax.tick_params(axis='y', labelsize=FONT_SIZE_TICK, direction='in', colors='black')
    if log_scale:
        ax.set_yscale('log')
        # 全データ中の最小非ゼロ値から下限を決める
        all_vals = [v for _, loads in all_data for v in loads if v > 0]
        y_bottom = 10 ** np.floor(np.log10(min(all_vals))) if all_vals else 1.0
        if y_max is not None:
            ax.set_ylim(y_bottom, y_max)
        else:
            ax.set_ylim(bottom=y_bottom)
        ax.set_ylabel('最大負荷率 (%) (対数スケール)',
                      fontsize=FONT_SIZE_LABEL, fontweight='bold', color='black')
    else:
        ax.set_ylabel('最大負荷率 (%)',
                      fontsize=FONT_SIZE_LABEL, fontweight='bold', color='black')
        if y_max is not None:
            ax.set_ylim(0, y_max)
            if y_interval is not None:
                ax.set_yticks(np.arange(0, y_max + 1, y_interval))
        else:
            ax.set_ylim(bottom=0)
            if y_interval is not None:
                all_vals = [v for _, loads in all_data for v in loads]
                data_max = max(all_vals) if all_vals else 0
                y_max_auto = np.ceil(data_max / y_interval) * y_interval
                ax.set_ylim(0, y_max_auto)
                ax.set_yticks(np.arange(0, y_max_auto + 1, y_interval))

    # グリッド
    ax.grid(True, alpha=0.3, linestyle='--', color='black')
    ax.set_axisbelow(True)

    # 凡例
    legend = ax.legend(loc='upper right', framealpha=1.0,
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
    groups, log_scale, x_max, y_max, y_interval, output_file = interactive_input()

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
    plot_comparison(all_data, log_scale, x_max, y_max, y_interval, output_file)

    print()
    print("=" * 80)
    print("完了")
    print("=" * 80)


if __name__ == "__main__":
    main()
