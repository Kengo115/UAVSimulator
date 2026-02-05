#!/usr/bin/env python3
"""
Phase 7-6: 混雑ヒートマップ可視化スクリプト

スナップショットデータを使ってネットワーク上の混雑状況をヒートマップで可視化する。

Usage:
    python3 scripts/plot_congestion_heatmap.py <topology_file> <snapshot_file> [output_file]
    python3 scripts/plot_congestion_heatmap.py <topology_file> <snapshot_dir> [output_dir] --all

Examples:
    # 単一スナップショットを可視化
    python3 scripts/plot_congestion_heatmap.py config/topology/koriyama_topology.txt \\
        src/result/large_scale/Bisectional/snapshot/snapshot_10000.csv output/heatmap.png

    # 全スナップショットを可視化
    python3 scripts/plot_congestion_heatmap.py config/topology/koriyama_topology.txt \\
        src/result/large_scale/Bisectional/snapshot/ output/heatmaps/ --all
"""

import sys
import os
import glob
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import matplotlib.colors as mcolors
import networkx as nx
import pandas as pd
import numpy as np


def parse_topology_file(filepath):
    """トポロジファイルを解析してノードとリンク情報を取得"""
    nodes = {}
    links = []

    with open(filepath, 'r') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue

            parts = line.split()
            if len(parts) == 0:
                continue

            keyword = parts[0]

            if keyword == 'NODE':
                node_id = int(parts[1])
                x = float(parts[2])
                y = float(parts[3])
                district_type = int(parts[4]) if len(parts) >= 5 else 0
                nodes[node_id] = {
                    'x': x,
                    'y': y,
                    'district_type': district_type
                }

            elif keyword == 'LINK' and len(parts) >= 6:
                source = int(parts[1])
                dest = int(parts[2])
                capacity = float(parts[3])
                links.append({
                    'source': source,
                    'dest': dest,
                    'capacity': capacity
                })

    return nodes, links


def parse_snapshot_file(filepath):
    """スナップショットCSVを解析してリンク負荷率を取得"""
    df = pd.read_csv(filepath)

    # リンクごとの負荷率を辞書に変換
    link_load = {}
    for _, row in df.iterrows():
        # カラム名の互換性対応（node_a/node_b または link_from/link_to）
        if 'node_a' in df.columns:
            from_node = int(row['node_a'])
            to_node = int(row['node_b'])
        else:
            from_node = int(row['link_from'])
            to_node = int(row['link_to'])

        # 容量カラムの互換性対応
        if 'current_capacity' in df.columns:
            capacity = float(row['current_capacity'])
        else:
            capacity = float(row['capacity'])

        key = (from_node, to_node)
        link_load[key] = {
            'flying': int(row['flying_count']),
            'waiting': int(row['waiting_count']),
            'capacity': capacity,
            'load_rate': float(row['load_rate'])
        }

    return link_load


def get_load_color(load_rate):
    """負荷率に応じた色を返す
    0%: 色なし（グレー）
    0-25%: 薄青色
    25-50%: 緑色
    50-75%: オレンジ色
    75-100%: 赤色
    100%以上: 深い赤紫色
    """
    if load_rate == 0:
        # 0%は色なし（薄いグレー）
        return (0.85, 0.85, 0.85)
    elif load_rate <= 25:
        # より薄い青色
        return (0.6, 0.75, 1.0)
    elif load_rate <= 50:
        # 緑色
        return (0.0, 0.8, 0.0)
    elif load_rate <= 75:
        # オレンジ色
        return (1.0, 0.6, 0.0)
    elif load_rate <= 100:
        # 赤色
        return (1.0, 0.0, 0.0)
    else:
        # 深い赤紫色（100%以上）黒を少し入れる
        return (0.45, 0.0, 0.3)


