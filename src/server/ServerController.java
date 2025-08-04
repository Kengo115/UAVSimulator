package server;

import client.Client;
import client.ClientController;
import item.Beacon;
import item.BeaconCluster;
import item.Link;
import item.Uav;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static java.lang.Math.sqrt;


public class ServerController {

    private static final double INF = 10000.0;
    private static final double NEG = -1.0;
    private static final double GAMMA = 1.01;
    private static final double DELTA_TIME = 0.01;
    private static final int PLOT = 1;
    private static final int PLOT_2 = 20;
    private static final double INIT_THICKNESS = 0.5;
    private static final double INIT_LENGTH = 1.0;
    private static final double INIT_RATE = 100.0;
    private static final double THRESHOLD_1 = 0.5;
    private static final double THRESHOLD_2 = 2.0;
    private static int node;
    private int runCounter = 0;
    private boolean fig_SOURCE = false;
    private boolean fig_DIST = false;
    private static final double coefficient_tanh = 1;
    // 基本パラメータ
    private static Link[][] link;
    private double[] Q_Kirchhoff;
    private double[] P_tubePressure;

    // 計算パラメータ
    private double[][] D_tubeThickness_deltaT;
    private double[][] pressureCoefficient;
    private double[] Q_Kirchhoff_sinkExcept;
    private double[] P_tubePressure_sinkExcept;
    private double[][] pressureCoefficient_sinkExcept;

    // シグモイド関数用
    private double[][] Q_tubeFlow_sigmoidOutput;

    private static BeaconCluster beaconCluster;
    private double[][] Flow_Capacity;
    private int[][] tubeFlow;
    private int[][] adjMatrix;

    private int min_Flow = 100;
    int UAV_count;
    int maxPathIndex = 0;
    private static int clientNum = 1;
    private static int counter = 0;

    public ServerController(int node) {
        initialize(node);
    }

