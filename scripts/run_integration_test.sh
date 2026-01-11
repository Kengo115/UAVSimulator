#!/bin/bash
#
# Phase 7-10: 統合テスト実行スクリプト
#
# 使用方法:
#   ./scripts/run_integration_test.sh [test|full]
#
#   test: 3分間のテスト実行（デフォルト）
#   full: 60分間のフル実行
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

# 引数チェック
MODE="${1:-test}"

echo "=== Phase 7-10: 統合テスト ==="
echo "モード: $MODE"
echo ""

# ビルド
echo "1. ビルド中..."
mvn compile -q
echo "   ✓ ビルド完了"

# 結果ディレクトリをクリア
echo ""
echo "2. 結果ディレクトリをクリア中..."
rm -rf src/result/large_scale/Bisectional/snapshot/
rm -rf src/result/large_scale/Bisectional/link_status/
rm -rf src/result/large_scale/Bisectional/client/
rm -f src/log/simulator.log
echo "   ✓ クリア完了"

# 設定ファイルを表示
if [ "$MODE" = "full" ]; then
    CONFIG_FILE="config/simulation_params.json"
else
    CONFIG_FILE="config/simulation_params_test.json"
fi

echo ""
echo "3. 使用する設定ファイル: $CONFIG_FILE"
echo "---"
cat "$CONFIG_FILE"
echo "---"
echo ""

# 実行方法を案内
echo "4. シミュレーション実行"
echo ""
echo "以下のコマンドで実行してください:"
echo ""
echo "  mvn exec:java -Dexec.mainClass=controller.BoundaryController -q"
echo ""
echo "CUIでの選択:"
echo "  1) ネットワーク規模: 2 (大規模)"
echo "  2) トポロジファイル: [Enter]でデフォルト (koriyama_topology.txt)"
echo "  3) トポロジ画像出力: 1 (出力しない)"
echo "  4) 経路探索手法: 6 (Bisectional)"
echo "  5) ログ記録: 2 (記録する)"
echo "  6) ワーカーモード: 1 (メモリベース)"
echo "  7) 設定ファイル: 3 (ファイルパスを指定)"
echo "     パス: $CONFIG_FILE"
echo "  8) クライアント生成モード: 3 (4フェーズ制御)"
echo ""
echo "=== テスト終了後の確認 ==="
echo ""
echo "# ログ確認"
echo "  tail -100 src/log/simulator.log"
echo ""
echo "# スナップショット確認"
echo "  ls -la src/result/large_scale/Bisectional/snapshot/"
echo ""
echo "# リンク状態確認"
echo "  ls -la src/result/large_scale/Bisectional/link_status/"
echo ""
echo "# クライアント情報確認"
echo "  cat src/result/large_scale/Bisectional/client/client.txt"
echo ""
echo "# ヒートマップ生成"
echo "  source .venv/bin/activate"
echo "  python3 scripts/plot_congestion_heatmap.py config/topology/koriyama_topology.txt \\"
echo "    src/result/large_scale/Bisectional/snapshot/ output/heatmaps/ --all"
echo ""
echo "# リンク別ファイル分割"
echo "  python3 scripts/split_link_status.py src/result/large_scale/Bisectional/link_status/link_status.csv"
echo ""
