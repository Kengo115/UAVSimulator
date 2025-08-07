package server.uav;

import item.Beacon;
import item.BeaconCluster;
import item.Link;
import item.Uav;

import java.util.Queue;

/**
 * UAVの飛行を制御する実装クラス
 */
public class UAVFlightControllerImpl implements UAVFlightController {
    
    private Link[][] linkMatrix;
    private BeaconCluster beaconCluster;
    private FlightDataRecorder dataRecorder;
    private UAVQueueManager queueManager;
    private int nodeNum;
    private UAVStateListener stateListener;
    
    /**
     * コンストラクタ
     * 
     * @param linkMatrix リンク行列
     * @param beaconCluster ビーコンクラスター
     * @param dataRecorder データ記録
     * @param queueManager キュー管理
     * @param nodeNum ノード数
     */
    public UAVFlightControllerImpl(Link[][] linkMatrix, BeaconCluster beaconCluster, 
                                  FlightDataRecorder dataRecorder, UAVQueueManager queueManager,
                                  int nodeNum) {
        this.linkMatrix = linkMatrix;
        this.beaconCluster = beaconCluster;
        this.dataRecorder = dataRecorder;
        this.queueManager = queueManager;
        this.nodeNum = nodeNum;
        this.stateListener = null;
    }
    
    /**
     * UAVの状態変更を通知するリスナーを設定する
     * 
     * @param listener UAV状態リスナー
     */
    public void setStateListener(UAVStateListener listener) {
        this.stateListener = listener;
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
        int queueSize = flyingUavQueue.size();
        for (int i = 0; i < queueSize; i++) {
            Uav uav = flyingUavQueue.poll();
            if (uav != null) {
                moveUAV(uav, flyingUavQueue, uavQueue, flyingUAVMatrix);
            }
        }
    }
    
    /**
     * 飛行中のUAVを移動させる
     * 
     * @param uav 移動させるUAV
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param flyingUAVMatrix 飛行中のUAVの分布を表す行列
     */
    @Override
    public void moveUAV(Uav uav, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, int[][] flyingUAVMatrix) {
        double flightDistance = uav.getFlightTime() * uav.getSpeed();
        int[] path = uav.getPath();
        
        double totalPathDistance = calculateTotalPathDistance(path);
        
        if (flightDistance >= totalPathDistance) {
            // 目的地に到着した場合
            if (uav.isFlying()) {
                uav.cancelTimer();
            } else {
                System.out.println("要修正0");
            }
            
            // UAV状態リスナーに通知
            if (stateListener != null) {
                stateListener.onUAVArrived(uav);
            }
            
            // ログの保存
            saveFlightData(uav, totalPathDistance);
        } else {
            // まだ飛行中の場合
            double traveledDistance = 0.0;
            for (int k = 0; k < path.length - 1; k++) {
                int startNode = path[k];
                int endNode = path[k + 1];
                double linkLength = linkMatrix[startNode][endNode].getDistance();
                
                if (traveledDistance + linkLength >= flightDistance) {
                    // このリンク上にUAVがある
                    if (linkMatrix[startNode][endNode] != uav.getFlyingLink()) {
                        // 新しいリンクに入る場合
                        if (linkMatrix[startNode][endNode].getCapacity() > 0) {
                            // 容量があれば飛行継続
                            uav.setFlyingLink(linkMatrix[startNode][endNode]);
                            flyingUAVMatrix[startNode][endNode]++;
                            System.out.println("client " + uav.getClientId() + " :UAV " + uav.getId() + " が " + startNode + " → " + endNode + " へ移動");
                            flyingUavQueue.add(uav);
                        } else {
                            // 容量がなければ待機
                            if (uav.isFlying()) {
                                uav.stopTimer();
                            } else {
                                System.out.println("要修正1: client " + uav.getClientId() + " :UAV " + uav.getId() + " が飛行中でないのに stopTimer() が呼ばれました");
                            }
                            queueManager.setUAVToWaiting(uav, startNode, uavQueue);
                        }
                    } else {
                        // 同じリンク上を飛行継続
                        flyingUAVMatrix[startNode][endNode]++;
                        flyingUavQueue.add(uav);
                    }
                    break;
                } else {
                    // このリンクは通過済み
                    traveledDistance += linkLength;
                }
            }
        }
    }
    
    /**
     * 総飛行距離を計算する
     * 
     * @param path 経路
     * @return 総飛行距離
     */
    private double calculateTotalPathDistance(int[] path) {
        double totalDistance = 0.0;
        for (int k = 0; k < path.length - 1; k++) {
            int startNode = path[k];
            int endNode = path[k + 1];
            totalDistance += linkMatrix[startNode][endNode].getDistance();
        }
        return totalDistance;
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
     * リンク容量を取得する
     * 
     * @param startNode 開始ノード
     * @param endNode 終了ノード
     * @return リンク容量
     */
    @Override
    public double getLinkCapacity(int startNode, int endNode) {
        return linkMatrix[startNode][endNode].getCapacity();
    }
    
    /**
     * リンク距離を取得する
     * 
     * @param startNode 開始ノード
     * @param endNode 終了ノード
     * @return リンク距離
     */
    @Override
    public double getLinkDistance(int startNode, int endNode) {
        return linkMatrix[startNode][endNode].getDistance();
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
     * ビーコンクラスターを設定する
     * 
     * @param beaconCluster ビーコンクラスター
     */
    public void setBeaconCluster(BeaconCluster beaconCluster) {
        this.beaconCluster = beaconCluster;
    }
    
    /**
     * データ記録を設定する
     * 
     * @param dataRecorder データ記録
     */
    public void setDataRecorder(FlightDataRecorder dataRecorder) {
        this.dataRecorder = dataRecorder;
    }
    
    /**
     * キュー管理を設定する
     * 
     * @param queueManager キュー管理
     */
    public void setQueueManager(UAVQueueManager queueManager) {
        this.queueManager = queueManager;
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
