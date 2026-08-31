#!/usr/bin/env bash
# Usage: scripts/stress-client.sh
#
# The stress campaign's REAL-CLIENT half (docs/phases/STRESS-TEST.md).
#
# NOT A GATE, and deliberately NOT a gametest. `:fabric:runClientGameTest` boots
# a client in test mode with its mods already in the store; what this drives is
# an ORDINARY client, in a world, with a player standing in it, and the roster
# arriving through `/vibe enable` TYPED INTO THE CHAT BOX by real keyboard input.
# That is the only way to exercise what a console structurally cannot reach: a
# right-click on an item held by a player, a signed player chat message, a boss
# bar somebody can see, a player who physically moves.
#
# The shape, and why:
#   * A real VibeMod dedicated server is booted first, with an EMPTY store, and
#     the client joins it with `--quickPlayMultiplayer`. Singleplayer quick-play
#     was tried first and did nothing at all (see STRESS-RESULT.md); joining a
#     server removes the world-folder variable and costs only the two things a
#     dedicated server never had anyway - client-side `assets/**` and runtime
#     registry content - both of which the Phase 3 client gate already covers.
#   * Keystrokes are sent with `osascript` / System Events. THIS REQUIRES the
#     terminal running this script to hold macOS Accessibility permission
#     (System Settings -> Privacy & Security -> Accessibility). Without it every
#     keystroke fails with "System Events got an error: osascript is not allowed
#     to send keystrokes. (1002)" and the script stops rather than pretending.
#   * "Use item" is rebound to the R key in options.txt before launch, because
#     System Events sends keystrokes but not mouse buttons. R is a real key press
#     through the game's own keybind system, reaching the same `UseItemCallback`
#     a right mouse button would.
#   * Screenshots are the game's own F2, not `screencapture` (which needs Screen
#     Recording permission and fails on this machine with "could not create image
#     from display"). They land in fabric/stress-client/screenshots/, which is
#     better evidence anyway: the client's own framebuffer, not a picture of a
#     desktop.
#
# SHARING THE MACHINE. This script runs a server and a client next to whatever
# the person at the keyboard is already doing, and this campaign learned that the
# hard way twice: once it killed a Minecraft somebody had open, and once its JVMs
# helped the OS OOM-kill one. So, as rules rather than habits:
#   * kill ONLY pids this script started - never a broad `pkill` on a class name,
#     never `gradlew --stop` (that stops the daemon somebody else's run is using);
#   * refuse to start if another Minecraft is already running (below);
#   * cap the heaps. The server is -Xmx1G and the client -Xmx2G (fabric/
#     build.gradle.kts), against Loom's default of 4G.
#
# Everything lives under fabric/stress-client/, which is git-ignored.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="$ROOT/fabric/stress-client"
SRV="$ROOT/fabric/stress-client-server"
CACHE="$ROOT/fabric/smoke-cache"
MODS_SRC="$ROOT/scripts/stress-mods"
STORE="$SRV/vibemod/mods"
SHOTS="$RUN/screenshots"
CLIENT_LOG="$RUN/logs/latest.log"
GRADLE_LOG="$ROOT/fabric/stress-client-gradle.log"
SRV_LOG="$SRV/boot.log"
PORT=25589
RCON_PORT=25590
RCON_PASSWORD="vibemod-stress-client"
RCON="$ROOT/scripts/smoke-rcon.py"

prop() { grep -E "^$1=" "$ROOT/gradle.properties" | cut -d= -f2- ; }
MC_VERSION="$(prop minecraftVersion)"
LOADER_VERSION="$(prop fabricLoaderVersion)"
FABRIC_API_VERSION="$(prop fabricApiVersion)"

JAR="$(ls -t "$ROOT"/fabric/build/libs/vibemod-fabric-*.jar 2>/dev/null | head -1 || true)"
[[ -n "$JAR" ]] || { echo "!! run ./gradlew :fabric:build first" >&2; exit 1; }

