package legacy.eventdriven.route;

import legacy.eventdriven.MemoryJobQueue;
import operator.controller.ServerController;
import operator.route.PhysarumSolverRouteSearcher;
import shared.client.Client;
import shared.item.BeaconCluster;
import shared.item.Link;
import shared.item.Uav;
import shared.item.UAVJob;
import shared.util.LogManager;
import shared.util.ResultOutputManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Option B (イベント駆動インメモリ方式) PhysarumSolver ルートサーチャー
 *
 * PhysarumSolverRouteSearcher を継承し、runUAVFlowRedis をオーバーライドして
 * Redis の UAVJobQueue の代わりに MemoryJobQueue へ UAVJob を投入する。
 *
 * Redis 版との差異:
 *   - UAVJobQueue      → MemoryJobQueue.getInstance()
 *   - LinkCapacityManager（未使用のため除去）
 *   - Link117DebugLogger → 除去
 *   - DebugModeHook    → 除去
 */
public class EventDrivenPSRouteSearcher extends PhysarumSolverRouteSearcher {

    public EventDrivenPSRouteSearcher(
            ServerController serverController,
            int[][] adjMatrix,
            Link[][] link,
            BeaconCluster beaconCluster,
            int node) {
        super(serverController, adjMatrix, link, beaconCluster, node);
    }

    /**
     * Redis ではなく MemoryJobQueue へ UAVJob を投入する。
     * AbstractPhysarumSolverRouteSearcher#runUAVFlowRedis のインメモリ版。
     */
    @Override
    protected void runUAVFlowRedis(int startNode, int goalNode, int requiredUAVs, Client client) {
        LogManager.getInstance().log("[EventDrivenMode] client" + client.getId()
            + " UAV" + requiredUAVs + "機 ジョブ投入 (PS)");
        System.out.printf("[EventDrivenMode] client%d UAV%d機 ジョブ投入 (PS)%n",
            client.getId(), requiredUAVs);

        int   clientId = client.getId();
        ScheduledExecutorService enqueueScheduler = Executors.newScheduledThreadPool(1);

        // 一ホップ目リンクごとの投入順序管理
        Map<String, Integer> linkEnqueueOrder = new HashMap<>();

        // Phase 12 相当: 全 UAV の経路が確定するまで保留リストに積む
        List<PendingJob> pendingJobs = new ArrayList<>();

        UAV_count      = 0;
        boolean flowAvailable = true;

        while (UAV_count < requiredUAVs && flowAvailable) {
            min_Flow   = 100;
            int[] path = new int[40];
            int pathIndex = 0;
            path[pathIndex++] = startNode;
            maxPathIndex = pathIndex;

            int flow = explorePath(startNode, startNode, goalNode, path, pathIndex, 0);

            if (flow > 0) {
                int pathLength    = maxPathIndex;
                int[] pathArray   = Arrays.copyOf(path, pathLength);
                String firstLinkKey = pathArray[0] + "-" + pathArray[1];
                double[] linkDistances = calculateLinkDistances(pathArray);

                for (int f = 0; f < flow && UAV_count < requiredUAVs; f++) {
                    Uav    uav      = client.getFlow().getUav(UAV_count);
                    int    uavId    = uav.getId();
                    double uavSpeed = uav.getSpeed();

                    int currentOrder = linkEnqueueOrder.getOrDefault(firstLinkKey, 0);
                    linkEnqueueOrder.put(firstLinkKey, currentOrder + 1);

                    pendingJobs.add(new PendingJob(uavId, clientId, pathArray,
                        linkDistances, uavSpeed, currentOrder * 2, startNode, goalNode));
                    UAV_count++;
                }
            } else {
                flowAvailable = false;
            }
        }

        // 保留リストを一括スケジュール登録
        for (PendingJob p : pendingJobs) {
            enqueueScheduler.schedule(() -> {
                UAVJob job = new UAVJob(
                    p.uavId, p.clientId, p.path, p.speed,
                    System.currentTimeMillis(), p.startNode, p.goalNode);
                job.setLinkDistances(p.linkDistances);

                MemoryJobQueue.getInstance().enqueueJob(job);

                LogManager.getInstance().log(
                    "[EventDrivenMode] UAV" + p.uavId + " ジョブ投入完了 (PS)");

                try {
                    ResultOutputManager.outputRouteAssignment(job, p.clientId - 1);
                } catch (IOException e) {
                    LogManager.getInstance().error(
                        "[EventDrivenMode] route.csv 書き込みエラー (UAV" + p.uavId + ")", e);
                }
            }, p.delaySeconds, TimeUnit.SECONDS);
        }

        enqueueScheduler.shutdown();
        LogManager.getInstance().log(
            "[EventDrivenMode] 全" + UAV_count + "件のジョブをスケジュール (PS)");
    }

    // ─────────────────────────────────────────────────────────────────
    // 内部クラス
    // ─────────────────────────────────────────────────────────────────

    private static class PendingJob {
        final int      uavId;
        final int      clientId;
        final int[]    path;
        final double[] linkDistances;
        final double   speed;
        final int      delaySeconds;
        final int      startNode;
        final int      goalNode;

        PendingJob(int uavId, int clientId, int[] path, double[] linkDistances,
                   double speed, int delaySeconds, int startNode, int goalNode) {
            this.uavId         = uavId;
            this.clientId      = clientId;
            this.path          = path;
            this.linkDistances = linkDistances;
            this.speed         = speed;
            this.delaySeconds  = delaySeconds;
            this.startNode     = startNode;
            this.goalNode      = goalNode;
        }
    }
}
