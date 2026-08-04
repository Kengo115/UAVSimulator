# デバッグモード 開発ロードマップ

## 概要

デバッグモードは通常シミュレーションと完全独立した動作モード。
`make run debug` で起動し、ブラウザUI上で事業者生成・経路確認・飛行制御をインタラクティブに操作できる。

---

## 技術スタック

| レイヤー | 技術 | 備考 |
|---------|------|------|
| フロントエンド | Vue 3 (CDN) + SVG | ビルドステップ不要。unpkg.com アクセス確認済み |
| バックエンド | FastAPI + uvicorn | viz_server.py と同一技術、完全独立 |
| リアルタイム通信 | WebSocket | UAV状態のリアルタイム表示 |
| Java↔Python通信 | Redis pub/sub (`debug:command` チャンネル) | コマンド送受信 |
| 経路結果受け渡し | Redis Hash (`debug:route_results`) | 割り当て完了通知 |
| 静的ファイル配信 | FastAPI StaticFiles | `scripts/debug_static/` を配信 |

### ファイル構成

```
scripts/
  viz_server.py              # 既存（変更なし）
  debug_server.py            # 新規: FastAPIサーバー（ポート9001固定）
  debug_static/
    index.html               # Vue 3 エントリーポイント
    app.js                   # Vue 3 アプリロジック
    style.css                # スタイル

src/operator/
  BoundaryController.java    # make run debug 分岐追加
  DebugModeController.java   # 新規: デバッグモード全制御

src/network_manager/scheduler/
  FlightScheduler.java       # pause/resume 機能追加

src/operator/route/
  AbstractPhysarumSolverRouteSearcher.java  # pendingJobs フック追加
  DijkstraRouteSearcher.java                # 同上
```

---

## アーキテクチャ概要

```
[ブラウザ]
    ↕ HTTP REST / WebSocket
[debug_server.py (FastAPI, port:9001)]
    ↕ Redis pub/sub (debug:command)
    ↕ Redis Hash    (debug:route_results, viz:sim_1:states)
[DebugModeController.java]
    ↕
[FlightScheduler / RouteSearcher / AsyncUAVWorker]
```

### コマンドフロー例（一時停止）
```
UI → POST /api/debug/pause
  → debug_server が Redis publish "debug:command" "PAUSE"
  → DebugModeController が subscribe して受信
  → FlightScheduler.setPaused(true)
  → onLinkPassed() が isPaused=true を検出 → 自分自身を200ms後に再スケジュール
```

---

## 経路割り当て→表示→飛行開始フロー

```
① 事業者生成ボタン押下
② モーダル表示: S-D選択（ノードをクリック: 1クリック目=S, 2クリック目=D）
③ UAV台数入力
④ 「経路割り当て」ボタン押下 → モーダルはロック（戻れない）
   └ POST /api/debug/assign { src, dst, uav_count }
⑤ debug_server → Redis publish "debug:command" "ASSIGN:src:dst:uav_count:method"
⑥ DebugModeController が受信 → routeRequest() 実行
⑦ RouteSearcher が pendingJobs を全UAV分構築
   ★フックポイント: pendingJobs 確定後、enqueue前に割り込み
⑧ pendingJobs を Redis Hash "debug:route_results:{clientId}" に書き込み
   + Redis publish "debug:command" "ROUTES_READY:{clientId}"
⑨ debug_server が ROUTES_READY を受信 → WebSocketで UI に通知
⑩ UI: ローディング解除 → 「割り当て結果を表示」ボタン出現
⑪ ボタン押下 → GET /api/debug/route-results/{clientId}
⑫ モーダルのトポロジ上に飛行経路を太線矢印で描画
   + 同一経路のノードごとにUAV台数表示
   + 飛行前待機UAV数を明示
⑬ 「飛行開始」ボタン押下 → POST /api/debug/fly/{clientId}
   → Redis set "debug:fly_approved:{clientId}" "1"
   → DebugModeController が検知 → enqueueScheduler でジョブ一括投入
⑭ 「飛行中に戻る」ボタン → モーダルを閉じてトポロジ表示に戻る
```

### 経路割り当て結果のRedis保存フォーマット

```json
// debug:route_results:{clientId}
{
  "clientId": 1,
  "src": 10,
  "dst": 200,
  "uavCount": 5,
  "status": "ready",
  "uavs": [
    {
      "uavId": 1,
      "path": [10, 25, 47, 200],
      "linkDistances": [500.0, 320.0, 410.0],
      "speed": 12.5,
      "delaySeconds": 0,
      "isPathWaiting": false
    },
    {
      "uavId": 2,
      "path": [10, 25, 47, 200],
      "linkDistances": [500.0, 320.0, 410.0],
      "speed": 10.0,
      "delaySeconds": 2,
      "isPathWaiting": false
    }
  ]
}
```

---

## 一時停止/再開/初期化

### 一時停止（Bレベル: 完全停止）

```java
// FlightScheduler.onLinkPassed() 先頭に追加
if (isPaused.get()) {
    scheduler.schedule(() -> onLinkPassed(job, linkIndex), 200, MILLISECONDS);
    return;  // スレッドはブロックせず再スケジュールで待機
}
```