FAILURES=0
UNDRIVABLE=0
SHOT_LIST="$RUN/screenshot-index.txt"
STARTED_AT=$(date +%s)
t() { echo "T+$(( $(date +%s) - STARTED_AT ))s"; }
note() { echo; echo "== [$(t)] $*"; }
assert() {
  local what="$1"; shift
  if "$@"; then echo "  ok: $what"; else echo "  FAIL: $what" >&2; FAILURES=$((FAILURES + 1)); fi
}
undrivable() { echo "  UNDRIVABLE-EVEN-IN-A-REAL-CLIENT: $*"; UNDRIVABLE=$((UNDRIVABLE + 1)); }
# All three logs. VibeMod logs through java.util.logging, which on the server
# goes to boot.log and in a Loom dev client goes to the gradle console, while
# Minecraft's own log4j output (including every [CHAT] line) goes to
# logs/latest.log. Reading one would miss two thirds of the evidence.
logs() { cat "$CLIENT_LOG" "$GRADLE_LOG" "$SRV_LOG" 2>/dev/null; }
in_log() { logs | grep -qF -- "$1"; }
not_in_log() { ! logs | grep -qF -- "$1"; }
count_log() { logs | grep -cF -- "$1" || true; }
rc() { "$RCON" "$RCON_PORT" "$RCON_PASSWORD" "$@"; }

# --------------------------------------------- do not touch anyone else's game
# This script sends synthetic keystrokes to a window and kills a process by pid.
# Both are catastrophic if it picks the WRONG Minecraft, and the first version of
# it did exactly that: `pgrep -f net.minecraft.client.main.Main` matched a client
# the person at this machine had open in the official launcher, and the cleanup
# killed it. So: refuse to start while another Minecraft is running, and from
# here on identify our own client by its Loom run configuration name.
note "checking that no other Minecraft is running"
# `comm` must be java: a shell whose own command line happens to contain the
# pattern (a `pgrep` in a waiter loop, this script itself) is not a game.
FOREIGN="$(pgrep -f 'net.minecraft.client.main.Main' 2>/dev/null | while read -r pid; do
    [[ "$(ps -p "$pid" -o comm= 2>/dev/null)" == *java* ]] && echo "$pid"
  done | tr '\n' ' ' || true)"
if [[ -n "${FOREIGN// /}" ]]; then
  echo "!! REFUSING TO RUN: a Minecraft client is already running (pid(s): $FOREIGN)." >&2
  echo "!! This script types into the focused window and kills the client it started." >&2
  echo "!! Close the other Minecraft first." >&2
  exit 3
fi
STALE="$(pgrep -f 'runStressClient' | tr '\n' ' ' || true)"
if [[ -n "${STALE// /}" ]]; then
  echo "!! REFUSING TO RUN: a previous stress client is still alive (pid(s): $STALE)." >&2
  echo "!! It will steal window focus and this run's keystrokes would go to it." >&2
  echo "!! kill $STALE  and try again." >&2
  exit 3
fi
echo "  ok: no other Minecraft is running, so nothing here can reach one"

note "checking macOS Accessibility permission for synthetic keystrokes"
if ! PROBE="$(osascript -e 'tell application "System Events" to key code 63' 2>&1)"; then
  echo "!! BLOCKED: synthetic keyboard input is not permitted for this terminal." >&2
  echo "!! osascript said, verbatim:" >&2
  echo "!!   $PROBE" >&2
  echo "!! Grant Accessibility in System Settings -> Privacy & Security ->" >&2
  echo "!! Accessibility, then run this again. scripts/stress-native.sh does not" >&2
  echo "!! need this and stands alone." >&2
  exit 2
fi
echo "  ok: System Events accepted a key code, so real input is available"

# --------------------------------------------------------------- the server
SERVER_JAR="$CACHE/fabric-server-$MC_VERSION-$LOADER_VERSION.jar"
API_JAR="$CACHE/fabric-api-$FABRIC_API_VERSION.jar"
for f in "$SERVER_JAR" "$API_JAR"; do
  [[ -f "$f" ]] || { echo "!! $f missing - run scripts/stress-native.sh once first" >&2; exit 1; }
