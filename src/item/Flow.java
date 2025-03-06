package item;

import java.util.ArrayList;

public class Flow {
    Beacon source;
    Beacon destination;
    double theNumberOfUAV;
    private Uav[] uavList;


    public Flow(Beacon source, Beacon destination, double theNumberOfUAV) {
        this.source = source;
        this.destination = destination;
        this.theNumberOfUAV = theNumberOfUAV;
    }

    public void setUavList(Uav[] uavList) {
        this.uavList = uavList;
    }

    public Uav[] getUavList() {
        return uavList;
    }
    //i番目のuavを返す
    public Uav getUav(int i) {
        return uavList[i];
    }

    public Beacon getSource() {
        return source;
    }

    public Beacon getDestination() {
        return destination;
    }

    public double getTheNumberOfUAV() {
        return theNumberOfUAV;
    }

}
