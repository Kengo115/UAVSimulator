package server.communication;

import item.Beacon;
import item.BeaconCluster;
import item.Link;
import server.util.ConfigurationManager;

import static java.lang.Math.sqrt;

/**
 * ネットワークトポロジー管理の実装クラス
 */
public class NetworkTopologyManagerImpl implements NetworkTopologyManager {
    
    private Link[][] linkMatrix;
    private int nodeNum;
    private ConfigurationManager config;
    
    /**
     * コンストラクタ
     * 
     * @param nodeNum ノード数
     */
    public NetworkTopologyManagerImpl(int nodeNum) {
        this.nodeNum = nodeNum;
        this.config = ConfigurationManager.getInstance();
        initializeLinkMatrix();
    }
    
    /**
     * リンク行列を初期化する
     */
    private void initializeLinkMatrix() {
        linkMatrix = new Link[nodeNum][nodeNum];
        for (int i = 0; i < nodeNum; i++) {
            for (int j = 0; j < nodeNum; j++) {
                linkMatrix[i][j] = new Link();
            }
        }
    }
    
    /**
     * ネットワークトポロジーを設定する
     * 
     * @param nodeNum ノード数
     * @param beaconCluster ビーコンクラスター
     */
    @Override
    public void setNetworkTopology(int nodeNum, BeaconCluster beaconCluster) {
        this.nodeNum = nodeNum;
        if (linkMatrix == null || linkMatrix.length != nodeNum) {
            initializeLinkMatrix();
        }
        setLink(nodeNum, beaconCluster);
    }
    
    /**
     * リンクを設定する
     * 
     * @param nodeNum ノード数
     * @param beaconCluster ビーコンクラスター
     */
    @Override
    public void setLink(int nodeNum, BeaconCluster beaconCluster) {
        // 全てのリンクを初期化
        for (int i = 0; i < nodeNum; i++) {
            for (int j = 0; j < nodeNum; j++) {
                linkMatrix[i][j].setD_tubeThickness(0.0);
                linkMatrix[i][j].setL_tubeLength(config.getInf());
            }
        }
        
        // 手動でリンクを設定
        setLinkBetween(0, 1, 5, 250, beaconCluster);
        setLinkBetween(0, 2, 15, 750, beaconCluster);
        setLinkBetween(0, 3, 10, 500, beaconCluster);
        setLinkBetween(1, 4, 10, 500, beaconCluster);
        setLinkBetween(2, 3, 5, 250, beaconCluster);
        setLinkBetween(2, 5, 15, 750, beaconCluster);
        setLinkBetween(3, 5, 10, 500, beaconCluster);
        setLinkBetween(4, 5, 15, 850, beaconCluster);
    }
    
    /**
     * 2つのノード間にリンクを設定する
     * 
     * @param node1 ノード1
     * @param node2 ノード2
     * @param capacity 容量
     * @param distance 距離
     * @param beaconCluster ビーコンクラスター
     */
    private void setLinkBetween(int node1, int node2, double capacity, double distance, BeaconCluster beaconCluster) {
        // node1 -> node2 のリンク
        linkMatrix[node1][node2].setLink(beaconCluster.getBeacon(node1), beaconCluster.getBeacon(node2), capacity);
        linkMatrix[node1][node2].setD_tubeThickness(config.getInitThickness());
        linkMatrix[node1][node2].setL_tubeLength(node1 == 4 && node2 == 5 ? 3.3 : (distance / 250));
        linkMatrix[node1][node2].setDistance(distance);
        linkMatrix[node1][node2].setCongestionRate(config.getInitRate());
        
        // node2 -> node1 のリンク（双方向）
        linkMatrix[node2][node1].setLink(beaconCluster.getBeacon(node2), beaconCluster.getBeacon(node1), capacity);
        linkMatrix[node2][node1].setD_tubeThickness(config.getInitThickness());
        linkMatrix[node2][node1].setL_tubeLength(node1 == 4 && node2 == 5 ? 3.3 : (distance / 250));
        linkMatrix[node2][node1].setDistance(distance);
        linkMatrix[node2][node1].setCongestionRate(config.getInitRate());
    }
    
    /**
     * ランダムなリンクを設定する
     * 
     * @param nodeNum ノード数
     * @param beaconCluster ビーコンクラスター
     */
    @Override
    public void setLinkRandom(int nodeNum, BeaconCluster beaconCluster) {
        this.nodeNum = nodeNum;
        if (linkMatrix == null || linkMatrix.length != nodeNum) {
            initializeLinkMatrix();
        }
        
        double maxDistance = sqrt(2);  // 最大距離
        
        for (int i = 0; i < nodeNum; i++) {
            for (int j = 0; j < nodeNum; j++) {
                linkMatrix[i][j].setD_tubeThickness(0.0);
                linkMatrix[i][j].setInitD_tubeThickness(0.0);
                linkMatrix[i][j].setL_tubeLength(config.getInf());
                linkMatrix[i][j].setInitL_tubeLength(config.getInf());
                if (i != j) {
                    double distance = sqrt(Math.pow(beaconCluster.getBeacon(i).getX() - beaconCluster.getBeacon(j).getX(), 2) + 
                                          Math.pow(beaconCluster.getBeacon(i).getY() - beaconCluster.getBeacon(j).getY(), 2));
                    linkMatrix[i][j].setDistance(distance);
                }
            }
        }
        
        for (int i = 0; i < nodeNum; i++) {
            for (int j = i + 1; j < nodeNum; j++) {
                if (0.0 < linkMatrix[i][j].getDistance() && linkMatrix[i][j].getDistance() <= config.getThreshold1()) {
                    initializeLink(linkMatrix[i][j], beaconCluster.getBeacon(i), beaconCluster.getBeacon(j));
                    initializeLink(linkMatrix[j][i], beaconCluster.getBeacon(j), beaconCluster.getBeacon(i));
                }
            }
        }
    }
    
