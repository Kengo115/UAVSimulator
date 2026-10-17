package shared.util;

import shared.network.TopologyFileReader.TopologyData;
import shared.network.TopologyFileReader.LinkInfo;

import java.io.*;
import java.util.*;

/**
 * 全ノード間の最大フローを算出するソルバー（Edmonds-Karp法）
 * トポロジ読み込み時に一度だけ計算し、結果を静的に保持する
 */
public class MaxFlowSolver {

    private static double[][] maxFlowMatrix = null;
    private static int nodeCount = 0;
    private static String outputFilePath = null;

    /**
     * 全ノード間の最大フローを計算し、2D配列とファイルに保存する
     * @param topologyData   読み込み済みトポロジデータ
     * @param topologyFilePath トポロジファイルのパス（出力ファイル名の生成に使用）
     * @param isLargeScale   true=大規模ネットワーク、false=小規模ネットワーク
     */
    public static void computeAll(TopologyData topologyData,
                                  String topologyFilePath,
                                  boolean isLargeScale) throws IOException {
        int n = topologyData.nodeCount;
        nodeCount = n;

        // --- 容量行列の構築（双方向）---
        double[][] capacity = new double[n][n];
        for (LinkInfo link : topologyData.links) {
            if (link.source == link.dest) continue; // 自己ループは無視
            capacity[link.source][link.dest] += link.capacity;
            capacity[link.dest][link.source] += link.capacity;
        }

        // --- 全ペア最大フロー計算（s < d のみ計算し、対称コピー）---
        maxFlowMatrix = new double[n][n];
        int totalPairs = n * (n - 1) / 2;
        System.out.printf("最大フロー計算中... (%dノード, %dペア)%n", n, totalPairs);

        int computed = 0;
        for (int s = 0; s < n; s++) {
            for (int d = s + 1; d < n; d++) {
                double flow = edmondsKarp(capacity, n, s, d);
                maxFlowMatrix[s][d] = flow;
                maxFlowMatrix[d][s] = flow; // 最大フロー値は対称
                computed++;
                if (n >= 100 && computed % 2000 == 0) {
                    System.out.printf("  進捗: %d / %d ペア完了%n", computed, totalPairs);
                }
            }
        }

        // --- ファイル出力 ---
        String scaleDir = isLargeScale ? "large_scale" : "small_scale";
        String outDir = "config/topology/maximum_flow/" + scaleDir;
        new File(outDir).mkdirs();

        String baseName = new File(topologyFilePath).getName();
        if (baseName.contains(".")) baseName = baseName.substring(0, baseName.lastIndexOf('.'));
        outputFilePath = outDir + "/" + baseName + "_max_flow.txt";

        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFilePath))) {
            writer.println("# node1 node2 max_flow");
            for (int s = 0; s < n; s++) {
                for (int d = s + 1; d < n; d++) {
                    writer.printf("%d %d %.0f%n", s, d, maxFlowMatrix[s][d]);
                }
            }
        }

        System.out.println("最大フロー計算完了 → " + outputFilePath);
    }

    /**
     * Edmonds-Karp法（BFS版 Ford-Fulkerson）で s→t の最大フローを求める
     */
    private static double edmondsKarp(double[][] cap, int n, int source, int sink) {
        double[][] residual = new double[n][n];
        for (int i = 0; i < n; i++) {
            residual[i] = cap[i].clone();
        }

        double totalFlow = 0.0;
        int[] parent = new int[n];

        while (true) {
            // BFS で増加路を探索
            Arrays.fill(parent, -1);
            parent[source] = source;
            Queue<Integer> queue = new ArrayDeque<>();
            queue.add(source);

            bfs:
            while (!queue.isEmpty()) {
                int u = queue.poll();
                for (int v = 0; v < n; v++) {
                    if (parent[v] == -1 && residual[u][v] > 1e-9) {
                        parent[v] = u;
                        if (v == sink) break bfs;
                        queue.add(v);
                    }
                }
            }

            if (parent[sink] == -1) break; // 増加路なし → 終了

            // ボトルネック容量の算出
            double bottleneck = Double.MAX_VALUE;
            for (int v = sink; v != source; v = parent[v]) {
                bottleneck = Math.min(bottleneck, residual[parent[v]][v]);
            }

            // 残余グラフの更新
            for (int v = sink; v != source; v = parent[v]) {
                int u = parent[v];
                residual[u][v] -= bottleneck;
                residual[v][u] += bottleneck;
            }

            totalFlow += bottleneck;
        }

        return totalFlow;
    }

    // ===== アクセサ =====

    /** 事前計算済みの最大フロー値を返す */
    public static double getMaxFlow(int s, int d) {
        if (maxFlowMatrix == null || s < 0 || d < 0 || s >= nodeCount || d >= nodeCount) return 0.0;
        return maxFlowMatrix[s][d];
    }

    public static double[][] getMaxFlowMatrix() { return maxFlowMatrix; }
    public static boolean isInitialized()        { return maxFlowMatrix != null; }
    public static String getOutputFilePath()     { return outputFilePath; }
}
