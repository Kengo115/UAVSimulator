package network_manager.redis;

import java.io.Serializable;
import java.util.Arrays;

/**
 * UAV飛行完了イベント
 * Phase 3b-2a: イベント駆動アーキテクチャの基盤
 *
 * Workerが飛行完了した際にメインプロセスに通知するイベント
 * 統計更新と最終リンクの容量回復のトリガーとなる
 */
public class UAVCompletionEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    // UAV識別情報
    private int uavId;
    private int clientId;

    // 飛行結果情報
    private long arrivalTime;        // 到着時刻（ミリ秒）
    private double totalDistance;    // 総飛行距離（メートル）
    private double actualFlightTime; // 実飛行時間（秒）
    private double totalWaitingTime; // 総待機時間（秒）

    // 経路情報
    private int[] path;              // 飛行経路
    private int sourceBeaconId;      // 出発地ビーコンID
    private int destinationBeaconId; // 目的地ビーコンID

    // 最終リンク情報（容量回復用）
    private int lastLinkFromNode;    // 最終リンクの始点
    private int lastLinkToNode;      // 最終リンクの終点

    /**
     * デフォルトコンストラクタ（シリアライゼーション用）
     */
    public UAVCompletionEvent() {
    }

    /**
     * コンストラクタ
     *
     * @param uavId UAV ID
     * @param clientId クライアントID
     * @param totalDistance 総飛行距離
     * @param actualFlightTime 実飛行時間
     * @param totalWaitingTime 総待機時間
     * @param path 飛行経路
     * @param sourceBeaconId 出発地
     * @param destinationBeaconId 目的地
     */
    public UAVCompletionEvent(int uavId, int clientId,
                               double totalDistance, double actualFlightTime,
                               double totalWaitingTime, int[] path,
                               int sourceBeaconId, int destinationBeaconId) {
        this.uavId = uavId;
        this.clientId = clientId;
        this.arrivalTime = System.currentTimeMillis();
        this.totalDistance = totalDistance;
        this.actualFlightTime = actualFlightTime;
        this.totalWaitingTime = totalWaitingTime;
        this.path = path;
        this.sourceBeaconId = sourceBeaconId;
        this.destinationBeaconId = destinationBeaconId;

        // 最終リンク情報を設定
        if (path != null && path.length >= 2) {
            this.lastLinkFromNode = path[path.length - 2];
            this.lastLinkToNode = path[path.length - 1];
        } else {
            this.lastLinkFromNode = -1;
            this.lastLinkToNode = -1;
        }
    }

    // Getters and Setters

    public int getUavId() {
        return uavId;
    }

    public void setUavId(int uavId) {
        this.uavId = uavId;
    }

    public int getClientId() {
        return clientId;
    }

    public void setClientId(int clientId) {
        this.clientId = clientId;
    }

    public long getArrivalTime() {
        return arrivalTime;
    }

    public void setArrivalTime(long arrivalTime) {
        this.arrivalTime = arrivalTime;
    }

    public double getTotalDistance() {
        return totalDistance;
    }

    public void setTotalDistance(double totalDistance) {
        this.totalDistance = totalDistance;
    }

    public double getActualFlightTime() {
        return actualFlightTime;
    }

    public void setActualFlightTime(double actualFlightTime) {
        this.actualFlightTime = actualFlightTime;
    }

    public double getTotalWaitingTime() {
        return totalWaitingTime;
    }

    public void setTotalWaitingTime(double totalWaitingTime) {
        this.totalWaitingTime = totalWaitingTime;
    }

    public int[] getPath() {
        return path;
    }

    public void setPath(int[] path) {
        this.path = path;
    }

    public int getSourceBeaconId() {
        return sourceBeaconId;
    }

    public void setSourceBeaconId(int sourceBeaconId) {
        this.sourceBeaconId = sourceBeaconId;
    }

    public int getDestinationBeaconId() {
        return destinationBeaconId;
    }

    public void setDestinationBeaconId(int destinationBeaconId) {
        this.destinationBeaconId = destinationBeaconId;
    }

    public int getLastLinkFromNode() {
        return lastLinkFromNode;
    }

    public void setLastLinkFromNode(int lastLinkFromNode) {
        this.lastLinkFromNode = lastLinkFromNode;
    }

    public int getLastLinkToNode() {
        return lastLinkToNode;
    }

    public void setLastLinkToNode(int lastLinkToNode) {
        this.lastLinkToNode = lastLinkToNode;
    }

    /**
     * 飛行効率を計算（実飛行時間 / 総時間）
     * @return 効率（0.0〜1.0）
     */
    public double getFlightEfficiency() {
        double totalTime = actualFlightTime + totalWaitingTime;
        if (totalTime <= 0) {
            return 1.0;
        }
        return actualFlightTime / totalTime;
    }

    @Override
    public String toString() {
        return "UAVCompletionEvent{" +
                "uavId=" + uavId +
                ", clientId=" + clientId +
                ", path=" + Arrays.toString(path) +
                ", totalDistance=" + String.format("%.2f", totalDistance) + "m" +
                ", flightTime=" + String.format("%.2f", actualFlightTime) + "s" +
                ", waitingTime=" + String.format("%.2f", totalWaitingTime) + "s" +
                ", efficiency=" + String.format("%.1f", getFlightEfficiency() * 100) + "%" +
                ", lastLink=" + lastLinkFromNode + "→" + lastLinkToNode +
                '}';
    }
}