- スレッドをブロックしないため、スレッドプールを消費しない
- JS側: `renderNowMs` を pauseTime で固定 → UAVが視覚的にその場に止まる
- 事業者生成中（ローディング中）は PAUSE を受け付けない

### 初期化（Reset）

```
UI → POST /api/debug/reset
  → Redis flushdb（SIM_ID=1専用のため影響なし）
  → DebugModeController が FlightScheduler.resetCounters() + AsyncUAVWorker 再起動
  → WebSocket で UI に "reset" 通知 → UAV一覧クリア・カウンタリセット
```

---

## 開発フェーズ

### Phase 1: 基盤構築（最初に実装）

**目標**: `make run debug` で起動し、トポロジが表示される状態にする

| タスク | 対象ファイル | 内容 |
|--------|------------|------|
| 1-1 | `Makefile` | `run-debug` ターゲット追加（`DEBUG_MODE=true` 環境変数 + port 9001） |
| 1-2 | `BoundaryController.java` | `main()` に `DEBUG_MODE` 分岐 → `DebugModeController.start()` |
| 1-3 | `DebugModeController.java` | 新規作成: Redis subscribe, debug_server.py 起動, 基本ループ |
| 1-4 | `debug_server.py` | FastAPI 骨格 + StaticFiles マウント + WebSocket エンドポイント |
| 1-5 | `debug_static/index.html` | Vue 3 CDN + 基本レイアウト（トポロジ表示エリア + サイドバー） |
| 1-6 | `debug_static/app.js` | トポロジ描画（viz_server.py のJS部分を移植・Vue化） |
| 1-7 | `debug_static/style.css` | ベーススタイル |

完了条件: `make run debug` → `http://localhost:9001` でトポロジが見える

---

### Phase 2: 事業者生成フロー（コア機能）

**目標**: S-D選択→UAV数入力→経路割り当て→結果表示→飛行開始が動く

| タスク | 対象ファイル | 内容 |
|--------|------------|------|
| 2-1 | `app.js` | 事業者生成ボタン + トポロジモーダル |
| 2-2 | `app.js` | ノードクリックでS-D選択（1クリック目=S:青, 2クリック目=D:赤） |
| 2-3 | `app.js` | UAV台数入力 + 「経路割り当て」ボタン + ローディング表示 |
| 2-4 | `debug_server.py` | `POST /api/debug/assign` エンドポイント → Redis pub/sub で Java へ |
| 2-5 | `DebugModeController.java` | ASSIGN コマンド受信 → routeRequest() 非同期実行 |
| 2-6 | `AbstractPhysarumSolverRouteSearcher.java` | pendingJobs 確定後のフック: Redis への書き込み + ROUTES_READY publish |
| 2-7 | `DijkstraRouteSearcher.java` | 同上（Dijkstra用） |
| 2-8 | `debug_server.py` | ROUTES_READY 受信 → WebSocket で UI 通知 |
| 2-9 | `app.js` | 「割り当て結果を表示」ボタン表示 → `GET /api/debug/route-results` |
| 2-10 | `app.js` | 飛行経路を太線矢印でSVG描画（ノードごとにUAV台数表示） |
| 2-11 | `app.js` | 「飛行開始」ボタン → POST /api/debug/fly → ジョブ一括投入 |
| 2-12 | `app.js` | 「飛行中に戻る」ボタン → モーダルクローズ |

完了条件: エンドツーエンドで事業者生成→飛行開始が動く

---

### Phase 3: 一時停止/再開/初期化

**目標**: 飛行中のUAVを完全停止・再開・全削除できる

| タスク | 対象ファイル | 内容 |
|--------|------------|------|
| 3-1 | `FlightScheduler.java` | `AtomicBoolean isPaused` + `setPaused()` + `onLinkPassed()` 再スケジュール |
| 3-2 | `DebugModeController.java` | PAUSE/RESUME コマンド受信 → FlightScheduler.setPaused() |
| 3-3 | `debug_server.py` | `POST /api/debug/pause`, `/api/debug/resume`, `/api/debug/reset` |
| 3-4 | `app.js` | 一時停止/再開ボタン（pauseTime で renderNowMs を固定） |
| 3-5 | `app.js` | 初期化ボタン + 確認ダイアログ |
| 3-6 | `app.js` | 一時停止中は「事業者生成」ボタンを無効化 |

完了条件: 一時停止でUAVが画面上で止まり、再開で動き出す

---

### Phase 4: 仕上げ・品質向上

| タスク | 内容 |
|--------|------|
| 4-1 | エラーハンドリング（経路探索失敗時のUI通知） |
| 4-2 | 同一経路複数クライアントの色分け表示 |
| 4-3 | 飛行完了UAVのカウント表示 |
| 4-4 | `make run-debug` を help に追記 |
| 4-5 | 経路結果モーダルの飛行前待機UAVの見やすい表示改善 |

---

## 未実装のまま着手しないこと

- Phase 2 の Java フックが完成する前に Phase 3 の一時停止を実装しない
- Phase 1 のトポロジ表示が確認できる前に Phase 2 のモーダルを実装しない

## 並列実行について

デバッグモードは並列実行を想定しない。ポートは `9001` 固定。
SIM_ID は常に `1` として Redis ポート `6379` を使用。
