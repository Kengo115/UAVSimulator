.PHONY: up down down-all restart run compile clean status logs kill-sim \
       heatmap heatmap-all extract-links venv-setup plot-congestion heatmap-video plot-link \
       plot-topology plot-topology-labels ensure-redis plot-method-comparison \
       plot-flight-cdf-comparison plot-real-flight-cdf-comparison \
       plot-preflight-wait-cdf-comparison plot-inflight-wait-cdf-comparison \
       plot-distance-cdf-comparison plot-exceeded-rate-comparison \
       plot-exceeded-rate-comparison-jp

# =============================================================================
# 並列シミュレーション設定
# =============================================================================
# シミュレーションID（デフォルト: 1）
# 使用例: make run SIM_ID=2
SIM_ID := 1

# SIM_IDに基づくポートとディレクトリ設定
# SIM_ID=1 → Redis:6379, SIM_ID=2 → Redis:6380, ...
REDIS_PORT_NUM := $(shell expr 6378 + $(SIM_ID))
RESULT_DIR_SIM := src/result/sim_$(SIM_ID)
LOG_DIR_SIM := src/log/sim_$(SIM_ID)

# PIDファイルのパス（SIM_ID別プロセス管理用）
PID_FILE := .sim_$(SIM_ID).pid

# =============================================================================
# Dockerコンテナ管理
# =============================================================================

# Dockerコンテナを起動（SIM_ID=1のデフォルト用）
up:
	@echo "Redisコンテナを起動します..."
	docker compose up -d
	@echo "✓ Redisコンテナが起動しました"
	@echo "  Redis: http://localhost:6379"
	@echo "  Redis Commander: http://localhost:8081"

# Dockerコンテナを停止（SIM_ID=1のみ）
down:
	@echo "Redisコンテナを停止します..."
	docker compose down
	@echo "✓ Redisコンテナが停止しました"

# 全SIM_ID用Redisコンテナとシミュレータを停止
down-all: stop-all
	@echo "全Redisコンテナを停止します..."
	@docker compose down 2>/dev/null || true
	@for container in $$(docker ps --format '{{.Names}}' | grep '^uav-redis-sim'); do \
		echo "  $$container を停止..."; \
		docker stop $$container && docker rm $$container; \
	done
	@echo "✓ 全Redisコンテナが停止しました"

# Dockerコンテナを再起動
restart:
	@echo "Redisコンテナを再起動します..."
	docker compose restart
	@echo "✓ Redisコンテナが再起動しました"

# Dockerコンテナの状態を確認
status:
	@echo "=== Dockerコンテナの状態 ==="
	@docker ps --format "table {{.Names}}\t{{.Ports}}\t{{.Status}}" | grep -E "redis|NAMES"

# Dockerコンテナのログを表示
logs:
	docker compose logs -f

# =============================================================================
# Redis自動起動（SIM_ID別）
# =============================================================================

# SIM_ID用のRedisが起動していなければ自動起動
ensure-redis:
ifeq ($(SIM_ID),1)
	@if ! docker ps --format '{{.Names}}' | grep -q "^uav-simulator-redis$$"; then \
		echo "SIM_ID=1: Redisコンテナを起動します..."; \
		docker compose up -d; \
		echo "✓ Redis起動完了 (ポート: 6379)"; \
		sleep 1; \
	else \
		echo "✓ Redis既に起動中 (ポート: 6379)"; \
	fi
else
	@if ! docker ps --format '{{.Names}}' | grep -q "^uav-redis-sim$(SIM_ID)$$"; then \
		echo "SIM_ID=$(SIM_ID): Redisコンテナを起動します..."; \
		docker run -d --name uav-redis-sim$(SIM_ID) \
			-p $(REDIS_PORT_NUM):6379 \
			redis:7.2-alpine \
			redis-server --appendonly yes --maxmemory 256mb --maxmemory-policy allkeys-lru; \
		echo "✓ Redis起動完了 (ポート: $(REDIS_PORT_NUM))"; \
		sleep 1; \
	else \
		echo "✓ Redis既に起動中 (ポート: $(REDIS_PORT_NUM))"; \
	fi
endif

# =============================================================================
# プロセス管理（SIM_ID別）
# =============================================================================

# 該当SIM_IDの古いプロセスのみを停止
kill-sim:
	@if [ -f $(PID_FILE) ]; then \
		OLD_PID=$$(cat $(PID_FILE)); \
		if ps -p $$OLD_PID > /dev/null 2>&1; then \
			echo "SIM_ID=$(SIM_ID): 古いプロセス (PID=$$OLD_PID) を停止します..."; \
			kill $$OLD_PID 2>/dev/null || true; \
			sleep 2; \
		fi; \
		rm -f $(PID_FILE); \
	fi

