package server.communication;

import client.Client;
import item.BeaconCluster;
import item.Link;
import item.Uav;

import java.io.IOException;

/**
 * 通信管理サーバの実装クラス
 */
public class CommunicationServerImpl implements CommunicationServer {
    
    private NetworkTopologyManager topologyManager;
    private ResultOutputManager outputManager;
    
    /**
     * コンストラクタ
     * 
     * @param topologyManager ネットワークトポロジー管理
     * @param outputManager 結果出力管理
     */
    public CommunicationServerImpl(NetworkTopologyManager topologyManager, ResultOutputManager outputManager) {
        this.topologyManager = topologyManager;
        this.outputManager = outputManager;
    }
    
    /**
     * ネットワークトポロジーを設定する
     * 
     * @param nodeNum ノード数
     * @param beaconCluster ビーコンクラスター
     */
    @Override
    public void setNetworkTopology(int nodeNum, BeaconCluster beaconCluster) {
        topologyManager.setNetworkTopology(nodeNum, beaconCluster);
    }
    
    /**
     * リンクを設定する
     * 
     * @param nodeNum ノード数
     * @param beaconCluster ビーコンクラスター
     */
    @Override
    public void setLink(int nodeNum, BeaconCluster beaconCluster) {
        topologyManager.setLink(nodeNum, beaconCluster);
    }
    
    /**
     * ランダムなリンクを設定する
     * 
     * @param nodeNum ノード数
     * @param beaconCluster ビーコンクラスター
     */
    @Override
    public void setLinkRandom(int nodeNum, BeaconCluster beaconCluster) {
        topologyManager.setLinkRandom(nodeNum, beaconCluster);
    }
    
    /**
     * Pajekファイル形式でネットワークトポロジーを出力する
     * 
     * @param filePath ファイルパス
     * @param client クライアント
     * @param beaconCluster ビーコンクラスター
     * @throws IOException 入出力例外
     */
    @Override
    public void nodeConfigureToPajek(String filePath, Client client, BeaconCluster beaconCluster) throws IOException {
        // Pajekファイル形式でネットワークトポロジーを出力
        try (java.io.FileWriter writer = new java.io.FileWriter(filePath)) {
            writer.write("*Vertices\t" + topologyManager.getNodeNum() + "\n");
            for (int i = 0; i < topologyManager.getNodeNum(); i++) {
                boolean isSource = i == client.getFlow().getSource().getId();
                boolean isDest = i == client.getFlow().getDestination().getId();
                
                if (isSource || isDest) {
                    writer.write(String.format("%d \"%d\" %.4f %.4f ic Black\n", i + 1, i + 1, beaconCluster.getBeacon(i).getX(), beaconCluster.getBeacon(i).getY()));
                } else {
                    writer.write(String.format("%d \"%d\" %.4f %.4f ic White\n", i + 1, i + 1, beaconCluster.getBeacon(i).getX(), beaconCluster.getBeacon(i).getY()));
                }
            }
            writer.write("*Arcs\n*Edges\n");
            
            Link[][] linkMatrix = topologyManager.getLinkMatrix();
            for (int i = 0; i < topologyManager.getNodeNum(); i++) {
                for (int j = 0; j < topologyManager.getNodeNum(); j++) {
                    if (i != j && linkMatrix[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY) {
                        writer.write(String.format("%d %d 1\n", i + 1, j + 1));
                    }
                }
            }
        }
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
        outputManager.outputToPajek(client, eps, allFlow, ct);
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
        outputManager.outputToExcel(client, ct);
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
        outputManager.outputToTxt(client, ct);
    }
    
    /**
     * 経路情報をファイルに出力する
     * 
     * @param currentUAV 現在のUAV
     * @param method 使用したメソッド
     */
    @Override
    public void outputRoute(Uav currentUAV, String method) {
        outputManager.outputRoute(currentUAV, method);
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
        outputManager.outputToFlow(client, ct);
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
        outputManager.outputRouteToExcel(client, ct);
    }
    
    /**
     * ネットワークトポロジー管理を取得する
     * 
     * @return ネットワークトポロジー管理
     */
    public NetworkTopologyManager getTopologyManager() {
        return topologyManager;
    }
    
    /**
     * 結果出力管理を取得する
     * 
     * @return 結果出力管理
     */
    public ResultOutputManager getOutputManager() {
        return outputManager;
    }
    
    /**
     * ネットワークトポロジー管理を設定する
     * 
     * @param topologyManager ネットワークトポロジー管理
     */
    public void setTopologyManager(NetworkTopologyManager topologyManager) {
        this.topologyManager = topologyManager;
    }
    
    /**
     * 結果出力管理を設定する
     * 
     * @param outputManager 結果出力管理
     */
    public void setOutputManager(ResultOutputManager outputManager) {
        this.outputManager = outputManager;
    }
}
