#!/usr/bin/env python3
"""
UAV可視化サーバー (viz_server.py)

FastAPI + WebSocket + Redis を用いてUAVのリアルタイム飛行状況を可視化する。
トポロジ上のノード・リンク・UAVを SVG で表示し、requestAnimationFrame で滑らかにアニメーションする。

起動例:
  python3 scripts/viz_server.py --sim-id 1 --topology config/topology/koriyama_topology.txt \
      --port 8001 --redis-port 6379 --recording src/result/sim_1/viz/recording.jsonl

エンドポイント:
  GET  /              → メインUI (HTML/JS/SVG 埋め込み)
  GET  /api/topology  → トポロジJSON
  GET  /api/recording → 録画JSONLファイル全体
  GET  /api/status    → サーバー状態
  WS   /ws            → リアルタイム状態配信 (JSON)
"""

import argparse
import asyncio
import json
import os
import sys
import time
from pathlib import Path
from typing import Dict, List, Optional, Set

import redis.asyncio as aioredis
from contextlib import asynccontextmanager
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse
import uvicorn

# =============================================================================
# 引数パース
# =============================================================================

parser = argparse.ArgumentParser(description="UAV Visualization Server")
parser.add_argument("--sim-id", default="1", help="Simulation ID")
parser.add_argument("--topology", required=True, help="Path to topology file")
parser.add_argument("--port", type=int, default=8001, help="HTTP server port")
parser.add_argument("--redis-port", type=int, default=6379, help="Redis port")
parser.add_argument("--redis-host", default="localhost", help="Redis host")
parser.add_argument("--recording", required=True, help="Path to recording JSONL file")
args = parser.parse_args()

SIM_ID = args.sim_id
TOPOLOGY_PATH = args.topology
SERVER_PORT = args.port
REDIS_HOST = args.redis_host
REDIS_PORT = args.redis_port
RECORDING_PATH = args.recording

REDIS_STATES_KEY = f"viz:sim_{SIM_ID}:states"
REDIS_SIM_ENDED_KEY = f"viz:sim_{SIM_ID}:sim_ended"
POLL_INTERVAL_MS = 100       # Redisポーリング間隔
RECORD_INTERVAL_MS = 500     # 録画スナップショット間隔

# =============================================================================
# トポロジ読み込み
# =============================================================================

def load_topology(path: str) -> dict:
    """
    トポロジファイルを読み込みノード・リンク情報を返す。

    NODE <id> <x> <y> [<district>]
    LINK <src> <dst> <capacity> <distance_m> <lTubeLength>
    """
    nodes: Dict[int, dict] = {}
    links: List[dict] = []

    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if parts[0] == "NODE" and len(parts) >= 4:
                nid = int(parts[1])
                x = float(parts[2])
                y = float(parts[3])
                district = parts[4] if len(parts) > 4 else ""
                nodes[nid] = {"id": nid, "x": x, "y": y, "district": district}
            elif parts[0] == "LINK" and len(parts) >= 5:
                src = int(parts[1])
                dst = int(parts[2])
                capacity = float(parts[3])
                distance_m = float(parts[4])
                links.append({
                    "src": src,
                    "dst": dst,
                    "capacity": capacity,
                    "distance_m": distance_m,
                })

    return {"nodes": nodes, "links": links}


try:
    TOPOLOGY = load_topology(TOPOLOGY_PATH)
    print(f"[viz_server] トポロジ読み込み完了: {len(TOPOLOGY['nodes'])} ノード, {len(TOPOLOGY['links'])} リンク")
except Exception as e:
    print(f"[viz_server] トポロジ読み込み失敗: {e}", file=sys.stderr)
    sys.exit(1)

# ノードIDから距離を引けるようにリンクマップを作成（正規化キー: min_max）
LINK_DISTANCE_MAP: Dict[str, float] = {}
for lnk in TOPOLOGY["links"]:
    key = f"{min(lnk['src'], lnk['dst'])}_{max(lnk['src'], lnk['dst'])}"
    LINK_DISTANCE_MAP[key] = lnk["distance_m"]

