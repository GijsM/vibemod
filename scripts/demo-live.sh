#!/usr/bin/env bash
# Usage: scripts/demo-live.sh [prompt-number ...]      (default: 1 2)
#
# The V3 Phase 4 end-to-end demo. Unlike scripts/smoke-*.sh this is NOT a gate:
# it spends real money, it depends on a model's judgement, and a model having a
# bad day is not a regression in this repo. It is the honest evidence that the
# whole pipeline — prompt, LLM, parse, compile, surgeon, hot-load, seams,
# resources, teardown — works on a request nobody wrote a fixture for.
#
# It boots the same dedicated Fabric server the Phase D gate boots, from the
# same installed jar and the same download cache, and drives `/vibe make` over
# RCON with the DEMO.md prompts. For each generated mod it asserts:
#
#     generated -> (self-healed) -> live -> exercised -> deleted -> no residue
#
# The API key is resolved in the host's own order: $OPENROUTER_API_KEY, then
# ~/.config/vibemod/openrouter.key. It is exported into the server's environment
# and never written into the run directory, so a demo run leaves no key on disk.
#
# Everything lives under fabric/demo/, which is git-ignored runtime state, and is
# deleted and recreated on every run.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="$ROOT/fabric/demo"
CACHE="$ROOT/fabric/smoke-cache"
RCON_PORT=25588
RCON_PASSWORD="vibemod-demo"
BOOT_TIMEOUT=420
GEN_TIMEOUT=900

