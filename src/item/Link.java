package item;

public class Link {
    //beacon1-beacon2のリンク情報
    private Beacon beacon1;
    private Beacon beacon2;
    private double distance;
    private double capacity;
    private double initCapacity;
    private int flyingUAV;
    private double congestionRate;
    // リンクの基本パラメータ
    private double Q_tubeFlow;
    private double D_tubeThickness;
    private double L_tubeLength;


    public void setLink(Beacon beacon1, Beacon beacon2, double capacity) {
        this.beacon1 = beacon1;
        this.beacon2 = beacon2;
        this.capacity = capacity;
        this.initCapacity = capacity;
    }


    public void setDistance(double distance) {
        this.distance = distance;
    }
    public void setD_tubeThickness(double D_tubeThickness) {
        this.D_tubeThickness = D_tubeThickness;
    }
    public void setL_tubeLength(double L_tubeLength) {
        this.L_tubeLength = L_tubeLength;
    }
    public void setQ_tubeFlow(double q_tubeFlow) {
        Q_tubeFlow = q_tubeFlow;
    }
    public void setCongestionRate(double congestionRate) {
        this.congestionRate = congestionRate;
    }
    public void setFlyingUAV(int flyingUAV) {
        this.flyingUAV = flyingUAV;
    }

    //接続ビーコンを返す
    public Beacon getBeacon1() {
        return beacon1;
    }
    public Beacon getBeacon2() {
        return beacon2;
    }
    public void setCapacity(double capacity) {
        this.capacity = capacity;
    }

    //容量を返す
    public double getCapacity() {
        return capacity;
    }
    //飛行中のUAV数を返す
    public double getFlyingUAV() {
        return flyingUAV;
    }
    //混雑率を返す
    public double getCongestionRate() {
        return congestionRate;
    }
    //管の太さを返す
    public double getD_tubeThickness() {
        return D_tubeThickness;
    }
    //管の長さを返す
    public double getL_tubeLength() {
        return L_tubeLength;
    }

    public double getDistance() {
        return distance;
    }

    public double getQ_tubeFlow() {
        return Q_tubeFlow;
    }

    //混雑率を計算する
    public void calcCongestionRate() {
        congestionRate = flyingUAV / capacity;
    }

    public double getInitCapacity() {
        return initCapacity;
    }
}
