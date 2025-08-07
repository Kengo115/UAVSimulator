package server.route;

import client.Client;
import item.Uav;

import java.util.Queue;

/**
 * 経路割り当てサービスのインターフェース
 * 計算された経路をUAVに割り当てる
 */
public interface RouteAssignmentService {
    
    /**
     * 経路をUAVに割り当てる
     * 
     * @param startNode 開始ノード
     * @param goalNode 目標ノード
     * @param requiredUAVs 必要なUAV数
     * @param client クライアント
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     */
    void assignRoutes(int startNode, int goalNode, int requiredUAVs, Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue);
    
    /**
     * 経路を探索する
     * 
     * @param startNode 開始ノード
     * @param currentNode 現在のノード
     * @param goalNode 目標ノード
     * @param path 経路を格納する配列
     * @param pathIndex 経路配列のインデックス
     * @param passedFlow 通過した流量
     * @return 流量
     */
    int explorePath(int startNode, int currentNode, int goalNode, int[] path, int pathIndex, int passedFlow);
    
    /**
     * 残りのUAVに経路を割り当てる
     * 
     * @param needUAV 必要なUAV数
     * @param startNode 開始ノード
     * @param goalNode 目標ノード
     * @param client クライアント
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     */
    void adjustRemainingFlow(int needUAV, int startNode, int goalNode, Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue);
}
