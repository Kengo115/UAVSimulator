package item;


import java.util.Random;

//Beaconクラスを複数保持するクラス
public class BeaconCluster {
    private final Beacon[] beaconList;
    private final int beaconNum;

    //コンストラクタ
    public BeaconCluster(int beaconNum) {
        this.beaconNum = beaconNum;
        beaconList = new Beacon[beaconNum];

        Random random = new Random(10);
        //指定された数だけランダムにBeaconを生成
        for (int i = 0; i < beaconNum; i++) {
            Beacon beacon = new Beacon(random.nextDouble(), random.nextDouble(), i);
            beaconList[i] = beacon;
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
