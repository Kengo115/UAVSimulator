# refactoring-RedisMode ブランチ：設計変更ガイド

**ブランチ**: `refactoring-RedisMode`
**目的**: MEMORYモードの廃止・REDISモード専用化を経て、疎結合アーキテクチャへ移行する

---

## 背景

これまでのコードには2つのワーカーモードが混在していた。

| モード | 実装 | 状態 |
|---|---|---|
| **MEMORY** | `UAVFlyScheduler` による2秒ポーリング | **廃止対象** |
| **REDIS** | `AsyncUAVWorker×4` + `FlightScheduler` によるイベント駆動 | **継続・メインパス** |

MEMORYモードは比較実験用として残されていたが実験完了後は不要となった。
また、将来的に Network Manager 側（Worker + Redis）を独立OSS化する方針のため、
まず両モードを分離してコードを整理する。

---

## 移行ロードマップ

```
[現在]                [Step 1 ★]          [Step 2]              [Step 3]
MEMORYモード混在  →  MEMORYモード削除  →  同一リポジトリ内   →  別リポジトリへ
REDISモード混在       REDIS専用クリーン     モジュール分割        分離・OSS化
                      コードベース          operator/
                                           network-manager/
```

---

## ドキュメント一覧

| ファイル | 内容 |
|---|---|
| [01_MEMORY_MODE_REMOVAL.md](./01_MEMORY_MODE_REMOVAL.md) | ★ Step 1: MEMORYモード削除の詳細計画 |
| [02_DECOUPLED_ARCHITECTURE.md](./02_DECOUPLED_ARCHITECTURE.md) | Step 2/3: 疎結合アーキテクチャ設計 |
