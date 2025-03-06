package item;

public class Uav {
    private double speed;
    private double x;
    private double y;
    private int id;
    private Beacon Source;
    private Beacon Destination;
    private UAVTimer uavTimer = new UAVTimer();
    private UAVTimer waitingTimer = new UAVTimer();
    private int[] path;
    private boolean isFlying = false;
    private boolean isWaiting = false;
    private double flightTime = 0;
    private int clientId;
    private int stayedBeaconId = -1;
    private Link flyingLink;
    private Link passedLink;

    //コンストラクタ
    public Uav(double speed, double x, double y, int id, Beacon Source, Beacon Destination, int clientId) {
        this.speed = speed;
        this.x = x;
        this.y = y;
        this.id = id;
        this.Source = Source;
        this.Destination = Destination;
        this.clientId = clientId;
    }
    public void setPath(int[] path) {
        this.path = path;
    }

    public int[] getPath() {
        return path;
    }


    public void startTimer(){
        isFlying = true;
        uavTimer.start();
    }

    public void startWaitingTimer(){
        isWaiting = true;
        waitingTimer.start();
    }

    public void cancelWaitingTimer(){
        isWaiting = false;
        waitingTimer.cancel();
    }

    public void stopTimer(){
        uavTimer.stop();
    }

    public void stopWaitingTimer(){
        waitingTimer.stop();
    }

    public long getFlightTime(){
        return uavTimer.getFlightTime();
    }

    public long getWaitingTime(){
        return waitingTimer.getFlightTime();
    }

    public void resetTimer() {
        uavTimer.reset();
    }

    public void cancelTimer() {
        isFlying = false;
        uavTimer.cancel();
    }


    public boolean getIsFlying() {
        return isFlying;
    }
    //速度を返す
    public double getSpeed() {
        return speed;
    }
    //x座標を返す
    public double getX() {
        return x;
    }
    //y座標を返す
    public double getY() {
        return y;
    }
    //idを返す
    public int getId() {
        return id;
    }
    //出発地を返す
    public Beacon getSource() {
        return Source;
    }
    //到着地を返す
    public Beacon getDistination() {
        return Destination;
    }

    public void setFlightTime(double flightTime) {
        this.flightTime = flightTime;
    }

    public double getFlightTime(double flightTime) {
        return flightTime;
    }

    public int getClientId() {
        return clientId;
    }

    public int getStayedBeaconId() {
        return stayedBeaconId;
    }

    public void setStayedBeaconId(int stayedBeaconId) {
        this.stayedBeaconId = stayedBeaconId;
    }

    public void setFlyingLink(Link flyingLink) {
        this.flyingLink = flyingLink;
    }

    public Link getFlyingLink() {
        return flyingLink;
    }

    public void setPassedLink(Link passedLink) {
        this.passedLink = passedLink;
    }

    public Link getPassedLink() {
        return passedLink;
    }

}
