package server.route;

import client.Client;
import client.ClientController;
import item.Link;
import item.Uav;
import server.communication.ResultOutputManager;
import server.util.ConfigurationManager;
import server.util.NumericalSolverService;

import java.io.IOException;
import java.util.Arrays;
import java.util.Queue;

/**
 * 拡張粘菌アルゴリズム（Extended Physarum Solver）による経路探索を行う実装クラス
 */
public class ExtendedPhysarumSolverRouteSearcherImpl implements ExtendedPhysarumSolverRouteSearcher {
    
    private Link[][] linkMatrix;
    private int nodeNum;
    private double[] qKirchhoff;
    private double[] pTubePressure;
    private double[][] pressureCoefficient;
    private double[] qKirchhoffSinkExcept;
    private double[] pTubePressureSinkExcept;
    private double[][] pressureCoefficientSinkExcept;
    private double[][] qTubeFlowSigmoidOutput;
    private double[][] flowCapacity;
    private int[][] tubeFlow;
    private int[][] adjMatrix;
    private int runCounter;
    private ResultOutputManager outputManager;
    private RouteAssignmentService routeAssignmentService;
    private NumericalSolverService numericalSolver;
    private ConfigurationManager config;
    
    /**
     * コンストラクタ
     * 
     * @param linkMatrix リンク行列
     * @param nodeNum ノード数
     * @param outputManager 結果出力管理
     * @param routeAssignmentService 経路割り当てサービス
     * @param numericalSolver 数値計算ソルバー
     */
    public ExtendedPhysarumSolverRouteSearcherImpl(Link[][] linkMatrix, int nodeNum, ResultOutputManager outputManager, 
                                                  RouteAssignmentService routeAssignmentService, NumericalSolverService numericalSolver) {
        this.linkMatrix = linkMatrix;
        this.nodeNum = nodeNum;
        this.outputManager = outputManager;
        this.routeAssignmentService = routeAssignmentService;
        this.numericalSolver = numericalSolver;
        this.config = ConfigurationManager.getInstance();
        this.runCounter = 0;
        
        initialize(nodeNum);
    }
    
    /**
     * 初期化する
     * 
     * @param nodeNum ノード数
     */
    private void initialize(int nodeNum) {
        int nodeExcept = nodeNum - 1;
        
        // 1xN matrix
        this.qKirchhoff = new double[nodeNum];
        this.pTubePressure = new double[nodeNum];
        this.qKirchhoffSinkExcept = new double[nodeExcept];
        this.pTubePressureSinkExcept = new double[nodeExcept];
        
        // 2xN matrix
        this.pressureCoefficient = new double[nodeNum][nodeNum];
        this.pressureCoefficientSinkExcept = new double[nodeExcept][nodeExcept];
        this.qTubeFlowSigmoidOutput = new double[nodeNum][nodeNum];
        this.flowCapacity = new double[nodeNum][nodeNum];
        this.tubeFlow = new int[nodeNum][nodeNum];
        this.adjMatrix = new int[nodeNum][nodeNum];
    }
    
    /**
     * 経路探索を実行し、UAVに経路を割り当てる
     * 
     * @param client クライアント
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param numLoop 繰り返し回数
     * @throws IOException 入出力例外
     */
    @Override
    public void searchAndAssignRoutes(Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, int numLoop) throws IOException {
        run_EPS(client, flyingUavQueue, uavQueue, null, numLoop);
    }
    
    /**
     * アルゴリズム名を取得する
     * 
     * @return アルゴリズム名
     */
    @Override
    public String getAlgorithmName() {
        return "ExtendedPhysarumSolver";
    }
    
