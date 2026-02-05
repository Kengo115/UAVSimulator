#!/usr/bin/env python3
"""
トポロジファイルからネットワーク図を生成するスクリプト

Usage:
    python3 scripts/plot_topology.py <topology_file> [output_file] [--simple|--detailed] [--show-labels]

Modes:
    --detailed (default for small networks): ノードラベル、容量表示あり
    --simple (default for large networks): シンプル表示、地区タイプで色分け

Options:
    --show-labels: ノード番号を表示（simpleモード用）

Example:
    python3 scripts/plot_topology.py config/topology/default_topology.txt output/topology.png
    python3 scripts/plot_topology.py config/topology/koriyama_topology.txt output/koriyama.png --simple
    python3 scripts/plot_topology.py config/topology/koriyama_topology.txt output/koriyama.png --simple --show-labels
"""

import sys
import os
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import matplotlib.font_manager as fm
import networkx as nx

# 大規模トポロジの閾値（これ以上のノード数はシンプルモードをデフォルト）
LARGE_NETWORK_THRESHOLD = 50


def setup_japanese_font():
    """日本語フォント設定"""
    # 利用可能なフォントを探して設定する
    target_fonts = [
        'IPAexGothic', 'IPAGothic', 'Noto Sans CJK JP', 'Noto Sans JP',
        'Hiragino Maru Gothic Pro', 'Hiragino Kaku Gothic ProN',
        'Yu Gothic', 'MS Gothic', 'Meiryo', 'TakaoGothic', 'VL PGothic',
        'DejaVu Sans'  # フォールバック用
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
        # システムフォントパスから探す試み（Linux/Docker環境用）
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


# 日本語フォント設定を実行
setup_japanese_font()


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
                # 地区タイプ（オプション、デフォルト0）
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
                distance = float(parts[4])
                links.append({
                    'source': source,
                    'dest': dest,
                    'capacity': capacity,
                    'distance': distance
                })

    return nodes, links


def plot_topology_detailed(nodes, links, output_path, title=None):
    """詳細モード: ノードラベル、容量表示あり（小規模向け）"""

    G = nx.Graph()

    for node_id, data in nodes.items():
        G.add_node(node_id, pos=(data['x'], data['y']))

    for link in links:
        G.add_edge(link['source'], link['dest'],
                   capacity=link['capacity'],
                   distance=link['distance'])

    fig, ax = plt.subplots(1, 1, figsize=(10, 8))

    pos = nx.get_node_attributes(G, 'pos')

    # リンク描画（容量で太さ変更）
    edges = G.edges(data=True)
    capacities = [d['capacity'] for _, _, d in edges]
    max_cap = max(capacities) if capacities else 1
    min_cap = min(capacities) if capacities else 1

    if max_cap > min_cap:
        widths = [1 + 4 * (d['capacity'] - min_cap) / (max_cap - min_cap) for _, _, d in edges]
    else:
        widths = [2.5 for _ in edges]

    nx.draw_networkx_edges(G, pos, ax=ax,
                           width=widths,
                           edge_color='#4a90d9',
                           alpha=0.7)

    nx.draw_networkx_nodes(G, pos, ax=ax,
                           node_size=800,
                           node_color='#ff6b6b',
                           edgecolors='#333333',
                           linewidths=2)

    nx.draw_networkx_labels(G, pos, ax=ax,
                            font_size=14,
                            font_weight='bold',
                            font_color='white')

    edge_labels = {(u, v): f"c={int(d['capacity'])}" for u, v, d in G.edges(data=True)}
    nx.draw_networkx_edge_labels(G, pos, edge_labels=edge_labels, ax=ax,
                                  font_size=9,
                                  font_color='#333333')

    if title:
        ax.set_title(title, fontsize=14, fontweight='bold')
    else:
        ax.set_title(f'Network Topology ({len(nodes)} nodes, {len(links)} links)',
                     fontsize=14, fontweight='bold')

    ax.set_xlim(-0.05, 1.05)
    ax.set_ylim(-0.05, 1.05)
    ax.set_aspect('equal')
    ax.grid(True, alpha=0.3)
    ax.set_xlabel('X coordinate')
    ax.set_ylabel('Y coordinate')

    legend_elements = [
        mpatches.Patch(color='#ff6b6b', label='Node'),
        mpatches.Patch(color='#4a90d9', label='Link (width = capacity)')
    ]
    ax.legend(handles=legend_elements, loc='upper right')

    plt.tight_layout()
    plt.savefig(output_path, dpi=150, bbox_inches='tight')
    plt.close()

    return output_path


def plot_topology_simple(nodes, links, output_path, title=None, show_labels=False):
    """シンプルモード: 大規模ネットワーク向け、地区タイプで色分け"""

    G = nx.Graph()

    for node_id, data in nodes.items():
        G.add_node(node_id, pos=(data['x'], data['y']), district_type=data['district_type'])

    for link in links:
        G.add_edge(link['source'], link['dest'])

    # ノード番号表示時は大きめのサイズ
    if show_labels:
        fig, ax = plt.subplots(1, 1, figsize=(20, 16))
    else:
        fig, ax = plt.subplots(1, 1, figsize=(12, 10))

    pos = nx.get_node_attributes(G, 'pos')

    # リンク描画（細い灰色線）
    nx.draw_networkx_edges(G, pos, ax=ax,
                           width=0.5,
                           edge_color='#888888',
                           alpha=0.5)

    # ノードを地区タイプで分類
    scattered_nodes = [n for n, d in G.nodes(data=True) if d.get('district_type', 0) == 0]
    concentrated_nodes = [n for n, d in G.nodes(data=True) if d.get('district_type', 0) == 1]

    # ノードサイズ（少し大きめに設定）
    node_size = 120 if show_labels else 50

    # 点在地区（青）
    if scattered_nodes:
        nx.draw_networkx_nodes(G, pos, ax=ax,
                               nodelist=scattered_nodes,
                               node_size=node_size,
                               node_color='#2196F3',
                               edgecolors='#1565C0',
                               linewidths=0.5)

    # 集中地区（赤）
    if concentrated_nodes:
        nx.draw_networkx_nodes(G, pos, ax=ax,
                               nodelist=concentrated_nodes,
                               node_size=node_size,
                               node_color='#F44336',
                               edgecolors='#C62828',
                               linewidths=0.5)

    # ノード番号を表示
    if show_labels:
        # ノードの少し上にラベルを表示
        label_pos = {node: (x, y + 0.008) for node, (x, y) in pos.items()}
        nx.draw_networkx_labels(G, label_pos, ax=ax,
                                font_size=6,
                                font_color='#333333')

    # タイトルなし（削除）

    # 上部に少し余白を設けて凡例がノードと重ならないようにする
    ax.set_xlim(-0.02, 1.02)
    ax.set_ylim(-0.02, 1.08)  # 上部に少し余白を追加
    ax.set_aspect('equal')
    ax.grid(True, alpha=0.3)
    # 軸ラベルなし（削除）
    ax.set_xticklabels([])
    ax.set_yticklabels([])
    ax.tick_params(left=False, bottom=False)

    # 凡例（日本語、文字サイズ16、位置を少し下に）
    legend_elements = [
        mpatches.Patch(color='#2196F3', label='点在地区ノード'),
        mpatches.Patch(color='#F44336', label='集中地区ノード'),
    ]
    ax.legend(handles=legend_elements, loc='upper right', fontsize=16,
              framealpha=1.0, edgecolor='black', bbox_to_anchor=(1.0, 0.98))

    plt.tight_layout()
    plt.savefig(output_path, dpi=150, bbox_inches='tight')
    plt.close()

    return output_path


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 plot_topology.py <topology_file> [output_file] [--simple|--detailed] [--show-labels]")
        sys.exit(1)

    topology_file = sys.argv[1]

    # 出力ファイル名を決定
    output_file = None
    mode = None
    show_labels = False

    for arg in sys.argv[2:]:
        if arg == '--simple':
            mode = 'simple'
        elif arg == '--detailed':
            mode = 'detailed'
        elif arg == '--show-labels':
            show_labels = True
        elif not output_file:
            output_file = arg

    if not output_file:
        basename = os.path.splitext(os.path.basename(topology_file))[0]
        output_file = f"output/topology_{basename}.png"

    # 出力ディレクトリ作成
    output_dir = os.path.dirname(output_file)
    if output_dir and not os.path.exists(output_dir):
        os.makedirs(output_dir)

    # トポロジ解析
    print(f"Reading topology file: {topology_file}")
    nodes, links = parse_topology_file(topology_file)
    print(f"  Nodes: {len(nodes)}")
    print(f"  Links: {len(links)}")

    # モード自動判定
    if mode is None:
        if len(nodes) > LARGE_NETWORK_THRESHOLD:
            mode = 'simple'
            print(f"  Auto-selected mode: simple (nodes > {LARGE_NETWORK_THRESHOLD})")
        else:
            mode = 'detailed'
            print(f"  Auto-selected mode: detailed (nodes <= {LARGE_NETWORK_THRESHOLD})")
    else:
        print(f"  Mode: {mode}")

    # 描画
    print(f"Generating topology image...")
    if show_labels:
        print(f"  Show labels: enabled")
    title = os.path.basename(topology_file)

    if mode == 'simple':
        plot_topology_simple(nodes, links, output_file, title=title, show_labels=show_labels)
    else:
        plot_topology_detailed(nodes, links, output_file, title=title)

    print(f"Output saved to: {output_file}")


if __name__ == '__main__':
    main()
