#!/bin/bash

# Phase 0 セットアップスクリプト
# Redis環境のセットアップと接続テストを自動化

set -e  # エラーが発生したら即座に終了

echo "=========================================="
echo "  UAV Simulator - Phase 0 Setup"
echo "=========================================="
echo ""

# 色の定義
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# ステップ1: 前提条件の確認
echo "Step 1: 前提条件を確認しています..."
echo ""

check_command() {
    if command -v $1 &> /dev/null; then
        echo -e "${GREEN}✓${NC} $1 がインストールされています"
        $1 --version | head -n 1
    else
        echo -e "${RED}✗${NC} $1 がインストールされていません"
        echo "  $2 をインストールしてください"
        exit 1
    fi
}

check_command "docker" "Docker (https://docs.docker.com/get-docker/)"
check_command "docker-compose" "Docker Compose"
check_command "mvn" "Maven (https://maven.apache.org/install.html)"
check_command "java" "Java JDK 11以上 (https://adoptium.net/)"

echo ""

# ステップ2: Maven依存関係のインストール
echo "Step 2: Maven依存関係をインストールしています..."
echo ""

mvn clean install -DskipTests

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓${NC} Maven依存関係のインストールが完了しました"
else
    echo -e "${RED}✗${NC} Maven依存関係のインストールに失敗しました"
    exit 1
fi

echo ""

# ステップ3: Redisコンテナの起動
echo "Step 3: Redisコンテナを起動しています..."
echo ""

docker-compose up -d

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓${NC} Redisコンテナが起動しました"
    echo ""
    echo "起動したコンテナ:"
    docker-compose ps
else
    echo -e "${RED}✗${NC} Redisコンテナの起動に失敗しました"
    exit 1
fi

echo ""

# ステップ4: Redisの起動を待機
echo "Step 4: Redisの起動を待機しています..."
echo ""

MAX_RETRIES=30
RETRY_COUNT=0

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    if docker exec uav-simulator-redis redis-cli ping &> /dev/null; then
        echo -e "${GREEN}✓${NC} Redisが起動しました"
        break
    fi

    RETRY_COUNT=$((RETRY_COUNT + 1))
    echo "  待機中... ($RETRY_COUNT/$MAX_RETRIES)"
    sleep 1
done

if [ $RETRY_COUNT -eq $MAX_RETRIES ]; then
    echo -e "${RED}✗${NC} Redisの起動タイムアウト"
    exit 1
fi

echo ""

# ステップ5: Redis接続テスト
echo "Step 5: Redis接続テストを実行しています..."
echo ""

mvn compile exec:java -Dexec.mainClass="server.redis.RedisConnectionTest"

if [ $? -eq 0 ]; then
    echo ""
    echo -e "${GREEN}✓${NC} Redis接続テストが成功しました"
else
    echo ""
    echo -e "${RED}✗${NC} Redis接続テストに失敗しました"
    exit 1
fi

echo ""
echo "=========================================="
echo -e "${GREEN}  Phase 0 セットアップ完了！${NC}"
echo "=========================================="
echo ""
echo "次のステップ:"
echo "  1. Redis Commander を確認: http://localhost:8081"
echo "  2. 既存シミュレーターの動作確認:"
echo "     mvn exec:java -Dexec.mainClass=\"controller.BoundaryController\""
echo ""
echo "  3. Phase 1 に進む準備ができました！"
echo "     詳細: docs/REFACTORING_PLAN.md"
echo ""