    /**
     * 拡張粘菌アルゴリズムを実行する
     * 
     * @param client クライアント
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param clientController クライアントコントローラ
     * @param numLoop 繰り返し回数
     * @throws IOException 入出力例外
     */
    @Override
    public void run_EPS(Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, ClientController clientController, int numLoop) throws IOException {
        int nodeExcept = nodeNum - 1;
        int ct = 0;
        double eps = 1e-10;
        int testIter = 10;
        int a = 0, b, i, j;
        double degeneracyEffect = 1.0;
        double coefficientTanh = config.getCoefficientTanh(); // 拡張版で使用するtanh関数の係数
        int source = client.getFlow().getSource().getId();
        int dist = client.getFlow().getDestination().getId();
        
        if (runCounter != 0) {
            // 更新メソッドを呼び出す
            reset();
        }
        
        while (ct < numLoop) {
            // sourceとdistを取得
            qKirchhoff[source] = client.getFlow().getTheNumberOfUAV();
            qKirchhoff[dist] = client.getFlow().getTheNumberOfUAV() * config.getNeg();
            
            for (i = 0; i < nodeNum; i++) {
                pressureCoefficient[i][i] = 0.0;
                if (i != source && i != dist) {
                    qKirchhoff[i] = 0.0;
                }
            }
            
            // 圧力勾配の導出
            calculatePressureCoefficient(nodeNum, source, dist, client.getFlow().getTheNumberOfUAV());
            
            // sinkExcept 配列の準備
            for (a = 0, i = 0; i < nodeNum; i++) {
                if (i != dist) {
                    qKirchhoffSinkExcept[a] = qKirchhoff[i];
                    a++;
                }
            }
            
            for (i = 0, a = 0; i < nodeNum && a < nodeExcept; i++, a++) {
                if (i == dist && dist != nodeNum) {
                    i++;
                }
                for (j = 0, b = 0; j < nodeNum && b < nodeExcept; j++, b++) {
                    if (j == dist) {
                        j++;
                    }
                    pressureCoefficientSinkExcept[a][b] = pressureCoefficient[i][j];
                }
            }
            
            // 線形方程式を解く
            if (solveLinearEquation(numericalSolver, nodeExcept, testIter, eps) == 0) {
                break;
            }
            
            // 圧力値の反映
            for (a = 0, i = 0; i < nodeNum; i++) {
                if (i == dist) {
                    pTubePressure[i] = 0.0;
                } else {
                    pTubePressure[i] = pTubePressureSinkExcept[a];
                    a++;
                }
            }
            
            // 流量の計算
            calculateFlow(nodeNum);
            
            if (ct == numLoop - 1) {
                int linkCount = 0;
                for (i = 0; i < nodeNum; i++) {
                    for (j = 0; j < nodeNum; j++) {
                        if (linkMatrix[i][j].getL_tubeLength() != config.getInf()) {
                            linkCount++;
                        }
                    }
                }
                
                double[] flows = new double[linkCount];
                int index = 0;
                for (i = 0; i < nodeNum; i++) {
                    for (j = 0; j < nodeNum; j++) {
                        if (linkMatrix[i][j].getL_tubeLength() != config.getInf()) {
                            flows[index++] = linkMatrix[i][j].getQ_tubeFlow();
                        }
                    }
                }
                
                roundWithConservation(flows);
                
                index = 0;
                for (i = 0; i < nodeNum; i++) {
                    for (j = 0; j < nodeNum; j++) {
                        if (linkMatrix[i][j].getL_tubeLength() != config.getInf()) {
                            linkMatrix[i][j].setQ_tubeFlow(flows[index++]);
                        }
                    }
                }
            }
            
            // シグモイド関数
            applySigmoidFunction(nodeNum, config.getGamma());
            
            // チューブ厚の更新（拡張版）
            updateTubeThickness(nodeNum, degeneracyEffect, config.getDeltaTime(), coefficientTanh);
            
            // 結果のプロット
            if ((ct + 1) % config.getPlot() == 0) {
                System.out.println("Iteration: " + (ct + 1));
                outputManager.outputToPajek(client, eps, client.getFlow().getTheNumberOfUAV(), ct);
                outputManager.outputToExcel(client, ct);
                outputManager.outputToTxt(client, ct);
            }
            
            if (ct % config.getPlot2() == 0 || ct == numLoop - 1) {
                outputManager.outputRouteToExcel(client, ct);
            }
            
            ct++;
            
            // 最後のループの場合に実行する処理
            if (ct == numLoop) {
                // 初期設定として、Flow_CapacityにQ_tubeFlowを代入,各リンクを流れる流量の整数値をtubeFlowに追加
                saveFlow(nodeNum);
                
                // スタートノード、ゴールノード、必要なUAV台数を取得
                int startNode = client.getFlow().getSource().getId();
                int goalNode = client.getFlow().getDestination().getId();
                int requiredUAVs = (int) client.getFlow().getTheNumberOfUAV();
                
                // 実際のUAVに経路を割り当てるためのメイン処理
                runUAVFlow(startNode, goalNode, requiredUAVs, client, flyingUavQueue, uavQueue);
            }
        }
        
        runCounter++;
    }
    
    /**
     * 圧力係数を計算する
     * 
     * @param nodeNum ノード数
     * @param source 出発地
     * @param destination 目的地
     * @param flowAmount 流量
     */
    @Override
    public void calculatePressureCoefficient(int nodeNum, int source, int destination, double flowAmount) {
        for (int i = 0; i < nodeNum; i++) {
            for (int j = 0; j < nodeNum; j++) {
                if (linkMatrix[i][j].getL_tubeLength() != config.getInf()) {
                    if (i != j) {
                        pressureCoefficient[i][j] = linkMatrix[i][j].getD_tubeThickness() / linkMatrix[i][j].getL_tubeLength() * config.getNeg();
                    }
                }
            }
        }
        
        int k = 0;
        for (int i = 0; i < nodeNum; i++) {
            for (int j = 0; j < nodeNum; j++) {
                if (linkMatrix[i][j].getL_tubeLength() != config.getInf()) {
                    pressureCoefficient[k][k] = pressureCoefficient[k][k] + linkMatrix[i][j].getD_tubeThickness() / linkMatrix[i][j].getL_tubeLength();
                }
            }
            k++;
        }
    }
    
