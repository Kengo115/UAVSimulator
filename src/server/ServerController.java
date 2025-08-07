package server;

import client.Client;
import client.ClientController;
import item.BeaconCluster;
import item.Link;
import item.Uav;
import server.communication.CommunicationServer;
import server.communication.CommunicationServerImpl;
import server.communication.NetworkTopologyManager;
import server.communication.NetworkTopologyManagerImpl;
import server.communication.ResultOutputManager;
import server.communication.ResultOutputManagerImpl;
import server.route.DijkstraRouteSearcher;
import server.route.DijkstraRouteSearcherImpl;
import server.route.ExtendedPhysarumSolverRouteSearcher;
import server.route.ExtendedPhysarumSolverRouteSearcherImpl;
import server.route.PhysarumSolverRouteSearcher;
import server.route.PhysarumSolverRouteSearcherImpl;
import server.route.RouteAssignmentService;
import server.route.RouteAssignmentServiceImpl;
import server.route.RouteSearchServer;
import server.uav.CapacityManager;
import server.uav.CapacityManagerImpl;
import server.uav.FlightDataRecorder;
import server.uav.FlightDataRecorderImpl;
import server.uav.UAVFlightController;
import server.uav.UAVFlightControllerImpl;
import server.uav.UAVManagementServer;
import server.uav.UAVManagementServerImpl;
import server.uav.UAVQueueManager;
import server.uav.UAVQueueManagerImpl;
import server.util.BiCGSTABSolver;
import server.util.ConfigurationManager;
import server.util.ICCGSolver;
import server.util.NumericalSolverService;

import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;

/**
 * サーバーコントローラークラス
 * 各種サービスを統合し、UAVシミュレーションを制御する
 */
public class ServerController {
    
    // 定数
    private static final int DEFAULT_NODE_NUM = 6;
    private static final String DEFAULT_BASE_DIR_PATH = "src/output/EPS"; // デフォルトはEPS
    
    // 変数
    private String baseDirectoryPath;
    
    // サーバーコンポーネント
    private CommunicationServer communicationServer;
    private UAVManagementServer uavManagementServer;
    private RouteSearchServer routeSearchServer;
    
    // サブコンポーネント
    private NetworkTopologyManager topologyManager;
    private ResultOutputManager outputManager;
    private CapacityManager capacityManager;
    private UAVFlightController flightController;
    private UAVQueueManager queueManager;
    private FlightDataRecorder dataRecorder;
    private RouteAssignmentService routeAssignmentService;
    private NumericalSolverService numericalSolver;
    
    // 設定
    private ConfigurationManager config;
    private int nodeNum;
    private Link[][] linkMatrix;
    private BeaconCluster beaconCluster;
    
    // UAVキュー
    private Queue<Uav> flyingUavQueue;
    private Queue<Uav> uavQueue;
    
    /**
     * コンストラクタ
     */
    public ServerController() {
        this.nodeNum = DEFAULT_NODE_NUM;
        this.config = ConfigurationManager.getInstance();
        this.flyingUavQueue = new LinkedList<>();
        this.uavQueue = new LinkedList<>();
        this.baseDirectoryPath = DEFAULT_BASE_DIR_PATH;
        
        initializeComponents();
    }
    
