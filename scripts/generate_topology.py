#!/usr/bin/env python3
"""
中規模トポロジ生成ツール

ランダムノード配置とユークリッド距離ベースの接続でトポロジファイルを生成する。

【アルゴリズム】
1. N個のノードを正規化座標 [0.05, 0.95]² にランダム配置
2. 全ノード間のユークリッド距離を算出
3. クラスカル法でMST（最小全域木）を構築 → 連結性を保証
4. MST以外の辺について: 距離 ≤ radius かつ確率 prob で辺を追加
5. 実際の距離(m) = 正規化距離 × scale
6. lTubeLength = 実距離 / 最短辺距離

Usage:
    python scripts/generate_topology.py --nodes N [options]

Example:
    python scripts/generate_topology.py --nodes 30
    python scripts/generate_topology.py --nodes 50 --seed 123 --radius 0.25 --prob 0.6
    python scripts/generate_topology.py --nodes 100 --capacity 8 --output config/topology/medium_100.txt
"""

import argparse
import math
import os
import random
import sys


def euclidean_dist(p1, p2):
    return math.sqrt((p1[0] - p2[0]) ** 2 + (p1[1] - p2[1]) ** 2)


def build_mst(n, dist_matrix):
    """クラスカル法でMSTを構築し、辺集合を返す。"""
    edges = []
    for i in range(n):
        for j in range(i + 1, n):
            edges.append((dist_matrix[i][j], i, j))
    edges.sort()

    parent = list(range(n))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(x, y):
        px, py = find(x), find(y)
        if px == py:
            return False
        parent[px] = py
        return True

    mst = set()
    for d, i, j in edges:
        if union(i, j):
            mst.add((i, j))
            if len(mst) == n - 1:
                break
    return mst


def auto_radius(n):
    """ノード数から接続距離閾値を自動決定する。
    目標: 平均次数 ~5
    均一分布での期待次数 ≈ N * π * r²  → r = sqrt(5 / (N * π)) * 1.2
    """
    r = 1.2 * math.sqrt(5.0 / (n * math.pi))
    return min(max(r, 0.10), 0.65)


def generate_topology(n_nodes, seed, radius, prob, scale, capacity, output):
    rng = random.Random(seed)

    # ノードをランダム配置
    nodes = [(rng.uniform(0.05, 0.95), rng.uniform(0.05, 0.95))
             for _ in range(n_nodes)]

    # 全ペアのユークリッド距離（正規化座標）
    dist_matrix = [[0.0] * n_nodes for _ in range(n_nodes)]
    for i in range(n_nodes):
        for j in range(i + 1, n_nodes):
            d = euclidean_dist(nodes[i], nodes[j])
            dist_matrix[i][j] = d
            dist_matrix[j][i] = d

    # MST で連結性を保証
    edges = set(build_mst(n_nodes, dist_matrix))

    # radius 以内のノード間をランダムに追加接続
    for i in range(n_nodes):
        for j in range(i + 1, n_nodes):
            if (i, j) in edges:
                continue
            if dist_matrix[i][j] <= radius and rng.random() < prob:
                edges.add((i, j))

    # 実距離(m) に変換
    edge_list = sorted(edges)
    dist_meters = {(i, j): dist_matrix[i][j] * scale for i, j in edge_list}

    # lTubeLength 正規化
    min_dist_m = min(dist_meters.values())
    tube_lengths = {k: v / min_dist_m for k, v in dist_meters.items()}

    # 平均次数の計算（確認用）
    avg_degree = 2 * len(edge_list) / n_nodes

    # 出力ファイル書き込み
    os.makedirs(os.path.dirname(os.path.abspath(output)), exist_ok=True)
    with open(output, 'w') as f:
        f.write(f"# 中規模トポロジ（自動生成）\n")
        f.write(f"# ノード数: {n_nodes}, リンク数: {len(edge_list)}, 平均次数: {avg_degree:.2f}\n")
        f.write(f"# 生成パラメータ: seed={seed}, radius={radius:.4f}, prob={prob}\n")
        f.write(f"# スケール: {scale:.0f} m/unit, 最短辺: {min_dist_m:.1f} m\n")
        f.write(f"#\n")
        f.write(f"NODES {n_nodes}\n")
        f.write(f"\n")
        f.write(f"# NODE id x y\n")
        for i, (x, y) in enumerate(nodes):
            f.write(f"NODE {i} {x:.6f} {y:.6f}\n")
        f.write(f"\n")
        f.write(f"# LINK source dest capacity distance lTubeLength\n")
        for i, j in edge_list:
            d = dist_meters[(i, j)]
            lt = tube_lengths[(i, j)]
            f.write(f"LINK {i} {j} {capacity} {d:.1f} {lt:.4f}\n")

    return len(edge_list), avg_degree, radius, min_dist_m


