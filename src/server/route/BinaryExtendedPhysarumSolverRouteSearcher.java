package server.route;

import client.Client;
import item.BeaconCluster;
import item.Link;
import item.Uav;
import server.controller.ServerController;
import server.util.ICCGSolver;
import server.util.LogManager;
import server.util.MathUtils;
import server.util.ResultOutputManager;

import java.io.IOException;
import java.util.Queue;

/**
 * バイナリサーチPhysarumSolverルートサーチャー
 * 二分探索を使用して最適なフロー値を見つけるEPS
 */
public class BinaryExtendedPhysarumSolverRouteSearcher extends ExtendedPhysarumSolverRouteSearcher {

    // 定数
    private static final double INIT_THICKNESS = 0.5; // 初期チューブ厚
    private static final double INIT_LENGTH = 1.0; // 初期チューブ長
    private static final int MAX_ITERATIONS = 10000; // 最大イテレーション数
    private static final int REQUIRED_STABLE_ITERATIONS = 500; // 収束判定用の連続安定回数
    private static final int MAX_BINARY_SEARCH_ITERATIONS = 30; // 二分探索の最大回数

    // ソースノード圧力専用閾値
    private static final double SOURCE_PRESSURE_EMERGENCY = 80; // 圧力絶対値閾値
    private static final double SOURCE_PRESSURE_CHANGE_THRESHOLD = 0.20; // 20%変化率閾値（フロー減少用）
    private static final double SOURCE_PRESSURE_REDUCTION_THRESHOLD = 0.30; // 20%減少閾値（フロー増加用）

    // フロー減少（UAV整数値対応）
    private static final double MINIMUM_FLOW_RATIO = 0.1; // 最小安全フロー（要求フローの10%）

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

    // 二分探索用変数
    private double lowerBound = 0.0; // 下限
    private double upperBound; // 上限（要求フロー）
    private int binarySearchIteration = 0; // 二分探索回数

    /**
     * コンストラクタ
     * @param serverController サーバーコントローラー
     * @param adjMatrix 隣接行列
     * @param link リンク情報
     * @param beaconCluster ビーコンクラスター
     * @param node ノード数
     */
    public BinaryExtendedPhysarumSolverRouteSearcher(ServerController serverController, int[][] adjMatrix, 
                                                    Link[][] link, BeaconCluster beaconCluster, int node) {
        super(serverController, adjMatrix, link, beaconCluster, node);
    }
    
    /**
     * 経路記録のタグを取得する
     * @return 経路記録のタグ
     */
    @Override
    protected String getRouteRecordTag() {
        return "runUAVFlow_BINARY";
    }

    /**
     * 残りの経路記録のタグを取得する
     * @return 残りの経路記録のタグ
     */
    @Override
    protected String getRemainingRouteRecordTag() {
        return "remainingFlow_BINARY";
    }