    /**
     * コンポーネントを初期化する
     */
    private void initializeComponents() {
        // リンク行列の初期化
        linkMatrix = new Link[nodeNum][nodeNum];
        for (int i = 0; i < nodeNum; i++) {
            for (int j = 0; j < nodeNum; j++) {
                linkMatrix[i][j] = new Link();
            }
        }
        
        // ビーコンクラスターの初期化
        beaconCluster = new BeaconCluster(nodeNum);
        
        // 通信関連コンポーネントの初期化
        topologyManager = new NetworkTopologyManagerImpl(nodeNum);
        outputManager = new ResultOutputManagerImpl(linkMatrix, beaconCluster, nodeNum, baseDirectoryPath);
        communicationServer = new CommunicationServerImpl(topologyManager, outputManager);
        
        // UAV管理関連コンポーネントの初期化
        dataRecorder = new FlightDataRecorderImpl(baseDirectoryPath);
        capacityManager = new CapacityManagerImpl(linkMatrix, nodeNum);
        queueManager = new UAVQueueManagerImpl(linkMatrix, beaconCluster, nodeNum);
        flightController = new UAVFlightControllerImpl(linkMatrix, beaconCluster, dataRecorder, queueManager, nodeNum);
        uavManagementServer = new UAVManagementServerImpl(flightController, queueManager, capacityManager, dataRecorder, beaconCluster, nodeNum);
        
        // 経路探索関連コンポーネントの初期化
        numericalSolver = new BiCGSTABSolver(); // デフォルトはBiCGSTAB
        routeAssignmentService = new RouteAssignmentServiceImpl(linkMatrix, nodeNum, outputManager);
        
        // デフォルトの経路探索アルゴリズムはExtendedPhysarumSolver
        routeSearchServer = new ExtendedPhysarumSolverRouteSearcherImpl(linkMatrix, nodeNum, outputManager, routeAssignmentService, numericalSolver);
    }
    
    /**
     * ネットワークトポロジーを設定する
     */
    public void setupNetworkTopology() {
        communicationServer.setNetworkTopology(nodeNum, beaconCluster);
    }
    
    /**
     * 経路探索アルゴリズムを設定する
     * 
     * @param algorithmName アルゴリズム名
     */
    public void setRouteSearchAlgorithm(String algorithmName) {
        switch (algorithmName.toLowerCase()) {
            case "dijkstra":
                routeSearchServer = new DijkstraRouteSearcherImpl(linkMatrix, nodeNum, outputManager);
                break;
            case "physarumsolver":
            case "ps":
                routeSearchServer = new PhysarumSolverRouteSearcherImpl(linkMatrix, nodeNum, outputManager, routeAssignmentService, numericalSolver);
                break;
            case "extendedphysarumsolver":
            case "eps":
                routeSearchServer = new ExtendedPhysarumSolverRouteSearcherImpl(linkMatrix, nodeNum, outputManager, routeAssignmentService, numericalSolver);
                break;
            default:
                System.out.println("Unknown algorithm: " + algorithmName + ". Using ExtendedPhysarumSolver as default.");
                routeSearchServer = new ExtendedPhysarumSolverRouteSearcherImpl(linkMatrix, nodeNum, outputManager, routeAssignmentService, numericalSolver);
        }
    }
    
    /**
     * 数値計算ソルバーを設定する
     * 
     * @param solverName ソルバー名
     */
    public void setNumericalSolver(String solverName) {
        switch (solverName.toLowerCase()) {
            case "bicgstab":
                numericalSolver = new BiCGSTABSolver();
                break;
            case "iccg":
                numericalSolver = new ICCGSolver();
                break;
            default:
                System.out.println("Unknown solver: " + solverName + ". Using BiCGSTAB as default.");
                numericalSolver = new BiCGSTABSolver();
        }
        
        // 経路探索アルゴリズムが数値計算ソルバーを使用する場合は更新
        if (routeSearchServer instanceof PhysarumSolverRouteSearcher) {
            ((PhysarumSolverRouteSearcher) routeSearchServer).setNumericalSolver(numericalSolver);
        } else if (routeSearchServer instanceof ExtendedPhysarumSolverRouteSearcher) {
            ((ExtendedPhysarumSolverRouteSearcher) routeSearchServer).setNumericalSolver(numericalSolver);
        }
    }
    
    /**
     * 経路探索を実行する
     * 
     * @param client クライアント
     * @param numLoop 繰り返し回数
     * @throws IOException 入出力例外
     */
    public void searchRoutes(Client client, int numLoop) throws IOException {
        routeSearchServer.searchAndAssignRoutes(client, flyingUavQueue, uavQueue, numLoop);
    }
    
    /**
     * UAVの飛行を管理する
     */
    public void manageUAVFlight() {
        uavManagementServer.flyUAV(flyingUavQueue, uavQueue);
    }
    
