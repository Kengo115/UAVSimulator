package old_controller;

import client.Client;
import client.ClientController;
import item.Beacon;
import item.BeaconCluster;
import item.Flow;
import item.Uav;
//import server.ServerController;
//import server.UAVFlyScheduler;

import old_server.ServerController;
import old_server.UAVFlyScheduler;

import java.io.*;
import java.util.LinkedList;
import java.util.Queue;


public class BoundaryController {
    private static int num_loop = 1000;
    private static int nodeNum;
    //ビーコンクラスタークラスを生成
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

    //ネットワークトポロジーを設定する関数
    public void setNetworkTopology() throws IOException {
        //ビーコンクラスタークラスを取得
        beaconList = new Beacon[nodeNum];
        beaconList = beaconCluster.getBeaconList();
        //ビーコンの情報を設定
        setLink();
    }

    private void setLink(){
        server.setLink(nodeNum, beaconCluster);
        //server.setLink_random(nodeNum, beaconCluster);
    }

    public void setNodeNum(int nodeNum){
        this.nodeNum = nodeNum;
    }

    public int getNodeNum() {
        return nodeNum;
    }
    //クライアントを生成する関数
    public Client createClient1() {
        int sourceId = 0;
        int destinationId = 5;
        Beacon source = beaconCluster.getBeacon(sourceId);
        Beacon destination = beaconCluster.getBeacon(destinationId);

        int uavNum = 12;
        //flowListにsource, destination, uavNumを格納
        flow = new Flow(source, destination, uavNum);

        Client client = new Client(flow, 1);
        clientController.addClient(client);

        return client;
    }

    public Client createClient2(){
        int sourceId = 0;
        int destinationId = 5;
        Beacon source = beaconCluster.getBeacon(sourceId);
        Beacon destination = beaconCluster.getBeacon(destinationId);

        int uavNum = 12;
        //flowListにsource, destination, uavNumを格納
        flow = new Flow(source, destination, uavNum);

        Client client = new Client(flow, 2);
        clientController.addClient(client);

        return client;
    }

    public Client createClient3(){
        int sourceId = 2;
        int destinationId = 4;
        Beacon source = beaconCluster.getBeacon(sourceId);
        Beacon destination = beaconCluster.getBeacon(destinationId);

        int uavNum = 14;
        //flowListにsorrce, destination, uavNumを格納
        flow = new Flow(source, destination, uavNum);

        Client client = new Client(flow, 3);
        clientController.addClient(client);

        return client;
    }


    //sourceId, destinationId, uavNumをランダムに決める
    public Client createRandomClient(){
        /**
        int sourceId = (int)(Math.random() * nodeNum);
        int destinationId = (int)(Math.random() * nodeNum);
         */

        if(clientId == 1) {
            int sourceId = 0;
            int destinationId = 5;
            while (sourceId == destinationId) {
                destinationId = (int) (Math.random() * nodeNum);
            }
            Beacon source = beaconCluster.getBeacon(sourceId);
            Beacon destination = beaconCluster.getBeacon(destinationId);

            int uavNum = 15; //+ (int)(Math.random() * 20);
            //flowListにsource, destination, uavNumを格納
            flow = new Flow(source, destination, uavNum);

            Client client = new Client(flow, clientId);
            clientController.addClient(client);
            clientId++;
            return client;
        }else if(clientId == 2){
            int sourceId = 0;
            int destinationId = 5;
            while (sourceId == destinationId) {
                destinationId = (int) (Math.random() * nodeNum);
            }
            Beacon source = beaconCluster.getBeacon(sourceId);
            Beacon destination = beaconCluster.getBeacon(destinationId);

            int uavNum = 15; //+ (int)(Math.random() * 20);
            //flowListにsource, destination, uavNumを格納
            flow = new Flow(source, destination, uavNum);

            Client client = new Client(flow, clientId);
            clientController.addClient(client);
            clientId++;
            return client;
        }else{
            int sourceId = 2;
            int destinationId = 4;
            while (sourceId == destinationId) {
                destinationId = (int) (Math.random() * nodeNum);
            }
            Beacon source = beaconCluster.getBeacon(sourceId);
            Beacon destination = beaconCluster.getBeacon(destinationId);

            int uavNum = 15; //+ (int)(Math.random() * 20);
            //flowListにsource, destination, uavNumを格納
            flow = new Flow(source, destination, uavNum);

            Client client = new Client(flow, clientId);
            clientController.addClient(client);
            clientId++;
            return client;
        }
    }

