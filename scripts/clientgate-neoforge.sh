#!/usr/bin/env bash
# Usage: scripts/clientgate-neoforge.sh
#
# The Phase E CLIENT acceptance gate (ARCHITECTURE-V2 §9), in one command.
#
# NeoForge has no fabric-client-gametest equivalent — its GameTest framework
# runs on a dedicated server and never starts a client, and ModDevGradle's JUnit
# support has no window — so the gate is a self-driving mod in its own source
# set (`neoforge/src/clientgate`, mod id `vibemodgate`, never shipped). It boots
# a real client with a real GL context, creates a real singleplayer world, runs
# the assertions from inside ClientTickEvent, writes a verdict file and halts.
#
# This script's whole job is to run it from a clean directory, put a wall-clock
# limit on it, and turn the verdict file into an exit code.
#
# IT NEEDS A DISPLAY. There is no headless mode: the point of the gate is that a
# HUD renderer really renders. On CI this needs xvfb (Linux) or a hosted macOS
# runner — see ARCHITECTURE-V2 §10.4's Phase F notes.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="$ROOT/neoforge/clientgate-run"
RESULTS="$RUN/vibemod-clientgate-results.txt"
LOG="$ROOT/neoforge/clientgate.log"
# Generous: a cold run compiles the game's assets, generates a world and
# compiles three mods in-process. The gate has its own 12-minute tick budget
# inside the game; this is the outer backstop for a client that never gets that
# far.
TIMEOUT_SECONDS=1200

note() { echo "== $*"; }

note "wiping $RUN"
rm -rf "$RUN"
mkdir -p "$RUN"

# A first launch into an empty game directory does NOT show the title screen: it
# shows the accessibility-onboarding flow, and a gate that waits for a specific
# screen sits there forever. The gate no longer waits for one, but seeding these
# gets it into the world sooner and keeps the run quiet.
cat > "$RUN/options.txt" <<'OPTS'
version:5000
onboardAccessibility:false
narrator:0
soundCategory_master:0.0
pauseOnLostFocus:false
enableVsync:false
maxFps:60
guiScale:2
fullscreen:false
skipMultiplayerWarning:true
tutorialStep:none
OPTS

note "running the client gate (log: $LOG)"
"$ROOT/gradlew" -p "$ROOT" :neoforge:runClientGate > "$LOG" 2>&1 &
GRADLE_PID=$!

cleanup() {
  if kill -0 "$GRADLE_PID" 2>/dev/null; then
    kill "$GRADLE_PID" 2>/dev/null || true
  fi
  # The client is a grandchild of gradle; killing the daemon's child is not
  # enough, so the run directory is used as the signature.
  pkill -f "clientgate-run" 2>/dev/null || true
}
trap cleanup EXIT

WAITED=0
while kill -0 "$GRADLE_PID" 2>/dev/null; do
  if [[ -f "$RESULTS" ]]; then
    # The gate halts the JVM right after writing this, so a brief grace period
    # lets gradle notice and exit on its own.
    sleep 5
    break
  fi
  if [[ "$WAITED" -ge "$TIMEOUT_SECONDS" ]]; then
    echo "!! the client gate did not finish within ${TIMEOUT_SECONDS}s; tail:" >&2
    tail -40 "$LOG" >&2
    exit 1
  fi
  sleep 2
  WAITED=$((WAITED + 2))
done

cleanup
trap - EXIT

if [[ ! -f "$RESULTS" ]]; then
  echo "!! the client gate produced no verdict; tail:" >&2
  tail -60 "$LOG" >&2
  exit 1
fi

echo
cat "$RESULTS"
echo

if grep -q '^PHASE E CLIENT GATE PASSED' "$RESULTS"; then
  echo "== PHASE E CLIENT GATE PASSED"
  echo "   verdict: $RESULTS"
  echo "   log: $LOG"
else
  echo "!! PHASE E CLIENT GATE FAILED (verdict: $RESULTS, log: $LOG)" >&2
  exit 1
fi
