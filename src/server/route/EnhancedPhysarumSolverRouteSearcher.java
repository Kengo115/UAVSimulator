package server.route;

import client.Client;
import client.ClientController;
import item.Uav;
import server.util.NumericalSolverService;

import java.io.IOException;
import java.util.Queue;

/**
 * 拡張粘菌アルゴリズム（Enhanced Physarum Solver）による経路探索を行うインターフェース
 */
public interface EnhancedPhysarumSolverRouteSearcher extends RouteSearchServer {
    
    /**
     * 拡張粘菌アルゴリズムを実行する
     * 
     * @param client クライアント
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param clientController クライアントコントローラ
     * @param numLoop 繰り返し回数
     * @throws IOException 入出力例外
     */
    void run_EPS(Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, ClientController clientController, int numLoop) throws IOException;
    
    /**
     * 圧力係数を計算する
     * 
     * @param nodeNum ノード数
     * @param source 出発地
     * @param destination 目的地
     * @param flowAmount 流量
     */
    void calculatePressureCoefficient(int nodeNum, int source, int destination, double flowAmount);
    
    /**
     * 線形方程式を解く
     * 
     * @param numericalSolver 数値計算ソルバー
     * @param nodeNum ノード数
     * @param maxIter 最大反復回数
     * @param eps 収束判定閾値
     * @return 収束した場合は1、収束しなかった場合は0
     */
    int solveLinearEquation(NumericalSolverService numericalSolver, int nodeNum, int maxIter, double eps);
    
    /**
     * 流量を計算する
     * 
     * @param nodeNum ノード数
     */
    void calculateFlow(int nodeNum);
    
    /**
     * シグモイド関数を適用する
     * 
     * @param nodeNum ノード数
     * @param gamma ガンマ値
     */
    void applySigmoidFunction(int nodeNum, double gamma);
    
    /**
     * 管の太さを更新する
     * 
     * @param nodeNum ノード数
     * @param degeneracyEffect 退化効果
     * @param deltaTime デルタ時間
     * @param coefficientTanh tanh関数の係数
     */
    void updateTubeThickness(int nodeNum, double degeneracyEffect, double deltaTime, double coefficientTanh);
    
    /**
     * 流量を保存する
     * 
     * @param nodeNum ノード数
     */
    void saveFlow(int nodeNum);
    
    /**
     * 流量を保存する（整数値に丸める）
     * 
     * @param flows 流量配列
     */
    void roundWithConservation(double[] flows);
    
    /**
     * UAVに経路を割り当てる
     * 
     * @param startNode 開始ノード
     * @param goalNode 目標ノード
     * @param requiredUAVs 必要なUAV数
     * @param client クライアント
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     */
    void runUAVFlow(int startNode, int goalNode, int requiredUAVs, Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue);
    
    /**
     * 数値計算ソルバーを設定する
     * 
     * @param numericalSolver 数値計算ソルバー
     */
    void setNumericalSolver(NumericalSolverService numericalSolver);
    
    /**
     * 数値計算ソルバーを取得する
     * 
     * @return 数値計算ソルバー
     */
    NumericalSolverService getNumericalSolver();
}
