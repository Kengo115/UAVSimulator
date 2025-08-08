package server.uav;

import client.ClientController;
import item.Uav;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * UAVの飛行データを記録するクラス
 */
public class FlightDataRecorder {

    /**
     * UAVの飛行経路を記録する
     * @param currentUAV UAV
     * @param method メソッド名
     */
    public static void recordRoute(Uav currentUAV, String method) {
        String dirPath = "src/result/EPS/path";
        //String dirPath = "src/result/PS/path";
        //String dirPath = "src/result/Dijkstra/path";
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
    }

    /**
     * フライトデータを保存する
     * @param clientController クライアントコントローラー
     * @param uav UAV
     * @param totalPathDistance 総経路距離
     */
    public static void saveFlightData(ClientController clientController, Uav uav, double totalPathDistance) {
        String dirPath = "src/result/EPS/time";
        //String dirPath = "src/result/PS/time";
        //String dirPath = "src/result/Dijkstra/time";
        String filePath = dirPath + "/flight_times.csv";

        File dir1 = new File(dirPath);
        if (!dir1.exists()) {
            dir1.mkdirs();
        }

        long flightTime = clientController.getFlightTime();
        long UAV_flightTime = uav.getFlightTime();
        long UAV_waitingTime = uav.getWaitingTime();

        try (FileWriter writer = new FileWriter(filePath, true)) {
            File file = new File(filePath);
            if (file.length() == 0) {
                writer.write("source,dist,passedTime,UAV_flightTime,UAV_waitingTime,ClientID,UAVID,speed,distance,path\n");
            }
            String pathString = Arrays.stream(uav.getPath()).mapToObj(String::valueOf).collect(Collectors.joining("-"));
            writer.write(String.format("%d,%d,%d,%d,%d,%d,%d,%f,%f,%s\n",
                    uav.getSource().getId(), uav.getDistination().getId(), flightTime, UAV_flightTime, UAV_waitingTime,
                    uav.getClientId(), uav.getId(), uav.getSpeed(), totalPathDistance, pathString));
        } catch (IOException e) {
            System.err.println("ファイル書き込みエラー: " + e.getMessage());
        }
    }
}
