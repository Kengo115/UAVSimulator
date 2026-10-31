package operator.route;

import shared.client.Client;
import shared.item.BeaconCluster;
import shared.item.Link;
import operator.controller.ServerController;
import shared.util.EffectiveResistanceSolver;
import shared.util.EPSSavePoint;
import shared.util.ICCGSolver;
import shared.util.LogManager;
import shared.util.MaxFlowSolver;
import shared.util.MathUtils;
import shared.util.ResultOutputManager;
import shared.redis.ClientTimeManager;
import shared.redis.PathWaitingManager;
import shared.item.UAVJob;
import operator.BoundaryController;

import java.io.IOException;

/**
 * 二分法型圧力誘導EPS (Bisectional Pressure-Guided EPS)
 * Phase 4: 最大フロー超過分は経路待ちキューで管理し、
 * 第一ホップ通過時に経路をコピーして飛行開始する
 *
 * 二分探索を使用して最適なフロー値を見つけるEPS
 */
public class BisectionalPressureGuidedEPSRouteSearcher extends ExtendedPhysarumSolverRouteSearcher {

    // ===== 実験モード定義 =====
    // 上限タイプ × トリガー構成 = 9通り
    //   上限タイプ: 1=固定100 / 2=理論値p_limit_high / 3=理論値p_limit_high+下限p_limit_low
    //   トリガー:   1=なし    / 2=-θのみ(Trigger2) / 3=±θ(Trigger1+Trigger2)
    private enum ExperimentMode {
        // ---- 上限100（固定値）----
        /** 固定上限p_limit=100 / トリガーなし */
        MODE_1_1,
        /** 固定上限p_limit=100 / Trigger2(-θ)のみ */
        MODE_1_2,
        /** 固定上限p_limit=100 / Trigger1(+θ) + Trigger2(-θ) */
        MODE_1_3,
        // ---- 理論的上限p_limit_high ----
        /** 理論的上限p_limit_high / トリガーなし */
        MODE_2_1,
        /** 理論的上限p_limit_high / Trigger2(-θ)のみ */
        MODE_2_2,
        /** 理論的上限p_limit_high / Trigger1(+θ) + Trigger2(-θ) */
        MODE_2_3,
        // ---- 理論的上限p_limit_high + 下限p_limit_low ----
        /** 理論的上限p_limit_high / 下限p_limit_low / トリガーなし */
        MODE_3_1,
        /** 理論的上限p_limit_high / 下限p_limit_low / Trigger2(-θ)のみ */
        MODE_3_2,
        /** 理論的上限p_limit_high / 下限p_limit_low / Trigger1(+θ) + Trigger2(-θ) */
        MODE_3_3
    }
    /** ← ここを変えるだけでモード切替 */
    private static final ExperimentMode EXPERIMENT_MODE = ExperimentMode.MODE_3_3;

    // ===== モード判定ヘルパー（static final フィールドと同等に扱える） =====

    /** 理論的p_limit_highを使用するか（Mode 2_x / 3_x） */
    private static boolean usesTheoreticalPLimit() {
        return EXPERIMENT_MODE == ExperimentMode.MODE_2_1
            || EXPERIMENT_MODE == ExperimentMode.MODE_2_2
            || EXPERIMENT_MODE == ExperimentMode.MODE_2_3
            || EXPERIMENT_MODE == ExperimentMode.MODE_3_1
            || EXPERIMENT_MODE == ExperimentMode.MODE_3_2
            || EXPERIMENT_MODE == ExperimentMode.MODE_3_3;
    }
    /** p_limit_low（下限絶対値閾値）を使用するか（Mode 3_x） */
    private static boolean usesLowerBound() {
        return EXPERIMENT_MODE == ExperimentMode.MODE_3_1
            || EXPERIMENT_MODE == ExperimentMode.MODE_3_2
            || EXPERIMENT_MODE == ExperimentMode.MODE_3_3;
    }
    /** Trigger 1（+θ 圧力増加率 → フロー削減）を使用するか（modeN_3） */
    private static boolean usesTrigger1() {
        return EXPERIMENT_MODE == ExperimentMode.MODE_1_3
            || EXPERIMENT_MODE == ExperimentMode.MODE_2_3
            || EXPERIMENT_MODE == ExperimentMode.MODE_3_3;
    }
    /** Trigger 2（-θ 圧力減少率 → フロー増加）を使用するか（modeN_2 / modeN_3） */
    private static boolean usesTrigger2() {
        return EXPERIMENT_MODE == ExperimentMode.MODE_1_2
            || EXPERIMENT_MODE == ExperimentMode.MODE_1_3
            || EXPERIMENT_MODE == ExperimentMode.MODE_2_2
            || EXPERIMENT_MODE == ExperimentMode.MODE_2_3
            || EXPERIMENT_MODE == ExperimentMode.MODE_3_2
            || EXPERIMENT_MODE == ExperimentMode.MODE_3_3;
    }

    // 定数
    private static final double INIT_THICKNESS = 0.5; // 初期チューブ厚
    private static final double INIT_LENGTH = 1.0; // 初期チューブ長
    private static final int MAX_ITERATIONS = 10000; // 最大イテレーション数
    private static final int REQUIRED_STABLE_ITERATIONS = 1000; // 収束判定用の連続安定回数
    private static final int MAX_BINARY_SEARCH_ITERATIONS = 20; // 二分探索の最大回数
    private static final double MIN_ASSIGNMENT_RATIO = 1.0 / 4.0; // EPS最低割当比率（1/4 = 25%）

    // ソースノード圧力専用閾値
    private static final double SOURCE_PRESSURE_EMERGENCY = 100; // 圧力絶対値閾値（Mode 1/2用ベース値）
    private static final double SOURCE_PRESSURE_CHANGE_THRESHOLD = 0.50; // 50%増加閾値（Trigger 1 / Mode 1,3用）
    private static final double SOURCE_PRESSURE_REDUCTION_THRESHOLD = 0.50; // 50%減少閾値（Trigger 2 / Mode 1,2,3用）

    // 理論的閾値パラメータ（Mode 2_x / 3_x 用）
    /** p_limit_high = ALPHA × Q_max × R_eff の倍率係数（BoundaryController のファイル出力でも参照） */
    public static final double ALPHA = 1.2; // 圧力上限の倍率係数
    /** p_limit_low  = BETA  × p_limit_high の係数（BoundaryController のファイル出力でも参照） */
    public static final double BETA  = 0.2;  // 圧力下限の係数（Mode 3_x）

    // フロー減少（UAV整数値対応）
    private static final double MINIMUM_FLOW_RATIO = 0.1; // 最小安全フロー（要求フローの10%）

    // 安定化フェーズ設定
    private static final int STABILIZATION_PHASE_ITERATIONS = 5; // 安定化フェーズのイテレーション数

    // 現在のフロー値と制御状態
    private double currentFlow;
    private double requestedFlow;
    private int stableIterationCount = 0;
    private int outputIterationCursor = 0;
    private double[] previousThickness;
    private int sourceNode = -1; // ソースノードID
    private int destNode = -1; // デスティネーションノードID
    private double previousSourcePressure = 0.0; // 前回のソース圧力値
    private double currentFlowBaselinePressure = 0.0; // 現在の要求フローでの基準圧力値（フロー増加判定用）
    private boolean currentFlowBaselineCaptured = false; // 現在フロー基準圧力がキャプチャ済みかどうか

    // 安定化フェーズ制御
    private boolean inStabilizationPhase = false; // 安定化フェーズ中かどうか
    private int stabilizationIterationCount = 0; // 安定化フェーズのイテレーション数

    // 二分探索用変数
    private double lowerBound = 0.0; // 下限
    private double upperBound; // 上限（要求フロー）
    private int binarySearchIteration = 0; // 二分探索回数

    // 実行時の圧力閾値（モードと(s,d)ペアに応じてsearch()冒頭で設定）
    private double activePLimit;    // 圧力上限値（Trigger 3）
    private double activePLimitLow; // 圧力下限値（Mode 3_x用）

    // EPSセーブポイント機能
    private EPSSavePoint epsSavePoint = null; // EPSセーブポイント

    // 二分探索スタック時のシフト方向制御
    private int shiftDirection = 0; // 0=なし, -1=下方シフト中, +1=上方シフト中

    // Phase 5: 現在処理中のクライアントID
    private int currentClientId = -1;

    /**
     * コンストラクタ
     * @param serverController サーバーコントローラー
     * @param adjMatrix 隣接行列
     * @param link リンク情報
     * @param beaconCluster ビーコンクラスター
     * @param node ノード数
     */
    public BisectionalPressureGuidedEPSRouteSearcher(ServerController serverController, int[][] adjMatrix,
                                                    Link[][] link, BeaconCluster beaconCluster, int node) {
        super(serverController, adjMatrix, link, beaconCluster, node);
    }
    
    /**
     * 経路記録のタグを取得する
     * @return 経路記録のタグ
     */
    @Override
    protected String getRouteRecordTag() {
        return "runUAVFlow_BISECTIONAL_PGEPS";
    }

    /**
     * 残りの経路記録のタグを取得する
     * @return 残りの経路記録のタグ
     */
    @Override
    protected String getRemainingRouteRecordTag() {
        return "remainingFlow_BISECTIONAL_PGEPS";
    }