done

rm -rf "$RUN" "$SRV"
mkdir -p "$SRV/mods" "$STORE" "$RUN" "$SHOTS"
cp "$JAR" "$API_JAR" "$SRV/mods/"
echo "eula=true" > "$SRV/eula.txt"
cat > "$SRV/server.properties" <<PROPS
online-mode=false
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASSWORD
server-port=$PORT
level-type=minecraft\:flat
level-seed=7
spawn-protection=0
pause-when-empty-seconds=0
max-players=5
view-distance=8
simulation-distance=8
sync-chunk-writes=false
allow-flight=true
motd=VibeMod stress client run
PROPS

note "booting the server the client will join (empty store)"
( cd "$SRV" && java -Xms512M -Xmx1G -jar "$SERVER_JAR" nogui > "$SRV_LOG" 2>&1 < /dev/null & echo $! > "$SRV/pid" )
SERVER_PID="$(cat "$SRV/pid")"
for i in $(seq 1 420); do
  grep -q 'Done (' "$SRV_LOG" 2>/dev/null && { echo "  server booted after ${i}s"; break; }
  sleep 1
done
grep -q 'Done (' "$SRV_LOG" || { echo "!! server never booted" >&2; tail -40 "$SRV_LOG" >&2; exit 1; }
rc "forceload add -128 -128 127 127" > /dev/null 2>&1 || true

# ------------------------------------------------------------------- options
cat > "$RUN/options.txt" <<'OPTS'
version:4901
autoJump:false
fov:0.0
gamma:1.0
guiScale:2
renderDistance:8
simulationDistance:8
maxFps:60
graphicsMode:0
narrator:0
tutorialStep:none
key_key.use:key.keyboard.r
key_key.attack:key.keyboard.f
key_key.screenshot:key.keyboard.f2
lang:en_us
soundCategory_master:0.0
skipMultiplayerWarning:true
onboardAccessibility:false
OPTS
mkdir -p "$RUN/config"

