package server.uav;

import client.ClientController;
import item.BeaconCluster;
import item.Link;
import item.Uav;
import server.redis.UAVStateManager;
import server.util.LogManager;

import java.util.Queue;

/**
 * UAVの飛行制御を行うクラス
 */
public class UAVFlightController {

    // Phase 1: UAV状態管理用のRedisマネージャー
    private static UAVStateManager uavStateManager = new UAVStateManager();

    /**
     * UAVを移動させる
     * @param clientController クライアントコントローラー
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param link リンク情報
     * @param beaconCluster ビーコンクラスター
     * @param node ノード数
     */
    public static void flyUAV(ClientController clientController, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, Link[][] link, BeaconCluster beaconCluster, int node) {
        int[][] flyingUAV = new int[node][node];

        // Phase 1: 飛行中のUAVの状態をRedisに保存（更新前）
        for (Uav uav : flyingUavQueue) {
            try {
                uavStateManager.saveUAVState(uav);
            } catch (Exception e) {
                LogManager.getInstance().error("Redis書き込み失敗（飛行中UAV更新前）", e);
            }
        }

        // Phase 1: 待機中のUAVの状態をRedisに保存（更新前）
        for (Uav uav : uavQueue) {
            try {
                uavStateManager.saveUAVState(uav);
            } catch (Exception e) {
                LogManager.getInstance().error("Redis書き込み失敗（待機中UAV更新前）", e);
            }
        }

        // 飛行中のUAVを移動させる
        int queueSize = flyingUavQueue.size();
        for (int i = 0; i < queueSize; i++) {
            Uav uav = flyingUavQueue.poll();
            double flightDistance = uav.getFlightTime() * uav.getSpeed();
            int[] path = uav.getPath();

            double totalPathDistance = 0.0;
            for (int k = 0; k < path.length - 1; k++) {
                int startNode = path[k];
                int endNode = path[k + 1];
                totalPathDistance += link[startNode][endNode].getDistance();
            }

            if (flightDistance >= totalPathDistance) {
                if (uav.isFlying()) {
                    // 目的地に到着したときに正確な飛行距離を計算するためにlinkを渡す
                    uav.cancelTimer(link, totalPathDistance);
                } else {
                    LogManager.getInstance().error("要修正0: UAVが飛行中でないのにcancelTimerが呼ばれました");
                }
                clientController.getClient(uav.getClientId() - 1).incrementFinishFlyingCounter();

                // ログの保存
                FlightDataRecorder.saveFlightData(clientController, uav, totalPathDistance);

                // Phase 1: UAV状態をRedisに保存（到着時）
                try {
                    uavStateManager.saveUAVState(uav);
                } catch (Exception e) {
                    LogManager.getInstance().error("Redis書き込み失敗（UAV到着時）", e);
                }
            } else {
                processUavMovement(uav, flightDistance, path, flyingUAV, flyingUavQueue, uavQueue, link, beaconCluster);
            }
        }

        // 容量の更新
        CapacityManager.updateCapacity(flyingUAV, link, node);

        // 待機中のUAVを処理
        processWaitingUAVs(uavQueue, flyingUavQueue, flyingUAV, link, beaconCluster, node);
    }

    /**
     * UAVの移動を処理する
     * @param uav UAV
     * @param flightDistance 飛行距離
     * @param path 経路
     * @param flyingUAV 飛行中のUAV配列
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param link リンク情報
     * @param beaconCluster ビーコンクラスター
     */
    private static void processUavMovement(Uav uav, double flightDistance, int[] path, int[][] flyingUAV, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, Link[][] link, BeaconCluster beaconCluster) {
        double traveledDistance = 0.0;
        for (int k = 0; k < path.length - 1; k++) {
            int startNode = path[k];
            int endNode = path[k + 1];
            double linkLength = link[startNode][endNode].getDistance();

            if (traveledDistance + linkLength >= flightDistance) {
                if (link[startNode][endNode] != uav.getFlyingLink()) {
                    if (link[startNode][endNode].getCapacity() > 0) {
                        uav.setFlyingLink(link[startNode][endNode]);
                        flyingUAV[startNode][endNode]++;

                        // 実際の飛行距離（飛行時間×速度）と正確な飛行距離（リンク距離）を計算
                        double actualFlightDistance = uav.getFlightTime() * uav.getSpeed();

                        // 正確な飛行距離を計算
                        double accurateFlightDistance = 0.0;
                        for (int j = 0; j < k; j++) {
                            int pathStartNode = path[j];
                            int pathEndNode = path[j + 1];
                            accurateFlightDistance += link[pathStartNode][pathEndNode].getDistance();
                        }

                        LogManager.getInstance().log("client" + uav.getClientId() + " UAV" + uav.getId() + " " + startNode + " → " + endNode + " へ移動" +
                            "（実際の飛行距離：" + String.format("%.2f", actualFlightDistance) + "，正確な飛行距離：" + String.format("%.2f", accurateFlightDistance) + "）");

                        // Phase 1: UAV状態をRedisに保存（リンク移動時）
                        try {
                            uavStateManager.saveUAVState(uav);
                        } catch (Exception e) {
                            LogManager.getInstance().error("Redis書き込み失敗（リンク移動時）", e);
                        }

                        flyingUavQueue.add(uav);
                    } else {
                        if (uav.isFlying()) {
                            uav.stopTimer();
                        } else {
                            LogManager.getInstance().error("要修正1: client" + uav.getClientId() + " UAV" + uav.getId() + " が飛行中でないのに stopTimer() が呼ばれました");
                        }
                        if (!uav.isWaiting()) {
                            uav.startWaitingTimer();
                        } else {
                            LogManager.getInstance().error("要修正2: client" + uav.getClientId() + " UAV" + uav.getId() + " がすでに待機状態");
                        }
                        uav.setStayedBeaconId(startNode);
                        beaconCluster.getBeacon(startNode).addUav(uav);
                        beaconCluster.getBeacon(startNode).incrementWaitingUavCount();

                        // Phase 1: UAV状態をRedisに保存（待機状態）
                        try {
                            uavStateManager.saveUAVState(uav);
                        } catch (Exception e) {
                            LogManager.getInstance().error("Redis書き込み失敗（待機状態）", e);
                        }

                        uavQueue.add(uav);
                    }
                } else {
                    flyingUAV[startNode][endNode]++;
                    flyingUavQueue.add(uav);
                }
                break;
            } else {
                traveledDistance += linkLength;
            }
        }
    }

