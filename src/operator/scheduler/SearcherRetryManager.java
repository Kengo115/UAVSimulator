package operator.scheduler;
import network_manager.scheduler.FlightScheduler;

import shared.client.Client;
import shared.client.ClientController;
import network_manager.redis.PathWaitingManager;
import operator.BoundaryController;
import operator.route.RouteSearcher;
import operator.route.SolverFailedException;
import shared.util.LogManager;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase 5: サーチャー再試行管理クラス
 *
 * ソルバー失敗時の指数バックオフ再試行を管理
 * - 即時待機キュー（FIFO）
 * - 指数バックオフ（2→4→8→16→32→64→128→256→512→1024秒）
 * - サーチャー排他制御（1クライアントのみ使用可能）
 * - 10回失敗でスキップ
 */
public class SearcherRetryManager {

    private static SearcherRetryManager instance;

    // 再試行設定
    private static final long INITIAL_DELAY_MS = 2000;   // 初回2秒
    private static final long MAX_DELAY_MS = 1024000;    // 上限1024秒
    private static final int MAX_RETRIES = 10;           // 最大再試行回数
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
        public final int numLoop;
        public final RouteSearcher searcher;
        public final Runnable preSearchAction;  // 検索前の処理（容量同期など）
        public final Runnable postSearchAction; // 検索後の処理