# 全シミュレーションを停止
stop-all:
	@echo "全シミュレーションを停止します..."
	@for f in .sim_*.pid; do \
		if [ -f "$$f" ]; then \
			PID=$$(cat "$$f"); \
			SID=$$(echo "$$f" | sed 's/.sim_\(.*\).pid/\1/'); \
			if ps -p $$PID > /dev/null 2>&1; then \
				echo "  SIM_ID=$$SID (PID=$$PID) を停止..."; \
				kill $$PID 2>/dev/null || true; \
			fi; \
			rm -f "$$f"; \
		fi; \
	done
	@echo "✓ 全シミュレーション停止完了"

# プロジェクトをコンパイル
compile:
	@echo "プロジェクトをコンパイルします..."
	mvn compile

# JVMメモリ設定（長時間シミュレーション用）
JVM_OPTS := -Xms2g -Xmx4g

# =============================================================================
# シミュレータ実行
# =============================================================================

# シミュレータを実行（コンパイル後）
# 使用例: make run SIM_ID=2
run: compile ensure-redis kill-sim
	@echo "UAVシミュレータを起動します..."
	@echo "  SIM_ID: $(SIM_ID)"
	@echo "  Redisポート: $(REDIS_PORT_NUM)"
	@echo "  結果出力先: $(RESULT_DIR_SIM)"
	@echo "  ログ出力先: $(LOG_DIR_SIM)"
	@echo "  JVM設定: $(JVM_OPTS)"
	@mkdir -p $(RESULT_DIR_SIM) $(LOG_DIR_SIM)
	@echo $$$$ > $(PID_FILE)
	REDIS_PORT=$(REDIS_PORT_NUM) RESULT_DIR=$(RESULT_DIR_SIM) LOG_DIR=$(LOG_DIR_SIM) SIM_ID=$(SIM_ID) \
		MAVEN_OPTS="$(JVM_OPTS)" mvn exec:java -Dexec.mainClass="controller.BoundaryController"; \
	rm -f $(PID_FILE)

# シミュレータを実行（コンパイルなし）
# 使用例: make run-quick SIM_ID=2
run-quick: ensure-redis kill-sim
	@echo "UAVシミュレータを起動します..."
	@echo "  SIM_ID: $(SIM_ID)"
	@echo "  Redisポート: $(REDIS_PORT_NUM)"
	@echo "  結果出力先: $(RESULT_DIR_SIM)"
	@echo "  ログ出力先: $(LOG_DIR_SIM)"
	@echo "  JVM設定: $(JVM_OPTS)"
	@mkdir -p $(RESULT_DIR_SIM) $(LOG_DIR_SIM)
	@echo $$$$ > $(PID_FILE)
	REDIS_PORT=$(REDIS_PORT_NUM) RESULT_DIR=$(RESULT_DIR_SIM) LOG_DIR=$(LOG_DIR_SIM) SIM_ID=$(SIM_ID) \
		MAVEN_OPTS="$(JVM_OPTS)" mvn exec:java -Dexec.mainClass="controller.BoundaryController"; \
	rm -f $(PID_FILE)

# シミュレータを実行（メモリ制限なし、短時間テスト用）
run-light: compile ensure-redis kill-sim
	@echo "UAVシミュレータを起動します（軽量モード）..."
	@mkdir -p $(RESULT_DIR_SIM) $(LOG_DIR_SIM)
	@echo $$$$ > $(PID_FILE)
	REDIS_PORT=$(REDIS_PORT_NUM) RESULT_DIR=$(RESULT_DIR_SIM) LOG_DIR=$(LOG_DIR_SIM) SIM_ID=$(SIM_ID) \
		mvn exec:java -Dexec.mainClass="controller.BoundaryController"; \
	rm -f $(PID_FILE)

# ビルド成果物をクリーンアップ
clean:
	@echo "ビルド成果物をクリーンアップします..."
	mvn clean

# Redisデータをクリア
redis-clear:
	@echo "Redisデータをクリアします..."
	docker exec uav-simulator-redis redis-cli FLUSHALL
	@echo "✓ Redisデータがクリアされました"

