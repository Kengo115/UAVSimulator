package server.uav;

import item.Beacon;
import item.BeaconCluster;
import item.Link;
import item.Uav;

import java.util.Queue;

/**
 * UAVキューを管理する実装クラス
 */
public class UAVQueueManagerImpl implements UAVQueueManager {
    
    private Link[][] linkMatrix;
    private BeaconCluster beaconCluster;
    private int nodeNum;
    
    /**
     * コンストラクタ
     * 
     * @param linkMatrix リンク行列
     * @param beaconCluster ビーコンクラスター
     * @param nodeNum ノード数
     */
    public UAVQueueManagerImpl(Link[][] linkMatrix, BeaconCluster beaconCluster, int nodeNum) {
        this.linkMatrix = linkMatrix;
        this.beaconCluster = beaconCluster;
        this.nodeNum = nodeNum;
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
        int waitQueueSize = uavQueue.size();
        for (int i = 0; i < waitQueueSize; i++) {
            Uav uav = uavQueue.poll();
            if (uav == null) continue;
            
            int[] path = uav.getPath();
            int startNode = uav.getStayedBeaconId();
            
            int nextNode = -1;
            if (startNode != -1) {
                for (int j = 0; j < path.length - 1; j++) {
                    if (path[j] == startNode) {
                        nextNode = path[j + 1];
                        break;
                    }
                }
            } else {
                System.out.println("要修正4: client " + uav.getClientId() + " :UAV " + uav.getId() + " が待機中のビーコンIDを取得できませんでした");
            }
            
            if (nextNode != -1) {
                if (flyingUAVMatrix[startNode][nextNode] < linkMatrix[startNode][nextNode].getCapacity()) {
                    // 容量があれば飛行開始
                    flyingUAVMatrix[startNode][nextNode]++;
                    setUAVToFlying(uav, startNode, nextNode, flyingUavQueue, flyingUAVMatrix);
                } else {
                    // 容量がなければ待機継続
                    uavQueue.add(uav);
                    System.out.println("client " + uav.getClientId() + " :UAV " + uav.getId() + " は容量不足のため待機継続 (" + startNode + " -> " + nextNode + ")");
                }
            } else {
                // 移動できるリンクがない場合
                System.out.println("client " + uav.getClientId() + " :UAV " + uav.getId() + " は移動できるリンクがないため待機継続");
                uavQueue.add(uav);
            }
        }
    }
    
    /**
     * UAVを待機状態にする
     * 
     * @param uav 待機状態にするUAV
     * @param beaconId 待機するビーコンのID
     * @param uavQueue 待機中のUAVキュー
     */
    @Override
    public void setUAVToWaiting(Uav uav, int beaconId, Queue<Uav> uavQueue) {
        if (!uav.isWaiting()) {
            uav.startWaitingTimer();
        } else {
            System.out.println("要修正2: client " + uav.getClientId() + " :UAV " + uav.getId() + " がすでに待機状態");
        }
        uav.setStayedBeaconId(beaconId);
        beaconCluster.getBeacon(beaconId).addUav(uav);
        beaconCluster.getBeacon(beaconId).incrementWaitingUavCount();
        uavQueue.add(uav);
    }
    
    /**
     * UAVを飛行状態にする
     * 
     * @param uav 飛行状態にするUAV
     * @param startNode 開始ノード
     * @param nextNode 次のノード
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param flyingUAVMatrix 飛行中のUAVの分布を表す行列
     */
    @Override
    public void setUAVToFlying(Uav uav, int startNode, int nextNode, Queue<Uav> flyingUavQueue, int[][] flyingUAVMatrix) {
        if (uav.isWaiting()) {
            uav.stopWaitingTimer();
        } else {
            System.out.println("要修正3: client " + uav.getClientId() + " :UAV " + uav.getId() + " は待機していないのに stopWaitingTimer() が呼ばれました");
        }
        beaconCluster.getBeacon(startNode).removeUav(uav);
        beaconCluster.getBeacon(startNode).decrementWaitingUavCount();
        uav.startTimer();
        uav.setFlyingLink(linkMatrix[startNode][nextNode]);
        uav.setStayedBeaconId(-1);
        flyingUavQueue.add(uav);
    }
    
    /**
     * 待機中のUAVの数を取得する
     * 
     * @param beaconId ビーコンID
     * @return 待機中のUAVの数
     */
    @Override
    public int getWaitingUAVCount(int beaconId) {
        return beaconCluster.getBeacon(beaconId).getWaitingUavCount();
    }
    
    /**
     * 待機中のUAVのリストを取得する
     * 
     * @param beaconId ビーコンID
     * @return 待機中のUAVのリスト
     */
    @Override
    public Uav[] getWaitingUAVList(int beaconId) {
        return beaconCluster.getBeacon(beaconId).getWaitingUavList();
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
     * ノード数を設定する
     * 
     * @param nodeNum ノード数
     */
    public void setNodeNum(int nodeNum) {
        this.nodeNum = nodeNum;
    }
}
