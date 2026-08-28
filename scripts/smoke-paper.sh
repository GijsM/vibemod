#!/usr/bin/env bash
# Usage: scripts/smoke-paper.sh <mc-version> [--force-chat]
#
# Boots a throwaway Paper server of <mc-version> with the freshly built
# VibeMod.jar and a pre-seeded canned mod, then drives it over RCON and asserts
# on the results. The Phase C acceptance gate (ARCHITECTURE-V2 §9) in one
# command, for any version between the 1.20.6 floor and the newest supported
# line.
#
# The canned mod exists so the gate needs no LLM and no API key: VibeMod's
# restore-on-boot path compiles and hot-loads whatever is in the store, so a mod
# written straight to disk exercises compile -> load -> command -> unload exactly
# as a generated one would.
#
# Everything lives under paper/run/smoke-<version>/, which is git-ignored
# runtime state, and is deleted and recreated on every run.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:?usage: smoke-paper.sh <mc-version> [--force-chat]}"
FORCE_CHAT="${2:-}"
# SMOKE_LABEL names the run directory, so a Paper fork tested at the same
# Minecraft version does not overwrite the Paper run it is being compared with.
# VERSION stays the MINECRAFT version either way - the mineflayer bot needs it.
LABEL="${SMOKE_LABEL:-$VERSION}"
RUN="$ROOT/paper/run/smoke-$LABEL"
JAR="$ROOT/paper/build/libs/VibeMod.jar"
RCON_PORT=25585
RCON_PASSWORD="vibemod-smoke"
BOOT_TIMEOUT=420

if [[ ! -f "$JAR" ]]; then
  # shadowJar, not jar: since the shadow plugin arrived `jar` builds a thin,
  # bStats-less artifact that is NOT the shipped VibeMod.jar this gate needs.
  echo "!! $JAR missing - run ./gradlew :paper:shadowJar first" >&2
  exit 1
fi

# ---------------------------------------------------------------- server jar
# SMOKE_SERVER_JAR points the gate at an already-downloaded jar instead of
# asking Fill for a Paper build, which is what lets the same canary protocol run
# against a Paper FORK (Purpur, Folia, ...). Everything downstream is Bukkit API,
# so nothing else in this script needs to know the difference.
CACHE="$ROOT/paper/run/.paper-cache"
mkdir -p "$CACHE"
if [[ -n "${SMOKE_SERVER_JAR:-}" ]]; then
  PAPER_JAR="$SMOKE_SERVER_JAR"
  if [[ ! -f "$PAPER_JAR" ]]; then
    echo "!! SMOKE_SERVER_JAR=$PAPER_JAR does not exist" >&2
    exit 1
  fi
else
  PAPER_JAR="$CACHE/paper-$VERSION.jar"
  if [[ ! -f "$PAPER_JAR" ]]; then
    echo "== downloading Paper $VERSION"
    URL="$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/$VERSION/builds/latest" \
      -H 'User-Agent: vibemod-smoke/1.0' \
      | python3 -c 'import json,sys; print(json.load(sys.stdin)["downloads"]["server:default"]["url"])')"
    curl -fsSL "$URL" -o "$PAPER_JAR.part"
    mv "$PAPER_JAR.part" "$PAPER_JAR"
  fi
fi
echo "== server jar: $PAPER_JAR"

# ---------------------------------------------------------------- run dir
rm -rf "$RUN"
mkdir -p "$RUN/plugins/VibeMod/mods/SmokeCanary/v1"
cp "$JAR" "$RUN/plugins/VibeMod.jar"
echo "eula=true" > "$RUN/eula.txt"

cat > "$RUN/server.properties" <<PROPS
online-mode=false
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASSWORD
level-type=minecraft\:flat
level-seed=1
spawn-protection=0
max-players=5
view-distance=4
simulation-distance=4
sync-chunk-writes=false
enable-command-block=false
motd=VibeMod Phase C smoke ($VERSION)
PROPS

# The plugin writes its own defaults on first enable, but ui.force-chat has to be
# there BEFORE onEnable picks a renderer, so seed the file when asked.
if [[ "$FORCE_CHAT" == "--force-chat" ]]; then
  cat > "$RUN/plugins/VibeMod/config.yml" <<'CFG'
ui:
  force-chat: true
CFG
fi

# ---------------------------------------------------------------- canned mod
cat > "$RUN/plugins/VibeMod/mods/SmokeCanary/v1/SmokeCanary.java" <<'MOD'
package vibemod.smokecanary;