    /**
     * 線形方程式を解く
     * 
     * @param numericalSolver 数値計算ソルバー
     * @param nodeNum ノード数
     * @param maxIter 最大反復回数
     * @param eps 収束判定閾値
     * @return 収束した場合は1、収束しなかった場合は0
     */
    @Override
    public int solveLinearEquation(NumericalSolverService numericalSolver, int nodeNum, int maxIter, double eps) {
        return numericalSolver.solve(pressureCoefficientSinkExcept, qKirchhoffSinkExcept, pTubePressureSinkExcept, nodeNum, maxIter, eps);
    }
    
    /**
     * 流量を計算する
     * 
     * @param nodeNum ノード数
     */
    @Override
    public void calculateFlow(int nodeNum) {
        for (int i = 0; i < nodeNum; i++) {
            for (int j = 0; j < nodeNum; j++) {
                if (linkMatrix[i][j].getL_tubeLength() != config.getInf()) {
                    linkMatrix[i][j].setQ_tubeFlow((linkMatrix[i][j].getD_tubeThickness() / linkMatrix[i][j].getL_tubeLength()) * (pTubePressure[i] - pTubePressure[j]));
                }
            }
        }
    }
    
    /**
     * シグモイド関数を適用する
     * 
     * @param nodeNum ノード数
     * @param gamma ガンマ値
     */
    @Override
    public void applySigmoidFunction(int nodeNum, double gamma) {
        for (int i = 0; i < nodeNum; i++) {
            for (int j = 0; j < nodeNum; j++) {
                if (linkMatrix[i][j].getL_tubeLength() != config.getInf()) {
                    qTubeFlowSigmoidOutput[i][j] = (Math.pow(Math.abs(linkMatrix[i][j].getQ_tubeFlow()), gamma)) / (1 + Math.pow(Math.abs(linkMatrix[i][j].getQ_tubeFlow()), gamma));
                }
            }
        }
    }
    
    /**
     * 管の太さを更新する
     * 
     * @param nodeNum ノード数
     * @param degeneracyEffect 退化効果
     * @param deltaTime デルタ時間
     */
    @Override
    public void updateTubeThickness(int nodeNum, double degeneracyEffect, double deltaTime) {
        // 拡張版のメソッドを呼び出す（デフォルトのcoefficientTanhを使用）
        updateTubeThickness(nodeNum, degeneracyEffect, deltaTime, config.getCoefficientTanh());
    }
    
    /**
     * 管の太さを更新する（拡張版）
     * 
     * @param nodeNum ノード数
     * @param degeneracyEffect 退化効果
     * @param deltaTime デルタ時間
     * @param coefficientTanh tanh関数の係数
     */
    @Override
    public void updateTubeThickness(int nodeNum, double degeneracyEffect, double deltaTime, double coefficientTanh) {
        for (int i = 0; i < nodeNum; i++) {
            for (int j = 0; j < nodeNum; j++) {
                if (linkMatrix[i][j].getL_tubeLength() != config.getInf()) {
                    // 拡張版: tanh関数を使用して非線形性を強化
                    double flowEffect = Math.tanh(coefficientTanh * Math.abs(linkMatrix[i][j].getQ_tubeFlow()));
                    double adaptiveFactor = 1.0 + flowEffect;
                    
                    double deltaThickness = (qTubeFlowSigmoidOutput[i][j] * adaptiveFactor - (degeneracyEffect * linkMatrix[i][j].getD_tubeThickness())) * deltaTime;
                    double newThickness = linkMatrix[i][j].getD_tubeThickness() + deltaThickness;
                    
                    // 最小値を保証
                    newThickness = Math.max(newThickness, 0.01);
                    
                    linkMatrix[i][j].setD_tubeThickness(newThickness);
                }
            }
        }
    }
    
    /**
     * 流量を保存する
     * 
     * @param nodeNum ノード数
     */
    @Override
    public void saveFlow(int nodeNum) {
        for (int i = 0; i < nodeNum; i++) {
            for (int j = 0; j < nodeNum; j++) {
                if (linkMatrix[i][j].getL_tubeLength() != config.getInf()) {
                    adjMatrix[i][j] = 1;
                    if (linkMatrix[i][j].getQ_tubeFlow() > 0) {
                        flowCapacity[i][j] = linkMatrix[i][j].getQ_tubeFlow();
                        int flow = (int) Math.floor(flowCapacity[i][j]);
                        tubeFlow[i][j] = flow;
                    }
                }
            }
        }
    }
    
