.PHONY: up down restart run compile clean status logs

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
run: compile
	@echo "UAVシミュレータを起動します..."
	mvn exec:java -Dexec.mainClass="controller.BoundaryController"

# シミュレータを実行（コンパイルなし）
run-quick:
	@echo "UAVシミュレータを起動します..."
	mvn exec:java -Dexec.mainClass="controller.BoundaryController"

# ビルド成果物をクリーンアップ
clean:
	@echo "ビルド成果物をクリーンアップします..."
	mvn clean

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
	@echo "  make compile     - プロジェクトをコンパイル"
	@echo "  make run         - シミュレータを実行（コンパイル込み）"
	@echo "  make run-quick   - シミュレータを実行（コンパイルなし）"
	@echo "  make clean       - ビルド成果物をクリーンアップ"
	@echo "  make help        - このヘルプを表示"
	@echo ""
	@echo "典型的な使用例:"
	@echo "  1. make up       # Redisを起動"
	@echo "  2. make run      # シミュレータを実行"
	@echo "  3. make down     # 終了時にRedisを停止"
