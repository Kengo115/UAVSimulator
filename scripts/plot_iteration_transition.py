#!/usr/bin/env python3
"""
圧力とフローのイテレーション遷移グラフを生成するスクリプト（対話形式）

Usage:
    python plot_iteration_transition.py
"""

import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
import numpy as np
import os
import sys

# 日本語フォント設定（Noto Sans CJK JP）
plt.rcParams['font.family'] = 'Noto Sans CJK JP'


def ask(prompt, example=None):
    """入力プロンプト（例を表示）"""
    if example:
        print(f"  例: {example}")
    return input(prompt).strip()


def ask_optional_float(prompt):
    """空欄を許容するfloat入力（空欄はNoneを返す）"""
    val = input(prompt).strip()
    if val == "":
        return None
    try:
        return float(val)
    except ValueError:
        print(f"  ※ 数値として解釈できません。自動スケーリングを使用します。")
        return None


def main():
    print("=" * 60)
    print("  イテレーション遷移グラフ生成")
    print("=" * 60)
    print()

    # --- ファイルパス入力 ---
    print("【入力ファイル】")
    while True:
        flow_file = ask(
            "フロー遷移ファイルのパス: ",
            "src/result/mode1-1_G1/small_scale/Bisectional/iteration/flow/client1/iteration_flow.txt"
        )
        if os.path.exists(flow_file):
            break
        print(f"  Error: ファイルが見つかりません: {flow_file}")

    while True:
        pressure_file = ask(
            "圧力遷移ファイルのパス: ",
            "src/result/mode1-1_G1/small_scale/Bisectional/iteration/sourcePressure/client1/iteration_source_pressure.txt"
        )
        if os.path.exists(pressure_file):
            break
        print(f"  Error: ファイルが見つかりません: {pressure_file}")

    print()

    # --- 軸スケール入力 ---
    print("【軸スケール設定（空欄で自動スケーリング）】")
    x_max           = ask_optional_float("X軸 最大値         : ")
    x_interval      = ask_optional_float("X軸 間隔           : ")
    print()
    y_max_flow      = ask_optional_float("フロー Y軸 最大値  : ")
    y_interval_flow = ask_optional_float("フロー Y軸 間隔    : ")
    print()
    y_max_pressure  = ask_optional_float("圧力 Y軸 最大値    : ")
    y_interval_pres = ask_optional_float("圧力 Y軸 間隔      : ")

    print()
    print("グラフを生成中...")

    # --- データ読み込み ---
    df_flow = pd.read_csv(flow_file)
    df_pres = pd.read_csv(pressure_file)

    # クライアントIDをflowファイルのディレクトリ名から取得
    client_id = os.path.basename(os.path.dirname(flow_file))

    # 出力先: flowファイルから3階層上（iteration/flow/clientX/ → iteration/）
    output_dir = os.path.dirname(os.path.dirname(os.path.dirname(flow_file)))

    # --- グラフ作成（二軸グラフ） ---
    fig, ax1 = plt.subplots(figsize=(14, 8))

    # クローズドグラフ設定
    for spine in ax1.spines.values():
        spine.set_visible(True)
        spine.set_color('black')
        spine.set_linewidth(1.0)

    # 左軸: フロー
    color_flow = 'blue'
    ax1.set_xlabel('繰り返し回数', fontsize=28, fontweight='bold', color='black')
    ax1.set_ylabel('フロー値', fontsize=28, fontweight='bold', color='black')
    line1, = ax1.plot(df_flow['iteration'], df_flow['flow'],
                      label='フロー値', linewidth=2.5, color=color_flow)
    ax1.tick_params(axis='y', labelsize=23, direction='in', colors='black')
    ax1.tick_params(axis='x', labelsize=23, direction='in', colors='black')

    # X軸スケーリング
    x_data_max = max(df_flow['iteration'].max(), df_pres['iteration'].max())
    if x_max is not None:
        ax1.set_xlim(0, x_max)
        if x_interval is not None:
            ax1.set_xticks(np.arange(0, x_max + 1, x_interval))
    else:
        ax1.set_xlim(0, x_data_max)
        if x_interval is not None:
            x_max_auto = np.ceil(x_data_max / x_interval) * x_interval
            ax1.set_xlim(0, x_max_auto)
            ax1.set_xticks(np.arange(0, x_max_auto + 1, x_interval))

    # 左軸（フロー）のY軸スケーリング
    if y_max_flow is not None:
        ax1.set_ylim(0, y_max_flow)
        if y_interval_flow is not None:
            ax1.set_yticks(np.arange(0, y_max_flow + 1, y_interval_flow))
    else:
        flow_data_max = df_flow['flow'].max()
        ax1.set_ylim(bottom=0)
        if y_interval_flow is not None:
            y_max_flow_auto = np.ceil(flow_data_max / y_interval_flow) * y_interval_flow
            ax1.set_ylim(0, y_max_flow_auto)
            ax1.set_yticks(np.arange(0, y_max_flow_auto + 1, y_interval_flow))

    # 右軸: 圧力
    ax2 = ax1.twinx()
    color_pres = 'darkorange'
    ax2.set_ylabel('ソースノードの圧力値', fontsize=28, fontweight='bold', color='black')
    line2, = ax2.plot(df_pres['iteration'], df_pres['sourcePressure'],
                      label='ソースノードの圧力値', linewidth=2.5,
                      color=color_pres, marker='o', markersize=4)
    ax2.tick_params(axis='y', labelsize=23, direction='in', colors='black')

    # 右軸スケーリング
    if y_max_pressure is not None:
        ax2.set_ylim(0, y_max_pressure)
        if y_interval_pres is not None:
            ax2.set_yticks(np.arange(0, y_max_pressure + 1, y_interval_pres))
    else:
        pres_data_max = df_pres['sourcePressure'].max()
        ax2.set_ylim(bottom=0)
        if y_interval_pres is not None:
            y_max_pres_auto = np.ceil(pres_data_max / y_interval_pres) * y_interval_pres
            ax2.set_ylim(0, y_max_pres_auto)
            ax2.set_yticks(np.arange(0, y_max_pres_auto + 1, y_interval_pres))

    # 右軸の枠線設定
    for spine in ax2.spines.values():
        spine.set_visible(True)
        spine.set_color('black')
        spine.set_linewidth(1.0)

    # グリッド（左軸基準）
    ax1.grid(True, alpha=0.3, linestyle='--', color='black')

    # 凡例（両軸のラインをまとめて表示）
    lines = [line1, line2]
    labels = [l.get_label() for l in lines]
    ax1.legend(lines, labels, loc='best', fontsize=18)

    plt.tight_layout()

    # 出力ディレクトリを作成
    os.makedirs(output_dir, exist_ok=True)

    # 出力
    output_path = os.path.join(output_dir, f"iteration_transition_{client_id}.png")
    plt.savefig(output_path, dpi=150, bbox_inches='tight')
    print(f"Graph saved to: {output_path}")

    plt.close()


if __name__ == "__main__":
    main()
