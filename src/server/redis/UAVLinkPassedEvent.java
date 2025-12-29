package server.redis;

import java.io.Serializable;
import java.util.Arrays;

/**
 * UAVリンク通過イベント
 * Phase 3b-2a: イベント駆動アーキテクチャの基盤
 *
 * Workerがリンクを通過した際にメインプロセスに通知するイベント
 * 容量回復と待機UAVの再ジョブ化のトリガーとなる
 */
public class UAVLinkPassedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    // UAV識別情報
    private int uavId;
    private int clientId;

    // 通過したリンク情報
    private int passedFromNode;      // 通過したリンクの始点
    private int passedToNode;        // 通過したリンクの終点

    // 次のリンク情報（-1 = 最終リンク通過済み）
    private int nextFromNode;        // 次のリンクの始点
    private int nextToNode;          // 次のリンクの終点

    // 経路情報
    private int[] path;              // 飛行経路全体
    private int currentLinkIndex;    // 通過したリンクのインデックス

    // タイミング情報
    private long timestamp;          // イベント発生時刻（ミリ秒）
    private double elapsedFlightTime; // 経過飛行時間（秒）

    /**
     * デフォルトコンストラクタ（シリアライゼーション用）
     */
    public UAVLinkPassedEvent() {
    }

    /**
     * コンストラクタ
     *
     * @param uavId UAV ID
     * @param clientId クライアントID
     * @param passedFromNode 通過したリンクの始点
     * @param passedToNode 通過したリンクの終点
     * @param nextFromNode 次のリンクの始点（-1 = なし）
     * @param nextToNode 次のリンクの終点（-1 = なし）
     * @param path 飛行経路
     * @param currentLinkIndex 現在のリンクインデックス
     * @param elapsedFlightTime 経過飛行時間
     */
    public UAVLinkPassedEvent(int uavId, int clientId,
                               int passedFromNode, int passedToNode,
                               int nextFromNode, int nextToNode,
                               int[] path, int currentLinkIndex,
                               double elapsedFlightTime) {
        this.uavId = uavId;
        this.clientId = clientId;
        this.passedFromNode = passedFromNode;
        this.passedToNode = passedToNode;
        this.nextFromNode = nextFromNode;
        this.nextToNode = nextToNode;
        this.path = path;
        this.currentLinkIndex = currentLinkIndex;
        this.timestamp = System.currentTimeMillis();
        this.elapsedFlightTime = elapsedFlightTime;
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

    public int getPassedFromNode() {
        return passedFromNode;
    }

    public void setPassedFromNode(int passedFromNode) {
        this.passedFromNode = passedFromNode;
    }

    public int getPassedToNode() {
        return passedToNode;
    }

    public void setPassedToNode(int passedToNode) {
        this.passedToNode = passedToNode;
    }

    public int getNextFromNode() {
        return nextFromNode;
    }

    public void setNextFromNode(int nextFromNode) {
        this.nextFromNode = nextFromNode;
    }

    public int getNextToNode() {
        return nextToNode;
    }

    public void setNextToNode(int nextToNode) {
        this.nextToNode = nextToNode;
    }

    public int[] getPath() {
        return path;
    }

    public void setPath(int[] path) {
        this.path = path;
    }

    public int getCurrentLinkIndex() {
        return currentLinkIndex;
    }

    public void setCurrentLinkIndex(int currentLinkIndex) {
        this.currentLinkIndex = currentLinkIndex;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public double getElapsedFlightTime() {
        return elapsedFlightTime;
    }

    public void setElapsedFlightTime(double elapsedFlightTime) {
        this.elapsedFlightTime = elapsedFlightTime;
    }

    /**
     * 最終リンクを通過したかどうか
     * @return 最終リンクの場合true
     */
    public boolean isLastLink() {
        return nextFromNode == -1 && nextToNode == -1;
    }

    @Override
    public String toString() {
        return "UAVLinkPassedEvent{" +
                "uavId=" + uavId +
                ", clientId=" + clientId +
                ", passedLink=" + passedFromNode + "→" + passedToNode +
                ", nextLink=" + (isLastLink() ? "none" : nextFromNode + "→" + nextToNode) +
                ", path=" + Arrays.toString(path) +
                ", currentLinkIndex=" + currentLinkIndex +
                ", elapsedFlightTime=" + String.format("%.2f", elapsedFlightTime) + "s" +
                '}';
    }
}
