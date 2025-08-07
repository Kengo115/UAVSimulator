package server.communication;

import item.BeaconCluster;
import item.Link;

/**
 * ネットワークトポロジー管理のインターフェース
 * ビーコンとリンクの初期化と設定を担当
 */
public interface NetworkTopologyManager {
    
    /**
     * ネットワークトポロジーを設定する
     * 
     * @param nodeNum ノード数
     * @param beaconCluster ビーコンクラスター
     */
    void setNetworkTopology(int nodeNum, BeaconCluster beaconCluster);
    
    /**
     * リンクを設定する
     * 
     * @param nodeNum ノード数
     * @param beaconCluster ビーコンクラスター
     */
    void setLink(int nodeNum, BeaconCluster beaconCluster);
    
    /**
     * ランダムなリンクを設定する
     * 
     * @param nodeNum ノード数
     * @param beaconCluster ビーコンクラスター
     */
    void setLinkRandom(int nodeNum, BeaconCluster beaconCluster);
    
    /**
     * リンクを初期化する
     * 
     * @param link 初期化するリンク
     * @param start 開始ビーコン
     * @param end 終了ビーコン
     */
    void initializeLink(Link link, item.Beacon start, item.Beacon end);
    
    /**
     * すべてのフィールドをリセットする
     */
    void reset();
    
    /**
     * ランダム設定のフィールドをリセットする
     */
    void resetRandom();
    
    /**
     * ノード数を取得する
     * 
     * @return ノード数
     */
    int getNodeNum();
    
    /**
     * リンク行列を取得する
     * 
     * @return リンク行列
     */
    Link[][] getLinkMatrix();
    
    /**
     * ノード数を設定する
     * 
     * @param nodeNum ノード数
     */
    void setNodeNum(int nodeNum);
}