def plot_congestion_heatmap(nodes, links, link_load, output_path, title=None):
    """混雑ヒートマップを描画"""

    G = nx.DiGraph()  # 有向グラフ（リンクの方向を表現）

    # ノード追加
    for node_id, data in nodes.items():
        G.add_node(node_id, pos=(data['x'], data['y']), district_type=data['district_type'])

    # リンク追加（負荷率情報付き）
    for link in links:
        src, dst = link['source'], link['dest']
        key = (src, dst)

        if key in link_load:
            load_data = link_load[key]
            G.add_edge(src, dst,
                       load_rate=load_data['load_rate'],
                       flying=load_data['flying'],
                       waiting=load_data['waiting'],
                       capacity=load_data['capacity'])
        else:
            # スナップショットにないリンクは負荷率0
            G.add_edge(src, dst, load_rate=0, flying=0, waiting=0, capacity=link['capacity'])

    fig, ax = plt.subplots(1, 1, figsize=(14, 11))

    pos = nx.get_node_attributes(G, 'pos')

    # リンクを負荷率で色分けして描画
    edge_colors = []
    edge_widths = []

    for u, v, data in G.edges(data=True):
        load_rate = data.get('load_rate', 0)
        edge_colors.append(get_load_color(load_rate))

        # 線の太さ:
        # - 0%: とても細い線
        # - 0-25%: 薄い線
        # - 25-75%: 中程度
        # - 75-100%: 太い線
        # - 100%超: 太さで差を表現（赤紫色は同じなので）
        if load_rate == 0:
            edge_widths.append(0.3)
        elif load_rate <= 25:
            edge_widths.append(0.6)
        elif load_rate <= 75:
            edge_widths.append(1.0)
        elif load_rate <= 100:
            edge_widths.append(1.5)
        else:
            # 100%を超えた分に応じて太くする（控えめに）
            edge_widths.append(1.5 + (load_rate - 100) / 300 * 1.0)

    # リンク描画（直線、矢印なし）
    edges = list(G.edges())
    nx.draw_networkx_edges(G, pos, ax=ax,
                           edgelist=edges,
                           width=edge_widths,
                           edge_color=edge_colors,
                           alpha=0.8,
                           arrows=False)

    # ノード描画（地区タイプで色を変える）
    scattered_nodes = [n for n, d in G.nodes(data=True) if d.get('district_type', 0) == 0]
    concentrated_nodes = [n for n, d in G.nodes(data=True) if d.get('district_type', 0) == 1]

    # 点在地区（青色・丸）
    if scattered_nodes:
        nx.draw_networkx_nodes(G, pos, ax=ax,
                               nodelist=scattered_nodes,
                               node_size=40,
                               node_color='#3366FF',
                               edgecolors='white',
                               linewidths=0.5)

    # 集中地区（赤色・四角）
    if concentrated_nodes:
        nx.draw_networkx_nodes(G, pos, ax=ax,
                               nodelist=concentrated_nodes,
                               node_size=40,
                               node_color='#FF3333',
                               node_shape='s',
                               edgecolors='white',
                               linewidths=0.5)

    # タイトル
    if title:
        ax.set_title(title, fontsize=14, fontweight='bold')
    else:
        ax.set_title('Congestion Heatmap', fontsize=14, fontweight='bold')

    ax.set_xlim(-0.02, 1.02)
    ax.set_ylim(-0.02, 1.02)
    ax.set_aspect('equal')
    ax.grid(True, alpha=0.3)
    ax.set_xlabel('X coordinate')
    ax.set_ylabel('Y coordinate')

    # カラーバー用のカラーマップを作成（段階的な色）
    cmap_colors = [
        (0.6, 0.75, 1.0),  # 0-25%: 薄青
        (0.6, 0.75, 1.0),  #
        (0.0, 0.8, 0.0),   # 25-50%: 緑
        (0.0, 0.8, 0.0),   #
        (1.0, 0.6, 0.0),   # 50-75%: オレンジ
        (1.0, 0.6, 0.0),   #
        (1.0, 0.0, 0.0),   # 75-100%: 赤
        (1.0, 0.0, 0.0),   #
        (0.45, 0.0, 0.3),  # 100%+: 深い赤紫（黒入り）
    ]
    cmap = mcolors.LinearSegmentedColormap.from_list('load_rate', cmap_colors, N=256)
    norm = mcolors.Normalize(vmin=0, vmax=125)
    sm = plt.cm.ScalarMappable(cmap=cmap, norm=norm)
    sm.set_array([])
    cbar = plt.colorbar(sm, ax=ax, shrink=0.6, pad=0.02)
    cbar.set_label('Load Rate (%)', fontsize=10)
    cbar.set_ticks([0, 25, 50, 75, 100, 125])

    # 統計情報
    if link_load:
        load_rates = [d['load_rate'] for d in link_load.values()]
        avg_load = np.mean(load_rates)
        max_load = np.max(load_rates)
        congested = sum(1 for r in load_rates if r >= 80)
        congested_rate = congested / len(load_rates) * 100

        stats_text = f"Avg Load: {avg_load:.1f}%\nMax Load: {max_load:.1f}%\nCongested Links (>=80%): {congested} ({congested_rate:.1f}%)"
        ax.text(0.02, 0.98, stats_text, transform=ax.transAxes, fontsize=9,
                verticalalignment='top', bbox=dict(boxstyle='round', facecolor='white', alpha=0.8))

    # 凡例
    legend_elements = [
        mpatches.Patch(color=(0.6, 0.75, 1.0), label='0-25%'),
        mpatches.Patch(color=(0.0, 0.8, 0.0), label='25-50%'),
        mpatches.Patch(color=(1.0, 0.6, 0.0), label='50-75%'),
        mpatches.Patch(color=(1.0, 0.0, 0.0), label='75-100%'),
        mpatches.Patch(color=(0.45, 0.0, 0.3), label='>100% (width=load)'),
    ]
    ax.legend(handles=legend_elements, loc='lower right', fontsize=8)

    plt.tight_layout()
    plt.savefig(output_path, dpi=150, bbox_inches='tight')
    plt.close()

    return output_path


