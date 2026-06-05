package shared.item;

import shared.util.LogManager;
import java.util.Timer;

public class ClientTimer {
    private Timer timer;
    private long startTime;
    private long endTime;
    private boolean isTiming;

    public ClientTimer() {
        this.timer = new Timer();
        this.startTime = 0;
        this.endTime = 0;
        this.isTiming = false;
    }

    // タイマー開始
    public void start() {
        if (!isTiming) {
            this.startTime = System.currentTimeMillis();
            this.isTiming = true;
            LogManager.getInstance().log("クライアントタイマー開始 startTime = " + startTime);
        } else {
            LogManager.getInstance().log("クライアントタイマーは既に開始されています");
        }
    }

    // タイマー終了と飛行時間の表示
    public void stop() {
        if (isTiming) {
            this.endTime = System.currentTimeMillis();
            long flightTime = getFlightTime();
            LogManager.getInstance().log("クライアントタイマーが停止されました 最終経過時間 " + flightTime + " s");
            this.isTiming = false;
        } else {
            LogManager.getInstance().log("クライアントタイマーは停止しています");
        }
    }

    // 飛行時間の取得 (秒単位)
    public long getFlightTime() {
        long flightTime;
        if (isTiming) {
            LogManager.getInstance().log("クライアントタイマーが動作中です");
            flightTime = (System.currentTimeMillis() - startTime) / 1000;
            return flightTime; // タイマーが動作中の場合の経過時間
        } else {
            LogManager.getInstance().log("クライアントタイマーは停止しています");
            flightTime = (endTime - startTime) / 1000;
            return flightTime; // タイマー停止後の最終飛行時間
        }
    }



    // タイマーのリセット
    public void reset() {
        this.startTime = 0;
        this.endTime = 0;
        this.isTiming = false;
        this.stop();
        LogManager.getInstance().log("クライアントタイマーがリセットされました");
    }

    // タイマーのキャンセル
    public void cancel() {
        this.stop();
        this.timer.cancel();
        LogManager.getInstance().log("クライアントタイマーがキャンセルされました");
    }
}
