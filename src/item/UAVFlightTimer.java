package item;

import server.util.LogManager;

public class UAVFlightTimer {
    private long startTime;
    private long totalElapsedTime;
    private boolean isTiming;

    public UAVFlightTimer() {
        this.startTime = 0;
        this.totalElapsedTime = 0;
        this.isTiming = false;
    }

    public void start(Uav uav) {
        if (!isTiming) {
            this.startTime = System.currentTimeMillis();
            this.isTiming = true;
            String message = "client" + uav.getClientId() + " No" + uav.getId() + " 飛行タイマーが開始されました。";
            LogManager.getInstance().log(message);
        }
    }

    public void stop(Uav uav) {
        if (isTiming) {
            totalElapsedTime += (System.currentTimeMillis() - startTime);
            isTiming = false;
            String message = "client" + uav.getClientId() + " No" + uav.getId() + " 飛行タイマーが停止されました 累積飛行時間 " + totalElapsedTime / 1000 + " s";
            LogManager.getInstance().log(message);
        }
    }

    public long getFlightTime() {
        if (isTiming) {
            return (totalElapsedTime + (System.currentTimeMillis() - startTime)) / 1000;
        }
        return totalElapsedTime / 1000;
    }

    public void reset() {
        this.startTime = 0;
        this.totalElapsedTime = 0;
        this.isTiming = false;
        LogManager.getInstance().log("飛行タイマーがリセットされました");
    }

    public void cancel(Uav uav) {
        if (isTiming) {
            stop(uav);
        }
        String message = "client" + uav.getClientId() + " No" + uav.getId() + " が目的地に到着しました。";
        LogManager.getInstance().log(message);
    }
}