def extract_timestamp(filename):
    """ファイル名からタイムスタンプを抽出"""
    basename = os.path.basename(filename)
    # snapshot_10000.csv -> 10000
    try:
        return int(basename.replace('snapshot_', '').replace('.csv', ''))
    except ValueError:
        return 0


def load_phase_transitions(result_dir):
    """フェーズ遷移CSVを読み込む

    Args:
        result_dir: 結果ディレクトリ（例: src/result/large_scale/Bisectional）

    Returns:
        フェーズ遷移のリスト [(timestamp, phase), ...]
    """
    # result_dirからphase_transitions.csvを探す
    # snapshot_dir の親ディレクトリを探す
    if result_dir.endswith('/snapshot') or result_dir.endswith('/snapshot/'):
        parent_dir = os.path.dirname(result_dir.rstrip('/'))
    else:
        parent_dir = result_dir

    phase_file = os.path.join(parent_dir, 'phase_transitions.csv')

    if not os.path.exists(phase_file):
        print(f"  Warning: phase_transitions.csv not found: {phase_file}")
        return []

    transitions = []
    try:
        df = pd.read_csv(phase_file)
        for _, row in df.iterrows():
            transitions.append((int(row['timestamp']), int(row['phase'])))
        print(f"  Loaded {len(transitions)} phase transitions from {phase_file}")
    except Exception as e:
        print(f"  Warning: Failed to read phase_transitions.csv: {e}")

    return transitions


def get_phase_for_timestamp(timestamp, transitions):
    """タイムスタンプに対応するフェーズを取得

    Args:
        timestamp: スナップショットのタイムスタンプ（ミリ秒）
        transitions: フェーズ遷移リスト [(timestamp, phase), ...]

    Returns:
        フェーズ番号（1-4）、遷移情報がない場合は1
    """
    if not transitions:
        return 1

    current_phase = 1
    for trans_time, phase in transitions:
        if timestamp >= trans_time:
            current_phase = phase
        else:
            break

    return current_phase


