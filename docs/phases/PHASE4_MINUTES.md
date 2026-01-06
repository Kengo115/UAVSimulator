# Phase 4 実装議事録：経路待ちキュー管理と圧力誘導EPSルートサーチャー

**実施日**: 2025-01-06
**担当者**: Claude (Opus 4.5)

---

## 概要

Phase 4では、EPSによるフロー計算で割り当てられなかったUAVを「経路待ち」として管理し、
先行するUAVの第一ホップ通過時に経路をコピーして飛行を開始する仕組みを実装しました。

これにより、ネットワーク容量に応じた適応的なUAV投入が可能になります。

---

## 問題の背景

### 従来の課題

1. **EPSのフロー制限**: 圧力誘導EPSでは、ネットワークの安定性を保つためにフロー値を動的に調整する
2. **要求UAV数との乖離**: 要求フロー（必要UAV数）に対し、EPSが割り当て可能なフロー値が少ない場合がある
3. **残りUAVの処理**: EPS割り当て分に含まれなかったUAVの処理方法が未定義だった

### 解決方針

- **経路待ちキュー**: EPS割り当て外のUAVを経路待ちキューで管理
- **経路コピー**: 先行UAVの第一ホップ通過時に経路をコピーして飛行開始
- **時間計測**: 事業者ごとの経路割り当てから全UAV完了までの時間を計測

---

## 新規追加ファイル

### 1. PathWaitingManager.java

**場所**: `src/server/redis/PathWaitingManager.java`

経路待ちUAVをクライアント別にFIFOキューで管理するクラス。

```java
/**
 * 経路待ちUAV管理クラス
 * Phase 4: PG-EPS用の経路待ちキュー管理
 *
 * クライアント別の経路待ちキュー（FIFO）を管理する
 * 待機キーの形式: waiting:path:{clientId}
 */
public class PathWaitingManager {
    // Redisキュー操作
    public void enqueue(int clientId, UAVJob job);
    public UAVJob dequeue(int clientId);
    public boolean hasWaitingUAV(int clientId);
    public int getWaitingCount(int clientId);
    public void clear(int clientId);
    public void clearAll();
}
```

**主要機能**:
- クライアント別の経路待ちキュー（FIFO）
- Redisベースのキュー管理
- 待機キーの形式: `waiting:path:{clientId}`

---

### 2. ClientTimeManager.java

**場所**: `src/server/redis/ClientTimeManager.java`

事業者ごとの時間計測を管理するクラス。

```java
/**
 * 事業者ごとの時間計測管理クラス
 * Phase 4: 経路割り当てから全UAV飛行完了までの時間を計測
 *
 * 計測開始: 事業者の最初のUAVに経路が割り当てられた時点
 * 計測終了: 事業者の全UAVが飛行完了した時点
 */
public class ClientTimeManager {
    public void startClientTime(int clientId, int requiredUAVs);
    public void onUAVCompleted(int clientId);
    public boolean isStarted(int clientId);
    public int getCompletedCount(int clientId);
    public int getRequiredCount(int clientId);
}
```

**主要機能**:
- 経路割り当て開始時刻の記録
- UAV完了カウントの管理
- 全UAV完了時に `clientTime.csv` へ出力

**出力形式**:
```csv
clientId,uavCount,elapsedTime(s)
0,10,45.32
1,15,67.89
```

---

### 3. StepControlledPressureGuidedEPSRouteSearcher.java

**場所**: `src/server/route/StepControlledPressureGuidedEPSRouteSearcher.java`

段階制御型圧力誘導EPS。

**定数設定**:
```java
private static final int MAX_ITERATIONS = 1000;
private static final int REQUIRED_STABLE_ITERATIONS = 500;
private static final int STABILIZATION_ITERATIONS = 500;

// ソースノード圧力閾値
private static final double SOURCE_PRESSURE_WARNING = 70.0;
private static final double SOURCE_PRESSURE_EMERGENCY = 100.0;

// 圧力変化率の段階的閾値
private static final double SOURCE_PRESSURE_CHANGE_LEVEL1 = 0.07;  // 7%: 1UAV減少
private static final double SOURCE_PRESSURE_CHANGE_LEVEL2 = 0.12;  // 12%: 3UAV減少
private static final double SOURCE_PRESSURE_CHANGE_LEVEL3 = 0.15;  // 15%: 5UAV減少
```

