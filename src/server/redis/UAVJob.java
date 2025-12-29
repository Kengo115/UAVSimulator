package server.redis;

import java.io.Serializable;
import java.util.Arrays;

/**
 * UAVジョブの定義
 * Phase 3b: ワーカープロセスに渡すジョブ情報
 *
 * Serializableを実装してRedisに保存可能にする
 */
public class UAVJob implements Serializable {

    private static final long serialVersionUID = 1L;

    // UAV識別情報
    private int uavId;
    private int clientId;

    // 飛行情報
    private int[] path;                    // 飛行経路（ビーコンIDの配列）
    private double speed;                  // 速度（m/s）
    private long startTime;                // 飛行開始時刻（ミリ秒）

    // 目的地情報
    private int sourceBeaconId;            // 出発地ビーコンID
    private int destinationBeaconId;       // 目的地ビーコンID

    // 経路追跡
    private int currentPathIndex;          // 現在の経路インデックス（0から開始）

    // Phase 3b-2b: リンク距離情報
    private double[] linkDistances;        // 各リンクの距離（メートル）

    /**
     * デフォルトコンストラクタ（シリアライゼーション用）
     */
    public UAVJob() {
    }

    /**
     * コンストラクタ
     *
     * @param uavId UAV ID
     * @param clientId クライアントID
     * @param path 飛行経路
     * @param speed 速度
     * @param startTime 開始時刻
     * @param sourceBeaconId 出発地
     * @param destinationBeaconId 目的地
     */
    public UAVJob(int uavId, int clientId, int[] path, double speed, long startTime,
                  int sourceBeaconId, int destinationBeaconId) {
        this.uavId = uavId;
        this.clientId = clientId;
        this.path = path;
        this.speed = speed;
        this.startTime = startTime;
        this.sourceBeaconId = sourceBeaconId;
        this.destinationBeaconId = destinationBeaconId;
        this.currentPathIndex = 0;
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

    public int[] getPath() {
        return path;
    }

    public void setPath(int[] path) {
        this.path = path;
    }

    public double getSpeed() {
        return speed;
    }

    public void setSpeed(double speed) {
        this.speed = speed;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
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

    public int getCurrentPathIndex() {
        return currentPathIndex;
    }

    public void setCurrentPathIndex(int currentPathIndex) {
        this.currentPathIndex = currentPathIndex;
    }

    // Phase 3b-2b: リンク距離関連メソッド

    public double[] getLinkDistances() {
        return linkDistances;
    }

    public void setLinkDistances(double[] linkDistances) {
        this.linkDistances = linkDistances;
    }

    /**
     * 指定インデックスのリンク距離を取得
     * @param linkIndex リンクインデックス
     * @return リンク距離（メートル）
     */
    public double getLinkDistance(int linkIndex) {
        if (linkDistances == null || linkIndex < 0 || linkIndex >= linkDistances.length) {
            return 0.0;
        }
        return linkDistances[linkIndex];
    }

    /**
     * 経路の総距離を計算する
     * @return 総距離（メートル）
     */
    public double getTotalDistance() {
        if (linkDistances == null || linkDistances.length == 0) {
            return 0.0;
        }
        double total = 0.0;
        for (double distance : linkDistances) {
            total += distance;
        }
        return total;
    }

    /**
     * 現在位置から目的地までの残り距離を計算
     * @return 残り距離（メートル）
     */
    public double getRemainingDistance() {
        if (linkDistances == null || currentPathIndex >= linkDistances.length) {
            return 0.0;
        }
        double remaining = 0.0;
        for (int i = currentPathIndex; i < linkDistances.length; i++) {
            remaining += linkDistances[i];
        }
        return remaining;
    }

    @Override
    public String toString() {
        return "UAVJob{" +
                "uavId=" + uavId +
                ", clientId=" + clientId +
                ", path=" + Arrays.toString(path) +
                ", linkDistances=" + Arrays.toString(linkDistances) +
                ", speed=" + speed +
                ", startTime=" + startTime +
                ", sourceBeaconId=" + sourceBeaconId +
                ", destinationBeaconId=" + destinationBeaconId +
                ", currentPathIndex=" + currentPathIndex +
                ", totalDistance=" + getTotalDistance() +
                '}';
    }
}
