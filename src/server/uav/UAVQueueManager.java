package server.uav;

import item.Uav;

import java.util.Queue;

/**
 * UAVキューを管理するインターフェース
 */
public interface UAVQueueManager {
    
    /**
     * 待機中のUAVを処理する
     * 
     * @param uavQueue 待機中のUAVキュー
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param flyingUAVMatrix 飛行中のUAVの分布を表す行列
     */
    void processWaitingUAVs(Queue<Uav> uavQueue, Queue<Uav> flyingUavQueue, int[][] flyingUAVMatrix);
    
    /**
     * UAVを待機状態にする
     * 
     * @param uav 待機状態にするUAV
     * @param beaconId 待機するビーコンのID
     * @param uavQueue 待機中のUAVキュー
     */
    void setUAVToWaiting(Uav uav, int beaconId, Queue<Uav> uavQueue);
    
    /**
     * UAVを飛行状態にする
     * 
     * @param uav 飛行状態にするUAV
     * @param startNode 開始ノード
     * @param nextNode 次のノード
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param flyingUAVMatrix 飛行中のUAVの分布を表す行列
     */
    void setUAVToFlying(Uav uav, int startNode, int nextNode, Queue<Uav> flyingUavQueue, int[][] flyingUAVMatrix);
    
    /**
     * 待機中のUAVの数を取得する
     * 
     * @param beaconId ビーコンID
     * @return 待機中のUAVの数
     */
    int getWaitingUAVCount(int beaconId);
    
    /**
     * 待機中のUAVのリストを取得する
     * 
     * @param beaconId ビーコンID
     * @return 待機中のUAVのリスト
     */
    Uav[] getWaitingUAVList(int beaconId);
}
