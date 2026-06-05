package shared.network;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ネットワークトポロジファイルを読み込むクラス
 */
public class TopologyFileReader {

    /**
     * ノード情報を保持するクラス
     */
    public static class NodeInfo {
        public final int id;
        public final double x;
        public final double y;
        public final int districtType; // 0=点在地区, 1=集中地区

        public NodeInfo(int id, double x, double y) {
            this(id, x, y, 0);
        }

        public NodeInfo(int id, double x, double y, int districtType) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.districtType = districtType;
        }
    }

    /**
     * リンク情報を保持するクラス
     */
    public static class LinkInfo {
        public final int source;
        public final int dest;
        public final double capacity;
        public final double distance;
        public final double lTubeLength;

        public LinkInfo(int source, int dest, double capacity, double distance, double lTubeLength) {
            this.source = source;
            this.dest = dest;
            this.capacity = capacity;
            this.distance = distance;
            this.lTubeLength = lTubeLength;
        }
    }

    /**
     * トポロジ情報を保持するクラス
     */
    public static class TopologyData {
        public final int nodeCount;
        public final List<NodeInfo> nodes;
        public final List<LinkInfo> links;

        public TopologyData(int nodeCount, List<NodeInfo> nodes, List<LinkInfo> links) {
            this.nodeCount = nodeCount;
            this.nodes = nodes;
            this.links = links;
        }
    }

    /**
     * トポロジファイルを読み込む
     * @param filePath ファイルパス
     * @return トポロジデータ
     * @throws IOException ファイル読み込みエラー
     */
    public static TopologyData readTopologyFile(String filePath) throws IOException {
        int nodeCount = 0;
        List<NodeInfo> nodes = new ArrayList<>();
        List<LinkInfo> links = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();

                // 空行またはコメント行をスキップ
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split("\\s+");

                try {
                    if (parts[0].equals("NODES")) {
                        // ノード数の読み込み
                        if (parts.length != 2) {
                            throw new IOException("NODES行のフォーマットが不正です (行 " + lineNumber + "): " + line);
                        }
                        nodeCount = Integer.parseInt(parts[1]);

                    } else if (parts[0].equals("NODE")) {
                        // ノード情報の読み込み（4列または5列対応）
                        if (parts.length < 4 || parts.length > 5) {
                            throw new IOException("NODE行のフォーマットが不正です (行 " + lineNumber + "): " + line);
                        }
                        int id = Integer.parseInt(parts[1]);
                        double x = Double.parseDouble(parts[2]);
                        double y = Double.parseDouble(parts[3]);
                        int districtType = (parts.length >= 5) ? Integer.parseInt(parts[4]) : 0;

                        // 座標の範囲チェック
                        if (x < 0.0 || x > 1.0 || y < 0.0 || y > 1.0) {
                            throw new IOException("座標は0.0～1.0の範囲である必要があります (行 " + lineNumber + "): " + line);
                        }

                        nodes.add(new NodeInfo(id, x, y, districtType));

                    } else if (parts[0].equals("LINK")) {
                        // リンク情報の読み込み
                        if (parts.length != 6) {
                            throw new IOException("LINK行のフォーマットが不正です (行 " + lineNumber + "): " + line);
                        }
                        int source = Integer.parseInt(parts[1]);
                        int dest = Integer.parseInt(parts[2]);
                        double capacity = Double.parseDouble(parts[3]);
                        double distance = Double.parseDouble(parts[4]);
                        double lTubeLength = Double.parseDouble(parts[5]);

                        links.add(new LinkInfo(source, dest, capacity, distance, lTubeLength));

                    } else {
                        throw new IOException("不明なキーワードです (行 " + lineNumber + "): " + parts[0]);
                    }
                } catch (NumberFormatException e) {
                    throw new IOException("数値のパースに失敗しました (行 " + lineNumber + "): " + line, e);
                }
            }
        }

        // バリデーション
        if (nodeCount == 0) {
            throw new IOException("NODES行が見つかりません");
        }

        if (nodes.size() != nodeCount) {
            throw new IOException("ノード数が一致しません。宣言: " + nodeCount + ", 定義: " + nodes.size());
        }

        // ノードIDの連続性チェック
        for (int i = 0; i < nodeCount; i++) {
            if (nodes.get(i).id != i) {
                throw new IOException("ノードIDは0から連続している必要があります。期待: " + i + ", 実際: " + nodes.get(i).id);
            }
        }

        // リンクのノードID範囲チェック
        for (LinkInfo link : links) {
            if (link.source < 0 || link.source >= nodeCount) {
                throw new IOException("リンクのsource IDが範囲外です: " + link.source);
            }
            if (link.dest < 0 || link.dest >= nodeCount) {
                throw new IOException("リンクのdest IDが範囲外です: " + link.dest);
            }
            if (link.source == link.dest) {
                throw new IOException("自己ループは許可されていません: " + link.source + " -> " + link.dest);
            }
        }

        return new TopologyData(nodeCount, nodes, links);
    }

    /**
     * トポロジデータの情報を出力する
     * @param data トポロジデータ
     */
    public static void printTopologyInfo(TopologyData data) {
        System.out.println("=== ネットワークトポロジ情報 ===");
        System.out.println("ノード数: " + data.nodeCount);
        System.out.println("\n--- ノード一覧 ---");
        for (NodeInfo node : data.nodes) {
            System.out.printf("  Node %d: (%.2f, %.2f)\n", node.id, node.x, node.y);
        }
        System.out.println("\n--- リンク一覧 ---");
        for (LinkInfo link : data.links) {
            System.out.printf("  Link %d↔%d: 容量=%.0f, 距離=%.0fm, 長さ=%.1f\n",
                link.source, link.dest, link.capacity, link.distance, link.lTubeLength);
        }
        System.out.println("===============================\n");
    }

    /**
     * デフォルトのトポロジファイルパス
     */
    public static final String DEFAULT_TOPOLOGY_PATH = "config/topology/default_topology.txt";
}
