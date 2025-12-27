#!/bin/bash
# UAVシミュレータ実行スクリプト

echo "UAVシミュレータを起動します..."
mvn exec:java -Dexec.mainClass="controller.BoundaryController"
