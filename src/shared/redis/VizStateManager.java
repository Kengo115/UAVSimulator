package shared.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import shared.util.LogManager;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UAV可視化状態管理クラス（シングルトン）
 *
 * シミュレーション中の全UAVの状態をRedis Hashに書き込む。
 * 可視化が有効な場合のみ動作し、Redis障害時も例外を握り潰して
 * シミュレーション本体に影響を与えない。
 *
 * Redis Hash キー: viz:sim_{SIM_ID}:states
 * フィールド: "{clientId}_{uavId}"
 * 値: JSON文字列
 *
 * UAV状態の種類:
 *   FLYING   - リンクを飛行中（赤）
 *   HOVERING - 上空待機中（青）: 飛行開始後、次ホップ容量超過でノード待機
 *   PRE_WAIT - 飛行前待機中（緑）: 経路割り当て待ち or 第一ホップ容量超過
 *   COMPLETED - 飛行完了（状態削除）
 */
public class VizStateManager {

    private static final VizStateManager INSTANCE = new VizStateManager();

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private volatile String simId = "1";
    private volatile RedissonClient redisClient;
    private volatile String statesKey;
    private volatile String simEndedKey;
    private final ObjectMapper mapper = new ObjectMapper();

    private VizStateManager() {}

    public static VizStateManager getInstance() {
        return INSTANCE;
    }

    /**
     * 可視化を有効化する。
     * BoundaryController からシミュレーション開始前に呼び出す。
     *
     * @param simId  シミュレーションID（"1", "2", ...）
     * @param client Redissonクライアント
     */
    public synchronized void enable(String simId, RedissonClient client) {
        this.simId = simId;
        this.redisClient = client;
        this.statesKey = "viz:sim_" + simId + ":states";
        this.simEndedKey = "viz:sim_" + simId + ":sim_ended";
        this.enabled.set(true);
        LogManager.getInstance().log("VizStateManager: 有効化 (simId=" + simId + ", key=" + statesKey + ")");
    }

    /**
     * 可視化を無効化する。
     */
    public void disable() {
        enabled.set(false);
        LogManager.getInstance().log("VizStateManager: 無効化");
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * シミュレーション終了をRedisに通知する。
     * BoundaryController のシミュレーション終了処理から呼び出す。
     */
    public void signalSimulationEnded() {
        if (!enabled.get() || redisClient == null) return;
        try {
            RBucket<String> bucket = redisClient.getBucket(simEndedKey);
            bucket.set("1");
            LogManager.getInstance().log("VizStateManager: シミュレーション終了通知 -> " + simEndedKey);
        } catch (Exception e) {
            LogManager.getInstance().error("VizStateManager.signalSimulationEnded エラー（無視）", e);
        }
    }

    /**
     * UAVが新しいリンクを飛行開始したことを報告する。
     *
     * @param uavId       UAV ID
     * @param clientId    クライアント ID
     * @param fromNode    出発ノード
     * @param toNode      到着ノード
     * @param linkStartMs リンク飛行開始時刻（System.currentTimeMillis()）
     * @param linkDistM   リンク距離（メートル）
     * @param speedMs     飛行速度（m/s）
     */
    public void reportFlying(int uavId, int clientId, int fromNode, int toNode,
                              long linkStartMs, double linkDistM, double speedMs) {
        if (!enabled.get()) return;
        Map<String, Object> state = new HashMap<>();
        state.put("status", "FLYING");
        state.put("clientId", clientId);
        state.put("uavId", uavId);
        state.put("fromNode", fromNode);
        state.put("toNode", toNode);
        state.put("linkStartMs", linkStartMs);
        state.put("linkDistM", linkDistM);
        state.put("speedMs", speedMs);
        state.put("updatedMs", System.currentTimeMillis());
        putState(uavId, clientId, state);
    }

    /**
     * UAVが上空待機状態になったことを報告する（飛行中に次ホップ容量超過）。
     *
     * @param uavId    UAV ID
     * @param clientId クライアント ID
     * @param waitNode 待機ノード
     */
    public void reportHovering(int uavId, int clientId, int waitNode) {
        if (!enabled.get()) return;
        Map<String, Object> state = new HashMap<>();
        state.put("status", "HOVERING");
        state.put("clientId", clientId);
        state.put("uavId", uavId);
        state.put("waitNode", waitNode);
        state.put("updatedMs", System.currentTimeMillis());
        putState(uavId, clientId, state);
    }

    /**
     * UAVが飛行前待機状態になったことを報告する（経路割り当て待ち or 第一ホップ容量超過）。
     *
     * @param uavId    UAV ID
     * @param clientId クライアント ID
     * @param waitNode 待機ノード（出発ノード）
     */
    public void reportPreWait(int uavId, int clientId, int waitNode) {
        if (!enabled.get()) return;
        Map<String, Object> state = new HashMap<>();
        state.put("status", "PRE_WAIT");
        state.put("clientId", clientId);
        state.put("uavId", uavId);
        state.put("waitNode", waitNode);
        state.put("updatedMs", System.currentTimeMillis());
        putState(uavId, clientId, state);
    }

    /**
     * UAVの飛行が完了したことを報告する（Redis Hashから削除）。
     *
     * @param uavId    UAV ID
     * @param clientId クライアント ID
     */
    public void reportCompleted(int uavId, int clientId) {
        if (!enabled.get() || redisClient == null) return;
        try {
            // StringCodec: JsonJacksonCodecによる二重エンコードを防ぎredis-pyで直接読める形式で保存
            RMap<String, String> map = redisClient.getMap(statesKey, StringCodec.INSTANCE);
            map.remove(clientId + "_" + uavId);
        } catch (Exception e) {
            LogManager.getInstance().error("VizStateManager.reportCompleted エラー（無視）", e);
        }
    }

    /**
     * 状態をRedis Hashに書き込む。例外はすべて握り潰す。
     * StringCodec を明示して JsonJacksonCodec による二重エンコードを防ぐ。
     */
    private void putState(int uavId, int clientId, Map<String, Object> state) {
        if (redisClient == null) return;
        try {
            String json = mapper.writeValueAsString(state);
            // StringCodec: redis-py が直接 json.loads() できる plain string として保存
            RMap<String, String> map = redisClient.getMap(statesKey, StringCodec.INSTANCE);
            map.put(clientId + "_" + uavId, json);
        } catch (Exception e) {
            LogManager.getInstance().error("VizStateManager.putState エラー（無視）", e);
        }
    }
}
