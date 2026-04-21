package shared.redis;

import shared.item.UAVJob;

import org.redisson.api.RBlockingQueue;
import org.redisson.api.RedissonClient;
import shared.util.LogManager;

import java.util.concurrent.TimeUnit;

/**
 * UAVジョブキュー管理クラス
 * Phase 3b: メインプロセスがジョブを投入し、ワーカーが取得する
 *
 * RedissonのRBlockingQueueを使用してジョブキューを実装
 */
public class UAVJobQueue {

    private static final String QUEUE_KEY = "jobs:uav";

    private boolean redisEnabled = false;
    private RedissonClient client;
    private RBlockingQueue<UAVJob> queue;

    /**
     * コンストラクタ
     */
    public UAVJobQueue() {
        try {
            RedisConnectionManager connectionManager = RedisConnectionManager.getInstance();
            if (connectionManager.isConnected()) {
                this.client = connectionManager.getClient();
                this.queue = client.getBlockingQueue(QUEUE_KEY);
                this.redisEnabled = true;
                LogManager.getInstance().log("UAVJobQueue: ジョブキュー初期化完了");
            } else {
                LogManager.getInstance().log("UAVJobQueue: Redis未接続のため、ジョブキュー機能は無効です");
            }
        } catch (Exception e) {
            LogManager.getInstance().error("UAVJobQueue初期化エラー", e);
            this.redisEnabled = false;
        }
    }

    /**
     * ジョブをキューに追加する（メインプロセス用）
     * LPUSH相当の操作
     *
     * @param job UAVジョブ
     * @return 成功した場合true
     */
    public boolean enqueueJob(UAVJob job) {
        if (!redisEnabled) {
            LogManager.getInstance().log("Redis無効のため、ジョブをキューに追加できません");
            return false;
        }

        try {
            boolean added = queue.offer(job);
            if (added) {
                LogManager.getInstance().log("Phase 3b: ジョブ投入成功 - client" + job.getClientId() + " UAV" + job.getUavId());
            } else {
                LogManager.getInstance().log("Phase 3b: ジョブ投入失敗 - client" + job.getClientId() + " UAV" + job.getUavId());
            }
            return added;
        } catch (Exception e) {
            LogManager.getInstance().error("ジョブ投入エラー: client" + job.getClientId() + " UAV" + job.getUavId(), e);
            return false;
        }
    }

    /**
     * ジョブをキューから取得する（ワーカー用）
     * BRPOP相当の操作（ブロッキング）
     *
     * @param timeout タイムアウト時間
     * @param unit タイムアウト単位
     * @return UAVジョブ（タイムアウト時はnull）
     * @throws InterruptedException 割り込まれた場合
     */
    public UAVJob dequeueJob(long timeout, TimeUnit unit) throws InterruptedException {
        if (!redisEnabled) {
            LogManager.getInstance().log("Redis無効のため、ジョブを取得できません");
            return null;
        }

        try {
            UAVJob job = queue.poll(timeout, unit);
            if (job != null) {
                LogManager.getInstance().log("Phase 3b: ジョブ取得成功 - client" + job.getClientId() + " UAV" + job.getUavId());
            }
            return job;
        } catch (InterruptedException e) {
            LogManager.getInstance().log("ジョブ取得中に割り込みが発生しました");
            throw e;
        } catch (Exception e) {
            LogManager.getInstance().error("ジョブ取得エラー", e);
            return null;
        }
    }

    /**
     * キューのサイズを取得する
     *
     * @return キュー内のジョブ数
     */
    public int getQueueSize() {
        if (!redisEnabled) {
            return 0;
        }

        try {
            return queue.size();
        } catch (Exception e) {
            LogManager.getInstance().error("キューサイズ取得エラー", e);
            return 0;
        }
    }

    /**
     * キューをクリアする
     * テスト用
     */
    public void clearQueue() {
        if (!redisEnabled) {
            return;
        }

        try {
            queue.clear();
            LogManager.getInstance().log("Phase 3b: ジョブキューをクリアしました");
        } catch (Exception e) {
            LogManager.getInstance().error("キュークリアエラー", e);
        }
    }

    /**
     * Redis機能が有効かどうかを確認する
     *
     * @return 有効な場合true
     */
    public boolean isEnabled() {
        return redisEnabled;
    }
}
