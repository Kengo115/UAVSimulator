#!/usr/bin/env python3
"""
郡山トポロジデータをUAVSimulator形式に変換するスクリプト

入力: config/郡山駅周辺モデル座標とドロネーリンク情報.txt
出力: config/topology/koriyama_topology.txt

変換ルール:
- IPアドレス 10.1.0.X → ノードID X-1 (0始まり)
- 座標: [0,1]に正規化
- 容量: 点在地区(0)=4, 集中地区(1)=2, 混在リンク=min
- lTubeLength: 最小距離を1として相対値
- distance: lTubeLength × 250 (m)
"""

import sys
import math


def parse_ip_to_id(ip_str):
    """IPアドレスをノードIDに変換 (10.1.0.X → X-1)"""
    parts = ip_str.strip().split('.')
    return int(parts[3]) - 1


def calculate_distance(x1, y1, x2, y2):
    """2点間のユークリッド距離を計算"""
    return math.sqrt((x2 - x1) ** 2 + (y2 - y1) ** 2)


def convert_topology(input_path, output_path):
    nodes = {}  # id -> (x, y, district_type)
    links = []  # [(source_id, dest_id), ...]

    # ファイル読み込み
    with open(input_path, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    # ノード情報とリンク情報を分離
    node_lines = []
    link_lines = []

    for line in lines:
        line = line.strip()
        if not line:
            continue

        parts = line.split(',')
        if len(parts) == 4:
            # ノード情報: IP,X,Y,district_type
            node_lines.append(parts)
        elif len(parts) == 2:
            # リンク情報: IP1,IP2
            link_lines.append(parts)

    print(f"ノード数: {len(node_lines)}")
    print(f"リンク数: {len(link_lines)}")

    # ノード情報をパース
    min_x, max_x = float('inf'), float('-inf')
    min_y, max_y = float('inf'), float('-inf')

    for parts in node_lines:
        ip = parts[0]
        x = float(parts[1])
        y = float(parts[2])
        district_type = int(parts[3])

        node_id = parse_ip_to_id(ip)
        nodes[node_id] = {
            'x': x,
            'y': y,
            'district_type': district_type,
            'capacity': 4 if district_type == 0 else 2
        }

        min_x = min(min_x, x)
        max_x = max(max_x, x)
        min_y = min(min_y, y)
        max_y = max(max_y, y)

    print(f"座標範囲: X=[{min_x}, {max_x}], Y=[{min_y}, {max_y}]")

    # 座標を[0,1]に正規化
    x_range = max_x - min_x
    y_range = max_y - min_y

    for node_id, data in nodes.items():
        data['x_norm'] = (data['x'] - min_x) / x_range
        data['y_norm'] = (data['y'] - min_y) / y_range

    # リンク情報をパース
    link_set = set()  # 重複排除用

    for parts in link_lines:
        source_id = parse_ip_to_id(parts[0])
        dest_id = parse_ip_to_id(parts[1])

        # ソート済みタプルで重複チェック（双方向リンクを1つに）
        link_key = tuple(sorted([source_id, dest_id]))
        if link_key not in link_set:
            link_set.add(link_key)
            links.append((source_id, dest_id))

    print(f"重複排除後リンク数: {len(links)}")

    # 各リンクの距離を計算
    link_distances = []
    for source_id, dest_id in links:
        src = nodes[source_id]
        dst = nodes[dest_id]
        dist = calculate_distance(src['x'], src['y'], dst['x'], dst['y'])
        link_distances.append(dist)

    # 最小距離を基準にlTubeLengthを計算
    min_dist = min(link_distances)
    print(f"最小距離(ピクセル): {min_dist}")

    # 出力ファイル生成
    with open(output_path, 'w', encoding='utf-8') as f:
        f.write(f"# 郡山駅周辺ネットワークトポロジ\n")
        f.write(f"# 変換元: 郡山駅周辺モデル座標とドロネーリンク情報.txt\n")
        f.write(f"# ノード数: {len(nodes)}, リンク数: {len(links)}\n")
        f.write(f"# 点在地区(type=0): 容量4, 集中地区(type=1): 容量2\n")
        f.write(f"# lTubeLength: 最小距離=1基準の相対値\n")
        f.write(f"# distance: lTubeLength × 250 (m)\n")
        f.write(f"#\n")

        # ノード数
        f.write(f"NODES {len(nodes)}\n\n")

        # ノード情報（IDでソート）
        # district_type: 0=点在地区, 1=集中地区
        f.write(f"# NODE id x y district_type\n")
        for node_id in sorted(nodes.keys()):
            data = nodes[node_id]
            f.write(f"NODE {node_id} {data['x_norm']:.6f} {data['y_norm']:.6f} {data['district_type']}\n")

        f.write(f"\n")

        # リンク情報
        f.write(f"# LINK source dest capacity distance lTubeLength\n")

        # 統計用
        capacity_stats = {2: 0, 4: 0}

        for i, (source_id, dest_id) in enumerate(links):
            src = nodes[source_id]
            dst = nodes[dest_id]

            # 容量は両端の小さい方
            capacity = min(src['capacity'], dst['capacity'])
            capacity_stats[capacity] = capacity_stats.get(capacity, 0) + 1

            # lTubeLength: 最小距離=1基準
            l_tube_length = link_distances[i] / min_dist

            # distance: lTubeLength × 250 (m)
            distance = l_tube_length * 250

            f.write(f"LINK {source_id} {dest_id} {capacity} {distance:.1f} {l_tube_length:.4f}\n")

        print(f"\n容量別リンク数:")
        print(f"  容量2 (集中地区含む): {capacity_stats.get(2, 0)}")
        print(f"  容量4 (点在地区のみ): {capacity_stats.get(4, 0)}")

    print(f"\n出力ファイル: {output_path}")
    return len(nodes), len(links)


def main():
    input_path = "config/郡山駅周辺モデル座標とドロネーリンク情報.txt"
    output_path = "config/topology/koriyama_topology.txt"

    if len(sys.argv) >= 2:
        input_path = sys.argv[1]
    if len(sys.argv) >= 3:
        output_path = sys.argv[2]

    print(f"入力ファイル: {input_path}")
    print(f"出力ファイル: {output_path}")
    print()

    convert_topology(input_path, output_path)


if __name__ == '__main__':
    main()
