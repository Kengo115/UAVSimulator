package server.uav;

import client.ClientController;
import controller.BoundaryController;
import item.BeaconCluster;
import item.Uav;
import server.redis.UAVStateValidator;
import server.redis.UAVStatisticsManager;
import server.redis.UAVStatisticsReader;
import server.util.LogManager;

import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * UAVの飛行スケジュールを管理するクラス
 */
public class UAVFlyScheduler {
    private static ScheduledExecutorService scheduler;
    private static final int UPDATE_INTERVAL_SECONDS = 2;

    // Phase 1: 整合性チェック用のカウンター
    private static int updateCounter = 0;
    private static final int VALIDATION_INTERVAL = 5; // 5回に1回チェック
    private static UAVStateValidator validator = new UAVStateValidator();

    // Phase 2: 統計情報管理
    private static UAVStatisticsManager statisticsManager = new UAVStatisticsManager();
    private static UAVStatisticsReader statisticsReader = new UAVStatisticsReader();

    /**
     * UAV位置更新を開始する
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param clientController クライアントコントローラー
     * @param beaconCluster ビーコンクラスター（Phase 2: 統計情報に必要）
     * @param nodeCount ノード数（Phase 2: 統計情報に必要）
     */
    public static synchronized void startFlyUAVUpdates(Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, ClientController clientController, BeaconCluster beaconCluster, int nodeCount) {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newScheduledThreadPool(1);
            LogManager.getInstance().log("UAV位置更新スケジューラーを開始します...");
        } else {
            LogManager.getInstance().log("スケジューラーは既に稼働中です。");
            return; // 既にスケジュール済みなら何もしない
        }

        // 定期的にUAVの位置を更新するタスクをスケジュール
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (flyingUavQueue.isEmpty() && uavQueue.isEmpty()) {
                    LogManager.getInstance().log("飛行中UAV, 待機中UAVが存在しません");
                    clientController.stopTimer();
                    stopFlyUAVUpdates(clientController);
                } else {
                    //クライアントタイマー動作中
                    server.controller.ServerController.flyUAV(clientController, flyingUavQueue, uavQueue);

                    // Phase 1 & Phase 2: 5回に1回、整合性チェックと統計情報保存
                    // Phase 3b-12: メモリモード時はスキップ
                    updateCounter++;
                    if (updateCounter % VALIDATION_INTERVAL == 0
                        && BoundaryController.getCurrentWorkerMode() == BoundaryController.WorkerMode.REDIS) {
                        // Phase 1: UAV状態の整合性チェック
                        validateAllUAVStates(flyingUavQueue, uavQueue);

                        // Phase 2: 統計情報をRedisに保存
                        saveStatistics(flyingUavQueue, uavQueue, clientController, beaconCluster, nodeCount);

                        // Phase 2: 統計情報の整合性検証
                        validateStatistics(flyingUavQueue, uavQueue, clientController, beaconCluster, nodeCount);
                    }
                }
            } catch (Exception e) {
                LogManager.getInstance().error("スケジューラー内で例外が発生しましたが, タスクは継続します", e);
            }
        }, 0, UPDATE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * UAV位置更新を停止する
     * @param clientController クライアントコントローラー
     */
    public static synchronized void stopFlyUAVUpdates(ClientController clientController) {
        if (scheduler != null && !scheduler.isShutdown()) {
            //clientController.showFlightTime();
            scheduler.shutdown();
            LogManager.getInstance().log("UAV位置更新スケジューラーが停止しました");
        } else {
            LogManager.getInstance().log("スケジューラーは既に停止しています");
        }
    }

    /**
     * スケジューラーの状態を取得
     * @return スケジューラーが実行中の場合はtrue、そうでない場合はfalse
     */
    public static synchronized boolean isSchedulerRunning() {
        return scheduler != null && !scheduler.isShutdown();
    }

    /**
     * Phase 1: すべてのUAVの整合性をチェック
     * メモリとRedisの状態が一致しているかを確認する
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     */
    private static void validateAllUAVStates(Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue) {
        try {
            int totalUavs = flyingUavQueue.size() + uavQueue.size();
            int validCount = 0;
            int invalidCount = 0;

            // 飛行中のUAVをチェック
            for (Uav uav : flyingUavQueue) {
                if (validator.validateUAVState(uav)) {
                    validCount++;
                } else {
                    invalidCount++;
                }
            }

            // 待機中のUAVをチェック
            for (Uav uav : uavQueue) {
                if (validator.validateUAVState(uav)) {
                    validCount++;
                } else {
                    invalidCount++;
                }
            }

            // 整合性チェックの結果をログ出力
            if (invalidCount == 0) {
                LogManager.getInstance().log("整合性チェック: すべて正常 (" + totalUavs + "機)");
            } else {
                LogManager.getInstance().log("整合性チェック: " + invalidCount + "件の不一致を検出 (正常: " + validCount + ", 不一致: " + invalidCount + ")");
            }
        } catch (Exception e) {
            LogManager.getInstance().error("整合性チェック中にエラーが発生しました", e);
        }
    }

    /**
     * Phase 2: 統計情報をRedisに保存する
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param clientController クライアントコントローラー
     * @param beaconCluster ビーコンクラスター
     * @param nodeCount ノード数
     */
    private static void saveStatistics(Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue,
                                      ClientController clientController, BeaconCluster beaconCluster,
                                      int nodeCount) {
        try {
            LogManager.getInstance().log("Phase 2: saveStatistics()が呼ばれました");
            statisticsManager.saveAllStats(flyingUavQueue, uavQueue, clientController, beaconCluster, nodeCount);
        } catch (Exception e) {
            LogManager.getInstance().error("統計情報保存中にエラーが発生しました", e);
        }
    }

    /**
     * Phase 2: 統計情報の整合性を検証する
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param clientController クライアントコントローラー
     * @param beaconCluster ビーコンクラスター
     * @param nodeCount ノード数
     */
    private static void validateStatistics(Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue,
                                          ClientController clientController, BeaconCluster beaconCluster,
                                          int nodeCount) {
        try {
            int flyingCount = flyingUavQueue.size();
            int waitingCount = uavQueue.size();
            statisticsReader.validateAllStats(flyingCount, waitingCount, clientController, beaconCluster, nodeCount);
        } catch (Exception e) {
            LogManager.getInstance().error("統計情報検証中にエラーが発生しました", e);
        }
    }
}
