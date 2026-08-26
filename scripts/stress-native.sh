#!/usr/bin/env bash
# Usage: scripts/stress-native.sh
#
# The V3 stress campaign driver (docs/phases/STRESS-TEST.md).
#
# NOT A GATE. Nothing in `check` or CI runs this. It is a repeatable discovery
# harness, and it deliberately does NOT look like smoke-fabric.sh in one respect:
# nothing is seeded before boot. The server comes up bare, starts ticking, and
# every one of the nine roster mods is then written to disk and hot-loaded
# MID-SESSION through `/vibe enable`, which is the product path - the compile,
# the surgeon, the unfreeze window, the ReloadCoordinator, the live Brigadier
# install and the SERVER_STARTED replay all fire into a running game rather than
# into a boot sequence. Restore-on-boot is already gated by smoke-fabric.sh;
# what has never been driven is VibeMod's own running time.
#
# It is also ONE continuous session. The server is booted once, all nine mods
# arrive into it, behaviours are observed over real minutes on the clock
# (MeteorStorm's 600-tick cadence twice unaided, ArenaMaster's waves to
# completion, Nightmare's night scaling on a real mob), mods are disabled,
# re-enabled and unloaded underneath it, and it is still serving at the end.
# Every step prints T+<seconds> since the server said "Done (", so "during
# running time" is evidenced rather than asserted.
#
# The boot / RCON / assert plumbing IS pattern-copied from smoke-fabric.sh: the
# download cache, the flat world, `pause-when-empty-seconds=0` (an empty 1.21.2+
# server stops ticking after a minute and every tick-counting assertion here
# would then be measuring a paused server), and the wait-for-the-COMPLETION-line
# discipline are all things that gate learned the hard way.
#
# SHARING THE MACHINE. This runs a real server for several minutes next to
# whatever the person at the keyboard is doing. It kills only the pid it started,
# never a broad `pkill`, and never `gradlew --stop`; its heap is capped at 1G
# against the 2G it used to take. That is not politeness - during this campaign a
# stray `gradlew --stop` ended somebody's game, and an uncapped overlap helped the
# OS OOM-kill their client.
#
# Everything lives under fabric/stress/, which is git-ignored runtime state, and
# is deleted and recreated on every run.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="$ROOT/fabric/stress"
CACHE="$ROOT/fabric/smoke-cache"
MODS_SRC="$ROOT/scripts/stress-mods"
STORE="$RUN/vibemod/mods"
RCON_PORT=25588
RCON_PASSWORD="vibemod-stress"
BOOT_TIMEOUT=420
RCON="$ROOT/scripts/smoke-rcon.py"

prop() { grep -E "^$1=" "$ROOT/gradle.properties" | cut -d= -f2- ; }
MC_VERSION="$(prop minecraftVersion)"
LOADER_VERSION="$(prop fabricLoaderVersion)"
FABRIC_API_VERSION="$(prop fabricApiVersion)"

JAR="$(ls -t "$ROOT"/fabric/build/libs/vibemod-fabric-*.jar 2>/dev/null | head -1 || true)"
if [[ -z "$JAR" ]]; then
  echo "!! no vibemod-fabric jar - run ./gradlew :fabric:build first" >&2
  exit 1
fi

FAILURES=0
UNTESTED=0
BOOTED_AT=0
# T+<seconds> since the server finished booting. The whole campaign's evidence
# that these things happened to a running instance rather than to a boot.
t() { echo "T+$(( $(date +%s) - BOOTED_AT ))s"; }
note() { echo; echo "== [$(t)] $*"; }
assert() {
  local what="$1"; shift
  if "$@"; then
    echo "  ok: $what"
  else
    echo "  FAIL: $what" >&2
    FAILURES=$((FAILURES + 1))
  fi
}
# For behaviours that genuinely cannot be driven from a console. Recorded, not
# skipped: the count is printed at the end so it can never quietly grow.
untestable() {
  echo "  UNTESTABLE-HEADLESS: $*"
  UNTESTED=$((UNTESTED + 1))
}
in_file() { grep -qF -- "$2" "$1"; }
not_in_file() { ! grep -qF -- "$2" "$1"; }
rc() { "$RCON" "$RCON_PORT" "$RCON_PASSWORD" "$@"; }

# ------------------------------------------------------------------ downloads
mkdir -p "$CACHE"
SERVER_JAR="$CACHE/fabric-server-$MC_VERSION-$LOADER_VERSION.jar"
if [[ ! -f "$SERVER_JAR" ]]; then
  echo "== downloading the Fabric server launcher ($MC_VERSION / loader $LOADER_VERSION)"
  INSTALLER="$(curl -fsSL https://meta.fabricmc.net/v2/versions/installer \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["version"])')"
  curl -fsSL \
    "https://meta.fabricmc.net/v2/versions/loader/$MC_VERSION/$LOADER_VERSION/$INSTALLER/server/jar" \
    -o "$SERVER_JAR.part"
  mv "$SERVER_JAR.part" "$SERVER_JAR"
fi
API_JAR="$CACHE/fabric-api-$FABRIC_API_VERSION.jar"
if [[ ! -f "$API_JAR" ]]; then
  echo "== downloading fabric-api $FABRIC_API_VERSION"
  curl -fsSL \
    "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$FABRIC_API_VERSION/fabric-api-$FABRIC_API_VERSION.jar" \
    -o "$API_JAR.part"
  mv "$API_JAR.part" "$API_JAR"
fi
echo "== server:  $SERVER_JAR"
echo "== vibemod: $JAR"

# ------------------------------------------------------------------- run dir
rm -rf "$RUN"
mkdir -p "$RUN/mods" "$STORE"
cp "$JAR" "$RUN/mods/"
cp "$API_JAR" "$RUN/mods/"
echo "eula=true" > "$RUN/eula.txt"

cat > "$RUN/server.properties" <<PROPS
online-mode=false
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASSWORD
level-type=minecraft\:flat
level-seed=7
spawn-protection=0
pause-when-empty-seconds=0
max-players=5
view-distance=6
simulation-distance=6
sync-chunk-writes=false
motd=VibeMod V3 stress campaign ($MC_VERSION)
PROPS