import com.gijsm.vibemod.api.Mod;
import com.gijsm.vibemod.api.VibeContext;

/**
 * The Phase C smoke canary. Registers one top-level command, one action and one
 * listener so a single mod exercises every registration path the host tracks,
 * and reads its knob live so the config plumbing is covered too.
 */
public final class SmokeCanary implements Mod {

    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        ctx.command("smokeping", "Phase C smoke canary", (sender, args) ->
                sender.sendMessage("smoke-pong " + ctx.configString("greeting")));
        ctx.action("ping", (sender, args) ->
                sender.sendMessage("smoke-action " + ctx.configInt("count")));
        ctx.listen(new SmokeListener(ctx));
        ctx.repeat(20L, 20L, () -> { });
        ctx.log().info("SmokeCanary enabled");
    }

    @Override
    public void onDisable(VibeContext ctx) {
        ctx.log().info("SmokeCanary disabled");
    }
}
MOD

cat > "$RUN/plugins/VibeMod/mods/SmokeCanary/v1/SmokeListener.java" <<'LST'
package vibemod.smokecanary;

import com.gijsm.vibemod.api.VibeContext;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** One listener, so the event bridge's registration and teardown are covered. */
public final class SmokeListener implements Listener {

    private final VibeContext ctx;

    public SmokeListener(VibeContext ctx) {
        this.ctx = ctx;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        ctx.log().info("SmokeCanary saw " + event.getPlayer().getName() + " join");
    }
}
LST

python3 - "$RUN/plugins/VibeMod/mods/SmokeCanary/meta.json" <<'META'
import json, sys, time
meta = {
    "name": "SmokeCanary",
    "description": "Phase C smoke canary: one command, one action, one listener, one task.",
    "usage": "/smokeping",
    "manual": "## SmokeCanary\n\nA **canary** for the Phase C acceptance gate.\n\n### Settings\n\n- `greeting` - what /smokeping echoes back (default `hi`).\n- `count` - what the ping action echoes back (default **7**).",
    "icon": "FEATHER",
    # meta.json stores the FQCN, exactly as ModGenerator resolves it before saving.
    "mainClass": "vibemod.smokecanary.SmokeCanary",
    "currentVersion": 1,
    "enabled": True,
    "creator": "smoke",
    "versions": [{
        "version": 1,
        "prompt": "a canary for the phase C smoke gate",
        "model": "none",
        "createdAt": int(time.time() * 1000),
        "changelog": "First canary.",
        "kind": "create",
        "costUsd": 0.0,
        "requester": "smoke",
    }],
    # "def", not "default": the LLM's output contract says "default", but the
    # persisted ConfigKnob record component is `def`, and Gson writes that.
    "config": [
        {"key": "greeting", "type": "text", "def": "hi", "description": "What /smokeping echoes back."},
        {"key": "count", "type": "integer", "def": "7", "description": "What the ping action echoes back.",
         "min": 1.0, "max": 10.0, "step": 1.0},
    ],
    "configValues": {},
}
open(sys.argv[1], "w").write(json.dumps(meta, indent=2))
META

# ---------------------------------------------------------------- boot
LOG="$RUN/boot.log"
echo "== booting Paper $VERSION (log: $LOG)"
cd "$RUN"
# Class-load logging goes to its own file so the gate can prove the dialog API
# classes are never even LOADED on a server that has no dialog API, without
# drowning the server log.
# JAVA_HOME wins when set, so one machine can gate an old Paper line on the JDK
# that line actually supports. Without it every run inherits whatever `java` is
# on PATH, and a server that simply cannot start on that JDK looks like a
# VibeMod failure - Paper 1.21 is the case in point: its bundled spark ships an
# async-profiler native library that SIGSEGVs the moment it profiles a JDK 25.
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -Xms1G -Xmx2G "-Xlog:class+load=info:file=$RUN/classload.log" \
  -jar "$PAPER_JAR" --nogui > "$LOG" 2>&1 < /dev/null &
SERVER_PID=$!

