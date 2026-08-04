#!/usr/bin/env python3
"""
UAV デバッグモード サーバー (debug_server.py)

FastAPI + WebSocket + Redis pub/sub でデバッグモードUIをホストする。

起動例:
  python3 scripts/debug_server.py --port 9001 --topology config/topology/koriyama_topology.txt \
      --redis-port 6379

エンドポイント:
  GET  /                            → UI (static: debug_static/index.html)
  GET  /api/topology                → トポロジ JSON
  GET  /api/route-results/{cid}     → 経路探索結果 JSON
  POST /api/assign                  → 事業者生成コマンド
  POST /api/fly/{client_id}         → 飛行開始コマンド
  POST /api/pause                   → 一時停止コマンド
  POST /api/resume                  → 再開コマンド
  POST /api/reset                   → 初期化コマンド
  WS   /ws                          → リアルタイム UAV 状態 + イベント
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
from fastapi.responses import JSONResponse, FileResponse
from fastapi.staticfiles import StaticFiles
import uvicorn

# =============================================================================
# 引数パース
# =============================================================================

parser = argparse.ArgumentParser(description="UAV Debug Server")
parser.add_argument("--port",       type=int, default=9001,        help="HTTP server port")
parser.add_argument("--topology",   required=True,                 help="Path to topology file")
parser.add_argument("--redis-port", type=int, default=6379,        help="Redis port")
parser.add_argument("--redis-host",           default="localhost", help="Redis host")
args = parser.parse_args()

SERVER_PORT   = args.port
TOPOLOGY_PATH = args.topology
REDIS_HOST    = args.redis_host
REDIS_PORT    = args.redis_port
SIM_ID        = os.environ.get("SIM_ID", "1")

COMMAND_CHANNEL   = "debug:command"
VIZ_STATES_KEY    = f"viz:sim_{SIM_ID}:states"
POLL_INTERVAL_MS  = 200    # UAV状態ポーリング間隔

# =============================================================================
# トポロジ読み込み
# =============================================================================

def load_topology(path: str) -> dict:
    nodes: Dict[int, dict] = {}
    links: List[dict]      = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if parts[0] == "NODE" and len(parts) >= 4:
                nid      = int(parts[1])
                x        = float(parts[2])
                y        = float(parts[3])
                district = parts[4] if len(parts) > 4 else "0"
                nodes[nid] = {"id": nid, "x": x, "y": y, "district": district}
            elif parts[0] == "LINK" and len(parts) >= 5:
                links.append({
                    "src":        int(parts[1]),
                    "dst":        int(parts[2]),
                    "capacity":   float(parts[3]),
                    "distance_m": float(parts[4]),
                })
    return {"nodes": nodes, "links": links}

try:
    TOPOLOGY = load_topology(TOPOLOGY_PATH)
    print(f"[debug_server] トポロジ読み込み完了: "
          f"{len(TOPOLOGY['nodes'])} ノード, {len(TOPOLOGY['links'])} リンク")
except Exception as e:
    print(f"[debug_server] トポロジ読み込み失敗: {e}", file=sys.stderr)
    sys.exit(1)

# =============================================================================
# グローバル状態
# =============================================================================

current_uav_states: Dict[str, dict] = {}
connected_ws: Set[WebSocket]        = set()
redis_http: Optional[aioredis.Redis] = None   # HTTP/polling 用
redis_pub:  Optional[aioredis.Redis] = None   # pub/sub 用

# =============================================================================
# FastAPI アプリ
# =============================================================================

@asynccontextmanager
async def lifespan(app: FastAPI):
    asyncio.create_task(uav_state_poller())
    asyncio.create_task(event_subscriber())
    print(f"[debug_server] 起動完了 → http://localhost:{SERVER_PORT}")
    yield

app = FastAPI(title="UAV Debug Server", lifespan=lifespan)

# static ファイルは /static/ でサーブ（index.html は / で個別返却）
_static_dir = Path(__file__).parent / "debug_static"
app.mount("/static", StaticFiles(directory=str(_static_dir)), name="static")

# =============================================================================
# バックグラウンドタスク
# =============================================================================

async def uav_state_poller() -> None:
    """Redisから定期的にUAV状態を取得してWebSocketへ配信する。"""
    global current_uav_states, redis_http
    try:
        redis_http = aioredis.Redis(
            host=REDIS_HOST, port=REDIS_PORT, decode_responses=True)
        print(f"[debug_server] Redis(HTTP) 接続: {REDIS_HOST}:{REDIS_PORT}")
    except Exception as e:
        print(f"[debug_server] Redis(HTTP) 接続失敗: {e}", file=sys.stderr)
        return

    while True:
        try:
            raw_map: Dict[str, str] = await redis_http.hgetall(VIZ_STATES_KEY)
            new_states: Dict[str, dict] = {}
            for field, val in raw_map.items():
                try:
                    new_states[field] = json.loads(val)
                except json.JSONDecodeError:
                    pass
            current_uav_states = new_states

            if connected_ws:
                await broadcast({"type": "states",
                                  "ts": int(time.time() * 1000),
                                  "states": current_uav_states})
        except Exception as e:
            print(f"[debug_server] poller エラー: {e}", file=sys.stderr)
        await asyncio.sleep(POLL_INTERVAL_MS / 1000.0)


async def event_subscriber() -> None:
    """Redisの debug:command チャンネルを購読し、JavaからのイベントをWSへ転送する。"""
    global redis_pub
    try:
        redis_pub = aioredis.Redis(
            host=REDIS_HOST, port=REDIS_PORT, decode_responses=True)
        pubsub = redis_pub.pubsub()
        await pubsub.subscribe(COMMAND_CHANNEL)
        print(f"[debug_server] Redis(pub/sub) 購読: {COMMAND_CHANNEL}")
    except Exception as e:
        print(f"[debug_server] Redis(pub/sub) 接続失敗: {e}", file=sys.stderr)
        return

    async for message in pubsub.listen():
        if message["type"] != "message":
            continue
        data: str = message["data"]
        try:
            if data.startswith("ROUTES_READY:"):
                parts = data.split(":", 1)
                if len(parts) < 2 or not parts[1].isdigit():
                    print(f"[debug_server] ROUTES_READY 不正フォーマット: {data!r}", file=sys.stderr)
                    continue
                client_id = int(parts[1])
                # 経路結果を Redis から読んで WS へ転送
                result_json = await redis_http.get(f"debug:route_results:{client_id}")
                result = json.loads(result_json) if result_json else {}
                await broadcast({"type": "routes_ready",
                                  "clientId": client_id,
                                  "result": result})
            elif data == "RESET_DONE":
                await broadcast({"type": "reset_done"})
        except Exception as e:
            print(f"[debug_server] event_subscriber エラー: {e}", file=sys.stderr)


async def broadcast(message: dict) -> None:
    """全WebSocketクライアントにメッセージを送信する。"""
    if not connected_ws:
        return
    data = json.dumps(message, ensure_ascii=False)
    dead: Set[WebSocket] = set()
    for ws in list(connected_ws):
        try:
            await ws.send_text(data)
        except Exception:
            dead.add(ws)
    connected_ws.difference_update(dead)

# =============================================================================
# REST エンドポイント
# =============================================================================

@app.get("/")
async def root():
    return FileResponse(str(_static_dir / "index.html"))

@app.get("/api/topology")
async def get_topology():
    return JSONResponse({
        "nodes": [v for v in TOPOLOGY["nodes"].values()],
        "links": TOPOLOGY["links"],
    })

@app.get("/api/route-results/{client_id}")
async def get_route_results(client_id: int):
    if redis_http is None:
        return JSONResponse({"error": "Redis未接続"}, status_code=503)
    raw = await redis_http.get(f"debug:route_results:{client_id}")
    if raw is None:
        return JSONResponse({"error": "not found"}, status_code=404)
    return JSONResponse(json.loads(raw))

@app.post("/api/assign")
async def post_assign(body: dict):
    """
    body: { "src": int, "dst": int, "uavCount": int, "method": int }
    """
    try:
        src      = int(body["src"])
        dst      = int(body["dst"])
        uav_count = int(body["uavCount"])
        method   = int(body.get("method", 3))  # デフォルト: EPS
    except (KeyError, ValueError) as e:
        return JSONResponse({"error": str(e)}, status_code=400)

    cmd = f"ASSIGN:{src}:{dst}:{uav_count}:{method}"
    await _publish(cmd)
    return JSONResponse({"ok": True, "command": cmd})

@app.post("/api/fly/{client_id}")
async def post_fly(client_id: int):
    await _publish(f"FLY:{client_id}")
    return JSONResponse({"ok": True})

@app.post("/api/pause")
async def post_pause():
    await _publish("PAUSE")
    return JSONResponse({"ok": True})

@app.post("/api/resume")
async def post_resume():
    await _publish("RESUME")
    return JSONResponse({"ok": True})

@app.post("/api/reset")
async def post_reset():
    await _publish("RESET")
    return JSONResponse({"ok": True})

async def _publish(msg: str) -> None:
    if redis_http is not None:
        await redis_http.publish(COMMAND_CHANNEL, msg)

# =============================================================================
# WebSocket エンドポイント
# =============================================================================

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    connected_ws.add(websocket)
    try:
        # 接続直後に現在のUAV状態を送信
        await websocket.send_text(json.dumps({
            "type": "states",
            "ts": int(time.time() * 1000),
            "states": current_uav_states,
        }, ensure_ascii=False))
        while True:
            await websocket.receive_text()  # ping keepalive
    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        connected_ws.discard(websocket)

# =============================================================================
# エントリーポイント
# =============================================================================

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=SERVER_PORT, log_level="warning")