# ヘルプ
help:
	@echo "==============================================================================="
	@echo "                        UAVシミュレータ Makefile"
	@echo "==============================================================================="
	@echo ""
	@echo "【シミュレーション実行】"
	@echo "  make run SIM_ID=N         シミュレータを実行（Redis自動起動、コンパイル込み）"
	@echo "  make run-quick SIM_ID=N   シミュレータを実行（コンパイルなし）"
	@echo "  make run-light SIM_ID=N   シミュレータを実行（メモリ制限なし）"
	@echo ""
	@echo "  ※ SIM_ID省略時はSIM_ID=1として実行"
	@echo "  ※ make run SIM_ID=N だけで以下が自動実行されます:"
	@echo "      1. Redis未起動なら自動起動（SIM_ID=1→6379, SIM_ID=2→6380, ...）"
	@echo "      2. 該当SIM_IDの古いプロセスを停止（他SIM_IDには影響なし）"
	@echo "      3. シミュレーション開始"
	@echo ""
	@echo "-------------------------------------------------------------------------------"
	@echo "【並列シミュレーション】"
	@echo "-------------------------------------------------------------------------------"
	@echo "  複数ターミナルで同時実行可能:"
	@echo ""
	@echo "    ターミナル1: make run SIM_ID=1"
	@echo "    ターミナル2: make run SIM_ID=2"
	@echo "    ターミナル3: make run SIM_ID=3"
	@echo ""
	@echo "  各SIM_IDのリソース割り当て:"
	@echo "    SIM_ID  Redisポート  結果出力先              ログ出力先"
	@echo "    ------  ----------  ----------------------  ----------------"
	@echo "    1       6379        src/result/sim_1/       src/log/sim_1/"
	@echo "    2       6380        src/result/sim_2/       src/log/sim_2/"
	@echo "    N       6378+N      src/result/sim_N/       src/log/sim_N/"
	@echo ""
	@echo "-------------------------------------------------------------------------------"
	@echo "【分析ツール】"
	@echo "-------------------------------------------------------------------------------"
	@echo ""
	@echo "  ■ パラメータ説明"
	@echo "    SIM_ID    シミュレーションID（必須）"
	@echo "    METHOD    経路探索手法（デフォルト: Bisectional）"
	@echo "    Y_MAX     混雑率グラフのY軸最大値（省略時は自動計算）"
	@echo "    Y_INTERVAL 混雑率グラフのY軸間隔（省略時は自動計算）"
	@echo ""
	@echo "    ※ 重要: シミュレーション時に選択した手法をMETHODに指定してください"
	@echo "      - PS         ... Physarum Solver"
	@echo "      - EPS        ... Extended Physarum Solver"
	@echo "      - Bisectional ... Bisectional PG-EPS（デフォルト）"
	@echo "      - PGEPS      ... Step Controlled PG-EPS"
	@echo ""
	@echo "  ■ コマンド一覧"
	@echo "    make extract-links SIM_ID=N [METHOD=X]    リンク別CSVファイル抽出"
	@echo "    make plot-congestion SIM_ID=N [METHOD=X] [Y_MAX=N] [Y_INTERVAL=N]  混雑率グラフ生成"
	@echo "    make plot-top-load SIM_ID=N [METHOD=X]    最大負荷率Top10リンクのグラフ生成"
	@echo "    make heatmap-all SIM_ID=N [METHOD=X]      全スナップショットのヒートマップ生成"
	@echo "    make heatmap-video SIM_ID=N [METHOD=X]    ヒートマップ動画生成"
	@echo "    make plot-link SIM_ID=N FROM=X TO=Y       リンク別load_rateグラフ生成"
	@echo "    make plot-topology                        トポロジ描画"
	@echo "    make analyze-exceeded SIM_ID=N [METHOD=X] 容量超過率分析"
	@echo ""
	@echo "  ■ 使用例"
	@echo ""
	@echo "    # SIM_ID=1でBisectionalを使用した場合（METHODはデフォルトなので省略可）"
	@echo "    make extract-links SIM_ID=1"
	@echo "    make plot-congestion SIM_ID=1"
	@echo "      → 入力: src/result/sim_1/large_scale/Bisectional/link_status/"
	@echo "      → 出力: output/sim_1/graphs/"
	@echo ""
	@echo "    # SIM_ID=2でPSを使用した場合（METHODの指定が必要）"
	@echo "    make extract-links SIM_ID=2 METHOD=PS"
	@echo "    make plot-congestion SIM_ID=2 METHOD=PS"
	@echo "      → 入力: src/result/sim_2/large_scale/PS/link_status/"
	@echo "      → 出力: output/sim_2/graphs/"
	@echo ""
	@echo "    # 特定リンクのload_rateグラフを生成"
	@echo "    make plot-link SIM_ID=1 METHOD=PS FROM=117 TO=123"
	@echo "      → 出力: output/sim_1/graphs/link_load_117_123.png"
	@echo ""
	@echo "    # 最大負荷率Top10のリンクをグラフ化"
	@echo "    make plot-top-load SIM_ID=1 METHOD=PS"
	@echo "      → 出力: output/sim_1/graphs/top10_max_load_links.png"
	@echo ""
	@echo "    # Top20にする場合"
	@echo "    make plot-top-load SIM_ID=1 METHOD=PS TOP_N=20"
	@echo ""
	@echo "    # 混雑率グラフのY軸最大値を100%、間隔を20%に指定"
	@echo "    make plot-congestion SIM_ID=1 Y_MAX=100 Y_INTERVAL=20"
	@echo ""
	@echo "-------------------------------------------------------------------------------"
	@echo "【管理コマンド】"
	@echo "-------------------------------------------------------------------------------"
	@echo "  make status       起動中のRedisコンテナを確認"
	@echo "  make stop-all     全シミュレーションを停止"
	@echo "  make compile      プロジェクトをコンパイル"
	@echo "  make clean        ビルド成果物をクリーンアップ"
	@echo "  make redis-clear  SIM_ID=1用Redisデータをクリア"
	@echo "  make up           SIM_ID=1用Redisを手動起動"
	@echo "  make down         SIM_ID=1用Redisを停止"
	@echo "  make down-all     全シミュレータ＋全Redisを停止"
	@echo ""
	@echo "-------------------------------------------------------------------------------"
	@echo "【ディレクトリ構造】"
	@echo "-------------------------------------------------------------------------------"
	@echo "  src/result/sim_N/large_scale/[METHOD]/"
	@echo "    ├── client/           クライアント情報"
	@echo "    ├── time/             飛行時間記録"
	@echo "    ├── link_status/      リンク状態記録"
	@echo "    │   ├── link_status.csv"
	@echo "    │   ├── congestion_rate.csv"
	@echo "    │   └── links/        リンク別CSV（extract-links実行後）"
	@echo "    └── snapshot/         スナップショット"
	@echo ""
	@echo "  output/sim_N/"
	@echo "    ├── graphs/           グラフ出力"
	@echo "    ├── heatmap/          ヒートマップ出力"
	@echo "    └── videos/           動画出力"
	@echo ""

