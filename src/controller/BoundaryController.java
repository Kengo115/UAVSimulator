package controller;

import client.Client;
import client.ClientController;
import item.Beacon;
import item.BeaconCluster;
import item.Flow;
import item.Uav;
import server.controller.ServerController;
import server.uav.UAVFlyScheduler;
import server.util.LogManager;

import java.io.*;
import java.util.LinkedList;
import java.util.Queue;


public class BoundaryController {
    private static int num_loop = 500;
    private static int nodeNum;
    // ビーコンクラスタークラスを生成
    static BeaconCluster beaconCluster;
    public Beacon[] beaconList;
    static ServerController server;
    static Client client;
    static int clientId = 1;
    static ClientController clientController = new ClientController();
    static Queue<Uav> uavQueue = new LinkedList<>();
    static Queue<Uav> flyingUavQueue = new LinkedList<>();
    static Queue<Client> passedClient = new LinkedList<>();
    Flow flow;

    String filePath = "src/result/practice.net";

    private static int trial = 5;

    // ネットワークトポロジーを設定する関数
    public void setNetworkTopology() throws IOException {
        // ビーコンクラスタークラスを取得
        beaconList = new Beacon[nodeNum];
        beaconList = beaconCluster.getBeaconList();
        // ビーコンの情報を設定
        setLink();
    }

    private void setLink() {
        server.setLink(nodeNum, beaconCluster);
    }

    public void setNodeNum(int nodeNum) {
        this.nodeNum = nodeNum;
    }

    public int getNodeNum() {
        return nodeNum;
    }

    // クライアントを生成する関数
    public Client createClient1() {
        int sourceId = 0;
        int destinationId = 5;
        Beacon source = beaconCluster.getBeacon(sourceId);
        Beacon destination = beaconCluster.getBeacon(destinationId);

        int uavNum = 12;
        // flowListにsource, destination, uavNumを格納
        flow = new Flow(source, destination, uavNum);

        Client client = new Client(flow, 1);
        clientController.addClient(client);

        return client;
    }

    public Client createClient2() {
        int sourceId = 0;
        int destinationId = 5;
        Beacon source = beaconCluster.getBeacon(sourceId);
        Beacon destination = beaconCluster.getBeacon(destinationId);

        int uavNum = 12;
        // flowListにsource, destination, uavNumを格納
        flow = new Flow(source, destination, uavNum);

        Client client = new Client(flow, 2);
        clientController.addClient(client);

        return client;
    }

    public Client createClient3() {
        int sourceId = 2;
        int destinationId = 4;
        Beacon source = beaconCluster.getBeacon(sourceId);
        Beacon destination = beaconCluster.getBeacon(destinationId);

        int uavNum = 14;
        // flowListにsorrce, destination, uavNumを格納
        flow = new Flow(source, destination, uavNum);

        Client client = new Client(flow, 3);
        clientController.addClient(client);

        return client;
    }

    // sourceId, destinationId, uavNumをランダムに決める
    public Client createRandomClient() {
        if (clientId == 1) {
            int sourceId = 0;
            int destinationId = 5;
            while (sourceId == destinationId) {
                destinationId = (int) (Math.random() * nodeNum);
            }
            Beacon source = beaconCluster.getBeacon(sourceId);
            Beacon destination = beaconCluster.getBeacon(destinationId);

            int uavNum = 40; // + (int)(Math.random() * 20);
            // flowListにsource, destination, uavNumを格納
            flow = new Flow(source, destination, uavNum);

            Client client = new Client(flow, clientId);
            clientController.addClient(client);
            clientId++;
            return client;
        } else if (clientId == 2) {
            int sourceId = 0;
            int destinationId = 5;
            while (sourceId == destinationId) {
                destinationId = (int) (Math.random() * nodeNum);
            }
            Beacon source = beaconCluster.getBeacon(sourceId);
            Beacon destination = beaconCluster.getBeacon(destinationId);

            int uavNum = 10; // + (int)(Math.random() * 20);
            // flowListにsource, destination, uavNumを格納
            flow = new Flow(source, destination, uavNum);

            Client client = new Client(flow, clientId);
            clientController.addClient(client);
            clientId++;
            return client;
        } else {
            int sourceId = 2;
            int destinationId = 4;
            while (sourceId == destinationId) {
                destinationId = (int) (Math.random() * nodeNum);
            }
            Beacon source = beaconCluster.getBeacon(sourceId);
            Beacon destination = beaconCluster.getBeacon(destinationId);

            int uavNum = 10; // + (int)(Math.random() * 20);
            // flowListにsource, destination, uavNumを格納
            flow = new Flow(source, destination, uavNum);

            Client client = new Client(flow, clientId);
            clientController.addClient(client);
            clientId++;
            return client;
        }
    }

