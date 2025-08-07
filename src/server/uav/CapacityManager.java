package server.uav;

import item.Link;

/**
 * リンク容量を管理するインターフェース
 */
public interface CapacityManager {
    
    /**
     * リンク容量を更新する
     * 
     * @param flyingUAVMatrix 飛行中のUAVの分布を表す行列
     */
    void updateCapacity(int[][] flyingUAVMatrix);
    
    /**
     * リンク容量を初期値に戻す
     */
    void resetCapacity();
    
    /**
     * リンク容量を減少させる
     * 
     * @param startNode 開始ノード
     * @param endNode 終了ノード
     * @param amount 減少量
     */
    void decrementCapacity(int startNode, int endNode, double amount);
    
    /**
     * リンク容量を取得する
     * 
     * @param startNode 開始ノード
     * @param endNode 終了ノード
     * @return リンク容量
     */
    double getCapacity(int startNode, int endNode);
    
    /**
     * 初期リンク容量を取得する
     * 
     * @param startNode 開始ノード
     * @param endNode 終了ノード
     * @return 初期リンク容量
     */
    double getInitCapacity(int startNode, int endNode);
    
    /**
     * 混雑率を計算する
     * 
     * @param startNode 開始ノード
     * @param endNode 終了ノード
     */
    void calcCongestionRate(int startNode, int endNode);
    
    /**
     * 混雑率を取得する
     * 
     * @param startNode 開始ノード
     * @param endNode 終了ノード
     * @return 混雑率
     */
    double getCongestionRate(int startNode, int endNode);
    
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
