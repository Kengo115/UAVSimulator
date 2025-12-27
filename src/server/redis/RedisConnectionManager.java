package server.redis;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import server.util.LogManager;

import java.io.IOException;
import java.io.InputStream;

/**
 * Redis接続を管理するシングルトンクラス
 * Redissonクライアントのライフサイクルを管理する
 */
public class RedisConnectionManager {
    private static RedisConnectionManager instance;
    private RedissonClient redissonClient;
    private boolean isConnected = false;

    // Redis設定
    private String redisHost = "localhost";
    private int redisPort = 6379;
    private int connectionPoolSize = 50;
    private int connectionMinimumIdleSize = 10;
    private int timeout = 10000; // 10秒

    /**
     * プライベートコンストラクタ（シングルトンパターン）
     */
    private RedisConnectionManager() {
        // 環境変数から設定を読み込む
        String envHost = System.getenv("REDIS_HOST");
        String envPort = System.getenv("REDIS_PORT");

        if (envHost != null && !envHost.isEmpty()) {
            this.redisHost = envHost;
        }

        if (envPort != null && !envPort.isEmpty()) {
            try {
                this.redisPort = Integer.parseInt(envPort);
            } catch (NumberFormatException e) {
                LogManager.getInstance().error("無効なREDIS_PORT環境変数: " + envPort, e);
            }
        }
    }

    /**
     * シングルトンインスタンスを取得
     * @return RedisConnectionManagerのインスタンス
     */
    public static synchronized RedisConnectionManager getInstance() {
        if (instance == null) {
            instance = new RedisConnectionManager();
        }
        return instance;
    }

    /**
     * Redisに接続する
     * @throws IOException 接続に失敗した場合
     */
    public synchronized void connect() throws IOException {
        if (isConnected && redissonClient != null) {
            LogManager.getInstance().log("Redis接続は既に確立されています");
            return;
        }

        try {
            Config config = new Config();
            config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort)
                .setConnectionPoolSize(connectionPoolSize)
                .setConnectionMinimumIdleSize(connectionMinimumIdleSize)
                .setTimeout(timeout)
                .setRetryAttempts(3)
                .setRetryInterval(1500);

            redissonClient = Redisson.create(config);
            isConnected = true;

            LogManager.getInstance().log("Redisに接続しました: " + redisHost + ":" + redisPort);
        } catch (Exception e) {
            isConnected = false;
            LogManager.getInstance().error("Redis接続に失敗しました", e);
            throw new IOException("Redis接続に失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * Redis接続をテストする
     * @return 接続が正常な場合true
     */
    public boolean testConnection() {
        if (!isConnected || redissonClient == null) {
            return false;
        }

        try {
            // 簡単なPINGテスト
            redissonClient.getKeys().count();
            return true;
        } catch (Exception e) {
            LogManager.getInstance().error("Redis接続テストに失敗しました", e);
            return false;
        }
    }

    /**
     * Redissonクライアントを取得する
     * @return RedissonClientインスタンス
     * @throws IllegalStateException 接続が確立されていない場合
     */
    public RedissonClient getClient() {
        if (!isConnected || redissonClient == null) {
            throw new IllegalStateException("Redis接続が確立されていません。先にconnect()を呼び出してください");
        }
        return redissonClient;
    }

    /**
     * 接続状態を確認する
     * @return 接続されている場合true
     */
    public boolean isConnected() {
        return isConnected && redissonClient != null;
    }

    /**
     * Redis接続を切断する
     */
    public synchronized void disconnect() {
        if (redissonClient != null) {
            try {
                redissonClient.shutdown();
                LogManager.getInstance().log("Redis接続を切断しました");
            } catch (Exception e) {
                LogManager.getInstance().error("Redis切断中にエラーが発生しました", e);
            } finally {
                redissonClient = null;
                isConnected = false;
            }
        }
    }

    /**
     * Redis設定を取得する
     * @return Redis接続情報の文字列
     */
    public String getConnectionInfo() {
        return String.format("Redis: %s:%d (接続状態: %s)",
            redisHost, redisPort, isConnected ? "接続中" : "未接続");
    }

    /**
     * 接続プールサイズを設定する
     * @param size プールサイズ
     */
    public void setConnectionPoolSize(int size) {
        if (isConnected) {
            LogManager.getInstance().log("警告: 接続中は設定を変更できません");
            return;
        }
        this.connectionPoolSize = size;
    }

    /**
     * 最小アイドル接続数を設定する
     * @param size 最小アイドル接続数
     */
    public void setConnectionMinimumIdleSize(int size) {
        if (isConnected) {
            LogManager.getInstance().log("警告: 接続中は設定を変更できません");
            return;
        }
        this.connectionMinimumIdleSize = size;
    }
}