# ------------------------------------------------------------------ the input
CLIENT_PID=""
# Focus the game, then VERIFY it, and refuse to type if it is not focused.
#
# This is not belt-and-braces, it is the fix for a real incident: when the client
# died mid-run the focus call failed silently, `say()` typed anyway, and
# "thello-from-a-real-keyboard" landed in the operator's terminal. Synthetic
# keystrokes go to whatever is frontmost, so "we asked for focus" is not good
# enough - the only safe precondition is "the game IS frontmost, checked now".
focus() {
  osascript -e "tell application \"System Events\" to set frontmost of (first process whose unix id is $CLIENT_PID) to true" \
    >/dev/null 2>&1 || true
  sleep 0.3
  # Checked by WHAT the front process is, not by pid equality: the java that owns
  # the window is not always the pid `pgrep` first matched, but it is always a
  # java whose command line carries this run configuration. Nothing else on the
  # machine does.
  # The frontmost process must be a JVM. Deliberately not "must be pid
  # $CLIENT_PID": the java that owns the window is a different pid from the one
  # Loom's launcher reports, and pinning it exactly made this script refuse to
  # run at all. The thing being guarded against is concrete - a keystroke landing
  # in the operator's TERMINAL, which is what actually happened - and a terminal
  # is never a JVM. The start-up check above has already established that the
  # only Minecraft on this machine is ours.
  local front comm
  front="$(osascript -e 'tell application "System Events" to unix id of first process whose frontmost is true' 2>/dev/null || true)"
  comm="$(ps -p "${front:-0}" -o comm= 2>/dev/null || true)"
  if [[ -z "$front" || "$comm" != *java* ]]; then
    echo "!! ABORTING: the frontmost process (pid ${front:-unknown}) is not a JVM." >&2
    echo "!!   ${comm:-unknown}" >&2
    echo "!! Refusing to send keystrokes - they would land in whatever window that is." >&2
    exit 5
  fi
}
# One chat line, typed. T opens the chat box, the text is typed, Enter sends.
#
# The T is a `key code`, not a `keystroke`, and the difference is the whole
# reason the first run of this script did nothing: System Events' `keystroke`
# synthesizes a unicode character event, which GLFW delivers to its CHAR
# callback but not to its KEY callback - so Minecraft's chat-open keybind never
# saw it, while F2 (sent as a key code) worked perfectly. Once the chat box is
# open the text goes in through the char callback, so `keystroke` is right for
# the body and wrong for the trigger.
say() {
  focus
  osascript -e "tell application \"System Events\" to key code $KEY_T" >/dev/null
  sleep 0.7
  osascript -e "tell application \"System Events\" to keystroke \"$1\"" >/dev/null
  sleep 0.5
  osascript -e 'tell application "System Events" to key code 36' >/dev/null
  sleep 0.8
}
press() {
  focus
  osascript -e "tell application \"System Events\" to key code $1" >/dev/null
  sleep 0.5
}
# Escape. `/vibe list` opens VibeMod's own dialog SCREEN, and a screen swallows
# every keystroke after it - which is exactly how the first run of this script
# died: eight mods loaded, `/vibe list` opened the mod list, and every command
# from then on was typed into a dialog nobody was reading. Screenshots kept
# working (the screenshot key is handled above the screen layer), which is why
# it looked like the game was still listening.
dismiss() {
  focus
  osascript -e 'tell application "System Events" to key code 53' >/dev/null
  sleep 0.6
}
KEY_T=17
KEY_R=15
KEY_F2=120
shot() {
  press "$KEY_F2"
  sleep 1.5
  local latest
  latest="$(ls -t "$SHOTS" 2>/dev/null | head -1 || true)"
  if [[ -n "$latest" ]]; then
    echo "$1  $SHOTS/$latest" >> "$SHOT_LIST"
    echo "  screenshot [$1]: $SHOTS/$latest"
  else
    echo "$1  NONE TAKEN" >> "$SHOT_LIST"
    echo "  screenshot [$1]: NONE TAKEN"
  fi
}
await_chat() {
  local needle="$1" limit="${2:-45}"
  for _ in $(seq 1 "$limit"); do
    logs | grep -qF -- "$needle" && return 0
    sleep 1
  done
  return 1
}
# Seeds a mod's files and hot-loads it by TYPING the command, as a player would.
hotload() {
  local name="$1" main="$2" icon="$3"
  local dest="$STORE/$name"
  mkdir -p "$dest"
  cp -R "$MODS_SRC/$name/v1" "$dest/v1"
  python3 - "$dest/meta.json" "$name" "$main" "$icon" <<'META'
import json, sys, time
path, name, main, icon = sys.argv[1:5]
open(path, "w").write(json.dumps({
    "schema": 3, "platform": "fabric", "mcVersion": "26.2", "side": "both",
    "name": name, "description": "V3 stress campaign roster mod.",
    "usage": "", "manual": "## " + name, "icon": icon,
    "mainClass": "vibemod." + name.lower() + "." + main,
    "currentVersion": 1, "enabled": False, "creator": "stress",
    "versions": [{"version": 1, "prompt": "the V3 stress roster: " + name,
                  "model": "hand-written", "createdAt": int(time.time() * 1000),
                  "changelog": "First roster version.", "kind": "create",
                  "costUsd": 0.0, "requester": "stress"}],
    "config": [], "configValues": {},
}, indent=2))
META
  local at
  at="$(t)"
  say "/vibe enable $name"
  local outcome="TIMEOUT"
  for _ in $(seq 1 90); do
    if logs | grep -qF "Mod $name registered /"; then outcome="LIVE"; break; fi
    if logs | grep -qF "Refusing registry content from $name"; then outcome="REFUSED"; break; fi
    sleep 1
  done
  echo "  $name: TYPED /vibe enable at $at, $outcome at $(t)"
  echo "$name $at $(t) $outcome" >> "$RUN/hotload-timeline.txt"
}

