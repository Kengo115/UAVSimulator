package server.uav;

import item.BeaconCluster;
import item.Link;
import item.Uav;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

/**
 * UAV管理サーバの実装クラス
 */
public class UAVManagementServerImpl implements UAVManagementServer {
    
    // クライアントIDごとの完了UAV数を管理するマップ
    private Map<Integer, Integer> finishedUAVCounters;
    
    private UAVFlightController flightController;
    private UAVQueueManager queueManager;
    private CapacityManager capacityManager;
    private FlightDataRecorder dataRecorder;
    private BeaconCluster beaconCluster;
    private int nodeNum;
    
    /**
     * コンストラクタ
     * 
     * @param flightController 飛行コントローラ
     * @param queueManager キュー管理
     * @param capacityManager 容量管理
     * @param dataRecorder データ記録
     * @param beaconCluster ビーコンクラスター
     * @param nodeNum ノード数
     */
    public UAVManagementServerImpl(UAVFlightController flightController, UAVQueueManager queueManager, 
                                  CapacityManager capacityManager, FlightDataRecorder dataRecorder,
                                  BeaconCluster beaconCluster, int nodeNum) {
        this.flightController = flightController;
        this.queueManager = queueManager;
        this.capacityManager = capacityManager;
        this.dataRecorder = dataRecorder;
        this.beaconCluster = beaconCluster;
        this.nodeNum = nodeNum;
        this.finishedUAVCounters = new HashMap<>();
        
        // UAVFlightControllerにこのインスタンスをリスナーとして設定
        if (flightController instanceof UAVFlightControllerImpl) {
            ((UAVFlightControllerImpl) flightController).setStateListener(this);
        }
    }
    
    /**
     * UAVの飛行を管理する
     * 
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     */
    @Override
    public void flyUAV(Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue) {
        int[][] flyingUAVMatrix = new int[nodeNum][nodeNum];
        
        // 飛行中のUAVを移動させる
        flightController.flyUAV(flyingUavQueue, uavQueue);
        
        // 容量の更新
        updateCapacity(flyingUAVMatrix);
        
        // 待機中のUAVを処理する
        processWaitingUAVs(uavQueue, flyingUavQueue, flyingUAVMatrix);
    }
    
    /**
     * リンク容量を更新する
     * 
     * @param flyingUAVMatrix 飛行中のUAVの分布を表す行列
     */
    @Override
    public void updateCapacity(int[][] flyingUAVMatrix) {
        capacityManager.updateCapacity(flyingUAVMatrix);
    }
    
    /**
     * 待機中のUAVを処理する
     * 
     * @param uavQueue 待機中のUAVキュー
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param flyingUAVMatrix 飛行中のUAVの分布を表す行列
     */
    @Override
    public void processWaitingUAVs(Queue<Uav> uavQueue, Queue<Uav> flyingUavQueue, int[][] flyingUAVMatrix) {
        queueManager.processWaitingUAVs(uavQueue, flyingUavQueue, flyingUAVMatrix);
    }
    
    /**
     * 飛行データを保存する
     * 
     * @param uav UAV
     * @param totalPathDistance 総飛行距離
     */
    @Override
    public void saveFlightData(Uav uav, double totalPathDistance) {
        // 現在のシステム時間を飛行時間として使用
        long flightTime = System.currentTimeMillis();
        dataRecorder.saveFlightData(flightTime, uav, totalPathDistance);
    }
    
    /**
     * ビーコンクラスターを設定する
     * 
     * @param beaconCluster ビーコンクラスター
     */
    public void setBeaconCluster(BeaconCluster beaconCluster) {
        this.beaconCluster = beaconCluster;
    }
    
    /**
     * ノード数を設定する
     * 
     * @param nodeNum ノード数
     */
    public void setNodeNum(int nodeNum) {
        this.nodeNum = nodeNum;
    }
    
    /**
     * ビーコンクラスターを取得する
     * 
     * @return ビーコンクラスター
     */
    public BeaconCluster getBeaconCluster() {
        return beaconCluster;
    }
    
    /**
     * ノード数を取得する
     * 
     * @return ノード数
     */
    public int getNodeNum() {
        return nodeNum;
    }
    
    /**
     * UAVが目的地に到着した時に呼び出されるメソッド
     * 
     * @param uav 到着したUAV
     */
    @Override
    public void onUAVArrived(Uav uav) {
        // クライアントIDに対応する完了カウンターをインクリメント
        int clientId = uav.getClientId();
        finishedUAVCounters.put(clientId, finishedUAVCounters.getOrDefault(clientId, 0) + 1);
        
        // 必要に応じて他の処理を行う
        System.out.println("UAV " + uav.getId() + " from client " + clientId + " has arrived at destination.");
    }
    
    /**
     * UAVが待機状態になった時に呼び出されるメソッド
     * 
     * @param uav 待機状態になったUAV
     * @param nodeId 待機ノードID
     */
    @Override
    public void onUAVWaiting(Uav uav, int nodeId) {
        // 待機状態になったUAVの処理
        System.out.println("UAV " + uav.getId() + " from client " + uav.getClientId() + " is waiting at node " + nodeId);
    }
    
    /**
     * UAVが飛行を開始した時に呼び出されるメソッド
     * 
     * @param uav 飛行を開始したUAV
     */
    @Override
    public void onUAVStarted(Uav uav) {
        // 飛行開始したUAVの処理
        System.out.println("UAV " + uav.getId() + " from client " + uav.getClientId() + " has started flying.");
    }
    
    /**
     * クライアントIDに対応する完了UAV数を取得する
     * 
     * @param clientId クライアントID
     * @return 完了UAV数
     */
    public int getFinishedUAVCount(int clientId) {
        return finishedUAVCounters.getOrDefault(clientId, 0);
    }
    
    /**
     * 飛行コントローラを取得する
     * 
     * @return 飛行コントローラ
     */
    public UAVFlightController getFlightController() {
        return flightController;
    }
    
    /**
     * キュー管理を取得する
     * 
     * @return キュー管理
     */
    public UAVQueueManager getQueueManager() {
        return queueManager;
    }
    
    /**
     * 容量管理を取得する
     * 
     * @return 容量管理
     */
    public CapacityManager getCapacityManager() {
        return capacityManager;
    }
    
    /**
     * データ記録を取得する
     * 
     * @return データ記録
     */
    public FlightDataRecorder getDataRecorder() {
        return dataRecorder;
    }
}
