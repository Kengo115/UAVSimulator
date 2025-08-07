package server.route;

import client.Client;
import client.ClientController;
import item.Uav;

import java.io.IOException;
import java.util.Queue;

/**
 * Dijkstraアルゴリズムによる経路探索を行うインターフェース
 */
public interface DijkstraRouteSearcher extends RouteSearchServer {
    
    /**
     * Dijkstraアルゴリズムを実行する
     * 
     * @param client クライアント
     * @param clientController クライアントコントローラ
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @throws IOException 入出力例外
     */
    void run_Dijkstra(Client client, ClientController clientController, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue) throws IOException;
    
    /**
     * Dijkstraアルゴリズムで最短経路を計算する
     * 
     * @param client クライアント
     * @return 計算された経路
     */
    int[] dijkstra(Client client);
    
    /**
     * UAVに経路を割り当てる
     * 
     * @param client クライアント
     * @param path 経路
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param requiredUAVs 必要なUAV数
     */
    void runUAVFlow_Dijkstra(Client client, int[] path, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, int requiredUAVs);
    
    /**
     * 隣接行列を取得する
     * 
     * @return 隣接行列
     */
    int[][] getAdjMatrix();
    
    /**
     * 隣接行列を設定する
     * 
     * @param adjMatrix 隣接行列
     */
    void setAdjMatrix(int[][] adjMatrix);
}
