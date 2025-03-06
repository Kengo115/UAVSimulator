package client;

import item.Flow;
import item.Uav;

import java.util.Random;

public class Client {
    private Flow flow;
    private int Id;
    Random random = new Random(12345);

    private int finishFlyingCounter = 0;

    public Client(Flow flow, int id) {
        this.flow = flow;
        this.Id = id;
        createUav(flow);
    }

    //UAV数だけUAVを生成
    public void createUav(Flow flow) {
        Uav[] uavList= new Uav[((int) flow.getTheNumberOfUAV())];
        //UAV数だけUAVを生成
        for (int i = 0; i < flow.getTheNumberOfUAV(); i++) {
            double speed = 14 + (random.nextDouble() * 16);// 8~16の範囲に設定

            Uav uav = new Uav(speed, flow.getSource().getX(), flow.getSource().getY(), i, flow.getSource(), flow.getDestination(), this.Id);
            uavList[i] = uav;
        }

        flow.setUavList(uavList);
    }

    public int getFinishFlyingCounter() {
        return finishFlyingCounter;
    }

    public void setFinishFlyingCounter(int finishFlyingCounter) {
        this.finishFlyingCounter = finishFlyingCounter;
    }

    public void incrementFinishFlyingCounter() {
        finishFlyingCounter++;
    }

    public Flow getFlow() {
        return flow;
    }

    public int getId() {
        return Id;
    }

    public void setId(int id) {
        Id = id;
    }
}