    /**
     * 流量を保存する（整数値に丸める）
     * 
     * @param flows 流量配列
     */
    @Override
    public void roundWithConservation(double[] flows) {
        int n = flows.length;
        double sum_frac = 0.0;
        for (double val : flows) {
            sum_frac += val;
        }
        long sum_int = Math.round(sum_frac);
        
        long[] output_floor = new long[n];
        double[] fractional_parts = new double[n];
        long sum_floor = 0;
        
        for (int i = 0; i < n; i++) {
            output_floor[i] = (long) Math.floor(flows[i]);
            fractional_parts[i] = flows[i] - output_floor[i];
            sum_floor += output_floor[i];
        }
        
        long diff = sum_int - sum_floor;
        
        // 小数部分の大きい順にソート
        Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) {
            indices[i] = i;
        }
        
        Arrays.sort(indices, (a, b) -> Double.compare(fractional_parts[b], fractional_parts[a]));
        
        // 差分を埋める
        for (int i = 0; i < diff; i++) {
            output_floor[indices[i]]++;
        }
        
        // 結果を反映
        for (int i = 0; i < n; i++) {
            flows[i] = output_floor[i];
        }
    }
    
    /**
     * UAVに経路を割り当てる
     * 
     * @param startNode 開始ノード
     * @param goalNode 目標ノード
     * @param requiredUAVs 必要なUAV数
     * @param client クライアント
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     */
    @Override
    public void runUAVFlow(int startNode, int goalNode, int requiredUAVs, Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue) {
        routeAssignmentService.assignRoutes(startNode, goalNode, requiredUAVs, client, flyingUavQueue, uavQueue);
    }
    
    /**
     * 数値計算ソルバーを設定する
     * 
     * @param numericalSolver 数値計算ソルバー
     */
    @Override
    public void setNumericalSolver(NumericalSolverService numericalSolver) {
        this.numericalSolver = numericalSolver;
    }
    
    /**
     * 数値計算ソルバーを取得する
     * 
     * @return 数値計算ソルバー
     */
    @Override
    public NumericalSolverService getNumericalSolver() {
        return numericalSolver;
    }
    
    /**
     * リセットする
     */
    private void reset() {
        Arrays.fill(qKirchhoff, 0.0);
        Arrays.fill(pTubePressure, 0.0);
        Arrays.fill(qKirchhoffSinkExcept, 0.0);
        Arrays.fill(pTubePressureSinkExcept, 0.0);
        
        // 2次元配列の初期化
        for (int i = 0; i < nodeNum; i++) {
            for (int j = 0; j < nodeNum; j++) {
                pressureCoefficient[i][j] = 0.0;
                qTubeFlowSigmoidOutput[i][j] = 0.0;
                flowCapacity[i][j] = 0.0;
                tubeFlow[i][j] = 0;
                adjMatrix[i][j] = 0;
            }
        }
        
        for (int i = 0; i < nodeNum - 1; i++) {
            for (int j = 0; j < nodeNum - 1; j++) {
                pressureCoefficientSinkExcept[i][j] = 0.0;
            }
        }
    }
    
    /**
     * リンク行列を設定する
     * 
     * @param linkMatrix リンク行列
     */
    public void setLinkMatrix(Link[][] linkMatrix) {
        this.linkMatrix = linkMatrix;
    }
    
    /**
     * ノード数を設定する
     * 
     * @param nodeNum ノード数
     */
    public void setNodeNum(int nodeNum) {
        this.nodeNum = nodeNum;
        initialize(nodeNum);
    }
    
    /**
     * 結果出力管理を設定する
     * 
     * @param outputManager 結果出力管理
     */
    public void setOutputManager(ResultOutputManager outputManager) {
        this.outputManager = outputManager;
    }
    
    /**
     * 経路割り当てサービスを設定する
     * 
     * @param routeAssignmentService 経路割り当てサービス
     */
    public void setRouteAssignmentService(RouteAssignmentService routeAssignmentService) {
        this.routeAssignmentService = routeAssignmentService;
    }
    
    /**
     * 粘菌アルゴリズムを実行する
     * 
     * @param client クライアント
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     * @param clientController クライアントコントローラ
     * @param numLoop 繰り返し回数
     * @throws IOException 入出力例外
     */
    @Override
    public void run_PS(Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue, ClientController clientController, int numLoop) throws IOException {
        // 拡張版のメソッドを呼び出す
        run_EPS(client, flyingUavQueue, uavQueue, clientController, numLoop);
    }
    
    /**
     * 実行カウンターを設定する
     * 
     * @param runCounter 実行カウンター
     */
    public void setRunCounter(int runCounter) {
        this.runCounter = runCounter;
    }
}
