package server.route;

import client.Client;
import item.Link;
import item.Uav;
import server.communication.ResultOutputManager;
import server.util.ConfigurationManager;

import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 経路割り当てサービスの実装クラス
 */
public class RouteAssignmentServiceImpl implements RouteAssignmentService {
    
    private Link[][] linkMatrix;
    private int nodeNum;
    private double[][] flowCapacity;
    private int[][] tubeFlow;
    private int[][] adjMatrix;
    private int uavCount;
    private int maxPathIndex;
    private int minFlow;
    private ResultOutputManager outputManager;
    private ConfigurationManager config;
    
    /**
     * コンストラクタ
     * 
     * @param linkMatrix リンク行列
     * @param nodeNum ノード数
     * @param outputManager 結果出力管理
     */
    public RouteAssignmentServiceImpl(Link[][] linkMatrix, int nodeNum, ResultOutputManager outputManager) {
        this.linkMatrix = linkMatrix;
        this.nodeNum = nodeNum;
        this.outputManager = outputManager;
        this.config = ConfigurationManager.getInstance();
        this.flowCapacity = new double[nodeNum][nodeNum];
        this.tubeFlow = new int[nodeNum][nodeNum];
        this.adjMatrix = new int[nodeNum][nodeNum];
        this.uavCount = 0;
        this.maxPathIndex = 0;
        this.minFlow = 100;
    }
    
    /**
     * 経路をUAVに割り当てる
     * 
     * @param startNode 開始ノード
     * @param goalNode 目標ノード
     * @param requiredUAVs 必要なUAV数
     * @param client クライアント
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     */
    @Override
    public void assignRoutes(int startNode, int goalNode, int requiredUAVs, Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue) {
        uavCount = 0;
        
        // 初期設定として、Flow_CapacityにQ_tubeFlowを代入,各リンクを流れる流量の整数値をtubeFlowに追加
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
        
        runUAVFlow(startNode, goalNode, requiredUAVs, client, flyingUavQueue, uavQueue);
    }
    