    @Override
    public void search(Client client, int numLoop) throws IOException {
        // 通常のUAV割り当てのみを行う場合は親クラスのメソッドを呼び出す
        if (numLoop == 0) {
            super.search(client, numLoop);
            return;
        }

        // 入力パラメータの取得と初期化
        requestedFlow = client.getFlow().getTheNumberOfUAV();
        this.sourceNode = client.getFlow().getSource().getId();
        this.destNode = client.getFlow().getDestination().getId();
        this.currentClientId = client.getId();  // Phase 5: クライアントID保存
        double eps = 1e-10;

        // 実験モードに応じた圧力閾値の初期化
        this.activePLimit    = SOURCE_PRESSURE_EMERGENCY; // Mode 1/2 デフォルト（= 100）
        this.activePLimitLow = BETA * activePLimit;

        if (usesTheoreticalPLimit()) {
            // 理論的圧力閾値: p_limit(s,d) = α × Q_max(G_0) × R_eff_sim^(0)(s,d)
            if (MaxFlowSolver.isInitialized() && EffectiveResistanceSolver.isInitialized()) {
                double qMax = MaxFlowSolver.getMaxFlow(sourceNode, destNode);
                double rEff = EffectiveResistanceSolver.getReff(sourceNode, destNode);
                if (qMax > 0 && rEff > 0) {
                    this.activePLimit    = ALPHA * qMax * rEff;
                    this.activePLimitLow = BETA * activePLimit;
                    LogManager.getInstance().log("BisectionalPGEPS: [" + EXPERIMENT_MODE + "] Theoretical p_limit(s=" + sourceNode + ",d=" + destNode + ")=" +
                                               String.format("%.4f", activePLimit) +
                                               " (Q_max=" + qMax + ", R_eff=" + String.format("%.4f", rEff) + ", α=" + ALPHA + ", β=" + BETA + ")");
                } else {
                    LogManager.getInstance().log("BisectionalPGEPS: [" + EXPERIMENT_MODE + "] WARNING: Q_max=" + qMax + " or R_eff=" + rEff +
                                               " is invalid, falling back to SOURCE_PRESSURE_EMERGENCY=" + SOURCE_PRESSURE_EMERGENCY);
                }
            } else {
                LogManager.getInstance().log("BisectionalPGEPS: [" + EXPERIMENT_MODE + "] WARNING: Solvers not initialized, falling back to SOURCE_PRESSURE_EMERGENCY=" + SOURCE_PRESSURE_EMERGENCY);
            }
        }

        LogManager.getInstance().log("BisectionalPGEPS: [" + EXPERIMENT_MODE + "] activePLimit=" + String.format("%.4f", activePLimit) + ", activePLimitLow=" + String.format("%.4f", activePLimitLow));

        LogManager.getInstance().log("BisectionalPGEPS: Starting dynamic binary search EPS with requested flow " + requestedFlow);

        // 二分探索の初期化
        lowerBound = 0.0;
        upperBound = requestedFlow;
        currentFlow = requestedFlow; // 要求フローから開始
        
        LogManager.getInstance().log("BisectionalPGEPS: Initial bounds - Lower: " + lowerBound + ", Upper: " + upperBound + ", Starting flow: " + currentFlow);

        // 前回チューブ厚の初期化
        initializePreviousThickness();
        
        try {
        // EPSセーブポイントの初期化
        epsSavePoint = new EPSSavePoint(node);
        LogManager.getInstance().log("BisectionalPGEPS: EPSSavePoint initialized");

        // 容量0のリンクを一時的に切断（EPSが選択しないようにする）
        disconnectZeroCapacityLinks();

        // 動的二分探索EPS実行
        performDynamicBinarySearchEPS(client, eps);

            // フロー分解アルゴリズムによる経路割り当て準備
            // （tubeFlow配列は不要、explorePath/explorePathGreedyがlink[i][j].getQ_tubeFlow()を直接参照）
            LogManager.getInstance().log("BisectionalPGEPS: Flow decomposition will use link Q_tubeFlow directly");

            // EPSフロー値から残りUAV数を計算
            int requiredUAVs = (int) requestedFlow;
            double epsAssignedFlow = currentFlow;
            int remainingUAVs = requiredUAVs - (int)epsAssignedFlow;

            LogManager.getInstance().log("BisectionalPGEPS: EPS assigned flow " + epsAssignedFlow + " out of " + requiredUAVs + " required UAVs");

            // 1/4最低保証チェック: EPS割当が要求の1/4(25%)未満の場合はリトライ
            int minRequired = (int) Math.ceil(requiredUAVs * MIN_ASSIGNMENT_RATIO);
            if (epsAssignedFlow < minRequired) {
                LogManager.getInstance().log("BisectionalPGEPS: [MinAssignment] EPS assigned " + (int)epsAssignedFlow +
                    " UAVs, but minimum required is " + minRequired + " (1/4 of " + requiredUAVs + "). Triggering retry.");
                throw new SolverFailedException(currentClientId, 0, "BisectionalPGEPS-MinAssignment");
            }

            // Phase 4: 事業者の時間計測開始（経路割り当て開始時点）
            ClientTimeManager.getInstance().startClientTime(client.getId(), requiredUAVs);

            // Phase 4: 残りUAVがある場合は経路待ちキューに登録
            if (remainingUAVs > 0) {
                LogManager.getInstance().log("BisectionalPGEPS Phase 4: EPS割当=" + (int)epsAssignedFlow +
                    ", 経路待ち登録=" + remainingUAVs + " (要求UAV数=" + requiredUAVs + ")");
                registerRemainingUAVsToPathWaiting(client, (int)epsAssignedFlow, remainingUAVs, sourceNode, destNode);
            } else {
                LogManager.getInstance().log("BisectionalPGEPS Phase 4: 全UAV(" + requiredUAVs + "機)をEPS割当完了、経路待ちなし");
            }

            // EPS割り当て分のUAVのみ飛行開始
            int epsUAVCount = (int) epsAssignedFlow;
            LogManager.getInstance().log("BisectionalPGEPS: Starting UAV flight assignment for " + epsUAVCount + " EPS UAVs (out of " + requiredUAVs + " total)");
            runUAVFlow(sourceNode, destNode, epsUAVCount, client);
            
        } catch (Exception e) {
            LogManager.getInstance().error("Error in binary search EPS process: ", e);
            throw e;
        } finally {
            // 切断したリンクを復元（例外発生時も確実に復元）
            restoreDisconnectedLinks();
        }
    }

    /**
     * Phase 4: 残りUAVを経路待ちキューに登録する（サブクラスでオーバーライド可能）
     *
     * デフォルト実装は Redis の PathWaitingManager を使用する。
     * メモリモードではサブクラスで MemoryPathWaitingManager に差し替える。
     *
     * @param client        クライアント
     * @param epsAssigned   EPS で割り当てた UAV 数（次の UAV のインデックス起点）
     * @param remainingCount 経路待ち登録する UAV 数
     * @param srcNode       出発ノード
     * @param dstNode       目的ノード
     */
    protected void registerRemainingUAVsToPathWaiting(
            Client client, int epsAssigned, int remainingCount, int srcNode, int dstNode) {
        PathWaitingManager pm = new PathWaitingManager();
        for (int i = 0; i < remainingCount; i++) {
            int uavIndex = epsAssigned + i;
            double uavSpeed = client.getFlow().getUav(uavIndex).getSpeed();
            int uavId = client.getFlow().getUav(uavIndex).getId();
            UAVJob waitingJob = new UAVJob(
                uavId,
                client.getId(),
                null,  // path = null（経路待ち状態）
                uavSpeed,
                System.currentTimeMillis(),
                srcNode,
                dstNode
            );
            waitingJob.setSessionId(BoundaryController.getCurrentSessionId());
            waitingJob.startPathWaiting();
            pm.enqueue(client.getId(), waitingJob);
        }
        LogManager.getInstance().log(
            "BisectionalPGEPS Phase 4: " + remainingCount + "機の経路待ち登録完了");
    }

    /**
     * フローテスト結果の状態
     */
    private enum FlowTestStatus {
        STABLE,            // 安定
        UNSTABLE_PRESSURE, // 圧力絶対値による不安定
        SOLVER_ERROR,      // ソルバーエラー
        MAX_ITERATIONS     // 最大イテレーション到達
    }

    /**
     * フローテスト結果
     */
    private static class FlowTestResult {
        FlowTestStatus status;
        double maxSourcePressure;
        int iterations;
        
        FlowTestResult(FlowTestStatus status, double maxSourcePressure, int iterations) {
            this.status = status;
            this.maxSourcePressure = maxSourcePressure;
            this.iterations = iterations;
        }
    }