    /**
     * 待機中のUAVを処理する
     * @param uavQueue 待機中のUAVキュー
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param flyingUAV 飛行中のUAV配列
     * @param link リンク情報
     * @param beaconCluster ビーコンクラスター
     * @param node ノード数
     */
    private static void processWaitingUAVs(Queue<Uav> uavQueue, Queue<Uav> flyingUavQueue, int[][] flyingUAV, Link[][] link, BeaconCluster beaconCluster, int node) {
        int waitQueueSize = uavQueue.size();
        for (int i = 0; i < waitQueueSize; i++) {
            Uav uav = uavQueue.poll();
            int[] path = uav.getPath();

            int startNode = uav.getStayedBeaconId();

            int nextNode = -1;
            if (startNode != -1) {
                for (int j = 0; j < path.length - 1; j++) {
                    if (path[j] == startNode) {
                        nextNode = path[j + 1];
                        break;
                    }
                }
            } else {
                LogManager.getInstance().error("要修正4: client" + uav.getClientId() + " UAV" + uav.getId() + " が待機中のビーコンIDを取得できませんでした");
            }

            if (nextNode != -1) {
                if (flyingUAV[startNode][nextNode] < link[startNode][nextNode].getCapacity()) {
                    flyingUAV[startNode][nextNode]++;
                    if (uav.isWaiting()) {
                        uav.stopWaitingTimer();
                    } else {
                        LogManager.getInstance().error("要修正3: client" + uav.getClientId() + " UAV" + uav.getId() + " は待機していないのに stopWaitingTimer() が呼ばれました");
                    }
                    beaconCluster.getBeacon(startNode).removeUav(uav);
                    beaconCluster.getBeacon(startNode).decrementWaitingUavCount();
                    uav.startTimer();
                    uav.setFlyingLink(link[startNode][nextNode]);
                    uav.setStayedBeaconId(-1);

                    // 実際の飛行距離（飛行時間×速度）と正確な飛行距離（リンク距離）を計算
                    double actualFlightDistance = uav.getFlightTime() * uav.getSpeed();
                    double accurateFlightDistance = link[startNode][nextNode].getDistance();

                    LogManager.getInstance().log("client" + uav.getClientId() + " UAV" + uav.getId() + " " + startNode + " → " + nextNode + " へ移動" +
                        "（実際の飛行距離：" + String.format("%.2f", actualFlightDistance) + "，正確な飛行距離：" + String.format("%.2f", accurateFlightDistance) + "）");

                    // Phase 1: UAV状態をRedisに保存（飛行再開時）
                    try {
                        uavStateManager.saveUAVState(uav);
                    } catch (Exception e) {
                        LogManager.getInstance().error("Redis書き込み失敗（飛行再開時）", e);
                    }

                    flyingUavQueue.add(uav);
                } else{
                    // Phase 1: UAV状態をRedisに保存（待機継続時）
                    try {
                        uavStateManager.saveUAVState(uav);
                    } catch (Exception e) {
                        LogManager.getInstance().error("Redis書き込み失敗（待機継続時）", e);
                    }

                    uavQueue.add(uav);
                    LogManager.getInstance().log("client" + uav.getClientId() + " UAV" + uav.getId() + " 容量不足のため待機継続 (" + startNode + " -> " + nextNode + ")");
                }
            } else {
                // Phase 1: UAV状態をRedisに保存（待機継続時・リンクなし）
                try {
                    uavStateManager.saveUAVState(uav);
                } catch (Exception e) {
                    LogManager.getInstance().error("Redis書き込み失敗（待機継続時・リンクなし）", e);
                }

                LogManager.getInstance().log("client" + uav.getClientId() + " UAV" + uav.getId() + " 移動できるリンクがないため待機継続");
                uavQueue.add(uav);
            }
        }

        // 容量の更新
        CapacityManager.updateCapacity(flyingUAV, link, node);
    }
}
