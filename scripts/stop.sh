#!/usr/bin/env bash
#
# PAPER-ONLY DEV HELPER (see the header of scripts/setup.sh).
#
# Stops the local dev server gracefully via RCON (falling back to killing the
# tracked PID), then waits for the process to actually exit.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$ROOT/server"
PID_FILE="$SERVER_DIR/.pid"

if [[ ! -f "$PID_FILE" ]]; then
  echo "==> No $PID_FILE found; nothing to stop."
  exit 0
fi

PID="$(cat "$PID_FILE" 2>/dev/null || true)"

if [[ -z "$PID" ]] || ! kill -0 "$PID" 2>/dev/null; then
  echo "==> Tracked pid ($PID) is not running; removing stale pid file."
  rm -f "$PID_FILE"
  exit 0
fi

if nc -z 127.0.0.1 25575 2>/dev/null; then
  echo "==> RCON reachable; sending 'stop'"
  "$ROOT/scripts/rcon.sh" "stop" || true
else
  echo "==> RCON not reachable; killing pid $PID"
  kill "$PID" 2>/dev/null || true
fi

echo "==> Waiting for pid $PID to exit (up to 60s)..."
DEADLINE=$((SECONDS + 60))
while kill -0 "$PID" 2>/dev/null; do
  if (( SECONDS >= DEADLINE )); then
    echo "!! Server did not exit in time; sending SIGKILL to $PID" >&2
    kill -9 "$PID" 2>/dev/null || true
    break
  fi
  sleep 1
done

rm -f "$PID_FILE"
echo "==> Stopped."