# =============================================================================
# 分析ツール設定
# =============================================================================

# Python仮想環境
VENV := .venv
PYTHON := $(VENV)/bin/python3

# デフォルトの経路探索手法
METHOD := Bisectional
Y_MAX :=
Y_INTERVAL :=

# デフォルトのパス設定（SIM_ID対応）
# 使用例: make plot-congestion SIM_ID=2 METHOD=PGEPS
TOPOLOGY := config/topology/koriyama_topology.txt
RESULT_DIR := $(RESULT_DIR_SIM)/large_scale/$(METHOD)
SNAPSHOT_DIR := $(RESULT_DIR)/snapshot
LINK_STATUS := $(RESULT_DIR)/link_status/link_status.csv
OUTPUT_DIR := output/sim_$(SIM_ID)

# RESULT_DIRからサーチャー名を動的に取得
SEARCHER_NAME := $(METHOD)

# Python仮想環境のセットアップ
venv-setup:
	@echo "Python仮想環境をセットアップします..."
	@if [ ! -d "$(VENV)" ]; then \
		python3 -m venv $(VENV); \
		$(VENV)/bin/pip install --upgrade pip --quiet; \
		$(VENV)/bin/pip install matplotlib networkx pandas --quiet; \
		echo "✓ 仮想環境を作成しました: $(VENV)"; \
	else \
		echo "✓ 仮想環境は既に存在します: $(VENV)"; \
	fi

# 単一スナップショットのヒートマップ生成
# Usage: make heatmap SNAPSHOT=path/to/snapshot.csv [SEARCHER_NAME=name]
heatmap: venv-setup
	@if [ -z "$(SNAPSHOT)" ]; then \
		echo "Error: SNAPSHOTを指定してください"; \
		echo "Usage: make heatmap SNAPSHOT=path/to/snapshot.csv"; \
		exit 1; \
	fi
	@SEARCHER=$$(echo "$(SNAPSHOT)" | sed -n 's|.*large_scale/\([^/]*\)/snapshot.*|\1|p'); \
	if [ -z "$$SEARCHER" ]; then SEARCHER="$(SEARCHER_NAME)"; fi; \
	mkdir -p $(OUTPUT_DIR)/heatmap/$$SEARCHER; \
	echo "ヒートマップを生成します: $(SNAPSHOT)"; \
	$(PYTHON) scripts/plot_congestion_heatmap.py $(TOPOLOGY) $(SNAPSHOT) $(OUTPUT_DIR)/heatmap/$$SEARCHER/; \
	echo "✓ ヒートマップを生成しました"

# 全スナップショットのヒートマップ一括生成
# Usage: make heatmap-all SIM_ID=N [METHOD=X]
heatmap-all: venv-setup
	@echo "全スナップショットのヒートマップを生成します..."
	@echo "  入力: $(SNAPSHOT_DIR)/"
	@echo "  出力: $(OUTPUT_DIR)/heatmap/$(SEARCHER_NAME)/"
	@if [ ! -d "$(SNAPSHOT_DIR)" ]; then \
		echo ""; \
		echo "Error: スナップショットディレクトリが見つかりません"; \
		echo "       $(SNAPSHOT_DIR)"; \
		echo ""; \
		echo "確認事項:"; \
		echo "  1. SIM_ID=$(SIM_ID) でシミュレーションを実行しましたか？"; \
		echo "  2. シミュレーション時に選択した手法は $(METHOD) ですか？"; \
		echo ""; \
		exit 1; \
	fi
	@mkdir -p $(OUTPUT_DIR)/heatmap/$(SEARCHER_NAME)
	$(PYTHON) scripts/plot_congestion_heatmap.py $(TOPOLOGY) $(SNAPSHOT_DIR) $(OUTPUT_DIR)/heatmap/$(SEARCHER_NAME) --all
	@echo "✓ 全ヒートマップを生成しました: $(OUTPUT_DIR)/heatmap/$(SEARCHER_NAME)/"

