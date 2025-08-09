package item;

public class UAVWaitingTimer {
    private long startTime;
    private long totalElapsedTime; // 累積待機時間を保持
    private boolean isTiming;

    public UAVWaitingTimer() {
        this.startTime = 0;
        this.totalElapsedTime = 0;
        this.isTiming = false;
    }

    // タイマー開始
    public void start(Uav uav) {
        if (!isTiming) {
            this.startTime = System.currentTimeMillis();
            this.isTiming = true;
            System.out.println("client" + uav.getClientId() + " " + "UAV" + uav.getId() + " 待機タイマーが開始されました");
        }
    }

    // タイマー停止
    public void stop(Uav uav) {
        if (isTiming) {
            totalElapsedTime += (System.currentTimeMillis() - startTime); // 経過時間を累積
            isTiming = false;
            System.out.println("client" + uav.getClientId() + " " + "UAV" + uav.getId() + " 待機タイマーが停止されました 累積待機時間 " + totalElapsedTime / 1000 + " s");
        }
    }

    // 待機時間の取得 (秒単位)
    public long getFlightTime() {
        if (isTiming) {
            return (totalElapsedTime + (System.currentTimeMillis() - startTime)) / 1000;
        }
        return totalElapsedTime / 1000;
    }

    // タイマーのリセット
    public void reset() {
        this.startTime = 0;
        this.totalElapsedTime = 0; // 累積時間をリセット
        this.isTiming = false;
        System.out.println("待機タイマーがリセットされました。");
    }

    // タイマーのキャンセル
    public void cancel(Uav uav) {
        if (isTiming) {
            stop(uav);
        }
        System.out.println("client" + uav.getClientId() + " " + "UAV" + uav.getId() + " 待機タイマーがキャンセルされました");
    }
}
