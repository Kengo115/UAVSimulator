package legacy.polling;

import operator.uav.FlightDataRecorder;
import shared.client.ClientController;
import shared.item.BeaconCluster;
import shared.item.Link;
import shared.item.Uav;
import shared.util.LinkStatusRecorder;
import shared.util.LogManager;

import java.util.Queue;

/**
 * Option A (ポーリング方式) UAV 飛行制御クラス
 *
 * 2秒ごとに呼び出され、全 UAV の位置を一括更新する。
 * 容量管理は UAV 1機処理ごとに即時更新する（per-UAV トランザクション方式）。
 */
public class PollingUAVFlightController {

    /**
     * 全 UAV の移動を一括処理する。
     * PollingUAVFlyScheduler から 2 秒ごとに呼び出す。
     */
    public static void flyUAV(
            ClientController clientController,
            Queue<Uav> flyingUavQueue,
            Queue<Uav> uavQueue,
            Link[][] link,
            BeaconCluster beaconCluster,
            int node) {

        // 飛行中 UAV を処理
        int queueSize = flyingUavQueue.size();
        for (int i = 0; i < queueSize; i++) {
            Uav uav = flyingUavQueue.poll();
            if (uav == null) continue;

            double flightDistance = uav.getFlightTime() * uav.getSpeed();
            int[] path = uav.getPath();

            // 総経路距離を計算
            double totalPathDistance = 0.0;
            for (int k = 0; k < path.length - 1; k++) {
                totalPathDistance += link[path[k]][path[k + 1]].getDistance();
            }

            if (flightDistance >= totalPathDistance) {
                // 目的地到着: 最終リンクを即時解放
                if (uav.isFlying()) {
                    uav.cancelTimer(link, totalPathDistance);
                }
                if (uav.getFlyingLink() != null && path.length >= 2) {
                    int fromNode = path[path.length - 2];
                    int toNode   = path[path.length - 1];
                    link[fromNode][toNode].incrementCapacity();
                    link[toNode][fromNode].incrementCapacity();
                    LinkStatusRecorder.getInstance().onLinkExit(fromNode, toNode);
                }
                clientController.getClient(uav.getClientId() - 1)
                        .incrementFinishFlyingCounter();
                FlightDataRecorder.saveFlightData(clientController, uav, totalPathDistance);

                System.out.println("[PollingMode] UAV" + uav.getId()
                        + " 到着完了 (飛行時間: "
                        + uav.getFlightTime() + "s)");
            } else {
                processUavMovement(uav, flightDistance, path,
                        flyingUavQueue, uavQueue, link, beaconCluster);
            }
        }

        // 待機 UAV を再チェック（flyingUAV 処理後に容量が回復している可能性あり）
        processWaitingUAVs(uavQueue, flyingUavQueue, link, beaconCluster);

        LogManager.getInstance().log("[PollingCapacityManager] 容量更新完了 (node=" + node + ")");
    }

    /**
     * UAV の現在位置を特定し、次のリンクへの移行または中継ノード待機を処理する。
     * リンク進入・退出時に容量を即時更新する。
     */
    private static void processUavMovement(
            Uav uav,
            double flightDistance,
            int[] path,
            Queue<Uav> flyingUavQueue,
            Queue<Uav> uavQueue,
            Link[][] link,
            BeaconCluster beaconCluster) {

        double traveledDistance = 0.0;
        for (int k = 0; k < path.length - 1; k++) {
            int startNode = path[k];
            int endNode   = path[k + 1];
            double linkLength = link[startNode][endNode].getDistance();

            if (traveledDistance + linkLength >= flightDistance) {
                // UAV はこのリンク上にいる
                if (link[startNode][endNode] != uav.getFlyingLink()) {
                    // 前リンクを即時解放（k=0 かつ flyingLink==null なら初回進入）
                    if (uav.getFlyingLink() != null && k > 0) {
                        link[path[k - 1]][path[k]].incrementCapacity();
                        link[path[k]][path[k - 1]].incrementCapacity();
                        LinkStatusRecorder.getInstance().onLinkExit(path[k - 1], path[k]);
                    }

                    if (link[startNode][endNode].getCapacity() > 0) {
                        // リンク移行可能: 新リンクを即時確保
                        link[startNode][endNode].decrementCapacity();
                        link[endNode][startNode].decrementCapacity();
                        uav.setFlyingLink(link[startNode][endNode]);
                        LinkStatusRecorder.getInstance().onLinkEnter(startNode, endNode);
                        LogManager.getInstance().log(
                                "[PollingMode] UAV" + uav.getId()
                                + " " + startNode + "→" + endNode + " へ移動");
                        flyingUavQueue.add(uav);
                    } else {
                        // 容量不足 → 中継ノードで待機（確保せず）
                        if (uav.isFlying()) uav.stopTimer();
                        if (!uav.isWaiting()) uav.startWaitingTimer();
                        uav.setStayedBeaconId(startNode);
                        beaconCluster.getBeacon(startNode).addUav(uav);
                        beaconCluster.getBeacon(startNode).incrementWaitingUavCount();
                        LinkStatusRecorder.getInstance().onWaitingStart(startNode, endNode);
                        uavQueue.add(uav);

                        System.out.println("[PollingMode] UAV" + uav.getId()
                                + " ノード" + startNode + " で待機（容量不足）");
                    }
                } else {
                    // 同一リンク継続（容量変化なし）
                    flyingUavQueue.add(uav);
                }
                return;
            }
            traveledDistance += linkLength;
        }
    }

    /**
     * 待機 UAV を確認し、容量が回復していれば flyingUavQueue へ移動する。
     * 容量確保も即時実行する。
     */
    private static void processWaitingUAVs(
            Queue<Uav> uavQueue,
            Queue<Uav> flyingUavQueue,
            Link[][] link,
            BeaconCluster beaconCluster) {

        int waitQueueSize = uavQueue.size();
        for (int i = 0; i < waitQueueSize; i++) {
            Uav uav = uavQueue.poll();
            if (uav == null) continue;

            int[] path = uav.getPath();
            int startNode = uav.getStayedBeaconId();

            // path 上で startNode の次のノードを探す
            int nextNode = -1;
            for (int k = 0; k < path.length - 1; k++) {
                if (path[k] == startNode) {
                    nextNode = path[k + 1];
                    break;
                }
            }

            if (nextNode < 0) {
                LogManager.getInstance().log(
                        "[PollingMode] 警告: UAV" + uav.getId()
                        + " の stayed beacon が path 上に存在しない");
                continue;
            }

            if (link[startNode][nextNode].getCapacity() > 0) {
                // 容量回復 → リンクを即時確保して飛行再開
                if (uav.isWaiting()) uav.stopWaitingTimer();
                LinkStatusRecorder.getInstance().onWaitingEnd(startNode, nextNode);
                link[startNode][nextNode].decrementCapacity();
                link[nextNode][startNode].decrementCapacity();
                uav.startTimer();
                uav.setFlyingLink(link[startNode][nextNode]);
                LinkStatusRecorder.getInstance().onLinkEnter(startNode, nextNode);
                beaconCluster.getBeacon(startNode).removeUav(uav);
                beaconCluster.getBeacon(startNode).decrementWaitingUavCount();
                flyingUavQueue.add(uav);

                LogManager.getInstance().log(
                        "[PollingMode] UAV" + uav.getId()
                        + " 待機解除 (" + startNode + "→" + nextNode + ")");
            } else {
                // まだ容量不足 → 継続待機
                uavQueue.add(uav);
            }
        }
    }
}