# ------------------------------------------------------------------ launch
note "launching the real client (:fabric:runStressClient -> localhost:$PORT)"
( cd "$ROOT" && ./gradlew --console=plain -PvibemodStressClient :fabric:runStressClient > "$GRADLE_LOG" 2>&1 ) &
GRADLE_PID=$!

cleanup() {
  echo; echo "== [$(t)] closing the client and the server"
  # Only ever our own pids, and never a broad pkill on the class name: that is
  # what killed somebody else's game the first time this script ran.
  [[ -n "$CLIENT_PID" ]] && kill "$CLIENT_PID" 2>/dev/null || true
  kill "$GRADLE_PID" 2>/dev/null || true
  sleep 4
  [[ -n "$CLIENT_PID" ]] && kill -9 "$CLIENT_PID" 2>/dev/null || true
  rc stop > /dev/null 2>&1 || kill "$SERVER_PID" 2>/dev/null || true
  sleep 4
  kill -9 "$SERVER_PID" 2>/dev/null || true
}
trap cleanup EXIT

# Identified by OUR Loom run configuration, which is on the client's own command
# line. Nothing else on this machine carries it.
for i in $(seq 1 420); do
  CLIENT_PID="$(pgrep -f 'runStressClient' | head -1 || true)"
  [[ -n "$CLIENT_PID" ]] && { echo "  our client process $CLIENT_PID after ${i}s"; break; }
  if ! kill -0 "$GRADLE_PID" 2>/dev/null; then
    echo "!! gradle exited before the client started; tail:" >&2
    tail -40 "$GRADLE_LOG" >&2
    exit 1
  fi
  sleep 1
done
[[ -n "$CLIENT_PID" ]] || { echo "!! the client never started" >&2; tail -40 "$GRADLE_LOG" >&2; exit 1; }

note "waiting for the player to be in the world"
for i in $(seq 1 420); do
  grep -q 'joined the game' "$SRV_LOG" 2>/dev/null && { echo "  player joined after ${i}s"; break; }
  sleep 1
done
assert "the client quick-play joined the server with no human on a menu" \
  in_log 'joined the game'
PLAYER="$(grep -m1 -oE '[A-Za-z0-9_]+ joined the game' "$SRV_LOG" | cut -d' ' -f1 || true)"
echo "  player: ${PLAYER:-unknown}"
[[ -n "$PLAYER" ]] || { echo "!! nobody joined; server tail:" >&2; tail -30 "$SRV_LOG" >&2; exit 1; }
sleep 12
assert "VibeMod is running on the server the player is standing in" in_log 'VibeMod ready'
assert "and there really is a player attached to it" \
  test "$(rc list | grep -c "$PLAYER")" -ge 1
shot "00-in-world"

# Proved FIRST, and the script stops if it is not true. Everything below this
# line is a claim about a mod; this is the claim that the keyboard is connected
# to the game at all, and without it the rest would fail one by one for a reason
# that has nothing to do with VibeMod.
note "proving the keyboard actually reaches the game"
rc "op $PLAYER" > /dev/null
sleep 2
say "hello-from-a-real-keyboard"
if ! await_chat "<$PLAYER> hello-from-a-real-keyboard" 20; then
  echo "!! The client is in the world and F2 works, but typed text is not arriving." >&2
  echo "!! Not continuing: every assertion below would fail for this reason and" >&2
  echo "!! none of them would be about VibeMod. Server log tail:" >&2
  tail -20 "$SRV_LOG" >&2
  exit 4
fi
echo "  ok: a REAL chat line typed at the keyboard reached the server and came back"

note "settling in (typed by the player, into the chat box)"
say "/gamemode creative"
say "/effect give @s minecraft:resistance 99999 4 true"
say "/effect give @s minecraft:fire_resistance 99999 1 true"
say "/time set day"
# "Set the time to ..." is the 1.21 wording. 26.2 says "Set minecraft:overworld
# to time marker minecraft:day", which is the kind of thing only a real run tells
# you. (`/gamerule doDaylightCycle false` is gone from this script for the same
# reason: 26.2 answers it with "Incorrect argument for command".)
assert "a command TYPED at the keyboard reached the server" \
  await_chat 'to time marker minecraft:day'