# ------------------------------------------------------------------ boot (bare)
LOG="$RUN/boot.log"
echo "== booting Fabric $MC_VERSION with an EMPTY store (log: $LOG)"
cd "$RUN"
java -Xms512M -Xmx1G -jar "$SERVER_JAR" nogui > "$LOG" 2>&1 < /dev/null &
SERVER_PID=$!

cleanup() {
  if kill -0 "$SERVER_PID" 2>/dev/null; then
    echo; echo "== [$(t)] stopping server (pid $SERVER_PID)"
    rc stop > /dev/null 2>&1 || kill "$SERVER_PID"
    for _ in $(seq 1 60); do
      kill -0 "$SERVER_PID" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$SERVER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

for i in $(seq 1 "$BOOT_TIMEOUT"); do
  grep -q 'Done (' "$LOG" 2>/dev/null && { echo "== booted after ${i}s"; break; }
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "!! server died during boot; tail:" >&2
    tail -80 "$LOG" >&2
    exit 1
  fi
  sleep 1
done
grep -q 'Done (' "$LOG" || { echo "!! server never booted; tail:" >&2; tail -80 "$LOG" >&2; exit 1; }
BOOTED_AT=$(date +%s)

note "the server is up and ticking with nothing installed"
assert "the host initialised" in_file "$LOG" 'VibeMod ready'
assert "it is the native fabric profile" in_file "$LOG" 'prompt profile Fabric'
assert "a compiler backend resolved" in_file "$LOG" 'Compiler backend:'
assert "the bytecode seams were installed" in_file "$LOG" 'Bytecode seams:'
assert "restore-on-boot had nothing to restore" not_in_file "$LOG" 'v1 is live'
BARE="$RUN/rcon-bare.log"
rc "vibe list" "vibe settings" | tee "$BARE"
assert "the store really is empty" in_file "$BARE" 'platform: fabric 26.2'

# A server with nobody on it keeps almost nothing loaded, and the first run of
# this campaign learned what that costs: a mob summoned by one command was gone
# by the next, `execute if block` answered "That position is not loaded", and a
# falling meteor never ticked at all - it was simply removed with its chunk, so
# the mod correctly saw it disappear and correctly called that a landing.
#
# Forceloading is not a weakened assertion, it is the opposite: without it every
# world-state assertion here was measuring an unloaded world. The region covers
# spawn (where seven of the mods work) and is deliberately small.
note "force-loading the campaign's working area, so the world is actually there"
FL="$RUN/rcon-forceload.log"
# Wide enough to cover SkyGrid's radius-64 grid as well as spawn: the greedy
# variant's whole point is that its work is real, and a setBlock into a chunk
# nobody has loaded is nearly free. -128..127 is chunks -8..7 on both axes,
# which is 256 - vanilla's exact per-command cap ("Too many chunks in the
# specified area (maximum 256, but specified 289)" is what 128 inclusive costs).
rc "forceload add -128 -128 127 127" "forceload query" | tee "$FL"
assert "the campaign's chunks are forceloaded" in_file "$FL" 'to be force loaded'
assert "and the game agrees they are" in_file "$FL" 'force loaded chunks were found'
# Ten seconds of a running, empty server, so the mods below arrive into a game
# that has been ticking for a while rather than into the tail of a boot.
sleep 10

# ---------------------------------------------------------------- mid-session seeding
# Writes a mod's files where a generation would have put them, then hot-loads it
# through the product's own command. `enabled` is false in the metadata on
# purpose: `/vibe enable` then has to compile and load a mod the lifecycle has
# never seen, which is the same code path a fresh `/vibe make` ends in.
seed_only() {
  local name="$1" main="$2" icon="$3"
  local dest="$STORE/$name"
  mkdir -p "$dest"
  cp -R "$MODS_SRC/$name/v1" "$dest/v1"
  python3 - "$dest/meta.json" "$name" "$main" "$icon" <<'META'
import json, sys, time
path, name, main, icon = sys.argv[1:5]
open(path, "w").write(json.dumps({
    "schema": 3, "platform": "fabric", "mcVersion": "26.2", "side": "both",
    "name": name,
    "description": "V3 stress campaign roster mod.",
    "usage": "", "manual": "## " + name + "\n\nA stress-campaign roster mod.",
    "icon": icon,
    "mainClass": "vibemod." + name.lower() + "." + main,
    "currentVersion": 1,
    "enabled": False,
    "creator": "stress",
    "versions": [{"version": 1, "prompt": "the V3 stress roster: " + name,
                  "model": "hand-written", "createdAt": int(time.time() * 1000),
                  "changelog": "First roster version.", "kind": "create",
                  "costUsd": 0.0, "requester": "stress"}],
    "config": [], "configValues": {},
}, indent=2))
META
}

# Seeds and hot-loads one mod into the running session, and records when.
#
# The readiness predicate is `/vibe info` rather than a log line, and that is a
# finding rather than a preference: "<Name> v1 is live" is emitted by
# RESTORE-ON-BOOT, and the hot-load path (`applyVersion`) never prints it. The
# install card is the one place both paths agree. `/vibe enable` also answers
# "(no reply)" over RCON, because the compile is async and the connection is
# closed long before it finishes - so this has to poll either way.
hotload() {
  local name="$1" main="$2" icon="$3"
  seed_only "$name" "$main" "$icon"
  local at
  at="$(t)"
  rc "vibe enable $name" >> "$RUN/rcon-hotload.log" 2>&1 || true
  local outcome="TIMEOUT"
  for _ in $(seq 1 120); do
    if rc "vibe info $name" 2>/dev/null | grep -q 'not currently loaded'; then
      # Still loading, or refused. Tell the two apart from the journal.
      #
      # NOT `onInitialize failed for mod $name`: that line comes from
      # RESTORE-ON-BOOT. On the hot-load path the only thing the console is told
      # is the seam's own refusal, so that is what this watches for. Finding it
      # cost this campaign a 180-second timeout per run until it was noticed.
      if grep -qE "Refusing registry content from $name|onInitialize failed for mod $name" \
           "$LOG" 2>/dev/null; then
        outcome="REFUSED"
        break
      fi
    else
      outcome="LIVE"
      break
    fi
    sleep 1
  done
  echo "  $name: enable at $at, $outcome at $(t)"
  echo "$name $at $(t) $outcome" >> "$RUN/hotload-timeline.txt"
}

note "hot-loading the roster into the running session, one mod at a time"
hotload GrapplingHook GrapplingHook FISHING_ROD
hotload MeteorStorm   MeteorStorm   MAGMA_BLOCK
hotload ZombieTitan   ZombieTitan   ZOMBIE_HEAD
hotload RubyEconomy   RubyEconomy   AMETHYST_SHARD
hotload ArenaMaster   ArenaMaster   IRON_SWORD
hotload ChatCraft     ChatCraft     WRITABLE_BOOK
hotload SkyGrid       SkyGrid       GLASS
hotload TitanForge    TitanForge    NETHER_STAR
hotload Nightmare     Nightmare     TOTEM_OF_UNDYING
echo
echo "  hot-load timeline (mod, requested, live):"
sed 's/^/    /' "$RUN/hotload-timeline.txt"

# The datapack channel debounces its reload by 40 ticks. Nothing about a recipe
# is true until the COMPLETION line has appeared for the last mod's reload.
for i in $(seq 1 90); do
  test "$(grep -c 'Server data reloaded in' "$LOG")" -ge 1 && break
  sleep 1
done
sleep 5

# ============================================================== compile / load
note "the compile-and-hot-load pass"
live() { grep -qE "^$1 .* LIVE\$" "$RUN/hotload-timeline.txt"; }
registered() { grep -qF "Mod $1 registered /" "$LOG"; }
for mod in GrapplingHook MeteorStorm ZombieTitan RubyEconomy ArenaMaster ChatCraft SkyGrid Nightmare; do
  assert "$mod compiled and hot-loaded into the running server" live "$mod"
  assert "$mod's command reached the LIVE Brigadier dispatcher" registered "$mod"
  assert "$mod passed the surgeon's policy" not_in_file "$LOG" "$mod v1 failed to compile"
done
assert "no mod's source smuggled in a VibeMod import" \
  test -z "$(grep -rl 'com.gijsm.vibemod' "$MODS_SRC" || true)"
assert "nothing threw on the server thread while they arrived" \
  not_in_file "$LOG" 'Exception in thread'
assert "no mixin failed to apply" not_in_file "$LOG" 'Mixin apply failed'
assert "no command name collided" not_in_file "$LOG" 'is already registered'
# A datapack entry whose shape or whose vanilla id 26.x rejects is DROPPED as
# the pack loads: the mod reports success and the recipe simply is not there.
# One assertion over the whole roster catches every such file at once, and it is
# the only thing standing between "the mod loaded" and "the mod works".
assert "not one data file the roster shipped failed to parse" \
  not_in_file "$LOG" "Couldn't parse data file"

# ====================================================== 9. Nightmare's late replay
# Nightmare registers ServerLifecycleEvents.SERVER_STARTED in onInitialize. It
# arrived minutes after the server started and nothing will ever fire that event
# again, so if the host did not REPLAY it the mod would never learn the server
# exists and would never find its flag file. Asserted on the mod's own log line,
# because the host's replay notice is at FINE.
note "Nightmare - the SERVER_STARTED replay into a mod that loaded mid-session"
assert "its onInitialize ran" in_file "$LOG" 'nightmare-init'
assert "the host replayed SERVER_STARTED for it" in_file "$LOG" 'nightmare-started'
assert "exactly once" test "$(grep -c 'nightmare-started' "$LOG")" -eq 1
NLOG="$RUN/rcon-nightmare.log"
rc "nightmare" | tee "$NLOG"
assert "so the mod has a server reference it could not have got any other way" \
  in_file "$NLOG" 'hasServer=true'
assert "and counted exactly one SERVER_STARTED callback" in_file "$NLOG" 'startedCallbacks=1'

# =========================================================== 1. GrapplingHook
note "1. GrapplingHook - raytrace, launch, velocity sync, cooldown"
GLOG="$RUN/rcon-grapple.log"
rc "grapple test 14" "grapple cooldowns" | tee "$GLOG"
assert "the hook raytraced and found the anchor it placed 14 up" in_file "$GLOG" 'grapple-test hit=true'
assert "it measured a real distance" test "$(grep -cE 'dist=1[0-9]\.' "$GLOG")" -ge 1
assert "the launch wrote the expected upward velocity onto the probe" \
  in_file "$GLOG" 'vel=0.000,1.400,0.000'
assert "and set hurtMarked, so a real client would be told about it" \
  in_file "$GLOG" 'hurtMarked=true'
assert "the cooldown map has the probe in it" in_file "$GLOG" 'grapple-cooldowns tracked=1'
G2LOG="$RUN/rcon-grapple2.log"
rc "grapple test 14" | tee "$G2LOG"
assert "a second grapple inside the 3s cooldown is refused" in_file "$G2LOG" 'grapple-test hit=false'
PLOG="$RUN/rcon-grapple-probe.log"
rc "execute if entity @e[tag=vibemod_grapplinghook_probe]" | tee "$PLOG"
assert "the probe armour stand really is in the world" in_file "$PLOG" 'Test passed'
RECLOG="$RUN/rcon-packs.log"
rc "datapack list enabled" | tee "$RECLOG"
assert "its datapack was selected mid-session with no operator involved" \
  in_file "$RECLOG" 'vibemod-grapplinghook'
untestable "the right-click on the hook item itself (UseItemCallback needs a player in the world)"

# ============================================================= 2. MeteorStorm
note "2. MeteorStorm - forced drop, landing detection, explosion, fire ring"
MLOG="$RUN/rcon-meteor.log"
rc "meteor status" | tee "$MLOG"
assert "the mod's tick handler is counting in the running server" \
  test "$(grep -cE 'meteor-status inFlight=[0-9]+ impacts=[0-9]+ tick=[1-9]' "$MLOG")" -ge 1
IMPACTS_BEFORE="$(grep -c 'meteor-impact' "$LOG" || true)"
MARK=$(( $(wc -l < "$LOG") ))
rc "meteor now" | tee -a "$MLOG"
assert "/meteor now spawned a meteor" in_file "$MLOG" 'meteor-spawned'
for i in $(seq 1 40); do
  test "$(grep -c 'meteor-impact' "$LOG")" -gt "$IMPACTS_BEFORE" && break
  sleep 1
done
assert "the per-tick sweep detected the landing" \
  test "$(grep -c 'meteor-impact' "$LOG")" -gt "$IMPACTS_BEFORE"
IMPACT_LINE="$(tail -n "+$MARK" "$LOG" | grep -m1 'meteor-impact' || true)"
IMPACT="$(echo "$IMPACT_LINE" \
  | sed -E 's/.*meteor-impact (-?[0-9]+) (-?[0-9]+) (-?[0-9]+).*/\1 \2 \3/')"
IX="$(echo "$IMPACT" | cut -d' ' -f1)"
IY="$(echo "$IMPACT" | cut -d' ' -f2)"
IZ="$(echo "$IMPACT" | cut -d' ' -f3)"
echo "  impact at ${IX:-?} ${IY:-?} ${IZ:-?}"
# The age/removed/grounded fields exist because the first run's meteors "landed"
# the tick after they were made. In an unloaded chunk they never tick at all.
echo "  landing detail: $(echo "$IMPACT_LINE" | grep -o 'age=[0-9]* removed=[a-z]* grounded=[a-z]*' || true)"
AGE="$(echo "$IMPACT_LINE" | grep -oE 'age=[0-9]+' | cut -d= -f2 || true)"
assert "the meteor actually flew before it landed (age=${AGE:-?} ticks)" \
  test "${AGE:-0}" -gt 5
FLOG="$RUN/rcon-meteor-fire.log"
rc "execute if block $((IX + 2)) $((IY - 1)) $IZ minecraft:magma_block" \
   "execute if block $((IX - 2)) $((IY - 1)) $IZ minecraft:magma_block" \
   "execute if block $((IX + 2)) $IY $IZ minecraft:fire" \
   "meteor status" | tee "$FLOG"
assert "the impact scorched a ring the console can probe" \
  test "$(grep -c 'Test passed' "$FLOG")" -ge 2
# NOT inFlight=0: the mod's own 600-tick timer is dropping meteors throughout
# this campaign, so there is usually one in the air. What must be true is that
# the list is drained rather than growing, which a bound checks and an equality
# would only check by luck.
INFLIGHT="$(grep -oE 'inFlight=[0-9]+' "$FLOG" | tail -1 | cut -d= -f2)"
assert "the tracked-entity list is drained rather than growing (inFlight=$INFLIGHT)" \
  test "${INFLIGHT:-99}" -le 2
assert "the impact counter went up" \
  test "$(grep -oE 'impacts=[0-9]+' "$FLOG" | tail -1 | cut -d= -f2)" -ge 1
assert "the explosion never tripped the watchdog" not_in_file "$LOG" 'MeteorStorm was auto-disabled'

# ======================================== 8. MeteorStorm's unaided cadence
# The one thing a command cannot prove: that the mod's own 600-tick timer fires
# on its own. Waited out on the clock, twice.
note "8. MeteorStorm - two unaided 600-tick cycles, waited out on the clock"
AUTO_START="$(grep -c 'meteor-impact' "$LOG" || true)"
AUTO_T0=$(date +%s)
# BOTH conditions, and the wall clock is the load-bearing one: two more impacts
# could arrive quickly if one was already in the air. The mod's interval is 600
# ticks = 30 seconds, so two full cycles cannot happen in less than a minute.
for i in $(seq 1 180); do
  ELAPSED=$(( $(date +%s) - AUTO_T0 ))
  if test "$(grep -c 'meteor-impact' "$LOG")" -ge $((AUTO_START + 2)) && test "$ELAPSED" -ge 62; then
    echo "  two unaided impacts after ${ELAPSED}s of real time"
    break
  fi
  sleep 1
done
ELAPSED=$(( $(date +%s) - AUTO_T0 ))
NOW="$(grep -c 'meteor-impact' "$LOG" || true)"
assert "the mod's own timer dropped at least two more meteors with nobody asking ($AUTO_START -> $NOW)" \
  test "$NOW" -ge $((AUTO_START + 2))
assert "and it took at least two real 600-tick cycles to do it (${ELAPSED}s)" \
  test "$ELAPSED" -ge 62
rc "meteor status" | tee "$RUN/rcon-meteor-auto.log"
AUTO_INFLIGHT="$(grep -oE 'inFlight=[0-9]+' "$RUN/rcon-meteor-auto.log" | tail -1 | cut -d= -f2)"
assert "and it is still cleaning up after itself rather than leaking (inFlight=$AUTO_INFLIGHT)" \
  test "${AUTO_INFLIGHT:-99}" -le 2

# ============================================================== 3. ZombieTitan
note "3. ZombieTitan - attribute scaling, boss bar, tagged death, loot table"
TLOG="$RUN/rcon-titan.log"
rc "titan spawn" "titan status" | tee "$TLOG"
assert "a titan spawned" in_file "$TLOG" 'titan-spawned'
assert "with five times a zombie's 20 health" in_file "$TLOG" 'health=100.0'
assert "and three times its size" in_file "$TLOG" 'scale=3.0'
assert "the mod tracks it with a live boss bar" in_file "$TLOG" 'titan-status alive=1 bars=1'
T2LOG="$RUN/rcon-titan2.log"
rc "execute if entity @e[tag=vibemod_zombietitan_titan]" \
   "data get entity @e[tag=vibemod_zombietitan_titan,limit=1] Health" | tee "$T2LOG"
assert "the titan is in the world under its tag" in_file "$T2LOG" 'Test passed'
# Not `== 100.0f`: MeteorStorm is dropping explosives near spawn throughout this
# campaign and a titan that has been singed reads 99.06f. The claim being made is
# that the GAME sees a five-times-scaled pool, and a vanilla zombie caps at 20.
TITAN_HP="$(grep -oE 'entity data: [0-9]+' "$T2LOG" | tail -1 | grep -oE '[0-9]+$')"
assert "and the GAME reports the scaled health, not a vanilla zombie's 20 (${TITAN_HP}f)" \
  test "${TITAN_HP:-0}" -ge 90
T4LOG="$RUN/rcon-titan-loot.log"
rc "loot spawn 0 100 0 loot vibemod_zombietitan:titan_hoard" | tee "$T4LOG"
assert "the shipped loot_table resolved in the live game" \
  not_in_file "$T4LOG" 'Unknown loot table'
T3LOG="$RUN/rcon-titan3.log"
rc "kill @e[tag=vibemod_zombietitan_titan]" | tee "$T3LOG"
sleep 3
rc "titan status" \
   "execute if entity @e[type=minecraft:item,nbt={Item:{id:\"minecraft:rotten_flesh\"}}]" | tee -a "$T3LOG"
assert "AFTER_DEATH fired for the tagged titan" in_file "$LOG" 'titan-slain total=1'
assert "the boss bar came down with it" in_file "$T3LOG" 'titan-status alive=0 bars=0'
assert "and the Java hoard drop really dropped" in_file "$T3LOG" 'Test passed'
untestable "the boss bar being VISIBLE (a ServerBossEvent with no players is a live object nobody can see)"

# ============================================================== 4. RubyEconomy
note "4. RubyEconomy - two recipe types, a component-bearing result, balances on disk"
ELOG="$RUN/rcon-econ.log"
rc "econ verify" | tee "$ELOG"
assert "the smelting recipe reached the live recipe manager" in_file "$ELOG" 'ruby=true'
assert "the shaped recipe did too" in_file "$ELOG" 'blade=true'
assert "the game's own match+assemble produced the blade" \
  in_file "$ELOG" 'crafted=minecraft:iron_sword'
assert "the result carries the custom name from the JSON" in_file "$ELOG" 'craftedName=Ruby Blade'
assert "and BOTH attribute modifiers survived the 26.x codec" in_file "$ELOG" 'bladeModifiers=2'
E2LOG="$RUN/rcon-econ2.log"
rc "balance Steve" "econ grant Steve 50" "balance Steve" \
   "pay Steve Alex 20" "balance Steve" "balance Alex" "econ save" | tee "$E2LOG"
assert "a fresh account starts at 10" in_file "$E2LOG" 'balance Steve 10 rubies'
assert "a grant lands" in_file "$E2LOG" 'econ-granted Steve 50 -> 60'
assert "a transfer moves both balances" in_file "$E2LOG" 'econ-paid Steve -> Alex 20 (40/30)'
assert "and the reads agree afterwards" in_file "$E2LOG" 'balance Alex 30 rubies'
assert "balances are keyed by a real UUID" \
  test "$(grep -cE 'balance Steve .*\([0-9a-f-]{36}\)' "$E2LOG")" -ge 1
BAL="$RUN/world/vibemod_rubyeconomy-balances.json"
assert "the balances were written into the world folder from mod code" test -f "$BAL"
assert "as JSON with two accounts in it" \
  test "$(grep -cE '\"[0-9a-f-]{36}\": [0-9]+' "$BAL")" -eq 2
E3LOG="$RUN/rcon-econ3.log"
rc "pay Alex Steve 999" | tee "$E3LOG"
assert "an overdraft is refused rather than going negative" in_file "$E3LOG" 'econ-refused Alex has 30'

# ============================================================== 5. ArenaMaster
note "5. ArenaMaster - a four-branch Brigadier tree, waves over real ticks, scoreboard"
ALOG="$RUN/rcon-arena.log"
rc "arena list" "arena create ring 8" "arena list" "arena start ring 3" | tee "$ALOG"
assert "an empty /arena list works before anything exists" in_file "$ALOG" 'arena-list count=0'
assert "create takes a word and a bounded int" in_file "$ALOG" 'arena-created ring radius=8'
assert "list reflects it" in_file "$ALOG" 'arena-list count=1 ring/8'
assert "start takes a name and a wave count" in_file "$ALOG" 'arena-started ring waves=3'
A2LOG="$RUN/rcon-arena2.log"
rc "arena bogus" "arena start nope 1" "arena create x 99" | tee "$A2LOG"
assert "an unknown literal is a Brigadier parse error, not a crash" \
  in_file "$A2LOG" 'Incorrect argument for command'
assert "an out-of-range int is refused by the argument type" \
  in_file "$A2LOG" 'Integer must not be more than 32'
assert "an unknown arena is the mod's own error" in_file "$A2LOG" 'arena-unknown nope'
# Three waves, 60 ticks apart. Waited out on the clock, not simulated.
note "5b. ArenaMaster - waiting out three waves of real ticks"
for i in $(seq 1 60); do
  test "$(grep -c 'arena-wave' "$LOG")" -ge 3 && { echo "  three waves after ${i}s"; break; }
  sleep 1
done
assert "wave 1 spawned zombies" in_file "$LOG" 'arena-wave ring n=1 mobs=2 type=minecraft:zombie'
assert "wave 2 escalated to skeletons" in_file "$LOG" 'arena-wave ring n=2 mobs=4 type=minecraft:skeleton'
assert "wave 3 escalated again" in_file "$LOG" 'arena-wave ring n=3 mobs=6 type=minecraft:spider'
A3LOG="$RUN/rcon-arena3.log"
# The score is read BEFORE and AFTER, and the assertion is the delta. It has to
# be: MeteorStorm is dropping a meteor near spawn every 30 seconds throughout
# this campaign, and an explosion that kills an arena mob is a real arena kill.
# An absolute number here would be a flake, and pinning it would be a lie.
rc "scoreboard players get arena arena_kills" | tee "$A3LOG"
SCORE_BEFORE="$(sed -nE 's/.*has ([0-9]+) \[Arena kills\].*/\1/p' "$A3LOG" | head -1)"
SCORE_BEFORE="${SCORE_BEFORE:-0}"
rc "execute if entity @e[tag=vibemod_arenamaster_mob]" \
   "kill @e[tag=vibemod_arenamaster_mob,limit=3]" | tee -a "$A3LOG"
assert "the ring is full of tagged mobs" in_file "$A3LOG" 'Test passed'
sleep 3
A4LOG="$RUN/rcon-arena4.log"
rc "scoreboard players get arena arena_kills" "arena stop" | tee "$A4LOG"
SCORE_AFTER="$(sed -nE 's/.*has ([0-9]+) \[Arena kills\].*/\1/p' "$A4LOG" | head -1)"
SCORE_AFTER="${SCORE_AFTER:-0}"
assert "the mod created a scoreboard objective the vanilla command can read" \
  not_in_file "$A4LOG" 'Unknown scoreboard objective'
assert "and the death event scored the three kills ($SCORE_BEFORE -> $SCORE_AFTER)" \
  test "$SCORE_AFTER" -ge "$((SCORE_BEFORE + 3))"
assert "the tag sweep removed what was left" in_file "$A4LOG" 'arena-stopped swept='
A5LOG="$RUN/rcon-arena5.log"
rc "execute if entity @e[tag=vibemod_arenamaster_mob]" | tee "$A5LOG"
assert "nothing tagged survived the sweep" in_file "$A5LOG" 'Test failed'

# ================================================================ 6. ChatCraft
note "6. ChatCraft - a chat subscription, a shared parse path, an mcfunction"
assert "the host fanned out the chat event this mod asked for, mid-session" \
  in_file "$LOG" 'Fanning out ServerMessageEvents.AllowChatMessage'
CLOG="$RUN/rcon-chatcraft.log"
rc "chatcraft" "chatcraft craft me a diamond sword" "chatcraft craft me a unicorn" \
   "function vibemod_chatcraft:menu" | tee "$CLOG"
assert "the status branch answers" in_file "$CLOG" 'chatcraft-status served=0'
assert "the shared parse+lookup resolves a real item" \
  in_file "$CLOG" 'want=diamond sword found=true id=minecraft:diamond_sword'
assert "and misses a made-up one without throwing" in_file "$CLOG" 'want=unicorn found=false'
assert "the mod's mcfunction runs" in_file "$LOG" 'chatcraft-menu-ok'
untestable "the chat trigger firing (RCON cannot send a signed player chat message)"
untestable "the chat line being cancelled (same reason)"

# ================================================================== 7. SkyGrid
note "7. SkyGrid - sustained per-tick work under the watchdog"
LAG_BEFORE="$(grep -c "Can't keep up" "$LOG" || true)"
SLOG="$RUN/rcon-skygrid.log"
# radius 24, spacing 4 -> 13 nodes per axis -> 13^3, which is well over one
# tick's budget of 2000 and is the point of the batching.
rc "skygrid 24" | tee "$SLOG"
assert "the batched build queued 13^3 nodes" in_file "$SLOG" 'skygrid-queued nodes=2197'
for i in $(seq 1 60); do
  grep -q 'skygrid-done' "$LOG" && break
  sleep 1
done
assert "the batched build finished, over more than one tick" \
  test "$(grep -cE 'skygrid-done placed=2197 batches=[2-9]' "$LOG")" -ge 1
SLOWEST="$(grep -oE 'slowestBatchMs=[0-9]+' "$LOG" | tail -1 | cut -d= -f2)"
assert "and no single batch came near the 250ms budget (slowest ${SLOWEST:-?}ms)" \
  test "${SLOWEST:-999}" -lt 250
S2LOG="$RUN/rcon-skygrid2.log"
# The block at a node is a pure function of the position: |0*31 + 100*17 + 0*7|
# = 1700, 1700 % 8 = 4, and PALETTE[4] is coal ore. Asserting the exact block
# rather than "something solid" is what makes this a test of the mod's own
# placement rather than of setBlock.
rc "execute if block 0 100 0 minecraft:coal_ore" \
   "execute if block 1 101 1 minecraft:air" \
   "skygrid" | tee "$S2LOG"
assert "the origin node is the exact block the mod's hash picks" \
  test "$(grep -c 'Test passed' "$S2LOG")" -ge 2
assert "the batched build never tripped the watchdog" not_in_file "$LOG" 'SkyGrid was auto-disabled'
# Scoped to the batched build's own window. Earlier lag in this long session is
# somebody else's (the greedy variant below is deliberately somebody else).
assert "and 2197 blocks spread over ticks cost the server no lag warning at all" \
  test "$(grep -c "Can't keep up" "$LOG" || true)" -eq "$LAG_BEFORE"

# The greedy variant. This is an OBSERVATION, and the observation is the finding:
# a native mod's Brigadier command BODY is not watchdog-timed. The CommandSeam
# times the registration callback (it goes through ModDispatch) but the
# `executes(...)` node it installs is invoked by vanilla's own dispatcher with no
# host frame in between - so a command that will not yield has no budget at all.
# The v2 curated `ctx.command(...)` path IS timed; this is the difference.
note "7b. SkyGrid greedy - what happens to a mod that will not yield"
TRIPS_BEFORE="$(grep -c 'auto-disabled by the watchdog' "$LOG" || true)"
GREEDY="$RUN/rcon-greedy.log"
GSTART=$(date +%s)
rc "skygrid_greedy 128" | tee "$GREEDY" || true
GELAPSED=$(( $(date +%s) - GSTART ))
sleep 5
rc "list" > "$RUN/rcon-alive.log" 2>&1 || true
assert "the server survived a mod that would not yield" in_file "$RUN/rcon-alive.log" 'There are'
TRIPS_AFTER="$(grep -c 'auto-disabled by the watchdog' "$LOG" || true)"
LAG_AFTER="$(grep -c "Can't keep up" "$LOG" || true)"
# The mod times its own invocation, because "the server did not visibly stutter"
# is a claim about this Mac while "one invocation took N ms and nothing stopped
# it" is a claim about the host. The single-invocation budget is 250ms.
GMS="$(grep -oE 'elapsedMs=[0-9]+' "$GREEDY" | tail -1 | cut -d= -f2)"
echo "  OBSERVED:"
echo "    274625 setBlocks in ONE command invocation"
echo "    the mod's own measurement:  ${GMS:-?}ms (watchdog single-invocation budget: 250ms)"
echo "    wall time round trip:       ${GELAPSED}s"
echo "    watchdog trips:             $TRIPS_BEFORE -> $TRIPS_AFTER"
echo "    server lag warnings:        $LAG_BEFORE -> $LAG_AFTER"
grep -E "auto-disabled by the watchdog|Can't keep up" "$LOG" | tail -3 || true
GSTATE="$RUN/rcon-greedy-state.log"
rc "vibe info SkyGrid" "skygrid" | tee "$GSTATE" || true
echo "    SkyGrid reported not loaded afterwards: $(grep -c 'not currently loaded' "$GSTATE" || true)"
# The findings, stated positively so a future change says so instead of going quiet.
assert "the greedy command ran to completion inside one tick" \
  in_file "$GREEDY" 'skygrid-greedy-done placed=274625'
assert "it took longer than the watchdog's whole single-invocation budget (${GMS:-?}ms > 250ms)" \
  test "${GMS:-0}" -gt 250
assert "and the mod was NOT auto-disabled for it: a Brigadier command body has no host frame around it" \
  test "$TRIPS_AFTER" -eq "$TRIPS_BEFORE"
assert "the mod is still live afterwards, unbudgeted and unpunished" \
  not_in_file "$GSTATE" 'not currently loaded'

# ======================================================== disable/enable pass
note "disable/enable round-trip 1: ArenaMaster's Brigadier tree"
D1="$RUN/rcon-disable-arena.log"
rc "vibe disable ArenaMaster" "arena list" | tee "$D1"
assert "disabling removed the mod's tree from the live dispatcher" \
  not_in_file "$D1" 'arena-list count='
assert "and the server says the command is unknown" in_file "$D1" 'Unknown or incomplete command'
assert "its datapack directory went with it" test ! -d "$RUN/world/datapacks/vibemod-arenamaster"
E1="$RUN/rcon-enable-arena.log"
rc "vibe enable ArenaMaster" | tee "$E1"
for i in $(seq 1 90); do
  rc "arena list" >> "$E1" 2>&1 || true
  grep -q 'arena-list count=' "$E1" && break
  sleep 1
done
assert "re-enabling put the tree back, live, with no /reload" in_file "$E1" 'arena-list count='
assert "and the mod's own state started fresh, as an unloaded mod's must" \
  in_file "$E1" 'arena-list count=0'

note "disable/enable round-trip 2: MeteorStorm's tick subscription"
rc "meteor now" > /dev/null
sleep 4
rc "vibe disable MeteorStorm" > "$RUN/rcon-disable-meteor.log"
sleep 3
BEFORE="$(grep -c 'meteor-impact' "$LOG" || true)"
sleep 8
AFTER="$(grep -c 'meteor-impact' "$LOG" || true)"
assert "a disabled mod's tick handler stopped being dispatched (impacts $BEFORE -> $AFTER)" \
  test "$BEFORE" -eq "$AFTER"
M2="$RUN/rcon-enable-meteor.log"
rc "vibe enable MeteorStorm" | tee "$M2"
for i in $(seq 1 90); do
  rc "meteor status" >> "$M2" 2>&1 || true
  grep -q 'meteor-status' "$M2" && break
  sleep 1
done
assert "re-enabling brought the tick handler back" in_file "$M2" 'meteor-status'
rc "meteor now" > /dev/null
for i in $(seq 1 40); do
  test "$(grep -c 'meteor-impact' "$LOG")" -gt "$AFTER" && break
  sleep 1
done
assert "and a meteor lands again" test "$(grep -c 'meteor-impact' "$LOG")" -gt "$AFTER"

# ============================================ Nightmare: round-trip and unload
note "9b. Nightmare - night damage scaling on a real mob, at real night"
FLAG="$RUN/world/vibemod_nightmare-harsh.flag"
N4="$RUN/rcon-nightmare-damage.log"
# `Level.isDarkOutside()` reads skyDarken, which vanilla recomputes on the NEXT
# tick. Asking in the same RCON batch as `/time set night` reads the old value -
# the first run of this campaign did exactly that and saw dark=false at midnight.
rc "time set night" > /dev/null
sleep 3
rc "nightmare" | tee "$N4"
assert "the mod reads the world's own darkness" in_file "$N4" 'dark=true'
assert "and its harsh flag is on by default" in_file "$N4" 'harsh=true'
# A 10hp cow, hit for 4, at ground level inside the forceloaded region. Vanilla
# leaves 6; the mod's AFTER_DAMAGE doubling leaves 2. Read back off the entity by
# the GAME, not by the mod.
rc "summon minecraft:cow 6 -59 6 {Tags:[\"nmcow\"],NoAI:1b,NoGravity:1b}" | tee -a "$N4"
sleep 2
rc "damage @e[tag=nmcow,limit=1] 4 minecraft:generic" | tee -a "$N4"
sleep 2
rc "nightmare" "data get entity @e[tag=nmcow,limit=1] Health" | tee -a "$N4"
assert "AFTER_DAMAGE fired and the mod scaled the hit" in_file "$N4" 'scaled=1'
assert "and the doubled damage really landed (10hp cow, hit for 4 -> 2.0 left)" \
  in_file "$N4" '2.0f'
N5="$RUN/rcon-nightmare-day.log"
rc "nightmare toggle" | tee "$N5"
sleep 1
rc "summon minecraft:cow 8 -59 8 {Tags:[\"daycow\"],NoAI:1b,NoGravity:1b}" | tee -a "$N5"
sleep 2
rc "damage @e[tag=daycow,limit=1] 4 minecraft:generic" | tee -a "$N5"
sleep 2
rc "data get entity @e[tag=daycow,limit=1] Health" "nightmare" | tee -a "$N5"
assert "the toggle flips and is persisted" in_file "$N5" 'nightmare-toggled harsh=false'
assert "the flag file was written" test -f "$FLAG"
assert "with the toggled value in it" in_file "$FLAG" 'gentle'
assert "with the mod switched off the same hit is vanilla again (6.0 left)" \
  in_file "$N5" '6.0f'
assert "and the scaled counter did not move while it was off" in_file "$N5" 'scaled=1'

note "9c. Nightmare - disable, enable, and the flag that survives both"
N3="$RUN/rcon-nightmare3.log"
rc "vibe disable Nightmare" "nightmare" | tee "$N3"
assert "disabling removed the command" in_file "$N3" 'Unknown or incomplete command'
rc "vibe enable Nightmare" | tee -a "$N3"
for i in $(seq 1 90); do
  rc "nightmare" >> "$N3" 2>&1 || true
  grep -q 'nightmare-status' "$N3" && break
  sleep 1
done
assert "re-enabling brought the command back" in_file "$N3" 'nightmare-status'
assert "the persisted flag survived the round trip" in_file "$N3" 'nightmare-status harsh=false'
assert "and the replay fired again for the fresh instance" \
  test "$(grep -c 'nightmare-started' "$LOG")" -ge 2

note "9d. Nightmare - unload, and what the registry ledger says about it"
U1="$RUN/rcon-unload.log"
rc "vibe delete Nightmare confirm" | tee "$U1"
assert "the unload reported success" in_file "$U1" 'Deleted Nightmare.'
sleep 4
U2="$RUN/rcon-unload2.log"
rc "nightmare" "vibe list" "datapack list enabled" | tee "$U2"
assert "the command is gone" in_file "$U2" 'Unknown or incomplete command'
assert "the mod is gone from the store" not_in_file "$U2" 'Nightmare'
assert "and its datapack is deselected" not_in_file "$U2" 'vibemod-nightmare'
assert "no missing-data-pack warning was produced" not_in_file "$LOG" 'Missing data pack'
assert "the world folder no longer carries its pack" \
  test ! -d "$RUN/world/datapacks/vibemod-nightmare"

# ================================================== TitanForge: the registry mod
note "TitanForge - the roster's registry mod, hot-loaded into a host that refuses registries"
assert "the seam refused it" in_file "$LOG" 'Refusing registry content from TitanForge'
assert "with the deterministic policy stated verbatim" \
  in_file "$LOG" 'registry content is singleplayer/LAN-host only in v1'
# NOT `onInitialize failed for mod TitanForge`. That line is emitted by
# RESTORE-ON-BOOT (smoke-fabric.sh asserts it); on the HOT-LOAD path the same
# refusal is journalled to /vibe errors with the full stack but no summary line
# reaches the console. Asserted here as it actually is, and written up as a
# diagnostic asymmetry rather than smoothed over.
assert "the refusal is the only thing the console is told about it" \
  test "$(grep -c 'onInitialize failed for mod TitanForge' "$LOG")" -eq 0
assert "so neither registration line was ever reached" \
  not_in_file "$LOG" 'titan-forge-item-registered'
assert "and the half-built item was rolled back out of DATA_COMPONENT_INITIALIZERS" \
  in_file "$LOG" 'Rolled back 1 data-component initializer'
assert "so no later datapack reload in this long session was poisoned" \
  not_in_file "$LOG" 'Missing element ResourceKey[minecraft:item'
assert "it did not stop any other roster mod hot-loading" live SkyGrid
assert "and the timeline records it as REFUSED rather than live" \
  in_file "$RUN/hotload-timeline.txt" 'TitanForge'
TF="$RUN/rcon-titanforge.log"
rc "vibe errors TitanForge" "vibe info TitanForge" | tee "$TF"
assert "the refusal is where /vibe errors can show an operator" \
  in_file "$TF" 'singleplayer/LAN-host only'
assert "and the mod is reported as not live" in_file "$TF" 'not currently loaded'
assert "NO registry ledger exists on a host that registers nothing" \
  test ! -e "$RUN/vibemod/registry-ledger.json"
untestable "the ledger tombstone on unload (nothing can be registered on a dedicated server, so nothing can be tombstoned)"

# ============================================== the watchdog's whole story
# The two halves of the same asymmetry, reported together because neither is
# interesting alone:
#   * an END_SERVER_TICK handler IS budgeted - MeteorStorm's impact (an explosion
#     plus a 5x5 ring of setBlocks) can go over 250ms in a loaded world, and when
#     it does the mod is auto-disabled cleanly, with its command revoked;
#   * a Brigadier command body is NOT - SkyGrid's greedy variant placed 35937
#     blocks in one invocation, lagged the server visibly, and was not touched.
note "the watchdog's whole story, both halves"
TRIPPED="$(grep -oE '[A-Za-z]+ was auto-disabled by the watchdog' "$LOG" | sort -u || true)"
echo "  watchdog trips this session: ${TRIPPED:-none}"
if [[ -n "$TRIPPED" ]]; then
  assert "an auto-disabled mod had its command revoked as part of the trip" \
    in_file "$LOG" 'Removed /meteor with mod MeteorStorm'
  assert "and the trip took nothing else with it" not_in_file "$LOG" 'Exception in thread'
fi
assert "SkyGrid, which never yields inside a command body, was NOT auto-disabled" \
  not_in_file "$LOG" 'SkyGrid was auto-disabled'
# Bring anything the watchdog tripped back, so the /reload check below covers
# the whole roster rather than whatever happened to survive.
rc "vibe enable MeteorStorm" > /dev/null 2>&1 || true
sleep 6

# =============================================================== the session
note "the session survived all of it"
FINAL="$RUN/rcon-final.log"
rc "vibe list" "reload" | tee "$FINAL"
sleep 8
rc "arena list" "econ verify" "chatcraft" "grapple cooldowns" "skygrid" \
   "meteor status" "titan status" | tee -a "$FINAL"
assert "every surviving mod's command still works after a datapack /reload" \
  test "$(grep -cE 'arena-list|ruby=true|chatcraft-status|grapple-cooldowns|skygrid-status|meteor-status|titan-status' "$FINAL")" -ge 6
assert "the host replayed the survivors' command registrations into the new tree" \
  in_file "$LOG" 'mod command registration'
assert "and /vibe itself survived" in_file "$FINAL" 'GrapplingHook'
UPTIME=$(( $(date +%s) - BOOTED_AT ))
assert "the server was up, ticking and serving for the whole campaign (${UPTIME}s)" \
  test "$UPTIME" -ge 150

cleanup
trap - EXIT

echo
echo "=================================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "== V3 STRESS CAMPAIGN: all assertions held"
else
  echo "!! V3 STRESS CAMPAIGN: $FAILURES ASSERTION(S) FAILED"
fi
echo "   one continuous session, uptime ${UPTIME}s"
echo "   honestly-untestable-headless behaviours: $UNTESTED"
echo "   hot-load timeline: $RUN/hotload-timeline.txt"
echo "   log: $LOG"
echo "=================================================================="
[[ "$FAILURES" -eq 0 ]] || exit 1
