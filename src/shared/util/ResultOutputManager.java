package shared.util;

import shared.client.Client;
import operator.BoundaryController;
import shared.item.Beacon;
import shared.item.BeaconCluster;
import shared.item.Link;
import shared.item.UAVJob;

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
     * Phase 7-1: small_scale/large_scale分離、client形式に変更
     * Phase 9: 環境変数RESULT_DIRに対応
     * @param baseDir ベースディレクトリ
     * @param runCounter 実行カウンター（0始まり）
     * @return ディレクトリパス
     */
    private static String getDirectoryPath(String baseDir, int runCounter) {
        // Phase 9: BoundaryController.getResultDir()を使用して環境変数対応
        // runCounterは0始まりなので+1してclient1, client2, ...にする
        int clientNumber = runCounter + 1;
        return BoundaryController.getResultDir() + "/" + baseDir + "/client" + clientNumber;
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
        // Phase 7-3: 大規模モードではpajek出力をスキップ
        if (BoundaryController.isLargeScaleMode()) {
            return;
        }

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
        // Phase 7-3: 大規模モードではflow出力をスキップ
        if (BoundaryController.isLargeScaleMode()) {
            return;
        }

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
        // Phase 7-3: 大規模モードではstatus出力をスキップ
        if (BoundaryController.isLargeScaleMode()) {
            return;
        }

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
        // Phase 7-3: 大規模モードではiteration出力をスキップ
        if (BoundaryController.isLargeScaleMode()) {
            return;
        }

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
        // Phase 7-3: 大規模モードではiteration出力をスキップ
        if (BoundaryController.isLargeScaleMode()) {
            return;
        }

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
        // Phase 7-3: 大規模モードではrute出力をスキップ（小規模テスト用のハードコードされた経路出力）
        if (BoundaryController.isLargeScaleMode()) {
            return;
        }

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

        // Phase 5/11: 時間情報を取得
        double realFlightTime = job.getElapsedFlightTime();       // 純粋な飛行時間
        double waitingTime = job.getTotalWaitingTime();           // 飛行中待機時間（バッテリー消費あり）
        double pathWaitTime = job.getPathWaitTime();              // 経路待ち時間
        double flightStayingTime = job.getFlightStayingTime();    // 飛行前待機時間（1ホップ目のみ、バッテリー消費なし）
        double flightTime = job.getTotalTime();                   // 合計時間（realFlightTime + waitingTime + flightStayingTime、pathWaitTimeは含まない）

        try (FileWriter writer = new FileWriter(filename, true)) {
            File file = new File(filename);
            if (file.length() == 0) {
                // Phase 11: pathWaitTimeとflightStayingTimeを分離
                writer.write("source,dest,flightTime,realFlightTime,waitingTime,pathWaitTime,flightStayingTime,clientId,uavId,speed,distance,path\n");
            }
            writer.write(String.format("%d,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%d,%.2f,%.2f,%s\n",
                job.getSourceBeaconId(),
                job.getDestinationBeaconId(),
                flightTime,           // 合計時間（realFlightTime + waitingTime + flightStayingTime）
                realFlightTime,       // 純粋な飛行時間
                waitingTime,          // 飛行中待機時間（2ホップ目以降、バッテリー消費あり）
                pathWaitTime,         // 経路待ち時間（バッテリー消費なし）
                flightStayingTime,    // 飛行前待機時間（1ホップ目のみ、バッテリー消費なし）
                job.getClientId(),
                job.getUavId(),
                job.getSpeed(),
                job.getTotalDistance(),
                pathString));
        }

        LogManager.getInstance().log("Phase 11: UAV" + job.getUavId() +
            " 飛行結果保存 (合計=" + String.format("%.2f", flightTime) +
            "s, 飛行=" + String.format("%.2f", realFlightTime) +
            "s, 飛行中待機=" + String.format("%.2f", waitingTime) +
            "s, 経路待ち=" + String.format("%.2f", pathWaitTime) +
            "s, 1ホップ目待機=" + String.format("%.2f", flightStayingTime) + "s)");
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
        double pathWaitTime = job.getPathWaitTime();          // Phase 4: 経路待ち時間

        try (FileWriter writer = new FileWriter(filename, true)) {
            File file = new File(filename);
            if (file.length() == 0) {
                // Phase 4: ヘッダーにpathWaitTimeを追加
                writer.write("source,dest,flightTime,realFlightTime,waitingTime,pathWaitTime,clientId,uavId,speed,distance,path\n");
            }
            writer.write(String.format("%d,%d,%.2f,%.2f,%.2f,%.2f,%d,%d,%.2f,%.2f,%s\n",
                job.getSourceBeaconId(),
                job.getDestinationBeaconId(),
                flightTime,
                elapsedFlightTime,
                waitingTime,
                pathWaitTime,       // Phase 4: 経路待ち時間
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

    /**
     * Phase 7-2: UAVの経路割り当て情報を出力する
     * @param uavId UAV ID
     * @param sourceId 出発ノードID
     * @param destId 到着ノードID
     * @param path 経路配列
     * @param runCounter 実行カウンター（クライアント番号）
     * @throws IOException 入出力例外
     */
    public static void outputRouteAssignment(int uavId, int sourceId, int destId, int[] path, int runCounter) throws IOException {
        // 現在の経路探索手法に基づいてディレクトリパスを作成
        String dirPath = getDirectoryPath("route", runCounter);
        String filename = dirPath + "/route.csv";

        // ディレクトリが存在しない場合は作成
        File dir = new File(dirPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // 経路を矢印形式に変換 (0->3->5)
        String pathString = Arrays.stream(path)
            .mapToObj(String::valueOf)
            .collect(Collectors.joining("->"));

        try (FileWriter writer = new FileWriter(filename, true)) {
            File file = new File(filename);
            if (file.length() == 0) {
                writer.write("uav_id,source,dest,path\n");
            }
            writer.write(String.format("%d,%d,%d,%s\n", uavId, sourceId, destId, pathString));
        }

        LogManager.getInstance().log("Phase 7-2: UAV" + uavId + " 経路割り当て記録 (" + pathString + ")");
    }

    /**
     * Phase 7-2: UAVJobから経路割り当て情報を出力する
     * @param job UAVジョブ
     * @param runCounter 実行カウンター（クライアント番号）
     * @throws IOException 入出力例外
     */
    public static void outputRouteAssignment(UAVJob job, int runCounter) throws IOException {
        outputRouteAssignment(
            job.getUavId(),
            job.getSourceBeaconId(),
            job.getDestinationBeaconId(),
            job.getPath(),
            runCounter
        );
    }

    /**
     * 全クライアントの飛行データを集計して平均値を出力する
     * 出力: time/ave_flightStatus.csv
     * Phase 9: 環境変数RESULT_DIRに対応
     * Phase 11: avg_real_flight_time, avg_staying_time, avg_path_wait_time を追加
     */
    public static void outputAverageFlightStatus() {
        // Phase 9: BoundaryController.getResultDir()を使用
        String timeDirPath = BoundaryController.getResultDir() + "/time";

        File timeDir = new File(timeDirPath);
        if (!timeDir.exists() || !timeDir.isDirectory()) {
            LogManager.getInstance().log("警告: timeディレクトリが存在しません: " + timeDirPath);
            return;
        }

        // Phase 11: 集計用変数を追加
        double totalFlightTime = 0;       // flightTime (realFlightTime + waitingTime + flightStayingTime)
        double totalRealFlightTime = 0;   // 実飛行時間
        double totalWaitingTime = 0;      // 飛行中待機時間
        double totalPathWaitTime = 0;     // 経路待ち時間
        double totalStayingTime = 0;      // 1ホップ目待機時間
        double totalDistance = 0;
        int uavCount = 0;

        // Phase 11: 評価・デバッグ用統計
        int fullyCompletedClientCount = 0;  // 10台全て完了したclient数
        int partiallyCompletedClientCount = 0;  // 1-9台完了したclient数
        double maxFlightTime = 0;
        double minFlightTime = Double.MAX_VALUE;
        double maxWaitingTime = 0;
        double minWaitingTime = Double.MAX_VALUE;

        // 全clientディレクトリを走査
        File[] clientDirs = timeDir.listFiles((dir, name) -> name.startsWith("client") && new File(dir, name).isDirectory());
        if (clientDirs == null || clientDirs.length == 0) {
            LogManager.getInstance().log("警告: clientディレクトリが見つかりません: " + timeDirPath);
            return;
        }

        for (File clientDir : clientDirs) {
            File flightTimesFile = new File(clientDir, "flight_times.csv");
            if (!flightTimesFile.exists()) {
                continue;
            }

            int clientUavCount = 0;  // このclientのUAV完了数

            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(flightTimesFile))) {
                String line;
                boolean isHeader = true;
                while ((line = reader.readLine()) != null) {
                    if (isHeader) {
                        isHeader = false;
                        continue;
                    }
                    String[] parts = line.split(",");
                    // Phase 11: 新しいCSVフォーマット
                    // source,dest,flightTime,realFlightTime,waitingTime,pathWaitTime,flightStayingTime,clientId,uavId,speed,distance,path
                    if (parts.length >= 11) {
                        double flightTime = Double.parseDouble(parts[2]);       // flightTime (index 2)
                        double realFlightTime = Double.parseDouble(parts[3]);   // realFlightTime (index 3)
                        double waitingTime = Double.parseDouble(parts[4]);      // waitingTime (index 4)
                        double pathWaitTime = Double.parseDouble(parts[5]);     // pathWaitTime (index 5)
                        double stayingTime = Double.parseDouble(parts[6]);      // flightStayingTime (index 6)
                        double distance = Double.parseDouble(parts[10]);        // distance (index 10)

                        totalFlightTime += flightTime;
                        totalRealFlightTime += realFlightTime;
                        totalWaitingTime += waitingTime;
                        totalPathWaitTime += pathWaitTime;
                        totalStayingTime += stayingTime;
                        totalDistance += distance;
                        uavCount++;
                        clientUavCount++;

                        // 最大/最小値の更新
                        if (flightTime > maxFlightTime) maxFlightTime = flightTime;
                        if (flightTime < minFlightTime) minFlightTime = flightTime;
                        if (waitingTime > maxWaitingTime) maxWaitingTime = waitingTime;
                        if (waitingTime < minWaitingTime) minWaitingTime = waitingTime;
                    }
                }
            } catch (IOException | NumberFormatException e) {
                LogManager.getInstance().log("警告: ファイル読み込みエラー: " + flightTimesFile.getPath() + " - " + e.getMessage());
            }

            // このclientのUAV完了数をカウント
            if (clientUavCount == 10) {
                fullyCompletedClientCount++;
            } else if (clientUavCount > 0) {
                partiallyCompletedClientCount++;
            }
        }

        if (uavCount == 0) {
            LogManager.getInstance().log("警告: 集計対象のUAVデータがありません");
            return;
        }

        // 平均値を計算
        double avgFlightTime = totalFlightTime / uavCount;
        double avgRealFlightTime = totalRealFlightTime / uavCount;
        double avgWaitingTime = totalWaitingTime / uavCount;
        double avgPathWaitTime = totalPathWaitTime / uavCount;
        double avgStayingTime = totalStayingTime / uavCount;
        double avgDistance = totalDistance / uavCount;

        // Phase 11: 評価・デバッグ用の統計値を計算
        int totalClientCount = clientDirs.length;
        double avgUavPerClient = (double) uavCount / totalClientCount;
        double uavCompletionRate = (uavCount * 100.0) / (totalClientCount * 10);  // 全clientが10台と仮定
        double fullyCompletedRate = (fullyCompletedClientCount * 100.0) / totalClientCount;

        // 最小値が更新されていない場合は0にする
        if (minFlightTime == Double.MAX_VALUE) minFlightTime = 0;
        if (minWaitingTime == Double.MAX_VALUE) minWaitingTime = 0;

        // 結果をファイルに出力
        String outputPath = timeDirPath + "/ave_flightStatus.csv";
        try (FileWriter writer = new FileWriter(outputPath)) {
            writer.write("metric,value,unit\n");

            // 平均値
            writer.write(String.format("avg_flight_time,%.2f,seconds\n", avgFlightTime));
            writer.write(String.format("avg_real_flight_time,%.2f,seconds\n", avgRealFlightTime));
            writer.write(String.format("avg_waiting_time,%.2f,seconds\n", avgWaitingTime));
            writer.write(String.format("avg_path_wait_time,%.2f,seconds\n", avgPathWaitTime));
            writer.write(String.format("avg_staying_time,%.2f,seconds\n", avgStayingTime));
            writer.write(String.format("avg_distance,%.2f,meters\n", avgDistance));

            // 最大/最小値（デバッグ用）
            writer.write(String.format("max_flight_time,%.2f,seconds\n", maxFlightTime));
            writer.write(String.format("min_flight_time,%.2f,seconds\n", minFlightTime));
            writer.write(String.format("max_waiting_time,%.2f,seconds\n", maxWaitingTime));
            writer.write(String.format("min_waiting_time,%.2f,seconds\n", minWaitingTime));

            // 総数・完了率
            writer.write(String.format("total_uav_count,%d,count\n", uavCount));
            writer.write(String.format("total_client_count,%d,count\n", totalClientCount));
            writer.write(String.format("fully_completed_client_count,%d,count\n", fullyCompletedClientCount));
            writer.write(String.format("partially_completed_client_count,%d,count\n", partiallyCompletedClientCount));

            // 完了率（評価用）
            writer.write(String.format("avg_uav_per_client,%.2f,count\n", avgUavPerClient));
            writer.write(String.format("uav_completion_rate,%.2f,percent\n", uavCompletionRate));
            writer.write(String.format("fully_completed_client_rate,%.2f,percent\n", fullyCompletedRate));
        } catch (IOException e) {
            LogManager.getInstance().log("エラー: 平均飛行ステータス出力失敗: " + e.getMessage());
            return;
        }

        LogManager.getInstance().log(String.format(
            "Phase 11: 平均飛行ステータス出力完了: UAV数=%d, Client数=%d, 完全完了Client=%d (%.1f%%), UAV完了率=%.1f%%, 平均UAV/Client=%.2f",
            uavCount, totalClientCount, fullyCompletedClientCount, fullyCompletedRate, uavCompletionRate, avgUavPerClient));
    }
}
