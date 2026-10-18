# メモリモード調査報告書

**作成日**: 2026-10-18
**調査目的**: メモリモード vs Redisモードの比較実験に向けた現状把握
**ブランチ**: fix-simulation → MemoryMode

---

## 目次

1. [調査結果サマリー](#調査結果サマリー)
2. [WorkerModeの現状](#workermodeの現状)
3. [削除されたメモリモード関連クラス](#削除されたメモリモード関連クラス)
4. [現在のRedisモード一本化構成](#現在のredisモード一本化構成)
5. [トポロジファイルの現状](#トポロジファイルの現状)
6. [比較実験に向けた方針](#比較実験に向けた方針)

---

## 調査結果サマリー

| 項目 | 状態 |
|---|---|
| メモリモードの動作可否 | **不可** — コミット `81600d7`（2026-04-05）で完全削除済み |
| WorkerMode enum の存在 | **なし** — 削除済み |
| 復旧難易度 | **高** — 388行以上の再実装が必要 |
| Redisモードの状態 | **一本化** — 全RouteSearcherが `runUAVFlowRedis()` に統一 |
| 中規模トポロジ | **なし** — 小規模（6ノード）・大規模（221ノード）のみ |

---

## WorkerModeの現状

### 経緯

当初、`BoundaryController` には `WorkerMode.MEMORY` と `WorkerMode.REDIS` の切り替え機能があったが、Redisアーキテクチャが安定したタイミングでメモリモードを完全削除。

### 削除コミット

```
コミット: 81600d7
日時: 2026-04-05
メッセージ: メモリモードの完全削除
```

### 現在の状態

`BoundaryController.java` は常にRedisモード前提で起動する。
メモリモードへの分岐フラグ・定数・コードパスは一切残っていない。

```java
// 現在のmain()フロー（常にRedis前提）
RedisConnectionManager.getInstance().connect();   // Redis接続（必須）
initializeRedisWorker();                           // AsyncUAVWorker x4 起動
// → メモリモードの分岐なし
```

---

## 削除されたメモリモード関連クラス

### 削除済みクラス一覧

| クラス名 | 行数 | 役割 |
|---|---|---|
| `UAVFlyScheduler.java` | 188行 | ポーリング方式のスケジューラ（100msごとに全UAVをチェック） |
| `UAVFlightController.java` | 274行 | `flyingUAV[][]` 配列によるUAV追跡・制御 |
| `CapacityManager.java` | 52行 | JVMヒープ上のリンク容量管理 |

**合計削除行数**: 514行以上

### メモリモードの動作原理（削除前）

```java
// UAVFlyScheduler の処理イメージ（復元）
while (true) {
    checkAllUAVs();    // 全飛行中UAVをポーリング（O(n)）
    sleep(100ms);      // 定周期で繰り返し
}

// UAVFlightController の管理
int[][] flyingUAV;    // [fromNode][toNode] = 飛行中UAV数
```

**問題点**: UAV台数 n が増えるとポーリング1ループの処理時間が O(n) で増大し、
処理が追いつかなくなるタイムラグが発生する。

### コードに残る痕跡

`LinkCapacityManager.java` にシグネチャの後方互換として `flyingUAV` パラメータが残っているが、内部では未使用：

```java
// LinkCapacityManager.java（行88-100付近）
public void updateAllCapacities(Link[][] link, int[][] flyingUAV, int node) {
    // flyingUAV パラメータは過去の遺物（未使用）
}
```

---

## 現在のRedisモード一本化構成

### アーキテクチャ全体図

```
BoundaryController.main()
  │
  ├─ Redis接続 ───────────────────────── Redis Server (Redisson)
  │
  ├─ RouteSearcher（経路計算）
  │   ├─ DijkstraRouteSearcher
  │   └─ AbstractPhysarumSolverRouteSearcher（EPS系）
  │       └─ runUAVFlowRedis() ← 全手法で統一
  │
  └─ UAVJobQueue（Redis RQueue）に投入
       │
       └─ AsyncUAVWorker x4（バックグラウンド）
            │
            ├─ dequeueJob()（ブロッキング取得）
            ├─ LinkCapacityManager.tryConsumeCapacity()（Lua原子操作）
            │   ├─ 容量あり → FlightScheduler.startFlight()
            │   └─ 容量なし → WaitingUAVManager.enqueue()
            │
            └─ FlightScheduler（ScheduledThreadPoolExecutor: 16〜32スレッド）
                 ├─ scheduleNextLink()：リンク飛行をタイマースケジュール
                 ├─ onLinkPassed()：リンク通過時のイベント処理
                 └─ onFlightCompleted()：飛行完了 → Pub/Sub通知
```

### メモリモードとの設計比較

| 項目 | メモリモード（削除済み） | Redisモード（現行） |
|---|---|---|
| 処理方式 | ポーリング（100ms周期） | イベント駆動 |
| スレッド数 | シングルスレッド | 4 Worker + 16〜32 Scheduler |
| 容量管理 | `int[][] flyingUAV`（JVMヒープ） | Redis Lua原子操作 |
| 待機管理 | なし（ポーリングで都度チェック） | Redis RDeque（リンク別FIFO） |
| スケーラビリティ | O(n) 劣化 | イベント数比例（台数非依存） |
| プロセス | 単一プロセス | マルチプロセス対応 |

### RouteSearcherの一本化

全RouteSearcherで `runUAVFlowRedis()` が呼ばれる：

```java
// DijkstraRouteSearcher.java
private void runUAVFlow(Client client, int[] path, int requiredUAVs) {
    runUAVFlowRedis(client, path, requiredUAVs);  // 常にRedis
}

// AbstractPhysarumSolverRouteSearcher.java
protected void runUAVFlow(int startNode, int goalNode, int requiredUAVs, Client client) {
    runUAVFlowRedis(startNode, goalNode, requiredUAVs, client);  // 常にRedis
}
```

---

## トポロジファイルの現状

### 存在するトポロジ

| ファイル | ノード数 | リンク数 | 用途 |
|---|---|---|---|
| `config/topology/default_topology.txt` | 6 | 8 | 小規模テスト・デモ |
| `config/topology/test_topology.txt` | 3 | 少数 | 最小テスト |
| `config/topology/koriyama_topology.txt` | 221 | 602 | 大規模シミュレーション |
| **中規模** | **なし** | **なし** | **未作成** |

### トポロジファイルのフォーマット

`TopologyFileReader.java` が読み込むフォーマット：

```
NODES <ノード数>
NODE <id> <x> <y> [<districtType>]
...
LINK <source> <dest> <capacity> <distance> <lTubeLength>
...
```

**制約**：
- ノードIDは0から連続（非連続はバリデーションエラー）
- x, y は 0.0〜1.0 の正規化座標
- districtType：0 = 点在地区（容量8）、1 = 集中地区（容量4）
- lTubeLength：最小距離基準の相対値（EPS系手法で使用）

---

## 比較実験に向けた方針

### 問題の整理

比較実験を行うために解決すべき課題は以下の2点：

1. **メモリモードが存在しない** → 再実装が必要
2. **中規模トポロジがない** → UAV高密度環境での比較ができない

### 方針の選択肢

#### 案A：比較用メモリモードを新規実装

削除された3クラスを再実装し、`BoundaryController` にモード分岐を追加する。
本番コードとは完全分離（`legacy/` パッケージ等）で実装する。

- **メリット**: 学会発表で「実際に動かして比較した」と言える
- **デメリット**: 実装コストが高い（514行以上の再実装）

#### 案B：旧コミットからメモリモードの結果を採取

コミット `81600d7` より前の状態でメモリモードを動かし、結果だけを採取して比較する。

- **メリット**: 再実装不要
- **デメリット**: 条件を完全に揃えた公平な比較が難しい

### 中規模トポロジの作成（共通課題）

どちらの案でも、段階的な負荷比較のために中規模トポロジが必要。
ノード数・リンク密度・容量を指定して自動生成するスクリプトを作成することで対応可能。

**想定する中規模トポロジの規模感**：

| 規模 | ノード数 | リンク数 |
|---|---|---|
| 小規模（既存） | 6 | 8 |
| 中規模（新規作成） | 30〜60 | 60〜150 |
| 大規模（既存） | 221 | 602 |

---

## 関連ドキュメント

- [ARCHITECTURE.md](../phases/ARCHITECTURE.md) — メモリモード→Redisモードの設計全体
- [PHASE3B_2_MINUTES.md](../phases/PHASE3B_2_MINUTES.md) — ジョブキュー・ワーカー実装の経緯
- [PHASE3B_3_MINUTES.md](../phases/PHASE3B_3_MINUTES.md) — イベントスケジューリング移行の経緯