    // 経路探索手法の列挙型
    public enum RouteSearchMethod {
        DIJKSTRA(1, "Dijkstra"),
        PS(2, "PS"),
        EPS(3, "EPS"),
        HYBRID(4, "HYBRID");
        
        private final int id;
        private final String name;
        
        RouteSearchMethod(int id, String name) {
            this.id = id;
            this.name = name;
        }
        
        public int getId() {
            return id;
        }
        
        public String getName() {
            return name;
        }
        
        public static RouteSearchMethod fromId(int id) {
            for (RouteSearchMethod method : values()) {
                if (method.getId() == id) {
                    return method;
                }
            }
            return EPS; // デフォルトはEPS
        }
    }
    
    // ログモードの列挙型
    public enum LoggingMode {
        DISABLED(1, "無効"),
        ENABLED(2, "有効");
        
        private final int id;
        private final String name;
        
        LoggingMode(int id, String name) {
            this.id = id;
            this.name = name;
        }
        
        public int getId() {
            return id;
        }
        
        public String getName() {
            return name;
        }
        
        public static LoggingMode fromId(int id) {
            for (LoggingMode mode : values()) {
                if (mode.getId() == id) {
                    return mode;
                }
            }
            return DISABLED; // デフォルトは無効
        }
    }
    
    // 現在選択されている経路探索手法
    private static RouteSearchMethod currentMethod = RouteSearchMethod.EPS;
    
    // 現在選択されているログモード
    private static LoggingMode currentLoggingMode = LoggingMode.DISABLED;
    
    /**
     * 経路探索手法を設定する
     * @param method 経路探索手法
     */
    public static void setRouteSearchMethod(RouteSearchMethod method) {
        currentMethod = method;
    }
    
    /**
     * 現在の経路探索手法を取得する
     * @return 経路探索手法
     */
    public static RouteSearchMethod getCurrentMethod() {
        return currentMethod;
    }
    
    /**
     * ログモードを設定する
     * @param mode ログモード
     */
    public static void setLoggingMode(LoggingMode mode) {
        currentLoggingMode = mode;
        LogManager.getInstance().setLoggingEnabled(mode == LoggingMode.ENABLED);
    }
    
    /**
     * 現在のログモードを取得する
     * @return ログモード
     */
    public static LoggingMode getCurrentLoggingMode() {
        return currentLoggingMode;
    }

    public void routeRequest(Client client) throws IOException {
        // Pajekファイルにネットワークトポロジーを出力
        server.nodeConfigureToPajek(filePath, client, beaconCluster);
        
        // 選択された経路探索手法に基づいて実行
        switch (currentMethod) {
            case DIJKSTRA:
                server.run_Dijkstra(client, clientController, flyingUavQueue, uavQueue);
                break;
            case PS:
                server.run_PS(client, clientController, flyingUavQueue, uavQueue, num_loop);
                break;
            case HYBRID:
                server.run_Hybrid(client, clientController, flyingUavQueue, uavQueue, num_loop);
                break;
            case EPS:
            default:
                server.run_EPS(client, clientController, flyingUavQueue, uavQueue, num_loop);
                break;
        }
    }

