.PHONY: up down restart run compile clean status logs kill-old \
       heatmap heatmap-all extract-links venv-setup plot-congestion heatmap-video plot-link \
       plot-topology plot-topology-labels

# Dockerコンテナを起動
up:
	@echo "Redisコンテナを起動します..."
	docker-compose up -d
	@echo "✓ Redisコンテナが起動しました"
	@echo "  Redis: http://localhost:6379"
	@echo "  Redis Commander: http://localhost:8081"

# Dockerコンテナを停止
down:
	@echo "Redisコンテナを停止します..."
	docker-compose down
	@echo "✓ Redisコンテナが停止しました"

# Dockerコンテナを再起動
restart:
	@echo "Redisコンテナを再起動します..."
	docker-compose restart
	@echo "✓ Redisコンテナが再起動しました"

# Dockerコンテナの状態を確認
status:
	@echo "=== Dockerコンテナの状態 ==="
	@docker-compose ps

# Dockerコンテナのログを表示
logs:
	docker-compose logs -f

# プロジェクトをコンパイル
compile:
	@echo "プロジェクトをコンパイルします..."
	mvn compile

# JVMメモリ設定（長時間シミュレーション用）
JVM_OPTS := -Xms2g -Xmx4g

# シミュレータを実行（コンパイル後）
run: compile kill-old
	@echo "UAVシミュレータを起動します..."
	@echo "  JVM設定: $(JVM_OPTS)"
	MAVEN_OPTS="$(JVM_OPTS)" mvn exec:java -Dexec.mainClass="controller.BoundaryController"

# シミュレータを実行（コンパイルなし）
run-quick: kill-old
	@echo "UAVシミュレータを起動します..."
	@echo "  JVM設定: $(JVM_OPTS)"
	MAVEN_OPTS="$(JVM_OPTS)" mvn exec:java -Dexec.mainClass="controller.BoundaryController"

# シミュレータを実行（メモリ制限なし、短時間テスト用）
run-light: compile kill-old
	@echo "UAVシミュレータを起動します（軽量モード）..."
	mvn exec:java -Dexec.mainClass="controller.BoundaryController"

# 古いシミュレータプロセスを終了
kill-old:
	@echo "古いシミュレータプロセスを確認..."
	@pkill -f "controller.BoundaryController" 2>/dev/null && echo "✓ 古いプロセスを終了しました" || echo "  古いプロセスはありません"
	@sleep 1

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
	@echo "=== UAVシミュレータ Makefile ==="
	@echo ""
	@echo "利用可能なコマンド:"
	@echo "  make up          - Redisコンテナを起動"
	@echo "  make down        - Redisコンテナを停止"
	@echo "  make restart     - Redisコンテナを再起動"
	@echo "  make status      - Redisコンテナの状態を確認"
	@echo "  make logs        - Redisコンテナのログを表示"
	@echo "  make redis-clear - Redisデータをクリア"
	@echo "  make compile     - プロジェクトをコンパイル"
	@echo "  make run         - シミュレータを実行（コンパイル込み、メモリ4GB、古いプロセス自動終了）"
	@echo "  make run-quick   - シミュレータを実行（コンパイルなし、メモリ4GB、古いプロセス自動終了）"
	@echo "  make run-light   - シミュレータを実行（コンパイル込み、メモリ制限なし、短時間テスト用）"
	@echo "  make kill-old    - 古いシミュレータプロセスを終了"
	@echo "  make clean       - ビルド成果物をクリーンアップ"
	@echo "  make help        - このヘルプを表示"
	@echo ""
	@echo "典型的な使用例:"
	@echo "  1. make up       # Redisを起動"
	@echo "  2. make run      # シミュレータを実行（Ctrl+Cで終了可能）"
	@echo "  3. make run      # 再実行OK（古いプロセスは自動終了）"
	@echo "  4. make down     # 終了時にRedisを停止"
	@echo ""
	@echo "=== 分析ツール ==="
	@echo "  make venv-setup              - Python仮想環境をセットアップ"
	@echo "  make heatmap SNAPSHOT=<file> - 単一スナップショットのヒートマップ生成"
	@echo "  make heatmap-all             - 全スナップショットのヒートマップ生成"
	@echo "  make extract-links           - リンク別CSVファイル抽出"
	@echo "  make aggregate-flight        - 平均飛行ステータス集計"
	@echo "  make plot-congestion         - 混雑率グラフ生成"
	@echo "  make heatmap-video           - ヒートマップ動画生成"
	@echo "  make plot-link               - リンク別load_rateグラフ生成"
	@echo "  make plot-topology           - トポロジ描画"
	@echo "  make plot-topology-labels    - トポロジ描画（ノード番号付き）"
	@echo ""
	@echo "分析ツール使用例:"
	@echo "  make heatmap SNAPSHOT=src/result/large_scale/Bisectional/snapshot/snapshot_1420045.csv"
	@echo "    -> output/heatmap/Bisectional/phase4_heatmap_1420045.png"
	@echo "  make heatmap SNAPSHOT=src/result/large_scale/PGEPS/snapshot/snapshot_1420045.csv"
	@echo "    -> output/heatmap/PGEPS/phase4_heatmap_1420045.png"
	@echo "  make heatmap-all RESULT_DIR=src/result/large_scale/Bisectional"
	@echo "    -> output/heatmap/Bisectional/phase{1-4}_heatmap_*.png"
	@echo "  make heatmap-all RESULT_DIR=src/result/large_scale/PGEPS"
	@echo "    -> output/heatmap/PGEPS/phase{1-4}_heatmap_*.png"
	@echo "  make extract-links RESULT_DIR=src/result/large_scale/Bisectional"
	@echo "  make plot-congestion RESULT_DIR=src/result/large_scale/Bisectional_1"
	@echo "    -> output/graphs/congestion_rate_Bisectional_1.png"
	@echo "  make heatmap-video RESULT_DIR=src/result/large_scale/Bisectional_1 FPS=3"
	@echo "    -> output/videos/heatmap_Bisectional_1.mp4"
	@echo "  make plot-link RESULT_DIR=src/result/large_scale/Bisectional FROM=157 TO=176"
	@echo "    -> output/graphs/link_load_157_176.png"

