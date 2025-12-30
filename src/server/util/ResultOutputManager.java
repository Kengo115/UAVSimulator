package server.util;

import client.Client;
import controller.BoundaryController;
import item.Beacon;
import item.BeaconCluster;
import item.Link;
import server.redis.UAVJob;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 結果出力を管理するクラス
 */
public class ResultOutputManager {

    /**
     * 現在の経路探索手法に基づいてディレクトリパスを取得する
     * @param baseDir ベースディレクトリ
     * @param runCounter 実行カウンター
     * @return ディレクトリパス
     */
    private static String getDirectoryPath(String baseDir, int runCounter) {
        BoundaryController.RouteSearchMethod method = BoundaryController.getCurrentMethod();
        return "src/result/" + method.getName() + "/" + baseDir + "/result" + runCounter;
    }

    /**
     * Pajekファイルに出力する
     * @param client クライアント
     * @param eps イプシロン
     * @param Q_allFlow 全フロー
     * @param ct カウンター
     * @param link リンク情報
     * @param beaconCluster ビーコンクラスター
     * @param node ノード数
     * @param runCounter 実行カウンター
     * @throws IOException 入出力例外
     */
    public static void outputToPajek(Client client, double eps, double Q_allFlow, int ct, Link[][] link, BeaconCluster beaconCluster, int node, int runCounter) throws IOException {
        Beacon source = client.getFlow().getSource();
        Beacon dist = client.getFlow().getDestination();

        // 現在の経路探索手法に基づいてディレクトリパスを作成
        String dirPath = getDirectoryPath("pajek", runCounter);
        // ファイル名を作成
        String filename = dirPath + "/test_topology_" + (ct + 1) + ".net";

        // Fileオブジェクトでディレクトリの存在を確認・作成
        File dir = new File(dirPath);
        if (!dir.exists()) {
            // ディレクトリが存在しない場合は作成
            dir.mkdirs();
        }
        try (FileWriter writer = new FileWriter(filename)) {
            writer.write("*Vertices\t" + node + "\n");
            for (int i = 0; i < node; i++) {
                if (i == source.getId() || i == dist.getId()) {
                    writer.write(String.format("%d \"%d\" %.4f %.4f ic Black\n", i + 1, i + 1, source.getX(), source.getY()));
                } else {
                    writer.write(String.format("%d \"%d\" %.4f %.4f ic White\n", i + 1, i + 1, beaconCluster.getBeacon(i).getX(), beaconCluster.getBeacon(i).getY()));
                }
            }
            writer.write("*Arcs\n*Edges\n");

            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY) {
                        double flow = link[i][j].getQ_tubeFlow();
                        if (link[i][j].getDistance() > 0) {
                            if (flow > 0 && flow <= eps) {
                                // Small flow, no color
                            } else if (flow > eps && flow <= 0.5) {
                                writer.write(String.format("%d %d 1 c Blue\n", i + 1, j + 1));
                            } else if (flow > 0.5 && flow <= 2.0) {
                                writer.write(String.format("%d %d 2 c Green\n", i + 1, j + 1));
                            } else if (flow > 2.0 && flow <= Q_allFlow) {
                                writer.write(String.format("%d %d 3 c Red\n", i + 1, j + 1));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * flowファイルに各リンクの流量を出力する
     * @param client クライアント
     * @param ct カウンター
     * @param link リンク情報
     * @param node ノード数
     * @param runCounter 実行カウンター
     * @throws IOException 入出力例外
     */
    public static void outputToExcel(Client client, int ct, Link[][] link, int node, int runCounter, double inflow) throws IOException {
        // 現在の経路探索手法に基づいてディレクトリパスを作成
        String dirPath = getDirectoryPath("flow", runCounter);
        // ファイル名を作成
        String filename = dirPath + "/test_topology_" + (ct + 1) + ".txt";

        // Fileオブジェクトでディレクトリの存在を確認・作成
        File dir = new File(dirPath);
        if (!dir.exists()) {
            // ディレクトリが存在しない場合は作成
            dir.mkdirs();
        }
        try (FileWriter writer = new FileWriter(filename)) {
            // 1行目: 流入フロー,ソース,シンク
            writer.write(String.format("%.1f,%d,%d\n", inflow, client.getFlow().getSource().getId(), client.getFlow().getDestination().getId()));
            writer.write("source,destination,flow\n");
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY) {
                        writer.write(String.format("%d,%d,%.4f\n", i, j, link[i][j].getQ_tubeFlow()));
                    }
                }
            }
        }
    }

    /**
     * statusファイルに管の長さ，管の太さ，管の容量を出力する（後方互換性用）
     * @param client クライアント
     * @param ct カウンター
     * @param link リンク情報
     * @param node ノード数
     * @param runCounter 実行カウンター
     * @throws IOException 入出力例外
     */
    public static void outputToTxt(Client client, int ct, Link[][] link, int node, int runCounter, double inflow) throws IOException {
        outputToTxt(client, ct, link, node, runCounter, null, inflow);
    }

    /**
     * statusファイルに管の長さ，管の太さ，管の容量，圧力係数を出力する
     * @param client クライアント
     * @param ct カウンター
     * @param link リンク情報
     * @param node ノード数
     * @param runCounter 実行カウンター
     * @param pressureCoefficient 圧力係数行列
     * @param inflow 流入フロー
     * @throws IOException 入出力例外
     */
    public static void outputToTxt(Client client, int ct, Link[][] link, int node, int runCounter, double[][] pressureCoefficient, double inflow) throws IOException {
        outputToTxt(client, ct, link, node, runCounter, pressureCoefficient, null, inflow);
    }

    /**
     * statusファイルに管の長さ，管の太さ，管の容量，圧力係数，チューブ圧力を出力する
     * @param client クライアント
     * @param ct カウンター
     * @param link リンク情報
     * @param node ノード数
     * @param runCounter 実行カウンター
     * @param pressureCoefficient 圧力係数行列
     * @param tubePressure チューブ圧力配列
     * @param inflow 流入フロー
     * @throws IOException 入出力例外
     */
    public static void outputToTxt(Client client, int ct, Link[][] link, int node, int runCounter, double[][] pressureCoefficient, double[] tubePressure, double inflow) throws IOException {
        // 現在の経路探索手法に基づいてディレクトリパスを作成
        String dirPath = getDirectoryPath("status", runCounter);
        // ファイル名を作成
        String filename = dirPath + "/test_topology_" + (ct + 1) + ".txt";

        // Fileオブジェクトでディレクトリの存在を確認・作成
        File dir = new File(dirPath);
        if (!dir.exists()) {
            // ディレクトリが存在しない場合は作成
            dir.mkdirs();
        }
        try (FileWriter writer = new FileWriter(filename)) {
            // 1行目: 流入フロー，出発ノード，到着ノード（要求UAV数→流入フローへ変更）
            writer.write(String.format("%.1f,%d,%d\n", inflow, client.getFlow().getSource().getId(), client.getFlow().getDestination().getId()));
            
            // ヘッダー行（P_tubePressureを追加）
            if (tubePressure != null) {
                writer.write("source,destination,length,thickness,capacity,pressureCoefficient,sourcePressure,destPressure\n");
            } else {
                writer.write("source,destination,length,thickness,capacity,pressureCoefficient\n");
            }
            
            for (int i = 0; i < node; i++) {
                for (int j = 0; j < node; j++) {
                    if (link[i][j].getL_tubeLength() != Double.POSITIVE_INFINITY) {
                        // 圧力係数を取得（非対角要素は負の値になっているので絶対値を取る）
                        double pressureCoeff = 0.0;
                        if (pressureCoefficient != null && i != j) {
                            pressureCoeff = Math.abs(pressureCoefficient[i][j]);
                        }
                        
                        if (tubePressure != null) {
                            // P_tubePressureも出力
                            double sourcePressure = tubePressure[i];
                            double destPressure = tubePressure[j];
                            writer.write(String.format("%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f\n", 
                                i, j, link[i][j].getL_tubeLength(), link[i][j].getD_tubeThickness(), 
                                link[i][j].getCapacity(), pressureCoeff, sourcePressure, destPressure));
                        } else {
                            writer.write(String.format("%d,%d,%.4f,%.4f,%.4f,%.4f\n", 
                                i, j, link[i][j].getL_tubeLength(), link[i][j].getD_tubeThickness(), 
                                link[i][j].getCapacity(), pressureCoeff));
                        }
                    }
                }
            }
        }
    }

    /**
     * イテレーション毎のソース圧力を記録する
     * @param iteration イテレーション番号
     * @param sourcePressure ソース圧力値
     * @param runCounter 実行カウンター
     * @throws IOException 入出力例外
     */
    public static void outputIterationSourcePressure(int iteration, double sourcePressure, int runCounter) throws IOException {
        // 現在の経路探索手法に基づいてディレクトリパスを作成
        String dirPath = getDirectoryPath("iteration/sourcePressure", runCounter);
        // ファイル名を作成（手法ごとに1つのファイル）
        String filename = dirPath + "/iteration_source_pressure.txt";

        // Fileオブジェクトでディレクトリの存在を確認・作成
        File dir = new File(dirPath);
        if (!dir.exists()) {
            // ディレクトリが存在しない場合は作成
            dir.mkdirs();
        }

        // ファイルが新規作成の場合はヘッダーを書き込み、既存の場合は追記
        File file = new File(filename);
        try (FileWriter writer = new FileWriter(filename, true)) { // 追記モード
            // ファイルが空の場合のみヘッダーを書き込む
            if (file.length() == 0) {
                writer.write("iteration,sourcePressure\n");
            }
            // イテレーション番号とソース圧力を追記
            writer.write(String.format("%d,%.6f\n", iteration, sourcePressure));
        }
    }

    /**
     * イテレーション毎のフロー値を記録する
     * @param iteration イテレーション番号
     * @param flowValue フロー値（要求台数/流入フロー）
     * @param runCounter 実行カウンター
     * @throws IOException 入出力例外
     */
    public static void outputIterationFlow(int iteration, double flowValue, int runCounter) throws IOException {
        // 現在の経路探索手法に基づいてディレクトリパスを作成
        String dirPath = getDirectoryPath("iteration/flow", runCounter);
        // ファイル名を作成（手法ごとに1つのファイル）
        String filename = dirPath + "/iteration_flow.txt";

        // Fileオブジェクトでディレクトリの存在を確認・作成
        File dir = new File(dirPath);
        if (!dir.exists()) {
            // ディレクトリが存在しない場合は作成
            dir.mkdirs();
        }

        // ファイルが新規作成の場合はヘッダーを書き込み、既存の場合は追記
        File file = new File(filename);
        try (FileWriter writer = new FileWriter(filename, true)) { // 追記モード
            // ファイルが空の場合のみヘッダーを書き込む
            if (file.length() == 0) {
                writer.write("iteration,flow\n");
            }
            // イテレーション番号とフロー値を追記
            writer.write(String.format("%d,%.6f\n", iteration, flowValue));
        }
    }

    /**
     * 経路ごとのUAV数をflow形式で出力する
     * @param client クライアント
     * @param ct カウンター
     * @param link リンク情報
     * @param runCounter 実行カウンター
     * @throws IOException 入出力例外
     */
    public static void outputRouteToExcel(Client client, int ct, Link[][] link, int runCounter) throws IOException {
        // 現在の経路探索手法に基づいてディレクトリパスを作成
        String dirPath = getDirectoryPath("rute", runCounter);
        // ファイル名を作成
        String filename = dirPath + "/test_topology_routes.txt";

        // Fileオブジェクトでディレクトリの存在を確認・作成
        File dir = new File(dirPath);
        if (!dir.exists()) {
            // ディレクトリが存在しない場合は作成
            dir.mkdirs();
        }

        // ファイルに追記
        try (FileWriter writer = new FileWriter(filename, true)) { // true で追記モードに設定
            // ヘッダーは1回だけ記載されるようにする
            File file = new File(filename);
            if (file.length() == 0) { // ファイルが空の場合のみヘッダーを書き込む
                writer.write("ct,v0->v1->v4->v5,v0->v2->v3->v5,v0->v2->v5,v0->v3->v5\n");
            }
            if (runCounter == 0 || runCounter == 1) {
                if (file.length() == 0) { // ファイルが空の場合のみヘッダーを書き込む
                    writer.write("ct,v0->v1->v4->v5,v0->v2->v3->v5,v0->v2->v5,v0->v3->v5\n");
                }
                double route1 = Math.min(link[0][1].getQ_tubeFlow(),
                        Math.min(link[1][4].getQ_tubeFlow(), link[4][5].getQ_tubeFlow()));

                double route2 = Math.min(link[0][2].getQ_tubeFlow(),
                        Math.min(link[2][3].getQ_tubeFlow(), link[3][5].getQ_tubeFlow()));

                double route3 = Math.min(link[0][2].getQ_tubeFlow(), link[2][5].getQ_tubeFlow());

                double route4 = Math.min(link[0][3].getQ_tubeFlow(), link[3][5].getQ_tubeFlow());
                // 経路ごとの情報を1行にまとめて追記
                writer.write(String.format("%d,%.4f,%.4f,%.4f,%.4f\n", ct, route1, route2, route3, route4));

            } else if (runCounter == 2) {
                if (file.length() == 0) { // ファイルが空の場合のみヘッダーを書き込む
                    writer.write("ct,v2->v0->v1->v4,v2->v3->v0->v1->v4,v2->v3->v5->v4,v2->v5->v4\n");
                }
                double route1 = Math.min(link[2][0].getQ_tubeFlow(),
                        Math.min(link[0][1].getQ_tubeFlow(), link[1][4].getQ_tubeFlow()));

                double route2 = Math.min(link[2][3].getQ_tubeFlow(),
                        Math.min(link[3][0].getQ_tubeFlow(), Math.min(link[0][1].getQ_tubeFlow(), link[1][4].getQ_tubeFlow())));

                double route3 = Math.min(link[2][3].getQ_tubeFlow(), Math.min(link[3][5].getQ_tubeFlow(), link[5][4].getQ_tubeFlow()));

                double route4 = Math.min(link[2][5].getQ_tubeFlow(), link[5][4].getQ_tubeFlow());
                // 経路ごとの情報を1行にまとめて追記
                writer.write(String.format("%d,%.4f,%.4f,%.4f,%.4f\n", ct, route1, route2, route3, route4));
            }
        }
    }

    /**
     * Phase 3b-9: Redisモード用のtime結果を出力する（UAVJobから時間情報を取得）
     * @param job UAVジョブ
     * @param runCounter 実行カウンター（クライアント番号）
     * @throws IOException 入出力例外
     */
    public static void outputFlightTimeResult(UAVJob job, int runCounter) throws IOException {
        // 現在の経路探索手法に基づいてディレクトリパスを作成
        String dirPath = getDirectoryPath("time", runCounter);
        String filename = dirPath + "/flight_times.csv";

        // ディレクトリが存在しない場合は作成
        File dir = new File(dirPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // 経路を文字列に変換
        String pathString = Arrays.stream(job.getPath())
            .mapToObj(String::valueOf)
            .collect(Collectors.joining("-"));

        // Phase 3b-9: 時間情報を取得
        double realFlightTime = job.getElapsedFlightTime();    // 純粋な飛行時間
        double waitingTime = job.getTotalWaitingTime();         // 待機時間
        double flightTime = job.getTotalTime();                 // 合計時間（飛行＋待機）

        try (FileWriter writer = new FileWriter(filename, true)) {
            File file = new File(filename);
            if (file.length() == 0) {
                // Phase 3b-9: ヘッダーを更新
                writer.write("source,dest,flightTime,realFlightTime,waitingTime,clientId,uavId,speed,distance,path\n");
            }
            writer.write(String.format("%d,%d,%.2f,%.2f,%.2f,%d,%d,%.2f,%.2f,%s\n",
                job.getSourceBeaconId(),
                job.getDestinationBeaconId(),
                flightTime,         // 合計時間（飛行＋待機）
                realFlightTime,     // 純粋な飛行時間
                waitingTime,        // 待機時間
                job.getClientId(),
                job.getUavId(),
                job.getSpeed(),
                job.getTotalDistance(),
                pathString));
        }

        LogManager.getInstance().log("Phase 3b-9: UAV" + job.getUavId() +
            " 飛行結果保存 (合計=" + String.format("%.2f", flightTime) +
            "s, 飛行=" + String.format("%.2f", realFlightTime) +
            "s, 待機=" + String.format("%.2f", waitingTime) + "s)");
    }

    /**
     * Phase 3b-7: Redisモード用のtime結果を出力する（後方互換性用）
     * @param job UAVジョブ
     * @param elapsedFlightTime 飛行時間（秒）
     * @param waitingTime 待機時間（秒）
     * @param runCounter 実行カウンター（クライアント番号）
     * @throws IOException 入出力例外
     * @deprecated Phase 3b-9以降は outputFlightTimeResult(UAVJob, int) を使用してください
     */
    @Deprecated
    public static void outputFlightTimeResult(UAVJob job, double elapsedFlightTime, double waitingTime, int runCounter) throws IOException {
        // 現在の経路探索手法に基づいてディレクトリパスを作成
        String dirPath = getDirectoryPath("time", runCounter);
        String filename = dirPath + "/flight_times.csv";

        // ディレクトリが存在しない場合は作成
        File dir = new File(dirPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // 経路を文字列に変換
        String pathString = Arrays.stream(job.getPath())
            .mapToObj(String::valueOf)
            .collect(Collectors.joining("-"));

        double flightTime = elapsedFlightTime + waitingTime; // 合計時間

        try (FileWriter writer = new FileWriter(filename, true)) {
            File file = new File(filename);
            if (file.length() == 0) {
                writer.write("source,dest,flightTime,realFlightTime,waitingTime,clientId,uavId,speed,distance,path\n");
            }
            writer.write(String.format("%d,%d,%.2f,%.2f,%.2f,%d,%d,%.2f,%.2f,%s\n",
                job.getSourceBeaconId(),
                job.getDestinationBeaconId(),
                flightTime,
                elapsedFlightTime,
                waitingTime,
                job.getClientId(),
                job.getUavId(),
                job.getSpeed(),
                job.getTotalDistance(),
                pathString));
        }

        LogManager.getInstance().log("Phase 3b-7: UAV" + job.getUavId() + " 飛行結果をファイルに保存しました");
    }

    /**
     * Phase 3b-7: Redisモード用のflow結果を出力する
     * @param jobs 完了したジョブリスト
     * @param runCounter 実行カウンター
     * @throws IOException 入出力例外
     */
    public static void outputFlowSummary(java.util.List<UAVJob> jobs, int runCounter) throws IOException {
        if (jobs == null || jobs.isEmpty()) return;

        // 現在の経路探索手法に基づいてディレクトリパスを作成
        String dirPath = getDirectoryPath("flow", runCounter);
        String filename = dirPath + "/flow_summary.txt";

        // ディレクトリが存在しない場合は作成
        File dir = new File(dirPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // ソース・シンクを取得（最初のジョブから）
        UAVJob firstJob = jobs.get(0);

        try (FileWriter writer = new FileWriter(filename)) {
            writer.write(String.format("%d,%d,%d\n",
                jobs.size(),
                firstJob.getSourceBeaconId(),
                firstJob.getDestinationBeaconId()));
            writer.write("uavId,clientId,path,distance,flightTime\n");

            for (UAVJob job : jobs) {
                String pathString = Arrays.stream(job.getPath())
                    .mapToObj(String::valueOf)
                    .collect(Collectors.joining("-"));
                writer.write(String.format("%d,%d,%s,%.2f,%.2f\n",
                    job.getUavId(),
                    job.getClientId(),
                    pathString,
                    job.getTotalDistance(),
                    job.getElapsedFlightTime()));
            }
        }

        LogManager.getInstance().log("Phase 3b-7: フローサマリーをファイルに保存しました (" + jobs.size() + "件)");
    }
}
