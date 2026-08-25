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
RUN="$ROOT/paper/run/smoke-$VERSION"
JAR="$ROOT/paper/build/libs/VibeMod.jar"
RCON_PORT=25585
RCON_PASSWORD="vibemod-smoke"
BOOT_TIMEOUT=420

if [[ ! -f "$JAR" ]]; then
  echo "!! $JAR missing - run ./gradlew :paper:jar first" >&2
  exit 1
fi

# ---------------------------------------------------------------- paper jar
CACHE="$ROOT/paper/run/.paper-cache"
mkdir -p "$CACHE"
PAPER_JAR="$CACHE/paper-$VERSION.jar"
if [[ ! -f "$PAPER_JAR" ]]; then
  echo "== downloading Paper $VERSION"
  URL="$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/$VERSION/builds/latest" \
    -H 'User-Agent: vibemod-smoke/1.0' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["downloads"]["server:default"]["url"])')"
  curl -fsSL "$URL" -o "$PAPER_JAR.part"
  mv "$PAPER_JAR.part" "$PAPER_JAR"
fi
echo "== paper jar: $PAPER_JAR"

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
java -Xms1G -Xmx2G "-Xlog:class+load=info:file=$RUN/classload.log" \
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
BOT_SUPPORTS_VERSION=0
if [[ -d "$ROOT/scripts/node_modules/mineflayer" ]] \
  && (cd "$ROOT/scripts" \
      && node -e "if (!require('minecraft-data')('$VERSION')) process.exit(1)" 2>/dev/null); then
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
  if node "$ROOT/scripts/smoke-bot.js" "$VERSION" 127.0.0.1 25565 $BOT_MODE | tee "$RUN/bot.log"; then
    echo "== bot phase passed"
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
