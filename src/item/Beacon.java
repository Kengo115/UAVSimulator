package item;


public class Beacon {
    private double x;
    private double y;
    private int id;
    private Uav[] waitingUavList = new Uav[0]; // 初期状態は空の配列
    private int waitingUavCount = 0;

    public Beacon(double x, double y, int id){
        this.x = x;
        this.y = y;
        this.id = id;
    }

    public double getX(){
        return x;
    }

    public double getY(){
        return y;
    }

    public int getId(){
        return id;
    }

    public Beacon getBeacon(){
        return this;
    }

    public Uav[] getWaitingUavList() {
        return waitingUavList;
    }

    public void setWaitingUavList(Uav[] waitingUavList) {
        this.waitingUavList = waitingUavList;
    }

    // UAVを追加するメソッド
    public void addUav(Uav newUav) {
        // 新しい配列を作成してコピー
        Uav[] updatedList = new Uav[waitingUavList.length + 1];
        System.arraycopy(waitingUavList, 0, updatedList, 0, waitingUavList.length);
        updatedList[waitingUavList.length] = newUav; // 新しいUAVを追加
        waitingUavList = updatedList; // リストを更新
    }

    public void incrementWaitingUavCount() {
        waitingUavCount++;
    }

    public void decrementWaitingUavCount() {
        waitingUavCount--;
    }

    public int getWaitingUavCount() {
        return waitingUavCount;
    }

    // UAVを削除するメソッド
    public void removeUav(Uav targetUav) {
        // 削除対象が見つかるか確認
        int index = -1;
        for (int i = 0; i < waitingUavList.length; i++) {
            if (waitingUavList[i] == targetUav) { // 同一オブジェクト参照を確認
                index = i;
                break;
            }
        }

        // 見つからない場合は何もしない
        if (index == -1) {
            System.out.println("指定されたUAVはリストに存在しません。");
            return;
        }

        // 新しい配列を作成して対象UAVを除外
        Uav[] updatedList = new Uav[waitingUavList.length - 1];
        for (int i = 0, j = 0; i < waitingUavList.length; i++) {
            if (i != index) {
                updatedList[j++] = waitingUavList[i];
            }
        }

        waitingUavList = updatedList; // リストを更新
    }
}

