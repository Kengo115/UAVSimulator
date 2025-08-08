package server.util;

import client.Client;
import item.Beacon;
import item.BeaconCluster;
import item.Link;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * 結果出力を管理するクラス
 */
public class ResultOutputManager {

    /**
     * Pajekファイルに出力する
     * @param client クライアント
     * @param eps イプシロン
     * @param Q_allFlow 全フロー
     * @param ct カウンター
     * @param link リンク情報
     * @param beaconCluster ビーコンクラスター
     * @param node ノード数
     * @param runCounter 実行カウンター
     * @throws IOException 入出力例外
     */
    public static void outputToPajek(Client client, double eps, double Q_allFlow, int ct, Link[][] link, BeaconCluster beaconCluster, int node, int runCounter) throws IOException {
        Beacon source = client.getFlow().getSource();
        Beacon dist = client.getFlow().getDestination();

        // ディレクトリパスを作成
        String dirPath = "src/result/EPS/pajek/result" + runCounter;
        //String dirPath = "src/result/PS/pajek/result" + runCounter;
        //String dirPath = "src/result/Dijkstra/pajek/result" + runCounter;
        // ファイル名を作成
        String filename = dirPath + "/test_topology_" + (ct + 1) + ".net";

        // Fileオブジェクトでディレクトリの存在を確認・作成
        File dir = new File(dirPath);
        if (!dir.exists()) {
            // ディレクトリが存在しない場合は作成
            dir.mkdirs();
        }
        try (FileWriter writer = new FileWriter(filename)) {
            writer.write("*Vertices\t" + node + "\n");
            for (int i = 0; i < node; i++) {
                if (i == source.getId() || i == dist.getId()) {
                    writer.write(String.format("%d \"%d\" %.4f %.4f ic Black\n", i + 1, i + 1, source.getX(), source.getY()));
                } else {
                    writer.write(String.format("%d \"%d\" %.4f %.4f ic White\n", i + 1, i + 1, beaconCluster.getBeacon(i).getX(), beaconCluster.getBeacon(i).getY()));
                }
            }
            writer.write("*Arcs\n*Edges\n");

            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY) {
                        double flow = link[i][j].getQ_tubeFlow();
                        if (link[i][j].getDistance() > 0) {
                            if (flow > 0 && flow <= eps) {
                                // Small flow, no color
                            } else if (flow > eps && flow <= 0.5) {
                                writer.write(String.format("%d %d 1 c Blue\n", i + 1, j + 1));
                            } else if (flow > 0.5 && flow <= 2.0) {
                                writer.write(String.format("%d %d 2 c Green\n", i + 1, j + 1));
                            } else if (flow > 2.0 && flow <= Q_allFlow) {
                                writer.write(String.format("%d %d 3 c Red\n", i + 1, j + 1));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Excelファイルに各リンクの流量を出力する
     * @param client クライアント
     * @param ct カウンター
     * @param link リンク情報
     * @param node ノード数
     * @param runCounter 実行カウンター
     * @throws IOException 入出力例外
     */
    public static void outputToExcel(Client client, int ct, Link[][] link, int node, int runCounter) throws IOException {
        // ディレクトリパスを作成
        String dirPath = "src/result/EPS/excel/result" + runCounter;
        //String dirPath = "src/result/PS/excel/result" + runCounter;
        //String dirPath = "src/result/Dijkstra/excel/result" + runCounter;
        // ファイル名を作成
        String filename = dirPath + "/test_topology_" + (ct + 1) + ".txt";

        // Fileオブジェクトでディレクトリの存在を確認・作成
        File dir = new File(dirPath);
        if (!dir.exists()) {
            // ディレクトリが存在しない場合は作成
            dir.mkdirs();
        }
        try (FileWriter writer = new FileWriter(filename)) {
            writer.write("source,destination,flow\n");
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY) {
                        writer.write(String.format("%d,%d,%.4f\n", i, j, link[i][j].getQ_tubeFlow()));
                    }
                }
            }
        }
    }

    /**
     * txtファイルに管の長さ，管の太さ，管の容量を出力する
     * @param client クライアント
     * @param ct カウンター
     * @param link リンク情報
     * @param node ノード数
     * @param runCounter 実行カウンター
     * @throws IOException 入出力例外
     */
    public static void outputToTxt(Client client, int ct, Link[][] link, int node, int runCounter) throws IOException {
        // ディレクトリパスを作成
        String dirPath = "src/result/EPS/txt/result" + runCounter;
        //String dirPath = "src/result/PS/txt/result" + runCounter;
        //String dirPath = "src/result/Dijkstra/txt/result" + runCounter;
        // ファイル名を作成
        String filename = dirPath + "/test_topology_" + (ct + 1) + ".txt";

        // Fileオブジェクトでディレクトリの存在を確認・作成
        File dir = new File(dirPath);
        if (!dir.exists()) {
            // ディレクトリが存在しない場合は作成
            dir.mkdirs();
        }
        try (FileWriter writer = new FileWriter(filename)) {
            //要求uav台数，出発ノード，到着ノードを１行目に出力
            writer.write(String.format("%.1f,%d,%d\n", client.getFlow().getTheNumberOfUAV(), client.getFlow().getSource().getId(), client.getFlow().getDestination().getId()));
            writer.write("source,destination,length,thickness,capacity\n");
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY) {
                        writer.write(String.format("%d,%d,%.4f,%.4f,%.4f\n", i, j, link[i][j].getL_tubeLength(), link[i][j].getD_tubeThickness(), link[i][j].getCapacity()));
                    }
                }
            }
        }
    }

    /**
     * 経路ごとのUAV数をExcel形式で出力する
     * @param client クライアント
     * @param ct カウンター
     * @param link リンク情報
     * @param runCounter 実行カウンター
     * @throws IOException 入出力例外
     */
    public static void outputRouteToExcel(Client client, int ct, Link[][] link, int runCounter) throws IOException {
        // ディレクトリパスを作成
        String dirPath = "src/result/EPS/rute/result" + runCounter;
        //String dirPath = "src/result/PS/rute/result" + runCounter;
        //String dirPath = "src/result/Dijkstra/rute/result" + runCounter;
        // ファイル名を作成
        String filename = dirPath + "/test_topology_routes.txt";

        // Fileオブジェクトでディレクトリの存在を確認・作成
        File dir = new File(dirPath);
        if (!dir.exists()) {
            // ディレクトリが存在しない場合は作成
            dir.mkdirs();
        }

        // ファイルに追記
        try (FileWriter writer = new FileWriter(filename, true)) { // true で追記モードに設定
            // ヘッダーは1回だけ記載されるようにする
            File file = new File(filename);
            if (file.length() == 0) { // ファイルが空の場合のみヘッダーを書き込む
                writer.write("ct,v0->v1->v4->v5,v0->v2->v3->v5,v0->v2->v5,v0->v3->v5\n");
            }
            if (runCounter == 0 || runCounter == 1) {
                if (file.length() == 0) { // ファイルが空の場合のみヘッダーを書き込む
                    writer.write("ct,v0->v1->v4->v5,v0->v2->v3->v5,v0->v2->v5,v0->v3->v5\n");
                }
                double route1 = Math.min(link[0][1].getQ_tubeFlow(),
                        Math.min(link[1][4].getQ_tubeFlow(), link[4][5].getQ_tubeFlow()));

                double route2 = Math.min(link[0][2].getQ_tubeFlow(),
                        Math.min(link[2][3].getQ_tubeFlow(), link[3][5].getQ_tubeFlow()));

                double route3 = Math.min(link[0][2].getQ_tubeFlow(), link[2][5].getQ_tubeFlow());

                double route4 = Math.min(link[0][3].getQ_tubeFlow(), link[3][5].getQ_tubeFlow());
                // 経路ごとの情報を1行にまとめて追記
                writer.write(String.format("%d,%.4f,%.4f,%.4f,%.4f\n", ct, route1, route2, route3, route4));

            } else if (runCounter == 2) {
                if (file.length() == 0) { // ファイルが空の場合のみヘッダーを書き込む
                    writer.write("ct,v2->v0->v1->v4,v2->v3->v0->v1->v4,v2->v3->v5->v4,v2->v5->v4\n");
                }
                double route1 = Math.min(link[2][0].getQ_tubeFlow(),
                        Math.min(link[0][1].getQ_tubeFlow(), link[1][4].getQ_tubeFlow()));

                double route2 = Math.min(link[2][3].getQ_tubeFlow(),
                        Math.min(link[3][0].getQ_tubeFlow(), Math.min(link[0][1].getQ_tubeFlow(), link[1][4].getQ_tubeFlow())));

                double route3 = Math.min(link[2][3].getQ_tubeFlow(), Math.min(link[3][5].getQ_tubeFlow(), link[5][4].getQ_tubeFlow()));

                double route4 = Math.min(link[2][5].getQ_tubeFlow(), link[5][4].getQ_tubeFlow());
                // 経路ごとの情報を1行にまとめて追記
                writer.write(String.format("%d,%.4f,%.4f,%.4f,%.4f\n", ct, route1, route2, route3, route4));
            }
        }
    }
}