# リンク別CSVファイル抽出
# Usage: make extract-links SIM_ID=N [METHOD=X]
extract-links:
	@echo "リンク別CSVファイルを抽出します..."
	@echo "  入力ファイル: $(LINK_STATUS)"
	@if [ ! -f "$(LINK_STATUS)" ]; then \
		echo ""; \
		echo "Error: link_status.csv が見つかりません"; \
		echo "       $(LINK_STATUS)"; \
		echo ""; \
		echo "確認事項:"; \
		echo "  1. SIM_ID=$(SIM_ID) でシミュレーションを実行しましたか？"; \
		echo "  2. シミュレーション時に選択した手法は $(METHOD) ですか？"; \
		echo "     (PS, EPS, Bisectional, PGEPS のいずれかを METHOD= で指定)"; \
		echo ""; \
		exit 1; \
	fi
	python3 scripts/extract_links.py $(LINK_STATUS)
	@echo "✓ リンク別ファイルを抽出しました"
	@echo "  出力: $(RESULT_DIR)/link_status/links/"

# 平均飛行ステータス集計（出力先指定版）
# Usage: make aggregate-flight-to SIM_ID=N [METHOD=X] OUTPUT_SIM=M
#
# 例: make aggregate-flight-to SIM_ID=1 METHOD=PS OUTPUT_SIM=1
#     → 入力: src/result/sim_1/large_scale/PS/time/
#     → 出力: src/result/sim_1/time/ave_flightStatus.csv
#
# 例: make aggregate-flight-to SIM_ID=2 METHOD=Bisectional OUTPUT_SIM=2
#     → 入力: src/result/sim_2/large_scale/Bisectional/time/
#     → 出力: src/result/sim_2/time/ave_flightStatus.csv
OUTPUT_SIM := $(SIM_ID)
OUTPUT_SIM_DIR := src/result/sim_$(OUTPUT_SIM)
aggregate-flight-to:
	@echo "平均飛行ステータスを集計します..."
	@echo "  入力: $(RESULT_DIR)/time/"
	@echo "  出力: $(OUTPUT_SIM_DIR)/time/ave_flightStatus.csv"
	@if [ ! -d "$(RESULT_DIR)/time" ]; then \
		echo ""; \
		echo "Error: timeディレクトリが見つかりません"; \
		echo "       $(RESULT_DIR)/time/"; \
		echo ""; \
		echo "確認事項:"; \
		echo "  1. SIM_ID=$(SIM_ID) でシミュレーションを実行しましたか？"; \
		echo "  2. シミュレーション時に選択した手法は $(METHOD) ですか？"; \
		echo ""; \
		exit 1; \
	fi
	@mkdir -p $(OUTPUT_SIM_DIR)/time
	python3 scripts/aggregate_flight_status.py $(RESULT_DIR) --output $(OUTPUT_SIM_DIR)
	@echo "✓ 平均飛行ステータスを出力しました: $(OUTPUT_SIM_DIR)/time/ave_flightStatus.csv"

# 容量超過率分析
# Usage: make analyze-exceeded SIM_ID=N [METHOD=X]
#
# 経路探索結果が容量を超過した割合を分析する
# 判定基準: flightStayingTime > 0 のUAVが1台でも存在するクライアント
#
# 例: make analyze-exceeded SIM_ID=1 METHOD=PS
#     → 入力: src/result/sim_1/large_scale/PS/time/
#     → 出力: src/result/sim_1/large_scale/PS/capacity_exceeded.csv
analyze-exceeded:
	@echo "容量超過率を分析します..."
	@echo "  入力: $(RESULT_DIR)/time/"
	@if [ ! -d "$(RESULT_DIR)/time" ]; then \
		echo ""; \
		echo "Error: timeディレクトリが見つかりません"; \
		echo "       $(RESULT_DIR)/time/"; \
		echo ""; \
		echo "確認事項:"; \
		echo "  1. SIM_ID=$(SIM_ID) でシミュレーションを実行しましたか？"; \
		echo "  2. シミュレーション時に選択した手法は $(METHOD) ですか？"; \
		echo ""; \
		exit 1; \
	fi
	python3 scripts/analyze_capacity_exceeded.py $(RESULT_DIR)
	@echo "✓ 容量超過率を出力しました: $(RESULT_DIR)/capacity_exceeded.csv"