        public SearchRequest(Client client, ClientController clientController,
                           int numLoop, RouteSearcher searcher,
                           Runnable preSearchAction, Runnable postSearchAction) {
            this.client = client;
            this.clientController = clientController;
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
            boolean addedToQueue = false;

            while (searcherInUse.get()) {
                // 待機キューに追加（初回のみ）
                if (!addedToQueue) {
                    waitingQueue.add(request);
                    addedToQueue = true;
                    LogManager.getInstance().log(
                        "Phase 5: client" + clientId + " 待機キューに追加 (キュー長=" + waitingQueue.size() + ")"
                    );
                }

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
                    break;
                } else if (!waitingQueue.contains(request)) {
                    // 既にキューから取り出されている（自分の番）
                    break;
                }
                // まだ自分の番ではない、再度wait（addはしない）
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
                    String failureMsg = "Phase 5: client" + clientId + " ソルバー失敗 (再試行=" +
                        retryInfo.retryCount + "/" + MAX_RETRIES + ", 待機時間=" +
                        retryInfo.currentDelayMs + "ms, 理由=" + e.getReason() + ")";
                    LogManager.getInstance().log(failureMsg);
                    logToSearcherFailureFile(failureMsg);

                    // Phase 12-Fix: 飛行中UAVとPathWaitingManagerのクリーンアップ（PG-EPS重複UAV防止）
                    // リトライ時に古いエントリが残留すると、成功時に重複UAVが記録される
                    try {
                        FlightScheduler flightScheduler = FlightScheduler.getInstance();

                        // Phase 12-Fix: Step 1 - 飛行中UAVをキャンセル（最重要）
                        // dequeueされて飛行中のUAVも確実にキャンセルする
                        int cancelledFlights = flightScheduler.cancelClientFlights(clientId);
                        if (cancelledFlights > 0) {
                            String cancelMsg = "Phase 12-Fix: client" + clientId + " 飛行中UAVをキャンセル (UAV数=" + cancelledFlights + ")";
                            LogManager.getInstance().log(cancelMsg);
                            logToSearcherFailureFile(cancelMsg);
                        }

                        // Phase 12: Step 2 - PathWaitingManagerキューをクリア
                        // キューに残っているUAV（まだdequeueされていないもの）を削除
                        PathWaitingManager pathWaitingManager = flightScheduler.getPathWaitingManager();
                        if (pathWaitingManager != null) {
                            int waitingCount = pathWaitingManager.getWaitingCount(clientId);
                            if (waitingCount > 0) {
                                pathWaitingManager.clear(clientId);
                                String clearMsg = "Phase 12: client" + clientId + " PathWaitingManagerキューをクリア (削除UAV数=" + waitingCount + ")";
                                LogManager.getInstance().log(clearMsg);
                                logToSearcherFailureFile(clearMsg);
                            }
                        }
                    } catch (Exception cleanupEx) {
                        LogManager.getInstance().error("Phase 12-Fix: client" + clientId + " クリーンアップ失敗", cleanupEx);
                        // クリーンアップ失敗は継続（リトライ処理は続行）
                    }

                    // 最大再試行回数超過ならスキップ
                    if (retryInfo.retryCount >= MAX_RETRIES) {
                        String maxRetryMsg = "Phase 5: client" + clientId + "の経路割り当てが行えませんでした（最大再試行回数" + MAX_RETRIES + "回超過）";
                        LogManager.getInstance().log(maxRetryMsg);
                        logToSearcherFailureFile(maxRetryMsg);

                        // Phase 12-Fix: 最大再試行超過時も完全クリーンアップ（残留UAV削除）
                        try {
                            FlightScheduler flightScheduler = FlightScheduler.getInstance();

                            // Phase 12-Fix: 飛行中UAVをキャンセル
                            int cancelledFlights = flightScheduler.cancelClientFlights(clientId);
                            if (cancelledFlights > 0) {
                                String cancelMsg = "Phase 12-Fix: client" + clientId + " 最大再試行超過により飛行中UAVをキャンセル (UAV数=" + cancelledFlights + ")";
                                LogManager.getInstance().log(cancelMsg);
                                logToSearcherFailureFile(cancelMsg);
                            }

                            // Phase 12: PathWaitingManagerキューをクリア
                            PathWaitingManager pathWaitingManager = flightScheduler.getPathWaitingManager();
                            if (pathWaitingManager != null) {
                                int waitingCount = pathWaitingManager.getWaitingCount(clientId);
                                if (waitingCount > 0) {
                                    pathWaitingManager.clear(clientId);
                                    String clearMsg = "Phase 12: client" + clientId + " 最大再試行超過によりPathWaitingManagerキューをクリア (削除UAV数=" + waitingCount + ")";
                                    LogManager.getInstance().log(clearMsg);
                                    logToSearcherFailureFile(clearMsg);
                                }
                            }
                        } catch (Exception cleanupEx) {
                            LogManager.getInstance().error("Phase 12-Fix: client" + clientId + " クリーンアップ失敗（最大再試行超過時）", cleanupEx);
                        }

                        success = false;
                        break;
                    }

                    // 待機してから再試行
                    long delayMs = retryInfo.currentDelayMs;
                    String retryMsg = "Phase 5: client" + clientId + " " + (delayMs / 1000) + "秒後に再試行";
                    LogManager.getInstance().log(retryMsg);
                    logToSearcherFailureFile(retryMsg);

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

    /**
     * searcher_failure.log にログを出力する
     * Phase 10: sim_N ディレクトリ配下に出力するように修正
     */
    private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * Phase 10: searcher_failure.logのパスを動的に生成
     * @return searcher_failure.logのパス（例: src/log/sim_1/searcher_failure.log）
     */
    private String getFailureLogPath() {
        String logBaseDir = BoundaryController.getLogBaseDir();
        String simId = BoundaryController.getSimId();
        return logBaseDir + "/sim_" + simId + "/searcher_failure.log";
    }

    private void logToSearcherFailureFile(String message) {
        try {
            String failureLogPath = getFailureLogPath();
            File logFile = new File(failureLogPath);
            File logDir = logFile.getParentFile();

            if (!logDir.exists()) {
                logDir.mkdirs();
            }

            try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(failureLogPath, true)))) {
                String timestamp = LocalDateTime.now().format(LOG_TIME_FORMAT);
                writer.println(timestamp + " - " + message);
            }
        } catch (IOException e) {
            // ログファイル書き込みエラーは無視
        }
    }
}
