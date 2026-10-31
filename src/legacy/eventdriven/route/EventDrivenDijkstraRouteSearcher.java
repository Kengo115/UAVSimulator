package legacy.eventdriven.route;

import legacy.eventdriven.MemoryJobQueue;
import operator.controller.ServerController;
import operator.route.DijkstraRouteSearcher;
import shared.client.Client;
import shared.item.BeaconCluster;
import shared.item.Link;
import shared.item.Uav;
import shared.item.UAVJob;
import shared.redis.ClientTimeManager;
import shared.util.LogManager;
import shared.util.ResultOutputManager;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Option B (イベント駆動インメモリ方式) Dijkstra ルートサーチャー
 *
 * DijkstraRouteSearcher を継承し、runUAVFlow をオーバーライドして
 * Redis の UAVJobQueue の代わりに MemoryJobQueue へ UAVJob を投入する。
 *
 * Redis 版との差異:
 *   - UAVJobQueue → MemoryJobQueue.getInstance()
 *   - BoundaryController.getCurrentSessionId() → null（session ID 不要）
 *   - Link117DebugLogger → 除去
 *   - DebugModeHook       → 除去（メモリモードはデバッグ UI なし）
 */
public class EventDrivenDijkstraRouteSearcher extends DijkstraRouteSearcher {

    public EventDrivenDijkstraRouteSearcher(
            ServerController serverController,
            int[][] adjMatrix,
            Link[][] link,
            BeaconCluster beaconCluster,
            int node) {
        super(serverController, adjMatrix, link, beaconCluster, node);
    }

    /**
     * Redis ではなく MemoryJobQueue へ UAVJob を投入する。
     * Redis 版の DijkstraRouteSearcher#runUAVFlowRedis に相当。
     */
    @Override
    protected void runUAVFlow(Client client, int[] path, int requiredUAVs) {
        ClientTimeManager.getInstance().startClientTime(client.getId(), requiredUAVs);

        System.out.printf("[EventDrivenMode] client%d UAV%d機 ジョブ投入 (Dijkstra)%n",
            client.getId(), requiredUAVs);
        LogManager.getInstance().log("[EventDrivenMode] client" + client.getId()
            + " UAV" + requiredUAVs + "機 ジョブ投入 (Dijkstra, 2秒間隔)");

        double[] linkDistances    = calculateLinkDistances(path);
        int      clientId         = client.getId();
        int      sourceBeaconId   = path[0];
        int      destBeaconId     = path[path.length - 1];

        ScheduledExecutorService enqueueScheduler = Executors.newScheduledThreadPool(1);

        for (int f = 0; f < requiredUAVs; f++) {
            Uav    uav      = client.getFlow().getUav(f);
            int    uavId    = uav.getId();
            double uavSpeed = uav.getSpeed();
            final int finalF = f;

            enqueueScheduler.schedule(() -> {
                UAVJob job = new UAVJob(
                    uavId, clientId, path, uavSpeed,
                    System.currentTimeMillis(), sourceBeaconId, destBeaconId);
                job.setLinkDistances(linkDistances);
                // session ID は null（MemoryFlightScheduler は null session ID を許可）

                MemoryJobQueue.getInstance().enqueueJob(job);

                LogManager.getInstance().log(
                    "[EventDrivenMode] UAV" + uavId + " ジョブ投入完了 (speed="
                    + String.format("%.2f", uavSpeed) + "m/s)");

                try {
                    ResultOutputManager.outputRouteAssignment(job, clientId - 1);
                } catch (IOException e) {
                    LogManager.getInstance().error(
                        "[EventDrivenMode] route.csv 書き込みエラー (UAV" + uavId + ")", e);
                }
            }, (long) finalF * 2, TimeUnit.SECONDS);
        }

        enqueueScheduler.shutdown();
        LogManager.getInstance().log("[EventDrivenMode] 全" + requiredUAVs + "件のジョブを2秒間隔でスケジュール (Dijkstra)");
    }

    /**
     * リンク距離配列を計算する。
     * DijkstraRouteSearcher の link フィールドは private なので
     * ServerController.getLinkArray() を使用する。
     */
    private double[] calculateLinkDistances(int[] path) {
        Link[][] linkArray = ServerController.getLinkArray();
        double[] distances = new double[path.length - 1];
        for (int i = 0; i < path.length - 1; i++) {
            distances[i] = linkArray[path[i]][path[i + 1]].getDistance();
        }
        return distances;
    }
}
