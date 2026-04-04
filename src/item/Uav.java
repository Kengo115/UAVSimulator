package item;
public class Uav {
    private double speed;
    private double x;
    private double y;
    private int id;
    private Beacon Source;
    private Beacon Destination;
    private UAVFlightTimer flightTimer = new UAVFlightTimer();
    private UAVWaitingTimer waitingTimer = new UAVWaitingTimer();
    private int[] path;
    private boolean isFlying = false;
    private boolean isWaiting = false;
    private int clientId;
    private int stayedBeaconId = -1;
    private Link flyingLink;
    private Link passedLink;

    // MEMORYモード用: 経路待ち時間の計測
    private long pathWaitStartTime = -1;
    private long totalPathWaitTimeMs = 0;

    // MEMORYモード用: 飛行前待機時間（1ホップ目のみ）の計測
    private long flightStayingStartTime = -1;
    private long totalFlightStayingTimeMs = 0;

    // MEMORYモード用: 第一リンク進入済みフラグ（flightStayingTime 計測終了判定）
    private boolean firstLinkEntered = false;

    // MEMORYモード用: 第一ホップ完了済みフラグ（PathWaiting 経路コピートリガー判定）
    private boolean firstHopCompleted = false;

    // コンストラクタ
    public Uav(double speed, double x, double y, int id, Beacon Source, Beacon Destination, int clientId) {
        this.speed = speed;
        this.x = x;
        this.y = y;
        this.id = id;
        this.Source = Source;
        this.Destination = Destination;
        this.clientId = clientId;
    }

    // 経路の設定と取得
    public void setPath(int[] path) {
        this.path = path;
    }

    public int[] getPath() {
        return path;
    }

    // 飛行タイマーの管理
    public void startTimer() {
        if (!isFlying) {
            isFlying = true;
            isWaiting = false; // 飛行を開始するので待機状態を解除
            flightTimer.start(this);
        }
    }

    public void stopTimer() {
        if (isFlying) {
            isFlying = false;
            flightTimer.stop(this);
        }
    }

    public void cancelTimer() {
        if (isFlying) {
            stopTimer();
            flightTimer.cancel(this);
        }
    }
    
    public void cancelTimer(Link[][] link, double totalPathDistance) {
        if (isFlying) {
            stopTimer();
            flightTimer.cancel(this, link, totalPathDistance);
        }
    }

    public long getFlightTime() {
        return flightTimer.getFlightTime();
    }

    // 待機タイマーの管理
    public void startWaitingTimer() {
        if (!isWaiting) {
            isWaiting = true;
            isFlying = false; // 待機を開始するので飛行状態を解除
            waitingTimer.start(this);
        }
    }

    public void stopWaitingTimer() {
        if (isWaiting) {
            isWaiting = false;
            waitingTimer.stop(this);
        }
    }

    public void cancelWaitingTimer() {
        if (isWaiting) {
            stopWaitingTimer();
            waitingTimer.cancel(this);
        }
    }

    public long getWaitingTime() {
        return waitingTimer.getFlightTime();
    }

    // フラグの取得
    public boolean isFlying() {
        return isFlying;
    }

    public boolean isWaiting() {
        return isWaiting;
    }

    // 各種ゲッター・セッター
    public double getSpeed() {
        return speed;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public int getId() {
        return id;
    }

    public Beacon getSource() {
        return Source;
    }

    public Beacon getDistination() {
        return Destination;
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

    // --- MEMORYモード用: 経路待ち時間の計測 ---

    /** 経路待ち開始（PG-EPS で PathWaiting キューに登録された時点） */
    public void startPathWaiting() {
        pathWaitStartTime = System.currentTimeMillis();
    }

    /** 経路待ち終了（先行UAVが第一ホップを通過して経路コピーが完了した時点） */
    public void stopPathWaiting() {
        if (pathWaitStartTime >= 0) {
            totalPathWaitTimeMs += System.currentTimeMillis() - pathWaitStartTime;
            pathWaitStartTime = -1;
        }
    }

    /** 経路待ち時間の累積値をミリ秒で返す */
    public long getTotalPathWaitTimeMs() {
        return totalPathWaitTimeMs;
    }

    // --- MEMORYモード用: 飛行前待機時間（1ホップ目のみ）の計測 ---

    /** 飛行前待機開始（1ホップ目リンク容量不足で待機開始した時点） */
    public void startFlightStaying() {
        if (flightStayingStartTime < 0) {
            flightStayingStartTime = System.currentTimeMillis();
        }
    }

    /** 飛行前待機終了（UAVが初めて第一リンクに進入した時点） */
    public void stopFlightStaying() {
        if (flightStayingStartTime >= 0) {
            totalFlightStayingTimeMs += System.currentTimeMillis() - flightStayingStartTime;
            flightStayingStartTime = -1;
        }
    }

    /** 飛行前待機時間の累積値をミリ秒で返す */
    public long getTotalFlightStayingTimeMs() {
        return totalFlightStayingTimeMs;
    }

    // --- MEMORYモード用: 第一リンク進入フラグ ---

    public boolean isFirstLinkEntered() {
        return firstLinkEntered;
    }

    public void setFirstLinkEntered(boolean firstLinkEntered) {
        this.firstLinkEntered = firstLinkEntered;
    }

    // --- MEMORYモード用: 第一ホップ完了フラグ ---

    public boolean isFirstHopCompleted() {
        return firstHopCompleted;
    }

    public void setFirstHopCompleted(boolean firstHopCompleted) {
        this.firstHopCompleted = firstHopCompleted;
    }
}
