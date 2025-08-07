package server.communication;

import client.Client;
import item.Beacon;
import item.BeaconCluster;
import item.Link;
import item.Uav;
import server.util.ConfigurationManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 結果出力を担当する実装クラス
 */
public class ResultOutputManagerImpl implements ResultOutputManager {
    
    private Link[][] linkMatrix;
    private BeaconCluster beaconCluster;
    private int nodeNum;
    private int runCounter;
    private ConfigurationManager config;
    private String baseDirectoryPath;
    private boolean fig_SOURCE = false;
    private boolean fig_DIST = false;
    
    /**
     * コンストラクタ
     * 
     * @param linkMatrix リンク行列
     * @param beaconCluster ビーコンクラスター
     * @param nodeNum ノード数
     * @param baseDirectoryPath 基本ディレクトリパス
     */
    public ResultOutputManagerImpl(Link[][] linkMatrix, BeaconCluster beaconCluster, int nodeNum, String baseDirectoryPath) {
        this.linkMatrix = linkMatrix;
        this.beaconCluster = beaconCluster;
        this.nodeNum = nodeNum;
        this.runCounter = 0;
        this.config = ConfigurationManager.getInstance();
        this.baseDirectoryPath = baseDirectoryPath;
    }
    
    /**
     * Pajekファイル形式で結果を出力する
     * 
     * @param client クライアント
     * @param eps イプシロン値
     * @param allFlow 全体の流量
     * @param ct カウンター
     * @throws IOException 入出力例外
     */
    @Override
    public void outputToPajek(Client client, double eps, double allFlow, int ct) throws IOException {
        // ディレクトリパスを作成
        String dirPath = baseDirectoryPath + "/EPS/pajek/result" + runCounter;
        // ファイル名を作成
        String filename = dirPath + "/test_topology_" + (ct + 1) + ".net";
        
        // ディレクトリの存在を確認・作成
        File dir = createOutputDirectory(dirPath);
        
        Beacon source = client.getFlow().getSource();
        Beacon dist = client.getFlow().getDestination();
        
        try (FileWriter writer = new FileWriter(filename)) {
            writer.write("*Vertices\t" + nodeNum + "\n");
            for (int i = 0; i < nodeNum; i++) {
                if (i == source.getId() || i == dist.getId()) {
                    writer.write(String.format("%d \"%d\" %.4f %.4f ic Black\n", i + 1, i + 1, beaconCluster.getBeacon(i).getX(), beaconCluster.getBeacon(i).getY()));
                } else {
                    writer.write(String.format("%d \"%d\" %.4f %.4f ic White\n", i + 1, i + 1, beaconCluster.getBeacon(i).getX(), beaconCluster.getBeacon(i).getY()));
                }
            }
            writer.write("*Arcs\n*Edges\n");
            
            for (int i = 0; i < nodeNum; i++) {
                for (int j = 0; j < nodeNum; j++) {
                    if (linkMatrix[i][j].getL_tubeLength() != config.getInf()) {
                        double flow = linkMatrix[i][j].getQ_tubeFlow();
                        if (linkMatrix[i][j].getDistance() > 0) {
                            if (flow > 0 && flow <= eps) {
                                // Small flow, no color
                            } else if (flow > eps && flow <= config.getThreshold1()) {
                                writer.write(String.format("%d %d 1 c Blue\n", i + 1, j + 1));
                            } else if (flow > config.getThreshold1() && flow <= config.getThreshold2()) {
                                writer.write(String.format("%d %d 2 c Green\n", i + 1, j + 1));
                            } else if (flow > config.getThreshold2() && flow <= allFlow) {
                                writer.write(String.format("%d %d 3 c Red\n", i + 1, j + 1));
                            }
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Excel形式で結果を出力する
     * 
     * @param client クライアント
     * @param ct カウンター
     * @throws IOException 入出力例外
     */
    @Override
    public void outputToExcel(Client client, int ct) throws IOException {
        // ディレクトリパスを作成
        String dirPath = baseDirectoryPath + "/EPS/excel/result" + runCounter;
        // ファイル名を作成
        String filename = dirPath + "/test_topology_" + (ct + 1) + ".txt";
        
        // ディレクトリの存在を確認・作成
        File dir = createOutputDirectory(dirPath);
        
        try (FileWriter writer = new FileWriter(filename)) {
            writer.write("source,destination,flow\n");
            for (int i = 0; i < nodeNum; i++) {
                for (int j = 0; j < nodeNum; j++) {
                    if (linkMatrix[i][j].getL_tubeLength() != config.getInf()) {
                        writer.write(String.format("%d,%d,%.4f\n", i, j, linkMatrix[i][j].getQ_tubeFlow()));
                    }
                }
            }
        }
    }
    
    /**
     * テキストファイル形式で結果を出力する
     * 
     * @param client クライアント
     * @param ct カウンター
     * @throws IOException 入出力例外
     */
    @Override
    public void outputToTxt(Client client, int ct) throws IOException {
        // ディレクトリパスを作成
        String dirPath = baseDirectoryPath + "/EPS/txt/result" + runCounter;
        // ファイル名を作成
        String filename = dirPath + "/test_topology_" + (ct + 1) + ".txt";
        
        // ディレクトリの存在を確認・作成
        File dir = createOutputDirectory(dirPath);
        
        try (FileWriter writer = new FileWriter(filename)) {
            //要求uav台数，出発ノード，到着ノードを１行目に出力
            writer.write(String.format("%.1f,%d,%d\n", client.getFlow().getTheNumberOfUAV(), client.getFlow().getSource().getId(), client.getFlow().getDestination().getId()));
            writer.write("source,destination,length,thickness,capacity\n");
            for (int i = 0; i < nodeNum; i++) {
                for (int j = 0; j < nodeNum; j++) {
                    if (linkMatrix[i][j].getL_tubeLength() != config.getInf()) {
                        writer.write(String.format("%d,%d,%.4f,%.4f,%.4f\n", i, j, linkMatrix[i][j].getL_tubeLength(), linkMatrix[i][j].getD_tubeThickness(), linkMatrix[i][j].getCapacity()));
                    }
                }
            }
        }
    }
    
    /**
     * 経路情報をファイルに出力する
     * 
     * @param currentUAV 現在のUAV
     * @param method 使用したメソッド
     */
    @Override
    public void outputRoute(Uav currentUAV, String method) {
        String dirPath = baseDirectoryPath + "/EPS/path";
        String filePath = dirPath + "/flight_path.txt";
        
        try {
            // ディレクトリが存在しない場合は作成
            File dir = createOutputDirectory(dirPath);
            
            try (FileWriter writer = new FileWriter(filePath, true)) {
                // ファイルが空の場合、ヘッダーを追加
                File file = new File(filePath);
                if (file.length() == 0) {
                    writer.write("clientId,UAVId,flightPath,method\n");
                }
                
                // UAVの飛行経路を取得し、"-" 区切りの文字列に変換
                String pathString = Arrays.stream(currentUAV.getPath())
                        .mapToObj(String::valueOf)
                        .collect(Collectors.joining("-"));
                
                // 行を書き込み
                writer.write(String.format("%d,%d,%s,%s\n", 
                        currentUAV.getClientId(), 
                        currentUAV.getId(), 
                        pathString, 
                        method));
            }
        } catch (IOException e) {
            System.err.println("ファイル書き込みエラー: " + e.getMessage());
        }
    }
    
    /**
     * フロー情報をファイルに出力する
     * 
     * @param client クライアント
     * @param ct カウンター
     * @throws IOException 入出力例外
     */
    @Override
    public void outputToFlow(Client client, int ct) throws IOException {
        String dirPath = baseDirectoryPath + "/EPS/flow/result" + runCounter;
        // ファイル名を作成
        String filename = dirPath + "/test_topology_flow.txt";
        
        // ディレクトリの存在を確認・作成
        File dir = createOutputDirectory(dirPath);
        
        // ファイルに追記
        try (FileWriter writer = new FileWriter(filename, true)) { // true で追記モードに設定
            // ヘッダーは1回だけ記載されるようにする
            File file = new File(filename);
            if (file.length() == 0) { // ファイルが空の場合のみヘッダーを書き込む
                writer.write("ct,v0->v1,v0->v2,v0->v3,v1->v4,v2->v3,v2->v5,v3->v5,v4->v5\n");
            }
            
            if (runCounter == 0 || runCounter == 1) {
                // 経路ごとの情報を1行にまとめて追記
                writer.write(String.format("%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f\n", 
                        ct, 
                        linkMatrix[0][1].getQ_tubeFlow(), 
                        linkMatrix[0][2].getQ_tubeFlow(), 
                        linkMatrix[0][3].getQ_tubeFlow(), 
                        linkMatrix[1][4].getQ_tubeFlow(), 
                        linkMatrix[2][3].getQ_tubeFlow(), 
                        linkMatrix[2][5].getQ_tubeFlow(), 
                        linkMatrix[3][5].getQ_tubeFlow(), 
                        linkMatrix[4][5].getQ_tubeFlow()));
            } else if (runCounter == 2) {
                if (file.length() == 0) { // ファイルが空の場合のみヘッダーを書き込む
                    writer.write("ct,v2->v0,v2->v3,v2->v5,v0->v1,v3->v0,v3->v5,v1->v4,v5->v4\n");
                }
                
                // 経路ごとの情報を1行にまとめて追記
                writer.write(String.format("%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f\n", 
                        ct, 
                        linkMatrix[2][0].getQ_tubeFlow(), 
                        linkMatrix[2][3].getQ_tubeFlow(), 
                        linkMatrix[2][5].getQ_tubeFlow(), 
                        linkMatrix[0][1].getQ_tubeFlow(), 
                        linkMatrix[3][0].getQ_tubeFlow(), 
                        linkMatrix[3][5].getQ_tubeFlow(), 
                        linkMatrix[1][4].getQ_tubeFlow(), 
                        linkMatrix[5][4].getQ_tubeFlow()));
            }
        }
    }
    
    /**
     * 経路ごとのUAV数をExcel形式で出力する
     * 
     * @param client クライアント
     * @param ct カウンター
     * @throws IOException 入出力例外
     */
    @Override
    public void outputRouteToExcel(Client client, int ct) throws IOException {
        // ディレクトリパスを作成
        String dirPath = baseDirectoryPath + "/EPS/rute/result" + runCounter;
        // ファイル名を作成
        String filename = dirPath + "/test_topology_routes.txt";
        
        // ディレクトリの存在を確認・作成
        File dir = createOutputDirectory(dirPath);
        
        // ファイルに追記
        try (FileWriter writer = new FileWriter(filename, true)) { // true で追記モードに設定
            // ヘッダーは1回だけ記載されるようにする
            File file = new File(filename);
            
            if (runCounter == 0 || runCounter == 1) {
                if (file.length() == 0) { // ファイルが空の場合のみヘッダーを書き込む
                    writer.write("ct,v0->v1->v4->v5,v0->v2->v3->v5,v0->v2->v5,v0->v3->v5\n");
                }
                
                double route1 = Math.min(linkMatrix[0][1].getQ_tubeFlow(),
                        Math.min(linkMatrix[1][4].getQ_tubeFlow(), linkMatrix[4][5].getQ_tubeFlow()));
                
                double route2 = Math.min(linkMatrix[0][2].getQ_tubeFlow(),
                        Math.min(linkMatrix[2][3].getQ_tubeFlow(), linkMatrix[3][5].getQ_tubeFlow()));
                
                double route3 = Math.min(linkMatrix[0][2].getQ_tubeFlow(), linkMatrix[2][5].getQ_tubeFlow());
                
                double route4 = Math.min(linkMatrix[0][3].getQ_tubeFlow(), linkMatrix[3][5].getQ_tubeFlow());
                
                // 経路ごとの情報を1行にまとめて追記
                writer.write(String.format("%d,%.4f,%.4f,%.4f,%.4f\n", ct, route1, route2, route3, route4));
            } else if (runCounter == 2) {
                if (file.length() == 0) { // ファイルが空の場合のみヘッダーを書き込む
                    writer.write("ct,v2->v0->v1->v4,v2->v3->v0->v1->v4,v2->v3->v5->v4,v2->v5->v4\n");
                }
                
                double route1 = Math.min(linkMatrix[2][0].getQ_tubeFlow(),
                        Math.min(linkMatrix[0][1].getQ_tubeFlow(), linkMatrix[1][4].getQ_tubeFlow()));
                
                double route2 = Math.min(linkMatrix[2][3].getQ_tubeFlow(),
                        Math.min(linkMatrix[3][0].getQ_tubeFlow(), Math.min(linkMatrix[0][1].getQ_tubeFlow(), linkMatrix[1][4].getQ_tubeFlow())));
                
                double route3 = Math.min(linkMatrix[2][3].getQ_tubeFlow(), Math.min(linkMatrix[3][5].getQ_tubeFlow(), linkMatrix[5][4].getQ_tubeFlow()));
                
                double route4 = Math.min(linkMatrix[2][5].getQ_tubeFlow(), linkMatrix[5][4].getQ_tubeFlow());
                
                // 経路ごとの情報を1行にまとめて追記
                writer.write(String.format("%d,%.4f,%.4f,%.4f,%.4f\n", ct, route1, route2, route3, route4));
            }
        }
    }
    
    /**
     * 実行カウンターを設定する
     * 
     * @param runCounter 実行カウンター
     */
    @Override
    public void setRunCounter(int runCounter) {
        this.runCounter = runCounter;
    }
    
    /**
     * 実行カウンターを取得する
     * 
     * @return 実行カウンター
     */
    @Override
    public int getRunCounter() {
        return runCounter;
    }
    
    /**
     * 出力ディレクトリを作成する
     * 
     * @param dirPath ディレクトリパス
     * @return 作成されたディレクトリ
     */
    @Override
    public File createOutputDirectory(String dirPath) {
        File dir = new File(dirPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }
    
    /**
     * リンク行列を設定する
     * 
     * @param linkMatrix リンク行列
     */
    public void setLinkMatrix(Link[][] linkMatrix) {
        this.linkMatrix = linkMatrix;
    }
    
    /**
     * ビーコンクラスターを設定する
     * 
     * @param beaconCluster ビーコンクラスター
     */
    public void setBeaconCluster(BeaconCluster beaconCluster) {
        this.beaconCluster = beaconCluster;
    }
    
    /**
     * ノード数を設定する
     * 
     * @param nodeNum ノード数
     */
    public void setNodeNum(int nodeNum) {
        this.nodeNum = nodeNum;
    }
    
    /**
     * 基本ディレクトリパスを設定する
     * 
     * @param baseDirectoryPath 基本ディレクトリパス
     */
    public void setBaseDirectoryPath(String baseDirectoryPath) {
        this.baseDirectoryPath = baseDirectoryPath;
    }
}