# =============================================================================
# 分析ツール設定
# =============================================================================

# Python仮想環境
VENV := .venv
PYTHON := $(VENV)/bin/python3

# デフォルトのパス設定
TOPOLOGY := config/topology/koriyama_topology.txt
RESULT_DIR := src/result/large_scale/Bisectional
SNAPSHOT_DIR := $(RESULT_DIR)/snapshot
LINK_STATUS := $(RESULT_DIR)/link_status/link_status.csv
OUTPUT_DIR := output

# RESULT_DIRからサーチャー名を動的に取得（例: src/result/large_scale/Bisectional → Bisectional）
SEARCHER_NAME := $(notdir $(RESULT_DIR))

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
# Usage: make heatmap-all [RESULT_DIR=path/to/result]
heatmap-all: venv-setup
	@mkdir -p $(OUTPUT_DIR)/heatmap/$(SEARCHER_NAME)
	@echo "全スナップショットのヒートマップを生成します..."
	@echo "  スナップショットディレクトリ: $(SNAPSHOT_DIR)"
	@echo "  出力先: $(OUTPUT_DIR)/heatmap/$(SEARCHER_NAME)"
	$(PYTHON) scripts/plot_congestion_heatmap.py $(TOPOLOGY) $(SNAPSHOT_DIR) $(OUTPUT_DIR)/heatmap/$(SEARCHER_NAME) --all
	@echo "✓ 全ヒートマップを生成しました"

# リンク別CSVファイル抽出
# Usage: make extract-links [RESULT_DIR=path/to/result]
extract-links:
	@echo "リンク別CSVファイルを抽出します..."
	@echo "  入力ファイル: $(LINK_STATUS)"
	@if [ ! -f "$(LINK_STATUS)" ]; then \
		echo "Error: link_status.csv が見つかりません: $(LINK_STATUS)"; \
		exit 1; \
	fi
	python3 scripts/extract_links.py $(LINK_STATUS)
	@echo "✓ リンク別ファイルを抽出しました"

# 平均飛行ステータス集計
# Usage: make aggregate-flight [RESULT_DIR=path/to/result]
aggregate-flight:
	@echo "平均飛行ステータスを集計します..."
	python3 scripts/aggregate_flight_status.py $(RESULT_DIR)
	@echo "✓ 平均飛行ステータスを出力しました"

# 混雑率グラフ生成
# Usage: make plot-congestion [RESULT_DIR=path/to/result]
plot-congestion: venv-setup
	@echo "混雑率グラフを生成します..."
	@echo "  入力: $(RESULT_DIR)/link_status/congestion_rate.csv"
	@mkdir -p $(OUTPUT_DIR)/graphs
	$(PYTHON) scripts/plot_congestion_rate.py $(RESULT_DIR) $(OUTPUT_DIR)/graphs
	@echo "✓ 混雑率グラフを生成しました"

# ヒートマップ動画生成
# Usage: make heatmap-video [RESULT_DIR=path/to/result] [FPS=2]
FPS := 2
heatmap-video:
	@echo "ヒートマップ動画を生成します..."
	@echo "  入力: $(OUTPUT_DIR)/heatmap/$(SEARCHER_NAME)/"
	@echo "  FPS: $(FPS)"
	@mkdir -p $(OUTPUT_DIR)/videos
	$(PYTHON) scripts/create_heatmap_video.py $(OUTPUT_DIR)/heatmap/$(SEARCHER_NAME) $(OUTPUT_DIR)/videos/heatmap_$(SEARCHER_NAME).mp4 $(FPS)
	@echo "✓ ヒートマップ動画を生成しました"

# リンク別load_rateグラフ生成
# Usage: make plot-link RESULT_DIR=path/to/result FROM=0 TO=85
FROM := 0
TO := 1
plot-link: venv-setup
	@echo "リンク別load_rateグラフを生成します..."
	@echo "  リンク: $(FROM) → $(TO)"
	@mkdir -p $(OUTPUT_DIR)/graphs
	$(PYTHON) scripts/plot_link_load.py $(RESULT_DIR)/link_status/links $(OUTPUT_DIR)/graphs $(FROM) $(TO)
	@echo "✓ リンク別グラフを生成しました"

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