# ================================================================== the roster
note "hot-loading the roster by typing /vibe enable, mod by mod, into a live world"
hotload GrapplingHook GrapplingHook FISHING_ROD
hotload ZombieTitan   ZombieTitan   ZOMBIE_HEAD
hotload ChatCraft     ChatCraft     WRITABLE_BOOK
hotload ArenaMaster   ArenaMaster   IRON_SWORD
hotload RubyEconomy   RubyEconomy   AMETHYST_SHARD
hotload SkyGrid       SkyGrid       GLASS
hotload MeteorStorm   MeteorStorm   MAGMA_BLOCK
hotload Nightmare     Nightmare     TOTEM_OF_UNDYING
sleep 8
echo
echo "  hot-load timeline (mod, typed, outcome):"
sed 's/^/    /' "$RUN/hotload-timeline.txt"
assert "every gameplay mod went live in the world the player is standing in" \
  test "$(grep -c ' LIVE$' "$RUN/hotload-timeline.txt")" -ge 8
say "/vibe list"
sleep 2
shot "01-roster-loaded"
dismiss

# ============================================================ 1. GrapplingHook
# The behaviour no console can reach: a real key press on a real item held by a
# real player, and the player physically moving because of it.
note "1. GrapplingHook - a real use-item key press, and a player who moves"
say "/give @s minecraft:fishing_rod"
# A stone shell around the player rather than a block overhead, because the
# player is NOT looking where /tp says they are: the server accepts the rotation
# and the client's very next movement packet overwrites it, so the raytrace fired
# along the pitch the person at the keyboard last had (0) and found nothing. A
# box makes the test independent of aim, which is the honest fix - the mod is
# being asked "can you hook a wall", not "can the harness point a camera".
say "/fill ~-6 ~-1 ~-6 ~6 ~5 ~6 minecraft:stone hollow"
sleep 2
shot "02-grapple-before"
GRAPPLES_BEFORE="$(count_log 'Grapple!')"
press "$KEY_R"
sleep 1
GRAPPLES_AFTER="$(count_log 'Grapple!')"
# The second press goes in IMMEDIATELY, while the 3-second cooldown is still
# running. Doing it after the chat probes below would have been a nine-second
# gap - which is what the previous run measured, and it measured nothing.
press "$KEY_R"
sleep 1
GRAPPLES_SECOND="$(count_log 'Grapple!')"
say "/data get entity @s Motion"
assert "the mod's own reply reached the player's chat" await_chat 'Grapple!'
assert "the first press really did grapple ($GRAPPLES_BEFORE -> $GRAPPLES_AFTER)" \
  test "$GRAPPLES_AFTER" -gt "$GRAPPLES_BEFORE"
assert "a second press one second later is refused by the 3s cooldown ($GRAPPLES_AFTER -> $GRAPPLES_SECOND)" \
  test "$GRAPPLES_SECOND" -eq "$GRAPPLES_AFTER"
assert "and the launch put a real velocity on the PLAYER, not a zero vector" \
  test "$(logs | grep -c 'Motion: \[0.0d, 0.0d, 0.0d\]')" -eq 0
sleep 2
shot "03-grapple-after"
say "/grapple cooldowns"
assert "the cooldown map has the real player in it" await_chat 'grapple-cooldowns tracked=1'

# ============================================================== 2. ZombieTitan
note "2. ZombieTitan - a boss bar on a real player's screen"
say "/tp @s 40 -59 40"
sleep 2
say "/titan spawn"
assert "the titan spawned next to the player" await_chat 'titan-spawned health=100.0'
sleep 3
say "/titan status"
assert "the mod put the REAL player on its boss bar" await_chat 'titan-status alive=1 bars=1'
shot "04-titan-bossbar"
say "/kill @e[tag=vibemod_zombietitan_titan]"
sleep 3
say "/titan status"
assert "the boss bar came down when it died" await_chat 'titan-status alive=0 bars=0'
assert "and AFTER_DEATH fired with a player watching" in_log 'titan-slain'
shot "05-titan-slain"