    @Override
    public void search(Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, int numLoop) throws IOException {
        // 通常のUAV割り当てのみを行う場合は親クラスのメソッドを呼び出す
        if (numLoop == 0) {
            super.search(client, flyingUavQueue, uavQueue, numLoop);
            return;
        }

        // 入力パラメータの取得と初期化
        requestedFlow = client.getFlow().getTheNumberOfUAV();
        this.sourceNode = client.getFlow().getSource().getId();
        this.destNode = client.getFlow().getDestination().getId();
        double eps = 1e-10;

        LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Starting dynamic binary search EPS with requested flow " + requestedFlow);

        // 二分探索の初期化
        lowerBound = 0.0;
        upperBound = requestedFlow;
        currentFlow = requestedFlow; // 要求フローから開始
        
        LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Initial bounds - Lower: " + lowerBound + ", Upper: " + upperBound + ", Starting flow: " + currentFlow);

        // 前回チューブ厚の初期化
        initializePreviousThickness();
        
        try {
            // 動的二分探索EPS実行
            performDynamicBinarySearchEPS(client, eps);

            // 親クラスのUAV割り当て処理を実行
            LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Setting up flow capacity arrays");
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != INF) {
                        adjMatrix[i][j] = 1;
                        if (link[i][j].getQ_tubeFlow() > 0) {
                            Flow_Capacity[i][j] = Math.max(0.0, link[i][j].getCapacity() - link[i][j].getQ_tubeFlow());
                            int flow = (int) Math.floor(link[i][j].getQ_tubeFlow());
                            tubeFlow[i][j] = flow;
                        }
                    }
                }
            }

            // EPSフロー値から残りUAV数を計算
            int requiredUAVs = (int) requestedFlow;
            double epsAssignedFlow = currentFlow;
            int remainingUAVs = requiredUAVs - (int)epsAssignedFlow;

            LogManager.getInstance().log("BinaryExtendedPhysarumSolver: EPS assigned flow " + epsAssignedFlow + " out of " + requiredUAVs + " required UAVs");

            // 残りUAVがある場合はPSで割り当て
            if (remainingUAVs > 0) {
                LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Remaining UAVs to be assigned by PS: " + remainingUAVs);
                
                // PS計算実行
                performPSComputation(client, remainingUAVs, sourceNode, destNode, eps);
                
                LogManager.getInstance().log("BinaryExtendedPhysarumSolver: PS computation completed. Results integrated.");
            } else {
                LogManager.getInstance().log("BinaryExtendedPhysarumSolver: EPS satisfied all required UAVs (" + requiredUAVs + "). No PS computation needed.");
            }

            // 全UAV割り当て実行
            LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Starting UAV flight assignment for all " + requiredUAVs + " UAVs");
            runUAVFlow(sourceNode, destNode, requiredUAVs, client, flyingUavQueue, uavQueue);
            
        } catch (Exception e) {
            LogManager.getInstance().error("Error in binary search EPS process: ", e);
            throw e;
        }
    }

    /**
     * フローテスト結果の状態
     */
    private enum FlowTestStatus {
        STABLE,                    // 安定
        UNSTABLE_PRESSURE,         // 圧力絶対値による不安定
        UNSTABLE_PRESSURE_CHANGE,  // 圧力変化率による不安定
        SOLVER_ERROR,              // ソルバーエラー
        MAX_ITERATIONS             // 最大イテレーション到達
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
                    LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Flow increase trigger detected at iteration " + (ct + 1) + " for flow " + testFlow + " (test only - no action taken during binary search)");
                }

                // ソース圧力チェック
                double currentSourcePressure = Math.abs(P_tubePressure[sourceNode]);
                maxSourcePressure = Math.max(maxSourcePressure, currentSourcePressure);

                // 圧力絶対値チェック
                if (currentSourcePressure >= SOURCE_PRESSURE_EMERGENCY) {
                    return new FlowTestResult(FlowTestStatus.UNSTABLE_PRESSURE, maxSourcePressure, ct);
                }

                // 圧力変化率チェック（10%増）
                if (previousSourcePressure > 0.0) {
                    double changeRate = (currentSourcePressure - previousSourcePressure) / previousSourcePressure;
                    if (changeRate >= SOURCE_PRESSURE_CHANGE_THRESHOLD) {
                        return new FlowTestResult(FlowTestStatus.UNSTABLE_PRESSURE_CHANGE, maxSourcePressure, ct);
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
        LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Performing final EPS run with flow " + finalFlow);
        
        // EPSの初期化
        initializeEPS();
        
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
                LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Pressure equation solving failed at iteration " + (ct + 1));
                break;
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
                LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Final EPS baseline pressure captured: " + String.format("%.4f", currentFlowBaselinePressure));
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
                    
                    LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Source pressure 10% reduction detected in final EPS " +
                                              " (baselinePressure=" + String.format("%.4f", currentFlowBaselinePressure) +
                                              ", currentPressure=" + String.format("%.4f", Math.abs(P_tubePressure[sourceNode])) +
                                              "). Binary search flow increase: " + (finalFlow - increaseAmount) + " → " + finalFlow +
                                              " (new bounds: " + lowerBound + " - " + Math.min(upperBound, requestedFlow) + ")");
                }
            }

            // ソース圧力チェック（最終実行でも安定性をチェック）
            double currentSourcePressure = Math.abs(P_tubePressure[sourceNode]);
            
            // 圧力絶対値チェック
            if (currentSourcePressure >= SOURCE_PRESSURE_EMERGENCY) {
                LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Final EPS run terminated due to pressure emergency at iteration " + (ct + 1) + " (pressure: " + currentSourcePressure + ")");
                break;
            }

            // 圧力変化率チェック（10%増）
            if (previousSourcePressure > 0.0) {
                double changeRate = (currentSourcePressure - previousSourcePressure) / previousSourcePressure;
                if (changeRate >= SOURCE_PRESSURE_CHANGE_THRESHOLD) {
                    LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Final EPS run terminated due to pressure change rate at iteration " + (ct + 1) + " (change rate: " + (changeRate * 100) + "%)");
                    break;
                }
            }

            // 安定カウンター増加
            stableIterationCount++;
            
            // 前回のソース圧力を更新（次回の変化率計算用）
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

        // 最終流量の整数丸め
        LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Performing final flow rounding after " + ct + " iterations");
        int linkCount = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    linkCount++;
                }
            }
        }

        double[] flows = new double[linkCount];
        int index = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    flows[index++] = link[i][j].getQ_tubeFlow();
                }
            }
        }

        MathUtils.roundWithConservation(flows);

        index = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    link[i][j].setQ_tubeFlow(flows[index++]);
                }
            }
        }

        LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Final EPS completed with flow " + finalFlow);

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
        LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Initialized previous thickness array with " + totalLinks + " links");
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
        
        LogManager.getInstance().log("BinaryExtendedPhysarumSolver: PS starting with " + remainingUAVs + " UAVs flow");
        
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
                LogManager.getInstance().log("BinaryExtendedPhysarumSolver: PS pressure equation solving failed at iteration " + (psIter + 1));
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

            // 最終イテレーションで流量を整数に丸める
            if (psIter == 499) {
                int linkCount = 0;
                for (int i = 0; i < node; i++) {
                    for (int j = 0; j < node; j++) {
                        if (psLink[i][j].getL_tubeLength() != INF) {
                            linkCount++;
                        }
                    }
                }

                double[] flows = new double[linkCount];
                int index = 0;
                for (int i = 0; i < node; i++) {
                    for (int j = 0; j < node; j++) {
                        if (psLink[i][j].getL_tubeLength() != INF) {
                            flows[index++] = psLink[i][j].getQ_tubeFlow();
                        }
                    }
                }

                MathUtils.roundWithConservation(flows);

                index = 0;
                for (int i = 0; i < node; i++) {
                    for (int j = 0; j < node; j++) {
                        if (psLink[i][j].getL_tubeLength() != INF) {
                            psLink[i][j].setQ_tubeFlow(flows[index++]);
                        }
                    }
                }
            }

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
        LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Integrating EPS and PS results");
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

        // 統合後に再度整数への丸め込みを実行
        LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Applying final integer rounding after EPS+PS integration");
        int linkCount = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    linkCount++;
                }
            }
        }

        double[] integratedFlows = new double[linkCount];
        int index = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    integratedFlows[index++] = link[i][j].getQ_tubeFlow();
                }
            }
        }

        MathUtils.roundWithConservation(integratedFlows);

        index = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    double oldFlow = link[i][j].getQ_tubeFlow();
                    double newFlow = integratedFlows[index++];
                    link[i][j].setQ_tubeFlow(newFlow);
                    if (oldFlow != newFlow) {
                        LogManager.getInstance().log("Final rounding: Link (" + i + "," + j + "): " + oldFlow + " → " + newFlow);
                    }
                }
            }
        }

        LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Final integer rounding completed. All flows are now exact integers.");

        // 統合完了後に、Flow_CapacityとtubeFlowを統合結果で更新
        LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Updating Flow_Capacity and tubeFlow arrays with integrated results");
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    if (link[i][j].getQ_tubeFlow() > 0) {
                        Flow_Capacity[i][j] = Math.max(0.0, link[i][j].getCapacity() - link[i][j].getQ_tubeFlow());
                        int flow = (int) Math.floor(link[i][j].getQ_tubeFlow());
                        tubeFlow[i][j] = flow;
                        LogManager.getInstance().log("Updated arrays: Link(" + i + "," + j + ") - Flow_Capacity=" + Flow_Capacity[i][j] + ", tubeFlow=" + tubeFlow[i][j]);
                    }
                }
            }
        }
        LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Flow arrays update completed");
    }

    /**
     * 動的二分探索EPS実行
     * EPSを継続実行しながら、圧力検知に基づいて二分探索でフロー値を動的に調整
     * @param client クライアント
     * @param eps イプシロン値
     * @throws IOException 入出力例外
     */
    private void performDynamicBinarySearchEPS(Client client, double eps) throws IOException {
        LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Starting dynamic binary search EPS with initial flow " + currentFlow);
        
        // EPSの初期化
        initializeEPS();
        
        stableIterationCount = 0;
        previousSourcePressure = 0.0;
        currentFlowBaselinePressure = 0.0;
        currentFlowBaselineCaptured = false;
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
                LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Pressure equation solving failed at iteration " + (ct + 1));
                break;
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
                LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Baseline pressure captured for flow " + currentFlow + ": " + String.format("%.4f", currentFlowBaselinePressure));
            }

            // ソース圧力チェック（優先度順で実行）
            double currentSourcePressure = Math.abs(P_tubePressure[sourceNode]);
            boolean flowChanged = false;
            
            // デバッグ用ログ出力（常に出力）
            boolean shouldIncrease = checkShouldIncreaseFlow();
            double tenPercentReduction = currentFlowBaselineCaptured ? currentFlowBaselinePressure * 0.9 : 0.0;
            
            // 100回に1回だけ詳細ログ出力（ログ量を抑制）
            if ((ct + 1) % 100 == 0 || ct < 10) {
                LogManager.getInstance().log("BinaryExtendedPhysarumSolver: [Debug] Flow increase check at iteration " + (ct + 1) + 
                                           " - baselineCaptured=" + currentFlowBaselineCaptured +
                                           ", baselinePressure=" + String.format("%.4f", currentFlowBaselinePressure) +
                                           ", currentPressure=" + String.format("%.4f", currentSourcePressure) +
                                           ", threshold=" + String.format("%.4f", tenPercentReduction) +
                                           ", shouldIncrease=" + shouldIncrease +
                                           ", currentFlow=" + currentFlow + 
                                           ", requestedFlow=" + requestedFlow +
                                           ", sourceNode=" + sourceNode);
            }
            
            // 【優先度1】圧力絶対値チェック（100以上でフロー減少）
            if (currentSourcePressure >= SOURCE_PRESSURE_EMERGENCY) {
                LogManager.getInstance().log("BinaryExtendedPhysarumSolver: [Priority 1] Pressure emergency detected at iteration " + (ct + 1) + 
                                           " (pressure: " + String.format("%.2f", currentSourcePressure) + "). Applying binary search flow reduction.");
                
                // 上限を現在のフロー値に更新
                upperBound = currentFlow;
                double newFlow = Math.ceil((lowerBound + upperBound) / 2.0);
                
                if (newFlow != currentFlow && newFlow > lowerBound) {
                    LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Binary search flow reduction: " + currentFlow + " → " + newFlow +
                                               " (new bounds: " + lowerBound + " - " + upperBound + ")");
                    
                    currentFlow = newFlow;
                    stableIterationCount = 0; // リセット
                    currentFlowBaselineCaptured = false; // 基準圧力をリセット
                    flowChanged = true;
                    // EPSは初期化せず、フロー値のみ変更してEPS継続
                }
            }
            // 【優先度2】圧力増加率チェック（直前との比較で10%増加でフロー減少）
            else if (previousSourcePressure > 0.0) {
                double changeRate = (currentSourcePressure - previousSourcePressure) / previousSourcePressure;
                if (changeRate >= SOURCE_PRESSURE_CHANGE_THRESHOLD) {
                    LogManager.getInstance().log("BinaryExtendedPhysarumSolver: [Priority 2] Pressure increase detected at iteration " + (ct + 1) + 
                                               " (change rate: " + String.format("%.2f%%", changeRate * 100) + "). Applying binary search flow reduction.");
                    
                    // 上限を現在のフロー値に更新
                    upperBound = currentFlow;
                    double newFlow = Math.ceil((lowerBound + upperBound) / 2.0);
                    
                    if (newFlow != currentFlow && newFlow > lowerBound) {
                        LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Binary search flow reduction: " + currentFlow + " → " + newFlow +
                                                   " (new bounds: " + lowerBound + " - " + upperBound + ")");
                        
                        currentFlow = newFlow;
                        stableIterationCount = 0; // リセット
                        currentFlowBaselineCaptured = false; // 基準圧力をリセット
                        flowChanged = true;
                        // EPSは初期化せず、フロー値のみ変更してEPS継続
                    }
                }
            }
            
            // 【優先度3】圧力減少率チェック（現在フロー基準圧力から10%減少でフロー増加）- 独立して実行
            if (!flowChanged && shouldIncrease && currentFlow < requestedFlow) {
                // デバッグログ
                LogManager.getInstance().log("BinaryExtendedPhysarumSolver: [Priority 3 DEBUG] Flow increase conditions - shouldIncrease=" + shouldIncrease + 
                                           ", currentFlow=" + currentFlow + ", requestedFlow=" + requestedFlow +
                                           ", lowerBound=" + lowerBound + ", upperBound=" + upperBound);
                
                // 下限を現在のフロー値に更新
                lowerBound = currentFlow;
                double newFlow = Math.ceil((lowerBound + Math.min(upperBound, requestedFlow)) / 2.0);
                
                // 要求フローを超えないように制限
                if (newFlow > requestedFlow) {
                    newFlow = requestedFlow;
                }
                
                LogManager.getInstance().log("BinaryExtendedPhysarumSolver: [Priority 3 DEBUG] Calculated newFlow=" + newFlow + 
                                           " (newFlow != currentFlow: " + (newFlow != currentFlow) + 
                                           ", newFlow <= requestedFlow: " + (newFlow <= requestedFlow) + ")");
                
                if (newFlow != currentFlow && newFlow <= requestedFlow) {
                    LogManager.getInstance().log("BinaryExtendedPhysarumSolver: [Priority 3] Source pressure 10% reduction detected at iteration " + (ct + 1) + 
                                               " (baselinePressure=" + String.format("%.4f", currentFlowBaselinePressure) +
                                               ", currentPressure=" + String.format("%.4f", currentSourcePressure) +
                                               "). Binary search flow increase: " + currentFlow + " → " + newFlow +
                                               " (new bounds: " + lowerBound + " - " + Math.min(upperBound, requestedFlow) + ")");
                    
                    currentFlow = newFlow;
                    stableIterationCount = 0; // リセット
                    currentFlowBaselineCaptured = false; // 基準圧力をリセット
                    flowChanged = true;
                    // EPSは初期化せず、フロー値のみ変更してEPS継続
                } else {
                    LogManager.getInstance().log("BinaryExtendedPhysarumSolver: [Priority 3 DEBUG] Flow increase skipped - newFlow=" + newFlow + 
                                               ", currentFlow=" + currentFlow + ", requestedFlow=" + requestedFlow +
                                               ", condition check: newFlow != currentFlow=" + (newFlow != currentFlow) +
                                               ", newFlow <= requestedFlow=" + (newFlow <= requestedFlow));
                }
            } else if (!flowChanged && (ct + 1) % 100 == 0) {
                // 100回に1回だけログ出力（フロー増加しない理由）
                LogManager.getInstance().log("BinaryExtendedPhysarumSolver: [Priority 3 DEBUG] Flow increase not triggered - shouldIncrease=" + shouldIncrease + 
                                           ", currentFlow=" + currentFlow + ", requestedFlow=" + requestedFlow + ", flowChanged=" + flowChanged);
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
        }

        // 最終流量の整数丸め
        LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Performing final flow rounding after " + ct + " iterations");
        int linkCount = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    linkCount++;
                }
            }
        }

        double[] flows = new double[linkCount];
        int index = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    flows[index++] = link[i][j].getQ_tubeFlow();
                }
            }
        }

        MathUtils.roundWithConservation(flows);

        index = 0;
        for (int i = 0; i < node; i++) {
            for (int j = 0; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    link[i][j].setQ_tubeFlow(flows[index++]);
                }
            }
        }

        LogManager.getInstance().log("BinaryExtendedPhysarumSolver: Dynamic binary search EPS completed with optimal flow " + currentFlow);

        // 最終結果を出力
        ResultOutputManager.outputToPajek(client, eps, requestedFlow, ct, link, beaconCluster, node, serverController.getRunCounter());
        ResultOutputManager.outputToExcel(client, ct, link, node, serverController.getRunCounter(), currentFlow);
        ResultOutputManager.outputToTxt(client, ct, link, node, serverController.getRunCounter(), pressureCoefficient, P_tubePressure, currentFlow);
    }
}