    /**
     * 経路を探索する
     * 
     * @param startNode 開始ノード
     * @param currentNode 現在のノード
     * @param goalNode 目標ノード
     * @param path 経路を格納する配列
     * @param pathIndex 経路配列のインデックス
     * @param passedFlow 通過した流量
     * @return 流量
     */
    @Override
    public int explorePath(int startNode, int currentNode, int goalNode, int[] path, int pathIndex, int passedFlow) {
        // ゴールノードに到達したら流量を返して経路探索を終了
        if (currentNode == goalNode) {
            maxPathIndex = pathIndex; // 正しい最大経路長を記録
            return passedFlow;
        }
        
        // `maxPathIndex` を `pathIndex` と比較して更新
        if (pathIndex > maxPathIndex) {
            maxPathIndex = pathIndex;
        }
        
        // 次のノードを探索し、経路を進む
        for (int nextNode = 0; nextNode < nodeNum; nextNode++) {
            if (adjMatrix[currentNode][nextNode] == 1 && tubeFlow[currentNode][nextNode] > 0) {
                int flow = tubeFlow[currentNode][nextNode]; // 現在ノード間の流量
                
                // 最小フローの計算
                int prevMinFlow = minFlow;  // バックトラックのために保存
                minFlow = (passedFlow == 0) ? flow : Math.min(minFlow, flow);
                
                // 経路に次のノードを追加
                path[pathIndex] = nextNode;
                
                // ゴールに到達した場合、`tubeFlow` を減算
                if (nextNode == goalNode && minFlow > 0) {
                    int nodeA = startNode;
                    for (int i = 0; i <= pathIndex; i++) {
                        int nodeB = path[i];
                        tubeFlow[nodeA][nodeB] -= minFlow;
                        flowCapacity[nodeA][nodeB] -= minFlow;
                        
                        if (tubeFlow[nodeA][nodeB] == 0) {
                            adjMatrix[nodeA][nodeB] = 0;
                        }
                        nodeA = nodeB;
                    }
                }
                
                // 再帰的に経路を探索
                int resultFlow = explorePath(startNode, nextNode, goalNode, path, pathIndex + 1, minFlow);
                if (resultFlow > 0) {
                    return resultFlow; // 成功した場合は流量を返す
                }
                
                // バックトラック処理
                minFlow = prevMinFlow;  // 元の min_Flow を復元
            }
        }
        
        return 0; // 失敗した場合、流量0を返す
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
    public void runUAVFlow(int startNode, int goalNode, int requiredUAVs, Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue) {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        CountDownLatch latch = new CountDownLatch(requiredUAVs);
        boolean flowAvailable = true;
        
        while (uavCount < requiredUAVs && flowAvailable) {
            int previousUAVCount = uavCount;
            minFlow = 100;
            
            int[] path = new int[100]; // より大きなサイズに変更（20から100へ）
            int pathIndex = 0;
            path[pathIndex++] = startNode;
            
            maxPathIndex = pathIndex;  // 初期化を適切に
            
            int flow = explorePath(startNode, startNode, goalNode, path, pathIndex, 0);
            
            if (flow > 0) {
                int pathLength = maxPathIndex;  // 正しい長さを取得
                int[] pathArray = Arrays.copyOf(path, pathLength);  // 正しい長さでコピー
                
                int u = pathArray[0];
                int v = pathArray[1];
                
                double minCapacity = linkMatrix[u][v].getCapacity();
                AtomicInteger flowCounter = new AtomicInteger(0);
                
                for (int f = 0; f < flow; f++) {
                    int currentUAVIndex;
                    
                    synchronized (this) {
                        uavCount++;
                        currentUAVIndex = uavCount - 1;
                    }
                    
                    Uav currentUAV = client.getFlow().getUav(currentUAVIndex);
                    
                    scheduler.schedule(() -> {
                        currentUAV.setPath(pathArray);
                        outputManager.outputRoute(currentUAV, "runUAVFlow");
                        
                        if (flowCounter.get() < minCapacity) {
                            currentUAV.startTimer();
                            currentUAV.setFlyingLink(linkMatrix[u][v]);
                            currentUAV.setPassedLink(linkMatrix[u][v]);
                            flyingUavQueue.add(currentUAV);
                            linkMatrix[u][v].decrementCapacity();
                            flowCounter.incrementAndGet();
                            System.out.println("UAV " + currentUAV.getId() + " is flying from " + u + " to " + v);
                        } else {
                            currentUAV.startWaitingTimer();
                            currentUAV.setStayedBeaconId(u);
                            client.getFlow().getSource().addUav(currentUAV);
                            client.getFlow().getSource().incrementWaitingUavCount();
                            uavQueue.add(currentUAV);
                            System.out.println("UAV " + currentUAV.getId() + " is waiting at " + u + "(" + u + " -> " + v + ")");
                        }
                        
                        latch.countDown();
                    }, f * 2, TimeUnit.SECONDS);
                }
            } else {
                flowAvailable = false;
            }
            
            if (uavCount == requiredUAVs) break;
        }
        
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("スレッドが割り込まれました: " + e.getMessage());
        }
        
        if (!flowAvailable) {
            int needUAV = requiredUAVs - uavCount;
            if (needUAV > 0) {
                System.out.println("全てのUAVに経路が割り当てられませんでした。残り" + needUAV + "台のUAVを再割り当てします。");
                adjustRemainingFlow(needUAV, startNode, goalNode, client, flyingUavQueue, uavQueue);
            }
        }
    }
    
