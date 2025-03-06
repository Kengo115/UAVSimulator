package client;

import item.ClientTimer;

import java.util.ArrayList;

public class ClientController {
    private ArrayList<Client> clientList;
    private ClientTimer clientTimer = new ClientTimer();
    private boolean isTiming = false;

    public ClientController(){
        clientList = new ArrayList<>();
    }

    //クライアントをコントローラに追加する
    public void addClient(Client client){
        clientList.add(client);
    }

    //要求完了クライアントをリストから削除する
    public void removeClient(Client client){
        clientList.remove(client);
    }

    public void startTimer(){
        isTiming = true;
        clientTimer.start();
    }
    public void stopTimer() {
        this.clientTimer.stop();
    }

    public long getFlightTime(){
        return clientTimer.getFlightTime();
    }

    public void resetTimer() {
        clientTimer.reset();
    }

    public void cancelTimer() {
        isTiming = false;
        clientTimer.cancel();
    }

    public boolean getIsTiming() {
        return isTiming;
    }

    //クライアントリストを返す
    public ArrayList<Client> getClientList(){
        return clientList;
    }

    //クライアントリストから任意のクライアントを返す
    public Client getClient(int i){
        return clientList.get(i);
    }
}
