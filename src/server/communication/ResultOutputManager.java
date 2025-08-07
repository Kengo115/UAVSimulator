package server.communication;

import client.Client;
import item.Uav;

import java.io.IOException;

/**
 * 結果出力を担当するインターフェース
 */
public interface ResultOutputManager {
    
    /**
     * Pajekファイル形式で結果を出力する
     * 
     * @param client クライアント
     * @param eps イプシロン値
     * @param allFlow 全体の流量
     * @param ct カウンター
     * @throws IOException 入出力例外
     */
    void outputToPajek(Client client, double eps, double allFlow, int ct) throws IOException;
    
    /**
     * Excel形式で結果を出力する
     * 
     * @param client クライアント
     * @param ct カウンター
     * @throws IOException 入出力例外
     */
    void outputToExcel(Client client, int ct) throws IOException;
    
    /**
     * テキストファイル形式で結果を出力する
     * 
     * @param client クライアント
     * @param ct カウンター
     * @throws IOException 入出力例外
     */
    void outputToTxt(Client client, int ct) throws IOException;
    
    /**
     * 経路情報をファイルに出力する
     * 
     * @param currentUAV 現在のUAV
     * @param method 使用したメソッド
     */
    void outputRoute(Uav currentUAV, String method);
    
    /**
     * フロー情報をファイルに出力する
     * 
     * @param client クライアント
     * @param ct カウンター
     * @throws IOException 入出力例外
     */
    void outputToFlow(Client client, int ct) throws IOException;
    
    /**
     * 経路ごとのUAV数をExcel形式で出力する
     * 
     * @param client クライアント
     * @param ct カウンター
     * @throws IOException 入出力例外
     */
    void outputRouteToExcel(Client client, int ct) throws IOException;
    
    /**
     * 実行カウンターを設定する
     * 
     * @param runCounter 実行カウンター
     */
    void setRunCounter(int runCounter);
    
    /**
     * 実行カウンターを取得する
     * 
     * @return 実行カウンター
     */
    int getRunCounter();
    
    /**
     * 出力ディレクトリを作成する
     * 
     * @param dirPath ディレクトリパス
     * @return 作成されたディレクトリ
     */
    java.io.File createOutputDirectory(String dirPath);
}