# ================================================================ 3. ChatCraft
# A REAL signed player chat message - the one path RCON structurally cannot
# produce. Typed with no leading slash, so it goes through the chat pipeline.
note "3. ChatCraft - a real player chat line, cancelled, with the item handed over"
say "/clear @s"
sleep 1
say "craft me a diamond"
sleep 3
assert "the mod answered the chat line" await_chat 'One diamond, coming up.'
assert "and the chat line itself was CANCELLED, never echoed to the room" \
  not_in_log "<$PLAYER> craft me a diamond"
say "/clear @s minecraft:diamond 0"
assert "the diamond really arrived in the player's inventory" await_chat 'Found 1 matching item'
shot "06-chatcraft"
say "craft me a unicorn"
sleep 3
assert "a made-up item is a polite miss, not a crash" await_chat 'Never heard of a unicorn.'
assert "and that line was cancelled too" not_in_log "<$PLAYER> craft me a unicorn"

# =============================================================== 4. ArenaMaster
note "4. ArenaMaster - waves around a real player, over real ticks"
say "/tp @s 0 -59 0"
sleep 2
say "/arena create pit 6"
assert "the arena was created where the player is standing" await_chat 'arena-created pit radius=6'
say "/arena start pit 3"
assert "it started" await_chat 'arena-started pit waves=3'
for _ in $(seq 1 60); do
  test "$(count_log 'arena-wave')" -ge 3 && break
  sleep 1
done
assert "three waves spawned around the player, escalating" test "$(count_log 'arena-wave')" -ge 3
shot "07-arena-waves"
say "/kill @e[tag=vibemod_arenamaster_mob,limit=3]"
sleep 3
say "/scoreboard objectives setdisplay sidebar arena_kills"
sleep 3
shot "08-arena-scoreboard"
say "/arena stop"
assert "the tag sweep cleaned up" await_chat 'arena-stopped swept='

# =============================================================== 5. RubyEconomy
note "5. RubyEconomy - a component-bearing recipe result, held by a real player"
say "/econ verify"
assert "both recipes are live on the server the player is on" await_chat 'ruby=true blade=true'
assert "and the game assembled the blade with both modifiers" await_chat 'bladeModifiers=2'
say "/give @s minecraft:amethyst_shard 3"
say "/give @s minecraft:stick 1"
sleep 2
shot "09-ruby-ingredients"
say "/econ grant $PLAYER 25"
say "/balance $PLAYER"
assert "the balance ledger works for a real named player" await_chat 'rubies'
undrivable "opening a crafting table and dragging the recipe (System Events has no mouse buttons)"
assert "the server SAYS its assets are inert here, rather than pretending" \
  in_log 'this host has no client resource pack'

# =================================================================== 6. SkyGrid
note "6. SkyGrid - a grid built above a real player, batched under the watchdog"
say "/tp @s 0 108 0"
sleep 2
say "/skygrid 16"
assert "the build queued" await_chat 'skygrid-queued nodes='
for _ in $(seq 1 60); do
  in_log 'skygrid-done' && break
  sleep 1
done
assert "the batched build finished while the player watched" in_log 'skygrid-done placed='
say "/tp @s ~ ~ ~ 0 20"
sleep 3
shot "10-skygrid"
assert "the watchdog never tripped on the batched build" not_in_log 'SkyGrid was auto-disabled'
say "/execute if block 0 100 0 minecraft:coal_ore"
assert "the exact block the mod's hash picks is really there" await_chat 'Test passed'

