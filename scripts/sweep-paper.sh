#!/usr/bin/env bash
# Usage: scripts/sweep-paper.sh <mc-version> [<mc-version> ...]
#
# Runs scripts/smoke-paper.sh once per version and turns each run into ONE
# verdict line, so a whole compatibility range can be answered in one command.
#
# The gate it wraps proves the server booted and the canned mod went live, and
# it PRINTS the RCON replies - but smoke-rcon.py has no assertions, so a gate
# can go green while every reply is wrong. This script adds that half: it reads
# the transcript back and requires the replies to say the right things, and it
# records which UI the plugin picked so a version's answer to "dialogs or chat
# fallback?" is data rather than a claim.
#
# JAVA_HOME is honoured, so the same range can be re-run on another JDK to tell
# "the plugin broke here" apart from "this Paper line will not run on that JDK".
#
# Results land in paper/run/sweep-results.tsv (append-only across runs).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESULTS="${SWEEP_RESULTS:-$ROOT/paper/run/sweep-results.tsv}"
mkdir -p "$ROOT/paper/run"

if [[ ! -f "$RESULTS" ]]; then
  printf 'version\tverdict\tui\tprofile\tdialogs\tjava\tfailures\n' > "$RESULTS"
fi

JAVA_LABEL="$("${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 | head -1 | sed 's/.*version "\([^"]*\)".*/\1/')"

# A version is only PASS if the gate exited 0 AND every one of these holds.
# Each is "<label>=<literal that must appear in the RCON transcript>".
ASSERTS=(
  'pong-default=smoke-pong hi'
  'action-knob=smoke-action 7'
  'knob-write=SmokeCanary.greeting: hi -> howdy'
  'pong-updated=smoke-pong howdy'
  'manual-renders=SmokeCanary - manual'
  'source-renders=vibemod.smokecanary.SmokeCanary'
  'disable-works=SmokeCanary disabled'
  'enable-works=SmokeCanary enabled'
)

overall=0

# Each argument is either a plain Minecraft version, which the gate downloads
# from Fill as Paper, or `<label>=<mc-version>=<jar>` for a server jar already on
# disk - which is how a Paper FORK gets tested by the same canary protocol. The
# mc-version half still has to be the real Minecraft version: it is what the
# mineflayer bot needs to speak, and a fork does not change the protocol.
for SPEC in "$@"; do
  case "$SPEC" in
    *=*=*)
      LABEL="${SPEC%%=*}"
      REST="${SPEC#*=}"
      VERSION="${REST%%=*}"
      SERVER_JAR="${REST#*=}"
      ;;
    *)
      LABEL="$SPEC"
      VERSION="$SPEC"
      SERVER_JAR=""
      ;;
  esac

  echo ""
  echo "########################################################"
  echo "## sweep: $LABEL (mc $VERSION)   (java $JAVA_LABEL)"
  echo "########################################################"
  RUN="$ROOT/paper/run/smoke-$LABEL"
  GATELOG="$ROOT/paper/run/sweep-$LABEL.gate.log"

  SMOKE_LABEL="$LABEL" SMOKE_SERVER_JAR="$SERVER_JAR" \
    "$ROOT/scripts/smoke-paper.sh" "$VERSION" > "$GATELOG" 2>&1
  gate_rc=$?

  failures=()
  [[ $gate_rc -ne 0 ]] && failures+=("gate-exit-$gate_rc")

  # --- the assertions the gate itself does not make ------------------------
  RCONLOG="$RUN/rcon.log"
  if [[ -f "$RCONLOG" ]]; then
    for a in "${ASSERTS[@]}"; do
      label="${a%%=*}"
      needle="${a#*=}"
      grep -qF "$needle" "$RCONLOG" || failures+=("$label")
    done
    # After `vibe disable`, /smokeping must stop answering. Counting is the only
    # honest check: "smoke-pong" appears twice before the disable. Anchored to
    # the line start on purpose - `vibe source` dumps the mod's own Java, which
    # contains the string `smoke-pong` in a sendMessage call, and an unanchored
    # count reads that third occurrence as a mod that ignored its disable.
    pongs=$(grep -c '^smoke-pong' "$RCONLOG" 2>/dev/null || echo 0)
    [[ "$pongs" -eq 2 ]] || failures+=("disable-still-answers-${pongs}pongs")
  else
    failures+=("no-rcon-transcript")
  fi

  # --- what the plugin said about itself -----------------------------------
  ui="-"; profile="-"; dialogs="-"
  BOOTLOG="$RUN/boot.log"
  if [[ -f "$BOOTLOG" ]]; then
    ui="$(sed -n 's/.*\[VibeMod\] UI: \(.*\)$/\1/p' "$BOOTLOG" | head -1 | tr -d '\r')"
    profile="$(sed -n 's/.*profile=\([^ ]*\).*/\1/p' "$BOOTLOG" | head -1)"
    dialogs="$(sed -n 's/.*dialogs=\([^ ]*\).*/\1/p' "$BOOTLOG" | head -1)"
    if [[ -z "$ui" ]]; then ui="-"; failures+=("no-ui-line"); fi
    grep -qF '[VibeMod] VibeMod ready' "$BOOTLOG" || failures+=("never-became-ready")
    # A stack trace attributed to VibeMod is a failure even if the gate passed.
    if grep -qE '\[VibeMod[^]]*\].*(Exception|Error)' "$BOOTLOG"; then
      failures+=("vibemod-exception")
    fi
  else
    failures+=("no-boot-log")
  fi

  if [[ ${#failures[@]} -eq 0 ]]; then
    verdict=PASS
  else
    verdict=FAIL
    overall=1
  fi
  joined="$(IFS=,; echo "${failures[*]:-}")"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$LABEL" "$verdict" "${ui:--}" "${profile:--}" "${dialogs:--}" "$JAVA_LABEL" "${joined:--}" \
    | tee -a "$RESULTS"
done

echo ""
echo "== sweep complete; results table:"
column -t -s "$(printf '\t')" "$RESULTS"
exit $overall
