package server.uav;

import item.Uav;

import java.util.Queue;

/**
 * UAV管理サーバのインターフェース
 * UAVの飛行状況を管理する
 */
public interface UAVManagementServer extends UAVStateListener {
    
    /**
     * UAVの飛行を管理する
     * 
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     */
    void flyUAV(Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue);
    
    /**
     * リンク容量を更新する
     * 
     * @param flyingUAVMatrix 飛行中のUAVの分布を表す行列
     */
    void updateCapacity(int[][] flyingUAVMatrix);
    
    /**
     * 待機中のUAVを処理する
     * 
     * @param uavQueue 待機中のUAVキュー
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param flyingUAVMatrix 飛行中のUAVの分布を表す行列
     */
    void processWaitingUAVs(Queue<Uav> uavQueue, Queue<Uav> flyingUavQueue, int[][] flyingUAVMatrix);
    
    /**
     * 飛行データを保存する
     * 
     * @param uav UAV
     * @param totalPathDistance 総飛行距離
     */
    void saveFlightData(Uav uav, double totalPathDistance);
}
