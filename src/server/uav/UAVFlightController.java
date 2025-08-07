package server.uav;

import client.ClientController;
import item.Uav;

import java.util.Queue;

/**
 * UAVの飛行を制御するインターフェース
 */
public interface UAVFlightController {
    
    /**
     * UAVの飛行を管理する
     * 
     * @param clientController クライアントコントローラ
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     */
    void flyUAV(ClientController clientController, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue);
    
    /**
     * 飛行中のUAVを移動させる
     * 
     * @param uav 移動させるUAV
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param flyingUAVMatrix 飛行中のUAVの分布を表す行列
     * @param clientController クライアントコントローラ
     */
    void moveUAV(Uav uav, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, int[][] flyingUAVMatrix, ClientController clientController);
    
    /**
     * 飛行データを保存する
     * 
     * @param clientController クライアントコントローラ
     * @param uav UAV
     * @param totalPathDistance 総飛行距離
     */
    void saveFlightData(ClientController clientController, Uav uav, double totalPathDistance);
    
    /**
     * リンク容量を取得する
     * 
     * @param startNode 開始ノード
     * @param endNode 終了ノード
     * @return リンク容量
     */
    double getLinkCapacity(int startNode, int endNode);
    
    /**
     * リンク距離を取得する
     * 
     * @param startNode 開始ノード
     * @param endNode 終了ノード
     * @return リンク距離
     */
    double getLinkDistance(int startNode, int endNode);
}
