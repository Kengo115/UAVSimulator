package legacy.polling;

import shared.client.ClientController;
import shared.item.BeaconCluster;
import shared.item.Uav;
import shared.util.LogManager;

import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Option A (ポーリング方式) UAV スケジューラ
 *
 * 2 秒ごとに PollingUAVFlightController.flyUAV() を呼び出す。
 * 削除された UAVFlyScheduler を Redis 依存なしで復元。
 */
public class PollingUAVFlyScheduler {

    private static final int UPDATE_INTERVAL_SECONDS = 1;

    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledTask;

    private final Queue<Uav> flyingUavQueue;
    private final Queue<Uav> uavQueue;
    private final ClientController clientController;
    private final BeaconCluster beaconCluster;
    private final int nodeCount;

    public PollingUAVFlyScheduler(
            Queue<Uav> flyingUavQueue,
            Queue<Uav> uavQueue,
            ClientController clientController,
            BeaconCluster beaconCluster,
            int nodeCount) {

        this.flyingUavQueue  = flyingUavQueue;
        this.uavQueue        = uavQueue;
        this.clientController = clientController;
        this.beaconCluster   = beaconCluster;
        this.nodeCount       = nodeCount;
        this.scheduler       = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PollingUAVFlyScheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * スケジューラを起動する。
     * 最初の実行は UPDATE_INTERVAL_SECONDS 秒後。
     */
    public synchronized void start() {
        if (scheduledTask != null && !scheduledTask.isCancelled()) {
            LogManager.getInstance().log(
                    "[PollingMode] スケジューラはすでに稼働中です");
            return;
        }

        scheduledTask = scheduler.scheduleAtFixedRate(
                this::tick,
                UPDATE_INTERVAL_SECONDS,
                UPDATE_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );

        System.out.println("[PollingMode] UAVFlyScheduler 起動 (間隔: "
                + UPDATE_INTERVAL_SECONDS + "秒)");
        LogManager.getInstance().log(
                "[PollingMode] UAVFlyScheduler 起動 (間隔: "
                + UPDATE_INTERVAL_SECONDS + "秒)");
    }

    /**
     * スケジューラを停止する。
     */
    public synchronized void stop() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("[PollingMode] UAVFlyScheduler 停止");
        LogManager.getInstance().log("[PollingMode] UAVFlyScheduler 停止");
    }

    /** スケジューラが実行中かどうか */
    public boolean isRunning() {
        return scheduledTask != null
                && !scheduledTask.isCancelled()
                && !scheduledTask.isDone();
    }

    // ─────────────────────────────────────────────────────────────────
    // private
    // ─────────────────────────────────────────────────────────────────

    private void tick() {
        try {
            PollingUAVFlightController.flyUAV(
                    clientController,
                    flyingUavQueue,
                    uavQueue,
                    // link 配列は ServerController のスタティック参照から取得
                    operator.controller.ServerController.getLinkArray(),
                    beaconCluster,
                    nodeCount);
        } catch (Exception e) {
            LogManager.getInstance().error(
                    "[PollingMode] tick() エラー", e);
        }
    }
}
