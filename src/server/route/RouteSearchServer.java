package server.route;

import client.Client;
import item.Uav;

import java.io.IOException;
import java.util.Queue;

/**
 * 経路探索サーバのインターフェース
 * 各経路探索アルゴリズムの実装クラスはこのインターフェースを実装する
 */
public interface RouteSearchServer {
    
    /**
     * 経路探索を実行し、UAVに経路を割り当てる
     * 
     * @param client クライアント
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param numLoop 繰り返し回数（アルゴリズムによって使用）
     * @throws IOException 入出力例外
     */
    void searchAndAssignRoutes(Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, int numLoop) throws IOException;
    
    /**
     * 経路探索アルゴリズムの名前を取得
     * 
     * @return アルゴリズム名
     */
    String getAlgorithmName();
}