def main():
    if len(sys.argv) < 3:
        print("Usage: python3 plot_congestion_heatmap.py <topology_file> <snapshot_file> [output_file]")
        print("       python3 plot_congestion_heatmap.py <topology_file> <snapshot_dir> [output_dir] --all")
        sys.exit(1)

    topology_file = sys.argv[1]
    snapshot_path = sys.argv[2]

    # --all オプションチェック
    process_all = '--all' in sys.argv

    # 出力パス決定
    output_path = None
    for arg in sys.argv[3:]:
        if arg != '--all':
            output_path = arg
            break

    # トポロジ解析
    print(f"Reading topology file: {topology_file}")
    nodes, links = parse_topology_file(topology_file)
    print(f"  Nodes: {len(nodes)}")
    print(f"  Links: {len(links)}")

    if process_all and os.path.isdir(snapshot_path):
        # 全スナップショット処理
        snapshot_files = sorted(glob.glob(os.path.join(snapshot_path, 'snapshot_*.csv')),
                               key=extract_timestamp)

        if not snapshot_files:
            print(f"No snapshot files found in: {snapshot_path}")
            sys.exit(1)

        # 出力ディレクトリ
        if output_path is None:
            output_path = 'output/heatmaps'

        if not os.path.exists(output_path):
            os.makedirs(output_path)

        # フェーズ遷移情報を読み込む
        transitions = load_phase_transitions(snapshot_path)

        print(f"Processing {len(snapshot_files)} snapshots...")

        for i, snapshot_file in enumerate(snapshot_files):
            timestamp = extract_timestamp(snapshot_file)
            phase = get_phase_for_timestamp(timestamp, transitions)
            output_file = os.path.join(output_path, f'phase{phase}_heatmap_{timestamp}.png')

            link_load = parse_snapshot_file(snapshot_file)
            title = f'Phase {phase} - Congestion Heatmap (t={timestamp}ms)'

            plot_congestion_heatmap(nodes, links, link_load, output_file, title=title)
            print(f"  [{i+1}/{len(snapshot_files)}] {output_file}")

        print(f"All heatmaps saved to: {output_path}")

    else:
        # 単一スナップショット処理
        if not os.path.isfile(snapshot_path):
            print(f"Snapshot file not found: {snapshot_path}")
            sys.exit(1)

        # フェーズ遷移情報を読み込む
        snapshot_dir = os.path.dirname(snapshot_path)
        transitions = load_phase_transitions(snapshot_dir)

        timestamp = extract_timestamp(snapshot_path)
        phase = get_phase_for_timestamp(timestamp, transitions)

        # 出力ファイル名決定
        if output_path is None:
            output_path = f'output/phase{phase}_heatmap_{timestamp}.png'
        elif output_path.endswith('/') or os.path.isdir(output_path):
            # ディレクトリが指定された場合
            output_dir = output_path.rstrip('/')
            output_path = os.path.join(output_dir, f'phase{phase}_heatmap_{timestamp}.png')
        else:
            # ファイルパスが指定された場合、フェーズを含めた名前に変更
            output_dir = os.path.dirname(output_path)
            output_path = os.path.join(output_dir, f'phase{phase}_heatmap_{timestamp}.png')

        # 出力ディレクトリ作成
        output_dir = os.path.dirname(output_path)
        if output_dir and not os.path.exists(output_dir):
            os.makedirs(output_dir)

        # スナップショット解析
        print(f"Reading snapshot file: {snapshot_path}")
        link_load = parse_snapshot_file(snapshot_path)
        print(f"  Links with data: {len(link_load)}")

        if link_load:
            load_rates = [d['load_rate'] for d in link_load.values()]
            print(f"  Avg load rate: {np.mean(load_rates):.1f}%")
            print(f"  Max load rate: {np.max(load_rates):.1f}%")

        # 描画
        print(f"Generating heatmap...")
        title = f'Phase {phase} - Congestion Heatmap (t={timestamp}ms)'

        plot_congestion_heatmap(nodes, links, link_load, output_path, title=title)
        print(f"Output saved to: {output_path}")


if __name__ == '__main__':
    main()
