#!/usr/bin/env python3
"""
経路探索手法比較グラフ生成スクリプト (日本語版・クローズドグラフ・黒色仕様・内向き目盛り・特大フォント)

- 縦軸: 計測時間, 横軸: 手法
- 日本語フォント対応
- 完全な黒色(#000000)での描画
- クローズドグラフ（4辺枠線あり）
- 目盛り内向き
"""

import csv
import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
import numpy as np
import os
import sys

# --- スタイル設定 (黒色化 & フォント & 目盛り) ---
def setup_style():
    # 1. 基本色をすべて「黒」にする
    plt.rcParams['text.color'] = 'black'
    plt.rcParams['axes.labelcolor'] = 'black'
    plt.rcParams['xtick.color'] = 'black'
    plt.rcParams['ytick.color'] = 'black'
    plt.rcParams['axes.edgecolor'] = 'black'
    
    # 目盛りを内向きにする
    plt.rcParams['xtick.direction'] = 'in'
    plt.rcParams['ytick.direction'] = 'in'
    
    # 2. フォント設定 (日本語対応)
    target_fonts = [
        'IPAexGothic', 'IPAGothic', 'Noto Sans CJK JP', 'Noto Sans JP', 
        'Saitamaar', 'Hiragino Maru Gothic Pro', 'Hiragino Kaku Gothic ProN', 
        'Yu Gothic', 'MS Gothic', 'Meiryo', 'TakaoGothic', 'VL PGothic', 
        'DejaVu Sans'
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
    else:
        try:
            import subprocess
            cmd = "fc-list :lang=ja family"
            output = subprocess.check_output(cmd, shell=True).decode('utf-8')
            if output:
                font_list = output.strip().split('\n')
                if font_list:
                    detected_font = font_list[0].split(',')[0]
                    plt.rcParams['font.family'] = 'sans-serif'
                    plt.rcParams['font.sans-serif'] = [detected_font]
        except:
            pass

    plt.rcParams['axes.unicode_minus'] = False

setup_style()


def read_ave_flight_status(file_path):
    data = {}
    if not os.path.exists(file_path):
        return None

    with open(file_path, 'r') as f:
        reader = csv.DictReader(f)
        for row in reader:
            metric = row['metric']
            value = float(row['value'])
            data[metric] = value

    val_real = data.get('avg_real_flight_time', 0)
    val_wait = data.get('avg_waiting_time', 0)
    val_path_wait = data.get('avg_path_wait_time', 0)
    val_staying = data.get('avg_staying_time', 0)

    # 統合: 飛行前待機時間
    pre_flight_wait = val_path_wait + val_staying

    return {
        'realFlightTime': val_real,
        'waitingTime': val_wait,
        'preFlightWait': pre_flight_wait
    }


def interactive_input():
    print("=" * 80)
    print("経路探索手法比較グラフ生成 (日本語版・特大フォント)")
    print("=" * 80)
    print()

    while True:
        try:
            num_groups = int(input("グループ数を入力してください (例: 2): "))
            if num_groups < 1: continue
            break
        except ValueError:
            pass

    print()
    groups = []

    for i in range(num_groups):
        print(f"--- グループ{i+1} ---")
        display_name = input(f"グループ{i+1}の表示名を入力してください (例: PG-EPS): ").strip()
        directory_name = input(f"グループ{i+1}のディレクトリ名を入力してください (例: Bisectional): ").strip()

        while True:
            try:
                num_elements = int(input(f"グループ{i+1}の要素数を入力してください (例: 3): "))
                if num_elements < 1: continue
                break
            except ValueError:
                pass

        elements = []
        for j in range(num_elements):
            while True:
                try:
                    lambda_val = float(input(f"  要素{j+1}のλ値を入力してください (例: 1.0): "))
                    sim_num = int(input(f"  要素{j+1}のsim番号を入力してください (例: 1): "))
                    elements.append({'lambda': lambda_val, 'sim': sim_num})
                    break
                except ValueError:
                    pass

        elements.sort(key=lambda x: x['lambda'])
        groups.append({
            'display_name': display_name,
            'directory_name': directory_name,
            'elements': elements
        })
        print()

    default_output = "output/method_comparison_jp.png"
    output_file = input(f"出力ファイル名を入力してください (デフォルト: {default_output}): ").strip()
    if not output_file:
        output_file = default_output

    return groups, output_file


def load_data(groups, base_dir="src/result"):
    all_data = []
    for group in groups:
        group_data = {
            'display_name': group['display_name'],
            'directory_name': group['directory_name'],
            'bars': []
        }
        for element in group['elements']:
            lambda_val = element['lambda']
            sim_num = element['sim']
            file_path = os.path.join(
                base_dir, f"sim_{sim_num}", "large_scale",
                group['directory_name'], "time", "ave_flightStatus.csv"
            )
            data = read_ave_flight_status(file_path)
            if data is None:
                print(f"警告: データなし ({file_path})")
                data = {'realFlightTime': 0, 'waitingTime': 0, 'preFlightWait': 0}
            else:
                print(f"✓ {group['display_name']} λ={lambda_val} 読み込み成功")

            group_data['bars'].append({'lambda': lambda_val, 'data': data})
        all_data.append(group_data)
    return all_data


def plot_stacked_bar(all_data, output_file):
    # --- 設定エリア ---
    colors = {
        'realFlightTime': '#3498db',    # 青
        'waitingTime': '#e74c3c',       # 赤
        'preFlightWait': '#2ecc71'      # 緑
    }
    labels = {
        'realFlightTime': '飛行時間',
        'waitingTime': '上空待機時間',
        'preFlightWait': '飛行前待機時間'
    }
    time_keys = ['realFlightTime', 'waitingTime', 'preFlightWait']

    # ★ フォントサイズ設定 (前回比 約1.2倍)
    FONT_SIZE_TITLE = 31     # 軸タイトル (24 -> 29)
    FONT_SIZE_TICK = 26      # 目盛り・λ値 (22 -> 26)
    FONT_SIZE_LEGEND = 26    # 凡例 (22 -> 26)
    FONT_SIZE_GROUP = 31     # グループ名 (26 -> 31)

    GROUP_GAP = 1.4
    
    # グラフ生成
    fig, ax = plt.subplots(figsize=(14, 8))

    # --- クローズドグラフ設定 ---
    for spine in ax.spines.values():
        spine.set_visible(True)
        spine.set_color('black')
        spine.set_linewidth(1.0)

    bar_width = 0.6
    bar_gap = 0.6
    
    x_positions = []
    x_labels = []
    group_info = []
    current_x = 0

    for group in all_data:
        num_bars = len(group['bars'])
        group_start = current_x

        for bar in group['bars']:
            x_positions.append(current_x)
            x_labels.append(f"λ={bar['lambda']}")
            current_x += bar_width + bar_gap

        group_center = group_start + (num_bars * (bar_width + bar_gap) - bar_gap) / 2
        group_info.append({'name': group['display_name'], 'center': group_center})

        current_x = current_x - bar_gap + GROUP_GAP

    # 描画
    all_bars = []
    for group in all_data:
        all_bars.extend(group['bars'])

    bottoms = np.zeros(len(all_bars))

    for time_key in time_keys:
        values = [bar['data'][time_key] for bar in all_bars]
        ax.bar(x_positions, values, bar_width,
               bottom=bottoms,
               label=labels[time_key],
               color=colors[time_key],
               edgecolor='black',
               linewidth=0.5)
        bottoms += values

    # X軸
    ax.set_xticks(x_positions)
    ax.set_xticklabels(x_labels, rotation=0, ha='center', fontsize=FONT_SIZE_TICK)
    ax.tick_params(axis='x', direction='in', colors='black', which='both', labelsize=FONT_SIZE_TICK) 

    # Y軸
    ax.set_ylabel('計測時間 [s]', fontsize=FONT_SIZE_TITLE, fontweight='bold', color='black')
    ax.tick_params(axis='y', direction='in', labelsize=FONT_SIZE_TICK, colors='black', which='both')

    # Y軸範囲
    max_value = max(bottoms) if len(bottoms) > 0 else 0
    y_max = int(np.ceil(max_value / 200) * 200)
    if y_max < max_value + 200:
        y_max += 200
    ax.set_ylim(0, y_max)
    ax.set_yticks(np.arange(0, y_max + 1, 200))

    # グリッド
    ax.grid(axis='y', alpha=0.3, linestyle='--', color='black')
    ax.set_axisbelow(True)

    # 凡例
    legend = ax.legend(loc='upper left', framealpha=1.0, fontsize=FONT_SIZE_LEGEND, edgecolor='black')
    for text in legend.get_texts():
        text.set_color("black")

    # グループ名
    for group in group_info:
        ax.text(group['center'], -0.13, group['name'],
                ha='center', va='top', fontsize=FONT_SIZE_GROUP, fontweight='bold', color='black',
                transform=ax.get_xaxis_transform())

    plt.tight_layout()
    plt.subplots_adjust(bottom=0.20) # 文字が大きくなった分、下部の余白を少し増やす

    # 保存
    os.makedirs(os.path.dirname(output_file) if os.path.dirname(output_file) else '.', exist_ok=True)
    plt.savefig(output_file, dpi=300, bbox_inches='tight')
    print(f"\n✓ グラフを保存しました: {output_file}")
    
    pdf_file = output_file.rsplit('.', 1)[0] + '.pdf'
    plt.savefig(pdf_file, bbox_inches='tight')
    print(f"✓ PDFを保存しました: {pdf_file}")
    plt.close()

def main():
    groups, output_file = interactive_input()
    print("\nデータ読み込み中...")
    all_data = load_data(groups)
    print("\nグラフ生成中...")
    plot_stacked_bar(all_data, output_file)
    print("\n完了")

if __name__ == "__main__":
    main()