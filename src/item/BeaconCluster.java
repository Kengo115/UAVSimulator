package item;

import java.util.ArrayList;
import java.util.Random;

//Beaconクラスを複数保持するクラス
public class BeaconCluster {
    private final Beacon[] beaconList;
    private final int beaconNum;

    //コンストラクタ
    public BeaconCluster(int beaconNum) {
        this.beaconNum = beaconNum;
        beaconList = new Beacon[beaconNum];

        /**
        Random random = new Random();
        //指定された数だけランダムにBeaconを生成
        for (int i = 0; i < beaconNum; i++) {
            Beacon beacon = new Beacon(random.nextDouble(), random.nextDouble(), i);
            beaconList.add(beacon);
        }
         */
        //手動で座標位置を入力
        beaconList[0] = new Beacon(0.1, 0.4, 0);
        beaconList[1] = new Beacon(0.1, 0.7, 1);
        beaconList[2] = new Beacon(0.5, 0.1, 2);
        beaconList[3] = new Beacon(0.5, 0.4, 3);
        beaconList[4] = new Beacon(0.5, 0.7, 4);
        beaconList[5] = new Beacon(0.9, 0.4, 5);
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
