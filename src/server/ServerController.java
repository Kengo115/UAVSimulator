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
import java.util.LinkedList;
import java.util.Queue;


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
    private static final double THRESHOLD_2 = 1.0;
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

    // シグモイド関数用
    private double[][] Q_tubeFlow_sigmoidOutput;

    private BeaconCluster beaconCluster;
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

        // 1xN matrix
        this.Q_Kirchhoff = new double[node];
        this.P_tubePressure = new double[node];
        // 初期値を追加してサイズを確保

        // 2xN matrix
        this.pressureCoefficient = new double[node][node];
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
        link[4][5].setL_tubeLength(4);
        adjMatrix[4][5] = 1;

        link[5][4].setD_tubeThickness(INIT_THICKNESS);
        link[5][4].setL_tubeLength(4);
        adjMatrix[5][4] = 1;
    }


    // nodeConfigureメソッドの追加
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
        link[4][5].setL_tubeLength(4);
        link[4][5].setDistance(1000);
        link[4][5].setCongestionRate(INIT_RATE);
        adjMatrix[4][5] = 1;

        link[5][4].setLink(beaconList.getBeacon(5), beaconList.getBeacon(4), 15);
        link[5][4].setD_tubeThickness(INIT_THICKNESS);
        link[5][4].setL_tubeLength(4);
        link[5][4].setDistance(1000);
        link[5][4].setCongestionRate(INIT_RATE);
        adjMatrix[5][4] = 1;

    }

    public void nodeConfigureToPajek(String NET_file, Client client, BeaconCluster beaconList) {
        double maxDistance = Math.sqrt(2);  // 最大距離 sqrt(2)

        //sourceとdistを取得
        Beacon source = client.getFlow().getSource();
        Beacon dist = client.getFlow().getDestination();

        // ファイル出力処理
        try (FileWriter writer = new FileWriter(new File(NET_file))) {
            writer.write("*Vertices\t" + node + "\n");
            for (int i = 0; i < node; i++) {

                if(i == source.getId()){
                    fig_SOURCE = true;
                }

                if(i == dist.getId()){
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

            for(int i = 0; i < node; i++){
                for(int j = 0; j < node; j++){
                    if(i != j && link[i][j].getL_tubeLength() != INF){
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
        String dirPath = "src/result/pajek/result" + runCounter;
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
                    if (link[i][j].getL_tubeLength() != INF){
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
        String dirPath = "src/result/excel/result" + runCounter;
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
        String dirPath = "src/result/flow/result" + runCounter;
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
            if(runCounter == 0 || runCounter == 1) {
                if (file.length() == 0) { // ファイルが空の場合のみヘッダーを書き込む
                    writer.write("ct,v0->v1,v0->v2,v0->v3,v1->v4,v2->v3,v2->v5,v3->v5,v4->v5\n");
                }

                // 経路ごとの情報を1行にまとめて追記
                writer.write(String.format("%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f\n", ct, link[0][1].getQ_tubeFlow(), link[0][2].getQ_tubeFlow(), link[0][3].getQ_tubeFlow(), link[1][4].getQ_tubeFlow(), link[2][3].getQ_tubeFlow(),link[2][5].getQ_tubeFlow(), link[3][5].getQ_tubeFlow(), link[4][5].getQ_tubeFlow()));

            }else if(runCounter == 2){
                if (file.length() == 0) { // ファイルが空の場合のみヘッダーを書き込む
                    writer.write("ct,v2->v0,v2->v3,v2->v5,v0->v1,v3->v0,v3->v5,v1->v4,v5->v4\n");
                }

                // 経路ごとの情報を1行にまとめて追記
                writer.write(String.format("%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f\n", ct, link[2][0].getQ_tubeFlow(), link[2][3].getQ_tubeFlow(), link[2][5].getQ_tubeFlow(), link[0][1].getQ_tubeFlow(), link[3][0].getQ_tubeFlow(),link[3][5].getQ_tubeFlow(), link[3][5].getQ_tubeFlow(), link[5][4].getQ_tubeFlow()));
            }
        }
    }

    // 経路ごとのUAV数をExcel形式で出力するメソッド
    public void outputRouteToExcel(Client client, int ct) throws IOException {
        // ディレクトリパスを作成
        String dirPath = "src/result/rute/result" + runCounter;
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
            if(runCounter == 0 || runCounter == 1) {
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

            }else if(runCounter == 2){
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
        String dirPath = "src/result/txt/result" + runCounter;
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


    // UAVを移動させるメソッド
    public static void flyUAV(Queue<Client> passedClient, ClientController clientcontroller) {
        // リンク上の飛行UAV数を保持する配列を初期化
        int[][] FlyingUAV = new int[node][node];

        // Queueの中に前回以前のClientが含まれている場合、そのUAVを取り出してリンク上の飛行台数を更新
        for (Client client : passedClient) {
            Uav[] uavList = client.getFlow().getUavList();
            for (Uav uav : uavList) {
                // UAVが飛行中の場合のみ処理を実行
                if (uav.getIsFlying()) {
                    // 現在の飛行時間とUAVの速さから移動距離を計算
                    double flightDistance = uav.getFlightTime() * uav.getSpeed();
                    int[] path = uav.getPath();

                    // UAVの経路上の総距離を計算
                    double totalPathDistance = 0.0;
                    for (int k = 0; k < path.length - 1; k++) {
                        int startNode = path[k];
                        int endNode = path[k + 1];
                        totalPathDistance += link[startNode][endNode].getDistance();
                    }


                    // UAVが飛行完了しているかを確認
                    if (flightDistance >= totalPathDistance) {
                        // 飛行が完了している場合、タイマーをキャンセル
                        uav.cancelTimer();
                        client.incrementFinishFlyingCounter(); // 完了したUAVの数を増加

                        // CSV形式のtxtファイルに書き込み
                        String dirPath = "src/result/time";
                        String filePath = dirPath + "/flight_times.csv";

                        // ディレクトリが存在しない場合は作成
                        File dir1 = new File(dirPath);
                        if (!dir1.exists()) {
                            dir1.mkdirs();
                        }


                        // 飛行時間の取得
                        long flightTime = clientcontroller.getFlightTime();
                        long UAV_flightTime = uav.getFlightTime();

                        try (FileWriter writer = new FileWriter(filePath, true)) {
                            // ファイルが空の場合、ヘッダーを追加
                            File file = new File(filePath);
                            if (file.length() == 0) {
                                writer.write("passedTime,UAVTime,ClientID,UAVID,speed,distance\n");
                            }
                            if (counter == 0){
                                writer.write(String.format("%f\n", client.getFlow().getTheNumberOfUAV()));
                                counter++;
                            }
                            // 行を書き込み
                            writer.write(String.format("%d,%d,%d,%d,%f,%f\n", flightTime, UAV_flightTime, client.getId(), uav.getId(), uav.getSpeed(), totalPathDistance));
                        } catch (IOException e) {
                            System.err.println("ファイル書き込みエラー: " + e.getMessage());
                        }


                        continue; // 次のUAVへ
                    }


                    // 経路上のリンクごとにUAVの位置を確認
                    double traveledDistance = 0.0;
                    for (int k = 0; k < path.length - 1; k++) {
                        int startNode = path[k];
                        int endNode = path[k + 1];
                        double linkLength = link[startNode][endNode].getDistance();

                        if (traveledDistance + linkLength >= flightDistance) {
                            // UAVがリンク上にいる場合、該当リンクの飛行UAV数を増加
                            FlyingUAV[startNode][endNode]++;
                            break;
                        } else {
                            traveledDistance += linkLength; // 次のリンクに進む
                        }
                    }
                }
            }
            //FinishFlyingCounterとUAVの数が一致した場合、Clientを削除
            if (client.getFinishFlyingCounter() == client.getFlow().getTheNumberOfUAV()) {
                passedClient.remove(client);
                System.out.println("Client " + clientNum + " has been removed.");

                if(clientcontroller.getIsTiming()){
                    System.out.println("正しく動作しています");
                }else{
                    System.out.println("正しく動作していません");
                }

                clientNum++;
            }
        }
        // 飛行中のUAVに基づいて管の容量を更新
        updateCapacity(FlyingUAV);
        //クライアントタイマー動作中
    }

    // 管の容量を更新するメソッド
    public static void updateCapacity(int[][] FlyingUAV) {
        //Capacityを初期値に戻す
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    link[i][j].setCapacity(link[i][j].getInitCapacity());

                }
            }
        }
        // 各リンクの初期容量から飛行中のUAV分を減少
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF && FlyingUAV[i][j] > 0) {
                    double newCapacity = link[i][j].getCapacity() - FlyingUAV[i][j];
                    link[i][j].setCapacity(newCapacity);
                    link[j][i].setCapacity(newCapacity);
                }
            }
        }
    }



    //PSを実行するメソッド
    public void run(Client client, Queue<Client> passedClient, ClientController clientcontroller, int numLoop) throws IOException {
        int nodeExcept = node - 1;
        int ct = 0;
        double eps = 1e-10;
        int testIter = 10;
        int a=0, b, i, j;
        double degeneracyEffect = 1.0;

        if(runCounter != 0){
            //更新メソッドを呼び出す
            //flyUAV(passedClient);
            reset();
        }

        //passedClientが空でない場合，UAVFlySchedulerを停止
        if (!passedClient.isEmpty()) {
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
            if(ct % PLOT_2 == 0 || ct == numLoop - 1){
                outputRouteToExcel(client, ct);
                outputToflow(client, ct);
            }

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
                    UAVFlyScheduler.startFlyUAVUpdates(passedClient, clientcontroller);
                }
                // 実際のUAVに経路を割り当てるためのメイン処理
                runUAVFlow(startNode, goalNode, requiredUAVs, client);
            }
        }
        //client.startTimer();
        runCounter++;
    }


    //深さ優先探索(DFS)
    private int explorePath(int startNode, int currentNode, int goalNode, int[] path, int pathIndex, int passedFlow) {
        // ゴールノードに到達したら流量を返して経路探索を終了
        if (currentNode == goalNode) {
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
                if (passedFlow == 0) {
                    min_Flow = flow;
                } else {
                    min_Flow = Math.min(min_Flow, flow);
                }

                // 経路に次のノードを追加
                path[pathIndex] = nextNode;

                // 最終的に見つかった経路に沿ってフローを減少させる
                if (nextNode == goalNode && min_Flow > 0) {
                    int nodeA = startNode;
                    for (int i = 0; i <= pathIndex; i++) {
                        int nodeB = path[i];
                        // `tubeFlow` と `Flow_Capacity` を減算
                        tubeFlow[nodeA][nodeB] -= min_Flow;
                        Flow_Capacity[nodeA][nodeB] -= min_Flow;

                        // `tubeFlow` が0なら `adjMatrix` から接続を削除
                        if (tubeFlow[nodeA][nodeB] == 0) {
                            adjMatrix[nodeA][nodeB] = 0;
                        }
                        nodeA = nodeB;
                    }
                }

                // 再帰的に経路を探索し、成功時には流量を返す
                int resultFlow = explorePath(startNode, nextNode, goalNode, path, pathIndex + 1, min_Flow);
                if (resultFlow > 0) {
                    return resultFlow; // 見つかった最小フローを返す
                }

                // 探索が失敗した場合、pathIndexを戻して最後のノードを削除（バックトラック）
                min_Flow = (int) INF;  // 最小フローをリセット
            }
        }

        return 0; // 失敗した場合、流量0を返す
    }

    //幅優先探索(BFS)
    private int explorePathBFS(int startNode, int goalNode, int[][] path, int[] pathIndex) {
        Queue<Integer> queue = new LinkedList<>(); // ノードを探索するためのキュー
        int[] parent = new int[node];             // 各ノードの親を記録
        Arrays.fill(parent, -1);                  // 初期化（-1は親なしを意味する）
        boolean[] visited = new boolean[node];    // 訪問済みノードを追跡
        int[] flow = new int[node];               // 各ノードに到達する最小フロー

        // スタートノードを初期化
        queue.add(startNode);
        visited[startNode] = true;
        flow[startNode] = Integer.MAX_VALUE; // 初期フローは∞

        // 幅優先探索開始
        while (!queue.isEmpty()) {
            int currentNode = queue.poll();

            // ゴールノードに到達した場合、経路を復元してフローを更新
            if (currentNode == goalNode) {
                int minFlow = flow[goalNode];

                // 経路復元とフロー更新
                int nodeA = goalNode;
                int index = 0;
                while (parent[nodeA] != -1) {
                    int nodeB = parent[nodeA];
                    path[index][0] = nodeB; // 経路の開始ノード
                    path[index][1] = nodeA; // 経路の終了ノード
                    index++;

                    // フローの減算
                    tubeFlow[nodeB][nodeA] -= minFlow;
                    Flow_Capacity[nodeB][nodeA] -= minFlow;

                    // `tubeFlow` が0なら `adjMatrix` から接続を削除
                    if (tubeFlow[nodeB][nodeA] == 0) {
                        adjMatrix[nodeB][nodeA] = 0;
                    }

                    nodeA = nodeB;
                }
                pathIndex[0] = index; // 経路の長さを記録
                return minFlow;
            }

            // 隣接ノードを探索
            for (int nextNode = 0; nextNode < node; nextNode++) {
                if (adjMatrix[currentNode][nextNode] == 1 && tubeFlow[currentNode][nextNode] > 0 && !visited[nextNode]) {
                    visited[nextNode] = true;
                    parent[nextNode] = currentNode; // 親ノードを記録
                    flow[nextNode] = Math.min(flow[currentNode], tubeFlow[currentNode][nextNode]); // 最小フローを記録
                    queue.add(nextNode);
                }
            }
        }

        return 0; // ゴールノードに到達できなかった場合
    }


    // runUAVFlow関数
    public void runUAVFlow(int startNode, int goalNode, int requiredUAVs, Client client) {
        UAV_count = 0; // ゴールに到達したUAVの数を追跡

        // 全UAV分の経路を格納する配列（各経路の最大ノード数を指定）
        int maxPathLength = 5; // 経路の最大ノード数を想定して指定
        int[][] paths = new int[requiredUAVs][maxPathLength];

        // 要求UAV数に到達するまで経路探索を繰り返す
        while (UAV_count < requiredUAVs) {
            int previousUAVCount = UAV_count;
            min_Flow = 100;

            // 新しい経路を格納する一時配列を初期化し、スタートノードを追加
            int[] path = new int[maxPathLength];
            int pathIndex = 0; // path 配列内の位置を追跡
            path[pathIndex++] = startNode;

            // 最大のpathIndexを追跡するための変数
            maxPathIndex = pathIndex;

            // スタートノードから経路を再帰的に探索し、成功時にUAV_countを増加
            int flow = explorePath(startNode, startNode, goalNode, path, pathIndex, 0); // 流量（UAV台数）を取得

            if (flow > 0) {
                // 見つかった経路を `flow` 回 `paths` に追加
                for (int f = 0; f < flow; f++) {
                    int[] pathArray = new int[maxPathIndex+1]; // 最大のpathIndexで配列を作成
                    System.arraycopy(path, 0, pathArray, 0, maxPathIndex+1); // 必要な部分のみをコピー
                    paths[UAV_count + f] = pathArray;

                    client.getFlow().getUav(UAV_count + f).setPath(pathArray); // 配列をUAVに設定
                    client.getFlow().getUav(UAV_count + f).startTimer();

                }
                UAV_count += flow; // UAV_count を流量分増加
            } else {
                // 要求されたUAV数にまだ満たない場合、残りの流量を調整
                if (previousUAVCount == UAV_count && UAV_count < requiredUAVs) {
                    int needUAV = requiredUAVs - UAV_count;
                    adjustRemainingFlow(needUAV, startNode, goalNode, client);
                    break;
                }
            }

            if (UAV_count == requiredUAVs) break;
        }

        // 全UAVに経路を割り当てられなかった場合の警告
        if (UAV_count < requiredUAVs) {
            System.out.println("全てのUAVに経路が割り当てられませんでした");
        }
    }



    private void adjustRemainingFlow(int needUAV, int startNode, int goalNode, Client client) {
        int countOfUAV = 0;
        int[] path = new int[5]; // path を再利用
        int pathIndex;

        while (countOfUAV < needUAV) {
            // pathIndexを0にリセットして、配列を再利用
            pathIndex = 0;
            Arrays.fill(path, 0);
            path[pathIndex++] = startNode;

            int currentNode = startNode;
            boolean pathFound = false;

            // 目的地に到達するまで経路を探索
            while (currentNode != goalNode) {
                int nextNode = -1;
                double maxCapacity = -1.0;

                // 6. Flow_Capacityが1以上のリンクを探索
                for (int j = 0; j < node; j++) {
                    if (tubeFlow[currentNode][j] > 0 && Flow_Capacity[currentNode][j] >= 1) {
                        if (Flow_Capacity[currentNode][j] > maxCapacity) {
                            maxCapacity = Flow_Capacity[currentNode][j];
                            nextNode = j;
                        }
                    }
                }

                // Flow_Capacityが1以上のリンクが存在しない場合、最大容量のリンクのFlow_Capacityを1に変更
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

                // 次のノードが見つからない場合、経路が無効なので終了
                if (nextNode == -1) {
                    break;
                }

                // 選択されたリンクに沿ってtubeFlowとFlow_Capacityを減少させ、経路を進む
                path[pathIndex++] = nextNode;
                int flow = tubeFlow[currentNode][nextNode];
                tubeFlow[currentNode][nextNode] -= flow;
                Flow_Capacity[currentNode][nextNode] -= flow;

                currentNode = nextNode;

                // 目的地に到達した場合、経路をUAVに割り当て
                if (currentNode == goalNode) {
                    UAV_count += flow;
                    countOfUAV += flow;
                    pathFound = true;

                    // 複数のUAVが同じ経路を使用できる場合、それぞれに経路を設定
                    for (int uav = 0; uav < flow; uav++) {
                        if (uav >= needUAV) break;
                        int[] assignedPath = new int[pathIndex];
                        System.arraycopy(path, 0, assignedPath, 0, pathIndex);
                        client.getFlow().getUav(UAV_count - 1).setPath(assignedPath);
                        client.getFlow().getUav(UAV_count - 1).startTimer();
                    }
                    break;
                }
            }

            // 経路が見つからなかった場合の処理
            if (!pathFound) {
                System.out.println("有効な経路が見つかりませんでした");
                break;
            }
        }
    }

}
