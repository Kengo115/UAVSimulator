#!/bin/bash
# UAVSimulator クラス図生成スクリプト
# Usage: ./generate_diagram.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLANTUML_JAR=/tmp/plantuml.jar
GRAPHVIZ_DOT=/tmp/gv_extract/usr/bin/dot_builtins
LIB_PATH=/tmp/gv_extract/usr/lib/x86_64-linux-gnu:/tmp/gv_extract/usr/lib/x86_64-linux-gnu/graphviz

PUML_FILES=(
  "$SCRIPT_DIR/diagram.puml"
  "$SCRIPT_DIR/diagram_package.puml"
  "$SCRIPT_DIR/diagram_shared.puml"
  "$SCRIPT_DIR/diagram_operator.puml"
  "$SCRIPT_DIR/diagram_network_manager.puml"
)

if [ ! -f "$PLANTUML_JAR" ]; then
  echo "Downloading PlantUML..."
  curl -L -o "$PLANTUML_JAR" https://github.com/plantuml/plantuml/releases/download/v1.2024.8/plantuml-1.2024.8.jar
fi

run_plantuml() {
  local puml_file="$1"
  if command -v dot &> /dev/null; then
    java -DPLANTUML_LIMIT_SIZE=32768 -jar "$PLANTUML_JAR" -tpng -tsvg \
      "$puml_file" -o "$SCRIPT_DIR/config/" 2>/dev/null
  else
    LD_LIBRARY_PATH=$LIB_PATH java -DPLANTUML_LIMIT_SIZE=32768 -jar "$PLANTUML_JAR" \
      -graphvizdot "$GRAPHVIZ_DOT" -tpng -tsvg \
      "$puml_file" -o "$SCRIPT_DIR/config/" 2>/dev/null
  fi
}

if command -v dot &> /dev/null; then
  echo "System graphviz found: $(dot -version 2>&1 | head -1)"
else
  echo "Using extracted graphviz..."
fi

for puml in "${PUML_FILES[@]}"; do
  name="$(basename "$puml" .puml)"
  echo -n "  Generating $name ... "
  run_plantuml "$puml"
  echo "done"
done

echo ""
echo "Generated:"
ls -lh "$SCRIPT_DIR/config/"*.{png,svg} 2>/dev/null
