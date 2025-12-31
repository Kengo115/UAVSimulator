# Phase 0: Redis環境セットアップガイド

## 概要

このガイドでは、UAVシミュレーターのRedis化プロジェクトのPhase 0（準備フェーズ）の手順を説明します。

## 前提条件

以下のソフトウェアがインストールされている必要があります：

- **Docker**: バージョン 20.10 以上
- **Docker Compose**: バージョン 2.0 以上
- **Maven**: バージョン 3.6 以上
- **Java JDK**: バージョン 11 以上

### インストール確認

```bash
# Docker
docker --version

# Docker Compose
docker-compose --version

# Maven
mvn --version

# Java
java -version
```

## セットアップ手順

### ステップ 1: Mavenの依存関係をインストール

プロジェクトルートで以下を実行します：

```bash
cd /Users/ninomiyakengou/workspace/UAVSimulator/UAVSimulator
mvn clean install
```

これにより、`pom.xml`に定義された以下の依存関係がダウンロードされます：
- Redisson 3.24.3
- SLF4J 2.0.9
- Jackson 2.15.3

### ステップ 2: Redisコンテナを起動

```bash
# バックグラウンドでRedisを起動
docker-compose up -d
```

起動確認：

```bash
# コンテナの状態を確認
docker-compose ps

# 出力例:
# NAME                      STATUS              PORTS
# uav-simulator-redis       Up 10 seconds       0.0.0.0:6379->6379/tcp
# uav-redis-commander       Up 10 seconds       0.0.0.0:8081->8081/tcp
```

### ステップ 3: Redis接続テスト

```bash
# Mavenでコンパイル
mvn compile

# Redis接続テストを実行
mvn exec:java -Dexec.mainClass="server.redis.RedisConnectionTest"
```

成功すると以下のような出力が表示されます：

```
=== Redis接続テスト ===

[1/5] Redis接続を確立しています...
✓ 接続成功: Redis: localhost:6379 (接続状態: 接続中)

[2/5] 接続状態を確認しています...
✓ 接続は正常です

[3/5] 文字列の書き込み・読み込みをテストしています...
  書き込み: 'Hello UAV Simulator!'
  読み込み: 'Hello UAV Simulator!'
✓ 文字列操作成功

[4/5] Hash構造の書き込み・読み込みをテストしています...
  書き込み:
    uavId: 1
    status: flying
    speed: 12.5
  読み込み:
    uavId: 1
    status: flying
    speed: 12.5
✓ Hash操作成功

[5/5] アトミック操作（リンク容量）をテストしています...
  初期容量: 5.0
  UAV飛行後: 4.0
  UAV到着後: 5.0
✓ アトミック操作成功

[追加情報] Redis内のキー数:
  総キー数: 0

=========================
✓ すべてのテストが成功しました！
Redis環境は正常に動作しています。
=========================
```

### ステップ 4: Redis Commander（オプション）

ブラウザで http://localhost:8081 にアクセスすると、Redis Commanderが開きます。
これはRedisのデータを視覚的に確認・編集できるWebツールです。

### ステップ 5: 既存シミュレーターの動作確認

Redis環境が整ったので、既存のシミュレーターが引き続き正常に動作することを確認します：

```bash
# 既存のシミュレーターを実行
mvn exec:java -Dexec.mainClass="controller.BoundaryController"
```

> **注意**: Phase 0では、既存のシミュレーターはまだRedisを使用しません。
> これは後続のPhaseで段階的に統合されます。

## トラブルシューティング

### 問題1: Redisコンテナが起動しない

**症状**:
```
Error response from daemon: Ports are not available
```

**解決策**:
1. ポート6379が既に使用されているか確認：
   ```bash
   lsof -i :6379
   ```
2. 既存のRedisプロセスを停止するか、`docker-compose.yml`のポート設定を変更

### 問題2: Maven依存関係のダウンロード失敗

**症状**:
```
Failed to execute goal ... Could not resolve dependencies
```

**解決策**:
1. インターネット接続を確認
2. Mavenリポジトリをクリア：
   ```bash
   rm -rf ~/.m2/repository
   mvn clean install
   ```

### 問題3: Redis接続テストが失敗

**症状**:
```
✗ 接続エラー: Unable to connect to Redis
```

**解決策**:
1. Redisコンテナが起動しているか確認：
   ```bash
   docker-compose ps
   ```
2. Redisログを確認：
   ```bash
   docker-compose logs redis
   ```
3. Redisに直接接続できるか確認：
   ```bash
   docker exec -it uav-simulator-redis redis-cli ping
   # 出力: PONG
   ```

## Redis基本コマンド

開発中に便利なRedisコマンド：

```bash
# Redisコンテナに接続
docker exec -it uav-simulator-redis redis-cli

# Redis CLI内で:
PING                    # 接続確認（PONG が返る）
KEYS *                  # すべてのキーを表示
GET test:string         # 文字列の値を取得
HGETALL test:uav:1      # Hashのすべてのフィールドを取得
FLUSHALL                # すべてのデータを削除（注意！）
INFO                    # サーバー情報を表示
```

## 環境変数の設定（オプション）

Redisの接続先を変更する場合、環境変数を設定できます：

```bash
export REDIS_HOST=localhost
export REDIS_PORT=6379
```

または、Javaプログラム実行時に指定：

```bash
REDIS_HOST=localhost REDIS_PORT=6379 mvn exec:java -Dexec.mainClass="server.redis.RedisConnectionTest"
```

## クリーンアップ

テスト環境を完全にクリーンアップする場合：

```bash
# コンテナとボリュームを削除
docker-compose down -v

# Mavenのビルド成果物を削除
mvn clean
```

## 次のステップ

Phase 0が完了したら、次は **Phase 1: Redis読み取り専用状態同期** に進みます。

Phase 1では：
- UAV状態をRedisに書き込む機能を追加
- 既存のメモリベース処理は維持（二重書き込み）
- Redisデータの読み取りテストを実装

詳細は `docs/REFACTORING_PLAN.md` の「Phase 1」セクションを参照してください。

## 参考資料

- [Redisson公式ドキュメント](https://github.com/redisson/redisson/wiki/Table-of-Content)
- [Redis公式ドキュメント](https://redis.io/documentation)
- [Docker Compose公式ドキュメント](https://docs.docker.com/compose/)

## チェックリスト

Phase 0完了前に以下を確認してください：

- [ ] Mavenで依存関係が正常にインストールされた
- [ ] Redisコンテナが起動している（`docker-compose ps`）
- [ ] Redis接続テストがすべて成功した
- [ ] Redis Commanderにアクセスできる（http://localhost:8081）
- [ ] 既存のシミュレーターが正常に動作する

すべてチェックが完了したら、Phase 1に進む準備が整いました！