    public static void main(String[] args) {
        BoundaryController boundaryController = new BoundaryController();
        boundaryController.setNodeNum(6);
        beaconCluster = new BeaconCluster(nodeNum);
        server = new ServerController(nodeNum, beaconCluster);
        
        // 経路探索アルゴリズムを初期化
        server.initializeRouteSearchers();

        // ランダムクライアント生成実験
        try {
            boundaryController.setNetworkTopology(); // ネットワークの初期化
            
            // 経路探索手法を選択
            System.out.println("経路探索手法を選択してください:");
            System.out.println("1: Dijkstra法");
            System.out.println("2: PhysarumSolver法 (PS)");
            System.out.println("3: ExtendedPhysarumSolver法 (EPS)");
            System.out.println("4: ハイブリッド法 (HYBRID: EPS+PS)");
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            int methodChoice = 3; // デフォルトはEPS
            try {
                String input = reader.readLine();
                if (!input.trim().isEmpty()) {
                    methodChoice = Integer.parseInt(input);
                    if (methodChoice < 1 || methodChoice > 4) {
                        System.out.println("無効な選択です。ExtendedPhysarumSolver法 (EPS) を使用します。");
                        methodChoice = 3;
                    }
                }
            } catch (NumberFormatException e) {
                System.out.println("無効な入力です。ExtendedPhysarumSolver法 (EPS) を使用します。");
            }
            
            // 選択された経路探索手法を設定
            RouteSearchMethod selectedMethod = RouteSearchMethod.fromId(methodChoice);
            setRouteSearchMethod(selectedMethod);
            System.out.println(selectedMethod.getName() + " を使用します。");
            
            // ログモードを選択
            System.out.println("ログをファイルに記録しますか？");
            System.out.println("1: 記録しない");
            System.out.println("2: 記録する (log/simulator.logに記録)");
            
            int loggingChoice = 1; // デフォルトは記録しない
            try {
                String input = reader.readLine();
                if (!input.trim().isEmpty()) {
                    loggingChoice = Integer.parseInt(input);
                    if (loggingChoice < 1 || loggingChoice > 2) {
                        System.out.println("無効な選択です。ログは記録しません。");
                        loggingChoice = 1;
                    }
                }
            } catch (NumberFormatException e) {
                System.out.println("無効な入力です。ログは記録しません。");
            }
            
            // 選択されたログモードを設定
            LoggingMode selectedLoggingMode = LoggingMode.fromId(loggingChoice);
            setLoggingMode(selectedLoggingMode);
            System.out.println("ログ記録: " + selectedLoggingMode.getName());
            
            if (selectedLoggingMode == LoggingMode.ENABLED) {
                System.out.println("ログは log/simulator.log に記録されます。");
                System.out.println("別のターミナルで 'tail -f log/simulator.log' を実行すると、リアルタイムでログを確認できます。");
            }
            
            // 標準入力からクライアントの生成回数を取得
            System.out.println("生成するクライアントの数を入力してください:");
            int clientCount = 1; // デフォルトは1
            try {
                String input = reader.readLine();
                if (!input.trim().isEmpty()) {
                    clientCount = Integer.parseInt(input);
                }
            } catch (NumberFormatException e) {
                System.out.println("無効な入力です。クライアント数を1に設定します。");
            }

            // 指定された回数だけクライアントを生成して処理
            for (int i = 0; i < clientCount; i++) {
                System.out.println("クライアント " + (i + 1) + " を生成しています...");
                client = boundaryController.createRandomClient();
                boundaryController.routeRequest(client);

                synchronized (passedClient) {
                    passedClient.add(client);
                }

                // 最初のクライアントに対してタイマーを起動
                if (i == 0) {
                    clientController.startTimer();
                }

                // UAVスケジューリングを更新
                UAVFlyScheduler.startFlyUAVUpdates(flyingUavQueue, uavQueue, clientController);

                String dirPath = "src/result/client";
                String filePath = dirPath + "/client.txt";

                // ディレクトリが存在しない場合は作成
                File dir1 = new File(dirPath);
                if (!dir1.exists()) {
                    dir1.mkdirs();
                }

                try (FileWriter writer = new FileWriter(filePath, true)) {
                    // ファイルが空の場合、ヘッダーを追加
                    File file = new File(filePath);
                    if (file.length() == 0) {
                        writer.write("source,dist,requiredUAVs\n");
                    }
                    // 行を書き込み
                    writer.write(String.format("%d,%d,%f\n", client.getFlow().getSource().getId(), client.getFlow().getDestination().getId(), client.getFlow().getTheNumberOfUAV()));
                } catch (IOException e) {
                    System.err.println("ファイル書き込みエラー: " + e.getMessage());
                }

                // 次のクライアント生成まで40秒待機
                if (i < clientCount - 1) { // 最後のクライアント以外
                    Thread.sleep(40000); // 40秒待機
                }
            }

            LogManager.getInstance().log("すべてのクライアント生成と処理が完了しました。");
            if (flyingUavQueue.isEmpty() && uavQueue.isEmpty()) {
                UAVFlyScheduler.stopFlyUAVUpdates(clientController);
            }
            
            // プログラム終了時にログマネージャーを閉じるようにシャットダウンフックを追加
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LogManager.getInstance().log("プログラムを終了します。ログを閉じます。");
                LogManager.getInstance().close();
            }));

        } catch (IOException e) {
            LogManager.getInstance().error("IOエラーが発生しました", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LogManager.getInstance().error("メインスレッドが中断されました", e);
        }
    }
}
