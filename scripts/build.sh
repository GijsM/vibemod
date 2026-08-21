#!/usr/bin/env bash
# Builds the VibeCore plugin jar with Maven and drops it into server/plugins/.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLUGIN_POM="$ROOT/plugin/pom.xml"
BUILT_JAR="$ROOT/plugin/target/VibeCore.jar"
PLUGINS_DIR="$ROOT/server/plugins"

echo "==> Building VibeCore ($PLUGIN_POM)"
mvn -q -f "$PLUGIN_POM" package

if [[ ! -f "$BUILT_JAR" ]]; then
  echo "!! Build reported success but $BUILT_JAR is missing" >&2
  exit 1
fi

mkdir -p "$PLUGINS_DIR"
cp "$BUILT_JAR" "$PLUGINS_DIR/VibeCore.jar"

echo "==> Built and installed: $PLUGINS_DIR/VibeCore.jar"
