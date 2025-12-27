package server.network;

import item.BeaconCluster;
import item.Link;

import java.util.List;

/**
 * ネットワークトポロジーを管理するクラス
 */
public class NetworkTopologyManager {
    // 定数
    private static final double INF = 10000.0;
    private static final double INIT_THICKNESS = 0.5;
    private static final double INIT_LENGTH = 1.0;
    private static final double INIT_RATE = 100.0;

    // ノード数
    private int node;

    // リンク情報
    private Link[][] link;

    // ビーコンクラスター
    private BeaconCluster beaconCluster;

    // 隣接行列
    private int[][] adjMatrix;

    // トポロジデータ（外部ファイルから読み込んだ場合）
    private TopologyFileReader.TopologyData topologyData;

    /**
     * コンストラクタ（デフォルト：ハードコードされたトポロジ）
     * @param node ノード数
     * @param link リンク情報
     * @param beaconCluster ビーコンクラスター
     * @param adjMatrix 隣接行列
     */
    public NetworkTopologyManager(int node, Link[][] link, BeaconCluster beaconCluster, int[][] adjMatrix) {
        this.node = node;
        this.link = link;
        this.beaconCluster = beaconCluster;
        this.adjMatrix = adjMatrix;
        this.topologyData = null;
    }

    /**
     * コンストラクタ（外部ファイルから読み込んだトポロジデータを使用）
     * @param link リンク情報
     * @param beaconCluster ビーコンクラスター
     * @param adjMatrix 隣接行列
     * @param topologyData トポロジデータ
     */
    public NetworkTopologyManager(Link[][] link, BeaconCluster beaconCluster, int[][] adjMatrix, TopologyFileReader.TopologyData topologyData) {
        this.node = topologyData.nodeCount;
        this.link = link;
        this.beaconCluster = beaconCluster;
        this.adjMatrix = adjMatrix;
        this.topologyData = topologyData;
    }

    /**
     * 基本的なリンクを設定する
     */
    public void setupBasicLinks() {
        // 外部ファイルから読み込んだ場合
        if (topologyData != null) {
            setupBasicLinksFromTopologyData();
            return;
        }

        // デフォルト（ハードコード）
        link[0][1].setD_tubeThickness(INIT_THICKNESS);
        link[0][1].setL_tubeLength(1);
        adjMatrix[0][1] = 1;

        link[1][0].setD_tubeThickness(INIT_THICKNESS);
        link[1][0].setL_tubeLength(1);
        adjMatrix[1][0] = 1;

        link[0][2].setD_tubeThickness(INIT_THICKNESS);
        link[0][2].setL_tubeLength(2);
        adjMatrix[0][2] = 1;

        link[2][0].setD_tubeThickness(INIT_THICKNESS);
        link[2][0].setL_tubeLength(2);
        adjMatrix[2][0] = 1;

        link[0][3].setD_tubeThickness(INIT_THICKNESS);
        link[0][3].setL_tubeLength(2);
        adjMatrix[0][3] = 1;

        link[3][0].setD_tubeThickness(INIT_THICKNESS);
        link[3][0].setL_tubeLength(2);
        adjMatrix[3][0] = 1;

        link[1][4].setD_tubeThickness(INIT_THICKNESS);
        link[1][4].setL_tubeLength(2);
        adjMatrix[1][4] = 1;

        link[4][1].setD_tubeThickness(INIT_THICKNESS);
        link[4][1].setL_tubeLength(2);
        adjMatrix[4][1] = 1;

        link[2][3].setD_tubeThickness(INIT_THICKNESS);
        link[2][3].setL_tubeLength(1);
        adjMatrix[2][3] = 1;

        link[3][2].setD_tubeThickness(INIT_THICKNESS);
        link[3][2].setL_tubeLength(1);
        adjMatrix[3][2] = 1;

        link[2][5].setD_tubeThickness(INIT_THICKNESS);
        link[2][5].setL_tubeLength(3);
        adjMatrix[2][5] = 1;

        link[5][2].setD_tubeThickness(INIT_THICKNESS);
        link[5][2].setL_tubeLength(3);
        adjMatrix[5][2] = 1;

        link[3][5].setD_tubeThickness(INIT_THICKNESS);
        link[3][5].setL_tubeLength(2);
        adjMatrix[3][5] = 1;

        link[5][3].setD_tubeThickness(INIT_THICKNESS);
        link[5][3].setL_tubeLength(2);
        adjMatrix[5][3] = 1;

        link[4][5].setD_tubeThickness(INIT_THICKNESS);
        link[4][5].setL_tubeLength(3.3);
        adjMatrix[4][5] = 1;

        link[5][4].setD_tubeThickness(INIT_THICKNESS);
        link[5][4].setL_tubeLength(3.3);
        adjMatrix[5][4] = 1;
    }