    /**
     * 指定されたフローでの安定性をテストする
     * @param client クライアント
     * @param testFlow テストするフロー値
     * @param eps イプシロン値
     * @return テスト結果
     */
    private FlowTestResult testFlowStability(Client client, double testFlow, double eps) {
        // EPSの初期化
        initializeEPS();
        
        stableIterationCount = 0;
        previousSourcePressure = 0.0;
        currentFlowBaselinePressure = 0.0;
        currentFlowBaselineCaptured = false;
        
        double maxSourcePressure = 0.0;
        int ct = 0;

        try {
            // テストループ
            while (ct < MAX_ITERATIONS && stableIterationCount < REQUIRED_STABLE_ITERATIONS) {
                // 流入フロー値を設定
                Q_Kirchhoff[sourceNode] = testFlow;
                Q_Kirchhoff[destNode] = testFlow * NEG;
                
                // その他のノードは0に設定
                for (int i = 0; i < node; i++) {
                    pressureCoefficient[i][i] = 0.0;
                    if (i != sourceNode && i != destNode) {
                        Q_Kirchhoff[i] = 0.0;
                    }
                }

                // 圧力勾配の導出
                for (int i = 0; i < node; i++) {
                    for (int j = 0; j < node; j++) {
                        if (link[i][j].getL_tubeLength() != INF) {
                            if (i != j) {
                                pressureCoefficient[i][j] = link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength() * NEG;
                            }
                        }
                    }
                }

                int k = 0;
                for (int i = 0; i < node; i++) {
                    for (int j = 0; j < node; j++) {
                        if (link[i][j].getL_tubeLength() != INF) {
                            pressureCoefficient[k][k] += link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength();
                        }
                    }
                    k++;
                }

                // 線形方程式を解く
                int testIter = 10;
                if (solvePressureEquation(pressureCoefficient, Q_Kirchhoff, P_tubePressure, node, testIter, eps) == -1) {
                    return new FlowTestResult(FlowTestStatus.SOLVER_ERROR, maxSourcePressure, ct);
                }

                // 流量の計算
                for (int i = 0; i < node; i++) {
                    for (int j = 0; j < node; j++) {
                        if (link[i][j].getL_tubeLength() != INF) {
                            link[i][j].setQ_tubeFlow((link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength()) * (P_tubePressure[i] - P_tubePressure[j]));
                        }
                    }
                }

                // シグモイド関数
                for (int i = 0; i < node; i++) {
                    for (int j = 0; j < node; j++) {
                        if (link[i][j].getL_tubeLength() != INF) {
                            Q_tubeFlow_sigmoidOutput[i][j] = Math.pow(Math.abs(link[i][j].getQ_tubeFlow()), GAMMA) / (1 + Math.pow(Math.abs(link[i][j].getQ_tubeFlow()), GAMMA));
                        }
                    }
                }

                // チューブ厚の更新
                updateTubeThickness(ct);

                // 現在フロー基準圧力をキャプチャ（初回のみ）
                if (!currentFlowBaselineCaptured && sourceNode >= 0) {
                    currentFlowBaselinePressure = Math.abs(P_tubePressure[sourceNode]);
                    currentFlowBaselineCaptured = true;
                }

                // ソース圧力10%減少検知とフロー増加トリガー（二分探索方式）
                boolean shouldIncrease = checkShouldIncreaseFlow();
                if (shouldIncrease) {
                    // 二分探索テスト中は実際のフロー変更は行わず、ログ出力のみ
                    LogManager.getInstance().log("BisectionalPGEPS: Flow increase trigger detected at iteration " + (ct + 1) + " for flow " + testFlow + " (test only - no action taken during binary search)");
                }

                // ソース圧力チェック
                double currentSourcePressure = Math.abs(P_tubePressure[sourceNode]);
                maxSourcePressure = Math.max(maxSourcePressure, currentSourcePressure);

                // 圧力絶対値チェック（Trigger 3）
                if (currentSourcePressure >= activePLimit) {
                    return new FlowTestResult(FlowTestStatus.UNSTABLE_PRESSURE, maxSourcePressure, ct);
                }

                // 圧力増加率チェック（Trigger 1 / modeN_3のみ）
                if (usesTrigger1() && currentFlowBaselineCaptured && currentFlowBaselinePressure > 0.0) {
                    double increaseThreshold = currentFlowBaselinePressure * (1.0 + SOURCE_PRESSURE_CHANGE_THRESHOLD);
                    if (currentSourcePressure >= increaseThreshold) {
                        return new FlowTestResult(FlowTestStatus.UNSTABLE_PRESSURE, maxSourcePressure, ct);
                    }
                }

                // イテレーション毎の結果を記録（テスト段階でも記録）
                try {
                    ResultOutputManager.outputIterationSourcePressure(ct + 1, currentSourcePressure, serverController.getRunCounter());
                    ResultOutputManager.outputIterationFlow(ct + 1, testFlow, serverController.getRunCounter());
                } catch (IOException e) {
                    LogManager.getInstance().error("Failed to output test iteration data", e);
                }

                // 安定カウンター増加
                stableIterationCount++;

                // 前回のソース圧力を更新
                previousSourcePressure = currentSourcePressure;
                ct++;
            }

            // 最大イテレーション到達
            if (ct >= MAX_ITERATIONS) {
                return new FlowTestResult(FlowTestStatus.MAX_ITERATIONS, maxSourcePressure, ct);
            }

            // 安定収束
            return new FlowTestResult(FlowTestStatus.STABLE, maxSourcePressure, ct);

        } catch (Exception e) {
            LogManager.getInstance().error("Error during flow stability test for flow " + testFlow, e);
            return new FlowTestResult(FlowTestStatus.SOLVER_ERROR, maxSourcePressure, ct);
        }
    }

    /**
     * ソース圧力10%減少検知とフロー増加判定（引数なしバージョン）
     * @return フロー増加すべき場合true
     */
    private boolean checkShouldIncreaseFlow() {
        // 現在フロー基準圧力がキャプチャされていない場合は何もしない
        if (!currentFlowBaselineCaptured || sourceNode < 0 || currentFlowBaselinePressure == 0.0) {
            return false;
        }

        double currentPressure = Math.abs(P_tubePressure[sourceNode]);
        
        // 現在の要求フローでの基準圧力から指定%減少したかをチェック
        double reductionThreshold = currentFlowBaselinePressure * (1.0 - SOURCE_PRESSURE_REDUCTION_THRESHOLD); // 閾値計算
        
        return currentPressure < reductionThreshold;
    }

    /**
     * ソース圧力10%減少検知（フロー増加判定）
     * 最終EPS実行でのみ使用。現在のフローの1回目圧力と比較して10%減少している場合にフロー増加を判定
     * @param currentPressure 現在の圧力値
     * @param baselinePressure 基準圧力値（現在フローの1回目の圧力）
     * @return フロー増加すべき場合true
     */
    private boolean checkShouldIncreaseFlow(double currentPressure, double baselinePressure) {
        if (baselinePressure <= 0.0 || sourceNode < 0) {
            return false;
        }

        double reductionThreshold = baselinePressure * (1.0 - SOURCE_PRESSURE_REDUCTION_THRESHOLD);
        return currentPressure < reductionThreshold;
    }