# =============================================================================
# グローバル状態
# =============================================================================

current_states: Dict[str, dict] = {}   # field -> parsed state dict
sim_ended: bool = False
sim_start_ms: int = int(time.time() * 1000)
connected_clients: Set[WebSocket] = set()
recording_lock = asyncio.Lock()
redis_client: Optional[aioredis.Redis] = None

# 録画ファイルを準備
Path(RECORDING_PATH).parent.mkdir(parents=True, exist_ok=True)

# =============================================================================
# FastAPI アプリ
# =============================================================================

@asynccontextmanager
async def lifespan(app: FastAPI):
    asyncio.create_task(redis_poller())
    print(f"[viz_server] 起動完了 → http://localhost:{SERVER_PORT}")
    yield

app = FastAPI(title="UAV Viz Server", lifespan=lifespan)

# =============================================================================
# ヘルパー
# =============================================================================

def build_snapshot() -> dict:
    """現在の全UAV状態スナップショットを構築する。"""
    now_ms = int(time.time() * 1000)
    return {
        "ts": now_ms,
        "elapsed_ms": now_ms - sim_start_ms,
        "sim_ended": sim_ended,
        "states": dict(current_states),
    }


async def write_recording_line(snapshot: dict) -> None:
    """スナップショットを JSONL ファイルに1行追記する。"""
    async with recording_lock:
        try:
            with open(RECORDING_PATH, "a", encoding="utf-8") as f:
                f.write(json.dumps(snapshot, ensure_ascii=False) + "\n")
        except Exception as e:
            print(f"[viz_server] 録画書き込みエラー: {e}", file=sys.stderr)


async def broadcast(message: dict) -> None:
    """全WebSocketクライアントにメッセージを送信する。"""
    if not connected_clients:
        return
    data = json.dumps(message, ensure_ascii=False)
    dead: Set[WebSocket] = set()
    for ws in list(connected_clients):
        try:
            await ws.send_text(data)
        except Exception:
            dead.add(ws)
    connected_clients.difference_update(dead)


# =============================================================================
# バックグラウンドタスク
# =============================================================================

async def redis_poller() -> None:
    """Redisから100msごとにUAV状態を取得してWebSocketクライアントへ配信する。"""
    global current_states, sim_ended, redis_client

    try:
        redis_client = aioredis.Redis(
            host=REDIS_HOST, port=REDIS_PORT, decode_responses=True
        )
        print(f"[viz_server] Redis接続: {REDIS_HOST}:{REDIS_PORT}")
    except Exception as e:
        print(f"[viz_server] Redis接続失敗: {e}", file=sys.stderr)
        return

    last_record_ms = 0

    while True:
        try:
            # UAV状態をすべて取得
            raw_map: Dict[str, str] = await redis_client.hgetall(REDIS_STATES_KEY)
            new_states: Dict[str, dict] = {}
            for field, val in raw_map.items():
                try:
                    new_states[field] = json.loads(val)
                except json.JSONDecodeError:
                    pass

            current_states = new_states

            # シミュレーション終了チェック
            ended_val = await redis_client.get(REDIS_SIM_ENDED_KEY)
            if ended_val == "1":
                sim_ended = True

            # WebSocketクライアントへブロードキャスト
            if connected_clients:
                snapshot = build_snapshot()
                await broadcast(snapshot)

            # 録画スナップショット（500msごと）
            now_ms = int(time.time() * 1000)
            if now_ms - last_record_ms >= RECORD_INTERVAL_MS:
                snapshot = build_snapshot()
                await write_recording_line(snapshot)
                last_record_ms = now_ms

        except Exception as e:
            print(f"[viz_server] pollerエラー: {e}", file=sys.stderr)

        await asyncio.sleep(POLL_INTERVAL_MS / 1000.0)



# =============================================================================
# REST エンドポイント
# =============================================================================

@app.get("/api/topology")
async def get_topology():
    """トポロジ情報をJSON形式で返す。"""
    return JSONResponse({
        "nodes": [v for v in TOPOLOGY["nodes"].values()],
        "links": TOPOLOGY["links"],
    })