    /**
     * 結果をPajek形式で出力する
     * 
     * @param client クライアント
     * @param eps イプシロン値
     * @param allFlow 全体の流量
     * @param ct カウンター
     * @throws IOException 入出力例外
     */
    public void outputResultsToPajek(Client client, double eps, double allFlow, int ct) throws IOException {
        communicationServer.outputToPajek(client, eps, allFlow, ct);
    }
    
    /**
     * 結果をExcel形式で出力する
     * 
     * @param client クライアント
     * @param ct カウンター
     * @throws IOException 入出力例外
     */
    public void outputResultsToExcel(Client client, int ct) throws IOException {
        communicationServer.outputToExcel(client, ct);
    }
    
    /**
     * 結果をテキスト形式で出力する
     * 
     * @param client クライアント
     * @param ct カウンター
     * @throws IOException 入出力例外
     */
    public void outputResultsToTxt(Client client, int ct) throws IOException {
        communicationServer.outputToTxt(client, ct);
    }
    
    /**
     * 実行カウンターを設定する
     * 
     * @param runCounter 実行カウンター
     */
    public void setRunCounter(int runCounter) {
        outputManager.setRunCounter(runCounter);
    }
    
    /**
     * ノード数を設定する
     * 
     * @param nodeNum ノード数
     */
    public void setNodeNum(int nodeNum) {
        this.nodeNum = nodeNum;
        
        // リンク行列の再初期化
        linkMatrix = new Link[nodeNum][nodeNum];
        for (int i = 0; i < nodeNum; i++) {
            for (int j = 0; j < nodeNum; j++) {
                linkMatrix[i][j] = new Link();
            }
        }
        
        // ビーコンクラスターの再初期化
        beaconCluster = new BeaconCluster(nodeNum);
        
        // 各コンポーネントのノード数を更新
        topologyManager.setNodeNum(nodeNum);
        outputManager.setNodeNum(nodeNum);
        capacityManager.setNodeNum(nodeNum);
        
        // 経路探索関連コンポーネントの更新
        routeAssignmentService = new RouteAssignmentServiceImpl(linkMatrix, nodeNum, outputManager);
        
        // 現在の経路探索アルゴリズムを保持
        String currentAlgorithm = routeSearchServer.getAlgorithmName();
        setRouteSearchAlgorithm(currentAlgorithm);
    }
    
    /**
     * リンク行列を取得する
     * 
     * @return リンク行列
     */
    public Link[][] getLinkMatrix() {
        return linkMatrix;
    }
    
    /**
     * ビーコンクラスターを取得する
     * 
     * @return ビーコンクラスター
     */
    public BeaconCluster getBeaconCluster() {
        return beaconCluster;
    }
    
    /**
     * 通信サーバーを取得する
     * 
     * @return 通信サーバー
     */
    public CommunicationServer getCommunicationServer() {
        return communicationServer;
    }
    
    /**
     * UAV管理サーバーを取得する
     * 
     * @return UAV管理サーバー
     */
    public UAVManagementServer getUavManagementServer() {
        return uavManagementServer;
    }
    
    /**
     * 経路探索サーバーを取得する
     * 
     * @return 経路探索サーバー
     */
    public RouteSearchServer getRouteSearchServer() {
        return routeSearchServer;
    }
    
    /**
     * 飛行中のUAVキューを取得する
     * 
     * @return 飛行中のUAVキュー
     */
    public Queue<Uav> getFlyingUavQueue() {
        return flyingUavQueue;
    }
    
    /**
     * 待機中のUAVキューを取得する
     * 
     * @return 待機中のUAVキュー
     */
    public Queue<Uav> getUavQueue() {
        return uavQueue;
    }
    
    /**
     * 出力先ディレクトリを設定する
     * 
     * @param directoryPath 出力先ディレクトリパス
     */
    public void setOutputDirectory(String directoryPath) {
        this.baseDirectoryPath = directoryPath;
        
        // 各コンポーネントの出力先ディレクトリを更新
        if (outputManager != null) {
            outputManager.setBaseDirectoryPath(directoryPath);
        }
        
        if (dataRecorder != null) {
            ((FlightDataRecorderImpl) dataRecorder).setBaseDirectoryPath(directoryPath);
        }
    }
}
