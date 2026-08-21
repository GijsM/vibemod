#!/usr/bin/env bash
# Builds the VibeMod plugin jar with Maven and drops it into server/plugins/.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLUGIN_POM="$ROOT/plugin/pom.xml"
BUILT_JAR="$ROOT/plugin/target/VibeMod.jar"
PLUGINS_DIR="$ROOT/server/plugins"

echo "==> Building VibeMod ($PLUGIN_POM)"
mvn -q -f "$PLUGIN_POM" package

if [[ ! -f "$BUILT_JAR" ]]; then
  echo "!! Build reported success but $BUILT_JAR is missing" >&2
  exit 1
fi

mkdir -p "$PLUGINS_DIR"
# Remove a stale pre-rename jar so both plugins never load side by side.
rm -f "$PLUGINS_DIR/VibeCore.jar"
cp "$BUILT_JAR" "$PLUGINS_DIR/VibeMod.jar"

echo "==> Built and installed: $PLUGINS_DIR/VibeMod.jar"
