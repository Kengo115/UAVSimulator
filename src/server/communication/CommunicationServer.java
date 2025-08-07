package server.communication;

import client.Client;
import item.BeaconCluster;
import item.Link;
import item.Uav;

import java.io.IOException;

/**
 * 通信管理サーバのインターフェース
 * クライアントとの通信、ネットワークトポロジーの設定、結果出力を担当
 */
public interface CommunicationServer {
    
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
     * Pajekファイル形式でネットワークトポロジーを出力する
     * 
     * @param filePath ファイルパス
     * @param client クライアント
     * @param beaconCluster ビーコンクラスター
     * @throws IOException 入出力例外
     */
    void nodeConfigureToPajek(String filePath, Client client, BeaconCluster beaconCluster) throws IOException;
    
    /**
     * Pajekファイル形式で結果を出力する
     * 
     * @param client クライアント
     * @param eps イプシロン値
     * @param allFlow 全体の流量
     * @param ct カウンター
     * @throws IOException 入出力例外
     */
    void outputToPajek(Client client, double eps, double allFlow, int ct) throws IOException;
    
    /**
     * Excel形式で結果を出力する
     * 
     * @param client クライアント
     * @param ct カウンター
     * @throws IOException 入出力例外
     */
    void outputToExcel(Client client, int ct) throws IOException;
    
    /**
     * テキストファイル形式で結果を出力する
     * 
     * @param client クライアント
     * @param ct カウンター
     * @throws IOException 入出力例外
     */
    void outputToTxt(Client client, int ct) throws IOException;
    
    /**
     * 経路情報をファイルに出力する
     * 
     * @param currentUAV 現在のUAV
     * @param method 使用したメソッド
     */
    void outputRoute(Uav currentUAV, String method);
    
    /**
     * フロー情報をファイルに出力する
     * 
     * @param client クライアント
     * @param ct カウンター
     * @throws IOException 入出力例外
     */
    void outputToFlow(Client client, int ct) throws IOException;
    
    /**
     * 経路ごとのUAV数をExcel形式で出力する
     * 
     * @param client クライアント
     * @param ct カウンター
     * @throws IOException 入出力例外
     */
    void outputRouteToExcel(Client client, int ct) throws IOException;
}