# 混雑率グラフ生成
# Usage: make plot-congestion SIM_ID=N [METHOD=X] [Y_MAX=N] [Y_INTERVAL=N]
plot-congestion: venv-setup
	@echo "混雑率グラフを生成します..."
	@echo "  入力: $(RESULT_DIR)/link_status/congestion_rate.csv"
	@if [ ! -f "$(RESULT_DIR)/link_status/congestion_rate.csv" ]; then \
		echo ""; \
		echo "Error: congestion_rate.csv が見つかりません"; \
		echo "       $(RESULT_DIR)/link_status/congestion_rate.csv"; \
		echo ""; \
		echo "確認事項:"; \
		echo "  1. SIM_ID=$(SIM_ID) でシミュレーションを実行しましたか？"; \
		echo "  2. シミュレーション時に選択した手法は $(METHOD) ですか？"; \
		echo ""; \
		exit 1; \
	fi
	@mkdir -p $(OUTPUT_DIR)/graphs
	$(PYTHON) scripts/plot_congestion_rate.py $(RESULT_DIR) $(OUTPUT_DIR)/graphs $(Y_MAX) $(Y_INTERVAL)
	@echo "✓ 混雑率グラフを生成しました: $(OUTPUT_DIR)/graphs/"

# ヒートマップ動画生成
# Usage: make heatmap-video SIM_ID=N [METHOD=X] [FPS=2]
FPS := 2
heatmap-video: venv-setup
	@echo "ヒートマップ動画を生成します..."
	@echo "  入力: $(OUTPUT_DIR)/heatmap/$(SEARCHER_NAME)/"
	@echo "  FPS: $(FPS)"
	@if [ ! -d "$(OUTPUT_DIR)/heatmap/$(SEARCHER_NAME)" ]; then \
		echo ""; \
		echo "Error: ヒートマップディレクトリが見つかりません"; \
		echo "       $(OUTPUT_DIR)/heatmap/$(SEARCHER_NAME)/"; \
		echo ""; \
		echo "先に heatmap-all を実行してヒートマップを生成してください:"; \
		echo "  make heatmap-all SIM_ID=$(SIM_ID) METHOD=$(METHOD)"; \
		echo ""; \
		exit 1; \
	fi
	@mkdir -p $(OUTPUT_DIR)/videos
	$(PYTHON) scripts/create_heatmap_video.py $(OUTPUT_DIR)/heatmap/$(SEARCHER_NAME) $(OUTPUT_DIR)/videos/heatmap_$(SEARCHER_NAME).mp4 $(FPS)
	@echo "✓ ヒートマップ動画を生成しました: $(OUTPUT_DIR)/videos/heatmap_$(SEARCHER_NAME).mp4"

# リンク別load_rateグラフ生成
# Usage: make plot-link SIM_ID=N [METHOD=X] FROM=X TO=Y
FROM := 0
TO := 1
plot-link: venv-setup
	@echo "リンク別load_rateグラフを生成します..."
	@echo "  リンク: $(FROM) → $(TO)"
	@echo "  入力: $(RESULT_DIR)/link_status/links/"
	@if [ ! -d "$(RESULT_DIR)/link_status/links" ]; then \
		echo ""; \
		echo "Error: リンク別CSVディレクトリが見つかりません"; \
		echo "       $(RESULT_DIR)/link_status/links/"; \
		echo ""; \
		echo "先に extract-links を実行してください:"; \
		echo "  make extract-links SIM_ID=$(SIM_ID) METHOD=$(METHOD)"; \
		echo ""; \
		exit 1; \
	fi
	@mkdir -p $(OUTPUT_DIR)/graphs
	$(PYTHON) scripts/plot_link_load.py $(RESULT_DIR)/link_status/links $(OUTPUT_DIR)/graphs $(FROM) $(TO)
	@echo "✓ リンク別グラフを生成しました: $(OUTPUT_DIR)/graphs/link_load_$(FROM)_$(TO).png"

# 最大負荷率Top Nリンクのグラフ生成
# Usage: make plot-top-load SIM_ID=N [METHOD=X] [TOP_N=10]
TOP_N := 10
plot-top-load: venv-setup
	@echo "最大負荷率 Top $(TOP_N) リンクのグラフを生成します..."
	@echo "  入力: $(RESULT_DIR)/link_status/links/_summary.csv"
	@if [ ! -f "$(RESULT_DIR)/link_status/links/_summary.csv" ]; then \
		echo ""; \
		echo "Error: _summary.csv が見つかりません"; \
		echo "       $(RESULT_DIR)/link_status/links/_summary.csv"; \
		echo ""; \
		echo "先に extract-links を実行してください:"; \
		echo "  make extract-links SIM_ID=$(SIM_ID) METHOD=$(METHOD)"; \
		echo ""; \
		exit 1; \
	fi
	@mkdir -p $(OUTPUT_DIR)/graphs
	$(PYTHON) scripts/plot_top_load_links.py $(RESULT_DIR)/link_status/links/_summary.csv $(OUTPUT_DIR)/graphs $(TOP_N)
	@echo "✓ グラフを生成しました: $(OUTPUT_DIR)/graphs/top$(TOP_N)_max_load_links.png"