**フロー制御ロジック**:
1. 要求フローから開始
2. ソース圧力を監視
3. 圧力変化率に応じて段階的にUAV数を減少
4. 500回連続安定でEPS収束
5. 残りUAVは経路待ちキューへ登録

---

### 4. BisectionalPressureGuidedEPSRouteSearcher.java

**場所**: `src/server/route/BisectionalPressureGuidedEPSRouteSearcher.java`

二分法型圧力誘導EPS。

**定数設定**:
```java
private static final int MAX_ITERATIONS = 1000;
private static final int REQUIRED_STABLE_ITERATIONS = 200;
private static final int MAX_BINARY_SEARCH_ITERATIONS = 30;
private static final int STABILIZATION_PHASE_ITERATIONS = 5;

// ソースノード圧力閾値
private static final double SOURCE_PRESSURE_EMERGENCY = 100;
private static final double SOURCE_PRESSURE_CHANGE_THRESHOLD = 0.30;  // 30%増加でフロー減少
private static final double SOURCE_PRESSURE_REDUCTION_THRESHOLD = 0.50;  // 50%減少でフロー増加
```

**二分探索ロジック**:
1. lowerBound = 0, upperBound = 要求フロー
2. 圧力異常検知時: upperBound = currentFlow, 新フロー = (lower + upper) / 2
3. 圧力50%減少検知時: lowerBound = currentFlow, 新フロー = (lower + upper) / 2
4. EPSSavePointによる状態バックアップ/リストア
5. 安定化フェーズ後に次の判定

**状態管理**:
```java
private EPSSavePoint epsSavePoint = null;  // EPS状態のセーブポイント

// フロー変更時に状態を復元
epsSavePoint.saveEPSState(link, pressureCoefficient, ...);
epsSavePoint.restoreEPSState(link, pressureCoefficient, ...);
```

---

## 既存ファイルの変更

### 1. FlightScheduler.java

**変更内容**:

1. **PathWaitingManager統合**:
```java
private PathWaitingManager pathWaitingManager;  // Phase 4: 経路待ちUAV管理

// コンストラクタ内
this.pathWaitingManager = new PathWaitingManager();
```

2. **第一ホップ通過時の経路コピー処理**:
```java
// onLinkPassed()内
if (linkIndex == 0) {
    processFirstHopPathCopy(job);
}
```

3. **processFirstHopPathCopy()メソッド追加**:
```java
private void processFirstHopPathCopy(UAVJob job) {
    int clientId = job.getClientId();

    // 経路待ちUAVがいるか確認
    if (!pathWaitingManager.hasWaitingUAV(clientId)) {
        return;
    }

    // 経路待ちUAVを取り出し
    UAVJob waitingJob = pathWaitingManager.dequeue(clientId);

    // 経路をコピー
    int[] copiedPath = job.getPath().clone();
    waitingJob.setPath(copiedPath);

    // リンク距離をコピー
    waitingJob.setLinkDistances(job.getLinkDistances().clone());

    // ジョブキューに投入して飛行開始
    jobQueue.enqueueJob(waitingJob);
}
```

4. **UAV完了時の時間計測通知**:
```java
// onFlightCompleted()内
ClientTimeManager.getInstance().onUAVCompleted(job.getClientId());
```

5. **PathWaitingManager取得メソッド**:
```java
public PathWaitingManager getPathWaitingManager() {
    return pathWaitingManager;
}
```

---

### 2. UAVEventChannels.java

**変更内容**:

経路待ちキュー用の定数とメソッド追加:

```java
/**
 * 経路待ちキューのキープレフィックス
 * Phase 4: 経路待ちUAVをクライアント別に管理
 * 形式: waiting:path:{clientId}
 */
public static final String PATH_WAITING_QUEUE_PREFIX = "waiting:path:";

/**
 * 経路待ちキューのキーを生成
 */
public static String getPathWaitingQueueKey(int clientId) {
    return PATH_WAITING_QUEUE_PREFIX + clientId;
}
```

---

## 処理フロー

### EPS実行時の経路待ち登録

```
1. EPS実行 → 安定フロー値を決定（例: 要求10UAV → 安定8UAV）
2. 8UAVに経路を割り当て → 飛行開始
3. 残り2UAVを経路待ちキューに登録（path=null）
4. ClientTimeManager: 時間計測開始
```

### 第一ホップ通過時の経路コピー

```
1. UAV0が第一ホップ（source→node1）を通過
2. FlightScheduler.processFirstHopPathCopy()が呼び出される
3. 経路待ちキューからUAV8を取り出し
4. UAV0の経路をUAV8にコピー
5. UAV8をジョブキューに投入 → 飛行開始
6. UAV8が第一ホップを通過 → UAV9に経路コピー
7. ... 繰り返し
```

### 全UAV完了時

```
1. 各UAV完了時: ClientTimeManager.onUAVCompleted(clientId)
2. 完了カウントが必要UAV数に到達
3. 経過時間を計算
4. clientTime.csvに出力
```

---

## 2つのルートサーチャーの比較

| 項目 | StepControlled | Bisectional |
|------|----------------|-------------|
| 安定判定回数 | 500回 | 200回 |
| フロー減少方式 | 段階的（1/3/5 UAV） | 二分探索 |
| フロー増加方式 | 圧力10%減少で+1 UAV | 圧力50%減少で二分探索 |
| 状態復元 | なし | EPSSavePoint使用 |
| 安定化フェーズ | 50イテレーション猶予 | 5イテレーション |
| 圧力変化閾値 | 7%/12%/15% | 30% |

---

## 検証ポイント

| 項目 | 期待動作 | 確認方法 |
|------|----------|----------|
| 経路待ち登録 | EPS割当外UAVがキューに登録される | ログ確認 |
| 経路コピー | 第一ホップ通過時に経路がコピーされる | ログ確認 |
| 時間計測 | clientTime.csvに正しい時間が出力される | ファイル確認 |
| 全UAV飛行 | 要求UAV数が全て飛行完了する | 完了ログ確認 |

---

## ログ出力例

### 経路待ち登録
```
Phase 4 [経路待ち登録]: client0 UAV8 を経路待ちキューに追加 (s=0→d=5, 速度=12.34m/s, 待機数=1)
```

### 経路コピー
```
Phase 4 [経路待ち→飛行]: client0 UAV8 が経路待ちから飛行開始 (経路コピー元=UAV0, path=[0→1→3→5], 速度=12.34m/s, 残り経路待ち=1)
```

### 時間計測
```
Phase 4 [時間計測開始]: client0 経路割り当て開始 (必要UAV数=10)
Phase 4 [時間計測完了]: client0 全UAV飛行完了 (UAV数=10, 経過時間=45.32s)
Phase 4: clientTime.csv出力完了 (src/result/BISECTIONAL_PGEPS/time/clientTime.csv)
```

---

## 関連する変更（Phase 3からの継続）

- **容量待ちキュー**: `waiting:link:{from}:{to}` - リンク容量不足時の待機
- **経路待ちキュー**: `waiting:path:{clientId}` - EPS割当外UAVの待機（Phase 4で追加）
- **双方向リンク対応**: 容量回復時に逆方向の待機キューもチェック

---

## 今後の課題

1. **負荷分散**: 経路コピーではなく、別経路の探索も検討
2. **優先度制御**: 経路待ちUAVの投入順序の最適化
3. **メモリモード対応**: Redis未使用時のPathWaitingManager動作

