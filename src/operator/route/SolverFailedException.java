package operator.route;

/**
 * Phase 5: ソルバー失敗例外
 *
 * EPS/PG-EPSのソルバーが収束しなかった場合（-1を返却）にスローされる例外
 * SearcherRetryManagerがこの例外をキャッチして再試行処理を行う
 */
public class SolverFailedException extends RuntimeException {

    private final int clientId;
    private final int iteration;
    private final String searcherType;

    /**
     * コンストラクタ
     *
     * @param clientId クライアントID
     * @param iteration 失敗したイテレーション番号
     * @param searcherType サーチャーの種類
     */
    public SolverFailedException(int clientId, int iteration, String searcherType) {
        super("Solver failed for client" + clientId + " at iteration " + iteration + " (" + searcherType + ")");
        this.clientId = clientId;
        this.iteration = iteration;
        this.searcherType = searcherType;
    }

    /**
     * コンストラクタ（イテレーション情報なし）
     *
     * @param clientId クライアントID
     * @param searcherType サーチャーの種類
     */
    public SolverFailedException(int clientId, String searcherType) {
        this(clientId, -1, searcherType);
    }

    /**
     * クライアントIDを取得
     */
    public int getClientId() {
        return clientId;
    }

    /**
     * 失敗したイテレーション番号を取得
     */
    public int getIteration() {
        return iteration;
    }

    /**
     * サーチャーの種類を取得
     */
    public String getSearcherType() {
        return searcherType;
    }

    /**
     * 失敗理由を取得（searcherTypeと同じ）
     */
    public String getReason() {
        return searcherType;
    }
}