# 全リンクの最大負荷率ランキンググラフ生成
# Usage: make plot-load-ranking SIM_ID=N [METHOD=X] [Y_MAX=N] [Y_INTERVAL=N]
plot-load-ranking: venv-setup
	@echo "全リンクの最大負荷率ランキンググラフを生成します..."
	@echo "  入力: $(RESULT_DIR)/link_status/links/_summary.csv"
	@if [ ! -f "$(RESULT_DIR)/link_status/links/_summary.csv" ]; then \
		echo ""; \
		echo "Error: _summary.csv が見つかりません"; \
		echo "       $(RESULT_DIR)/link_status/links/_summary.csv"; \
		echo ""; \
		echo "先に extract-links を実行してください:"; \
		echo "  make extract-links SIM_ID=$(SIM_ID) METHOD=$(METHOD)"; \
		echo ""; \
		exit 1; \
	fi
	@mkdir -p $(OUTPUT_DIR)/graphs
	$(PYTHON) scripts/plot_load_ranking.py $(RESULT_DIR)/link_status/links/_summary.csv $(OUTPUT_DIR)/graphs $(Y_MAX) $(Y_INTERVAL)
	@echo "✓ グラフを生成しました: $(OUTPUT_DIR)/graphs/load_ranking.png"

# 全UAVのflightTime累積分布グラフ生成
# Usage: make plot-flight-cdf SIM_ID=N [METHOD=X] [Y_MAX=N] [Y_INTERVAL=N]
plot-flight-cdf: venv-setup
	@echo "飛行時間累積分布グラフを生成します..."
	@echo "  入力: $(RESULT_DIR)/time/"
	@if [ ! -d "$(RESULT_DIR)/time" ]; then \
		echo ""; \
		echo "Error: timeディレクトリが見つかりません"; \
		echo "       $(RESULT_DIR)/time/"; \
		echo ""; \
		exit 1; \
	fi
	@mkdir -p $(OUTPUT_DIR)/graphs
	$(PYTHON) scripts/plot_flight_time_cdf.py $(RESULT_DIR)/time $(OUTPUT_DIR)/graphs $(Y_MAX) $(Y_INTERVAL)
	@echo "✓ グラフを生成しました: $(OUTPUT_DIR)/graphs/flight_time_cdf.png"

# トポロジ描画（ノード番号なし）
# Usage: make plot-topology [TOPOLOGY=path/to/topology.txt]
plot-topology: venv-setup
	@echo "トポロジを描画します..."
	@echo "  入力: $(TOPOLOGY)"
	@mkdir -p $(OUTPUT_DIR)
	$(PYTHON) scripts/plot_topology.py $(TOPOLOGY) $(OUTPUT_DIR)/topology_$(notdir $(basename $(TOPOLOGY))).png --simple
	@echo "✓ トポロジを描画しました: $(OUTPUT_DIR)/topology_$(notdir $(basename $(TOPOLOGY))).png"

# トポロジ描画（ノード番号あり）
# Usage: make plot-topology-labels [TOPOLOGY=path/to/topology.txt]
plot-topology-labels: venv-setup
	@echo "トポロジを描画します（ノード番号付き）..."
	@echo "  入力: $(TOPOLOGY)"
	@mkdir -p $(OUTPUT_DIR)
	$(PYTHON) scripts/plot_topology.py $(TOPOLOGY) $(OUTPUT_DIR)/topology_$(notdir $(basename $(TOPOLOGY)))_labels.png --simple --show-labels
	@echo "✓ トポロジを描画しました: $(OUTPUT_DIR)/topology_$(notdir $(basename $(TOPOLOGY)))_labels.png"

# 経路探索手法比較グラフ生成（積み上げ棒グラフ）
# Usage: make plot-method-comparison
#
# 対話的に以下を入力:
#   - グループ数 (例: 2)
#   - 各グループの経路探索手法名 (例: Bisectional, PS)
#   - 各グループ内の要素数 (例: 3 → λ=1.0, 1.5, 2.0)
#   - 各要素のλ値とsim番号
#   - 出力ファイル名 (デフォルト: output/method_comparison.png)
#
# 例:
#   グループ1: Bisectional
#     - λ=1.0 (sim_1)
#     - λ=1.5 (sim_2)
#     - λ=2.0 (sim_3)
#   グループ2: PS
#     - λ=1.0 (sim_4)
#     - λ=1.5 (sim_5)
#     - λ=2.0 (sim_6)
#
# データソース: src/result/sim_X/large_scale/{手法名}/time/ave_flightStatus.csv
# 出力先: output/ (デフォルト)
plot-method-comparison: venv-setup
	@echo "経路探索手法比較グラフを生成します..."
	@mkdir -p output
	$(PYTHON) scripts/plot_method_comparison.py
	@echo "✓ グラフ生成完了"


# 経路探索手法比較グラフ生成（積み上げ棒グラフ）
# Usage: make plot-method-comparison-jp

# データソース: src/result/sim_X/large_scale/{手法名}/time/ave_flightStatus.csv
# 出力先: output/ (デフォルト)

plot-method-comparison-jp: venv-setup
	@echo "経路探索手法比較グラフ(日本語版)を生成します..."
	@mkdir -p output
	$(PYTHON) scripts/plot_method_comparison_jp.py
	@echo "✓ グラフ生成完了"

