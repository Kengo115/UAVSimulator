package server.uav;

import item.Uav;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 飛行データを記録する実装クラス
 */
public class FlightDataRecorderImpl implements FlightDataRecorder {
    
    private String baseDirectoryPath;
    
    /**
     * コンストラクタ
     * 
     * @param baseDirectoryPath 基本ディレクトリパス
     */
    public FlightDataRecorderImpl(String baseDirectoryPath) {
        this.baseDirectoryPath = baseDirectoryPath;
        // デフォルトのディレクトリパスが指定されていない場合は、src/outputを使用
        if (this.baseDirectoryPath == null || this.baseDirectoryPath.isEmpty()) {
            this.baseDirectoryPath = "src/output";
        }
    }
    
    /**
     * 飛行データを保存する
     * 
     * @param flightTime 飛行時間
     * @param uav UAV
     * @param totalPathDistance 総飛行距離
     */
    @Override
    public void saveFlightData(long flightTime, Uav uav, double totalPathDistance) {
        String dirPath = baseDirectoryPath + "/time";
        String filePath = dirPath + "/flight_times.csv";
        
        try {
            File dir = createFlightDataFile(dirPath);
            
            long uavFlightTime = recordFlightTime(uav);
            long uavWaitingTime = recordWaitingTime(uav);
            
            String pathString = Arrays.stream(uav.getPath())
                    .mapToObj(String::valueOf)
                    .collect(Collectors.joining("-"));
            
            writeFlightData(filePath, 
                    uav.getSource().getId(), 
                    uav.getDistination().getId(), 
                    flightTime, 
                    uavFlightTime, 
                    uavWaitingTime, 
                    uav.getClientId(), 
                    uav.getId(), 
                    uav.getSpeed(), 
                    totalPathDistance, 
                    pathString);
            
        } catch (IOException e) {
            System.err.println("ファイル書き込みエラー: " + e.getMessage());
        }
    }
    
    /**
     * 飛行時間を記録する
     * 
     * @param uav UAV
     * @return 飛行時間
     */
    @Override
    public long recordFlightTime(Uav uav) {
        return uav.getFlightTime();
    }
    
    /**
     * 待機時間を記録する
     * 
     * @param uav UAV
     * @return 待機時間
     */
    @Override
    public long recordWaitingTime(Uav uav) {
        return uav.getWaitingTime();
    }
    
    /**
     * 飛行経路を記録する
     * 
     * @param uav UAV
     * @param method 使用したメソッド
     */
    @Override
    public void recordRoute(Uav uav, String method) {
        String dirPath = baseDirectoryPath + "/path";
        String filePath = dirPath + "/flight_path.txt";
        
        try {
            File dir = createFlightDataFile(dirPath);
            
            try (FileWriter writer = new FileWriter(filePath, true)) {
                // ファイルが空の場合、ヘッダーを追加
                File file = new File(filePath);
                if (file.length() == 0) {
                    writer.write("clientId,UAVId,flightPath,method\n");
                }
                
                // UAVの飛行経路を取得し、"-" 区切りの文字列に変換
                String pathString = Arrays.stream(uav.getPath())
                        .mapToObj(String::valueOf)
                        .collect(Collectors.joining("-"));
                
                // 行を書き込み
                writer.write(String.format("%d,%d,%s,%s\n", 
                        uav.getClientId(), 
                        uav.getId(), 
                        pathString, 
                        method));
            }
        } catch (IOException e) {
            System.err.println("ファイル書き込みエラー: " + e.getMessage());
        }
    }
    
    /**
     * 飛行データファイルを作成する
     * 
     * @param dirPath ディレクトリパス
     * @return 作成されたファイル
     * @throws IOException 入出力例外
     */
    @Override
    public File createFlightDataFile(String dirPath) throws IOException {
        File dir = new File(dirPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }
    
    /**
     * 飛行データをファイルに書き込む
     * 
     * @param filePath ファイルパス
     * @param source 出発地
     * @param destination 目的地
     * @param flightTime 飛行時間
     * @param waitingTime 待機時間
     * @param clientId クライアントID
     * @param uavId UAVID
     * @param speed 速度
     * @param distance 距離
     * @param path 経路
     * @throws IOException 入出力例外
     */
    @Override
    public void writeFlightData(String filePath, int source, int destination, long flightTime, 
                               long uavFlightTime, long waitingTime, int clientId, int uavId, 
                               double speed, double distance, String path) throws IOException {
        try (FileWriter writer = new FileWriter(filePath, true)) {
            File file = new File(filePath);
            if (file.length() == 0) {
                writer.write("source,dist,passedTime,UAV_flightTime,UAV_waitingTime,ClientID,UAVID,speed,distance,path\n");
            }
            
            writer.write(String.format("%d,%d,%d,%d,%d,%d,%d,%f,%f,%s\n",
                    source, destination, flightTime, uavFlightTime, waitingTime,
                    clientId, uavId, speed, distance, path));
        }
    }
    
    /**
     * 基本ディレクトリパスを設定する
     * 
     * @param baseDirectoryPath 基本ディレクトリパス
     */
    public void setBaseDirectoryPath(String baseDirectoryPath) {
        this.baseDirectoryPath = baseDirectoryPath;
    }
    
    /**
     * 基本ディレクトリパスを取得する
     * 
     * @return 基本ディレクトリパス
     */
    public String getBaseDirectoryPath() {
        return baseDirectoryPath;
    }
}
