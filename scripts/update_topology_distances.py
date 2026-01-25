#!/usr/bin/env python3
"""
トポロジファイルのリンク距離をユークリッド距離に更新するスクリプト

- ソースファイルの座標からユークリッド距離を計算
- lTubeLengthを最小距離基準で正規化
"""

import math
import re

def main():
    source_file = "config/topology/郡山駅周辺モデル座標とドロネーリンク情報.txt"
    topology_file = "config/topology/koriyama_topology.txt"
    output_file = "config/topology/koriyama_topology.txt"

    # ソースファイルからノード座標を読み取る
    node_coords = {}
    with open(source_file, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parts = line.split(',')
            if len(parts) >= 4 and parts[0].startswith('10.1.0.'):
                # 10.1.0.N -> N-1 (0-indexed)
                node_id = int(parts[0].split('.')[-1]) - 1
                x = float(parts[1])
                y = float(parts[2])
                node_coords[node_id] = (x, y)

    print(f"ノード座標を読み込みました: {len(node_coords)} ノード")

    # 現在のトポロジファイルを読み取る
    with open(topology_file, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    # リンク情報を更新
    new_lines = []
    link_distances = []
    link_info = []

    for line in lines:
        stripped = line.strip()
        if stripped.startswith('LINK '):
            parts = stripped.split()
            if len(parts) >= 6:
                src = int(parts[1])
                dst = int(parts[2])
                capacity = int(parts[3])

                # ユークリッド距離を計算
                if src in node_coords and dst in node_coords:
                    x1, y1 = node_coords[src]
                    x2, y2 = node_coords[dst]
                    distance = math.sqrt((x2 - x1)**2 + (y2 - y1)**2)
                    link_distances.append(distance)
                    link_info.append((src, dst, capacity, distance, line))
                else:
                    print(f"警告: ノード座標が見つかりません: {src} or {dst}")
                    new_lines.append(line)
        else:
            new_lines.append(line)

    # 最小距離を見つける
    min_distance = min(link_distances)
    print(f"最小距離: {min_distance:.1f}m")
    print(f"最大距離: {max(link_distances):.1f}m")

    # リンク情報を更新（正規化したlTubeLengthで）
    for src, dst, capacity, distance, original_line in link_info:
        lTubeLength = distance / min_distance
        new_line = f"LINK {src} {dst} {capacity} {distance:.1f} {lTubeLength:.4f}\n"
        new_lines.append(new_line)

    # ヘッダーコメントを更新
    updated_lines = []
    for line in new_lines:
        if line.startswith('# lTubeLength:'):
            updated_lines.append(f'# lTubeLength: 最小距離({min_distance:.1f}m)基準の相対値\n')
        elif line.startswith('# distance:'):
            updated_lines.append('# distance: ユークリッド距離 (m)\n')
        else:
            updated_lines.append(line)

    # 出力
    with open(output_file, 'w', encoding='utf-8') as f:
        f.writelines(updated_lines)

    print(f"トポロジファイルを更新しました: {output_file}")

    # サンプル出力
    print("\n--- サンプルリンク ---")
    samples = [(133, 134), (117, 123), (6, 5), (6, 7)]
    for src, dst in samples:
        for s, d, c, dist, _ in link_info:
            if (s == src and d == dst) or (s == dst and d == src):
                lTubeLength = dist / min_distance
                print(f"LINK {s}-{d}: distance={dist:.1f}m, lTubeLength={lTubeLength:.4f}")
                break

if __name__ == "__main__":
    main()
