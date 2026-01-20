package controller;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * クライアントスケジュールをCSVファイルから読み込むクラス
 *
 * CSVフォーマット:
 * clientId,sourceId,destinationId,uavCount,intervalAfterSec
 *
 * 例:
 * # コメント行（#で始まる行は無視）
 * 1,0,5,25,30
 * 2,2,4,14,30
 * 3,1,3,20,0
 */
public class ClientScheduleLoader {

    /**
     * クライアントスケジュールエントリ
     */
    public static class ScheduleEntry {
        public final int clientId;
        public final int sourceId;
        public final int destinationId;
        public final int uavCount;
        public final double intervalAfterSec;

        public ScheduleEntry(int clientId, int sourceId, int destinationId, int uavCount, int intervalAfterSec) {
            this.clientId = clientId;
            this.sourceId = sourceId;
            this.destinationId = destinationId;
            this.uavCount = uavCount;
            this.intervalAfterSec = intervalAfterSec;
        }

        public ScheduleEntry(int clientId, int sourceId, int destinationId, int uavCount, double intervalAfterSec) {
            this.clientId = clientId;
            this.sourceId = sourceId;
            this.destinationId = destinationId;
            this.uavCount = uavCount;
            this.intervalAfterSec = intervalAfterSec;
        }

        @Override
        public String toString() {
            return String.format("ScheduleEntry[client=%d, source=%d, dest=%d, uavs=%d, interval=%.2fs]",
                                 clientId, sourceId, destinationId, uavCount, intervalAfterSec);
        }
    }

    /**
     * CSVファイルからスケジュールを読み込む
     *
     * @param filePath CSVファイルのパス
     * @return スケジュールエントリのリスト
     * @throws IOException ファイル読み込みエラー
     */
    public static List<ScheduleEntry> load(String filePath) throws IOException {
        List<ScheduleEntry> entries = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                // 空行とコメント行をスキップ
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // CSV解析
                String[] parts = line.split(",");
                if (parts.length < 5) {
                    System.err.println("警告: 行 " + lineNumber + " の形式が不正です（5列必要）: " + line);
                    continue;
                }

                try {
                    int clientId = Integer.parseInt(parts[0].trim());
                    int sourceId = Integer.parseInt(parts[1].trim());
                    int destinationId = Integer.parseInt(parts[2].trim());
                    int uavCount = Integer.parseInt(parts[3].trim());
                    int intervalAfterSec = Integer.parseInt(parts[4].trim());

                    // バリデーション
                    if (clientId <= 0) {
                        System.err.println("警告: 行 " + lineNumber + " のクライアントIDが0以下です: " + line);
                        continue;
                    }
                    if (sourceId < 0 || destinationId < 0) {
                        System.err.println("警告: 行 " + lineNumber + " のノードIDが負です: " + line);
                        continue;
                    }
                    if (uavCount <= 0) {
                        System.err.println("警告: 行 " + lineNumber + " のUAV数が0以下です: " + line);
                        continue;
                    }
                    if (intervalAfterSec < 0) {
                        System.err.println("警告: 行 " + lineNumber + " の待機時間が負です: " + line);
                        continue;
                    }
                    if (sourceId == destinationId) {
                        System.err.println("警告: 行 " + lineNumber + " の出発地と目的地が同じです: " + line);
                        continue;
                    }

                    entries.add(new ScheduleEntry(clientId, sourceId, destinationId, uavCount, intervalAfterSec));

                } catch (NumberFormatException e) {
                    System.err.println("警告: 行 " + lineNumber + " の数値解析に失敗しました: " + line);
                }
            }
        }

        System.out.println("スケジュール読み込み完了: " + entries.size() + " クライアント");
        for (int i = 0; i < entries.size(); i++) {
            System.out.println("  [" + (i + 1) + "] " + entries.get(i));
        }

        return entries;
    }

    /**
     * デフォルトのスケジュールファイルパス
     */
    public static final String DEFAULT_SCHEDULE_PATH = "config/schedule/client_schedule.csv";
}