    public void initialize(int node) {
        int nodeExcept = node - 1;

        // 1xN matrix
        this.Q_Kirchhoff = new double[node];
        this.P_tubePressure = new double[node];
        this.Q_Kirchhoff_sinkExcept = new double[nodeExcept];
        this.P_tubePressure_sinkExcept = new double[nodeExcept];
        // 初期値を追加してサイズを確保

        // 2xN matrix
        this.pressureCoefficient = new double[node][node];
        this.pressureCoefficient_sinkExcept = new double[nodeExcept][nodeExcept];
        this.D_tubeThickness_deltaT = new double[node][node];
        this.Q_tubeFlow_sigmoidOutput = new double[node][node];
        this.Flow_Capacity = new double[node][node];
        this.tubeFlow = new int[node][node];
        this.adjMatrix = new int[node][node];


        // node数に応じてArrayList<Link>を初期化
        link = new Link[node][node]; // `node x node` の2次元配列を作成
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                link[i][j] = new Link(); // 各要素に Link オブジェクトを追加
            }
        }
    }

    //フィールドをすべてリセットする
    public void reset(){
        Arrays.fill(Q_Kirchhoff, 0.0);
        Arrays.fill(P_tubePressure, 0.0);
        // 2次元配列の初期化
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                pressureCoefficient[i][j] = 0.0;  // すべての要素に0.0を設定
                D_tubeThickness_deltaT[i][j] = 0.0;
                Q_tubeFlow_sigmoidOutput[i][j] = 0.0;
                Flow_Capacity[i][j] = 0.0;
                tubeFlow[i][j] = 0;
                adjMatrix[i][j] = 0;
                link[i][j].setD_tubeThickness(0.0);
                link[i][j].setL_tubeLength(INF);
                link[i][j].setQ_tubeFlow(0.0);
            }
        }
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

    //フィールドをすべてリセットする
    public void reset_random() {
        Arrays.fill(Q_Kirchhoff, 0.0);
        Arrays.fill(P_tubePressure, 0.0);
        Arrays.fill(Q_Kirchhoff_sinkExcept, 0.0);
        Arrays.fill(P_tubePressure_sinkExcept, 0.0);
        // 2次元配列の初期化
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                pressureCoefficient[i][j] = 0.0;  // すべての要素に0.0を設定
                D_tubeThickness_deltaT[i][j] = 0.0;
                Q_tubeFlow_sigmoidOutput[i][j] = 0.0;
                Flow_Capacity[i][j] = 0.0;
                tubeFlow[i][j] = 0;
                adjMatrix[i][j] = 0;
                link[i][j].setD_tubeThickness(0.0);
                link[i][j].setL_tubeLength(INF);
                link[i][j].setQ_tubeFlow(0.0);
            }
        }
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                double initD_tubeThickness = link[i][j].getInitD_tubeThickness();
                double initL_tubeLength = link[i][j].getInitL_tubeLength();
                link[i][j].setD_tubeThickness(initD_tubeThickness);
                link[i][j].setL_tubeLength(initL_tubeLength);
            }
        }
        for(int i = 0; i < node-1; i++){
            for(int j = 0; j < node-1; j++){
                pressureCoefficient_sinkExcept[i][j] = 0.0;
            }
        }
    }

    public void setLink(int node, BeaconCluster beaconList){
        this.node = node;
        this.beaconCluster = beaconList;

        //手動でリンクを決定
        for(int i=0; i<node; i++){
            for(int j=0; j<node; j++){
                link[i][j].setD_tubeThickness(0.0);
                link[i][j].setL_tubeLength(INF);
                //link.get(i).get(j).setDistance(Math.sqrt(Math.pow(beaconList.getBeacon(i).getX() - beaconList.getBeacon(j).getX(), 2) + Math.pow(beaconList.getBeacon(i).getY() - beaconList.getBeacon(j).getY(), 2)));
            }
        }

        link[0][1].setLink(beaconList.getBeacon(0), beaconList.getBeacon(1), 5);
        link[0][1].setD_tubeThickness(INIT_THICKNESS);
        link[0][1].setL_tubeLength(1);
        link[0][1].setDistance(250);
        link[0][1].setCongestionRate(INIT_RATE);
        adjMatrix[0][1] = 1;

        link[1][0].setLink(beaconList.getBeacon(1), beaconList.getBeacon(0), 5);
        link[1][0].setD_tubeThickness(INIT_THICKNESS);
        link[1][0].setL_tubeLength(1);
        link[1][0].setDistance(250);
        link[1][0].setCongestionRate(INIT_RATE);
        adjMatrix[1][0] = 1;

        link[0][2].setLink(beaconList.getBeacon(0), beaconList.getBeacon(2), 15);
        link[0][2].setD_tubeThickness(INIT_THICKNESS);
        link[0][2].setL_tubeLength(3);
        link[0][2].setDistance(750);
        link[0][2].setCongestionRate(INIT_RATE);
        adjMatrix[0][2] = 1;

        link[2][0].setLink(beaconList.getBeacon(2), beaconList.getBeacon(0), 15);
        link[2][0].setD_tubeThickness(INIT_THICKNESS);
        link[2][0].setL_tubeLength(3);
        link[2][0].setDistance(750);
        link[2][0].setCongestionRate(INIT_RATE);
        adjMatrix[2][0] = 1;

        link[0][3].setLink(beaconList.getBeacon(0), beaconList.getBeacon(3), 10);
        link[0][3].setD_tubeThickness(INIT_THICKNESS);
        link[0][3].setL_tubeLength(2);
        link[0][3].setDistance(500);
        link[0][3].setCongestionRate(INIT_RATE);
        adjMatrix[0][3] = 1;

        link[3][0].setLink(beaconList.getBeacon(3), beaconList.getBeacon(0), 10);
        link[3][0].setD_tubeThickness(INIT_THICKNESS);
        link[3][0].setL_tubeLength(2);
        link[3][0].setDistance(500);
        link[3][0].setCongestionRate(INIT_RATE);
        adjMatrix[3][0] = 1;

        link[1][4].setLink(beaconList.getBeacon(1), beaconList.getBeacon(4), 10);
        link[1][4].setD_tubeThickness(INIT_THICKNESS);
        link[1][4].setL_tubeLength(2);
        link[1][4].setDistance(500);
        link[1][4].setCongestionRate(INIT_RATE);
        adjMatrix[1][4] = 1;

        link[4][1].setLink(beaconList.getBeacon(4), beaconList.getBeacon(1), 10);
        link[4][1].setD_tubeThickness(INIT_THICKNESS);
        link[4][1].setL_tubeLength(2);
        link[4][1].setDistance(500);
        link[4][1].setCongestionRate(INIT_RATE);
        adjMatrix[4][1] = 1;

        link[2][3].setLink(beaconList.getBeacon(2), beaconList.getBeacon(3), 5);
        link[2][3].setD_tubeThickness(INIT_THICKNESS);
        link[2][3].setL_tubeLength(1);
        link[2][3].setDistance(250);
        link[2][3].setCongestionRate(INIT_RATE);
        adjMatrix[2][3] = 1;

        link[3][2].setLink(beaconList.getBeacon(3), beaconList.getBeacon(2), 5);
        link[3][2].setD_tubeThickness(INIT_THICKNESS);
        link[3][2].setL_tubeLength(1);
        link[3][2].setDistance(250);
        link[3][2].setCongestionRate(INIT_RATE);
        adjMatrix[3][2] = 1;

        link[2][5].setLink(beaconList.getBeacon(2), beaconList.getBeacon(5), 15);
        link[2][5].setD_tubeThickness(INIT_THICKNESS);
        link[2][5].setL_tubeLength(3);
        link[2][5].setDistance(750);
        link[2][5].setCongestionRate(INIT_RATE);
        adjMatrix[2][5] = 1;

        link[5][2].setLink(beaconList.getBeacon(5), beaconList.getBeacon(2), 15);
        link[5][2].setD_tubeThickness(INIT_THICKNESS);
        link[5][2].setL_tubeLength(3);
        link[5][2].setDistance(750);
        link[5][2].setCongestionRate(INIT_RATE);
        adjMatrix[5][2] = 1;

        link[3][5].setLink(beaconList.getBeacon(3), beaconList.getBeacon(5), 10);
        link[3][5].setD_tubeThickness(INIT_THICKNESS);
        link[3][5].setL_tubeLength(2);
        link[3][5].setDistance(500);
        link[3][5].setCongestionRate(INIT_RATE);
        adjMatrix[3][5] = 1;

        link[5][3].setLink(beaconList.getBeacon(5), beaconList.getBeacon(3), 10);
        link[5][3].setD_tubeThickness(INIT_THICKNESS);
        link[5][3].setL_tubeLength(2);
        link[5][3].setDistance(500);
        link[5][3].setCongestionRate(INIT_RATE);
        adjMatrix[5][3] = 1;

        link[4][5].setLink(beaconList.getBeacon(4), beaconList.getBeacon(5), 15);
        link[4][5].setD_tubeThickness(INIT_THICKNESS);
        link[4][5].setL_tubeLength(3.3);
        link[4][5].setDistance(850);
        link[4][5].setCongestionRate(INIT_RATE);
        adjMatrix[4][5] = 1;

        link[5][4].setLink(beaconList.getBeacon(5), beaconList.getBeacon(4), 15);
        link[5][4].setD_tubeThickness(INIT_THICKNESS);
        link[5][4].setL_tubeLength(3.3);
        link[5][4].setDistance(850);
        link[5][4].setCongestionRate(INIT_RATE);
        adjMatrix[5][4] = 1;

    }


    // nodeConfigureメソッドの追加
    public void setLink_random(int node, BeaconCluster beaconList) {
        this.node = node;
        this.beaconCluster = beaconList;
        double maxDistance = sqrt(2);  // 最大距離

        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                link[i][j].setD_tubeThickness(0.0);
                link[i][j].setInitD_tubeThickness(0.0);
                link[i][j].setL_tubeLength(INF);
                link[i][j].setInitL_tubeLength(INF);
                if (i != j) {
                    link[i][j].setDistance(sqrt(Math.pow(beaconList.getBeacon(i).getX() - beaconList.getBeacon(j).getX(), 2) + Math.pow(beaconList.getBeacon(i).getY() - beaconList.getBeacon(j).getY(), 2)));
                }
            }
        }

        for (int i = 0; i < node; i++) {
            for (int j = i + 1; j < node; j++) {
                if (0.0 < link[i][j].getDistance() && link[i][j].getDistance() <= THRESHOLD_1) {
                    initializeLink(link[i][j], beaconList.getBeacon(i), beaconList.getBeacon(j));
                    initializeLink(link[j][i], beaconList.getBeacon(j), beaconList.getBeacon(i));
                    adjMatrix[i][j] = adjMatrix[j][i] = 1;
                }
            }
        }
    }

    // リンク初期化用のメソッド
    private static void initializeLink(Link link, Beacon start, Beacon end) {
        link.setLink(start, end, 3);
        link.setD_tubeThickness(INIT_THICKNESS);
        link.setInitD_tubeThickness(INIT_THICKNESS);
        link.setL_tubeLength(link.getDistance() * 10);
        link.setInitL_tubeLength(link.getDistance() * 10);
        link.setDistance(link.getDistance() * 1000);
        link.setCongestionRate(INIT_RATE);
    }

    public void nodeConfigureToPajek(String NET_file, Client client, BeaconCluster beaconList) {
        double maxDistance = sqrt(2);  // 最大距離 sqrt(2)

        //sourceとdistを取得
        Beacon source = client.getFlow().getSource();
        Beacon dist = client.getFlow().getDestination();

        // ファイル出力処理
        try (FileWriter writer = new FileWriter(new File(NET_file))) {
            writer.write("*Vertices\t" + node + "\n");
            for (int i = 0; i < node; i++) {

                if (i == source.getId()) {
                    fig_SOURCE = true;
                }

                if (i == dist.getId()) {
                    fig_DIST = true;
                }

                if (fig_SOURCE || fig_DIST) {
                    writer.write(String.format("%d \"%d\" %.4f %.4f ic Black\n", i + 1, i + 1, beaconList.getBeacon(i).getX(), beaconList.getBeacon(i).getY()));
                } else {
                    writer.write(String.format("%d \"%d\" %.4f %.4f ic White\n", i + 1, i + 1, beaconList.getBeacon(i).getX(), beaconList.getBeacon(i).getY()));
                }
                fig_SOURCE = false;
                fig_DIST = false;
            }
            writer.write("*Arcs\n*Edges\n");

            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (i != j && link[i][j].getL_tubeLength() != INF) {
                        writer.write(String.format("%d %d 1\n", i + 1, j + 1));
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // setTopologyColorメソッドの追加
    public void outputToPajek(Client client, double eps, double Q_allFlow, int ct) throws IOException {

        Beacon source = client.getFlow().getSource();
        Beacon dist = client.getFlow().getDestination();

        // ディレクトリパスを作成
        //String dirPath = "src/result/EPS/pajek/result" + runCounter;
        String dirPath = "src/result/PS/pajek/result" + runCounter;
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
                    if (link[i][j].getL_tubeLength() != INF) {
                        double flow = link[i][j].getQ_tubeFlow();
                        if (link[i][j].getDistance() > 0) {
                            if (flow > 0 && flow <= eps) {
                                // Small flow, no color
                            } else if (flow > eps && flow <= THRESHOLD_1) {
                                writer.write(String.format("%d %d 1 c Blue\n", i + 1, j + 1));
                            } else if (flow > THRESHOLD_1 && flow <= THRESHOLD_2) {
                                writer.write(String.format("%d %d 2 c Green\n", i + 1, j + 1));
                            } else if (flow > THRESHOLD_2 && flow <= Q_allFlow) {
                                writer.write(String.format("%d %d 3 c Red\n", i + 1, j + 1));
                            }
                        }
                    }
                }
            }
        }
    }

    //Excelファイルに各リンクの流量を出力するメソッド
    public void outputToExcel(Client client, int ct) throws IOException {

        // ディレクトリパスを作成
        //String dirPath = "src/result/EPS/excel/result" + runCounter;
        String dirPath = "src/result/PS/excel/result" + runCounter;
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
                    if (link[i][j].getL_tubeLength() != INF) {
                        writer.write(String.format("%d,%d,%.4f\n", i, j, link[i][j].getQ_tubeFlow()));
                    }
                }
            }
        }
    }

    public void outputToflow(Client client, int ct) throws IOException {
        //String dirPath = "src/result/EPS/flow/result" + runCounter;
        String dirPath = "src/result/PS/flow/result" + runCounter;
        //String dirPath = "src/result/Dijkstra/flow/result" + runCounter;
        // ファイル名を作成
        String filename = dirPath + "/test_topology_flow.txt";

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
                writer.write("ct,v0->v1,v0->v2,v0->v3,v1->v4,v2->v3,v2->v5,v3->v5,v4->v5\n");
            }
            if (runCounter == 0 || runCounter == 1) {
                if (file.length() == 0) { // ファイルが空の場合のみヘッダーを書き込む
                    writer.write("ct,v0->v1,v0->v2,v0->v3,v1->v4,v2->v3,v2->v5,v3->v5,v4->v5\n");
                }

                // 経路ごとの情報を1行にまとめて追記
                writer.write(String.format("%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f\n", ct, link[0][1].getQ_tubeFlow(), link[0][2].getQ_tubeFlow(), link[0][3].getQ_tubeFlow(), link[1][4].getQ_tubeFlow(), link[2][3].getQ_tubeFlow(), link[2][5].getQ_tubeFlow(), link[3][5].getQ_tubeFlow(), link[4][5].getQ_tubeFlow()));

            } else if (runCounter == 2) {
                if (file.length() == 0) { // ファイルが空の場合のみヘッダーを書き込む
                    writer.write("ct,v2->v0,v2->v3,v2->v5,v0->v1,v3->v0,v3->v5,v1->v4,v5->v4\n");
                }

                // 経路ごとの情報を1行にまとめて追記
                writer.write(String.format("%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f\n", ct, link[2][0].getQ_tubeFlow(), link[2][3].getQ_tubeFlow(), link[2][5].getQ_tubeFlow(), link[0][1].getQ_tubeFlow(), link[3][0].getQ_tubeFlow(), link[3][5].getQ_tubeFlow(), link[3][5].getQ_tubeFlow(), link[5][4].getQ_tubeFlow()));
            }
        }
    }

    // 経路ごとのUAV数をExcel形式で出力するメソッド
    public void outputRouteToExcel(Client client, int ct) throws IOException {
        // ディレクトリパスを作成
        //String dirPath = "src/result/EPS/rute/result" + runCounter;
        String dirPath = "src/result/PS/rute/result" + runCounter;
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

    //txtファイルに管の長さ，管の太さ，管の容量を出力するメソッド
    public void outputToTxt(Client client, int ct) throws IOException {
        // ディレクトリパスを作成
        //String dirPath = "src/result/EPS/txt/result" + runCounter;
        String dirPath = "src/result/PS/txt/result" + runCounter;
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
                    if (link[i][j].getL_tubeLength() != INF) {
                        writer.write(String.format("%d,%d,%.4f,%.4f,%.4f\n", i, j, link[i][j].getL_tubeLength(), link[i][j].getD_tubeThickness(), link[i][j].getCapacity()));
                    }
                }
            }
        }
    }

    public static void outputRoute(Uav currentUAV, String method) {
        //String dirPath = "src/result/EPS/path";
        String dirPath = "src/result/PS/path";
        //String dirPath = "src/result/Dijkstra/path";
        String filePath = dirPath + "/flight_path.txt";

        // ディレクトリが存在しない場合は作成
        File dir1 = new File(dirPath);
        if (!dir1.exists()) {
            dir1.mkdirs();
        }

        try (FileWriter writer = new FileWriter(filePath, true)) {
            // ファイルが空の場合、ヘッダーを追加
            File file = new File(filePath);
            if (file.length() == 0) {
                writer.write("clientId,UAVId,flightPath,method\n");
            }
            // UAVの飛行経路を取得し、"-" 区切りの文字列に変換
            String pathString = Arrays.stream(currentUAV.getPath()).mapToObj(String::valueOf).collect(Collectors.joining("-"));
            // 行を書き込み
            writer.write(String.format("%d,%d,%s,%s\n", currentUAV.getClientId(), currentUAV.getId(), pathString, method));
        } catch (IOException e) {
            System.err.println("ファイル書き込みエラー: " + e.getMessage());
        }
    }


    // UAVを移動させるメソッド
    public static void flyUAV(ClientController clientcontroller, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue) {
        int[][] FlyingUAV = new int[node][node];

        // 飛行中のUAVを移動させる
        int queueSize = flyingUavQueue.size();
        for (int i = 0; i < queueSize; i++) {
            Uav uav = flyingUavQueue.poll();
            double flightDistance = uav.getFlightTime() * uav.getSpeed();
            int[] path = uav.getPath();

            double totalPathDistance = 0.0;
            for (int k = 0; k < path.length - 1; k++) {
                int startNode = path[k];
                int endNode = path[k + 1];
                totalPathDistance += link[startNode][endNode].getDistance();
            }

            if (flightDistance >= totalPathDistance) {
                if (uav.isFlying()) {
                    uav.cancelTimer();
                }else{
                    System.out.println("要修正0");
                }
                clientcontroller.getClient(uav.getClientId() - 1).incrementFinishFlyingCounter();

                // ログの保存
                saveFlightData(clientcontroller, uav, totalPathDistance);

            } else {
                double traveledDistance = 0.0;
                for (int k = 0; k < path.length - 1; k++) {
                    int startNode = path[k];
                    int endNode = path[k + 1];
                    double linkLength = link[startNode][endNode].getDistance();

                    if (traveledDistance + linkLength >= flightDistance) {
                        if (link[startNode][endNode] != uav.getFlyingLink()) {
                            if (link[startNode][endNode].getCapacity() > 0) {
                                uav.setFlyingLink(link[startNode][endNode]);
                                FlyingUAV[startNode][endNode]++;
                                System.out.println("client " + uav.getClientId() + " :UAV " + uav.getId() + " が " + startNode + " → " + endNode + " へ移動");
                                flyingUavQueue.add(uav);
                            } else {
                                if (uav.isFlying()) {
                                    uav.stopTimer();
                                } else {
                                    System.out.println("要修正1: client " + uav.getClientId() + " :UAV " + uav.getId() + " が飛行中でないのに stopTimer() が呼ばれました");
                                }
                                if (!uav.isWaiting()) {
                                    uav.startWaitingTimer();
                                } else {
                                    System.out.println("要修正2: client " + uav.getClientId() + " :UAV " + uav.getId() + " がすでに待機状態");
                                }
                                uav.setStayedBeaconId(startNode);
                                beaconCluster.getBeacon(startNode).addUav(uav);
                                beaconCluster.getBeacon(startNode).incrementWaitingUavCount();
                                uavQueue.add(uav);
                            }
                        } else {
                            FlyingUAV[startNode][endNode]++;
                            flyingUavQueue.add(uav);
                        }
                        break;
                    } else {
                        traveledDistance += linkLength;
                    }
                }
            }
        }
        // 容量の更新
        updateCapacity(FlyingUAV);

        // 待機中のUAVを移動させる
        processWaitingUAVs(uavQueue, flyingUavQueue, FlyingUAV);

    }

    // 待機中のUAVを処理するメソッド
    private static void processWaitingUAVs(Queue<Uav> uavQueue, Queue<Uav> flyingUavQueue,int[][] FlyingUAV) {
        int waitQueueSize = uavQueue.size();
        for (int i = 0; i < waitQueueSize; i++) {
            Uav uav = uavQueue.poll();
            int[] path = uav.getPath();

            int startNode = uav.getStayedBeaconId();

            int nextNode = -1;
            if(uav.getStayedBeaconId() != -1) {
                for (int j = 0; j < path.length - 1; j++) {
                    if (path[j] == startNode) {
                        nextNode = path[j + 1];
                        break;
                    }
                }
            }else{
                System.out.println("要修正4: client " + uav.getClientId()+ " :UAV " + uav.getId() + " が待機中のビーコンIDを取得できませんでした");
            }

            if (nextNode != -1) {
                if (FlyingUAV[startNode][nextNode] < link[startNode][nextNode].getCapacity()) {
                    FlyingUAV[startNode][nextNode]++;
                    if (uav.isWaiting()) {
                        uav.stopWaitingTimer();
                    } else {
                        System.out.println("要修正3: client " + uav.getClientId() + " :UAV " + uav.getId() + " は待機していないのに stopWaitingTimer() が呼ばれました");
                    }
                    beaconCluster.getBeacon(startNode).removeUav(uav);
                    beaconCluster.getBeacon(startNode).decrementWaitingUavCount();
                    uav.startTimer();
                    uav.setFlyingLink(link[startNode][nextNode]);
                    uav.setStayedBeaconId(-1);
                    flyingUavQueue.add(uav);
                } else {
                    uavQueue.add(uav);
                    System.out.println("client " + uav.getClientId() + " :UAV " + uav.getId() + " は容量不足のため待機継続 (" + startNode + " -> " + nextNode + ")");
                }
            } else {
                System.out.println("client " + uav.getClientId() + " :UAV " + uav.getId() + " は移動できるリンクがないため待機継続");
                uavQueue.add(uav);
            }
        }
        // 容量の更新
        updateCapacity(FlyingUAV);
    }
    // 管の容量を更新するメソッド
    public static void updateCapacity(int[][] FlyingUAV) {
        //Capacityを初期値に戻す
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    link[i][j].setCapacity(link[i][j].getInitCapacity());
                    link[j][i].setCapacity(link[j][i].getInitCapacity());
                }
            }
        }
        // 各リンクの初期容量から飛行中のUAV分を減少
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF && FlyingUAV[i][j] > 0) {
                    double newCapacity = link[i][j].getCapacity() - FlyingUAV[i][j];
                    link[i][j].setCapacity(Math.max(0, newCapacity));
                    link[j][i].setCapacity(Math.max(0, newCapacity));
                }
            }
        }
    }

    // フライトデータを保存するメソッド
    private static void saveFlightData(ClientController clientcontroller, Uav uav, double totalPathDistance) {
        //String dirPath = "src/result/EPS/time";
        //String dirPath = "src/result/PS/time";
        String dirPath = "src/result/Dijkstra/time";
        String filePath = dirPath + "/flight_times.csv";

        File dir1 = new File(dirPath);
        if (!dir1.exists()) {
            dir1.mkdirs();
        }

        long flightTime = clientcontroller.getFlightTime();
        long UAV_flightTime = uav.getFlightTime();
        long UAV_waitingTime = uav.getWaitingTime();

        try (FileWriter writer = new FileWriter(filePath, true)) {
            File file = new File(filePath);
            if (file.length() == 0) {
                writer.write("source,dist,passedTime,UAV_flightTime,UAV_waitingTime,ClientID,UAVID,speed,distance,path\n");
            }
            String pathString = Arrays.stream(uav.getPath()).mapToObj(String::valueOf).collect(Collectors.joining("-"));
            writer.write(String.format("%d,%d,%d,%d,%d,%d,%d,%f,%f,%s\n",
                    uav.getSource().getId(), uav.getDistination().getId(), flightTime, UAV_flightTime, UAV_waitingTime,
                    uav.getClientId(), uav.getId(), uav.getSpeed(), totalPathDistance, pathString));
        } catch (IOException e) {
            System.err.println("ファイル書き込みエラー: " + e.getMessage());
        }
    }


    public void run_Dijkstra(Client client, ClientController clientcontroller, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue)throws IOException{
        int i, j;

        if (runCounter != 0) {
            //更新メソッドを呼び出す
            reset();
            //reset_random();
        }

        //passedClientが空でない場合，UAVFlySchedulerを停止
        if (!flyingUavQueue.isEmpty()) {
            //ここではクライアントタイマーはすでに停止している
            UAVFlyScheduler.stopFlyUAVUpdates(clientcontroller);
        }

        // UAV一台ずつに経路を配列として受け渡し、飛行経路をすべてのUAVに割り当てる
        // 初期設定として、Flow_CapacityにQ_tubeFlowを代入,各リンクを流れる流量の整数値をtubeFlowに追加
        System.out.println("breakout point");
        for (i = 0; i < node; i++) {
            for (j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    adjMatrix[i][j] = 1;
                }
            }
        }
        // Dijkstraメソッドを呼び出して、結果の配列を受け取る
        int[] path = Dijkstra(client);

        // スタートノード、ゴールノード、必要なUAV台数を取得
        int requiredUAVs = (int) client.getFlow().getTheNumberOfUAV();

        if (runCounter != 0) {
            //UAVFlySchedulerを開始
            UAVFlyScheduler.startFlyUAVUpdates(flyingUavQueue, uavQueue, clientcontroller);
        }
        // 実際のUAVに経路を割り当てるためのメイン処理
        runUAVFlow_Dijkstra(client ,path, flyingUavQueue, uavQueue, requiredUAVs);

        //client.startTimer();
        runCounter++;
    }

    public int[] Dijkstra(Client client) {
        // 出発地と目的地のノードIDを取得
        int source = client.getFlow().getSource().getId();
        int destination = client.getFlow().getDestination().getId();

        // グローバル変数として利用可能なノード数
        int numNodes = node;

        // 必要な配列を定義
        double[] minDist = new double[node];    // 各ノードへの最短距離
        int[] minHops = new int[node];          // 各ノードへのホップ数
        boolean[] visited = new boolean[node]; // 訪問済みフラグ
        int[] previous = new int[node];         // 経路復元用の配列
        int[] unvisited = new int[node];        // 未訪問ノードのリスト

        // 初期化
        for (int i = 0; i < numNodes; i++) {
            minDist[i] = Double.POSITIVE_INFINITY; // 初期距離を無限大に
            minHops[i] = Integer.MAX_VALUE;        // 初期ホップ数を最大値に
            visited[i] = false;                    // 全ノード未訪問
            previous[i] = -1;                      // 前のノードを-1で初期化
            unvisited[i] = i;                      // 未訪問ノードリストを初期化
        }
        minDist[source] = 0;                        // 出発ノードの距離は0
        minHops[source] = 0;                        // 出発ノードのホップ数は0

        // Dijkstra法のメインループ
        while (true) {
            // 最短距離で未訪問のノードを探索
            int currentNode = -1;
            double shortestDistance = Double.POSITIVE_INFINITY;
            for (int i = 0; i < numNodes; i++) {
                if (!visited[unvisited[i]] && minDist[unvisited[i]] < shortestDistance) {
                    currentNode = unvisited[i];
                    shortestDistance = minDist[currentNode];
                }
            }

            // 未訪問ノードが見つからない場合、または目的地に到達した場合は終了
            if (currentNode == -1 || currentNode == destination) {
                break;
            }

            // 現在のノードを訪問済みにマーク
            visited[currentNode] = true;

            // 隣接ノードを探索
            for (int neighbor = 0; neighbor < numNodes; neighbor++) {
                if (adjMatrix[currentNode][neighbor] == 1 && !visited[neighbor]) {
                    // 距離を計算
                    double newDist = minDist[currentNode] + link[currentNode][neighbor].getDistance();
                    int newHops = minHops[currentNode] + 1;

                    // 条件に応じて更新
                    if (newDist < minDist[neighbor] ||
                            (Double.compare(newDist, minDist[neighbor]) == 0 && newHops < minHops[neighbor])) {
                        minDist[neighbor] = newDist;
                        minHops[neighbor] = newHops;
                        previous[neighbor] = currentNode;
                    }
                }
            }
        }

        // 経路を復元（逆順で格納し直す）
        int[] path = new int[numNodes];
        int pathIndex = 0; // 経路の現在のインデックス
        for (int at = destination; at != -1; at = previous[at]) {
            path[pathIndex++] = at;
        }

        // 出発ノードから到達できない場合
        if (pathIndex == 1 && path[0] != source) {
            return new int[0]; // 空の配列を返す
        }

        // 経路を逆順にする
        int[] result = new int[pathIndex];
        for (int i = 0; i < pathIndex; i++) {
            result[i] = path[pathIndex - i - 1];
        }
        return result;
    }

    public void runUAVFlow_Dijkstra(Client client, int[] path, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, int requiredUAVs) {
        // 最小Capacityを計算する
        int u = path[0];
        int v = path[1];
        // UAVの飛行をスケジュールするためのスレッドプールを作成
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);


        double minCapacity = link[u][v].getCapacity();
        int flow_count = 0;

        // UAVがrequiredUAVsより少ない場合はすべてのUAVに経路を割り当て
        for (int f = 0; f < requiredUAVs; f++) {
            int currentUAVIndex = UAV_count + f;
            Uav currentUAV = client.getFlow().getUav(currentUAVIndex);

            if (flow_count < minCapacity) {
                // 2秒ごとに飛行開始
                scheduler.schedule(() -> {
                    currentUAV.setPath(path);
                    currentUAV.startTimer();
                    currentUAV.setFlyingLink(link[u][v]);
                    currentUAV.setPassedLink(link[u][v]);
                    flyingUavQueue.add(currentUAV);
                    link[u][v].decrementCapacity();
                }, f * 2, TimeUnit.SECONDS);
                System.out.println("UAV " + currentUAV.getId() + " is flying from " + u + " to " + v);

                flow_count++;
            } else {
                // UAVを待機させる処理（変更なし）
                currentUAV.setPath(path);
                currentUAV.startWaitingTimer();
                currentUAV.setStayedBeaconId(u);
                beaconCluster.getBeacon(u).addUav(currentUAV);
                beaconCluster.getBeacon(u).incrementWaitingUavCount();
                uavQueue.add(currentUAV);
                System.out.println("UAV " + currentUAV.getId() + " is waiting at " + u);
            }
        }
    }

    public void run_PS(Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, ClientController clientcontroller, int numLoop)throws IOException{
        int nodeExcept = node - 1;
        int ct = 0;
        double eps = 1e-10;
        int testIter = 10;
        int a=0, b, i, j;
        double degeneracyEffect = 1.0;
        int source = client.getFlow().getSource().getId();
        int dist = client.getFlow().getDestination().getId();

        if(runCounter != 0){
            //更新メソッドを呼び出す
            reset();
            //reset_random();
        }

        //Queueが空でない場合，UAVFlySchedulerを停止
        if (!flyingUavQueue.isEmpty()) {
            //ここではクライアントタイマーはすでに停止している
            UAVFlyScheduler.stopFlyUAVUpdates(clientcontroller);
        }

        while (ct < numLoop) {
            //sourceとdistを取得
            Q_Kirchhoff[source] = client.getFlow().getTheNumberOfUAV();
            Q_Kirchhoff[dist] = client.getFlow().getTheNumberOfUAV() * NEG;

            for(i = 0; i < node; i++) //ノード数だけ繰り返す
            {
                pressureCoefficient[i][i] = 0.0; //圧力係数を初期化
                if(i != source && i != dist) //ソースノードとシンクノード以外の場合
                {
                    Q_Kirchhoff[i] = 0.0; //全流量を初期化
                }
            }

            // 圧力勾配の導出
            for (i = 0; i < node; i++) {
                for (j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF){//ノードiとノードjが直接接続されている場合
                        if (i != j) { // iとjが異なる場合
                            pressureCoefficient[i][j] = link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength() * NEG;// 圧力係数を計算
                        }
                    }
                }
            }

            int k = 0;
            for (i = 0; i < node; i++) {
                for (j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) { // ノードiとノードjが直接接続されている場合
                        pressureCoefficient[k][k] = pressureCoefficient[k][k] + link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength();// 対角成分を加算
                    }
                }
                k++;
            }

            for (i = 0, a = 0; i < node && a < nodeExcept; i++, a++) {
                if (i == dist && dist != node) { // iがシンクノードである場合
                    i++; // シンクノードをスキップ
                }
                for (j = 0, b = 0; j < node && b < nodeExcept; j++, b++) {
                    if (j == dist) { // jがシンクノードの場合
                        j++; // シンクノードをスキップ
                    }
                    pressureCoefficient_sinkExcept[a][b] = pressureCoefficient[i][j];// シンクノードを除いた圧力係数行列を作成
                }
            }

            // sinkExcept 配列の準備
            for (a = 0, i = 0; i < node; i++) {
                if (i != dist) {
                    Q_Kirchhoff_sinkExcept[a] = Q_Kirchhoff[i];
                    a++;
                }
            }


            if(ICCG.iccg(pressureCoefficient_sinkExcept, Q_Kirchhoff_sinkExcept, P_tubePressure_sinkExcept, nodeExcept, testIter, eps) == 0){
                break;
            }

            //圧力値の反映
            for (a = 0, i = 0; i < node; i++) {
                if (i == dist) {
                    P_tubePressure[i] = 0.0;
                } else {
                    P_tubePressure[i] = P_tubePressure_sinkExcept[a];
                    a++;
                }
            }

            // 流量の計算
            for (i = 0; i < node; i++) {
                for (j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) {
                        link[i][j].setQ_tubeFlow((link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength()) * (P_tubePressure[i] - P_tubePressure[j]));
                    }
                }
            }

            // シグモイド関数
            for (i = 0; i < node; i++) {
                for (j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) {
                        Q_tubeFlow_sigmoidOutput[i][j] = (Math.pow(Math.abs(link[i][j].getQ_tubeFlow()), GAMMA)) / (1 + Math.pow(Math.abs(link[i][j].getQ_tubeFlow()), GAMMA));
                    }
                }
            }

            // チューブ厚の更新
            for (i = 0; i < node; i++) {
                for (j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) {
                        double deltaThickness = (Q_tubeFlow_sigmoidOutput[i][j] - (degeneracyEffect * link[i][j].getD_tubeThickness())) * DELTA_TIME;
                        D_tubeThickness_deltaT[i][j] = deltaThickness;
                        double newThickness = link[i][j].getD_tubeThickness() + deltaThickness;
                        link[i][j].setD_tubeThickness(newThickness);
                    }
                }
            }

            // 結果のプロット
            if ((ct + 1) % PLOT == 0) {
                System.out.println("Iteration: " + (ct+1));
                outputToPajek(client, eps, client.getFlow().getTheNumberOfUAV(), ct);
                outputToExcel(client, ct);
                outputToTxt(client, ct);
            }
            if(ct % PLOT_2 == 0 || ct == numLoop - 1){
                outputRouteToExcel(client, ct);
            }

            ct++;
            // 最後のループの場合に実行する処理
            // UAV一台ずつに経路を配列として受け渡し、飛行経路をすべてのUAVに割り当てる
            if (ct == numLoop) {
                // 初期設定として、Flow_CapacityにQ_tubeFlowを代入,各リンクを流れる流量の整数値をtubeFlowに追加
                System.out.println("breakout point");
                System.out.println("requiredUAVs: " + client.getFlow().getTheNumberOfUAV());
                for (i = 0; i < node; i++) {
                    for (j = 0; j < node; j++) {
                        if(link[i][j].getL_tubeLength() != INF) {
                            adjMatrix[i][j] = 1;
                            if(link[i][j].getQ_tubeFlow() > 0) {
                                Flow_Capacity[i][j] = link[i][j].getQ_tubeFlow();
                                int flow = (int) Math.floor(Flow_Capacity[i][j]);
                                tubeFlow[i][j] = flow;
                            }
                        }
                    }
                }


                // スタートノード、ゴールノード、必要なUAV台数を取得
                int startNode = client.getFlow().getSource().getId();
                int goalNode = client.getFlow().getDestination().getId();
                int requiredUAVs = (int) client.getFlow().getTheNumberOfUAV();

                if(runCounter != 0) {
                    //UAVFlySchedulerを開始
                    UAVFlyScheduler.startFlyUAVUpdates(flyingUavQueue, uavQueue, clientcontroller);
                }
                // 実際のUAVに経路を割り当てるためのメイン処理
                runUAVFlow(startNode, goalNode, requiredUAVs, client, flyingUavQueue, uavQueue);
            }
        }
        //client.startTimer();
        runCounter++;
    }


    //PSを実行するメソッド
    public void run_EPS(Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, ClientController clientcontroller, int numLoop) throws IOException {
        int nodeExcept = node - 1;
        int ct = 0;
        double eps = 1e-10;
        int testIter = 10;
        int a=0, b, i, j;
        double degeneracyEffect = 1.0;

        if(runCounter != 0){
            //更新メソッドを呼び出す
            reset();
            //reset_random();
        }

        //passedClientが空でない場合，UAVFlySchedulerを停止
        if (!flyingUavQueue.isEmpty()) {
            //ここではクライアントタイマーはすでに停止している
            UAVFlyScheduler.stopFlyUAVUpdates(clientcontroller);
        }

        while (ct < numLoop) {
            //sourceとdistを取得
            Beacon source = client.getFlow().getSource();
            Beacon dist = client.getFlow().getDestination();
            Q_Kirchhoff[source.getId()] = client.getFlow().getTheNumberOfUAV();
            Q_Kirchhoff[dist.getId()] = client.getFlow().getTheNumberOfUAV() * NEG;

            for(i=0; i<node; i++){
                pressureCoefficient[i][i] = 0.0;  // i番目の行、i番目の列に0.0を設定

                if(i == source.getId() || i == dist.getId()){
                    fig_DIST = true;
                }

                if(!fig_DIST){
                    Q_Kirchhoff[i] = 0.0;
                }
                fig_DIST = false;
            }

            // 圧力勾配の導出
            for (i = 0; i < node; i++) {
                for (j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF){//ノードiとノードjが直接接続されている場合
                        if (i != j) { // iとjが異なる場合
                            pressureCoefficient[i][j] = link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength() * NEG;// 圧力係数を計算
                        }
                    }
                }
            }

            int k = 0;
            for (i = 0; i < node; i++) {
                for (j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) { // ノードiとノードjが直接接続されている場合
                        pressureCoefficient[k][k] = pressureCoefficient[k][k] + link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength();// 対角成分を加算
                    }
                }
                k++;
            }

            if(BiCGSTAB.BiCGSTAB(pressureCoefficient, Q_Kirchhoff, P_tubePressure, node, testIter, eps) == 0){
                break;
            }

            // 流量の計算
            for (i = 0; i < node; i++) {
                for (j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) {
                        link[i][j].setQ_tubeFlow((link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength()) * (P_tubePressure[i] - P_tubePressure[j]));
                    }
                }
            }

            // シグモイド関数
            for (i = 0; i < node; i++) {
                for (j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) {
                        Q_tubeFlow_sigmoidOutput[i][j] = Math.pow(Math.abs(link[i][j].getQ_tubeFlow()), GAMMA) / (1 + Math.pow(Math.abs(link[i][j].getQ_tubeFlow()), GAMMA));

                    }
                }
            }

            // チューブ厚の更新
            for (i = 0; i < node; i++) {
                for (j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) {
                        double deltaThickness = (Math.abs(link[i][j].getQ_tubeFlow()) - (degeneracyEffect * link[i][j].getD_tubeThickness())) * DELTA_TIME;
                        D_tubeThickness_deltaT[i][j] = deltaThickness;

                    }
                }
            }

            for(i=0; i<node; i++){
                for(j=0; j<node; j++){
                    {
                        link[i][j].setD_tubeThickness(link[i][j].getD_tubeThickness() + (D_tubeThickness_deltaT[i][j]) * Math.tanh((link[i][j].getCapacity() - Math.abs(link[i][j].getQ_tubeFlow())) * coefficient_tanh));
                    }
                }
            }
            // 結果のプロット

            if ((ct + 1) % PLOT == 0) {
                System.out.println("Iteration: " + (ct+1));
                outputToPajek(client, eps, client.getFlow().getTheNumberOfUAV(), ct);
                outputToExcel(client, ct);
                outputToTxt(client, ct);
            }

            /**
            if(ct % PLOT_2 == 0 || ct == numLoop - 1){
                outputRouteToExcel(client, ct);
                outputToflow(client, ct);
            }
             */


            ct++;
            // 最後のループの場合に実行する処理
            // UAV一台ずつに経路を配列として受け渡し、飛行経路をすべてのUAVに割り当てる
            if (ct == numLoop) {
                // 初期設定として、Flow_CapacityにQ_tubeFlowを代入,各リンクを流れる流量の整数値をtubeFlowに追加
                System.out.println("breakout point");
                for (i = 0; i < node; i++) {
                    for (j = 0; j < node; j++) {
                        if(link[i][j].getL_tubeLength() != INF) {
                            adjMatrix[i][j] = 1;
                            if(link[i][j].getQ_tubeFlow() > 0) {
                                Flow_Capacity[i][j] = link[i][j].getQ_tubeFlow();
                                int flow = (int) Math.floor(Flow_Capacity[i][j]);
                                tubeFlow[i][j] = flow;
                            }
                        }
                    }
                }


                // スタートノード、ゴールノード、必要なUAV台数を取得
                int startNode = client.getFlow().getSource().getId();
                int goalNode = client.getFlow().getDestination().getId();
                int requiredUAVs = (int) client.getFlow().getTheNumberOfUAV();

                if(runCounter != 0) {
                    //UAVFlySchedulerを開始
                    UAVFlyScheduler.startFlyUAVUpdates(flyingUavQueue, uavQueue, clientcontroller);
                }
                // 実際のUAVに経路を割り当てるためのメイン処理
                runUAVFlow(startNode, goalNode, requiredUAVs, client, flyingUavQueue, uavQueue);
            }
        }
        //client.startTimer();
        runCounter++;
    }


    //深さ優先探索(DFS)
    private int explorePath(int startNode, int currentNode, int goalNode, int[] path, int pathIndex, int passedFlow) {
        // ゴールノードに到達したら流量を返して経路探索を終了
        if (currentNode == goalNode) {
            maxPathIndex = pathIndex; // **修正: 正しい最大経路長を記録**
            return passedFlow;
        }

        // `maxPathIndex` を `pathIndex` と比較して更新
        if (pathIndex > maxPathIndex) {
            maxPathIndex = pathIndex;
        }

        // 次のノードを探索し、経路を進む
        for (int nextNode = 0; nextNode < node; nextNode++) {
            if (adjMatrix[currentNode][nextNode] == 1 && tubeFlow[currentNode][nextNode] > 0) {
                int flow = tubeFlow[currentNode][nextNode]; // 現在ノード間の流量

                // 最小フローの計算
                int prevMinFlow = min_Flow;  // **バックトラックのために保存**
                min_Flow = (passedFlow == 0) ? flow : Math.min(min_Flow, flow);

                // 経路に次のノードを追加
                path[pathIndex] = nextNode;

                // ゴールに到達した場合、`tubeFlow` を減算
                if (nextNode == goalNode && min_Flow > 0) {
                    int nodeA = startNode;
                    for (int i = 0; i <= pathIndex; i++) {
                        int nodeB = path[i];
                        tubeFlow[nodeA][nodeB] -= min_Flow;
                        Flow_Capacity[nodeA][nodeB] -= min_Flow;

                        if (tubeFlow[nodeA][nodeB] == 0) {
                            adjMatrix[nodeA][nodeB] = 0;
                        }
                        nodeA = nodeB;
                    }
                }

                // 再帰的に経路を探索
                int resultFlow = explorePath(startNode, nextNode, goalNode, path, pathIndex + 1, min_Flow);
                if (resultFlow > 0) {
                    return resultFlow; // 成功した場合は流量を返す
                }

                // **バックトラック処理**
                min_Flow = prevMinFlow;  // **元の min_Flow を復元**
            }
        }

        return 0; // 失敗した場合、流量0を返す
    }


    public void runUAVFlow(int startNode, int goalNode, int requiredUAVs, Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue) {
        UAV_count = 0;
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        CountDownLatch latch = new CountDownLatch(requiredUAVs);
        boolean flowAvailable = true;

        while (UAV_count < requiredUAVs && flowAvailable) {
            int previousUAVCount = UAV_count;
            min_Flow = 100;

            int[] path = new int[20];
            int pathIndex = 0;
            path[pathIndex++] = startNode;

            maxPathIndex = pathIndex;  // **修正: 初期化を適切に**

            int flow = explorePath(startNode, startNode, goalNode, path, pathIndex, 0);

            if (flow > 0) {
                int pathLength = maxPathIndex;  // **修正: 正しい長さを取得**
                int[] pathArray = Arrays.copyOf(path, pathLength);  // **修正: 正しい長さでコピー**

                int u = pathArray[0];
                int v = pathArray[1];

                double minCapacity = link[u][v].getCapacity();
                AtomicInteger flowCounter = new AtomicInteger(0);

                for (int f = 0; f < flow; f++) {
                    int currentUAVIndex;

                    synchronized (this) {
                        UAV_count++;
                        currentUAVIndex = UAV_count - 1;
                    }

                    Uav currentUAV = client.getFlow().getUav(currentUAVIndex);

                    scheduler.schedule(() -> {
                        currentUAV.setPath(pathArray);
                        outputRoute(currentUAV, "runUAVFlow");

                        if (flowCounter.get() < minCapacity) {
                            currentUAV.startTimer();
                            currentUAV.setFlyingLink(link[u][v]);
                            currentUAV.setPassedLink(link[u][v]);
                            flyingUavQueue.add(currentUAV);
                            link[u][v].decrementCapacity();
                            flowCounter.incrementAndGet();
                            System.out.println("UAV " + currentUAV.getId() + " is flying from " + u + " to " + v);
                        } else {
                            currentUAV.startWaitingTimer();
                            currentUAV.setStayedBeaconId(u);
                            beaconCluster.getBeacon(u).addUav(currentUAV);
                            beaconCluster.getBeacon(u).incrementWaitingUavCount();
                            uavQueue.add(currentUAV);
                            System.out.println("UAV " + currentUAV.getId() + " is waiting at " + u + "(" + u + " -> " + v + ")");
                        }

                        latch.countDown();
                    }, f * 2, TimeUnit.SECONDS);
                }
            } else {
                flowAvailable = false;
            }

            if (UAV_count == requiredUAVs) break;
        }

        scheduler.shutdown();
        try {
            scheduler.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("スレッドが割り込まれました: " + e.getMessage());
        }

        if (!flowAvailable) {
            int needUAV = requiredUAVs - UAV_count;
            if (needUAV > 0) {
                System.out.println("全てのUAVに経路が割り当てられませんでした。残り" + needUAV + "台のUAVを再割り当てします。");
                adjustRemainingFlow(needUAV, startNode, goalNode, client, flyingUavQueue, uavQueue);
            }
        }
    }

    private void adjustRemainingFlow(int needUAV, int startNode, int goalNode, Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue) {
        int countOfUAV = 0;
        int[] path = new int[20]; // path を再利用
        int pathIndex;

        while (countOfUAV < needUAV) {
            pathIndex = 0;
            Arrays.fill(path, 0);
            path[pathIndex++] = startNode;

            int currentNode = startNode;
            boolean pathFound = false;

            while (currentNode != goalNode) {
                int nextNode = -1;
                double maxCapacity = -1.0;

                for (int j = 0; j < node; j++) {
                    if (tubeFlow[currentNode][j] > 0 && Flow_Capacity[currentNode][j] >= 1) {
                        if (Flow_Capacity[currentNode][j] > maxCapacity) {
                            maxCapacity = Flow_Capacity[currentNode][j];
                            nextNode = j;
                        }
                    }
                }

                if (nextNode == -1) {
                    for (int j = 0; j < node; j++) {
                        if (Flow_Capacity[currentNode][j] > maxCapacity) {
                            maxCapacity = Flow_Capacity[currentNode][j];
                            nextNode = j;
                        }
                    }
                    if (nextNode != -1) {
                        tubeFlow[currentNode][nextNode] = 1;
                        Flow_Capacity[currentNode][nextNode] = 1.0;
                    }
                }

                if (nextNode == -1) {
                    break;
                }

                path[pathIndex++] = nextNode;
                int flow = tubeFlow[currentNode][nextNode];
                tubeFlow[currentNode][nextNode] -= flow;
                Flow_Capacity[currentNode][nextNode] -= flow;

                currentNode = nextNode;

                if (currentNode == goalNode) {
                    pathFound = true;
                    int[] assignedPath = new int[pathIndex];
                    System.arraycopy(path, 0, assignedPath, 0, pathIndex);

                    int u = assignedPath[0];
                    int v = assignedPath[1];
                    double minCapacity = link[u][v].getCapacity();

                    for (int uav = 0; uav < flow && countOfUAV < needUAV; uav++) {
                        int currentUAVIndex = UAV_count + countOfUAV;
                        Uav currentUAV = client.getFlow().getUav(currentUAVIndex);
                        currentUAV.setPath(assignedPath);
                        outputRoute(currentUAV, "remainingFlow");

                        if (countOfUAV < minCapacity) {
                            currentUAV.startTimer();
                            currentUAV.setFlyingLink(link[u][v]);
                            currentUAV.setPassedLink(link[u][v]);
                            flyingUavQueue.add(currentUAV);
                            link[u][v].decrementCapacity();
                            System.out.println("UAV " + currentUAV.getId() + " is flying from " + u + " to " + v + " adjustRemainingFlow");
                        } else {
                            currentUAV.startWaitingTimer();
                            currentUAV.setStayedBeaconId(u);
                            beaconCluster.getBeacon(u).addUav(currentUAV);
                            beaconCluster.getBeacon(u).incrementWaitingUavCount();
                            uavQueue.add(currentUAV);
                            System.out.println("UAV " + currentUAV.getId() + " is waiting at " + u + "(" + u + " -> " + v + ") adjustRemainingFlow");
                        }
                        countOfUAV++;
                    }
                }
            }

            if (!pathFound) {
                System.out.println("有効な経路が見つかりませんでした");
                break;
            }
        }

        UAV_count += countOfUAV; // UAV_count を適切に更新
    }

}
