package legacy.polling.route;

import operator.controller.ServerController;
import operator.route.PhysarumSolverRouteSearcher;
import shared.client.Client;
import shared.item.BeaconCluster;
import shared.item.Link;
import shared.item.Uav;
import shared.util.LogManager;

import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Option A (ポーリング方式) PhysarumSolver ルートサーチャー
 *
 * PhysarumSolverRouteSearcher を継承し、runUAVFlow をオーバーライドして
 * Redis の代わりに flyingUavQueue / uavQueue へ UAV を追加する。
 */
public class PollingPSRouteSearcher extends PhysarumSolverRouteSearcher {

    private final Queue<Uav> flyingUavQueue;
    private final Queue<Uav> uavQueue;

    public PollingPSRouteSearcher(
            ServerController serverController,
            int[][] adjMatrix,
            Link[][] link,
            BeaconCluster beaconCluster,
            int node,
            Queue<Uav> flyingUavQueue,
            Queue<Uav> uavQueue) {
        super(serverController, adjMatrix, link, beaconCluster, node);
        this.flyingUavQueue = flyingUavQueue;
        this.uavQueue       = uavQueue;
    }

    /**
     * Redis ではなくメモリキューへ UAV を投入する。
     * 削除された AbstractPhysarumSolverRouteSearcher#runUAVFlowMemory の復元。
     */
    @Override
    protected void runUAVFlow(int startNode, int goalNode, int requiredUAVs, Client client) {
        System.out.println("[PollingMode] client" + client.getId()
                + " UAV" + requiredUAVs + "機 → flyingQueue 投入 (PS)");

        runUAVFlowMemory(startNode, goalNode, requiredUAVs, client);
    }

    // ─────────────────────────────────────────────────────────────────
    // 削除コードの復元（AbstractPhysarumSolverRouteSearcher#runUAVFlowMemory）
    // ─────────────────────────────────────────────────────────────────

    private void runUAVFlowMemory(int startNode, int goalNode, int requiredUAVs, Client client) {
        UAV_count = 0;
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        CountDownLatch latch = new CountDownLatch(requiredUAVs);
        boolean flowAvailable = true;

        while (UAV_count < requiredUAVs && flowAvailable) {
            min_Flow = 100;

            int[] path = new int[40];
            int pathIndex = 0;
            path[pathIndex++] = startNode;
            maxPathIndex = pathIndex;

            int flow = explorePath(startNode, startNode, goalNode, path, pathIndex, 0);

            if (flow > 0) {
                int pathLength = maxPathIndex;
                int[] pathArray = Arrays.copyOf(path, pathLength);

                int u = pathArray[0];
                int v = pathArray[1];
                double minCapacity = link[u][v].getCapacity();
                final int[] flowCounter = {0};

                for (int f = 0; f < flow; f++) {
                    int currentUAVIndex;
                    synchronized (this) {
                        UAV_count++;
                        currentUAVIndex = UAV_count - 1;
                    }
                    Uav currentUAV = client.getFlow().getUav(currentUAVIndex);
                    final int finalF = f;

                    scheduler.schedule(() -> {
                        currentUAV.setPath(pathArray);

                        if (flowCounter[0] < minCapacity) {
                            currentUAV.startTimer();
                            currentUAV.setFlyingLink(link[u][v]);
                            currentUAV.setPassedLink(link[u][v]);
                            synchronized (flyingUavQueue) {
                                flyingUavQueue.add(currentUAV);
                            }
                            link[u][v].decrementCapacity();
                            link[v][u].decrementCapacity();
                            flowCounter[0]++;
                            LogManager.getInstance().log(
                                    "[PollingMode] client" + currentUAV.getClientId()
                                    + " UAV" + currentUAV.getId()
                                    + " 飛行開始 " + u + "→" + v);
                        } else {
                            currentUAV.startWaitingTimer();
                            currentUAV.setStayedBeaconId(u);
                            beaconCluster.getBeacon(u).addUav(currentUAV);
                            beaconCluster.getBeacon(u).incrementWaitingUavCount();
                            synchronized (uavQueue) {
                                uavQueue.add(currentUAV);
                            }
                            LogManager.getInstance().log(
                                    "[PollingMode] client" + currentUAV.getClientId()
                                    + " UAV" + currentUAV.getId()
                                    + " 容量不足、待機 (" + u + "→" + v + ")");
                        }
                        latch.countDown();
                    }, (long) finalF * 2, TimeUnit.SECONDS);
                }
            } else {
                flowAvailable = false;
            }

            if (UAV_count >= requiredUAVs) break;
        }

        scheduler.shutdown();
    }
}
