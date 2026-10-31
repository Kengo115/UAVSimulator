package legacy.polling.route;

import operator.controller.ServerController;
import operator.route.DijkstraRouteSearcher;
import shared.client.Client;
import shared.item.BeaconCluster;
import shared.item.Link;
import shared.item.Uav;
import shared.util.LogManager;

import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Option A (ポーリング方式) Dijkstra ルートサーチャー
 *
 * DijkstraRouteSearcher を継承し、runUAVFlow をオーバーライドして
 * Redis の代わりに flyingUavQueue / uavQueue へ UAV を追加する。
 */
public class PollingDijkstraRouteSearcher extends DijkstraRouteSearcher {

    private final Queue<Uav> flyingUavQueue;
    private final Queue<Uav> uavQueue;

    public PollingDijkstraRouteSearcher(
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
     * 削除された DijkstraRouteSearcher#runUAVFlowMemory の復元。
     */
    @Override
    protected void runUAVFlow(Client client, int[] path, int requiredUAVs) {
        System.out.println("[PollingMode] client" + client.getId()
                + " UAV" + requiredUAVs + "機 → flyingQueue 投入 (Dijkstra)");

        int u = path[0];
        int v = path[1];
        double firstLinkCapacity = link[u][v].getCapacity();
        final int[] flowCounter = {0};

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        for (int f = 0; f < requiredUAVs; f++) {
            Uav currentUAV = client.getFlow().getUav(f);
            final int finalF = f;
            scheduler.schedule(() -> {
                currentUAV.setPath(path.clone());

                if (flowCounter[0] < firstLinkCapacity) {
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
            }, (long) finalF * 2, TimeUnit.SECONDS);
        }

        // スケジューラをバックグラウンドで動作させる（デーモンスレッド）
        scheduler.shutdown();
    }
}
