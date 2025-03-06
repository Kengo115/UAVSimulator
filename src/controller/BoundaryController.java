package controller;

import client.Client;
import client.ClientController;
import item.Beacon;
import item.BeaconCluster;
import item.Flow;
import item.Uav;
import server.PhysarumSolver;
import server.UAVFlyScheduler;

import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;


public class BoundaryController {
    private static int num_loop = 1000;
    private static int nodeNum;
    //ビーコンクラスタークラスを生成
    static BeaconCluster beaconCluster;
    public Beacon[] beaconList;
    static PhysarumSolver solver;
    static Client client;
    static ClientController clientController = new ClientController();
    static Queue<Client> passedClient = new LinkedList<>();
    static Queue<Uav> uavQueue = new LinkedList<>();
    static Queue<Uav> flyingUavQueue = new LinkedList<>();
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
        solver.setLink(nodeNum, beaconCluster);
    }

    public void setNodeNum(int nodeNum){
        this.nodeNum = nodeNum;
    }

    public int getNodeNum() {
        return nodeNum;
    }

    //sourceId, destinationId, uavNumをランダムに決める
    public Client createRandomClient(){
        int sourceId = (int)(Math.random() * nodeNum);
        int destinationId = (int)(Math.random() * nodeNum);
        while(sourceId == destinationId){
            destinationId = (int)(Math.random() * nodeNum);
        }
        Beacon source = beaconCluster.getBeacon(sourceId);
        Beacon destination = beaconCluster.getBeacon(destinationId);

        int uavNum = 1 + (int)(Math.random() * 14);
        //flowListにsource, destination, uavNumを格納
        flow = new Flow(source, destination, uavNum);

        Client client = new Client(flow, 1);
        clientController.addClient(client);

        return client;
    }

    //クライアントを生成する関数
    public Client createClient1() {
        int sourceId = 0;
        int destinationId = 5;
        Beacon source = beaconCluster.getBeacon(sourceId);
        Beacon destination = beaconCluster.getBeacon(destinationId);

        int uavNum = 1;
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

        int uavNum = 16;
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

        int uavNum = 18;
        //flowListにsource, destination, uavNumを格納
        flow = new Flow(source, destination, uavNum);

        Client client = new Client(flow, 3);
        clientController.addClient(client);

        return client;
    }


    public void routeRequest(Client client) throws IOException {
        //PSを実行
        solver.nodeConfigureToPajek(filePath, client, beaconCluster);
        solver.run(client, clientController, flyingUavQueue, uavQueue);
    }

    public static void main(String[] args) {
        BoundaryController boundaryController = new BoundaryController();
        boundaryController.setNodeNum(6);
        solver = new PhysarumSolver(nodeNum);
        beaconCluster = new BeaconCluster(nodeNum);

        try {
            boundaryController.setNetworkTopology();

            client = boundaryController.createClient1();
            boundaryController.routeRequest(client);
            synchronized (passedClient) {
                passedClient.add(client);
                System.out.println("クライアント1をpassedClientに追加しました");
            }
            clientController.startTimer();

            /**
            //UAVの飛行を全て終えたクライアントをdequeueする
            // 12秒待機してから次の処理に移る
            try {
                Thread.sleep(17000); // 12秒待機
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Thread was interrupted, failed to complete wait");
            }
             */

            UAVFlyScheduler.startFlyUAVUpdates(flyingUavQueue, uavQueue, clientController);

            /**
            client = boundaryController.createClient2();//ここでクライアントタイマーが停止したと考えられる
            //ここではクライアントタイマーはすでに停止
            boundaryController.routeRequest(client);
            synchronized (passedClient) {
                passedClient.add(client);
                System.out.println("クライアント2をpassedClientに追加しました");
            }

            try {
                Thread.sleep(17000); // 12秒待機
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
             */
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
