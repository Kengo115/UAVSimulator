package server;

import client.Client;
import client.ClientController;
import server.util.ConfigurationManager;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

/**
 * UAVの飛行スケジュールを管理するクラス
 */
public class UAVFlyScheduler {
    
    private ServerController serverController;
    private ClientController clientController;
    private Timer timer;
    private int simulationInterval;
    private boolean isRunning;
    private int currentIteration;
    private int maxIterations;
    private ConfigurationManager config;
    
    /**
     * コンストラクタ
     * 
     * @param serverController サーバーコントローラー
     * @param clientController クライアントコントローラー
     */
    public UAVFlyScheduler(ServerController serverController, ClientController clientController) {
        this.serverController = serverController;
        this.clientController = clientController;
        this.timer = new Timer();
        this.simulationInterval = 1000; // デフォルトは1秒間隔
        this.isRunning = false;
        this.currentIteration = 0;
        this.maxIterations = 100; // デフォルトは100回
        this.config = ConfigurationManager.getInstance();
    }
    
    /**
     * シミュレーションを開始する
     * 
     * @param client クライアント
     * @param numLoop 繰り返し回数
     * @param algorithmName アルゴリズム名
     * @param solverName ソルバー名
     * @throws IOException 入出力例外
     */
    public void startSimulation(Client client, int numLoop, String algorithmName, String solverName) throws IOException {
        if (isRunning) {
            System.out.println("Simulation is already running.");
            return;
        }
        
        // 初期化
        serverController.setupNetworkTopology();
        serverController.setRouteSearchAlgorithm(algorithmName);
        serverController.setNumericalSolver(solverName);
        
        // 経路探索を実行
        serverController.searchRoutes(client, numLoop);
        
        // シミュレーション開始
        isRunning = true;
        currentIteration = 0;
        maxIterations = 1000; // 十分大きな値を設定
        
        // 定期的にUAVの飛行を管理
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (currentIteration >= maxIterations) {
                    stopSimulation();
                    return;
                }
                
                try {
                    // UAVの飛行を管理
                    serverController.manageUAVFlight();
                    
                    // 結果を出力
                    if (currentIteration % 10 == 0) {
                        double eps = 1e-10;
                        double allFlow = client.getFlow().getTheNumberOfUAV();
                        serverController.outputResultsToPajek(client, eps, allFlow, currentIteration);
                        serverController.outputResultsToExcel(client, currentIteration);
                        serverController.outputResultsToTxt(client, currentIteration);
                    }
                    
                    currentIteration++;
                    
                    // すべてのUAVが目的地に到着したかチェック
                    UAVManagementServer uavManager = serverController.getUavManagementServer();
                    int finishedUAVs = uavManager.getFinishedUAVCount(client.getId());
                    if (finishedUAVs >= client.getFlow().getTheNumberOfUAV()) {
                        System.out.println("All UAVs have reached their destinations.");
                        stopSimulation();
                    }
                } catch (Exception e) {
                    System.err.println("Error during simulation: " + e.getMessage());
                    e.printStackTrace();
                    stopSimulation();
                }
            }
        }, 0, simulationInterval);
    }
    
    /**
     * シミュレーションを停止する
     */
    public void stopSimulation() {
        if (!isRunning) {
            return;
        }
        
        timer.cancel();
        timer.purge();
        isRunning = false;
        System.out.println("Simulation stopped after " + currentIteration + " iterations.");
    }
    
    /**
     * シミュレーション間隔を設定する
     * 
     * @param interval 間隔（ミリ秒）
     */
    public void setSimulationInterval(int interval) {
        this.simulationInterval = interval;
    }
    
    /**
     * 最大繰り返し回数を設定する
     * 
     * @param maxIterations 最大繰り返し回数
     */
    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }
    
    /**
     * 実行中かどうかを取得する
     * 
     * @return 実行中の場合はtrue
     */
    public boolean isRunning() {
        return isRunning;
    }
    
    /**
     * 現在の繰り返し回数を取得する
     * 
     * @return 現在の繰り返し回数
     */
    public int getCurrentIteration() {
        return currentIteration;
    }
    
    /**
     * サーバーコントローラーを取得する
     * 
     * @return サーバーコントローラー
     */
    public ServerController getServerController() {
        return serverController;
    }
    
    /**
     * クライアントコントローラーを取得する
     * 
     * @return クライアントコントローラー
     */
    public ClientController getClientController() {
        return clientController;
    }
    
    /**
     * サーバーコントローラーを設定する
     * 
     * @param serverController サーバーコントローラー
     */
    public void setServerController(ServerController serverController) {
        this.serverController = serverController;
    }
    
    /**
     * クライアントコントローラーを設定する
     * 
     * @param clientController クライアントコントローラー
     */
    public void setClientController(ClientController clientController) {
        this.clientController = clientController;
    }
}
