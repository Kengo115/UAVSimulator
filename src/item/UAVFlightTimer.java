package item;

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
            System.out.println("client " + uav.getClientId() +  " : No." + uav.getId() + "の飛行タイマーが開始されました。");
        }
    }

    public void stop(Uav uav) {
        if (isTiming) {
            totalElapsedTime += (System.currentTimeMillis() - startTime);
            isTiming = false;
            System.out.println("client " + uav.getClientId() + " : No." + uav.getId() + "の飛行タイマーが停止されました。累積飛行時間: " + totalElapsedTime / 1000 + " s");
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
        System.out.println("飛行タイマーがリセットされました。");
    }

    public void cancel(Uav uav) {
        if (isTiming) {
            stop(uav);
        }
        System.out.println("client " + uav.getClientId() + " : No." + uav.getId() + "が目的地に到着しました。");
    }
}
