package shared.util;

import shared.item.Link;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * デバッグモード時にイテレーション毎のリンク流量を収集し JSON ファイルに保存するシングルトン。
 * DEBUG_MODE=true 環境変数が設定されている場合のみ動作する。
 *
 * 出力先: src/result/debug/iterations/client_{id}.json
 * フォーマット:
 *   {
 *     "meta": { clientId, source, destination, numUAVs, method, timestamp, totalIterations },
 *     "links": [[src, dst, capacity], ...],   // i<j の無向辺のみ
 *     "flows": [[f0, f1, ...], ...]           // iteration × links の絶対値流量
 *   }
 */
public class DebugIterationRecorder {

    private static final DebugIterationRecorder INSTANCE = new DebugIterationRecorder();
    private static final String OUTPUT_DIR = "src/result/debug/iterations";
    private static final double INF = 10000.0;

    // 記録対象クライアントのメタ情報
    private volatile boolean recording = false;
    private int clientId;
    private int source;
    private int destination;
    private int numUAVs;
    private String method;
    private String timestamp;

    // リンクインデックス（i < j の無向辺のみ）
    private int[] linkSrcs;   // [linkIdx] -> src node
    private int[] linkDsts;   // [linkIdx] -> dst node
    private float[] linkCapacities; // [linkIdx] -> capacity
    private int numLinks;

    // イテレーション毎の流量スナップショット
    private List<float[]> iterationFlows;

    private DebugIterationRecorder() {}

    public static DebugIterationRecorder getInstance() {
        return INSTANCE;
    }

    /** DEBUG_MODE=true 環境変数が設定されているかどうか */
    public boolean isEnabled() {
        return "true".equalsIgnoreCase(System.getenv("DEBUG_MODE"));
    }

    public boolean isRecording() {
        return recording;
    }

    /**
     * 記録を開始する。
     * リンクのインデックスを構築し、空のイテレーションリストを初期化する。
     */
    public void startRecording(int clientId, int source, int destination, int numUAVs,
                                String method, Link[][] link, int node) {
        if (!isEnabled()) return;

        this.clientId = clientId;
        this.source = source;
        this.destination = destination;
        this.numUAVs = numUAVs;
        this.method = method;
        this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        this.iterationFlows = new ArrayList<>(1100);

        // i < j の無向辺のみ収集（双方向リンクの重複を除去）
        List<Integer> srcs = new ArrayList<>();
        List<Integer> dsts = new ArrayList<>();
        List<Float> caps = new ArrayList<>();
        for (int i = 0; i < node; i++) {
            for (int j = i + 1; j < node; j++) {
                if (link[i][j].getL_tubeLength() != INF) {
                    srcs.add(i);
                    dsts.add(j);
                    caps.add((float) link[i][j].getCapacity());
                }
            }
        }

        numLinks = srcs.size();
        linkSrcs = new int[numLinks];
        linkDsts = new int[numLinks];
        linkCapacities = new float[numLinks];
        for (int k = 0; k < numLinks; k++) {
            linkSrcs[k] = srcs.get(k);
            linkDsts[k] = dsts.get(k);
            linkCapacities[k] = caps.get(k);
        }

        recording = true;
        LogManager.getInstance().log(
            "DebugIterationRecorder: started for client" + clientId +
            " (" + source + "->" + destination + ", " + numUAVs + "UAV, " + method + ")" +
            " numLinks=" + numLinks);
    }

    /**
     * 現在のリンク流量スナップショットを1イテレーション分として記録する。
     * updateTubeThickness() の直後に呼ぶこと。
     */
    public void recordIteration(Link[][] link, int node) {
        if (!recording || !isEnabled()) return;

        float[] flows = new float[numLinks];
        for (int k = 0; k < numLinks; k++) {
            int i = linkSrcs[k];
            int j = linkDsts[k];
            flows[k] = (float) Math.abs(link[i][j].getQ_tubeFlow());
        }
        iterationFlows.add(flows);
    }

    /**
     * 記録を停止し JSON ファイルに保存する。
     * route search 完了直後に呼ぶこと。
     */
    public void stopAndSave() {
        if (!recording || !isEnabled()) return;
        recording = false;

        int totalIterations = iterationFlows.size();
        if (totalIterations == 0) {
            LogManager.getInstance().log("DebugIterationRecorder: no iterations recorded, skipping save");
            return;
        }

        try {
            new File(OUTPUT_DIR).mkdirs();

            StringBuilder sb = new StringBuilder(totalIterations * numLinks * 8);

            // meta
            sb.append("{\"meta\":{");
            sb.append("\"clientId\":").append(clientId).append(",");
            sb.append("\"source\":").append(source).append(",");
            sb.append("\"destination\":").append(destination).append(",");
            sb.append("\"numUAVs\":").append(numUAVs).append(",");
            sb.append("\"method\":\"").append(method).append("\",");
            sb.append("\"timestamp\":\"").append(timestamp).append("\",");
            sb.append("\"totalIterations\":").append(totalIterations);
            sb.append("},");

            // links: [[src, dst, capacity], ...]
            sb.append("\"links\":[");
            for (int k = 0; k < numLinks; k++) {
                if (k > 0) sb.append(",");
                sb.append("[").append(linkSrcs[k]).append(",")
                  .append(linkDsts[k]).append(",")
                  .append(linkCapacities[k]).append("]");
            }
            sb.append("],");

            // flows: [[f0,f1,...], ...]
            sb.append("\"flows\":[");
            for (int t = 0; t < totalIterations; t++) {
                if (t > 0) sb.append(",");
                float[] flows = iterationFlows.get(t);
                sb.append("[");
                for (int k = 0; k < numLinks; k++) {
                    if (k > 0) sb.append(",");
                    // 小数点以下4桁に丸める（ファイルサイズ削減）
                    sb.append(String.format("%.4f", flows[k]));
                }
                sb.append("]");
            }
            sb.append("]}");

            // ファイル名: {method}_{yyyyMMdd}_{HHmmss}.json
            // 例: BisectionalPGEPS_20240115_143000.json
            String fileTimestamp = timestamp
                .replace("-", "").replace(":", "").replace("T", "_")
                .substring(0, 15);  // "yyyyMMdd_HHmmss"
            String filePath = OUTPUT_DIR + "/" + method + "_" + fileTimestamp + ".json";
            Files.writeString(Paths.get(filePath), sb.toString());

            LogManager.getInstance().log(
                "DebugIterationRecorder: saved " + totalIterations +
                " iterations (" + numLinks + " links) to " + filePath);

        } catch (IOException e) {
            LogManager.getInstance().error("DebugIterationRecorder: failed to save", e);
        }

        // メモリ解放
        iterationFlows = null;
    }
}