    /**
     * 詳細なリンクを設定する
     */
    public void setupDetailedLinks() {
        // 外部ファイルから読み込んだ場合
        if (topologyData != null) {
            setupDetailedLinksFromTopologyData();
            return;
        }

        // デフォルト（ハードコード）
        link[0][1].setLink(beaconCluster.getBeacon(0), beaconCluster.getBeacon(1), 5);
        link[0][1].setD_tubeThickness(INIT_THICKNESS);
        link[0][1].setL_tubeLength(1);
        link[0][1].setDistance(250);
        link[0][1].setCongestionRate(INIT_RATE);
        adjMatrix[0][1] = 1;

        link[1][0].setLink(beaconCluster.getBeacon(1), beaconCluster.getBeacon(0), 5);
        link[1][0].setD_tubeThickness(INIT_THICKNESS);
        link[1][0].setL_tubeLength(1);
        link[1][0].setDistance(250);
        link[1][0].setCongestionRate(INIT_RATE);
        adjMatrix[1][0] = 1;

        link[0][2].setLink(beaconCluster.getBeacon(0), beaconCluster.getBeacon(2), 15);
        link[0][2].setD_tubeThickness(INIT_THICKNESS);
        link[0][2].setL_tubeLength(3);
        link[0][2].setDistance(750);
        link[0][2].setCongestionRate(INIT_RATE);
        adjMatrix[0][2] = 1;

        link[2][0].setLink(beaconCluster.getBeacon(2), beaconCluster.getBeacon(0), 15);
        link[2][0].setD_tubeThickness(INIT_THICKNESS);
        link[2][0].setL_tubeLength(3);
        link[2][0].setDistance(750);
        link[2][0].setCongestionRate(INIT_RATE);
        adjMatrix[2][0] = 1;

        link[0][3].setLink(beaconCluster.getBeacon(0), beaconCluster.getBeacon(3), 10);
        link[0][3].setD_tubeThickness(INIT_THICKNESS);
        link[0][3].setL_tubeLength(2);
        link[0][3].setDistance(500);
        link[0][3].setCongestionRate(INIT_RATE);
        adjMatrix[0][3] = 1;

        link[3][0].setLink(beaconCluster.getBeacon(3), beaconCluster.getBeacon(0), 10);
        link[3][0].setD_tubeThickness(INIT_THICKNESS);
        link[3][0].setL_tubeLength(2);
        link[3][0].setDistance(500);
        link[3][0].setCongestionRate(INIT_RATE);
        adjMatrix[3][0] = 1;

        link[1][4].setLink(beaconCluster.getBeacon(1), beaconCluster.getBeacon(4), 10);
        link[1][4].setD_tubeThickness(INIT_THICKNESS);
        link[1][4].setL_tubeLength(2);
        link[1][4].setDistance(500);
        link[1][4].setCongestionRate(INIT_RATE);
        adjMatrix[1][4] = 1;

        link[4][1].setLink(beaconCluster.getBeacon(4), beaconCluster.getBeacon(1), 10);
        link[4][1].setD_tubeThickness(INIT_THICKNESS);
        link[4][1].setL_tubeLength(2);
        link[4][1].setDistance(500);
        link[4][1].setCongestionRate(INIT_RATE);
        adjMatrix[4][1] = 1;

        link[2][3].setLink(beaconCluster.getBeacon(2), beaconCluster.getBeacon(3), 5);
        link[2][3].setD_tubeThickness(INIT_THICKNESS);
        link[2][3].setL_tubeLength(1);
        link[2][3].setDistance(250);
        link[2][3].setCongestionRate(INIT_RATE);
        adjMatrix[2][3] = 1;

        link[3][2].setLink(beaconCluster.getBeacon(3), beaconCluster.getBeacon(2), 5);
        link[3][2].setD_tubeThickness(INIT_THICKNESS);
        link[3][2].setL_tubeLength(1);
        link[3][2].setDistance(250);
        link[3][2].setCongestionRate(INIT_RATE);
        adjMatrix[3][2] = 1;

        link[2][5].setLink(beaconCluster.getBeacon(2), beaconCluster.getBeacon(5), 15);
        link[2][5].setD_tubeThickness(INIT_THICKNESS);
        link[2][5].setL_tubeLength(3);
        link[2][5].setDistance(750);
        link[2][5].setCongestionRate(INIT_RATE);
        adjMatrix[2][5] = 1;

        link[5][2].setLink(beaconCluster.getBeacon(5), beaconCluster.getBeacon(2), 15);
        link[5][2].setD_tubeThickness(INIT_THICKNESS);
        link[5][2].setL_tubeLength(3);
        link[5][2].setDistance(750);
        link[5][2].setCongestionRate(INIT_RATE);
        adjMatrix[5][2] = 1;

        link[3][5].setLink(beaconCluster.getBeacon(3), beaconCluster.getBeacon(5), 10);
        link[3][5].setD_tubeThickness(INIT_THICKNESS);
        link[3][5].setL_tubeLength(2);
        link[3][5].setDistance(500);
        link[3][5].setCongestionRate(INIT_RATE);
        adjMatrix[3][5] = 1;

        link[5][3].setLink(beaconCluster.getBeacon(5), beaconCluster.getBeacon(3), 10);
        link[5][3].setD_tubeThickness(INIT_THICKNESS);
        link[5][3].setL_tubeLength(2);
        link[5][3].setDistance(500);
        link[5][3].setCongestionRate(INIT_RATE);
        adjMatrix[5][3] = 1;

        link[4][5].setLink(beaconCluster.getBeacon(4), beaconCluster.getBeacon(5), 15);
        link[4][5].setD_tubeThickness(INIT_THICKNESS);
        link[4][5].setL_tubeLength(3.3);
        link[4][5].setDistance(850);
        link[4][5].setCongestionRate(INIT_RATE);
        adjMatrix[4][5] = 1;

        link[5][4].setLink(beaconCluster.getBeacon(5), beaconCluster.getBeacon(4), 15);
        link[5][4].setD_tubeThickness(INIT_THICKNESS);
        link[5][4].setL_tubeLength(3.3);
        link[5][4].setDistance(850);
        link[5][4].setCongestionRate(INIT_RATE);
        adjMatrix[5][4] = 1;
    }