    public void routeRequest(Client client) throws IOException {
        server.nodeConfigureToPajek(filePath, client, beaconCluster);
        /**
         * UAVの経路探索方法を選択
         * ServerController内の出力先も適宜変更
         */
        server.run_EPS(client, flyingUavQueue, uavQueue, clientController, num_loop);
        //server.run_PS(client, flyingUavQueue, uavQueue, clientController, num_loop);
        //server.run_Dijkstra(client, clientController,flyingUavQueue, uavQueue);
    }

    public static void main(String[] args) {
        BoundaryController boundaryController = new BoundaryController();
        boundaryController.setNodeNum(6);
        //boundaryController.setNodeNum(20);
        server = new ServerController(nodeNum);
        beaconCluster = new BeaconCluster(nodeNum);
/**
        //事業者指定実験
        try {
            boundaryController.setNetworkTopology();

            client = boundaryController.createClient1();
            boundaryController.routeRequest(client);
            synchronized (passedClient) {
                passedClient.add(client);
                System.out.println("クライアント1をpassedClientに追加しました");
            }

            clientController.startTimer();


            //UAVの飛行を全て終えたクライアントをdequeueする
            // 12秒待機してから次の処理に移る
            try {
                Thread.sleep(30000); // 30秒待機
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Thread was interrupted, failed to complete wait");
            }


            UAVFlyScheduler.startFlyUAVUpdates(flyingUavQueue, uavQueue, clientController);


            client = boundaryController.createClient2();//ここでクライアントタイマーが停止したと考えられる
            //ここではクライアントタイマーはすでに停止
            boundaryController.routeRequest(client);
            synchronized (passedClient) {
                passedClient.add(client);
                System.out.println("クライアント2をpassedClientに追加しました");
            }

            //ここではクライアントタイマーすでに停止
            try {
                Thread.sleep(30000); // 30秒待機
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Thread was interrupted, failed to complete wait");
            }


            client = boundaryController.createClient3();
            boundaryController.routeRequest(client);
            synchronized (passedClient) {
                passedClient.add(client);
                System.out.println("クライアント3をpassedClientに追加しました");
            }
            //passedClientが空になるまでUAVFlySchedulerを実行

        } catch (IOException e) {
            e.printStackTrace();
        }
*/
        //ランダムクライアント生成実験
        try {
            boundaryController.setNetworkTopology(); // ネットワークの初期化

            // 標準入力からクライアントの生成回数を取得
            System.out.println("生成するクライアントの数を入力してください:");
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            int clientCount = Integer.parseInt(reader.readLine());

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
                    writer.write(String.format("%d,%d,%f\n",client.getFlow().getSource().getId(), client.getFlow().getDestination().getId(), client.getFlow().getTheNumberOfUAV()));
                } catch (IOException e) {
                    System.err.println("ファイル書き込みエラー: " + e.getMessage());
                }

                // 次のクライアント生成まで60秒待機
                if (i < clientCount - 1) { // 最後のクライアント以外
                    Thread.sleep(40000); // 60秒待機
                }
            }

            System.out.println("すべてのクライアント生成と処理が完了しました。");
            if(flyingUavQueue.isEmpty() && uavQueue.isEmpty()){
                UAVFlyScheduler.stopFlyUAVUpdates(clientController);
            }

        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("メインスレッドが中断されました。");
        }
    }
}
