package server.uav;

import client.ClientController;
import controller.BoundaryController;
import item.Uav;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import server.redis.RedisConnectionManager;
import server.util.LogManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * UAVの飛行データを記録するクラス
 * Phase 2: ファイルとRedisの両方に保存
 */
public class FlightDataRecorder {

    // Phase 2: Redis機能
    private static boolean redisEnabled = false;
    private static RedissonClient client;

    static {
        try {
            RedisConnectionManager connectionManager = RedisConnectionManager.getInstance();
            if (connectionManager.isConnected()) {
                client = connectionManager.getClient();
                redisEnabled = true;
                LogManager.getInstance().log("FlightDataRecorder: Redis保存機能が有効化されました");
            }
        } catch (Exception e) {
            LogManager.getInstance().error("FlightDataRecorder初期化エラー", e);
        }
    }

    /**
     * 現在の経路探索手法に基づいてディレクトリパスを取得する
     * @param baseDir ベースディレクトリ
     * @return ディレクトリパス
     */
    private static String getDirectoryPath(String baseDir) {
        BoundaryController.RouteSearchMethod method = BoundaryController.getCurrentMethod();
        return "src/result/" + method.getName() + "/" + baseDir;
    }

    /**
     * UAVの飛行経路を記録する
     * Phase 2: ファイルとRedisの両方に保存
     * @param currentUAV UAV
     * @param method メソッド名
     */
    public static void recordRoute(Uav currentUAV, String method) {
        // ファイルに保存
        String dirPath = getDirectoryPath("path");
        String filePath = dirPath + "/flight_path.txt";

        // ディレクトリが存在しない場合は作成
        File dir1 = new File(dirPath);
        if (!dir1.exists()) {
            dir1.mkdirs();
        }

        try (FileWriter writer = new FileWriter(filePath, true)) {
            // ファイルが空の場合、ヘッダーを追加
            File file = new File(filePath);
            if (file.length() == 0) {
                writer.write("clientId,UAVId,flightPath,method\n");
            }
            // UAVの飛行経路を取得し、"-" 区切りの文字列に変換
            String pathString = Arrays.stream(currentUAV.getPath()).mapToObj(String::valueOf).collect(Collectors.joining("-"));
            // 行を書き込み
            writer.write(String.format("%d,%d,%s,%s\n", currentUAV.getClientId(), currentUAV.getId(), pathString, method));
        } catch (IOException e) {
            System.err.println("ファイル書き込みエラー: " + e.getMessage());
        }

        // Phase 2: Redisに保存
        saveRouteToRedis(currentUAV, method);
    }

    /**
     * Phase 2: UAVの飛行経路をRedisに保存する
     * Phase 3b-12: メモリモード時はスキップ
     * @param uav UAV
     * @param method メソッド名
     */
    private static void saveRouteToRedis(Uav uav, String method) {
        // Phase 3b-12: メモリモード時はRedis書き込みをスキップ
        if (!redisEnabled || BoundaryController.getCurrentWorkerMode() != BoundaryController.WorkerMode.REDIS) {
            return;
        }

        try {
            String key = "flightpath:uav:" + uav.getId();
            RMap<String, Object> map = client.getMap(key);

            String pathString = Arrays.stream(uav.getPath()).mapToObj(String::valueOf).collect(Collectors.joining("-"));

            map.put("clientId", uav.getClientId());
            map.put("uavId", uav.getId());
            map.put("flightPath", pathString);
            map.put("method", method);
            map.put("savedTime", System.currentTimeMillis());

            LogManager.getInstance().log("Phase 2: UAV" + uav.getId() + " の飛行経路をRedisに保存しました");
        } catch (Exception e) {
            LogManager.getInstance().error("飛行経路Redis保存エラー", e);
        }
    }

    /**
     * フライトデータを保存する
     * Phase 2: ファイルとRedisの両方に保存
     * @param clientController クライアントコントローラー
     * @param uav UAV
     * @param totalPathDistance 総経路距離
     */
    public static void saveFlightData(ClientController clientController, Uav uav, double totalPathDistance) {
        // ファイルに保存
        String dirPath = getDirectoryPath("time");
        String filePath = dirPath + "/flight_times.csv";

        File dir1 = new File(dirPath);
        if (!dir1.exists()) {
            dir1.mkdirs();
        }

        long flightTime = clientController.getFlightTime();
        long UAV_flightTime = uav.getFlightTime();
        long UAV_waitingTime = uav.getWaitingTime();
        long UAV_totalTime = UAV_flightTime + UAV_waitingTime;

        try (FileWriter writer = new FileWriter(filePath, true)) {
            File file = new File(filePath);
            if (file.length() == 0) {
                writer.write("source,dist,passedTime,UAV_flightTime,UAV_waitingTime,UAV_totalTime,ClientID,UAVID,speed,distance,path\n");
            }
            String pathString = Arrays.stream(uav.getPath()).mapToObj(String::valueOf).collect(Collectors.joining("-"));
            writer.write(String.format("%d,%d,%d,%d,%d,%d,%d,%d,%f,%f,%s\n",
                    uav.getSource().getId(), uav.getDistination().getId(), flightTime, UAV_flightTime, UAV_waitingTime, UAV_totalTime,
                    uav.getClientId(), uav.getId(), uav.getSpeed(), totalPathDistance, pathString));
        } catch (IOException e) {
            System.err.println("ファイル書き込みエラー: " + e.getMessage());
        }

        // Phase 2: Redisに保存
        saveFlightDataToRedis(clientController, uav, totalPathDistance);
    }

    /**
     * Phase 2: フライトデータをRedisに保存する
     * Phase 3b-12: メモリモード時はスキップ
     * @param clientController クライアントコントローラー
     * @param uav UAV
     * @param totalPathDistance 総経路距離
     */
    private static void saveFlightDataToRedis(ClientController clientController, Uav uav, double totalPathDistance) {
        // Phase 3b-12: メモリモード時はRedis書き込みをスキップ
        if (!redisEnabled || BoundaryController.getCurrentWorkerMode() != BoundaryController.WorkerMode.REDIS) {
            return;
        }

        try {
            String key = "flightlog:uav:" + uav.getId();
            RMap<String, Object> map = client.getMap(key);

            long flightTime = clientController.getFlightTime();
            long UAV_flightTime = uav.getFlightTime();
            long UAV_waitingTime = uav.getWaitingTime();
            long UAV_totalTime = UAV_flightTime + UAV_waitingTime;
            String pathString = Arrays.stream(uav.getPath()).mapToObj(String::valueOf).collect(Collectors.joining("-"));

            map.put("sourceBeaconId", uav.getSource().getId());
            map.put("destinationBeaconId", uav.getDistination().getId());
            map.put("passedTime", flightTime);
            map.put("uavFlightTime", UAV_flightTime);
            map.put("uavWaitingTime", UAV_waitingTime);
            map.put("uavTotalTime", UAV_totalTime);
            map.put("clientId", uav.getClientId());
            map.put("uavId", uav.getId());
            map.put("speed", uav.getSpeed());
            map.put("distance", totalPathDistance);
            map.put("path", pathString);
            map.put("savedTime", System.currentTimeMillis());

            LogManager.getInstance().log("Phase 2: UAV" + uav.getId() + " のフライトデータをRedisに保存しました");
        } catch (Exception e) {
            LogManager.getInstance().error("フライトデータRedis保存エラー", e);
        }
    }
}
