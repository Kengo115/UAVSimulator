package server.route;

import server.util.NumericalSolverService;

/**
 * 拡張粘菌アルゴリズム（Extended Physarum Solver）による経路探索を行うインターフェース
 */
public interface ExtendedPhysarumSolverRouteSearcher extends PhysarumSolverRouteSearcher {
    
    /**
     * 管の太さを更新する（拡張版）
     * 
     * @param nodeNum ノード数
     * @param degeneracyEffect 退化効果
     * @param deltaTime デルタ時間
     * @param coefficientTanh tanh関数の係数
     */
    void updateTubeThickness(int nodeNum, double degeneracyEffect, double deltaTime, double coefficientTanh);
    
    /**
     * 拡張粘菌アルゴリズムを実行する
     * 
     * @param client クライアント
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param clientController クライアントコントローラ
     * @param numLoop 繰り返し回数
     * @throws java.io.IOException 入出力例外
     */
    void run_EPS(client.Client client, java.util.Queue<item.Uav> flyingUavQueue, java.util.Queue<item.Uav> uavQueue, client.ClientController clientController, int numLoop) throws java.io.IOException;
}
