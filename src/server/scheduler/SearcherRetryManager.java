package server.scheduler;

import client.Client;
import client.ClientController;
import item.Uav;
import server.route.RouteSearcher;
import server.route.SolverFailedException;
import server.util.LogManager;

import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase 5: サーチャー再試行管理クラス
 *
 * ソルバー失敗時の指数バックオフ再試行を管理
 * - 即時待機キュー（FIFO）
 * - 指数バックオフ（2→4→8→16→32→64→128秒）
 * - サーチャー排他制御（1クライアントのみ使用可能）
 * - 128秒後失敗でスキップ
 */
public class SearcherRetryManager {

    private static SearcherRetryManager instance;

    // 再試行設定
    private static final long INITIAL_DELAY_MS = 2000;   // 初回2秒
    private static final long MAX_DELAY_MS = 128000;     // 上限128秒
    private static final double BACKOFF_MULTIPLIER = 2.0;

    // 排他制御
    private final Object searcherLock = new Object();
    private AtomicBoolean searcherInUse = new AtomicBoolean(false);

    // 即時待機キュー（FIFO）
    private final LinkedList<SearchRequest> waitingQueue = new LinkedList<>();

    // 再試行情報管理
    private final ConcurrentHashMap<Integer, RetryInfo> retryInfoMap = new ConcurrentHashMap<>();

    // 再試行スケジューラ
    private final ScheduledExecutorService retryScheduler;

    /**
     * 検索リクエスト情報
     */
    public static class SearchRequest {
        public final Client client;
        public final ClientController clientController;
        public final Queue<Uav> flyingUavQueue;
        public final Queue<Uav> uavQueue;
        public final int numLoop;
        public final RouteSearcher searcher;
        public final Runnable preSearchAction;  // 検索前の処理（容量同期など）
        public final Runnable postSearchAction; // 検索後の処理（UAVFlyScheduler開始など）

        public SearchRequest(Client client, ClientController clientController,
                           Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue,
                           int numLoop, RouteSearcher searcher,
                           Runnable preSearchAction, Runnable postSearchAction) {
            this.client = client;
            this.clientController = clientController;
            this.flyingUavQueue = flyingUavQueue;
            this.uavQueue = uavQueue;
            this.numLoop = numLoop;
            this.searcher = searcher;
            this.preSearchAction = preSearchAction;
            this.postSearchAction = postSearchAction;
        }
    }

    /**
     * 再試行情報
     */
    private static class RetryInfo {
        int retryCount = 0;
        long currentDelayMs = INITIAL_DELAY_MS;
    }

