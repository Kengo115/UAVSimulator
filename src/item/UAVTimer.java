package item;

import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
public class UAVTimer {
    private Timer timer;
    private long startTime;
    private long endTime;
    private long totalElapsedTime; // 累積飛行時間を保持
    private boolean isTiming;

    public UAVTimer() {
        this.timer = new Timer();
        this.startTime = 0;
        this.endTime = 0;
        this.totalElapsedTime = 0; // 初期化
        this.isTiming = false;
    }

    // タイマー開始
    public void start() {
        if (!isTiming) {
            this.startTime = System.currentTimeMillis();
            this.isTiming = true;
            System.out.println("UAVタイマーが開始されました。");
        } else {
            System.out.println("UAVタイマーは既に開始されています。");
        }
    }

    // タイマー終了と飛行時間の表示
    public void stop() {
        if (isTiming) {
            this.endTime = System.currentTimeMillis();
            totalElapsedTime += (endTime - startTime); // 経過時間を累積
            System.out.println("UAVタイマーが停止されました。累積飛行時間: " + totalElapsedTime / 1000 + " s");
            this.isTiming = false;
        } else {
            System.out.println("UAVタイマーは停止しています。");
        }
    }

    // 飛行時間の取得 (秒単位)
    public long getFlightTime() {
        if (isTiming) {
            return (totalElapsedTime + (System.currentTimeMillis() - startTime)) / 1000; // タイマー動作中の場合
        } else {
            return totalElapsedTime / 1000; // タイマー停止後の場合
        }
    }

    // タイマーのリセット
    public void reset() {
        this.startTime = 0;
        this.endTime = 0;
        this.totalElapsedTime = 0; // 累積時間をリセット
        this.isTiming = false;
        System.out.println("UAVタイマーがリセットされました。");
    }

    // タイマーのキャンセル
    public void cancel() {
        if (isTiming) {
            this.stop();
        }
        this.timer.cancel();
        System.out.println("UAVタイマーがキャンセルされました。");
    }
}
