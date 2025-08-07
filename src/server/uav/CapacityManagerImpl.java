package server.uav;

import item.Link;
import server.util.ConfigurationManager;

/**
 * リンク容量を管理する実装クラス
 */
public class CapacityManagerImpl implements CapacityManager {
    
    private Link[][] linkMatrix;
    private int nodeNum;
    private ConfigurationManager config;
    
    /**
     * コンストラクタ
     * 
     * @param linkMatrix リンク行列
     * @param nodeNum ノード数
     */
    public CapacityManagerImpl(Link[][] linkMatrix, int nodeNum) {
        this.linkMatrix = linkMatrix;
        this.nodeNum = nodeNum;
        this.config = ConfigurationManager.getInstance();
    }
    
    /**
     * リンク容量を更新する
     * 
     * @param flyingUAVMatrix 飛行中のUAVの分布を表す行列
     */
    @Override
    public void updateCapacity(int[][] flyingUAVMatrix) {
        // 容量を初期値に戻す
        resetCapacity();
        
        // 各リンクの初期容量から飛行中のUAV分を減少
        for (int i = 0; i < nodeNum; i++) {
            for (int j = 0; j < nodeNum; j++) {
                if (linkMatrix[i][j].getL_tubeLength() != config.getInf() && flyingUAVMatrix[i][j] > 0) {
                    double newCapacity = linkMatrix[i][j].getCapacity() - flyingUAVMatrix[i][j];
                    linkMatrix[i][j].setCapacity(Math.max(0, newCapacity));
                    linkMatrix[j][i].setCapacity(Math.max(0, newCapacity));
                }
            }
        }
    }
    
    /**
     * リンク容量を初期値に戻す
     */
    @Override
    public void resetCapacity() {
        for (int i = 0; i < nodeNum; i++) {
            for (int j = 0; j < nodeNum; j++) {
                if (linkMatrix[i][j].getL_tubeLength() != config.getInf()) {
                    linkMatrix[i][j].setCapacity(linkMatrix[i][j].getInitCapacity());
                    linkMatrix[j][i].setCapacity(linkMatrix[j][i].getInitCapacity());
                }
            }
        }
    }
    
    /**
     * リンク容量を減少させる
     * 
     * @param startNode 開始ノード
     * @param endNode 終了ノード
     * @param amount 減少量
     */
    @Override
    public void decrementCapacity(int startNode, int endNode, double amount) {
        double newCapacity = linkMatrix[startNode][endNode].getCapacity() - amount;
        linkMatrix[startNode][endNode].setCapacity(Math.max(0, newCapacity));
        linkMatrix[endNode][startNode].setCapacity(Math.max(0, newCapacity));
    }
    
    /**
     * リンク容量を取得する
     * 
     * @param startNode 開始ノード
     * @param endNode 終了ノード
     * @return リンク容量
     */
    @Override
    public double getCapacity(int startNode, int endNode) {
        return linkMatrix[startNode][endNode].getCapacity();
    }
    
    /**
     * 初期リンク容量を取得する
     * 
     * @param startNode 開始ノード
     * @param endNode 終了ノード
     * @return 初期リンク容量
     */
    @Override
    public double getInitCapacity(int startNode, int endNode) {
        return linkMatrix[startNode][endNode].getInitCapacity();
    }
    
    /**
     * 混雑率を計算する
     * 
     * @param startNode 開始ノード
     * @param endNode 終了ノード
     */
    @Override
    public void calcCongestionRate(int startNode, int endNode) {
        linkMatrix[startNode][endNode].calcCongestionRate();
    }
    
    /**
     * 混雑率を取得する
     * 
     * @param startNode 開始ノード
     * @param endNode 終了ノード
     * @return 混雑率
     */
    @Override
    public double getCongestionRate(int startNode, int endNode) {
        return linkMatrix[startNode][endNode].getCongestionRate();
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
     * リンク行列を設定する
     * 
     * @param linkMatrix リンク行列
     */
    public void setLinkMatrix(Link[][] linkMatrix) {
        this.linkMatrix = linkMatrix;
    }
    
    /**
     * ノード数を設定する
     * 
     * @param nodeNum ノード数
     */
    public void setNodeNum(int nodeNum) {
        this.nodeNum = nodeNum;
    }
}