WANTED=("$@")
if [[ ${#WANTED[@]} -eq 0 ]]; then
  WANTED=(1 2)
fi

prop() { grep -E "^$1=" "$ROOT/gradle.properties" | cut -d= -f2- ; }
MC_VERSION="$(prop minecraftVersion)"
LOADER_VERSION="$(prop fabricLoaderVersion)"
FABRIC_API_VERSION="$(prop fabricApiVersion)"

JAR="$(ls -t "$ROOT"/fabric/build/libs/vibemod-fabric-*.jar 2>/dev/null | head -1 || true)"
if [[ -z "$JAR" ]]; then
  echo "!! no vibemod-fabric jar - run ./gradlew :fabric:build first" >&2
  exit 1
fi

# ------------------------------------------------------------------- the key
# The host's own order, minus the config file: this harness deliberately does
# NOT write a key into the run directory's config.
KEY="${OPENROUTER_API_KEY:-}"
if [[ -z "$KEY" && -r "$HOME/.config/vibemod/openrouter.key" ]]; then
  KEY="$(tr -d '[:space:]' < "$HOME/.config/vibemod/openrouter.key")"
fi
if [[ -z "$KEY" ]]; then
  echo "!! no OpenRouter API key (\$OPENROUTER_API_KEY or ~/.config/vibemod/openrouter.key)" >&2
  echo "   This demo talks to a real model; there is nothing to fall back to." >&2
  exit 2
fi
export OPENROUTER_API_KEY="$KEY"

FAILURES=0
note() { echo "== $*"; }
assert() {
  local what="$1"; shift
  if "$@"; then
    echo "  ok: $what"
  else
    echo "  FAIL: $what" >&2
    FAILURES=$((FAILURES + 1))
  fi
}
in_file() { grep -qF -- "$2" "$1"; }
not_in_file() { ! grep -qF -- "$2" "$1"; }
dir_exists() { [[ -d "$1" ]]; }
dir_absent() { [[ ! -d "$1" ]]; }

rcon() { "$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "$@"; }

# ------------------------------------------------------------------ downloads
mkdir -p "$CACHE"
SERVER_JAR="$CACHE/fabric-server-$MC_VERSION-$LOADER_VERSION.jar"
if [[ ! -f "$SERVER_JAR" ]]; then
  note "downloading the Fabric server launcher ($MC_VERSION / loader $LOADER_VERSION)"
  INSTALLER="$(curl -fsSL https://meta.fabricmc.net/v2/versions/installer \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["version"])')"
  curl -fsSL \
    "https://meta.fabricmc.net/v2/versions/loader/$MC_VERSION/$LOADER_VERSION/$INSTALLER/server/jar" \
    -o "$SERVER_JAR.part"
  mv "$SERVER_JAR.part" "$SERVER_JAR"
fi
API_JAR="$CACHE/fabric-api-$FABRIC_API_VERSION.jar"
if [[ ! -f "$API_JAR" ]]; then
  note "downloading fabric-api $FABRIC_API_VERSION"
  curl -fsSL \
    "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$FABRIC_API_VERSION/fabric-api-$FABRIC_API_VERSION.jar" \
    -o "$API_JAR.part"
  mv "$API_JAR.part" "$API_JAR"
fi

# ------------------------------------------------------------------- run dir
rm -rf "$RUN"
mkdir -p "$RUN/mods" "$RUN/config"
cp "$JAR" "$RUN/mods/"
cp "$API_JAR" "$RUN/mods/"
echo "eula=true" > "$RUN/eula.txt"

cat > "$RUN/server.properties" <<PROPS
online-mode=false
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASSWORD
level-type=minecraft\:flat
level-seed=1
spawn-protection=0
pause-when-empty-seconds=0
max-players=5
view-distance=4
simulation-distance=4
sync-chunk-writes=false
motd=VibeMod V3 live demo ($MC_VERSION)
PROPS

# api-key deliberately left blank: the host must find the key in the ENVIRONMENT,
# which is the second step of its documented resolution order and the one a
# server operator is most likely to use.
cat > "$RUN/config/vibemod.json" <<'CONF'
{
  "openrouter.api-key": "",
  "openrouter.model": "anthropic/claude-sonnet-5",
  "openrouter.timeout-seconds": 300,
  "openrouter.streaming": true,
  "openrouter.max-tokens": 0,
  "openrouter.reasoning-effort": "off",
  "generation.max-retries": 3,
  "generation.concurrency": 4,
  "watchdog.enabled": true,
  "watchdog.single-invocation-ms": 250,
  "watchdog.per-second-budget-ms": 500,
  "commands.allow-top-level": true,
  "errors.storm-threshold": 10,
  "errors.storm-window-seconds": 60,
  "errors.max-distinct": 25,
  "errors.stack-frames": 10,
  "debug.default-echo": false,
  "ui.force-chat": false
}
CONF

# ------------------------------------------------------------------ boot
LOG="$RUN/boot.log"
note "booting Fabric $MC_VERSION (log: $LOG)"
cd "$RUN"
java -Xms1G -Xmx2G -jar "$SERVER_JAR" nogui > "$LOG" 2>&1 < /dev/null &
SERVER_PID=$!

cleanup() {
  if kill -0 "$SERVER_PID" 2>/dev/null; then
    note "stopping server (pid $SERVER_PID)"
    rcon stop > /dev/null 2>&1 || kill "$SERVER_PID"
    for _ in $(seq 1 60); do
      kill -0 "$SERVER_PID" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$SERVER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

for i in $(seq 1 "$BOOT_TIMEOUT"); do
  grep -q 'Done (' "$LOG" 2>/dev/null && { note "booted after ${i}s"; break; }
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "!! server died during boot; tail:" >&2
    tail -60 "$LOG" >&2
    exit 1
  fi
  sleep 1
done
grep -q 'Done (' "$LOG" || { echo "!! server never booted; tail:" >&2; tail -60 "$LOG" >&2; exit 1; }

note "asserting the host found a key without one on disk"
assert "the host did not warn about a missing API key" \
  not_in_file "$LOG" 'No OpenRouter API key found'
assert "the run directory holds no key" in_file "$RUN/config/vibemod.json" '"openrouter.api-key": ""'

# --------------------------------------------------------------- the prompts
# The DEMO.md prompts, verbatim. Nothing here names a class, an API or a file.
p1() { echo "a ruby sword with a custom texture, crafted from rubies you get by smelting redstone"; }
p2() { echo "a /home command with a 30 second cooldown and a HUD timer showing the cooldown"; }

MODS_DIR="$RUN/vibemod/mods"

# Runs one /vibe make and waits for the pipeline to reach a verdict. Echoes the
# generated mod's name on stdout, or nothing if the run never produced one.
generate() {
  local prompt="$1" marker="$2"
  local before after
  before="$(ls "$MODS_DIR" 2>/dev/null | sort | tr '\n' ' ')"
  echo "$marker BEGIN $(date -u +%FT%TZ)" >> "$LOG"
  rcon "vibe make $prompt" > "$RUN/$marker-make.log" 2>&1 || true
  local i
  for i in $(seq 1 "$GEN_TIMEOUT"); do
    after="$(ls "$MODS_DIR" 2>/dev/null | sort | tr '\n' ' ')"
    if [[ "$after" != "$before" ]]; then
      # The store directory appears at the FIRST save, which may be a round
      # that then failed to load and is being repaired. The verdict is
      # ModGenerator's own "Generated <name> v<n>" line, which is written once,
      # when the pipeline is finished with this run.
      local name
      for name in $after; do
        case " $before " in *" $name "*) continue ;; esac
        if grep -q "Generated $name v[0-9]" "$LOG" 2>/dev/null; then
          echo "$name"
          return 0
        fi
      done
    fi
    grep -q 'Generation failed\|Compile failed after\|Mod failed to start\|unusable project' \
      "$LOG" 2>/dev/null && break
    sleep 1
  done
  # Last chance: the store moved but the log line is late.
  after="$(ls "$MODS_DIR" 2>/dev/null | sort | tr '\n' ' ')"
  local name
  for name in $after; do
    case " $before " in *" $name "*) continue ;; esac
    echo "$name"
    return 0
  done
  return 1
}

# Waits for a mod's datapack to become LIVE, not merely written.
#
# Materializing a pack only marks the coordinator dirty; the reload that makes
# the recipe craftable is debounced by 40 ticks. The first version of this
# script asserted immediately after "Generated <name>", deleted the mod one
# second later, and the coordinator quite correctly coalesced the load and the
# unload into a single reload that reported the mod as UNLOADED. Nothing was
# broken except the harness, which was faster than any player could be.
await_pack_live() {
  local ns="$1" i
  for i in $(seq 1 60); do
    if rcon "datapack list enabled" 2>/dev/null | grep -q "vibemod-$ns"; then
      note "its datapack went live after ${i}s"
      return 0
    fi
    sleep 1
  done
  return 1
}

# Everything the mod left behind, asserted absent.
assert_no_residue() {
  local name="$1" ns="$2"
  assert "the store no longer holds $name" dir_absent "$MODS_DIR/$name"
  assert "its world datapack directory is gone" \
    dir_absent "$RUN/world/datapacks/vibemod-$(echo "$name" | tr '[:upper:]' '[:lower:]')"
  local dl="$RUN/${name}-after-delete.log"
  rcon "datapack list enabled" "vibe list" > "$dl" 2>&1 || true
  assert "the world no longer has its datapack selected" not_in_file "$dl" "vibemod-$ns"
  assert "/vibe list no longer knows it" not_in_file "$dl" "$name"
}

ROUNDS_NOTE="$RUN/rounds.txt"
: > "$ROUNDS_NOTE"

# --------------------------------------------------------------- demo (1)
run_demo_1() {
  note "DEMO PROMPT 1 — a ruby sword with a custom texture, crafted from smelted redstone"
  local name
  name="$(generate "$(p1)" demo1)" || {
    echo "  FAIL: demo 1 never produced a mod" >&2
    FAILURES=$((FAILURES + 1))
    return
  }
  note "demo 1 produced: $name"
  local ns
  ns="$(echo "$name" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9]/_/g')"
  echo "demo1 $name" >> "$ROUNDS_NOTE"

  assert "the pipeline reported a finished generation" bash -c "grep -q 'Generated $name v[0-9]' '$LOG'"
  assert "its sources reached the store" dir_exists "$MODS_DIR/$name"
  assert "it is a NATIVE mod (no VibeMod import anywhere in its sources)" \
    bash -c "! grep -rqF 'com.gijsm.vibemod' '$MODS_DIR/$name'"
  assert "it shipped resource files, not just Java" \
    bash -c "find '$MODS_DIR/$name' -path '*/data/*' -o -path '*/assets/*' | grep -q ."
  assert "the datapack was materialized into the world" \
    dir_exists "$RUN/world/datapacks/vibemod-$ns"
  assert "its datapack became LIVE, not merely written" await_pack_live "$ns"
  assert "the coordinator ran a reload naming it as loaded" \
    bash -c "grep -q 'Reloading server data ($name loaded)' '$LOG'"

  local ex="$RUN/demo1-exercise.log"
  rcon "vibe list" "vibe info $name" "vibe manual $name" "vibe errors $name" \
       "datapack list enabled" > "$ex" 2>&1 || true
  assert "/vibe list shows it enabled" in_file "$ex" "$name"
  assert "the world enabled its datapack (no operator ever touched it)" \
    in_file "$ex" "vibemod-$ns"
  # V3 Phase 4 §D: /vibe info must describe a NATIVE mod, not a VibeContext one
  # wearing zeroes.
  assert "/vibe info names the loader entrypoints it implements" \
    in_file "$ex" 'entrypoints: ModInitializer'
  assert "/vibe info counts loader-event subscriptions rather than curated listeners" \
    bash -c "grep -q 'event subscriptions:' '$ex' && ! grep -q 'listeners: 0  tasks: 0' '$ex'"
  assert "/vibe info reports the resource tree the mod installed" \
    in_file "$ex" 'resource trees:'
  assert "nothing it registered was journalled as an error" \
    not_in_file "$ex" 'UnsupportedOperationException'

  # The recipe is the claim that matters, and RCON cannot ask the recipe manager
  # directly (see smoke-fabric.sh). What it CAN ask is the datapack's own files.
  assert "it wrote at least one recipe into its datapack" \
    bash -c "find '$RUN/world/datapacks/vibemod-$ns/data' -path '*recipe*' -name '*.json' | grep -q ."

  note "deleting demo 1 and asserting zero residue"
  rcon "vibe delete $name confirm" > "$RUN/demo1-delete.log" 2>&1 || true
  sleep 12
  assert_no_residue "$name" "$ns"
  assert "the teardown reload finished" in_file "$LOG" 'Server data reloaded in'
  assert "no missing-data-pack warning was produced" not_in_file "$LOG" 'Missing data pack'
}

# --------------------------------------------------------------- demo (2)
run_demo_2() {
  note "DEMO PROMPT 2 — a /home command with a 30 second cooldown and a HUD timer"
  local name
  name="$(generate "$(p2)" demo2)" || {
    echo "  FAIL: demo 2 never produced a mod" >&2
    FAILURES=$((FAILURES + 1))
    return
  }
  note "demo 2 produced: $name"
  local ns
  ns="$(echo "$name" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9]/_/g')"
  echo "demo2 $name" >> "$ROUNDS_NOTE"

  assert "the pipeline reported a finished generation" bash -c "grep -q 'Generated $name v[0-9]' '$LOG'"
  assert "it is a NATIVE mod (no VibeMod import anywhere in its sources)" \
    bash -c "! grep -rqF 'com.gijsm.vibemod' '$MODS_DIR/$name'"
  assert "its ModInitializer entrypoint ran without a refusal" \
    not_in_file "$LOG" 'UnsupportedOperationException: Mod '"$name"
  assert "the host registered a command for it through the command seam" \
    bash -c "grep -q \"Mod $name registered /\" '$LOG'"
  assert "its client half was skipped and SAID so on this dedicated server" \
    bash -c "! grep -rqF 'ClientModInitializer' '$MODS_DIR/$name' \
             || grep -q 'has a ClientModInitializer half; skipping it on a dedicated server' '$LOG'"

  # The command the seam recorded, asked of the live dispatcher.
  local cmd
  cmd="$(grep -o "Mod $name registered /[a-z0-9_-]*" "$LOG" | head -1 | sed 's/.*registered \///')"
  note "demo 2 registered /$cmd"
  local ex="$RUN/demo2-exercise.log"
  rcon "$cmd" "vibe info $name" "vibe errors $name" > "$ex" 2>&1 || true
  assert "its command is in the live dispatcher (not 'Unknown or incomplete')" \
    bash -c "! grep -q 'Unknown or incomplete command' <(head -3 '$ex')"
  # V3 Phase 4 §D: the command seam now names its commands on the card.
  assert "/vibe info names the loader entrypoints it implements" \
    in_file "$ex" 'entrypoints: ModInitializer'
  assert "/vibe info lists the command the seam installed for it" \
    in_file "$ex" "commands: $cmd"

  local dis="$RUN/demo2-disable.log"
  rcon "vibe disable $name" "$cmd" > "$dis" 2>&1 || true
  assert "disabling removed the command it registered" in_file "$dis" 'Unknown or incomplete command'
  rcon "vibe enable $name" > "$RUN/demo2-enable.log" 2>&1 || true
  sleep 3
  local re="$RUN/demo2-reenable.log"
  rcon "$cmd" > "$re" 2>&1 || true
  assert "re-enabling put the command back" \
    bash -c "! grep -q 'Unknown or incomplete command' '$re'"

  note "deleting demo 2 and asserting zero residue"
  rcon "vibe delete $name confirm" > "$RUN/demo2-delete.log" 2>&1 || true
  sleep 12
  assert_no_residue "$name" "$ns"
  local gone="$RUN/demo2-gone.log"
  rcon "$cmd" > "$gone" 2>&1 || true
  assert "its command is gone from the dispatcher for good" \
    in_file "$gone" 'Unknown or incomplete command'
}

for which in "${WANTED[@]}"; do
  case "$which" in
    1) run_demo_1 ;;
    2) run_demo_2 ;;
    *) echo "!! unknown demo prompt: $which" >&2; exit 2 ;;
  esac
done

note "generation rounds, from the host's own log"
grep -E 'Mod compile round failed|Unusable model response|LLM round failed|self-healed|Refusing registry' "$LOG" \
  | tail -40 || true

note "cost, from /vibe costs"
rcon "vibe costs" || true

echo
if [[ "$FAILURES" -eq 0 ]]; then
  note "V3 PHASE 4 LIVE DEMO PASSED"
else
  note "V3 PHASE 4 LIVE DEMO: $FAILURES FAILURE(S)"
  exit 1
fi