def main():
    parser = argparse.ArgumentParser(
        description='中規模トポロジ生成ツール',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
使用例:
  python scripts/generate_topology.py --nodes 30
  python scripts/generate_topology.py --nodes 50 --seed 123 --radius 0.25 --prob 0.6
  python scripts/generate_topology.py --nodes 100 --capacity 8
  python scripts/generate_topology.py --nodes 20 --output config/topology/test_medium.txt
"""
    )
    parser.add_argument('--nodes', type=int, required=True,
                        help='ノード数（必須）')
    parser.add_argument('--seed', type=int, default=42,
                        help='乱数シード（デフォルト: 42）')
    parser.add_argument('--radius', type=float, default=None,
                        help='接続距離閾値（正規化座標 0.0-1.0、デフォルト: 自動）')
    parser.add_argument('--prob', type=float, default=0.5,
                        help='閾値内ノード間の辺追加確率（デフォルト: 0.5）')
    parser.add_argument('--scale', type=float, default=1000.0,
                        help='スケールファクタ m/unit（デフォルト: 1000）')
    parser.add_argument('--capacity', type=int, default=10,
                        help='リンク容量（同時飛行可能UAV数、デフォルト: 10）')
    parser.add_argument('--output', type=str, default=None,
                        help='出力ファイルパス（デフォルト: config/topology/medium_{N}nodes.txt）')

    args = parser.parse_args()

    if args.nodes < 2:
        print("Error: ノード数は2以上である必要があります")
        sys.exit(1)
    if not (0.0 < args.prob <= 1.0):
        print("Error: --prob は (0.0, 1.0] の範囲で指定してください")
        sys.exit(1)
    if args.radius is not None and not (0.0 < args.radius <= 1.5):
        print("Error: --radius は (0.0, 1.5] の範囲で指定してください")
        sys.exit(1)

    radius = args.radius if args.radius is not None else auto_radius(args.nodes)
    output = args.output if args.output else f"config/topology/medium_{args.nodes}nodes.txt"

    print(f"トポロジを生成します...")
    print(f"  ノード数       : {args.nodes}")
    print(f"  乱数シード     : {args.seed}")
    print(f"  接続距離閾値   : {radius:.4f}" + (" (自動)" if args.radius is None else ""))
    print(f"  辺追加確率     : {args.prob}")
    print(f"  スケール       : {args.scale:.0f} m/unit")
    print(f"  リンク容量     : {args.capacity}")
    print(f"  出力先         : {output}")
    print()

    n_links, avg_deg, used_radius, min_dist = generate_topology(
        n_nodes=args.nodes,
        seed=args.seed,
        radius=radius,
        prob=args.prob,
        scale=args.scale,
        capacity=args.capacity,
        output=output,
    )

    print(f"生成完了:")
    print(f"  ノード数   : {args.nodes}")
    print(f"  リンク数   : {n_links}")
    print(f"  平均次数   : {avg_deg:.2f}")
    print(f"  最短辺距離 : {min_dist:.1f} m")
    print(f"  出力先     : {output}")


if __name__ == "__main__":
    main()
