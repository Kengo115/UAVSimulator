package server.uav;

import item.Uav;

/**
 * UAVの状態変更を通知するリスナーインターフェース
 */
public interface UAVStateListener {
    
    /**
     * UAVが目的地に到着した時に呼び出されるメソッド
     * 
     * @param uav 到着したUAV
     */
    void onUAVArrived(Uav uav);
    
    /**
     * UAVが待機状態になった時に呼び出されるメソッド
     * 
     * @param uav 待機状態になったUAV
     * @param nodeId 待機ノードID
     */
    void onUAVWaiting(Uav uav, int nodeId);
    
    /**
     * UAVが飛行を開始した時に呼び出されるメソッド
     * 
     * @param uav 飛行を開始したUAV
     */
    void onUAVStarted(Uav uav);
}