    /**
     * コンストラクタ（プライベート）
     */
    private SearcherRetryManager() {
        this.retryScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "SearcherRetryScheduler");
            t.setDaemon(true);
            return t;
        });
        LogManager.getInstance().log("Phase 5: SearcherRetryManager initialized");
    }

    /**
     * シングルトンインスタンス取得
     */
    public static synchronized SearcherRetryManager getInstance() {
        if (instance == null) {
            instance = new SearcherRetryManager();
        }
        return instance;
    }

    /**
     * 検索をリクエスト（同期処理）
     * サーチャーが使用中の場合は待機キューに追加して待つ
     *
     * @param request 検索リクエスト
     * @return 成功時true、スキップ時false
     */
    public boolean requestSearch(SearchRequest request) {
        int clientId = request.client.getId();
        LogManager.getInstance().log(
            "Phase 5: client" + clientId + " 検索リクエスト受付"
        );

        // サーチャーの取得を待つ
        synchronized (searcherLock) {
            while (searcherInUse.get()) {
                // 待機キューに追加
                waitingQueue.add(request);
                LogManager.getInstance().log(
                    "Phase 5: client" + clientId + " 待機キューに追加 (キュー長=" + waitingQueue.size() + ")"
                );

                try {
                    searcherLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LogManager.getInstance().error("Phase 5: 待機中に割り込み発生", e);
                    waitingQueue.remove(request);
                    return false;
                }

                // 起きた時、自分の番かチェック
                if (waitingQueue.peek() == request) {
                    waitingQueue.poll();
                } else if (!waitingQueue.contains(request)) {
                    // 既にキューから取り出されている（自分の番）
                } else {
                    // まだ自分の番ではない、再度待機
                    continue;
                }
                break;
            }
            searcherInUse.set(true);
        }

        // 検索実行
        return executeSearchWithRetry(request);
    }

    /**
     * 検索を実行（再試行ロジック込み）
     */
    private boolean executeSearchWithRetry(SearchRequest request) {
        int clientId = request.client.getId();
        boolean success = false;

        try {
            // 再試行情報を取得または作成
            RetryInfo retryInfo = retryInfoMap.computeIfAbsent(clientId, k -> new RetryInfo());

            while (true) {
                try {
                    LogManager.getInstance().log(
                        "Phase 5: client" + clientId + " 検索開始" +
                        (retryInfo.retryCount > 0 ? " (再試行" + retryInfo.retryCount + "回目)" : "")
                    );

                    // 検索前処理（容量同期など）
                    if (request.preSearchAction != null) {
                        request.preSearchAction.run();
                    }

                    // 検索実行
                    request.searcher.search(
                        request.client,
                        request.flyingUavQueue,
                        request.uavQueue,
                        request.numLoop
                    );

                    // 成功
                    LogManager.getInstance().log(
                        "Phase 5: client" + clientId + " 検索成功"
                    );

                    // 検索後処理
                    if (request.postSearchAction != null) {
                        request.postSearchAction.run();
                    }

                    success = true;
                    break;

                } catch (SolverFailedException e) {
                    // ソルバー失敗
                    LogManager.getInstance().log(
                        "Phase 5: client" + clientId + " ソルバー失敗 (現在の待機時間=" +
                        retryInfo.currentDelayMs + "ms)"
                    );

                    // 64秒後の失敗ならスキップ
                    if (retryInfo.currentDelayMs >= MAX_DELAY_MS) {
                        LogManager.getInstance().log(
                            "Phase 5: client" + clientId + "の経路割り当てが行えませんでした（最大再試行回数超過）"
                        );
                        success = false;
                        break;
                    }

                    // 待機してから再試行
                    long delayMs = retryInfo.currentDelayMs;
                    LogManager.getInstance().log(
                        "Phase 5: client" + clientId + " " + (delayMs / 1000) + "秒後に再試行"
                    );

                    // 一旦サーチャーを解放して他のクライアントに譲る
                    releaseSearcherForRetry(request, delayMs);

                    // 待機
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        LogManager.getInstance().error("Phase 5: 再試行待機中に割り込み発生", ie);
                        success = false;
                        break;
                    }

                    // サーチャー再取得
                    reacquireSearcher(request);

                    // 次回の待機時間を更新
                    retryInfo.retryCount++;
                    retryInfo.currentDelayMs = Math.min(
                        (long) (retryInfo.currentDelayMs * BACKOFF_MULTIPLIER),
                        MAX_DELAY_MS
                    );
                }
            }

        } catch (IOException e) {
            LogManager.getInstance().error("Phase 5: client" + clientId + " 検索中にIOエラー", e);
            success = false;
        } finally {
            // 再試行情報をクリア
            retryInfoMap.remove(clientId);

            // サーチャー解放
            releaseSearcher();
        }

        return success;
    }

    /**
     * 再試行のためにサーチャーを一時解放
     */
    private void releaseSearcherForRetry(SearchRequest request, long delayMs) {
        synchronized (searcherLock) {
            searcherInUse.set(false);

            // 待機中のクライアントがいれば通知
            if (!waitingQueue.isEmpty()) {
                searcherLock.notifyAll();
            }
        }

        LogManager.getInstance().log(
            "Phase 5: client" + request.client.getId() +
            " サーチャー一時解放（" + (delayMs / 1000) + "秒後に再取得予定）"
        );
    }

    /**
     * 再試行のためにサーチャーを再取得
     */
    private void reacquireSearcher(SearchRequest request) {
        int clientId = request.client.getId();

        synchronized (searcherLock) {
            while (searcherInUse.get()) {
                LogManager.getInstance().log(
                    "Phase 5: client" + clientId + " サーチャー再取得待ち"
                );

                // 待機キューの先頭に追加（優先）
                waitingQueue.addFirst(request);

                try {
                    searcherLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    waitingQueue.remove(request);
                    return;
                }

                // 自分の番かチェック
                if (waitingQueue.peek() == request) {
                    waitingQueue.poll();
                    break;
                } else if (!waitingQueue.contains(request)) {
                    break;
                }
            }
            searcherInUse.set(true);
        }

        LogManager.getInstance().log(
            "Phase 5: client" + clientId + " サーチャー再取得完了"
        );
    }

    /**
     * サーチャーを解放し、次のクライアントに通知
     */
    private void releaseSearcher() {
        synchronized (searcherLock) {
            searcherInUse.set(false);

            // 待機中のクライアントがいれば通知
            if (!waitingQueue.isEmpty()) {
                searcherLock.notifyAll();
            }
        }

        LogManager.getInstance().log("Phase 5: サーチャー解放完了");
    }

    /**
     * 待機キューの長さを取得
     */
    public int getWaitingQueueSize() {
        synchronized (searcherLock) {
            return waitingQueue.size();
        }
    }

    /**
     * サーチャーが使用中かどうか
     */
    public boolean isSearcherInUse() {
        return searcherInUse.get();
    }

    /**
     * リセット（シミュレーション開始時に呼び出す）
     */
    public void reset() {
        synchronized (searcherLock) {
            waitingQueue.clear();
            retryInfoMap.clear();
            searcherInUse.set(false);
        }
        LogManager.getInstance().log("Phase 5: SearcherRetryManager リセット完了");
    }

    /**
     * シャットダウン
     */
    public void shutdown() {
        if (retryScheduler != null && !retryScheduler.isShutdown()) {
            retryScheduler.shutdown();
            try {
                if (!retryScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    retryScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                retryScheduler.shutdownNow();
            }
        }
        LogManager.getInstance().log("Phase 5: SearcherRetryManager シャットダウン完了");
    }

    /**
     * シングルトンインスタンスをリセット（テスト用）
     */
    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
    }
}