# =============================================================================
# CDF比較グラフ生成（複数手法比較）
# =============================================================================

# flightTime CDF比較グラフ生成
# Usage: make plot-flight-cdf-comparison
#
# 対話的に以下を入力:
#   - グループ数 (例: 3)
#   - 各グループの表示名 (例: PG-EPS)
#   - 各グループのディレクトリ名 (例: Bisectional)
#   - 各グループのsim番号 (例: 1)
#   - X軸の最大値・間隔 (空欄で自動)
#   - 出力ファイル名 (デフォルト: output/flight_time_cdf_comparison.png)
#
# データソース: src/result/sim_X/large_scale/{手法名}/time/client*/flight_times.csv
plot-flight-cdf-comparison: venv-setup
	@echo "flightTime CDF比較グラフを生成します..."
	@mkdir -p output
	$(PYTHON) scripts/plot_flight_time_cdf_comparison.py
	@echo "✓ グラフ生成完了"

# realFlightTime CDF比較グラフ生成
# Usage: make plot-real-flight-cdf-comparison
#
# 対話的入力形式はplot-flight-cdf-comparisonと同様
# データソース: src/result/sim_X/large_scale/{手法名}/time/client*/flight_times.csv
plot-real-flight-cdf-comparison: venv-setup
	@echo "realFlightTime CDF比較グラフを生成します..."
	@mkdir -p output
	$(PYTHON) scripts/plot_real_flight_time_cdf_comparison.py
	@echo "✓ グラフ生成完了"

# 飛行前待機時間 CDF比較グラフ生成
# Usage: make plot-preflight-wait-cdf-comparison
#
# 飛行前待機時間 = pathWaitTime + flightStayingTime
# 対話的入力形式はplot-flight-cdf-comparisonと同様
# データソース: src/result/sim_X/large_scale/{手法名}/time/client*/flight_times.csv
plot-preflight-wait-cdf-comparison: venv-setup
	@echo "飛行前待機時間 CDF比較グラフを生成します..."
	@mkdir -p output
	$(PYTHON) scripts/plot_preflight_wait_cdf_comparison.py
	@echo "✓ グラフ生成完了"

# 飛行中待機時間 CDF比較グラフ生成
# Usage: make plot-inflight-wait-cdf-comparison
#
# 飛行中待機時間 = waitingTime（飛行中にリンク混雑等で待機した時間）
# 対話的入力形式はplot-flight-cdf-comparisonと同様
# データソース: src/result/sim_X/large_scale/{手法名}/time/client*/flight_times.csv
plot-inflight-wait-cdf-comparison: venv-setup
	@echo "飛行中待機時間 CDF比較グラフを生成します..."
	@mkdir -p output
	$(PYTHON) scripts/plot_inflight_wait_cdf_comparison.py
	@echo "✓ グラフ生成完了"

# distance CDF比較グラフ生成
# Usage: make plot-distance-cdf-comparison
#
# 対話的入力形式はplot-flight-cdf-comparisonと同様
# データソース: src/result/sim_X/large_scale/{手法名}/time/client*/flight_times.csv
plot-distance-cdf-comparison: venv-setup
	@echo "distance CDF比較グラフを生成します..."
	@mkdir -p output
	$(PYTHON) scripts/plot_distance_cdf_comparison.py
	@echo "✓ グラフ生成完了"

# 容量超過経路割り当て率 比較グラフ生成
# Usage: make plot-exceeded-rate-comparison
#
# 対話的に以下を入力:
#   - λの種類数 (例: 3)
#   - 各λの値 (例: 1.0, 1.5, 2.0)
#   - 経路探索手法の数 (例: 4)
#   - 各手法の表示名・ディレクトリ名
#   - 各λ×手法のsim番号
#   - Y軸の最大値・間隔 (空欄で自動)
#   - 出力ファイル名 (デフォルト: output/exceeded_rate_comparison.png)
#
# データソース: src/result/sim_X/large_scale/{手法名}/capacity_exceeded.csv
plot-exceeded-rate-comparison: venv-setup
	@echo "容量超過経路割り当て率 比較グラフを生成します..."
	@mkdir -p output
	$(PYTHON) scripts/plot_exceeded_rate_comparison.py
	@echo "✓ グラフ生成完了"

# 容量超過経路割り当て率 比較グラフ生成（日本語版）
# Usage: make plot-exceeded-rate-comparison-jp
#
# 対話的入力形式はplot-exceeded-rate-comparisonと同様
# 縦軸ラベルが日本語表記になります
# データソース: src/result/sim_X/large_scale/{手法名}/capacity_exceeded.csv
plot-exceeded-rate-comparison-jp: venv-setup
	@echo "容量超過経路割り当て率 比較グラフ（日本語版）を生成します..."
	@mkdir -p output
	$(PYTHON) scripts/plot_exceeded_rate_comparison_jp.py
	@echo "✓ グラフ生成完了"