# =============================================================== 7. MeteorStorm
note "7. MeteorStorm - a meteor aimed at the player, landing where they can see it"
say "/tp @s 0 -59 0"
sleep 3
say "/meteor now"
assert "a meteor was launched at the player's own position" await_chat 'meteor-spawned'
shot "11-meteor-incoming"
for _ in $(seq 1 40); do
  in_log 'meteor-impact' && break
  sleep 1
done
assert "it flew and landed" in_log 'meteor-impact'
sleep 3
shot "12-meteor-crater"
note "7b. waiting out one unaided 600-tick cycle in the running world"
AUTO="$(count_log 'meteor-impact')"
AUTO_T0=$(date +%s)
for _ in $(seq 1 90); do
  test "$(count_log 'meteor-impact')" -gt "$AUTO" && break
  sleep 1
done
assert "the mod's own timer dropped one on the player with nobody asking (after $(( $(date +%s) - AUTO_T0 ))s)" \
  test "$(count_log 'meteor-impact')" -gt "$AUTO"

# ================================================================= 8. Nightmare
note "8. Nightmare - real night, real damage scaling, and a mid-session round trip"
say "/time set night"
sleep 4
say "/nightmare"
assert "the mod reads the real world's darkness" await_chat 'dark=true'
shot "13-nightmare-night"
say "/summon minecraft:cow 3 -59 3 {Tags:[\\\"nmcow\\\"],NoAI:1b,NoGravity:1b}"
sleep 2
say "/damage @e[tag=nmcow,limit=1] 4 minecraft:generic"
sleep 2
say "/nightmare"
assert "AFTER_DAMAGE fired in the live world" await_chat 'scaled=1'
say "/data get entity @e[tag=nmcow,limit=1] Health"
assert "and the doubled hit really landed (10hp cow, hit for 4 -> 2.0)" await_chat '2.0f'
note "8b. the disable / enable round trip, typed, while the player keeps playing"
say "/vibe disable Nightmare"
sleep 4
say "/nightmare"
assert "disabling took the command out of the dispatcher the player is using" \
  await_chat 'Unknown or incomplete command'
say "/vibe enable Nightmare"
sleep 10
say "/nightmare"
assert "re-enabling put it back with no reconnect and no /reload" await_chat 'nightmare-status'
shot "14-nightmare-roundtrip"
say "/vibe delete Nightmare confirm"
sleep 5
say "/nightmare"
assert "unloading removed it for good" test "$(count_log 'Unknown or incomplete command')" -ge 2

# ====================================================================== wrap
note "the session survived all of it"
say "/vibe list"
sleep 2
shot "15-final"
dismiss
assert "the client is still running" kill -0 "$CLIENT_PID"
assert "the player is still connected" test "$(rc list | grep -c "$PLAYER")" -ge 1
assert "nothing crashed the render thread" not_in_log 'Render thread/FATAL'
assert "the client never disconnected" not_in_log 'Disconnected'
UPTIME=$(( $(date +%s) - STARTED_AT ))

undrivable "a mouse right-click specifically (System Events sends keystrokes, not mouse buttons; use-item was rebound to R, which reaches the same UseItemCallback)"
undrivable "a desktop screencapture (Screen Recording is denied to this terminal: 'could not create image from display'). The game's own F2 screenshots are used instead."
undrivable "client-side assets/** and runtime registry content on this run: the player is on a DEDICATED server, where both are refused by design. :fabric:runClientGameTest covers them."

echo
echo "=================================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "== V3 STRESS CAMPAIGN, REAL CLIENT: all assertions held"
else
  echo "!! V3 STRESS CAMPAIGN, REAL CLIENT: $FAILURES ASSERTION(S) FAILED"
fi
echo "   one continuous play session, ${UPTIME}s with a player in the world"
echo "   behaviours undrivable even here: $UNDRIVABLE"
echo "   screenshots:"
sed 's/^/     /' "$SHOT_LIST" 2>/dev/null || echo "     (none)"
echo "   client log: $CLIENT_LOG"
echo "   server log: $SRV_LOG"
echo "=================================================================="
[[ "$FAILURES" -eq 0 ]] || exit 1