    /**
     * リンクを初期化する
     * 
     * @param link 初期化するリンク
     * @param start 開始ビーコン
     * @param end 終了ビーコン
     */
    @Override
    public void initializeLink(Link link, Beacon start, Beacon end) {
        link.setLink(start, end, 3);
        link.setD_tubeThickness(config.getInitThickness());
        link.setInitD_tubeThickness(config.getInitThickness());
        link.setL_tubeLength(link.getDistance() * 10);
        link.setInitL_tubeLength(link.getDistance() * 10);
        link.setDistance(link.getDistance() * 1000);
        link.setCongestionRate(config.getInitRate());
    }
    
    /**
     * すべてのフィールドをリセットする
     */
    @Override
    public void reset() {
        for (int i = 0; i < nodeNum; i++) {
            for (int j = 0; j < nodeNum; j++) {
                linkMatrix[i][j].setD_tubeThickness(0.0);
                linkMatrix[i][j].setL_tubeLength(config.getInf());
                linkMatrix[i][j].setQ_tubeFlow(0.0);
            }
        }
        
        // 手動でリンクを再設定
        linkMatrix[0][1].setD_tubeThickness(config.getInitThickness());
        linkMatrix[0][1].setL_tubeLength(1);
        linkMatrix[1][0].setD_tubeThickness(config.getInitThickness());
        linkMatrix[1][0].setL_tubeLength(1);
        
        linkMatrix[0][2].setD_tubeThickness(config.getInitThickness());
        linkMatrix[0][2].setL_tubeLength(2);
        linkMatrix[2][0].setD_tubeThickness(config.getInitThickness());
        linkMatrix[2][0].setL_tubeLength(2);
        
        linkMatrix[0][3].setD_tubeThickness(config.getInitThickness());
        linkMatrix[0][3].setL_tubeLength(2);
        linkMatrix[3][0].setD_tubeThickness(config.getInitThickness());
        linkMatrix[3][0].setL_tubeLength(2);
        
        linkMatrix[1][4].setD_tubeThickness(config.getInitThickness());
        linkMatrix[1][4].setL_tubeLength(2);
        linkMatrix[4][1].setD_tubeThickness(config.getInitThickness());
        linkMatrix[4][1].setL_tubeLength(2);
        
        linkMatrix[2][3].setD_tubeThickness(config.getInitThickness());
        linkMatrix[2][3].setL_tubeLength(1);
        linkMatrix[3][2].setD_tubeThickness(config.getInitThickness());
        linkMatrix[3][2].setL_tubeLength(1);
        
        linkMatrix[2][5].setD_tubeThickness(config.getInitThickness());
        linkMatrix[2][5].setL_tubeLength(3);
        linkMatrix[5][2].setD_tubeThickness(config.getInitThickness());
        linkMatrix[5][2].setL_tubeLength(3);
        
        linkMatrix[3][5].setD_tubeThickness(config.getInitThickness());
        linkMatrix[3][5].setL_tubeLength(2);
        linkMatrix[5][3].setD_tubeThickness(config.getInitThickness());
        linkMatrix[5][3].setL_tubeLength(2);
        
        linkMatrix[4][5].setD_tubeThickness(config.getInitThickness());
        linkMatrix[4][5].setL_tubeLength(3.3);
        linkMatrix[5][4].setD_tubeThickness(config.getInitThickness());
        linkMatrix[5][4].setL_tubeLength(3.3);
    }
    
    /**
     * ランダム設定のフィールドをリセットする
     */
    @Override
    public void resetRandom() {
        for (int i = 0; i < nodeNum; i++) {
            for (int j = 0; j < nodeNum; j++) {
                linkMatrix[i][j].setD_tubeThickness(0.0);
                linkMatrix[i][j].setL_tubeLength(config.getInf());
                linkMatrix[i][j].setQ_tubeFlow(0.0);
            }
        }
        
        // 初期値を復元
        for (int i = 0; i < nodeNum; i++) {
            for (int j = 0; j < nodeNum; j++) {
                double initD_tubeThickness = linkMatrix[i][j].getInitD_tubeThickness();
                double initL_tubeLength = linkMatrix[i][j].getInitL_tubeLength();
                linkMatrix[i][j].setD_tubeThickness(initD_tubeThickness);
                linkMatrix[i][j].setL_tubeLength(initL_tubeLength);
            }
        }
    }
    
    /**
     * ノード数を取得する
     * 
     * @return ノード数
     */
    @Override
    public int getNodeNum() {
        return nodeNum;
    }
    
    /**
     * リンク行列を取得する
     * 
     * @return リンク行列
     */
    @Override
    public Link[][] getLinkMatrix() {
        return linkMatrix;
    }
    
    /**
     * ノード数を設定する
     * 
     * @param nodeNum ノード数
     */
    public void setNodeNum(int nodeNum) {
        this.nodeNum = nodeNum;
        if (linkMatrix == null || linkMatrix.length != nodeNum) {
            initializeLinkMatrix();
        }
    }
}
