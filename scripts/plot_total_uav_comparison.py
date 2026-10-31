#!/usr/bin/env python3
"""
複数手法の空域全体 総UAV数 時系列比較グラフ生成

総UAV数 = flying_count + waiting_count の全リンク合算を複数手法で重ね描きする。
横軸: 時間(s)、縦軸: 総UAV数

Usage:
    python scripts/plot_total_uav_comparison.py
"""

import os
import sys

import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
import matplotlib.ticker as ticker
import numpy as np
import pandas as pd


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
    plt.rcParams['agg.path.chunksize'] = 10000


setup_style()

FONT_SIZE_LABEL  = 34
FONT_SIZE_TICK   = 29
FONT_SIZE_LEGEND = 22
LINE_WIDTH       = 3.0
COLORS = ['#3498db', '#e74c3c', '#2ecc71', '#9b59b6', '#f39c12', '#1abc9c', '#e91e63', '#795548']


# --- データ読み込み ---
def load_total_uav(result_dir: str, bin_s: float = 300.0):
    """link_status.csv を読み込み、各ビン境界時刻の総UAV数を返す。

    総UAV数 = flying_count + waiting_count （全リンク合算、LOCF）

    【算出方法: LOCF (Last Observation Carried Forward)】
    link_status.csv はイベント駆動記録のため、各リンクの
    (flying_count + waiting_count) を直前のイベント値で補完し、
    ビン境界時刻ごとに全リンクの最終既知値を合算する。
    """
    csv_path = os.path.join(result_dir, "link_status", "link_status.csv")
    if not os.path.exists(csv_path):
        return None, csv_path

    df = pd.read_csv(csv_path)
    for col in ('flying_count', 'waiting_count'):
        if col not in df.columns:
            return None, csv_path

    df['total_count'] = df['flying_count'] + df['waiting_count']

    # リンク・タイムスタンプ順にソート
    df = df.sort_values(['link_from', 'link_to', 'timestamp']).reset_index(drop=True)
    t_max = float(df['timestamp'].max())

    # ビン境界タイムスタンプ列を生成（0, bin_s, 2*bin_s, ..., t_max）
    bin_ts = np.arange(0.0, t_max, bin_s)
    if len(bin_ts) == 0 or bin_ts[-1] < t_max:
        bin_ts = np.append(bin_ts, t_max)

    total_per_bin = np.zeros(len(bin_ts), dtype=float)

    # 各リンクごとに LOCF でビン時刻の total_count を取得して合算
    for _, link_df in df.groupby(['link_from', 'link_to'], sort=False):
        ts = link_df['timestamp'].values
        tc = link_df['total_count'].values

        idxs = np.searchsorted(ts, bin_ts, side='right') - 1
        link_tc = np.where(idxs >= 0, tc[idxs], 0.0)
        total_per_bin += link_tc

    result = pd.DataFrame({
        'timestamp': bin_ts,
        'total_uav': total_per_bin.astype(int),
    })
    return result, csv_path


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
    print("  総UAV数 時系列比較グラフ生成")
    print("  （飛行中 + 飛行中待機 + 飛行前待機 の合算）")
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
                f"/{directory_name}/link_status/link_status.csv")
        print(f"  → データ読み込み元: {path}")
        groups.append({
            'display_name':   display_name,
            'directory_name': directory_name,
            'sim':            sim_num,
        })
        print()

    # 集計間隔
    bin_s_input = input("集計間隔 (秒) [デフォルト: 300]: ").strip()
    bin_s = float(bin_s_input) if bin_s_input else 300.0
    print()

    # Y軸スケール
    print("【Y軸スケール】")
    print("  1: 線形  2: 対数")
    scale_choice = input("  選択 [1]: ").strip()
    log_scale = (scale_choice == '2')
    print()

    # 軸スケール入力
    print("【軸スケール設定（空欄で自動スケーリング）】")
    x_max = ask_optional_float("X軸 最大値       : ")
    x_interval_input = input("X軸 間隔 (空欄で6000): ").strip()
    x_interval = float(x_interval_input) if x_interval_input else 6000.0
    print()
    if log_scale:
        while True:
            y_min = ask_optional_float("Y軸 下限値 (例: 1) : ")
            if y_min is None or y_min > 0:
                break
            print("  対数スケールの下限値は正の値を入力してください（例: 1）")
        y_max      = ask_optional_float("Y軸 最大値       : ")
        y_interval = None
    else:
        y_min      = None
        y_max      = ask_optional_float("Y軸 最大値       : ")
        y_interval = ask_optional_float("Y軸 間隔         : ")
    print()

    # 出力ファイル名
    suffix = "_log" if log_scale else ""
    default_output = f"output/total_uav_comparison{suffix}.png"
    out = input(f"出力ファイル名 (デフォルト: {default_output}): ").strip()
    output_file = out if out else default_output

    return groups, bin_s, log_scale, x_max, x_interval, y_min, y_max, y_interval, output_file


