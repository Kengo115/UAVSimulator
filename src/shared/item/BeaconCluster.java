package shared.item;


import shared.network.TopologyFileReader;

import java.util.List;
import java.util.Random;

//Beaconクラスを複数保持するクラス
public class BeaconCluster {
    private final Beacon[] beaconList;
    private final int beaconNum;

    /**
     * コンストラクタ（デフォルト：ハードコードされた座標）
     * @param beaconNum ビーコン数
     */
    public BeaconCluster(int beaconNum) {
        this.beaconNum = beaconNum;
        beaconList = new Beacon[beaconNum];

        //手動で座標位置を入力
        beaconList[0] = new Beacon(0.1, 0.4, 0);
        beaconList[1] = new Beacon(0.1, 0.7, 1);
        beaconList[2] = new Beacon(0.5, 0.1, 2);
        beaconList[3] = new Beacon(0.5, 0.4, 3);
        beaconList[4] = new Beacon(0.5, 0.7, 4);
        beaconList[5] = new Beacon(0.9, 0.4, 5);

        /**
        Random random = new Random(10);
        //指定された数だけランダムにBeaconを生成
        for (int i = 0; i < beaconNum; i++) {
            Beacon beacon = new Beacon(random.nextDouble(), random.nextDouble(), i);
            beaconList[i] = beacon;
        }
        */
    }

    /**
     * コンストラクタ（外部ファイルから読み込んだトポロジデータから初期化）
     * @param topologyData トポロジデータ
     */
    public BeaconCluster(TopologyFileReader.TopologyData topologyData) {
        this.beaconNum = topologyData.nodeCount;
        this.beaconList = new Beacon[beaconNum];

        // トポロジデータからビーコンを生成
        List<TopologyFileReader.NodeInfo> nodes = topologyData.nodes;
        for (int i = 0; i < beaconNum; i++) {
            TopologyFileReader.NodeInfo nodeInfo = nodes.get(i);
            beaconList[i] = new Beacon(nodeInfo.x, nodeInfo.y, nodeInfo.id);
        }
    }

    //Beaconを返す
    public Beacon getBeacon(int i) {
        return beaconList[i];
    }

    //BeaconClusterを返す
    public Beacon[] getBeaconList() {
        return beaconList;
    }

    //Beaconの数を返す
    public int getBeaconNum() {
        return beaconNum;
    }

}
