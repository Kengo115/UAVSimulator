package server.uav;

import item.Uav;

import java.io.IOException;

/**
 * 飛行データを記録するインターフェース
 */
public interface FlightDataRecorder {
    
    /**
     * 飛行データを保存する
     * 
     * @param flightTime 飛行時間
     * @param uav UAV
     * @param totalPathDistance 総飛行距離
     */
    void saveFlightData(long flightTime, Uav uav, double totalPathDistance);
    
    /**
     * 飛行時間を記録する
     * 
     * @param uav UAV
     * @return 飛行時間
     */
    long recordFlightTime(Uav uav);
    
    /**
     * 待機時間を記録する
     * 
     * @param uav UAV
     * @return 待機時間
     */
    long recordWaitingTime(Uav uav);
    
    /**
     * 飛行経路を記録する
     * 
     * @param uav UAV
     * @param method 使用したメソッド
     */
    void recordRoute(Uav uav, String method);
    
    /**
     * 飛行データファイルを作成する
     * 
     * @param dirPath ディレクトリパス
     * @return 作成されたファイル
     * @throws IOException 入出力例外
     */
    java.io.File createFlightDataFile(String dirPath) throws IOException;
    
    /**
     * 飛行データをファイルに書き込む
     * 
     * @param filePath ファイルパス
     * @param source 出発地
     * @param destination 目的地
     * @param flightTime 飛行時間
     * @param uavFlightTime UAV飛行時間
     * @param waitingTime 待機時間
     * @param clientId クライアントID
     * @param uavId UAVID
     * @param speed 速度
     * @param distance 距離
     * @param path 経路
     * @throws IOException 入出力例外
     */
    void writeFlightData(String filePath, int source, int destination, long flightTime, 
                        long uavFlightTime, long waitingTime, int clientId, int uavId, 
                        double speed, double distance, String path) throws IOException;
}
