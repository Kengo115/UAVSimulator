package server.route;

import client.Client;
import item.Uav;

import java.io.IOException;
import java.util.Queue;

/**
 * 経路探索アルゴリズムのインターフェース
 */
public interface RouteSearcher {

    /**
     * 経路探索を実行する
     * @param client クライアント
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param numLoop 反復回数
     * @throws IOException 入出力例外
     */
    void search(Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, int numLoop) throws IOException;
}