# --- グラフ描画 ---
def plot_comparison(all_data, log_scale, x_max, x_interval, y_min, y_max, y_interval, output_file, bin_s):
    fig, ax = plt.subplots(figsize=(14, 8))

    for spine in ax.spines.values():
        spine.set_visible(True)
        spine.set_color('black')
        spine.set_linewidth(1.0)

    x_data_max = 0

    for idx, (display_name, df) in enumerate(all_data):
        if df is None:
            continue
        color = COLORS[idx % len(COLORS)]
        ax.plot(df['timestamp'], df['total_uav'],
                label=display_name, linewidth=LINE_WIDTH, color=color,
                marker='o', markersize=4)
        ts_max = df['timestamp'].max()
        if ts_max > x_data_max:
            x_data_max = ts_max

    # X軸
    ax.set_xlabel('シミュレーション時間 (s)', fontsize=FONT_SIZE_LABEL, fontweight='bold', color='black')
    ax.tick_params(axis='x', labelsize=FONT_SIZE_TICK, direction='in', colors='black')
    x_limit = x_max if x_max is not None else x_data_max
    ax.set_xlim(0, x_limit)
    ax.set_xticks(np.arange(0, x_limit + 1, x_interval))

    # Y軸
    ax.tick_params(axis='y', labelsize=FONT_SIZE_TICK, direction='in', colors='black')
    if log_scale:
        ax.set_yscale('log')
        if y_min is not None:
            y_bottom = y_min
        else:
            all_vals = [v for _, df in all_data if df is not None
                        for v in df['total_uav'] if v > 0]
            y_bottom = 10 ** np.floor(np.log10(min(all_vals))) if all_vals else 1.0
        if y_max is not None:
            ax.set_ylim(y_bottom, y_max)
        else:
            ax.set_ylim(bottom=y_bottom)
        ax.yaxis.set_major_locator(ticker.LogLocator(base=10.0, subs=(1.0,), numticks=20))
        ax.yaxis.set_minor_locator(ticker.LogLocator(base=10.0, subs=np.arange(2, 10) * 0.1, numticks=100))
        ax.yaxis.set_minor_formatter(ticker.NullFormatter())
        ax.set_ylabel('総UAV数 (対数スケール)',
                      fontsize=FONT_SIZE_LABEL, fontweight='bold', color='black')
    else:
        ax.set_ylabel('総UAV数',
                      fontsize=FONT_SIZE_LABEL, fontweight='bold', color='black')
        if y_max is not None:
            ax.set_ylim(0, y_max)
            if y_interval is not None:
                ax.set_yticks(np.arange(0, y_max + 1, y_interval))
        else:
            ax.set_ylim(bottom=0)
            if y_interval is not None:
                all_vals = [v for _, df in all_data if df is not None
                            for v in df['total_uav']]
                data_max = max(all_vals) if all_vals else 0
                y_max_auto = np.ceil(data_max / y_interval) * y_interval
                ax.set_ylim(0, y_max_auto)
                ax.set_yticks(np.arange(0, y_max_auto + 1, y_interval))

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
    groups, bin_s, log_scale, x_max, x_interval, y_min, y_max, y_interval, output_file = interactive_input()

    print()
    print("=" * 80)
    print("データ読み込み中...")
    print("=" * 80)

    all_data = []
    for group in groups:
        sim_num    = group['sim']
        result_dir = (f"src/result/sim_{sim_num}/large_scale"
                      f"/{group['directory_name']}")
        df, csv_path = load_total_uav(result_dir, bin_s)
        if df is None:
            print(f"警告: データが読み込めませんでした → {csv_path}")
            print(f"      SIM_ID={sim_num} でシミュレーションを実行したか確認してください")
        else:
            print(f"✓ {group['display_name']} (sim_{sim_num}): "
                  f"{len(df)} 集計点読み込み完了 (集計間隔: {bin_s:.0f}s)")
        all_data.append((group['display_name'], df))

    print()
    print("=" * 80)
    print("グラフ生成中...")
    print("=" * 80)
    plot_comparison(all_data, log_scale, x_max, x_interval, y_min, y_max, y_interval, output_file, bin_s)

    print()
    print("=" * 80)
    print("完了")
    print("=" * 80)


if __name__ == "__main__":
    main()
