package server;

import client.Client;
import client.ClientController;
import item.Uav;

import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public class UAVFlyScheduler {
    private static ScheduledExecutorService scheduler;
    private static final int UPDATE_INTERVAL_SECONDS = 1;

    // UAV位置更新を開始する
    public static synchronized void startFlyUAVUpdates(Queue<Client> passedClient, ClientController clientController) {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newScheduledThreadPool(1);
            System.out.println("UAV位置更新スケジューラーを開始します...");
        } else {
            System.out.println("スケジューラーは既に稼働中です。");
            return; // 既にスケジュール済みなら何もしない
        }

        // 3秒ごとにUAVの位置を更新するタスクをスケジュール
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (passedClient.isEmpty()) {
                    System.out.println("passedClientが空です。スケジューラーを停止します。");
                    clientController.stopTimer();
                    stopFlyUAVUpdates(clientController);
                } else {
                    //クライアントタイマー動作中
                    ServerController.flyUAV(passedClient, clientController);

                }
            } catch (Exception e) {
                e.printStackTrace();
                System.err.println("スケジューラー内で例外が発生しましたが、タスクは継続します。");
            }
        }, 0, UPDATE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    // UAV位置更新を停止する
    public static synchronized void stopFlyUAVUpdates(ClientController clientController) {
        if (scheduler != null && !scheduler.isShutdown()) {
            //clientController.showFlightTime();
            scheduler.shutdown();
            System.out.println("UAV位置更新スケジューラーが停止しました。");
        } else {
            System.out.println("スケジューラーは既に停止しています。");
        }
    }

    // スケジューラーの状態を取得
    public static synchronized boolean isSchedulerRunning() {
        return scheduler != null && !scheduler.isShutdown();
    }
}
