package server.scheduler;

import client.Client;
import controller.BoundaryController;
import controller.ClientScheduleLoader;
import item.BeaconCluster;
import item.Flow;
import server.config.SimulationConfig;
import server.util.LogManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Phase 7-8: ランダムクライアント生成器
 *
 * スケジュールファイルなしでランダムにクライアントを生成する。
 * シード値による再現性を確保。
 */
public class RandomClientGenerator {

    private final Random random;
    private final long seed;
    private final int nodeCount;
    private final BeaconCluster beaconCluster;

    // 生成カウンター
    private int generatedClientCount = 0;

    // デフォルト設定
    private int minUavCount = 1;
    private int maxUavCount = 5;
    private double minIntervalSec = 1.0;
    private double maxIntervalSec = 3.0;

    /**
     * コンストラクタ
     * @param seed 乱数シード（再現性確保用）
     * @param nodeCount ノード数
     * @param beaconCluster ビーコンクラスター
     */
    public RandomClientGenerator(long seed, int nodeCount, BeaconCluster beaconCluster) {
        this.seed = seed;
        this.random = new Random(seed);
        this.nodeCount = nodeCount;
        this.beaconCluster = beaconCluster;

        LogManager.getInstance().log("Phase 7-8: RandomClientGenerator初期化 (seed=" + seed + ", nodes=" + nodeCount + ")");
    }

    /**
     * SimulationConfigから設定を適用
     * @param config シミュレーション設定
     */
    public void applyConfig(SimulationConfig config) {
        if (config == null) return;

        // Phase1の設定をデフォルトとして使用
        SimulationConfig.Phase1Settings phase1 = config.getPhases().getPhase1();
        this.minUavCount = phase1.getMinUavCount();
        this.maxUavCount = phase1.getMaxUavCount();
        this.minIntervalSec = phase1.getMinIntervalSec();
        this.maxIntervalSec = phase1.getMaxIntervalSec();

        LogManager.getInstance().log("Phase 7-8: 設定適用 (UAV=" + minUavCount + "-" + maxUavCount +
                ", interval=" + minIntervalSec + "-" + maxIntervalSec + "s)");
    }

    /**
     * UAV台数の範囲を設定
     * @param min 最小UAV数
     * @param max 最大UAV数
     */
    public void setUavCountRange(int min, int max) {
        this.minUavCount = min;
        this.maxUavCount = max;
    }

    /**
     * 生成間隔の範囲を設定
     * @param minSec 最小間隔（秒）
     * @param maxSec 最大間隔（秒）
     */
    public void setIntervalRange(double minSec, double maxSec) {
        this.minIntervalSec = minSec;
        this.maxIntervalSec = maxSec;
    }

    /**
     * ランダムな始点・終点ペアを生成
     * @return [始点ID, 終点ID]
     */
    public int[] generateRandomSourceDest() {
        int source = random.nextInt(nodeCount);
        int dest;
        do {
            dest = random.nextInt(nodeCount);
        } while (dest == source); // 始点と終点が同じにならないようにする

        return new int[]{source, dest};
    }

    /**
     * ランダムなUAV台数を生成
     * @return UAV台数
     */
    public int generateRandomUavCount() {
        return minUavCount + random.nextInt(maxUavCount - minUavCount + 1);
    }

    /**
     * ランダムな生成間隔を生成
     * @return 間隔（秒）
     */
    public double generateRandomInterval() {
        return minIntervalSec + random.nextDouble() * (maxIntervalSec - minIntervalSec);
    }

    /**
     * 単一のスケジュールエントリを生成
     * @return スケジュールエントリ
     */
    public ClientScheduleLoader.ScheduleEntry generateEntry() {
        int[] sourceDest = generateRandomSourceDest();
        int uavCount = generateRandomUavCount();
        double interval = generateRandomInterval();

        generatedClientCount++;

        ClientScheduleLoader.ScheduleEntry entry = new ClientScheduleLoader.ScheduleEntry(
                generatedClientCount,
                sourceDest[0],
                sourceDest[1],
                uavCount,
                (int) Math.round(interval)
        );

        LogManager.getInstance().log(String.format(
                "Phase 7-8: クライアント生成 #%d (source=%d, dest=%d, uav=%d, interval=%.1fs)",
                generatedClientCount, sourceDest[0], sourceDest[1], uavCount, interval
        ));

        return entry;
    }

    /**
     * 複数のスケジュールエントリを一括生成
     * @param count 生成数
     * @return スケジュールエントリのリスト
     */
    public List<ClientScheduleLoader.ScheduleEntry> generateEntries(int count) {
        List<ClientScheduleLoader.ScheduleEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(generateEntry());
        }
        return entries;
    }

    /**
     * Clientオブジェクトを直接生成
     * @param boundaryController BoundaryController（クライアント生成用）
     * @return 生成されたClient
     */
    public Client generateClient(BoundaryController boundaryController) {
        ClientScheduleLoader.ScheduleEntry entry = generateEntry();
        return boundaryController.createClientFromSchedule(entry);
    }

    /**
     * 次の生成までの待機時間を取得（ミリ秒）
     * @return 待機時間（ミリ秒）
     */
    public long getNextIntervalMillis() {
        return (long) (generateRandomInterval() * 1000);
    }

    /**
     * 指定した設定でクライアントを生成
     * @param minUav 最小UAV数
     * @param maxUav 最大UAV数
     * @param minInterval 最小間隔（秒）
     * @param maxInterval 最大間隔（秒）
     * @return スケジュールエントリ
     */
    public ClientScheduleLoader.ScheduleEntry generateEntryWithParams(
            int minUav, int maxUav, double minInterval, double maxInterval) {

        int[] sourceDest = generateRandomSourceDest();
        int uavCount = minUav + random.nextInt(maxUav - minUav + 1);
        double interval = minInterval + random.nextDouble() * (maxInterval - minInterval);

        generatedClientCount++;

        return new ClientScheduleLoader.ScheduleEntry(
                generatedClientCount,
                sourceDest[0],
                sourceDest[1],
                uavCount,
                (int) Math.round(interval)
        );
    }

    /**
     * 生成されたクライアント数を取得
     * @return 生成数
     */
    public int getGeneratedClientCount() {
        return generatedClientCount;
    }

    /**
     * シード値を取得
     * @return シード値
     */
    public long getSeed() {
        return seed;
    }

    /**
     * 乱数生成器をリセット（同じシードで再初期化）
     */
    public void reset() {
        random.setSeed(seed);
        generatedClientCount = 0;
        LogManager.getInstance().log("Phase 7-8: RandomClientGeneratorリセット (seed=" + seed + ")");
    }

    /**
     * 現在の設定をログ出力
     */
    public void logSettings() {
        LogManager.getInstance().log("Phase 7-8 [RandomClientGenerator設定]:");
        LogManager.getInstance().log("  seed=" + seed);
        LogManager.getInstance().log("  nodeCount=" + nodeCount);
        LogManager.getInstance().log("  uavCount=" + minUavCount + "-" + maxUavCount);
        LogManager.getInstance().log("  interval=" + minIntervalSec + "-" + maxIntervalSec + "s");
        LogManager.getInstance().log("  generatedCount=" + generatedClientCount);
    }
}
