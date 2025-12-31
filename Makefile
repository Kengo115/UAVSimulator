.PHONY: up down restart run compile clean status logs kill-old

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

# シミュレータを実行（コンパイル後）
run: compile kill-old
	@echo "UAVシミュレータを起動します..."
	mvn exec:java -Dexec.mainClass="controller.BoundaryController"

# シミュレータを実行（コンパイルなし）
run-quick: kill-old
	@echo "UAVシミュレータを起動します..."
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
	@echo "  make run         - シミュレータを実行（コンパイル込み、古いプロセス自動終了）"
	@echo "  make run-quick   - シミュレータを実行（コンパイルなし、古いプロセス自動終了）"
	@echo "  make kill-old    - 古いシミュレータプロセスを終了"
	@echo "  make clean       - ビルド成果物をクリーンアップ"
	@echo "  make help        - このヘルプを表示"
	@echo ""
	@echo "典型的な使用例:"
	@echo "  1. make up       # Redisを起動"
	@echo "  2. make run      # シミュレータを実行（Ctrl+Cで終了可能）"
	@echo "  3. make run      # 再実行OK（古いプロセスは自動終了）"
	@echo "  4. make down     # 終了時にRedisを停止"