cleanup() {
  if kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "== stopping server (pid $SERVER_PID)"
    "$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" stop > /dev/null 2>&1 || kill "$SERVER_PID"
    for _ in $(seq 1 60); do
      kill -0 "$SERVER_PID" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$SERVER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

for i in $(seq 1 "$BOOT_TIMEOUT"); do
  if grep -q 'Done (' "$LOG" 2>/dev/null; then
    echo "== booted after ${i}s"
    break
  fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "!! server died during boot; tail:" >&2
    tail -40 "$LOG" >&2
    exit 1
  fi
  sleep 1
done

if ! grep -q 'Done (' "$LOG"; then
  echo "!! server did not finish booting within ${BOOT_TIMEOUT}s; tail:" >&2
  tail -40 "$LOG" >&2
  exit 1
fi

# Restore-on-boot compiles asynchronously and reports when the mod is actually
# live, so "Done (" is not the same thing as "the canary is running". Waiting for
# the plugin's own confirmation is the only race-free signal.
for i in $(seq 1 120); do
  if grep -q 'SmokeCanary v1 is live' "$LOG" 2>/dev/null; then
    echo "== canary live after ${i}s"
    break
  fi
  sleep 1
done
if ! grep -q 'SmokeCanary v1 is live' "$LOG"; then
  echo "!! the canned mod never went live; VibeMod lines:" >&2
  grep 'VibeMod\]' "$LOG" >&2 || true
  exit 1
fi

echo "== driving over RCON"
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" \
  "vibe settings" \
  "vibe list" \
  "vibe info SmokeCanary" \
  "smokeping" \
  "vibe do SmokeCanary ping" \
  "vibe set SmokeCanary greeting howdy" \
  "smokeping" \
  "vibe manual SmokeCanary" \
  "vibe source SmokeCanary" \
  "vibe costs" \
  "vibe disable SmokeCanary" \
  "smokeping" \
  "vibe enable SmokeCanary" \
  | tee "$RUN/rcon.log"

# ---------------------------------------------------------------- player phase
# A Screen only renders for a player, so the chat UI cannot be gated over RCON.
# When mineflayer is installed and it speaks this protocol, join as a headless
# player and drive the real chat renderer: click a browser row, flip a toggle,
# type into a captured input.
# The guard is an EXACT membership test, not `minecraft-data($VERSION)`. That
# call resolves loosely: asked for 1.21.7 it hands back the data for 1.21, and
# asked for 26.1.2 it hands back 26.1 - truthy both times, so the old check said
# "the bot speaks this" and the bot then died mid-handshake against a protocol
# it had never seen ("This server is version 1.21.7, you are using version
# 1.21"). Membership in supportedVersions.pc is the question actually being
# asked, and it is the one that distinguishes 1.21.8 (present) from 1.21.7 (not).
BOT_SUPPORTS_VERSION=0
if [[ -d "$ROOT/scripts/node_modules/mineflayer" ]] \
  && (cd "$ROOT/scripts" \
      && node -e "process.exit(require('minecraft-data').supportedVersions.pc.includes('$VERSION') ? 0 : 1)" 2>/dev/null); then
  BOT_SUPPORTS_VERSION=1
fi

if [[ "$BOT_SUPPORTS_VERSION" == "1" ]]; then
  echo "== joining as a headless player to drive the UI"
  "$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "op SmokeBot" > /dev/null
  # Which UI the bot should expect is the plugin's own answer, read back out of
  # its boot line - so the gate cannot disagree with the capability probe.
  BOT_MODE=""
  if grep -q 'UI: native dialogs' "$LOG"; then
    BOT_MODE="--dialogs"
  fi
  # 2>&1, so the transcript holds the failure too: mineflayer reports an
  # unsupported protocol by THROWING, which went to stderr and left the bot.log
  # the error message points at completely empty.
  if node "$ROOT/scripts/smoke-bot.js" "$VERSION" 127.0.0.1 25565 $BOT_MODE 2>&1 | tee "$RUN/bot.log"; then
    echo "== bot phase passed"
  elif grep -qE "is not supported\. Latest supported version|please specify the correct version" "$RUN/bot.log"; then
    # The guard above asked minecraft-data and got a yes, but mineflayer's
    # protocol loader has its own, shorter list and rejects the server's ping
    # version. 26.1 is the case in point: minecraft-data ships a 26.1 entry, so
    # every version check that can be made BEFORE connecting says "supported",
    # while mineflayer still throws "Latest supported version is 1.21.11". The
    # only reliable signal is the throw itself, so it counts as the same skip
    # the guard would have taken - not as a VibeMod failure.
    echo "== skipping the player phase (mineflayer cannot speak $VERSION after all)"
  else
    echo "!! bot phase FAILED (transcript: $RUN/bot.log)" >&2
    BOT_FAILED=1
  fi
else
  echo "== skipping the player phase (no mineflayer, or it does not speak $VERSION)"
fi

cleanup
trap - EXIT
echo "== server stopped; log at $LOG, rcon transcript at $RUN/rcon.log"
if [[ "${BOT_FAILED:-0}" == "1" ]]; then
  exit 1
fi
