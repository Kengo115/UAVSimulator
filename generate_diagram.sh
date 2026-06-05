#!/bin/bash
# UAVSimulator クラス図生成スクリプト
# Usage: ./generate_diagram.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLANTUML_JAR=/tmp/plantuml.jar
GRAPHVIZ_DOT=/tmp/gv_extract/usr/bin/dot_builtins
LIB_PATH=/tmp/gv_extract/usr/lib/x86_64-linux-gnu:/tmp/gv_extract/usr/lib/x86_64-linux-gnu/graphviz

if [ ! -f "$PLANTUML_JAR" ]; then
  echo "Downloading PlantUML..."
  curl -L -o "$PLANTUML_JAR" https://github.com/plantuml/plantuml/releases/download/v1.2024.8/plantuml-1.2024.8.jar
fi

# graphviz が system にインストール済みであればそちらを使用
if command -v dot &> /dev/null; then
  echo "System graphviz found: $(dot -version 2>&1 | head -1)"
  java -DPLANTUML_LIMIT_SIZE=32768 -jar "$PLANTUML_JAR" -tpng -tsvg \
    "$SCRIPT_DIR/diagram.puml" -o "$SCRIPT_DIR/"
else
  echo "Using extracted graphviz..."
  LD_LIBRARY_PATH=$LIB_PATH java -DPLANTUML_LIMIT_SIZE=32768 -jar "$PLANTUML_JAR" \
    -graphvizdot "$GRAPHVIZ_DOT" -tpng -tsvg \
    "$SCRIPT_DIR/diagram.puml" -o "$SCRIPT_DIR/"
fi

echo "Generated:"
ls -lh "$SCRIPT_DIR"/UAVSimulator_ClassDiagram.{png,svg} 2>/dev/null