@app.get("/api/recording")
async def get_recording():
    """録画JSONLファイルの全内容をテキストで返す。"""
    if not Path(RECORDING_PATH).exists():
        return PlainTextResponse("")
    with open(RECORDING_PATH, "r", encoding="utf-8") as f:
        content = f.read()
    return PlainTextResponse(content, media_type="application/x-ndjson")


@app.get("/api/status")
async def get_status():
    return JSONResponse({
        "sim_id": SIM_ID,
        "sim_ended": sim_ended,
        "active_uavs": len(current_states),
        "connected_clients": len(connected_clients),
        "elapsed_ms": int(time.time() * 1000) - sim_start_ms,
    })


# =============================================================================
# WebSocket エンドポイント
# =============================================================================

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    connected_clients.add(websocket)
    try:
        # 接続直後に現在状態を送信
        await websocket.send_text(json.dumps(build_snapshot(), ensure_ascii=False))
        while True:
            # クライアントからのメッセージを受信（ping keepalive用）
            await websocket.receive_text()
    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        connected_clients.discard(websocket)


# =============================================================================
# メインUI (埋め込みHTML)
# =============================================================================

MAIN_HTML = r"""<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>UAV Simulator - 可視化</title>
<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: 'Segoe UI', sans-serif; background: #1a1a2e; color: #e0e0e0; height: 100vh; display: flex; flex-direction: column; }
header { padding: 8px 16px; background: #16213e; display: flex; align-items: center; gap: 16px; border-bottom: 1px solid #0f3460; flex-shrink: 0; }
header h1 { font-size: 1rem; color: #4ecca3; }
.status-bar { display: flex; gap: 12px; align-items: center; font-size: 0.8rem; }
.badge { padding: 2px 8px; border-radius: 10px; background: #0f3460; }
.badge.live { background: #1a472a; color: #4ecca3; }
.badge.ended { background: #4a1515; color: #e06c75; }
.badge.playback { background: #3a2a00; color: #e5c07b; }
#connection-dot { width: 8px; height: 8px; border-radius: 50%; background: #555; display: inline-block; }
#connection-dot.connected { background: #4ecca3; }
.main { display: flex; flex: 1; overflow: hidden; }
#svg-container { flex: 1; overflow: hidden; position: relative; }
svg { width: 100%; height: 100%; }
.sidebar { width: 240px; background: #16213e; padding: 12px; overflow-y: auto; border-left: 1px solid #0f3460; font-size: 0.8rem; flex-shrink: 0; }
.sidebar h2 { font-size: 0.9rem; color: #4ecca3; margin-bottom: 8px; border-bottom: 1px solid #0f3460; padding-bottom: 4px; }
.uav-list { display: flex; flex-direction: column; gap: 4px; }
.uav-item { padding: 4px 8px; border-radius: 4px; border-left: 3px solid #555; }
.uav-item.FLYING { border-color: #e74c3c; background: rgba(231,76,60,0.1); }
.uav-item.HOVERING { border-color: #3498db; background: rgba(52,152,219,0.1); }
.uav-item.PRE_WAIT { border-color: #27ae60; background: rgba(39,174,96,0.1); }
.legend { margin-top: 12px; }
.legend-item { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
.legend-dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
#playback-controls { padding: 8px 16px; background: #16213e; border-top: 1px solid #0f3460; display: none; align-items: center; gap: 12px; flex-shrink: 0; }
#playback-controls.visible { display: flex; }
button { background: #0f3460; color: #e0e0e0; border: none; padding: 4px 12px; border-radius: 4px; cursor: pointer; }
button:hover { background: #1a4a8a; }
input[type=range] { flex: 1; accent-color: #4ecca3; }
</style>
</head>
<body>
<header>
  <h1>UAV Simulator 可視化</h1>
  <div class="status-bar">
    <span><span id="connection-dot"></span> <span id="conn-label">接続中...</span></span>
    <span class="badge" id="mode-badge">LIVE</span>
    <span class="badge" id="sim-status-badge">シミュレーション中</span>
    <span>UAV: <b id="uav-count">0</b></span>
    <span>経過: <b id="elapsed">0s</b></span>
  </div>
  <div style="margin-left:auto; display:flex; gap:8px;">
    <button id="btn-live">LIVE</button>
    <button id="btn-playback">再生</button>
  </div>
</header>
<div class="main">
  <div id="svg-container">
    <svg id="topo-svg" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <marker id="arrow-flying" markerWidth="6" markerHeight="4" refX="5" refY="2" orient="auto">
          <polygon points="0 0, 6 2, 0 4" fill="#e74c3c"/>
        </marker>
        <marker id="arrow-hovering" markerWidth="6" markerHeight="4" refX="5" refY="2" orient="auto">
          <polygon points="0 0, 6 2, 0 4" fill="#3498db"/>
        </marker>
        <marker id="arrow-prewait" markerWidth="6" markerHeight="4" refX="5" refY="2" orient="auto">
          <polygon points="0 0, 6 2, 0 4" fill="#27ae60"/>
        </marker>
      </defs>
      <g id="g-links"></g>
      <g id="g-nodes"></g>
      <g id="g-uavs"></g>
      <g id="g-waiting-counts"></g>
    </svg>
  </div>
  <div class="sidebar">
    <h2>UAV一覧</h2>
    <div class="uav-list" id="uav-list"></div>
    <div class="legend" style="margin-top:16px;">
      <h2>凡例</h2>
      <div class="legend-item"><div class="legend-dot" style="background:#e74c3c"></div>飛行中 (FLYING)</div>
      <div class="legend-item"><div class="legend-dot" style="background:#3498db"></div>上空待機 (HOVERING)</div>
      <div class="legend-item"><div class="legend-dot" style="background:#27ae60"></div>飛行前待機 (PRE_WAIT)</div>
    </div>
  </div>
</div>
<div id="playback-controls">
  <button id="pb-play">▶</button>
  <input type="range" id="pb-slider" min="0" value="0" step="1">
  <span id="pb-time">0s</span>
  <span id="pb-total"></span>
</div>

<script>
// ============================================================
// トポロジとレンダリング
// ============================================================
const SVG_PADDING = 40;
const NODE_RADIUS = 4;
const UAV_RADIUS = 3.6;
const LINK_OFFSET = 4; // 双方向リンクのオフセット(px)

let topology = null;
let nodeMap = {};   // id -> {id,x,y}
let svgW = 800, svgH = 600;
let renderNowMs = Date.now(); // LIVE: Date.now()  /  再生: スナップショットのts

// ノード座標をSVG座標に変換（Yを反転）
function toSvgX(nx) { return SVG_PADDING + nx * (svgW - 2 * SVG_PADDING); }
function toSvgY(ny) { return SVG_PADDING + (1 - ny) * (svgH - 2 * SVG_PADDING); }

async function loadTopology() {
  const res = await fetch('/api/topology');
  topology = await res.json();
  nodeMap = {};
  for (const n of topology.nodes) { nodeMap[n.id] = n; }
  renderTopology();
}

function renderTopology() {
  const svgEl = document.getElementById('topo-svg');
  svgW = svgEl.clientWidth || 800;
  svgH = svgEl.clientHeight || 600;

  // リンク描画
  const gLinks = document.getElementById('g-links');
  gLinks.innerHTML = '';
  for (const lnk of topology.links) {
    const a = nodeMap[lnk.src], b = nodeMap[lnk.dst];
    if (!a || !b) continue;
    const line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
    line.setAttribute('x1', toSvgX(a.x));
    line.setAttribute('y1', toSvgY(a.y));
    line.setAttribute('x2', toSvgX(b.x));
    line.setAttribute('y2', toSvgY(b.y));
    line.setAttribute('stroke', '#334');
    line.setAttribute('stroke-width', '1');
    gLinks.appendChild(line);
  }

  // ノード描画
  const gNodes = document.getElementById('g-nodes');
  gNodes.innerHTML = '';
  for (const n of topology.nodes) {
    const cx = toSvgX(n.x), cy = toSvgY(n.y);
    const circle = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
    circle.setAttribute('cx', cx);
    circle.setAttribute('cy', cy);
    circle.setAttribute('r', NODE_RADIUS);
    circle.setAttribute('fill', '#4a5568');
    circle.setAttribute('stroke', '#718096');
    circle.setAttribute('stroke-width', '0.5');
    circle.setAttribute('data-id', n.id);
    circle.setAttribute('title', `Node ${n.id}`);
    gNodes.appendChild(circle);
  }
}

// ============================================================
// 状態レンダリング
// ============================================================
let currentSnapshot = null;
let animationFrameId = null;

const STATUS_COLOR = {
  'FLYING': '#e74c3c',
  'HOVERING': '#3498db',
  'PRE_WAIT': '#27ae60',
};
const STATUS_ARROW = {
  'FLYING': 'url(#arrow-flying)',
  'HOVERING': 'url(#arrow-hovering)',
  'PRE_WAIT': 'url(#arrow-prewait)',
};
const STATUS_LABEL = {
  'FLYING': '飛行中',
  'HOVERING': '上空待機',
  'PRE_WAIT': '飛行前待機',
};

function lerp(a, b, t) { return a + (b - a) * Math.clamp(t, 0, 1); }
Math.clamp = (v, lo, hi) => Math.min(Math.max(v, lo), hi);

// UAVの現在SVG座標を計算
function calcUavSvgPos(state) {
  if (state.status === 'FLYING') {
    const from = nodeMap[state.fromNode];
    const to = nodeMap[state.toNode];
    if (!from || !to) return null;
    const flightTimeMs = (state.linkDistM / state.speedMs) * 1000;
    let progress = (renderNowMs - state.linkStartMs) / flightTimeMs;
    progress = Math.clamp(progress, 0, 1);

    // 双方向リンクオフセット（fromNode < toNode → 右方向にオフセット）
    const dx = toSvgX(to.x) - toSvgX(from.x);
    const dy = toSvgY(to.y) - toSvgY(from.y);
    const len = Math.sqrt(dx*dx + dy*dy) || 1;
    const sign = state.fromNode < state.toNode ? 1 : -1;
    const ox = sign * LINK_OFFSET * (-dy / len);
    const oy = sign * LINK_OFFSET * (dx / len);

    return {
      x: lerp(toSvgX(from.x), toSvgX(to.x), progress) + ox,
      y: lerp(toSvgY(from.y), toSvgY(to.y), progress) + oy,
      angle: Math.atan2(dy, dx) * 180 / Math.PI,
      ox, oy,
      fromX: toSvgX(from.x) + ox,
      fromY: toSvgY(from.y) + oy,
      toX: toSvgX(to.x) + ox,
      toY: toSvgY(to.y) + oy,
      progress,
    };
  } else {
    const node = nodeMap[state.waitNode];
    if (!node) return null;
    const cx = toSvgX(node.x), cy = toSvgY(node.y);
    // 複数UAVが同ノードに待機する場合は小さくずらす（シンプルにオフセットなし）
    return { x: cx, y: cy };
  }
}

function renderFrame() {
  if (!currentSnapshot || !topology) {
    animationFrameId = requestAnimationFrame(renderFrame);
    return;
  }

  // LIVEモード: 現在時刻で滑らかにアニメーション
  // 再生モード: スナップショットのts（録画時刻）で位置を再現
  renderNowMs = isLiveMode ? Date.now() : (currentSnapshot.ts || Date.now());

  const gUavs = document.getElementById('g-uavs');
  gUavs.innerHTML = '';
  const gWaiting = document.getElementById('g-waiting-counts');
  gWaiting.innerHTML = '';
  const uavList = document.getElementById('uav-list');
  uavList.innerHTML = '';

  // ノードごとの待機UAV数
  const nodeWaitCount = {};
  const states = currentSnapshot.states || {};

  for (const [field, state] of Object.entries(states)) {
    const pos = calcUavSvgPos(state);
    if (!pos) continue;
    const color = STATUS_COLOR[state.status] || '#aaa';

    if (state.status === 'FLYING') {
      // 飛行中: 移動中の点 + 方向矢印（リンク上の線）
      // 軌跡線（薄く）
      const tline = document.createElementNS('http://www.w3.org/2000/svg', 'line');
      tline.setAttribute('x1', pos.fromX); tline.setAttribute('y1', pos.fromY);
      tline.setAttribute('x2', pos.toX); tline.setAttribute('y2', pos.toY);
      tline.setAttribute('stroke', color); tline.setAttribute('stroke-width', '1.5');
      tline.setAttribute('stroke-opacity', '0.3');
      tline.setAttribute('marker-end', STATUS_ARROW[state.status]);
      gUavs.appendChild(tline);

      // UAV点
      const dot = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      dot.setAttribute('cx', pos.x); dot.setAttribute('cy', pos.y);
      dot.setAttribute('r', UAV_RADIUS);
      dot.setAttribute('fill', color);
      dot.setAttribute('fill-opacity', '0.9');
      const title = document.createElementNS('http://www.w3.org/2000/svg', 'title');
      title.textContent = `Client${state.clientId} UAV${state.uavId}: ${state.fromNode}→${state.toNode} (${Math.round((pos.progress||0)*100)}%)`;
      dot.appendChild(title);
      gUavs.appendChild(dot);

    } else {
      // 待機中: ノード上の点
      const waitKey = state.waitNode;
      nodeWaitCount[waitKey] = (nodeWaitCount[waitKey] || 0) + 1;

      const dot = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      dot.setAttribute('cx', pos.x); dot.setAttribute('cy', pos.y);
      dot.setAttribute('r', UAV_RADIUS);
      dot.setAttribute('fill', color);
      dot.setAttribute('fill-opacity', '0.85');
      const title = document.createElementNS('http://www.w3.org/2000/svg', 'title');
      title.textContent = `Client${state.clientId} UAV${state.uavId}: ${STATUS_LABEL[state.status]} @ Node${state.waitNode}`;
      dot.appendChild(title);
      gUavs.appendChild(dot);
    }

    // サイドバーリスト
    const item = document.createElement('div');
    item.className = `uav-item ${state.status}`;
    let detail = '';
    if (state.status === 'FLYING') {
      detail = `${state.fromNode}→${state.toNode}`;
    } else {
      detail = `@Node${state.waitNode}`;
    }
    item.textContent = `C${state.clientId}/U${state.uavId} ${detail}`;
    uavList.appendChild(item);
  }

  // 待機UAV数ラベル
  for (const [nodeId, count] of Object.entries(nodeWaitCount)) {
    const n = nodeMap[nodeId];
    if (!n) continue;
    const text = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    text.setAttribute('x', toSvgX(n.x) + 6);
    text.setAttribute('y', toSvgY(n.y) - 6);
    text.setAttribute('font-size', '9');
    text.setAttribute('fill', '#e0e0e0');
    text.textContent = count;
    gWaiting.appendChild(text);
  }

  // ステータスバー更新
  document.getElementById('uav-count').textContent = Object.keys(states).length;
  const elapsed = Math.floor((currentSnapshot.elapsed_ms || 0) / 1000);
  document.getElementById('elapsed').textContent = elapsed + 's';
  if (currentSnapshot.sim_ended) {
    document.getElementById('sim-status-badge').textContent = 'シミュレーション終了';
    document.getElementById('sim-status-badge').className = 'badge ended';
  }

  animationFrameId = requestAnimationFrame(renderFrame);
}

// ============================================================
// WebSocket (ライブモード)
// ============================================================
let ws = null;
let isLiveMode = true;

function connectWs() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  ws = new WebSocket(`${proto}//${location.host}/ws`);

  ws.onopen = () => {
    document.getElementById('connection-dot').className = 'connected';
    document.getElementById('conn-label').textContent = '接続済み';
  };
  ws.onmessage = (evt) => {
    if (!isLiveMode) return;
    try {
      currentSnapshot = JSON.parse(evt.data);
    } catch (e) {}
  };
  ws.onclose = () => {
    document.getElementById('connection-dot').className = '';
    document.getElementById('conn-label').textContent = '切断';
    setTimeout(connectWs, 2000);
  };
  ws.onerror = () => { ws.close(); };
}

// keepalive
setInterval(() => {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send('ping');
}, 10000);

// ============================================================
// 再生モード
// ============================================================
let recordingFrames = [];
let pbIndex = 0;
let pbPlaying = false;
let pbTimer = null;

async function loadRecording() {
  const res = await fetch('/api/recording');
  const text = await res.text();
  recordingFrames = text.trim().split('\n').filter(l => l.trim()).map(l => {
    try { return JSON.parse(l); } catch(e) { return null; }
  }).filter(Boolean);

  const slider = document.getElementById('pb-slider');
  slider.max = Math.max(0, recordingFrames.length - 1);
  slider.value = 0;
  const totalSec = recordingFrames.length > 0
    ? Math.floor((recordingFrames[recordingFrames.length-1].elapsed_ms || 0) / 1000) : 0;
  document.getElementById('pb-total').textContent = `/ ${totalSec}s`;
  pbIndex = 0;
  return recordingFrames.length;
}

function pbStep() {
  if (pbIndex >= recordingFrames.length) { pbPlaying = false; return; }
  currentSnapshot = recordingFrames[pbIndex];
  document.getElementById('pb-slider').value = pbIndex;
  const sec = Math.floor((currentSnapshot.elapsed_ms || 0) / 1000);
  document.getElementById('pb-time').textContent = sec + 's';
  if (pbPlaying) pbIndex++;
}

document.getElementById('btn-live').addEventListener('click', () => {
  isLiveMode = true;
  pbPlaying = false;
  clearInterval(pbTimer);
  document.getElementById('playback-controls').classList.remove('visible');
  document.getElementById('mode-badge').textContent = 'LIVE';
  document.getElementById('mode-badge').className = 'badge live';
});

document.getElementById('btn-playback').addEventListener('click', async () => {
  isLiveMode = false;
  document.getElementById('mode-badge').textContent = 'PLAYBACK';
  document.getElementById('mode-badge').className = 'badge playback';
  document.getElementById('playback-controls').classList.add('visible');
  const count = await loadRecording();
  if (count === 0) {
    alert('録画データがありません。シミュレーションが開始されているか確認してください。');
    return;
  }
});

document.getElementById('pb-play').addEventListener('click', () => {
  if (pbPlaying) {
    pbPlaying = false;
    document.getElementById('pb-play').textContent = '▶';
    clearInterval(pbTimer);
  } else {
    pbPlaying = true;
    document.getElementById('pb-play').textContent = '⏸';
    pbTimer = setInterval(pbStep, RECORD_INTERVAL_MS);
  }
});

document.getElementById('pb-slider').addEventListener('input', (e) => {
  pbIndex = parseInt(e.target.value);
  if (pbIndex < recordingFrames.length) {
    currentSnapshot = recordingFrames[pbIndex];
    const sec = Math.floor((currentSnapshot.elapsed_ms || 0) / 1000);
    document.getElementById('pb-time').textContent = sec + 's';
  }
});

// ============================================================
// 初期化
// ============================================================
const RECORD_INTERVAL_MS = """ + str(RECORD_INTERVAL_MS) + r""";

window.addEventListener('load', async () => {
  await loadTopology();
  connectWs();
  requestAnimationFrame(renderFrame);

  // リサイズ時にトポロジを再描画
  new ResizeObserver(() => renderTopology()).observe(document.getElementById('svg-container'));
});
</script>
</body>
</html>
"""


@app.get("/", response_class=HTMLResponse)
async def main_page():
    return HTMLResponse(MAIN_HTML)


# =============================================================================
# エントリーポイント
# =============================================================================

if __name__ == "__main__":
    uvicorn.run(
        app,
        host="0.0.0.0",
        port=SERVER_PORT,
        log_level="warning",
    )
