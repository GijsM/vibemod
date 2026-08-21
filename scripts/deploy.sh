#!/usr/bin/env bash
# Build + hot-deploy VibeMod without a server restart (Bukkit reload).
# Note: plugin reload is officially discouraged (leaks a classloader per
# reload); fine for this dev server, do a real restart occasionally.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$ROOT/scripts/build.sh"
echo "==> Reloading plugins in-place (no restart)"
"$ROOT/scripts/rcon.sh" 'say Hot-reloading VibeMod — one second of lag, no disconnect!'
"$ROOT/scripts/rcon.sh" 'bukkit:reload confirm'
sleep 3
"$ROOT/scripts/rcon.sh" 'vibe list' | head -3