    /**
     * 外部ファイルから読み込んだトポロジデータを使って基本リンクを設定する
     */
    private void setupBasicLinksFromTopologyData() {
        List<TopologyFileReader.LinkInfo> links = topologyData.links;

        for (TopologyFileReader.LinkInfo linkInfo : links) {
            int i = linkInfo.source;
            int j = linkInfo.dest;

            // i -> j
            link[i][j].setD_tubeThickness(INIT_THICKNESS);
            link[i][j].setL_tubeLength(linkInfo.lTubeLength);
            adjMatrix[i][j] = 1;

            // j -> i（双方向）
            link[j][i].setD_tubeThickness(INIT_THICKNESS);
            link[j][i].setL_tubeLength(linkInfo.lTubeLength);
            adjMatrix[j][i] = 1;
        }
    }

    /**
     * 外部ファイルから読み込んだトポロジデータを使って詳細リンクを設定する
     */
    private void setupDetailedLinksFromTopologyData() {
        List<TopologyFileReader.LinkInfo> links = topologyData.links;

        for (TopologyFileReader.LinkInfo linkInfo : links) {
            int i = linkInfo.source;
            int j = linkInfo.dest;

            // i -> j
            link[i][j].setLink(beaconCluster.getBeacon(i), beaconCluster.getBeacon(j), linkInfo.capacity);
            link[i][j].setD_tubeThickness(INIT_THICKNESS);
            link[i][j].setL_tubeLength(linkInfo.lTubeLength);
            link[i][j].setDistance(linkInfo.distance);
            link[i][j].setCongestionRate(INIT_RATE);
            adjMatrix[i][j] = 1;

            // j -> i（双方向）
            link[j][i].setLink(beaconCluster.getBeacon(j), beaconCluster.getBeacon(i), linkInfo.capacity);
            link[j][i].setD_tubeThickness(INIT_THICKNESS);
            link[j][i].setL_tubeLength(linkInfo.lTubeLength);
            link[j][i].setDistance(linkInfo.distance);
            link[j][i].setCongestionRate(INIT_RATE);
            adjMatrix[j][i] = 1;
        }
    }
}
