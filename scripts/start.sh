#!/usr/bin/env bash
# Starts the local Paper dev server in the background and waits for it to
# finish booting (log line + RCON port up) before returning.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$ROOT/server"
PAPER_JAR="paper-1.21.8-60.jar"
PID_FILE="$SERVER_DIR/.pid"
CONSOLE_LOG="$SERVER_DIR/console.log"
LATEST_LOG="$SERVER_DIR/logs/latest.log"

if [[ ! -f "$SERVER_DIR/$PAPER_JAR" ]]; then
  echo "!! $SERVER_DIR/$PAPER_JAR not found. Run scripts/setup.sh first." >&2
  exit 1
fi

if [[ -f "$PID_FILE" ]]; then
  EXISTING_PID="$(cat "$PID_FILE" 2>/dev/null || true)"
  if [[ -n "$EXISTING_PID" ]] && kill -0 "$EXISTING_PID" 2>/dev/null; then
    echo "==> Server already running (pid $EXISTING_PID)"
    exit 0
  fi
  rm -f "$PID_FILE"
fi

echo "==> Starting Paper server in $SERVER_DIR"
: > "$CONSOLE_LOG"

(
  cd "$SERVER_DIR"
  exec nohup java -Xms1G -Xmx2G -XX:+UseG1GC -XX:+AlwaysPreTouch \
    -jar "$PAPER_JAR" --nogui >>"$CONSOLE_LOG" 2>&1
) &
SERVER_PID=$!
disown "$SERVER_PID" 2>/dev/null || true
echo "$SERVER_PID" > "$PID_FILE"
echo "==> Launched pid $SERVER_PID, logging to $CONSOLE_LOG"

echo "==> Waiting for server to finish booting (up to 120s)..."
DEADLINE=$((SECONDS + 120))
DONE_SEEN=false
RCON_UP=false

while (( SECONDS < DEADLINE )); do
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "!! Server process exited early. Log tail:" >&2
    tail -n 60 "$CONSOLE_LOG" 2>/dev/null >&2 || true
    rm -f "$PID_FILE"
    exit 1
  fi

  if ! $DONE_SEEN; then
    if grep -Eq 'Done \(.*\)! For help' "$LATEST_LOG" 2>/dev/null \
       || grep -Eq 'Done \(.*\)! For help' "$CONSOLE_LOG" 2>/dev/null; then
      DONE_SEEN=true
    fi
  fi

  if ! $RCON_UP; then
    if nc -z 127.0.0.1 25575 2>/dev/null; then
      RCON_UP=true
    fi
  fi

  if $DONE_SEEN && $RCON_UP; then
    echo "==> server up (pid $SERVER_PID)"
    exit 0
  fi

  sleep 1
done

echo "!! Timed out waiting for server to come up (done=$DONE_SEEN rcon=$RCON_UP). Log tail:" >&2
tail -n 60 "$LATEST_LOG" 2>/dev/null >&2 || tail -n 60 "$CONSOLE_LOG" 2>/dev/null >&2 || true
exit 1
