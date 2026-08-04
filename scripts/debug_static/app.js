/**
 * UAV デバッグモード フロントエンド (app.js)
 * Vue 3 CDN + SVG トポロジ描画 + WebSocket リアルタイム更新
 */

const { createApp, ref, reactive, computed, onMounted, nextTick } = Vue;

// ============================================================
// 定数
// ============================================================
const SVG_PADDING  = 40;
const NODE_RADIUS  = 3.5;
const UAV_RADIUS   = 3.2;
const LINK_OFFSET  = 4;  // 双方向リンクのオフセット px

const METHOD_LABELS = {
  1: "Dijkstra",
  2: "PS (PhysarumSolver)",
  3: "EPS (ExtendedPS)",
  4: "Hybrid (EPS+PS)",
  5: "Binary Search EPS+PS",
  6: "Bisectional PG-EPS",
  7: "StepControlled PG-EPS",
};

// ============================================================
// Vue アプリ
// ============================================================
createApp({
  setup() {
    // --- トポロジ ---
    const topology = ref(null);    // { nodes: [...], links: [...] }
    const nodeMap  = ref({});      // id -> node

    // --- SVG サイズ ---
    let svgW = 800, svgH = 600;

    // --- UAV 状態 ---
    const uavStates  = ref({});   // key -> state dict
    const uavCount   = computed(() => Object.keys(uavStates.value).length);

    // --- デバッグ状態 ---
    const isPaused   = ref(false);
    const connected  = ref(false);
    const statusMsg  = ref("接続中...");

    // --- クライアント生成フォーム ---
    const form = reactive({
      src:      null,
      dst:      null,
      uavCount: 3,
      method:   3,
    });

    // 経路割り当て中フラグ（ボタン連打防止）
    const assigning = ref(false);

    // --- 選択ノードのラベル ---
    const srcLabel = computed(() => form.src !== null ? `ノード ${form.src}` : "未選択");
    const dstLabel = computed(() => form.dst !== null ? `ノード ${form.dst}` : "未選択");

    // --- 選択モード ("src" | "dst" | null) ---
    const selectMode = ref(null);

    // --- 経路結果 (clientId -> resultObject) ---
    const routeResults = ref({});

    // --- UAV個別選択: clientId → 選択中UAVインデックス (undefined = 全表示) ---
    const selectedUavs = ref({});

    // --- 飛行開始待ちの clientId 一覧 ---
    const pendingFly = ref([]);   // [{ clientId, src, dst, uavCount }]

    // --- WebSocket ---
    let ws = null;

    // ============================================================
    // SVG 座標変換
    // ============================================================
    function toSvgX(nx) { return SVG_PADDING + nx * (svgW - 2 * SVG_PADDING); }
    function toSvgY(ny) { return SVG_PADDING + (1 - ny) * (svgH - 2 * SVG_PADDING); }

    // ============================================================
    // トポロジ読み込み + 描画
    // ============================================================
    async function loadTopology() {
      const res  = await fetch("/api/topology");
      const data = await res.json();
      topology.value = data;
      nodeMap.value  = {};
      for (const n of data.nodes) nodeMap.value[n.id] = n;
      await nextTick();
      renderTopology();
    }

    function renderTopology() {
      const svgEl = document.getElementById("topo-svg");
      if (!svgEl) return;
      svgW = svgEl.clientWidth  || 800;
      svgH = svgEl.clientHeight || 600;

      const gLinks = document.getElementById("g-links");
      const gNodes = document.getElementById("g-nodes");
      if (!gLinks || !gNodes) return;
      gLinks.innerHTML = "";
      gNodes.innerHTML = "";

      // リンク描画
      for (const lnk of topology.value.links) {
        const a = nodeMap.value[lnk.src], b = nodeMap.value[lnk.dst];
        if (!a || !b) continue;
        const line = makeSvgEl("line");
        line.setAttribute("x1", toSvgX(a.x));
        line.setAttribute("y1", toSvgY(a.y));
        line.setAttribute("x2", toSvgX(b.x));
        line.setAttribute("y2", toSvgY(b.y));
        line.setAttribute("stroke", "#2a3040");
        line.setAttribute("stroke-width", "1");
        gLinks.appendChild(line);
      }

      // ノード描画
      for (const n of topology.value.nodes) {
        const cx = toSvgX(n.x), cy = toSvgY(n.y);
        const fill = n.district === "1" ? "#2d4a6a" : "#1e3040";

        const circle = makeSvgEl("circle");
        circle.setAttribute("cx", cx);
        circle.setAttribute("cy", cy);
        circle.setAttribute("r",  NODE_RADIUS);
        circle.setAttribute("fill",   fill);
        circle.setAttribute("stroke", "#4a5568");
        circle.setAttribute("stroke-width", "0.8");
        circle.classList.add("node-circle");
        circle.dataset.nodeId = n.id;

        circle.addEventListener("click", () => onNodeClick(n.id));
        circle.style.cursor = "pointer";
        gNodes.appendChild(circle);
      }
    }

    function onNodeClick(nodeId) {
      if (selectMode.value === "src") {
        form.src = nodeId;
        selectMode.value = "dst";
        highlightNodes();
      } else if (selectMode.value === "dst") {
        if (nodeId === form.src) {
          statusMsg.value = "到着ノードは出発ノードと異なるノードを選択してください";
          return;
        }
        form.dst = nodeId;
        selectMode.value = null;
        highlightNodes();
      }
    }

    function highlightNodes() {
      document.querySelectorAll(".node-circle").forEach(el => {
        el.classList.remove("selected-src", "selected-dst");
      });
      if (form.src !== null) {
        const el = document.querySelector(`.node-circle[data-node-id="${form.src}"]`);
        if (el) el.classList.add("selected-src");
      }
      if (form.dst !== null) {
        const el = document.querySelector(`.node-circle[data-node-id="${form.dst}"]`);
        if (el) el.classList.add("selected-dst");
      }
    }

    // ============================================================
    // UAV 描画
    // ============================================================
    let animFrameId = null;

    function startRenderLoop() {
      function loop() {
        renderUAVs();
        animFrameId = requestAnimationFrame(loop);
      }
      animFrameId = requestAnimationFrame(loop);
    }

    function renderUAVs() {
      const gUAVs = document.getElementById("g-uavs");
      if (!gUAVs) return;
      gUAVs.innerHTML = "";

      const nowMs = Date.now();
      const states = uavStates.value;

      for (const [key, state] of Object.entries(states)) {
        const pos = calcUavPos(state, nowMs);
        if (!pos) continue;

        const color =
          state.status === "FLYING"   ? "#e74c3c" :
          state.status === "HOVERING" ? "#3498db" : "#27ae60";

        const circle = makeSvgEl("circle");
        circle.setAttribute("cx", pos.x);
        circle.setAttribute("cy", pos.y);
        circle.setAttribute("r",  UAV_RADIUS);
        circle.setAttribute("fill", color);
        circle.setAttribute("fill-opacity", "0.85");

        const title = makeSvgEl("title");
        title.textContent =
          `UAV${state.uavId} (client${state.clientId}) ${state.status}`;
        circle.appendChild(title);
        gUAVs.appendChild(circle);
      }
    }

    function calcUavPos(state, nowMs) {
      const nm = nodeMap.value;
      if (state.status === "FLYING" && state.fromNode != null && state.toNode != null) {
        const a = nm[state.fromNode], b = nm[state.toNode];
        if (!a || !b) return null;
        const linkDistM    = state.linkDistM || 1;
        const speedMs      = (state.speed || 12) / 1000; // m/ms
        const linkTimeMs   = linkDistM / speedMs;
        const elapsed      = nowMs - (state.linkStartMs || nowMs);
        const progress     = Math.min(Math.max(elapsed / linkTimeMs, 0), 1);

        // 双方向オフセット
        const dx = toSvgX(b.x) - toSvgX(a.x);
        const dy = toSvgY(b.y) - toSvgY(a.y);
        const len = Math.sqrt(dx*dx + dy*dy) || 1;
        const ox = -dy / len * LINK_OFFSET;
        const oy =  dx / len * LINK_OFFSET;

        return {
          x: toSvgX(a.x) + dx * progress + ox,
          y: toSvgY(a.y) + dy * progress + oy,
        };
      } else if (state.currentNode != null) {
        const n = nm[state.currentNode];
        if (!n) return null;
        return { x: toSvgX(n.x), y: toSvgY(n.y) };
      }
      return null;
    }

    // ============================================================
    // 経路表示（太い矢印）
    // ============================================================
    function renderRoutePaths() {
      const gRoutes = document.getElementById("g-routes");
      if (!gRoutes) return;
      gRoutes.innerHTML = "";

      for (const [cidStr, result] of Object.entries(routeResults.value)) {
        if (!result.uavs) continue;

        // 選択中UAVがあればそのUAVだけ、なければ全UAVを描画
        const selIdx = selectedUavs.value[Number(cidStr)];
        const uavsToRender = selIdx !== undefined
          ? [result.uavs[selIdx]].filter(Boolean)
          : result.uavs;

        const drawnPaths = new Set();
        for (const uav of uavsToRender) {
          if (!uav) continue;
          const pathKey = uav.path.join(",");
          if (drawnPaths.has(pathKey)) continue;
          drawnPaths.add(pathKey);

          const path = uav.path;
          for (let i = 0; i < path.length - 1; i++) {
            const a = nodeMap.value[path[i]];
            const b = nodeMap.value[path[i + 1]];
            if (!a || !b) continue;

            const line = makeSvgEl("line");
            line.setAttribute("x1", toSvgX(a.x));
            line.setAttribute("y1", toSvgY(a.y));
            line.setAttribute("x2", toSvgX(b.x));
            line.setAttribute("y2", toSvgY(b.y));
            line.setAttribute("stroke",         "#f39c12");
            line.setAttribute("stroke-width",   "2");
            line.setAttribute("stroke-opacity", "0.75");
            line.setAttribute("marker-end",     "url(#arrow-route)");
            gRoutes.appendChild(line);
          }
        }
      }
    }

    // ============================================================
    // WebSocket
    // ============================================================
    function connectWS() {
      const proto = location.protocol === "https:" ? "wss:" : "ws:";
      ws = new WebSocket(`${proto}//${location.host}/ws`);

      ws.onopen = () => {
        connected.value = true;
        statusMsg.value = "接続中";
      };

      ws.onclose = () => {
        connected.value = false;
        statusMsg.value = "切断";
        setTimeout(connectWS, 3000);
      };

      ws.onmessage = (ev) => {
        try {
          const msg = JSON.parse(ev.data);
          handleWsMessage(msg);
        } catch (e) { /* ignore */ }
      };
    }

    function handleWsMessage(msg) {
      if (msg.type === "states") {
        uavStates.value = msg.states || {};
      } else if (msg.type === "routes_ready") {
        const cid    = msg.clientId;
        const result = msg.result || {};
        routeResults.value = { ...routeResults.value, [cid]: result };
        renderRoutePaths();
        // 飛行開始待ちリストに追加
        if (!pendingFly.value.find(p => p.clientId === cid)) {
          pendingFly.value = [...pendingFly.value, {
            clientId: cid,
            src:      result.src,
            dst:      result.dst,
            uavCount: result.uavCount,
          }];
        }
        statusMsg.value = `client${cid} の経路が確定 → 飛行開始ボタンを押してください`;
      } else if (msg.type === "reset_done") {
        uavStates.value    = {};
        routeResults.value = {};
        selectedUavs.value = {};
        pendingFly.value   = [];
        form.src    = null;
        form.dst    = null;
        isPaused.value  = false;
        statusMsg.value = "初期化完了";
        clearRoutePaths();
        highlightNodes();
      }
    }

    function clearRoutePaths() {
      const gRoutes = document.getElementById("g-routes");
      if (gRoutes) gRoutes.innerHTML = "";
    }

    // ============================================================
    // API コール
    // ============================================================
    async function doAssign() {
      if (assigning.value) return;  // 二重送信防止
      if (form.src === null || form.dst === null) {
        statusMsg.value = "出発ノードと到着ノードを選択してください";
        return;
      }
      if (form.src === form.dst) {
        statusMsg.value = "出発ノードと到着ノードを異なるノードにしてください";
        return;
      }
      assigning.value = true;
      try {
        const body = {
          src:      form.src,
          dst:      form.dst,
          uavCount: form.uavCount,
          method:   form.method,
        };
        const res = await fetch("/api/assign", {
          method:  "POST",
          headers: { "Content-Type": "application/json" },
          body:    JSON.stringify(body),
        });
        if (!res.ok) {
          statusMsg.value = "ASSIGN 失敗: " + await res.text();
          return;
        }
        statusMsg.value = `経路探索中... (src=${form.src} dst=${form.dst} UAV=${form.uavCount})`;
        form.src = null;
        form.dst = null;
        highlightNodes();
      } catch (e) {
        statusMsg.value = "通信エラー: " + e.message;
      } finally {
        assigning.value = false;
      }
    }

    async function doFly(clientId) {
      try {
        await fetch(`/api/fly/${clientId}`, { method: "POST" });
        pendingFly.value = pendingFly.value.filter(p => p.clientId !== clientId);
        // 飛行開始後は該当クライアントの経路矢印と選択状態を消す
        const newResults  = { ...routeResults.value };
        delete newResults[clientId];
        routeResults.value = newResults;
        const newSelected = { ...selectedUavs.value };
        delete newSelected[clientId];
        selectedUavs.value = newSelected;
        renderRoutePaths();
        statusMsg.value = `client${clientId} 飛行開始`;
      } catch (e) {
        statusMsg.value = "通信エラー: " + e.message;
      }
    }

    async function doPause() {
      await fetch("/api/pause", { method: "POST" });
      isPaused.value = true;
    }

    async function doResume() {
      await fetch("/api/resume", { method: "POST" });
      isPaused.value = false;
    }

    async function doReset() {
      if (!confirm("全UAVの飛行データとRedisを初期化します。よろしいですか？")) return;
      await fetch("/api/reset", { method: "POST" });
      statusMsg.value = "初期化中...";
    }

    // ============================================================
    // ノード選択モード
    // ============================================================
    function startSelectSrc() { selectMode.value = "src"; }
    function startSelectDst() { selectMode.value = "dst"; }

    // ============================================================
    // UAV個別経路選択
    // ============================================================
    function selectUav(clientId, uavIdx) {
      // 同じボタンを再押しで選択解除（全UAV表示に戻す）
      if (selectedUavs.value[clientId] === uavIdx) {
        const updated = { ...selectedUavs.value };
        delete updated[clientId];
        selectedUavs.value = updated;
      } else {
        selectedUavs.value = { ...selectedUavs.value, [clientId]: uavIdx };
      }
      renderRoutePaths();
    }

    // ============================================================
    // SVG ユーティリティ
    // ============================================================
    function makeSvgEl(tag) {
      return document.createElementNS("http://www.w3.org/2000/svg", tag);
    }

    // ============================================================
    // ライフサイクル
    // ============================================================
    onMounted(async () => {
      await loadTopology();
      connectWS();
      startRenderLoop();

      // SVG リサイズ対応
      window.addEventListener("resize", () => {
        renderTopology();
        renderRoutePaths();
      });
    });

    return {
      topology, uavStates, uavCount,
      isPaused, connected, statusMsg,
      form, srcLabel, dstLabel, selectMode,
      routeResults, pendingFly,
      selectedUavs, selectUav,
      assigning,
      METHOD_LABELS,
      doAssign, doFly, doPause, doResume, doReset,
      startSelectSrc, startSelectDst,
    };
  },
}).mount("#app");
