# Phase 0 セットアップ - クイックスタート

## エラー対処: Redissonパッケージが見つからない

`src/server/redis/` 配下のファイルでエラーが表示されている場合、これは正常です。
Mavenの依存関係がまだインストールされていないためです。

## セットアップ手順

### 方法1: 自動セットアップスクリプトを使用（推奨）

```bash
cd /Users/ninomiyakengou/workspace/UAVSimulator/UAVSimulator
./setup-phase0.sh
```

このスクリプトは以下を自動的に実行します：
1. 前提条件の確認
2. Maven依存関係のインストール（Redissonなど）
3. Redisコンテナの起動
4. 接続テスト

### 方法2: 手動セットアップ

```bash
# 1. Maven依存関係をインストール
mvn clean install

# 2. Redisコンテナを起動
docker-compose up -d

# 3. Redisが起動するまで待機（10秒程度）
sleep 10

# 4. 接続テストを実行
mvn compile exec:java -Dexec.mainClass="server.redis.RedisConnectionTest"
```

## トラブルシューティング

### IDEでエラーが消えない場合

IntelliJ IDEAの場合：
1. File → Invalidate Caches / Restart
2. Maven Tool Window → Reload All Maven Projects
3. エラーが消えるまで待機

Eclipse の場合：
1. プロジェクトを右クリック → Maven → Update Project
2. Clean...を実行

### Dockerが起動しない場合

```bash
# Dockerデーモンが起動しているか確認
docker ps

# 起動していない場合はDockerアプリケーションを起動
open -a Docker
```

### ポート6379が既に使用されている場合

既存のRedisを停止するか、docker-compose.ymlのポートを変更：

```yaml
ports:
  - "6380:6379"  # ホスト側を6380に変更
```

その後、RedisConnectionManager.javaの環境変数設定を変更：

```bash
export REDIS_PORT=6380
```

## 確認項目

Phase 0が正常に完了したことを確認：

```bash
# Redisコンテナが起動していることを確認
docker-compose ps
# 出力: uav-simulator-redis が Up 状態

# Redis Commanderにアクセス
# ブラウザで http://localhost:8081 を開く

# Redisに直接接続してテスト
docker exec -it uav-simulator-redis redis-cli ping
# 出力: PONG
```

## 次のステップ

Phase 0が完了したら、`docs/PHASE0_SETUP.md` の詳細ガイドを確認してください。
