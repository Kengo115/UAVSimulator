package server.uav;

import client.ClientController;
import item.BeaconCluster;
import item.Link;
import item.Uav;

import java.util.Queue;

/**
 * UAV管理サーバの実装クラス
 */
public class UAVManagementServerImpl implements UAVManagementServer {
    
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
    }
    
    /**
     * UAVの飛行を管理する
     * 
     * @param clientController クライアントコントローラ
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     */
    @Override
    public void flyUAV(ClientController clientController, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue) {
        int[][] flyingUAVMatrix = new int[nodeNum][nodeNum];
        
        // 飛行中のUAVを移動させる
        int queueSize = flyingUavQueue.size();
        for (int i = 0; i < queueSize; i++) {
            Uav uav = flyingUavQueue.poll();
            if (uav != null) {
                flightController.moveUAV(uav, flyingUavQueue, uavQueue, flyingUAVMatrix, clientController);
            }
        }
        
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
     * @param clientController クライアントコントローラ
     * @param uav UAV
     * @param totalPathDistance 総飛行距離
     */
    @Override
    public void saveFlightData(ClientController clientController, Uav uav, double totalPathDistance) {
        dataRecorder.saveFlightData(clientController, uav, totalPathDistance);
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
