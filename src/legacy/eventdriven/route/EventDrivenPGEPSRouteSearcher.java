package legacy.eventdriven.route;

import legacy.eventdriven.MemoryJobQueue;
import legacy.eventdriven.MemoryPathWaitingManager;
import operator.BoundaryController;
import operator.controller.ServerController;
import operator.route.BisectionalPressureGuidedEPSRouteSearcher;
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
 * Option B (イベント駆動インメモリ方式) PG-EPS (Bisectional) ルートサーチャー
 *
 * BisectionalPressureGuidedEPSRouteSearcher を継承し、以下をオーバーライドする:
 *   1. runUAVFlowRedis() → MemoryJobQueue を使用
 *   2. registerRemainingUAVsToPathWaiting() → MemoryPathWaitingManager を使用
 *
 * これにより Redis pub/sub を使わずに path-waiting 機構を再現する。
 */
public class EventDrivenPGEPSRouteSearcher extends BisectionalPressureGuidedEPSRouteSearcher {

    private final MemoryPathWaitingManager memoryPathWaitingManager;

    public EventDrivenPGEPSRouteSearcher(
            ServerController serverController,
            int[][] adjMatrix,
            Link[][] link,
            BeaconCluster beaconCluster,
            int node,
            MemoryPathWaitingManager memoryPathWaitingManager) {
        super(serverController, adjMatrix, link, beaconCluster, node);
        this.memoryPathWaitingManager = memoryPathWaitingManager;
    }

    /**
     * Redis ではなく MemoryJobQueue へ UAVJob を投入する。
     * AbstractPhysarumSolverRouteSearcher#runUAVFlowRedis のインメモリ版。
     */
    @Override
    protected void runUAVFlowRedis(int startNode, int goalNode, int requiredUAVs, Client client) {
        LogManager.getInstance().log("[EventDrivenMode] client" + client.getId()
            + " UAV" + requiredUAVs + "機 ジョブ投入 (PG-EPS)");
        System.out.printf("[EventDrivenMode] client%d UAV%d機 ジョブ投入 (PG-EPS)%n",
            client.getId(), requiredUAVs);

        int  clientId = client.getId();
        ScheduledExecutorService enqueueScheduler = Executors.newScheduledThreadPool(1);

        Map<String, Integer> linkEnqueueOrder = new HashMap<>();
        List<PendingJob>     pendingJobs       = new ArrayList<>();

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
                int    pathLength   = maxPathIndex;
                int[]  pathArray    = Arrays.copyOf(path, pathLength);
                String firstLinkKey = pathArray[0] + "-" + pathArray[1];
                double[] linkDists  = calculateLinkDistances(pathArray);

                for (int f = 0; f < flow && UAV_count < requiredUAVs; f++) {
                    Uav    uav      = client.getFlow().getUav(UAV_count);
                    int    uavId    = uav.getId();
                    double uavSpeed = uav.getSpeed();

                    int currentOrder = linkEnqueueOrder.getOrDefault(firstLinkKey, 0);
                    linkEnqueueOrder.put(firstLinkKey, currentOrder + 1);

                    pendingJobs.add(new PendingJob(uavId, clientId, pathArray,
                        linkDists, uavSpeed, currentOrder * 2, startNode, goalNode));
                    UAV_count++;
                }
            } else {
                flowAvailable = false;
            }
        }

        for (PendingJob p : pendingJobs) {
            enqueueScheduler.schedule(() -> {
                UAVJob job = new UAVJob(
                    p.uavId, p.clientId, p.path, p.speed,
                    System.currentTimeMillis(), p.startNode, p.goalNode);
                job.setLinkDistances(p.linkDistances);

                MemoryJobQueue.getInstance().enqueueJob(job);

                LogManager.getInstance().log(
                    "[EventDrivenMode] UAV" + p.uavId + " ジョブ投入完了 (PG-EPS)");

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
            "[EventDrivenMode] 全" + UAV_count + "件のジョブをスケジュール (PG-EPS)");
    }

    /**
     * Redis の PathWaitingManager の代わりに MemoryPathWaitingManager へ登録する。
     * BisectionalPressureGuidedEPSRouteSearcher#registerRemainingUAVsToPathWaiting のオーバーライド。
     */
    @Override
    protected void registerRemainingUAVsToPathWaiting(
            Client client, int epsAssigned, int remainingCount, int srcNode, int dstNode) {
        for (int i = 0; i < remainingCount; i++) {
            int    uavIndex = epsAssigned + i;
            double uavSpeed = client.getFlow().getUav(uavIndex).getSpeed();
            int    uavId    = client.getFlow().getUav(uavIndex).getId();
            UAVJob waitingJob = new UAVJob(
                uavId,
                client.getId(),
                null,  // path = null（経路待ち状態）
                uavSpeed,
                System.currentTimeMillis(),
                srcNode,
                dstNode
            );
            // session ID は null
            waitingJob.startPathWaiting();
            memoryPathWaitingManager.enqueue(client.getId(), waitingJob);
        }
        LogManager.getInstance().log(
            "[EventDrivenMode] Phase 4: " + remainingCount + "機の経路待ち登録完了 (MemoryPathWaitingManager)");
        System.out.printf("[EventDrivenMode] Phase 4: client%d %d機 経路待ち登録完了%n",
            client.getId(), remainingCount);
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
