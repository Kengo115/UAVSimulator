#!/usr/bin/env python3
"""
UAV可視化サーバー (viz_server.py)

FastAPI + WebSocket + Redis を用いてUAVのリアルタイム飛行状況を可視化する。
トポロジ上のノード・リンク・UAVを SVG で表示し、requestAnimationFrame で滑らかにアニメーションする。

起動例（チャンクモード）:
  python3 scripts/viz_server.py --sim-id 1 --topology config/topology/koriyama_topology.txt \
      --port 8001 --redis-port 6379 --session-dir src/result/sim_1/viz/20260824_123456

起動例（レガシーモード）:
  python3 scripts/viz_server.py --sim-id 1 --topology config/topology/koriyama_topology.txt \
      --port 8001 --redis-port 6379 --recording src/result/sim_1/viz/recording.jsonl

エンドポイント:
  GET  /              → メインUI (HTML/JS/SVG 埋め込み)
  GET  /api/topology  → トポロジJSON
  GET  /api/current-session → 現在のセッション情報
  GET  /api/sessions  → セッション一覧（過去録画含む）
  GET  /api/session/{id}/manifest → セッションマニフェスト
  GET  /api/session/{id}/chunk/{n} → チャンクデータ (JSONL)
  GET  /api/recording → [レガシー] 録画JSONLファイル全体
  GET  /api/recordings → [レガシー] 過去録画一覧
  GET  /api/recording/{filename} → [レガシー] 過去録画ファイル
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
parser.add_argument("--recording", default=None, help="[レガシー] Path to recording JSONL file")
parser.add_argument("--session-dir", default=None, help="セッションディレクトリ（チャンクモード）")
args = parser.parse_args()

SIM_ID = args.sim_id
TOPOLOGY_PATH = args.topology
SERVER_PORT = args.port
REDIS_HOST = args.redis_host
REDIS_PORT = args.redis_port
RECORDING_PATH = args.recording
SESSION_DIR = args.session_dir

if not SESSION_DIR and not RECORDING_PATH:
    print("[viz_server] --session-dir または --recording のいずれかを指定してください", file=sys.stderr)
    sys.exit(1)

# VIZ_DIR: セッション一覧を取得するルートディレクトリ
if SESSION_DIR:
    VIZ_DIR = str(Path(SESSION_DIR).parent)
    CURR_SESSION_ID = Path(SESSION_DIR).name
else:
    VIZ_DIR = str(Path(RECORDING_PATH).parent)
    CURR_SESSION_ID = None

FRAMES_PER_CHUNK = 500    # チャンクあたりのフレーム数
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

# チャンク録画状態（SESSION_DIR使用時のみ有効）
_chunk_write_idx: int = 0      # 現在書き込み中のチャンクインデックス
_chunk_frame_count: int = 0    # 現在チャンクに書き込んだフレーム数
_chunk_total_frames: int = 0   # 全チャンクの累積フレーム数

# 録画先を準備
if SESSION_DIR:
    Path(SESSION_DIR).mkdir(parents=True, exist_ok=True)
elif RECORDING_PATH:
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


def _update_manifest() -> None:
    """manifest.jsonを更新する。recording_lock保護下で呼ぶこと。"""
    if not SESSION_DIR:
        return
    manifest = {
        "session_id": CURR_SESSION_ID,
        "total_frames": _chunk_total_frames,
        "total_chunks": _chunk_write_idx + (1 if _chunk_frame_count > 0 else 0),
        "frames_per_chunk": FRAMES_PER_CHUNK,
        "sim_ended": sim_ended,
    }
    manifest_path = Path(SESSION_DIR) / "manifest.json"
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f)


async def write_recording_line(snapshot: dict) -> None:
    """スナップショットを録画ファイルに1行追記する。チャンクモードでは自動ローテーション。"""
    global _chunk_write_idx, _chunk_frame_count, _chunk_total_frames
    async with recording_lock:
        try:
            if SESSION_DIR:
                Path(SESSION_DIR).mkdir(parents=True, exist_ok=True)
                chunk_path = Path(SESSION_DIR) / f"chunk_{_chunk_write_idx:06d}.jsonl"
                with open(chunk_path, "a", encoding="utf-8") as f:
                    f.write(json.dumps(snapshot, ensure_ascii=False) + "\n")
                _chunk_frame_count += 1
                _chunk_total_frames += 1
                if _chunk_frame_count >= FRAMES_PER_CHUNK:
                    _chunk_write_idx += 1
                    _chunk_frame_count = 0
                _update_manifest()
            else:
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
            if ended_val == "1" and not sim_ended:
                sim_ended = True
                # 最終マニフェスト更新
                if SESSION_DIR:
                    async with recording_lock:
                        _update_manifest()

            if sim_ended:
                print("[viz_server] シミュレーション終了 - Redisポーリングを停止します")
                return

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

def _estimate_frame_count(path: Path, sample_bytes: int = 524288) -> int:
    """先頭 sample_bytes バイトをサンプリングしてフレーム数を推定する（ファイル全体スキャン不要）。"""
    try:
        file_size = path.stat().st_size
        if file_size == 0:
            return 0
        with open(path, "rb") as f:
            sample = f.read(min(sample_bytes, file_size))
        newlines = sample.count(b"\n")
        if newlines == 0:
            return 1
        return max(1, round(file_size / len(sample) * newlines))
    except Exception:
        return 0


@app.get("/api/topology")
async def get_topology():
    """トポロジ情報をJSON形式で返す。"""
    return JSONResponse({
        "nodes": [v for v in TOPOLOGY["nodes"].values()],
        "links": TOPOLOGY["links"],
    })


@app.get("/api/current-session")
async def get_current_session():
    """現在アクティブなセッション情報を返す。"""
    if SESSION_DIR:
        manifest_path = Path(SESSION_DIR) / "manifest.json"
        if manifest_path.exists():
            with open(manifest_path, encoding="utf-8") as f:
                manifest = json.load(f)
        else:
            manifest = {
                "session_id": CURR_SESSION_ID,
                "total_frames": _chunk_total_frames,
                "total_chunks": 0,
                "frames_per_chunk": FRAMES_PER_CHUNK,
                "sim_ended": sim_ended,
            }
        return JSONResponse({"type": "chunked", "session_id": CURR_SESSION_ID, "manifest": manifest})
    else:
        return JSONResponse({"type": "legacy", "recording": RECORDING_PATH})


@app.get("/api/sessions")
async def list_sessions():
    """VIZ_DIR内のセッション一覧を返す（現在のセッションは除外）。"""
    viz_dir = Path(VIZ_DIR)
    sessions = []

    if viz_dir.exists():
        # チャンクセッション（manifest.jsonを持つサブディレクトリ）
        try:
            dirs = sorted(
                [d for d in viz_dir.iterdir() if d.is_dir()],
                key=lambda p: p.name,
                reverse=True,
            )
        except Exception:
            dirs = []

        for d in dirs:
            manifest_path = d / "manifest.json"
            if not manifest_path.exists():
                continue
            if CURR_SESSION_ID and d.name == CURR_SESSION_ID:
                continue  # 現在のセッションは除外
            try:
                with open(manifest_path, encoding="utf-8") as f:
                    manifest = json.load(f)
                sessions.append({
                    "type": "chunked",
                    "session_id": d.name,
                    "manifest": manifest,
                })
            except Exception:
                pass

        # レガシー録画ファイル（recording_*.jsonl）
        for f in sorted(viz_dir.glob("recording_*.jsonl"), key=lambda p: p.name, reverse=True):
            try:
                st = f.stat()
                sessions.append({
                    "type": "legacy",
                    "filename": f.name,
                    "size_bytes": st.st_size,
                    "frame_count": _estimate_frame_count(f),
                })
            except Exception:
                pass

    return JSONResponse(sessions)


@app.get("/api/session/{session_id}/manifest")
async def get_session_manifest(session_id: str):
    """指定セッションのマニフェストを返す。"""
    if "/" in session_id or "\\" in session_id:
        return JSONResponse({}, status_code=400)
    target = Path(VIZ_DIR) / session_id
    if not target.is_dir() or target.parent.resolve() != Path(VIZ_DIR).resolve():
        return JSONResponse({}, status_code=404)
    manifest_path = target / "manifest.json"
    if not manifest_path.exists():
        return JSONResponse({}, status_code=404)
    try:
        with open(manifest_path, encoding="utf-8") as f:
            return JSONResponse(json.load(f))
    except Exception:
        return JSONResponse({}, status_code=500)


@app.get("/api/session/{session_id}/chunk/{chunk_idx}")
async def get_session_chunk(session_id: str, chunk_idx: int):
    """指定セッションの指定チャンク(JSONL)を返す。"""
    if "/" in session_id or "\\" in session_id:
        return PlainTextResponse("", status_code=400)
    target = Path(VIZ_DIR) / session_id
    if not target.is_dir() or target.parent.resolve() != Path(VIZ_DIR).resolve():
        return PlainTextResponse("", status_code=404)
    chunk_path = target / f"chunk_{chunk_idx:06d}.jsonl"
    if not chunk_path.exists():
        return PlainTextResponse("", status_code=404)
    try:
        with open(chunk_path, "r", encoding="utf-8") as f:
            content = f.read()
        return PlainTextResponse(content, media_type="application/x-ndjson")
    except Exception:
        return PlainTextResponse("", status_code=500)


# --- レガシーエンドポイント（後方互換） ---

@app.get("/api/recording")
async def get_recording(from_line: int = 0, limit: int = 0):
    """[レガシー] 録画JSONLファイルの内容をテキストで返す。"""
    if not RECORDING_PATH:
        return PlainTextResponse("", headers={"X-Total-Lines": "0"})
    path = Path(RECORDING_PATH)
    if not path.exists():
        return PlainTextResponse("", headers={"X-Total-Lines": "0"})
    subset = []
    with open(path, "r", encoding="utf-8") as f:
        for i, line in enumerate(f):
            if i >= from_line:
                subset.append(line)
                if limit > 0 and len(subset) >= limit:
                    break
    total_est = _estimate_frame_count(path)
    return PlainTextResponse(
        "".join(subset),
        media_type="application/x-ndjson",
        headers={"X-Total-Lines": str(total_est)},
    )


@app.get("/api/recordings")
async def list_recordings():
    """[レガシー] VIZ_DIR内の過去録画一覧を返す（現在の録画は除外）。"""
    viz_dir = Path(VIZ_DIR)
    current = Path(RECORDING_PATH).name if RECORDING_PATH else None
    result = []
    for f in sorted(viz_dir.glob("recording_*.jsonl"), key=lambda p: p.name, reverse=True):
        if f.name == current:
            continue
        try:
            st = f.stat()
            result.append({
                "filename": f.name,
                "size_bytes": st.st_size,
                "frame_count": _estimate_frame_count(f),
            })
        except Exception:
            pass
    return JSONResponse(result)


@app.get("/api/recording/{filename}")
async def get_recording_by_name(filename: str, from_line: int = 0, limit: int = 0):
    """[レガシー] 過去録画ファイルを名前で取得する（パストラバーサル対策付き）。"""
    if "/" in filename or "\\" in filename or not filename.endswith(".jsonl"):
        return PlainTextResponse("", status_code=400)
    target = Path(VIZ_DIR) / filename
    if not target.exists() or target.parent.resolve() != Path(VIZ_DIR).resolve():
        return PlainTextResponse("", status_code=404)
    subset = []
    with open(target, "r", encoding="utf-8") as f:
        for i, line in enumerate(f):
            if i >= from_line:
                subset.append(line)
                if limit > 0 and len(subset) >= limit:
                    break
    total_est = _estimate_frame_count(target)
    return PlainTextResponse(
        "".join(subset),
        media_type="application/x-ndjson",
        headers={"X-Total-Lines": str(total_est)},
    )


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
#history-controls { padding: 8px 16px; background: #16213e; border-top: 1px solid #0f3460; display: none; align-items: center; gap: 12px; flex-shrink: 0; flex-wrap: wrap; }
#history-controls.visible { display: flex; }
button { background: #0f3460; color: #e0e0e0; border: none; padding: 4px 12px; border-radius: 4px; cursor: pointer; }
button:hover { background: #1a4a8a; }
button.active-mode { background: #1a4a8a; outline: 1px solid #4ecca3; }
input[type=range] { flex: 1; accent-color: #4ecca3; }
select { background: #0f3460; color: #e0e0e0; border: 1px solid #4ecca3; padding: 4px 8px; border-radius: 4px; }
.sep { color: #445; margin: 0 2px; font-size: 0.9rem; }
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
  <div style="margin-left:auto; display:flex; gap:6px; align-items:center;">
    <button id="btn-live" class="active-mode">LIVE</button>
    <button id="btn-catchup">追っかけ</button>
    <button id="btn-history">履歴</button>
    <span class="sep">|</span>
    <button id="btn-speed-10">10x</button>
    <button id="btn-speed-100" class="active-mode">100x</button>
    <button id="btn-speed-1000">1000x</button>
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

<!-- 追っかけ / 履歴 共通スライダー -->
<div id="playback-controls">
  <button id="pb-play">▶</button>
  <input type="range" id="pb-slider" min="0" value="0" step="1">
  <span id="pb-time">0s</span>
  <span id="pb-total"></span>
</div>

<!-- 履歴モード専用: ファイル/セッション選択 -->
<div id="history-controls">
  <span style="font-size:0.8rem;">録画:</span>
  <select id="history-select"><option value="">--- 読み込み中 ---</option></select>
  <button id="history-load-btn">読み込む</button>
</div>

<script>
// ============================================================
// サーバー注入定数（Pythonから埋め込み）
// ============================================================
""" + f"const RECORD_INTERVAL_MS = {RECORD_INTERVAL_MS};\nconst FRAMES_PER_CHUNK = {FRAMES_PER_CHUNK};\n" + r"""

// ============================================================
// 速度設定
// RENDER_INTERVAL_MS=50ms (20fps), framesPerTick = 再生速度/10
//   10x  → 1  frame/tick × 20fps = 20 sim-frames/s (500ms/frame → 10x) ✓
//   100x → 10 frames/tick → 100x ✓
//   1000x→ 100 frames/tick → 1000x ✓
// ============================================================
const RENDER_INTERVAL_MS = 50;
const SPEED_CONFIGS = { 10: 1, 100: 10, 1000: 100 };
let currentSpeed = 100;

// ============================================================
// 再生状態
// ============================================================
let pbMode = null;       // 'chunked' | 'legacy' | null
let pbPlaying = false;
let pbTimer = null;
let pbAbsIndex = 0;
let totalFrames = 0;
let seekDebounce = null;

// チャンクモード
let chunkCache = {};           // chunkIdx -> frames[]
let currentSessionId = null;

// レガシースライディングウィンドウ
let recordingFrames = [];
let windowStart = 0;
let isFetching = false;
let currentFilename = null;

const CHUNK_SIZE     = 200;   // レガシー: 1回のfetchで取得するフレーム数
const WINDOW_SIZE    = 600;   // レガシー: メモリ上に保持するフレーム数上限
const PREFETCH_AHEAD = 60;    // レガシー: 先読みトリガー閾値

// モード管理
let currentMode = 'live';
let catchupTimer = null;

// ============================================================
// トポロジとレンダリング
// ============================================================
const SVG_PADDING = 40;
const NODE_RADIUS = 4;
const UAV_RADIUS = 3.6;
const LINK_OFFSET = 4;

let topology = null;
let nodeMap = {};
let svgW = 800, svgH = 600;
let renderNowMs = Date.now();
let currentSnapshot = null;
let animationFrameId = null;

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

function calcUavSvgPos(state) {
  if (state.status === 'FLYING') {
    const from = nodeMap[state.fromNode];
    const to = nodeMap[state.toNode];
    if (!from || !to) return null;
    const flightTimeMs = (state.linkDistM / state.speedMs) * 1000;
    let progress = (renderNowMs - state.linkStartMs) / flightTimeMs;
    progress = Math.clamp(progress, 0, 1);

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
    return { x: toSvgX(node.x), y: toSvgY(node.y) };
  }
}

function renderFrame() {
  if (!currentSnapshot || !topology) {
    animationFrameId = requestAnimationFrame(renderFrame);
    return;
  }

  renderNowMs = (currentMode === 'live') ? Date.now() : (currentSnapshot.ts || Date.now());

  const gUavs = document.getElementById('g-uavs');
  gUavs.innerHTML = '';
  const gWaiting = document.getElementById('g-waiting-counts');
  gWaiting.innerHTML = '';
  const uavList = document.getElementById('uav-list');
  uavList.innerHTML = '';

  const nodeWaitCount = {};
  const states = currentSnapshot.states || {};

  for (const [field, state] of Object.entries(states)) {
    const pos = calcUavSvgPos(state);
    if (!pos) continue;
    const color = STATUS_COLOR[state.status] || '#aaa';

    if (state.status === 'FLYING') {
      const tline = document.createElementNS('http://www.w3.org/2000/svg', 'line');
      tline.setAttribute('x1', pos.fromX); tline.setAttribute('y1', pos.fromY);
      tline.setAttribute('x2', pos.toX); tline.setAttribute('y2', pos.toY);
      tline.setAttribute('stroke', color); tline.setAttribute('stroke-width', '1.5');
      tline.setAttribute('stroke-opacity', '0.3');
      tline.setAttribute('marker-end', STATUS_ARROW[state.status]);
      gUavs.appendChild(tline);

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

    const item = document.createElement('div');
    item.className = `uav-item ${state.status}`;
    let detail = state.status === 'FLYING' ? `${state.fromNode}→${state.toNode}` : `@Node${state.waitNode}`;
    item.textContent = `C${state.clientId}/U${state.uavId} ${detail}`;
    uavList.appendChild(item);
  }

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
// WebSocket (LIVEモード)
// ============================================================
let ws = null;

function connectWs() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  ws = new WebSocket(`${proto}//${location.host}/ws`);

  ws.onopen = () => {
    document.getElementById('connection-dot').className = 'connected';
    document.getElementById('conn-label').textContent = '接続済み';
  };
  ws.onmessage = (evt) => {
    if (currentMode !== 'live') return;
    try { currentSnapshot = JSON.parse(evt.data); } catch (e) {}
  };
  ws.onclose = () => {
    document.getElementById('connection-dot').className = '';
    document.getElementById('conn-label').textContent = '切断';
    setTimeout(connectWs, 2000);
  };
  ws.onerror = () => { ws.close(); };
}

setInterval(() => {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send('ping');
}, 10000);

// ============================================================
// フレームアクセス（モード非依存）
// ============================================================
function currentFrame() {
  if (pbMode === 'chunked') {
    const ci = Math.floor(pbAbsIndex / FRAMES_PER_CHUNK);
    const li = pbAbsIndex % FRAMES_PER_CHUNK;
    const chunk = chunkCache[ci];
    return chunk ? (chunk[li] || null) : null;
  } else if (pbMode === 'legacy') {
    const li = pbAbsIndex - windowStart;
    return (li >= 0 && li < recordingFrames.length) ? recordingFrames[li] : null;
  }
  return null;
}

// ============================================================
// チャンクモード: フェッチとシーク（O(1)）
// ============================================================
async function fetchChunkByIdx(sessionId, chunkIdx) {
  if (chunkCache[chunkIdx] !== undefined) return chunkCache[chunkIdx];
  try {
    const url = `/api/session/${encodeURIComponent(sessionId)}/chunk/${chunkIdx}`;
    const res = await fetch(url);
    if (!res.ok) { chunkCache[chunkIdx] = []; return []; }
    const frames = parseLines(await res.text());
    chunkCache[chunkIdx] = frames;
    // 現在位置から3チャンク以上離れたものを削除
    const currentCi = Math.floor(pbAbsIndex / FRAMES_PER_CHUNK);
    for (const k of Object.keys(chunkCache).map(Number)) {
      if (Math.abs(k - currentCi) > 3) delete chunkCache[k];
    }
    return frames;
  } catch(e) { return []; }
}

async function seekToChunked(absIdx) {
  pbAbsIndex = Math.max(0, Math.min(absIdx, totalFrames - 1));
  const ci = Math.floor(pbAbsIndex / FRAMES_PER_CHUNK);
  if (chunkCache[ci] === undefined) {
    const wasPlaying = pbPlaying;
    if (wasPlaying) { pbPlaying = false; clearInterval(pbTimer); }
    await fetchChunkByIdx(currentSessionId, ci);
    if (wasPlaying) { pbPlaying = true; pbTimer = setInterval(pbStep, RENDER_INTERVAL_MS); }
  }
  updateSliderState();
  const frame = currentFrame();
  if (frame) currentSnapshot = frame;
  // 隣接チャンクを先読み
  const maxChunk = Math.max(0, Math.ceil(totalFrames / FRAMES_PER_CHUNK) - 1);
  if (ci + 1 <= maxChunk) fetchChunkByIdx(currentSessionId, ci + 1).catch(() => {});
}

// ============================================================
// レガシーモード: スライディングウィンドウ
// ============================================================
function parseLines(text) {
  return text.trim().split('\n').filter(l => l.trim()).map(l => {
    try { return JSON.parse(l); } catch(e) { return null; }
  }).filter(Boolean);
}

async function fetchChunk(fromLine, limit, filename = null) {
  const params = new URLSearchParams({ from_line: fromLine, limit });
  const url = filename
    ? `/api/recording/${encodeURIComponent(filename)}?${params}`
    : `/api/recording?${params}`;
  const res = await fetch(url);
  const newTotal = parseInt(res.headers.get('X-Total-Lines') || '0');
  const frames = parseLines(await res.text());
  return { frames, total: newTotal };
}

async function loadInitialWindow(filename = null) {
  isFetching = true;
  try {
    const { frames, total } = await fetchChunk(0, CHUNK_SIZE, filename);
    recordingFrames = frames;
    windowStart = 0;
    totalFrames = total;
    pbAbsIndex = 0;
  } finally {
    isFetching = false;
  }
  updateSliderState();
  const frame = currentFrame();
  if (frame) currentSnapshot = frame;
}

function trimWindowFront() {
  if (recordingFrames.length <= WINDOW_SIZE) return;
  const li = pbAbsIndex - windowStart;
  const maxTrim = Math.min(
    recordingFrames.length - WINDOW_SIZE,
    Math.max(0, li - CHUNK_SIZE)
  );
  if (maxTrim > 0) {
    recordingFrames = recordingFrames.slice(maxTrim);
    windowStart += maxTrim;
  }
}

async function prefetchNext() {
  if (isFetching) return;
  const nextStart = windowStart + recordingFrames.length;
  if (nextStart >= totalFrames) return;
  isFetching = true;
  try {
    const { frames, total } = await fetchChunk(nextStart, CHUNK_SIZE, currentFilename);
    totalFrames = total;
    recordingFrames = recordingFrames.concat(frames);
    trimWindowFront();
    updateSliderMax();
  } finally {
    isFetching = false;
  }
}

async function seekToLegacy(absIdx) {
  pbAbsIndex = Math.max(0, Math.min(absIdx, totalFrames - 1));
  const li = pbAbsIndex - windowStart;
  if (li < 0 || li >= recordingFrames.length) {
    const wasPlaying = pbPlaying;
    if (wasPlaying) { pbPlaying = false; clearInterval(pbTimer); }
    const newStart = Math.max(0, pbAbsIndex - Math.floor(CHUNK_SIZE / 4));
    isFetching = true;
    try {
      const { frames, total } = await fetchChunk(newStart, CHUNK_SIZE, currentFilename);
      recordingFrames = frames;
      windowStart = newStart;
      totalFrames = total;
    } finally { isFetching = false; }
    if (wasPlaying) { pbPlaying = true; pbTimer = setInterval(pbStep, RENDER_INTERVAL_MS); }
  }
  updateSliderState();
  const frame = currentFrame();
  if (frame) currentSnapshot = frame;
}

// ============================================================
// 再生ステップ（チャンク・レガシー共通、速度制御付き）
// ============================================================
function pbStep() {
  const frame = currentFrame();
  if (frame) {
    currentSnapshot = frame;
    document.getElementById('pb-slider').value = pbAbsIndex;
    updateTimeDisplay();
  }
  if (!pbPlaying) return;

  const framesPerTick = SPEED_CONFIGS[currentSpeed];
  pbAbsIndex = Math.min(pbAbsIndex + framesPerTick, totalFrames - 1);

  if (pbAbsIndex >= totalFrames - 1) {
    if (currentMode === 'catchup') {
      // 追っかけモードはライブエッジで待機（タイマー継続、catchupPollが更新次第再開）
      return;
    }
    // 履歴モードは末尾で再生終了
    pbPlaying = false;
    document.getElementById('pb-play').textContent = '▶';
    return;
  }

  if (pbMode === 'chunked') {
    const ci = Math.floor(pbAbsIndex / FRAMES_PER_CHUNK);
    if (chunkCache[ci] === undefined) {
      fetchChunkByIdx(currentSessionId, ci).catch(() => {});
    }
    // 次チャンクを先読み
    const maxChunk = Math.max(0, Math.ceil(totalFrames / FRAMES_PER_CHUNK) - 1);
    if (ci + 1 <= maxChunk && chunkCache[ci + 1] === undefined) {
      fetchChunkByIdx(currentSessionId, ci + 1).catch(() => {});
    }
  } else if (pbMode === 'legacy') {
    const li = pbAbsIndex - windowStart;
    if (li >= 0 && li >= recordingFrames.length - PREFETCH_AHEAD) {
      prefetchNext();
    }
  }
}

// ============================================================
// 再生ボタン・スライダー
// ============================================================
document.getElementById('pb-play').addEventListener('click', () => {
  if (pbPlaying) {
    pbPlaying = false;
    document.getElementById('pb-play').textContent = '▶';
    clearInterval(pbTimer);
  } else {
    if (pbAbsIndex >= totalFrames - 1) pbAbsIndex = 0;
    pbPlaying = true;
    document.getElementById('pb-play').textContent = '⏸';
    pbTimer = setInterval(pbStep, RENDER_INTERVAL_MS);
  }
});

document.getElementById('pb-slider').addEventListener('input', (e) => {
  const target = parseInt(e.target.value);
  pbAbsIndex = target;
  if (pbMode === 'chunked') {
    const ci = Math.floor(pbAbsIndex / FRAMES_PER_CHUNK);
    if (chunkCache[ci] !== undefined) {
      const frame = currentFrame();
      if (frame) { currentSnapshot = frame; updateTimeDisplay(); }
    } else {
      clearTimeout(seekDebounce);
      seekDebounce = setTimeout(() => seekToChunked(target), 400);
    }
  } else if (pbMode === 'legacy') {
    const li = pbAbsIndex - windowStart;
    if (li >= 0 && li < recordingFrames.length) {
      currentSnapshot = recordingFrames[li];
      updateTimeDisplay();
    } else {
      clearTimeout(seekDebounce);
      seekDebounce = setTimeout(() => seekToLegacy(target), 400);
    }
  }
});

// ============================================================
// 速度ボタン
// ============================================================
function setSpeed(speed) {
  currentSpeed = speed;
  // タイマーを新しい間隔で再スタート
  if (pbPlaying) {
    clearInterval(pbTimer);
    pbTimer = setInterval(pbStep, RENDER_INTERVAL_MS);
  }
  for (const sp of [10, 100, 1000]) {
    document.getElementById(`btn-speed-${sp}`).classList.toggle('active-mode', sp === speed);
  }
}

document.getElementById('btn-speed-10').addEventListener('click', () => setSpeed(10));
document.getElementById('btn-speed-100').addEventListener('click', () => setSpeed(100));
document.getElementById('btn-speed-1000').addEventListener('click', () => setSpeed(1000));

// ============================================================
// 追っかけポーリング
// ============================================================
async function catchupPoll() {
  try {
    if (pbMode === 'chunked') {
      const res = await fetch(`/api/session/${encodeURIComponent(currentSessionId)}/manifest`);
      if (!res.ok) return;
      const manifest = await res.json();
      const newTotal = manifest.total_frames || 0;
      if (newTotal <= totalFrames) return;
      totalFrames = newTotal;
      updateSliderMax();
      // ライブエッジにいる場合は最新チャンクへ追従
      const atEdge = pbAbsIndex >= totalFrames - FRAMES_PER_CHUNK - 5;
      if (atEdge) {
        pbAbsIndex = totalFrames - 1;
        document.getElementById('pb-slider').value = pbAbsIndex;
        const ci = Math.floor(pbAbsIndex / FRAMES_PER_CHUNK);
        if (chunkCache[ci] === undefined) {
          fetchChunkByIdx(currentSessionId, ci).catch(() => {});
        }
      }
    } else if (pbMode === 'legacy') {
      if (isFetching) return;
      const prevTotal = totalFrames;
      const atLiveEdge = (pbAbsIndex >= prevTotal - 5);
      const limit = atLiveEdge ? CHUNK_SIZE : 1;
      const { frames, total } = await fetchChunk(prevTotal, limit);
      if (total === prevTotal) return;
      totalFrames = total;
      if (atLiveEdge && frames.length > 0) {
        recordingFrames = recordingFrames.concat(frames);
        if (recordingFrames.length > WINDOW_SIZE) {
          const trimCount = recordingFrames.length - WINDOW_SIZE;
          recordingFrames = recordingFrames.slice(trimCount);
          windowStart += trimCount;
        }
        pbAbsIndex = totalFrames - 1;
        document.getElementById('pb-slider').value = pbAbsIndex;
      }
      updateSliderMax();
    }
  } catch(e) {}
}

// ============================================================
// 追っかけモード
// ============================================================
async function enterCatchupMode() {
  currentMode = 'catchup';
  clearInterval(catchupTimer);
  pbPlaying = false;
  clearInterval(pbTimer);
  document.getElementById('pb-play').textContent = '▶';

  try {
    const res = await fetch('/api/current-session');
    const info = await res.json();

    if (info.type === 'chunked') {
      pbMode = 'chunked';
      currentSessionId = info.session_id;
      chunkCache = {};
      const manifest = info.manifest;
      totalFrames = manifest ? (manifest.total_frames || 0) : 0;

      if (totalFrames === 0) {
        alert('録画データがありません。シミュレーションが開始されているか確認してください。');
        return;
      }

      // 最新チャンクから開始
      pbAbsIndex = Math.max(0, totalFrames - 1);
      const ci = Math.floor(pbAbsIndex / FRAMES_PER_CHUNK);
      await fetchChunkByIdx(currentSessionId, ci);
    } else {
      // レガシーモード
      pbMode = 'legacy';
      currentFilename = null;
      recordingFrames = []; windowStart = 0;
      await loadInitialWindow(null);
      if (recordingFrames.length === 0) {
        alert('録画データがありません。シミュレーションが開始されているか確認してください。');
        return;
      }
    }
  } catch(e) {
    alert('セッション情報の取得に失敗しました: ' + e.message);
    return;
  }

  updateSliderState();
  const frame = currentFrame();
  if (frame) currentSnapshot = frame;
  catchupTimer = setInterval(catchupPoll, 5000);
}

// ============================================================
// 履歴モード
// ============================================================
async function enterHistoryMode() {
  currentMode = 'history';
  clearInterval(catchupTimer);
  pbPlaying = false;
  clearInterval(pbTimer);
  document.getElementById('pb-play').textContent = '▶';

  try {
    const res = await fetch('/api/sessions');
    const sessions = await res.json();
    const sel = document.getElementById('history-select');
    sel.innerHTML = '';

    if (sessions.length === 0) {
      sel.innerHTML = '<option value="">過去録画なし</option>';
      return;
    }

    for (const s of sessions) {
      const opt = document.createElement('option');
      if (s.type === 'chunked') {
        opt.value = JSON.stringify({ type: 'chunked', session_id: s.session_id });
        const total = s.manifest ? (s.manifest.total_frames || 0) : 0;
        const totalSec = Math.round(total * RECORD_INTERVAL_MS / 1000);
        opt.textContent = `${s.session_id} (${total}fr / 約${totalSec}s)`;
      } else {
        opt.value = JSON.stringify({ type: 'legacy', filename: s.filename });
        const totalSec = Math.round((s.frame_count || 0) * RECORD_INTERVAL_MS / 1000);
        opt.textContent = `[旧] ${s.filename} (${s.frame_count}fr / 約${totalSec}s)`;
      }
      sel.appendChild(opt);
    }
  } catch(e) {
    document.getElementById('history-select').innerHTML = '<option value="">取得失敗</option>';
  }
}

document.getElementById('history-load-btn').addEventListener('click', async () => {
  const valStr = document.getElementById('history-select').value;
  if (!valStr) return;
  let val;
  try { val = JSON.parse(valStr); } catch(e) { return; }

  pbPlaying = false;
  clearInterval(pbTimer);
  document.getElementById('pb-play').textContent = '▶';

  if (val.type === 'chunked') {
    pbMode = 'chunked';
    currentSessionId = val.session_id;
    chunkCache = {};

    const mres = await fetch(`/api/session/${encodeURIComponent(val.session_id)}/manifest`);
    if (!mres.ok) { alert('マニフェストの取得に失敗しました。'); return; }
    const manifest = await mres.json();
    totalFrames = manifest.total_frames || 0;
    pbAbsIndex = 0;

    if (totalFrames === 0) { alert('録画データが空です。'); return; }

    await fetchChunkByIdx(currentSessionId, 0);
  } else {
    // レガシーファイル
    pbMode = 'legacy';
    currentFilename = val.filename;
    recordingFrames = []; windowStart = 0;
    await loadInitialWindow(val.filename);
    if (recordingFrames.length === 0) { alert('録画データが空です。'); return; }
  }

  updateSliderState();
  const frame = currentFrame();
  if (frame) currentSnapshot = frame;
});

// ============================================================
// スライダー表示更新
// ============================================================
function updateSliderState() {
  document.getElementById('pb-slider').max   = Math.max(0, totalFrames - 1);
  document.getElementById('pb-slider').value = pbAbsIndex;
  updateSliderMax();
  updateTimeDisplay();
}

function updateSliderMax() {
  document.getElementById('pb-slider').max = Math.max(0, totalFrames - 1);
  const totalSec = Math.floor(totalFrames * RECORD_INTERVAL_MS / 1000);
  document.getElementById('pb-total').textContent = `/ 約${totalSec}s (${totalFrames}fr)`;
}

function updateTimeDisplay() {
  const frame = currentFrame();
  if (frame) document.getElementById('pb-time').textContent = Math.floor((frame.elapsed_ms || 0) / 1000) + 's';
}

// ============================================================
// モード切り替え
// ============================================================
function setActiveBtn(activeId) {
  for (const id of ['btn-live', 'btn-catchup', 'btn-history']) {
    document.getElementById(id).classList.toggle('active-mode', id === activeId);
  }
}

function hideAllControls() {
  document.getElementById('playback-controls').classList.remove('visible');
  document.getElementById('history-controls').classList.remove('visible');
}

function stopAllPlayback() {
  pbPlaying = false;
  clearInterval(pbTimer);
  clearInterval(catchupTimer);
  clearTimeout(seekDebounce);
  document.getElementById('pb-play').textContent = '▶';
}

document.getElementById('btn-live').addEventListener('click', () => {
  currentMode = 'live';
  pbMode = null;
  stopAllPlayback();
  hideAllControls();
  setActiveBtn('btn-live');
  document.getElementById('mode-badge').textContent = 'LIVE';
  document.getElementById('mode-badge').className = 'badge live';
});

document.getElementById('btn-catchup').addEventListener('click', async () => {
  stopAllPlayback();
  hideAllControls();
  setActiveBtn('btn-catchup');
  document.getElementById('mode-badge').textContent = '追っかけ';
  document.getElementById('mode-badge').className = 'badge playback';
  document.getElementById('playback-controls').classList.add('visible');
  await enterCatchupMode();
});

document.getElementById('btn-history').addEventListener('click', async () => {
  stopAllPlayback();
  hideAllControls();
  setActiveBtn('btn-history');
  document.getElementById('mode-badge').textContent = '履歴';
  document.getElementById('mode-badge').className = 'badge playback';
  document.getElementById('playback-controls').classList.add('visible');
  document.getElementById('history-controls').classList.add('visible');
  await enterHistoryMode();
});

// ============================================================
// 初期化
// ============================================================
window.addEventListener('load', async () => {
  await loadTopology();
  connectWs();
  requestAnimationFrame(renderFrame);
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