    /**
     * 最適フローでの最終EPS実行
     * @param client クライアント
     * @param finalFlow 最終フロー値
     * @param eps イプシロン値
     */
    private void performFinalEPSRun(Client client, double finalFlow, double eps) throws IOException {
        LogManager.getInstance().log("BisectionalPGEPS: Performing final EPS run with flow " + finalFlow);
        
        // EPSの初期化
        initializeEPS();
        
        // *** 初期セーブポイントの作成 ***
        epsSavePoint.saveEPSState(link, pressureCoefficient, P_tubePressure, Q_Kirchhoff, D_tubeThickness_deltaT, Q_tubeFlow_sigmoidOutput);
        LogManager.getInstance().log("BisectionalPGEPS: Initial EPS savepoint created. " + epsSavePoint.getStatistics());
        
        stableIterationCount = 0;
        previousSourcePressure = 0.0;
        currentFlowBaselinePressure = 0.0;
        currentFlowBaselineCaptured = false;
        int ct = 0;

        // 最終EPSループ
        while (ct < MAX_ITERATIONS && stableIterationCount < REQUIRED_STABLE_ITERATIONS) {
            // 流入フロー値を設定
            Q_Kirchhoff[sourceNode] = finalFlow;
            Q_Kirchhoff[destNode] = finalFlow * NEG;
            
            // その他のノードは0に設定
            for (int i = 0; i < node; i++) {
                pressureCoefficient[i][i] = 0.0;
                if (i != sourceNode && i != destNode) {
                    Q_Kirchhoff[i] = 0.0;
                }
            }

            // 圧力勾配の導出
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) {
                        if (i != j) {
                            pressureCoefficient[i][j] = link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength() * NEG;
                        }
                    }
                }
            }

            int k = 0;
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) {
                        pressureCoefficient[k][k] += link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength();
                    }
                }
                k++;
            }

            // 線形方程式を解く
            int testIter = 10;

            if (solvePressureEquation(pressureCoefficient, Q_Kirchhoff, P_tubePressure, node, testIter, eps) == -1) {
                // Phase 5: ソルバー失敗時は例外をスローして再試行管理に委ねる
                LogManager.getInstance().log("BisectionalPGEPS: [Solver Failure] Final EPS pressure equation solving failed at iteration " + (ct + 1) +
                                           " with flow " + finalFlow + ". Throwing SolverFailedException for retry management.");
                throw new SolverFailedException(currentClientId, ct + 1, "BisectionalPGEPS");
            }

            // 流量の計算
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) {
                        link[i][j].setQ_tubeFlow((link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength()) * (P_tubePressure[i] - P_tubePressure[j]));
                    }
                }
            }

            // シグモイド関数
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) {
                        Q_tubeFlow_sigmoidOutput[i][j] = Math.pow(Math.abs(link[i][j].getQ_tubeFlow()), GAMMA) / (1 + Math.pow(Math.abs(link[i][j].getQ_tubeFlow()), GAMMA));
                    }
                }
            }

            // チューブ厚の更新
            updateTubeThickness(ct);

            // 現在フロー基準圧力をキャプチャ（初回のみ）
            if (!currentFlowBaselineCaptured && sourceNode >= 0) {
                currentFlowBaselinePressure = Math.abs(P_tubePressure[sourceNode]);
                currentFlowBaselineCaptured = true;
                LogManager.getInstance().log("BisectionalPGEPS: Final EPS baseline pressure captured: " + String.format("%.4f", currentFlowBaselinePressure));
            }

            // ソース圧力10%減少検知とフロー増加ロジック（二分探索方式）
            boolean shouldIncrease = checkShouldIncreaseFlow();
            if (shouldIncrease && finalFlow < requestedFlow) {
                // 下限を更新して二分探索方式でフロー増加
                lowerBound = finalFlow;
                double newFlow = Math.ceil((lowerBound + Math.min(upperBound, requestedFlow)) / 2.0);
                
                // 要求フローを超えないように制限
                if (newFlow > requestedFlow) {
                    newFlow = requestedFlow;
                }
                
                double increaseAmount = newFlow - finalFlow;
                if (increaseAmount > 0) {
                    finalFlow = newFlow;
                    stableIterationCount = 0; // リセット
                    
                    // 基準圧力をリセット（新しいフロー値での基準を次回キャプチャ）
                    currentFlowBaselineCaptured = false;
                    
                    LogManager.getInstance().log("BisectionalPGEPS: Source pressure 10% reduction detected in final EPS " +
                                              " (baselinePressure=" + String.format("%.4f", currentFlowBaselinePressure) +
                                              ", currentPressure=" + String.format("%.4f", Math.abs(P_tubePressure[sourceNode])) +
                                              "). Binary search flow increase: " + (finalFlow - increaseAmount) + " → " + finalFlow +
                                              " (new bounds: " + lowerBound + " - " + Math.min(upperBound, requestedFlow) + ")");
                }
            }

            // ソース圧力チェック（最終実行でも安定性をチェック）
            double currentSourcePressure = Math.abs(P_tubePressure[sourceNode]);

            // 圧力絶対値チェック（Trigger 3）
            if (currentSourcePressure >= activePLimit) {
                LogManager.getInstance().error("BisectionalPGEPS: CRITICAL ERROR - Final EPS pressure emergency detected at iteration " + (ct + 1) + " (pressure: " + currentSourcePressure + "). System termination required.");
                throw new RuntimeException("System termination: Pressure emergency in final EPS run");
            }

            // 圧力増加率チェック（Trigger 1 / modeN_3のみ）
            if (usesTrigger1() && currentFlowBaselineCaptured && currentFlowBaselinePressure > 0.0) {
                double increaseThreshold = currentFlowBaselinePressure * (1.0 + SOURCE_PRESSURE_CHANGE_THRESHOLD);
                if (currentSourcePressure >= increaseThreshold) {
                    LogManager.getInstance().log("BisectionalPGEPS: [Trigger 1 - Final EPS] Pressure increase rate exceeded at iteration " + (ct + 1) +
                                               " (baseline=" + String.format("%.4f", currentFlowBaselinePressure) +
                                               ", current=" + String.format("%.4f", currentSourcePressure) + "). Stopping final EPS.");
                    break;
                }
            }

            // 負の圧力チェック - Phase 5: SolverFailedExceptionで再試行対象に
            if (P_tubePressure[sourceNode] < 0.0) {
                LogManager.getInstance().log("BisectionalPGEPS: [Negative Pressure] Detected in final EPS at iteration " + (ct + 1) + " (pressure: " + P_tubePressure[sourceNode] + "). Throwing SolverFailedException for retry management.");
                throw new SolverFailedException(currentClientId, ct + 1, "BisectionalPGEPS-NegativePressure");
            }

            // 安定カウンター増加
            stableIterationCount++;

            // 前回のソース圧力を更新
            previousSourcePressure = currentSourcePressure;

            // イテレーション毎の結果を記録
            try {
                if (sourceNode >= 0) {
                    ResultOutputManager.outputIterationSourcePressure(ct + 1, currentSourcePressure, serverController.getRunCounter());
                }
                ResultOutputManager.outputIterationFlow(ct + 1, finalFlow, serverController.getRunCounter());
            } catch (IOException e) {
                LogManager.getInstance().error("Failed to output iteration data", e);
            }

            // 結果のプロット
            if ((ct + 1) % PLOT == 0) {
                LogManager.getInstance().log("Final EPS Iteration: " + (ct + 1) + " with flow " + finalFlow +
                                          " (stable count: " + stableIterationCount + "/" + REQUIRED_STABLE_ITERATIONS + ")");
                ResultOutputManager.outputToPajek(client, eps, requestedFlow, ct, link, beaconCluster, node, serverController.getRunCounter());
                ResultOutputManager.outputToExcel(client, ct, link, node, serverController.getRunCounter(), finalFlow);
                ResultOutputManager.outputToTxt(client, ct, link, node, serverController.getRunCounter(), pressureCoefficient, P_tubePressure, finalFlow);
            }

            ct++;
        }

        // フロー分解アルゴリズムでは整数丸め込みは不要
        // （explorePath/explorePathGreedyがリアルタイムでフローを減算する）
        LogManager.getInstance().log("BisectionalPGEPS: Final EPS completed with flow " + finalFlow + " after " + ct + " iterations");

        // 最終結果を出力
        ResultOutputManager.outputToPajek(client, eps, requestedFlow, ct, link, beaconCluster, node, serverController.getRunCounter());
        ResultOutputManager.outputToExcel(client, ct, link, node, serverController.getRunCounter(), finalFlow);
        ResultOutputManager.outputToTxt(client, ct, link, node, serverController.getRunCounter(), pressureCoefficient, P_tubePressure, finalFlow);
    }

    /**
     * 前回チューブ厚配列を初期化する
     */
    private void initializePreviousThickness() {
        int totalLinks = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    totalLinks++;
                }
            }
        }
        previousThickness = new double[totalLinks];
        
        // 初期値を設定
        int index = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    previousThickness[index++] = link[i][j].getD_tubeThickness();
                }
            }
        }
        LogManager.getInstance().log("BisectionalPGEPS: Initialized previous thickness array with " + totalLinks + " links");
    }

    /**
     * EPSを初期化する（一番初めの状態に戻す）
     */
    private void initializeEPS() {
        // リンクのチューブ厚を初期値にリセット
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    link[i][j].setD_tubeThickness(INIT_THICKNESS);
                    link[i][j].setQ_tubeFlow(0.0);
                }
            }
        }
        
        // 圧力係数配列をリセット
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                pressureCoefficient[i][j] = 0.0;
            }
        }
        
        // その他の配列もリセット
        for (int i = 0; i < node; i++) {
            P_tubePressure[i] = 0.0;
            Q_Kirchhoff[i] = 0.0;
            for (int j = 0; j < node; j++) {
                D_tubeThickness_deltaT[i][j] = 0.0;
                Q_tubeFlow_sigmoidOutput[i][j] = 0.0;
            }
        }
    }

    /**
     * EPS開始時のリンクデータを詳細ログ出力する（デバッグ用）
     * メモリモードとRedisモードの差分を特定するため
     * @param client クライアント
     */
    private void logEPSInputData(Client client) {
        LogManager log = LogManager.getInstance();

        log.log("=== DEBUG: EPS Input Data for Client " + client.getId() + " ===");
        log.log("sourceNode: " + sourceNode + ", destNode: " + destNode);
        log.log("node count: " + node);

        // リンク統計
        int validLinkCount = 0;
        int zeroCapacityCount = 0;
        int negativeCapacityCount = 0;
        double minCapacity = Double.MAX_VALUE;
        double maxCapacity = Double.MIN_VALUE;
        double sumCapacity = 0.0;

        StringBuilder sampleLinks = new StringBuilder();
        int sampleCount = 0;

        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    validLinkCount++;
                    double cap = link[i][j].getCapacity();
                    double initCap = link[i][j].getInitCapacity();
                    double thickness = link[i][j].getD_tubeThickness();
                    double length = link[i][j].getL_tubeLength();

                    sumCapacity += cap;
                    if (cap < minCapacity) minCapacity = cap;
                    if (cap > maxCapacity) maxCapacity = cap;
                    if (cap == 0.0) zeroCapacityCount++;
                    if (cap < 0.0) negativeCapacityCount++;

                    // サンプルリンクを記録（最初の10個）
                    if (sampleCount < 10) {
                        sampleLinks.append(String.format("  Link[%d][%d]: cap=%.2f, initCap=%.2f, thickness=%.4f, length=%.2f%n",
                                i, j, cap, initCap, thickness, length));
                        sampleCount++;
                    }
                }
            }
        }

        log.log("Valid links: " + validLinkCount);
        log.log("Zero capacity links: " + zeroCapacityCount);
        log.log("Negative capacity links: " + negativeCapacityCount);
        if (validLinkCount > 0) {
            log.log(String.format("Capacity stats: min=%.2f, max=%.2f, avg=%.2f",
                    minCapacity, maxCapacity, sumCapacity / validLinkCount));
        }
        log.log("Sample links:\n" + sampleLinks.toString());
        log.log("=== END DEBUG ===");
    }

    /**
     * PSによる追加計算を実行する
     * @param client クライアント
     * @param remainingUAVs 残りUAV数
     * @param sourceNode ソースノード
     * @param destNode デスティネーションノード
     * @param eps イプシロン
     * @throws IOException 入出力例外
     */
    private void performPSComputation(Client client, int remainingUAVs, int sourceNode, int destNode, double eps) throws IOException {
        // PS用一時結果配列
        double[][] psTempResults = new double[node][node];
        
        // PSのための初期化（EPSの結果は保持したまま、PSは独立して計算）
        double[] ps_Q_Kirchhoff = new double[node];
        double[] ps_P_tubePressure = new double[node];
        double[][] ps_pressureCoefficient = new double[node][node];
        double[][] ps_Q_tubeFlow_sigmoidOutput = new double[node][node];
        double[][] ps_D_tubeThickness_deltaT = new double[node][node];
        
        // PSのリンク情報を初期状態で初期化
        Link[][] psLink = new Link[node][node];
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    psLink[i][j] = new Link();
                    psLink[i][j].setDistance(link[i][j].getDistance());
                    psLink[i][j].setCapacity(link[i][j].getCapacity());
                    psLink[i][j].setL_tubeLength(link[i][j].getL_tubeLength());
                    psLink[i][j].setD_tubeThickness(INIT_THICKNESS); // 初期値0.5から開始
                    psLink[i][j].setQ_tubeFlow(0.0);
                } else {
                    psLink[i][j] = link[i][j]; // INFの場合はそのまま
                }
            }
        }
        
        LogManager.getInstance().log("BisectionalPGEPS: PS starting with " + remainingUAVs + " UAVs flow");
        
        // PS用500回イテレーション
        for (int psIter = 0; psIter < 500; psIter++) {
            // フロー値設定
            ps_Q_Kirchhoff[sourceNode] = remainingUAVs;
            ps_Q_Kirchhoff[destNode] = remainingUAVs * NEG;
            
            for (int i = 0; i < node; i++) {
                ps_pressureCoefficient[i][i] = 0.0;
                if (i != sourceNode && i != destNode) {
                    ps_Q_Kirchhoff[i] = 0.0;
                }
            }

            // 圧力勾配の導出
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (psLink[i][j].getL_tubeLength() != INF) {
                        if (i != j) {
                            ps_pressureCoefficient[i][j] = psLink[i][j].getD_tubeThickness() / psLink[i][j].getL_tubeLength() * NEG;
                        }
                    }
                }
            }

            int k = 0;
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (psLink[i][j].getL_tubeLength() != INF) {
                        ps_pressureCoefficient[k][k] = ps_pressureCoefficient[k][k] + psLink[i][j].getD_tubeThickness() / psLink[i][j].getL_tubeLength();
                    }
                }
                k++;
            }

            // 圧力方程式を解く（ICCGソルバーを使用）
            int testIter = 10;
            int result = ICCGSolver.solve(ps_pressureCoefficient, ps_Q_Kirchhoff, ps_P_tubePressure, node, testIter, eps);
            if (result != 1) {
                LogManager.getInstance().log("BisectionalPGEPS: PS pressure equation solving failed at iteration " + (psIter + 1));
                break;
            }

            // 流量の計算
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (psLink[i][j].getL_tubeLength() != INF) {
                        psLink[i][j].setQ_tubeFlow((psLink[i][j].getD_tubeThickness() / psLink[i][j].getL_tubeLength()) * (ps_P_tubePressure[i] - ps_P_tubePressure[j]));
                    }
                }
            }

            // フロー分解アルゴリズムでは整数丸め込みは不要
            // （explorePath/explorePathGreedyがリアルタイムでフローを減算する）

            // シグモイド関数
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (psLink[i][j].getL_tubeLength() != INF) {
                        ps_Q_tubeFlow_sigmoidOutput[i][j] = Math.pow(Math.abs(psLink[i][j].getQ_tubeFlow()), GAMMA) / (1 + Math.pow(Math.abs(psLink[i][j].getQ_tubeFlow()), GAMMA));
                    }
                }
            }

            // PSのチューブ厚更新
            double degeneracyEffect = 0.5;
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (psLink[i][j].getL_tubeLength() != INF) {
                        double deltaThickness = (ps_Q_tubeFlow_sigmoidOutput[i][j] - (degeneracyEffect * psLink[i][j].getD_tubeThickness())) * DELTA_TIME;
                        ps_D_tubeThickness_deltaT[i][j] = deltaThickness;
                        double newThickness = psLink[i][j].getD_tubeThickness() + deltaThickness;
                        psLink[i][j].setD_tubeThickness(newThickness);
                    }
                }
            }
        }

        // PS結果をpsTempResultsに保存
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (psLink[i][j].getL_tubeLength() != INF) {
                    psTempResults[i][j] = psLink[i][j].getQ_tubeFlow();
                }
            }
        }

        // EPSとPSの結果を統合
        LogManager.getInstance().log("BisectionalPGEPS: Integrating EPS and PS results");
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    double epsFlow = link[i][j].getQ_tubeFlow();
                    double psFlow = psTempResults[i][j];
                    link[i][j].setQ_tubeFlow(epsFlow + psFlow);
                    if (psFlow > 0) {
                        LogManager.getInstance().log("Link (" + i + "," + j + "): EPS=" + epsFlow + " + PS=" + psFlow + " = " + (epsFlow + psFlow));
                    }
                }
            }
        }

        // フロー分解アルゴリズムでは整数丸め込みは不要
        // （explorePath/explorePathGreedyがリアルタイムでフローを減算する）

        // 統合後のソース流出合計を検証
        double integratedSourceOutflow = 0.0;
        for (int j = 0; j < node; j++) {
            if (link[sourceNode][j].getL_tubeLength() != INF && link[sourceNode][j].getQ_tubeFlow() > 0) {
                integratedSourceOutflow += link[sourceNode][j].getQ_tubeFlow();
            }
        }
        LogManager.getInstance().log("BisectionalPGEPS: Integrated source outflow sum = " + integratedSourceOutflow + " (expected: " + (int) requestedFlow + ")");
        LogManager.getInstance().log("BisectionalPGEPS: EPS+PS integration completed. Flow decomposition will handle path extraction.");
    }

    /**
     * 動的二分探索EPS実行
     * EPSを継続実行しながら、圧力検知に基づいて二分探索でフロー値を動的に調整
     * @param client クライアント
     * @param eps イプシロン値
     * @throws IOException 入出力例外
     */
    private void performDynamicBinarySearchEPS(Client client, double eps) throws IOException {
        LogManager.getInstance().log("BisectionalPGEPS: Starting dynamic binary search EPS with initial flow " + currentFlow);

        // EPSの初期化
        initializeEPS();

        // === DEBUG: EPS開始時のリンクデータ詳細ログ ===
        logEPSInputData(client);

        // *** 初期セーブポイントの作成 ***
        epsSavePoint.saveEPSState(link, pressureCoefficient, P_tubePressure, Q_Kirchhoff, D_tubeThickness_deltaT, Q_tubeFlow_sigmoidOutput);
        LogManager.getInstance().log("BisectionalPGEPS: Initial EPS savepoint created. " + epsSavePoint.getStatistics());
        
        stableIterationCount = 0;
        previousSourcePressure = 0.0;
        currentFlowBaselinePressure = 0.0;
        currentFlowBaselineCaptured = false;
        binarySearchIteration = 0; // Phase 5: 再試行時にリセット
        shiftDirection = 0; // スタックシフト方向リセット
        int ct = 0;

        // 動的二分探索EPSループ
        while (ct < MAX_ITERATIONS && stableIterationCount < REQUIRED_STABLE_ITERATIONS) {
            // 流入フロー値を設定
            Q_Kirchhoff[sourceNode] = currentFlow;
            Q_Kirchhoff[destNode] = currentFlow * NEG;

            // その他のノードは0に設定
            for (int i = 0; i < node; i++) {
                pressureCoefficient[i][i] = 0.0;
                if (i != sourceNode && i != destNode) {
                    Q_Kirchhoff[i] = 0.0;
                }
            }

            // 圧力勾配の導出
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) {
                        if (i != j) {
                            pressureCoefficient[i][j] = link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength() * NEG;
                        }
                    }
                }
            }

            int k = 0;
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) {
                        pressureCoefficient[k][k] += link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength();
                    }
                }
                k++;
            }

            // 線形方程式を解く
            int testIter = 10;

            if (solvePressureEquation(pressureCoefficient, Q_Kirchhoff, P_tubePressure, node, testIter, eps) == -1) {
                // Phase 5: ソルバー失敗時は例外をスローして再試行管理に委ねる
                LogManager.getInstance().log("BisectionalPGEPS: [Solver Failure] Pressure equation solving failed at iteration " + (ct + 1) +
                                           " with flow " + currentFlow + ". Throwing SolverFailedException for retry management.");
                throw new SolverFailedException(currentClientId, ct + 1, "BisectionalPGEPS");
            }

            // 流量の計算
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) {
                        link[i][j].setQ_tubeFlow((link[i][j].getD_tubeThickness() / link[i][j].getL_tubeLength()) * (P_tubePressure[i] - P_tubePressure[j]));
                    }
                }
            }

            // シグモイド関数
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) {
                        Q_tubeFlow_sigmoidOutput[i][j] = Math.pow(Math.abs(link[i][j].getQ_tubeFlow()), GAMMA) / (1 + Math.pow(Math.abs(link[i][j].getQ_tubeFlow()), GAMMA));
                    }
                }
            }

            // チューブ厚の更新
            updateTubeThickness(ct);

            // 現在フロー基準圧力をキャプチャ（初回のみ）
            if (!currentFlowBaselineCaptured && sourceNode >= 0) {
                currentFlowBaselinePressure = Math.abs(P_tubePressure[sourceNode]);
                currentFlowBaselineCaptured = true;
                LogManager.getInstance().log("BisectionalPGEPS: Baseline pressure captured for flow " + currentFlow + ": " + String.format("%.4f", currentFlowBaselinePressure));
            }

            // ソース圧力チェック（優先度順で実行）
            double currentSourcePressure = Math.abs(P_tubePressure[sourceNode]);
            boolean flowChanged = false;
            
            // デバッグ用ログ出力（常に出力）
            boolean shouldIncrease = checkShouldIncreaseFlow();
            double tenPercentReduction = currentFlowBaselineCaptured ? currentFlowBaselinePressure * 0.9 : 0.0;
            
            // 100回に1回だけ詳細ログ出力（ログ量を抑制）
            if ((ct + 1) % 100 == 0 || ct < 10) {
                LogManager.getInstance().log("BisectionalPGEPS: [Debug] Flow increase check at iteration " + (ct + 1) + 
                                           " - baselineCaptured=" + currentFlowBaselineCaptured +
                                           ", baselinePressure=" + String.format("%.4f", currentFlowBaselinePressure) +
                                           ", currentPressure=" + String.format("%.4f", currentSourcePressure) +
                                           ", threshold=" + String.format("%.4f", tenPercentReduction) +
                                           ", shouldIncrease=" + shouldIncrease +
                                           ", currentFlow=" + currentFlow + 
                                           ", requestedFlow=" + requestedFlow +
                                           ", sourceNode=" + sourceNode);
            }
            
            // 安定化フェーズ中の場合は異常検知をスキップ
            if (!inStabilizationPhase) {
                // 【優先度1】Trigger 3: 圧力絶対値チェック（activePLimit以上でフロー減少）
                if (currentSourcePressure >= activePLimit) {
                    LogManager.getInstance().log("BisectionalPGEPS: [Priority 1 / Trigger 3] Pressure emergency detected at iteration " + (ct + 1) +
                                               " (pressure: " + String.format("%.2f", currentSourcePressure) + " >= " + activePLimit + "). Applying binary search flow reduction.");
                    
                    // 上限を現在のフロー値に更新
                    upperBound = currentFlow;
                    double newFlow = Math.ceil((lowerBound + upperBound) / 2.0);
                    
                    if (newFlow != currentFlow && newFlow > lowerBound) {
                        LogManager.getInstance().log("BisectionalPGEPS: Binary search flow reduction: " + currentFlow + " → " + newFlow +
                                                   " (new bounds: " + lowerBound + " - " + upperBound + ")");
                        
                        currentFlow = newFlow;
                        shiftDirection = 0; // 通常削減: シフトモードリセット
                        stableIterationCount = 0; // リセット
                        currentFlowBaselineCaptured = false; // 基準圧力をリセット
                        flowChanged = true;

                        // *** フロー減少後のEPS状態復元 ***
                        if (epsSavePoint.isAvailable()) {
                            epsSavePoint.restoreEPSState(link, pressureCoefficient, P_tubePressure, Q_Kirchhoff, D_tubeThickness_deltaT, Q_tubeFlow_sigmoidOutput);
                            LogManager.getInstance().log("BisectionalPGEPS: EPS state restored from savepoint after flow reduction due to pressure emergency. " + epsSavePoint.getStatistics());
                        } else {
                            // セーブポイントがない場合（流入フロー初回等）は初期状態に戻す
                            LogManager.getInstance().log("BisectionalPGEPS: No savepoint available, restoring EPS to initial state after flow reduction");
                            initializeEPS();
                        }

                        // フロー減少後に安定化フェーズを開始
                        inStabilizationPhase = true;
                        stabilizationIterationCount = 0;
                        LogManager.getInstance().log("BisectionalPGEPS: Entering stabilization phase for " + STABILIZATION_PHASE_ITERATIONS + " iterations");
                    } else {
                        // スタック検知: lowerBound == upperBound == currentFlow → 下方シフトで脱出
                        boolean isStuckShifted = false;
                        if (newFlow == currentFlow) {
                            if (shiftDirection == 1) {
                                // 上方シフト直後に削減スタック → 両方向失敗
                                LogManager.getInstance().log("BisectionalPGEPS: [Stuck/P1] DOWN shift blocked (shiftDirection=UP). Both directions failed. Throwing SolverFailedException.");
                                throw new SolverFailedException(currentClientId, ct + 1, "BisectionalPGEPS-StuckBothDirections");
                            } else if (currentFlow <= 1) {
                                LogManager.getInstance().log("BisectionalPGEPS: [Stuck/P1] Cannot reduce flow below 1 (currentFlow=" + currentFlow + "). Throwing SolverFailedException.");
                                throw new SolverFailedException(currentClientId, ct + 1, "BisectionalPGEPS-StuckMinFlow");
                            } else {
                                double oldFlow = currentFlow;
                                lowerBound -= 1;
                                upperBound -= 1;
                                currentFlow -= 1;
                                shiftDirection = -1;
                                isStuckShifted = true;
                                LogManager.getInstance().log("BisectionalPGEPS: [Stuck/P1 / DOWN Shift] Shifted all bounds -1: " + oldFlow + " → " + currentFlow +
                                                           " (lower=" + lowerBound + ", upper=" + upperBound + ", shiftDirection=-1)");
                            }
                        }
                        // EPS処理: スタックシフト時はセーブポイントを破棄し初期状態にリセット、それ以外はセーブポイント復元
                        if (isStuckShifted) {
                            initializeEPS();
                            epsSavePoint.saveEPSState(link, pressureCoefficient, P_tubePressure, Q_Kirchhoff, D_tubeThickness_deltaT, Q_tubeFlow_sigmoidOutput);
                            LogManager.getInstance().log("BisectionalPGEPS: [Stuck/P1] EPS savepoint discarded and reset to initial state. " + epsSavePoint.getStatistics());
                        } else {
                            if (epsSavePoint.isAvailable()) {
                                epsSavePoint.restoreEPSState(link, pressureCoefficient, P_tubePressure, Q_Kirchhoff, D_tubeThickness_deltaT, Q_tubeFlow_sigmoidOutput);
                                LogManager.getInstance().log("BisectionalPGEPS: EPS state restored from savepoint (no flow change required). " + epsSavePoint.getStatistics());
                            } else {
                                LogManager.getInstance().log("BisectionalPGEPS: No savepoint available, restoring EPS to initial state (no flow change required)");
                                initializeEPS();
                            }
                        }

                        // EPS処理後は安定化フェーズに入る
                        LogManager.getInstance().log("BisectionalPGEPS: EPS state updated. Entering stabilization phase for " + STABILIZATION_PHASE_ITERATIONS + " iterations");
                        inStabilizationPhase = true;
                        stabilizationIterationCount = 0;
                        stableIterationCount = 0; // リセット
                        currentFlowBaselineCaptured = false; // 基準圧力をリセット
                        flowChanged = true; // 状態が変化したことを示す
                    }
                }
                // 【優先度2】Trigger 1: 圧力増加率チェック（modeN_3のみ）
                if (!flowChanged
                        && usesTrigger1()
                        && currentFlowBaselineCaptured && currentFlowBaselinePressure > 0.0) {
                    double increaseThreshold = currentFlowBaselinePressure * (1.0 + SOURCE_PRESSURE_CHANGE_THRESHOLD);
                    if (currentSourcePressure >= increaseThreshold) {
                        double increaseRate = (currentSourcePressure - currentFlowBaselinePressure) / currentFlowBaselinePressure;
                        LogManager.getInstance().log("BisectionalPGEPS: [Priority 2 / Trigger 1] Pressure increase rate exceeded at iteration " + (ct + 1) +
                                                   " (baseline=" + String.format("%.4f", currentFlowBaselinePressure) +
                                                   ", current=" + String.format("%.4f", currentSourcePressure) +
                                                   ", rate=" + String.format("%.2f%%", increaseRate * 100) + "). Applying binary search flow reduction.");

                        upperBound = currentFlow;
                        double newFlow = Math.ceil((lowerBound + upperBound) / 2.0);

                        if (newFlow != currentFlow && newFlow > lowerBound) {
                            LogManager.getInstance().log("BisectionalPGEPS: [Trigger 1] Binary search flow reduction: " + currentFlow + " → " + newFlow +
                                                       " (new bounds: " + lowerBound + " - " + upperBound + ")");
                            currentFlow = newFlow;
                            shiftDirection = 0; // 通常削減: シフトモードリセット
                            stableIterationCount = 0;
                            currentFlowBaselineCaptured = false;
                            flowChanged = true;
                            inStabilizationPhase = true;
                            stabilizationIterationCount = 0;
                            LogManager.getInstance().log("BisectionalPGEPS: Entering stabilization phase after Trigger 1 flow reduction.");
                        } else if (newFlow == currentFlow) {
                            // スタック検知: lowerBound == upperBound == currentFlow → 下方シフトで脱出
                            if (shiftDirection == 1) {
                                LogManager.getInstance().log("BisectionalPGEPS: [Stuck/Trigger1] DOWN shift blocked (shiftDirection=UP). Both directions failed. Throwing SolverFailedException.");
                                throw new SolverFailedException(currentClientId, ct + 1, "BisectionalPGEPS-StuckBothDirections");
                            } else if (currentFlow <= 1) {
                                LogManager.getInstance().log("BisectionalPGEPS: [Stuck/Trigger1] Cannot reduce flow below 1 (currentFlow=" + currentFlow + "). Throwing SolverFailedException.");
                                throw new SolverFailedException(currentClientId, ct + 1, "BisectionalPGEPS-StuckMinFlow");
                            } else {
                                double oldFlow = currentFlow;
                                lowerBound -= 1;
                                upperBound -= 1;
                                currentFlow -= 1;
                                shiftDirection = -1;
                                stableIterationCount = 0;
                                currentFlowBaselineCaptured = false;
                                flowChanged = true;
                                inStabilizationPhase = true;
                                stabilizationIterationCount = 0;
                                // スタック時: セーブポイント破棄 → 初期状態にリセット
                                initializeEPS();
                                epsSavePoint.saveEPSState(link, pressureCoefficient, P_tubePressure, Q_Kirchhoff, D_tubeThickness_deltaT, Q_tubeFlow_sigmoidOutput);
                                LogManager.getInstance().log("BisectionalPGEPS: [Stuck/Trigger1 / DOWN Shift] Shifted all bounds -1: " + oldFlow + " → " + currentFlow +
                                                           " (lower=" + lowerBound + ", upper=" + upperBound + ", shiftDirection=-1). EPS reset to initial state. " + epsSavePoint.getStatistics());
                            }
                        }
                    }
                }
            } else {
                // 安定化フェーズ中の処理
                stabilizationIterationCount++;
                LogManager.getInstance().log("BisectionalPGEPS: Stabilization phase iteration " + stabilizationIterationCount + "/" + STABILIZATION_PHASE_ITERATIONS + " (pressure emergency detection only)");

                // 安定化フェーズ中でも圧力絶対値（Trigger 3）の異常検知のみ作動
                if (currentSourcePressure >= activePLimit) {
                    LogManager.getInstance().log("BisectionalPGEPS: [Priority 1 - Stabilization Phase] Pressure emergency detected during stabilization at iteration " + (ct + 1) +
                                               " (pressure: " + String.format("%.2f", currentSourcePressure) + "). Applying binary search flow reduction.");
                    
                    // 上限を現在のフロー値に更新
                    upperBound = currentFlow;
                    double newFlow = Math.ceil((lowerBound + upperBound) / 2.0);
                    
                    if (newFlow != currentFlow && newFlow > lowerBound) {
                        LogManager.getInstance().log("BisectionalPGEPS: Binary search flow reduction during stabilization: " + currentFlow + " → " + newFlow +
                                                   " (new bounds: " + lowerBound + " - " + upperBound + ")");
                        
                        double oldFlow = currentFlow;
                        currentFlow = newFlow;
                        shiftDirection = 0; // 通常削減: シフトモードリセット
                        stableIterationCount = 0; // リセット
                        currentFlowBaselineCaptured = false; // 基準圧力をリセット
                        flowChanged = true;

                        // *** フロー減少後のEPS状態復元 ***
                        if (epsSavePoint.isAvailable()) {
                            epsSavePoint.restoreEPSState(link, pressureCoefficient, P_tubePressure, Q_Kirchhoff, D_tubeThickness_deltaT, Q_tubeFlow_sigmoidOutput);
                            LogManager.getInstance().log("BisectionalPGEPS: EPS state restored from savepoint after flow reduction during stabilization. " + epsSavePoint.getStatistics());
                        } else {
                            LogManager.getInstance().log("BisectionalPGEPS: No savepoint available, restoring EPS to initial state after flow reduction during stabilization");
                            initializeEPS();
                        }

                        // 安定化フェーズをリセット（新しいフロー値で再開始）
                        stabilizationIterationCount = 0;
                        LogManager.getInstance().log("BisectionalPGEPS: Flow reduced from " + oldFlow + " to " + currentFlow + " during stabilization. Restarting stabilization phase for " + STABILIZATION_PHASE_ITERATIONS + " iterations");
                    } else if (newFlow == currentFlow) {
                        // スタック検知: 安定化フェーズ中でも下方シフトで脱出
                        if (shiftDirection == 1) {
                            LogManager.getInstance().log("BisectionalPGEPS: [Stuck/Stab] DOWN shift blocked (shiftDirection=UP). Both directions failed. Throwing SolverFailedException.");
                            throw new SolverFailedException(currentClientId, ct + 1, "BisectionalPGEPS-StuckBothDirections");
                        } else if (currentFlow <= 1) {
                            LogManager.getInstance().log("BisectionalPGEPS: [Stuck/Stab] Cannot reduce flow below 1 (currentFlow=" + currentFlow + "). Throwing SolverFailedException.");
                            throw new SolverFailedException(currentClientId, ct + 1, "BisectionalPGEPS-StuckMinFlow");
                        } else {
                            double oldFlow = currentFlow;
                            lowerBound -= 1;
                            upperBound -= 1;
                            currentFlow -= 1;
                            shiftDirection = -1;
                            stableIterationCount = 0;
                            currentFlowBaselineCaptured = false;
                            flowChanged = true;
                            // スタック時: セーブポイント破棄 → 初期状態にリセット
                            initializeEPS();
                            epsSavePoint.saveEPSState(link, pressureCoefficient, P_tubePressure, Q_Kirchhoff, D_tubeThickness_deltaT, Q_tubeFlow_sigmoidOutput);
                            stabilizationIterationCount = 0;
                            LogManager.getInstance().log("BisectionalPGEPS: [Stuck/Stab / DOWN Shift] Shifted all bounds -1: " + oldFlow + " → " + currentFlow +
                                                       " (lower=" + lowerBound + ", upper=" + upperBound + ", shiftDirection=-1). EPS reset to initial state. " + epsSavePoint.getStatistics());
                        }
                    }
                } else {
                    // 安定化フェーズ完了チェック
                    if (stabilizationIterationCount >= STABILIZATION_PHASE_ITERATIONS) {
                        inStabilizationPhase = false;
                        stabilizationIterationCount = 0;
                        shiftDirection = 0; // 安定化完了: シフトモードリセット
                        LogManager.getInstance().log("BisectionalPGEPS: Stabilization phase completed. Resuming full anomaly detection.");
                        
                        // 安定化フェーズ後の安全性チェック
                        if (currentSourcePressure >= activePLimit) {
                            LogManager.getInstance().error("BisectionalPGEPS: CRITICAL ERROR - Pressure still >= " + activePLimit + " after stabilization phase (pressure: " + String.format("%.2f", currentSourcePressure) + "). System termination required.");
                            throw new RuntimeException("System termination: Pressure emergency persists after stabilization phase");
                        }
                        
                        // Phase 5: 負の圧力検出 → SolverFailedExceptionで再試行対象に
                        if (P_tubePressure[sourceNode] < 0.0) {
                            LogManager.getInstance().log("BisectionalPGEPS: [Negative Pressure] Detected after stabilization phase (pressure: " + P_tubePressure[sourceNode] + "). Throwing SolverFailedException for retry management.");
                            throw new SolverFailedException(currentClientId, ct + 1, "BisectionalPGEPS-NegativePressure");
                        }
                    }
                }
            }
            
            // 【優先度3】フロー増加チェック - 安定化フェーズ中はスキップ
            // lowerBoundFired : Mode 3_x → 圧力が p_limit_low 以下（EPS初期化直後の誤発火を baselineCaptured でガード）
            // trigger2Fired   : modeN_2 / modeN_3 → 圧力が baseline から -θ 以上低下
            // Mode 1_1 / 2_1  : どちらも false → フロー増加トリガーなし（Trigger 3 によるフロー削減のみ）
            boolean lowerBoundFired = usesLowerBound()
                    && currentFlowBaselineCaptured
                    && (currentSourcePressure <= activePLimitLow);
            boolean trigger2Fired = usesTrigger2() && shouldIncrease;
            boolean shouldTriggerIncrease = lowerBoundFired || trigger2Fired;

            if (usesLowerBound() && ((ct + 1) % 100 == 0 || ct < 10)) {
                LogManager.getInstance().log("BisectionalPGEPS: [" + EXPERIMENT_MODE + " Lower Bound] pressure=" + String.format("%.4f", currentSourcePressure) +
                                           ", activePLimitLow=" + String.format("%.4f", activePLimitLow) +
                                           ", baselineCaptured=" + currentFlowBaselineCaptured +
                                           ", lowerBoundFired=" + lowerBoundFired +
                                           ", trigger2Fired=" + trigger2Fired +
                                           ", shouldTriggerIncrease=" + shouldTriggerIncrease);
            }

            if (!inStabilizationPhase && !flowChanged && shouldTriggerIncrease && currentFlow < requestedFlow) {
                // デバッグログ
                LogManager.getInstance().log("BisectionalPGEPS: [Priority 3 DEBUG] Flow increase conditions - mode=" + EXPERIMENT_MODE +
                                           ", shouldTriggerIncrease=" + shouldTriggerIncrease +
                                           ", currentFlow=" + currentFlow + ", requestedFlow=" + requestedFlow +
                                           ", lowerBound=" + lowerBound + ", upperBound=" + upperBound);
                
                // 下限を現在のフロー値に更新
                lowerBound = currentFlow;
                double newFlow = Math.ceil((lowerBound + Math.min(upperBound, requestedFlow)) / 2.0);
                
                // 要求フローを超えないように制限
                if (newFlow > requestedFlow) {
                    newFlow = requestedFlow;
                }
                
                LogManager.getInstance().log("BisectionalPGEPS: [Priority 3 DEBUG] Calculated newFlow=" + newFlow + 
                                           " (newFlow != currentFlow: " + (newFlow != currentFlow) + 
                                           ", newFlow <= requestedFlow: " + (newFlow <= requestedFlow) + ")");
                
                if (newFlow != currentFlow && newFlow <= requestedFlow) {
                    String increaseReason = lowerBoundFired ? "lower bound p_limit_low" : "Trigger2(-θ)";
                    LogManager.getInstance().log("BisectionalPGEPS: [Priority 3] Flow increase triggered (" + increaseReason + ") at iteration " + (ct + 1) +
                                               " (baselinePressure=" + String.format("%.4f", currentFlowBaselinePressure) +
                                               ", currentPressure=" + String.format("%.4f", currentSourcePressure) +
                                               "). Binary search flow increase: " + currentFlow + " → " + newFlow +
                                               " (new bounds: " + lowerBound + " - " + Math.min(upperBound, requestedFlow) + ")");
                    
                    // *** フロー増加直前のEPSセーブポイント保存 ***
                    epsSavePoint.saveEPSState(link, pressureCoefficient, P_tubePressure, Q_Kirchhoff, D_tubeThickness_deltaT, Q_tubeFlow_sigmoidOutput);
                    LogManager.getInstance().log("BisectionalPGEPS: EPS savepoint created before flow increase from " + currentFlow + " to " + newFlow + ". " + epsSavePoint.getStatistics());
                    
                    currentFlow = newFlow;
                    shiftDirection = 0; // 通常増加: シフトモードリセット
                    stableIterationCount = 0; // リセット
                    currentFlowBaselineCaptured = false; // 基準圧力をリセット
                    flowChanged = true;

                    // フロー増加後に安定化フェーズを開始（EPSは初期化しない）
                    inStabilizationPhase = true;
                    stabilizationIterationCount = 0;
                    LogManager.getInstance().log("BisectionalPGEPS: Flow increased to " + currentFlow + ". Entering stabilization phase for " + STABILIZATION_PHASE_ITERATIONS + " iterations (EPS state preserved)");
                } else if (newFlow == currentFlow) {
                    // スタック検知: lowerBound == upperBound == currentFlow → 上方シフトで脱出
                    if (shiftDirection == -1) {
                        // 下方シフト直後に増加スタック → 両方向失敗
                        LogManager.getInstance().log("BisectionalPGEPS: [Stuck/P3] UP shift blocked (shiftDirection=DOWN). Both directions failed. Throwing SolverFailedException.");
                        throw new SolverFailedException(currentClientId, ct + 1, "BisectionalPGEPS-StuckBothDirections");
                    } else {
                        double oldFlow = currentFlow;
                        lowerBound += 1;
                        upperBound += 1;
                        currentFlow += 1;
                        shiftDirection = 1;
                        stableIterationCount = 0;
                        currentFlowBaselineCaptured = false;
                        flowChanged = true;
                        // スタック時: セーブポイント破棄 → 初期状態にリセット
                        initializeEPS();
                        epsSavePoint.saveEPSState(link, pressureCoefficient, P_tubePressure, Q_Kirchhoff, D_tubeThickness_deltaT, Q_tubeFlow_sigmoidOutput);
                        inStabilizationPhase = true;
                        stabilizationIterationCount = 0;
                        LogManager.getInstance().log("BisectionalPGEPS: [Stuck/P3 / UP Shift] Shifted all bounds +1: " + oldFlow + " → " + currentFlow +
                                                   " (lower=" + lowerBound + ", upper=" + upperBound + ", shiftDirection=+1). EPS reset to initial state. " + epsSavePoint.getStatistics());
                    }
                } else {
                    LogManager.getInstance().log("BisectionalPGEPS: [Priority 3 DEBUG] Flow increase skipped - newFlow=" + newFlow +
                                               ", currentFlow=" + currentFlow + ", requestedFlow=" + requestedFlow +
                                               ", condition check: newFlow != currentFlow=" + (newFlow != currentFlow) +
                                               ", newFlow <= requestedFlow=" + (newFlow <= requestedFlow));
                }
            } else if (!flowChanged && (ct + 1) % 100 == 0) {
                // 100回に1回だけログ出力（フロー増加しない理由）
                LogManager.getInstance().log("BisectionalPGEPS: [Priority 3 DEBUG] Flow increase not triggered - mode=" + EXPERIMENT_MODE +
                                           ", inStabilizationPhase=" + inStabilizationPhase +
                                           ", shouldTriggerIncrease=" + shouldTriggerIncrease + ", currentFlow=" + currentFlow + ", requestedFlow=" + requestedFlow + ", flowChanged=" + flowChanged);
            }

            // 安定カウンター増加（フロー変更されなかった場合のみ）
            if (!flowChanged) {
                stableIterationCount++;
            }
            
            // 前回のソース圧力を更新
            previousSourcePressure = currentSourcePressure;

            // イテレーション毎の結果を記録
            try {
                if (sourceNode >= 0) {
                    ResultOutputManager.outputIterationSourcePressure(ct + 1, currentSourcePressure, serverController.getRunCounter());
                }
                ResultOutputManager.outputIterationFlow(ct + 1, currentFlow, serverController.getRunCounter());
            } catch (IOException e) {
                LogManager.getInstance().error("Failed to output iteration data", e);
            }

            // 結果のプロット
            if ((ct + 1) % PLOT == 0) {
                LogManager.getInstance().log("Dynamic Binary Search EPS Iteration: " + (ct + 1) + " with flow " + currentFlow +
                                          " (stable count: " + stableIterationCount + "/" + REQUIRED_STABLE_ITERATIONS + ")");
                ResultOutputManager.outputToPajek(client, eps, requestedFlow, ct, link, beaconCluster, node, serverController.getRunCounter());
                ResultOutputManager.outputToExcel(client, ct, link, node, serverController.getRunCounter(), currentFlow);
                ResultOutputManager.outputToTxt(client, ct, link, node, serverController.getRunCounter(), pressureCoefficient, P_tubePressure, currentFlow);
            }

            ct++;

            // Phase 5: 二分探索回数のチェック（フロー変更時のみカウント）
            if (flowChanged) {
                binarySearchIteration++;
                if (binarySearchIteration >= MAX_BINARY_SEARCH_ITERATIONS) {
                    LogManager.getInstance().log("BisectionalPGEPS: [Max Binary Search] Reached maximum binary search iterations (" + MAX_BINARY_SEARCH_ITERATIONS + "). Throwing SolverFailedException for retry management.");
                    throw new SolverFailedException(currentClientId, ct, "BisectionalPGEPS-MaxBinarySearch");
                }
            }
        }

        // Phase 5: MAX_ITERATIONSに達した場合は再試行対象
        if (ct >= MAX_ITERATIONS && stableIterationCount < REQUIRED_STABLE_ITERATIONS) {
            LogManager.getInstance().log("BisectionalPGEPS: [Max Iterations] Reached maximum iterations (" + MAX_ITERATIONS + ") without convergence. Throwing SolverFailedException for retry management.");
            throw new SolverFailedException(currentClientId, ct, "BisectionalPGEPS-MaxIterations");
        }

        // フロー分解アルゴリズムでは整数丸め込みは不要
        // （explorePath/explorePathGreedyがリアルタイムでフローを減算する）
        LogManager.getInstance().log("BisectionalPGEPS: EPS completed after " + ct + " iterations with flow " + currentFlow);

        LogManager.getInstance().log("BisectionalPGEPS: Dynamic binary search EPS completed with optimal flow " + currentFlow);

        // 最終結果を出力
        ResultOutputManager.outputToPajek(client, eps, requestedFlow, ct, link, beaconCluster, node, serverController.getRunCounter());
        ResultOutputManager.outputToExcel(client, ct, link, node, serverController.getRunCounter(), currentFlow);
        ResultOutputManager.outputToTxt(client, ct, link, node, serverController.getRunCounter(), pressureCoefficient, P_tubePressure, currentFlow);
    }

    // roundSourceOutflowsAndPropagate は削除
    // フロー分解アルゴリズムでは整数丸め込みは不要
    // （explorePath/explorePathGreedyがリアルタイムでフローを減算する）

    /**
     * 下流のフローを再帰的に調整する
     * @param psLink PSリンク配列
     * @param currentNode 現在のノード
     * @param destNode デスティネーションノード
     * @param correctionFactor 修正係数
     */
    private void adjustDownstreamFlows(Link[][] psLink, int currentNode, int destNode, double correctionFactor) {
        // 現在のノードから出るリンクを調整
        for (int j = 0; j < node; j++) {
            if (psLink[currentNode][j].getL_tubeLength() != INF && psLink[currentNode][j].getQ_tubeFlow() > 0) {
                double oldFlow = psLink[currentNode][j].getQ_tubeFlow();
                double newFlow = oldFlow * correctionFactor;
                psLink[currentNode][j].setQ_tubeFlow(newFlow);

                // 再帰的に下流も調整（デスティネーション到達まで）
                if (j != destNode && newFlow > 0) {
                    adjustDownstreamFlows(psLink, j, destNode, correctionFactor);
                }
            }
        }
    }
}