    /**
     * 残りのUAVに経路を割り当てる
     * 
     * @param needUAV 必要なUAV数
     * @param startNode 開始ノード
     * @param goalNode 目標ノード
     * @param client クライアント
     * @param flyingUavQueue 飛行中のUAVキュー
     * @param uavQueue 待機中のUAVキュー
     */
    @Override
    public void adjustRemainingFlow(int needUAV, int startNode, int goalNode, Client client, Queue<Uav> flyingUavQueue, Queue<Uav> uavQueue) {
        int countOfUAV = 0;
        int[] path = new int[100]; // より大きなサイズに変更（20から100へ）
        int pathIndex;
        
        while (countOfUAV < needUAV) {
            pathIndex = 0;
            Arrays.fill(path, 0);
            path[pathIndex++] = startNode;
            
            int currentNode = startNode;
            boolean pathFound = false;
            
            while (currentNode != goalNode) {
                int nextNode = -1;
                double maxCapacity = -1.0;
                
                for (int j = 0; j < nodeNum; j++) {
                    if (tubeFlow[currentNode][j] > 0 && flowCapacity[currentNode][j] >= 1) {
                        if (flowCapacity[currentNode][j] > maxCapacity) {
                            maxCapacity = flowCapacity[currentNode][j];
                            nextNode = j;
                        }
                    }
                }
                
                if (nextNode == -1) {
                    for (int j = 0; j < nodeNum; j++) {
                        if (flowCapacity[currentNode][j] > maxCapacity) {
                            maxCapacity = flowCapacity[currentNode][j];
                            nextNode = j;
                        }
                    }
                    if (nextNode != -1) {
                        tubeFlow[currentNode][nextNode] = 1;
                        flowCapacity[currentNode][nextNode] = 1.0;
                    }
                }
                
                if (nextNode == -1) {
                    break;
                }
                
                path[pathIndex++] = nextNode;
                int flow = tubeFlow[currentNode][nextNode];
                tubeFlow[currentNode][nextNode] -= flow;
                flowCapacity[currentNode][nextNode] -= flow;
                
                currentNode = nextNode;
                
                if (currentNode == goalNode) {
                    pathFound = true;
                    int[] assignedPath = new int[pathIndex];
                    System.arraycopy(path, 0, assignedPath, 0, pathIndex);
                    
                    int u = assignedPath[0];
                    int v = assignedPath[1];
                    double minCapacity = linkMatrix[u][v].getCapacity();
                    
                    for (int uav = 0; uav < flow && countOfUAV < needUAV; uav++) {
                        int currentUAVIndex = uavCount + countOfUAV;
                        Uav currentUAV = client.getFlow().getUav(currentUAVIndex);
                        currentUAV.setPath(assignedPath);
                        outputManager.outputRoute(currentUAV, "remainingFlow");
                        
                        if (countOfUAV < minCapacity) {
                            currentUAV.startTimer();
                            currentUAV.setFlyingLink(linkMatrix[u][v]);
                            currentUAV.setPassedLink(linkMatrix[u][v]);
                            flyingUavQueue.add(currentUAV);
                            linkMatrix[u][v].decrementCapacity();
                            System.out.println("UAV " + currentUAV.getId() + " is flying from " + u + " to " + v + " adjustRemainingFlow");
                        } else {
                            currentUAV.startWaitingTimer();
                            currentUAV.setStayedBeaconId(u);
                            client.getFlow().getSource().addUav(currentUAV);
                            client.getFlow().getSource().incrementWaitingUavCount();
                            uavQueue.add(currentUAV);
                            System.out.println("UAV " + currentUAV.getId() + " is waiting at " + u + "(" + u + " -> " + v + ") adjustRemainingFlow");
                        }
                        countOfUAV++;
                    }
                }
            }
            
            if (!pathFound) {
                System.out.println("有効な経路が見つかりませんでした");
                break;
            }
        }
        
        uavCount += countOfUAV; // UAV_count を適切に更新
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
        this.flowCapacity = new double[nodeNum][nodeNum];
        this.tubeFlow = new int[nodeNum][nodeNum];
        this.adjMatrix = new int[nodeNum][nodeNum];
    }
    
    /**
     * 結果出力管理を設定する
     * 
     * @param outputManager 結果出力管理
     */
    public void setOutputManager(ResultOutputManager outputManager) {
        this.outputManager = outputManager;
    }
}
