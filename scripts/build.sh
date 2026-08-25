#!/usr/bin/env bash
# Builds the VibeMod plugin jar with Gradle and drops it into server/plugins/.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILT_JAR="$ROOT/paper/build/libs/VibeMod.jar"
PLUGINS_DIR="$ROOT/server/plugins"

echo "==> Building VibeMod (:paper:jar)"
"$ROOT/gradlew" -p "$ROOT" -q :paper:jar

if [[ ! -f "$BUILT_JAR" ]]; then
  echo "!! Build reported success but $BUILT_JAR is missing" >&2
  exit 1
fi

mkdir -p "$PLUGINS_DIR"
cp "$BUILT_JAR" "$PLUGINS_DIR/VibeMod.jar"

echo "==> Built and installed: $PLUGINS_DIR/VibeMod.jar"
