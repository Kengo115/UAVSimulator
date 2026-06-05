# refactoring-RedisMode ブランチ：設計変更ガイド

**ブランチ**: `refactoring-RedisMode`
**目的**: MEMORYモードの廃止・REDISモード専用化を経て、疎結合アーキテクチャへ移行する

---

## 背景

これまでのコードには2つのワーカーモードが混在していた。

| モード | 実装 | 状態 |
|---|---|---|
| **MEMORY** | `UAVFlyScheduler` による2秒ポーリング | **廃止済み** |
| **REDIS** | `AsyncUAVWorker×4` + `FlightScheduler` によるイベント駆動 | **継続・メインパス** |

MEMORYモードは比較実験用として残されていたが実験完了後は不要となった。
また、将来的に Network Manager 側（Worker + Redis）を独立OSS化する方針のため、
まず両モードを分離してコードを整理する。

---

## 移行ロードマップと進捗

```
[Step 1 ✅]          [Step 2 ✅]              [Step 3 ★次]          [Step 4]
MEMORYモード削除  →  同一リポジトリ内      →  Redis pub/sub      →  別リポジトリへ
REDIS専用クリーン     パッケージ分割            インターフェース化     分離・OSS化
コードベース          operator/
                      network_manager/
                      shared/
```

---

## 各Stepの状態

### ✅ Step 1: MEMORYモード完全削除（完了）
- コミット: `81600d7 メモリモードの完全削除`
- `UAVFlyScheduler` など MEMORY モード関連クラスを全削除
- REDIS モード専用のクリーンなコードベースに整理

### ✅ Step 2: パッケージ分割（完了）
- コミット: `18ecc3e 疎結合なディレクトリ構成に変更`
- 60ファイルを `git mv` で新パッケージへ移動（git履歴保持）

**新パッケージ構成**:
```
src/
├── operator/          # 経路探索・UAV割当（BoundaryController, RouteSearcher群, scheduler）
├── network_manager/   # 飛行実行・容量管理（AsyncUAVWorker, FlightScheduler, redis管理群）
└── shared/            # 共通クラス（Client, Uav, RedisConnectionManager, LogManager等）
```

**Step 2 完了後の追加修正**（バグ修正・動作確認で発覚）:
- `Makefile` の mainClass を `controller.BoundaryController` → `operator.BoundaryController` に修正
- `SearcherRetryManager.java` のコード本体中に残存していた `controller.BoundaryController` 完全修飾参照を修正
- 結果ファイル出力パスのハードコード4箇所を `BoundaryController.getResultDir()` 経由に統一（`FlightDataRecorder`, `ClientTimeManager`, `PhaseController` ×2）
- `AbstractPhysarumSolverRouteSearcher` の route 出力クライアント番号ずれ（+1オフセット）を修正

### ★ Step 3: Redis pub/sub インターフェース化（次のステップ）
- 詳細: [02_DECOUPLED_ARCHITECTURE.md](./02_DECOUPLED_ARCHITECTURE.md)
- operator → network_manager への直接メソッド呼び出しを Redis pub/sub 経由に置き換える
- 主な変更: `FlightScheduler.scheduleUAVJob()` の直接呼び出し → `flight:submit` チャンネル経由

### Step 4: 別リポジトリへの分離・OSS化（未着手）
- `network_manager/` を独立リポジトリへ
- `shared/` を Maven 共有ライブラリへ

---

## ドキュメント一覧

| ファイル | 内容 |
|---|---|
| [01_MEMORY_MODE_REMOVAL.md](./01_MEMORY_MODE_REMOVAL.md) | Step 1: MEMORYモード削除の詳細 |
| [02_DECOUPLED_ARCHITECTURE.md](./02_DECOUPLED_ARCHITECTURE.md) | Step 2/3: 疎結合アーキテクチャ設計 |
