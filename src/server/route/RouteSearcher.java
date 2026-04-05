package server.route;

import client.Client;

import java.io.IOException;

/**
 * 経路探索アルゴリズムのインターフェース
 */
public interface RouteSearcher {

    /**
     * 経路探索を実行する
     * @param client クライアント
     * @param numLoop 反復回数
     * @throws IOException 入出力例外
     */
    void search(Client client, int numLoop) throws IOException;
}
