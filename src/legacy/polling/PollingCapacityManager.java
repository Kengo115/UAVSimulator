package legacy.polling;

import shared.item.Link;

/**
 * Option A (ポーリング方式) 容量管理クラス
 *
 * per-UAV トランザクション方式への移行により、バッチ更新は廃止。
 * 容量の増減は PollingUAVFlightController がリンク進入・退出時に即時実行する。
 * このクラスは後方互換のため残すが、updateCapacity() は何もしない。
 */
public class PollingCapacityManager {

    /**
     * @deprecated per-UAV トランザクション方式に移行済み。呼び出し不要。
     */
    @Deprecated
    public static void updateCapacity(int[][] flyingUAV, Link[][] link, int node) {
        // per-UAV 即時更新に移行したため、バッチ更新は不要
    }
}
