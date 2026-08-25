#!/usr/bin/env bash
# Usage: scripts/smoke-neoforge.sh
#
# The Phase E dedicated-server acceptance gate (ARCHITECTURE-V2 §9) in one
# command, and the exact twin of scripts/smoke-fabric.sh. It runs NeoForge's own
# installer to build a real server beside the repo, installs the freshly built
# VibeMod jar next to a pre-seeded canned mod, boots it, drives it over RCON, and
# ASSERTS on every reply.
#
# Three things make this a real gate rather than a boot check.
#
# The canned mod is a mod-flavor source written straight to the store, so
# restore-on-boot compiles and hot-loads it exactly as a generated one would —
# no LLM, no API key, and the whole compile -> load -> command -> config -> unload
# path is exercised. It uses a curated ctx.on* hook, a top-level command, an
# action, a task and a live config knob, so one canary covers every registration
# path the host tracks.
#
# It is BYTE-IDENTICAL to the Fabric gate's canary, and that is an assertion in
# itself: the sdk mod flavor is loader-neutral (§10.4), so the same source has to
# compile and run on both loaders. If it ever stops doing so, that claim is
# wrong and one of these two gates says so.
#
# And it runs against the INSTALLED jar, not ModDevGradle's dev classpath. That
# is the only way to prove the Jar-in-Jar half works: on the dev classpath
# Adventure and ECJ are plain classpath entries, while in the shipped jar they
# are nested under META-INF/jarjar and have to be found through FML and
# materialized by the cpcache before javac can read them.
#
# Everything lives under neoforge/smoke/, which is git-ignored runtime state, and
# is deleted and recreated on every run (the downloaded installer and the
# installer's own libraries/ tree are cached in neoforge/smoke-cache/, because
# re-downloading ~200MB per run is not a gate, it is a tax).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="$ROOT/neoforge/smoke"
CACHE="$ROOT/neoforge/smoke-cache"
RCON_PORT=25587
RCON_PASSWORD="vibemod-smoke"
BOOT_TIMEOUT=600

prop() { grep -E "^$1=" "$ROOT/gradle.properties" | cut -d= -f2- ; }
MC_VERSION="$(prop minecraftVersion)"
NEO_VERSION="$(prop neoforgeVersion)"

JAR="$(ls "$ROOT"/neoforge/build/libs/vibemod-neoforge-*.jar 2>/dev/null | grep -v sources | head -1 || true)"
if [[ -z "$JAR" ]]; then
  echo "!! no vibemod-neoforge jar - run ./gradlew :neoforge:build first" >&2
  exit 1
fi

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

# ------------------------------------------------------------------ install
# The installer builds a whole server tree (libraries/, the vanilla server jar,
# the arg files FML is launched through). It is cached and rsync'd rather than
# re-run, because it downloads ~200MB and the gate must be cheap enough to run
# on every change.
mkdir -p "$CACHE"
INSTALLER="$CACHE/neoforge-$NEO_VERSION-installer.jar"
if [[ ! -f "$INSTALLER" ]]; then
  note "downloading the NeoForge installer ($NEO_VERSION, MC $MC_VERSION)"
  curl -fsSL \
    "https://maven.neoforged.net/releases/net/neoforged/neoforge/$NEO_VERSION/neoforge-$NEO_VERSION-installer.jar" \
    -o "$INSTALLER.part"
  mv "$INSTALLER.part" "$INSTALLER"
fi

TEMPLATE="$CACHE/server-$NEO_VERSION"
if [[ ! -f "$TEMPLATE/.installed" ]]; then
  note "running the NeoForge installer into $TEMPLATE (this downloads the game + libraries)"
  rm -rf "$TEMPLATE"
  mkdir -p "$TEMPLATE"
  ( cd "$TEMPLATE" && java -jar "$INSTALLER" --installServer . > install.log 2>&1 ) || {
    echo "!! installer failed; tail:" >&2
    tail -40 "$TEMPLATE/install.log" >&2
    exit 1
  }
  touch "$TEMPLATE/.installed"
fi

ARGS_FILE="libraries/net/neoforged/neoforge/$NEO_VERSION/unix_args.txt"
if [[ ! -f "$TEMPLATE/$ARGS_FILE" ]]; then
  echo "!! no $ARGS_FILE in the installed server; the installer layout changed" >&2
  ls "$TEMPLATE" >&2
  exit 1
fi

# ------------------------------------------------------------------- run dir
rm -rf "$RUN"
mkdir -p "$RUN"
# The installed tree is the template; copy rather than reinstall.
( cd "$TEMPLATE" && tar cf - libraries ) | ( cd "$RUN" && tar xf - )
mkdir -p "$RUN/mods" "$RUN/vibemod/mods/SmokeCanary/v1"
cp "$JAR" "$RUN/mods/"
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
motd=VibeMod Phase E smoke ($MC_VERSION)
PROPS

# ---------------------------------------------------------------- canned mod
# Mod flavor: Mojang-typed, a curated hook, no Bukkit and no NeoForge anywhere.
# This is exactly what the neoforge prompt profile teaches a model to write —
# and it is the same file the Fabric gate uses, deliberately.
cat > "$RUN/vibemod/mods/SmokeCanary/v1/SmokeCanary.java" <<'MOD'
package vibemod.smokecanary;

import com.gijsm.vibemod.api.Mod;
import com.gijsm.vibemod.api.VibeContext;

import net.minecraft.network.chat.Component;

/**
 * The Phase E smoke canary. One top-level command, one action, one curated
 * server hook, one task and one live config knob, so a single mod exercises
 * every registration path the NeoForge host tracks.
 *
 * <p>Byte-identical to the Fabric gate's canary: the sdk mod flavor is
 * loader-neutral, and this is where that claim is checked.
 */
public final class SmokeCanary implements Mod {

    private final Counter counter = new Counter();

    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        ctx.command("smokeping", "Phase E smoke canary", (src, args) ->
                src.sendSystemMessage(Component.literal("smoke-pong " + ctx.configString("greeting"))));
        ctx.action("ping", (src, args) ->
                src.sendSystemMessage(Component.literal("smoke-action " + ctx.configInt("count"))));
        ctx.onBlockBreak((player, pos, state) -> {
            counter.bump();
            return true;
        });
        ctx.onServerTick(server -> { });
        ctx.repeat(20L, 20L, () -> { });
        // Client features are declared but must be inert here: this is a
        // dedicated server, so ctx.client(...) is a no-op and the mod still runs.
        ctx.client(client -> client.hud("never-on-a-server", (canvas, delta) -> { }));
        ctx.log().info("SmokeCanary enabled (hasClient=" + ctx.hasClient() + ")");
    }

    @Override
    public void onDisable(VibeContext ctx) {
        ctx.log().info("SmokeCanary disabled after " + counter.value() + " breaks");
    }
}
MOD

cat > "$RUN/vibemod/mods/SmokeCanary/v1/Counter.java" <<'CNT'
package vibemod.smokecanary;

import java.util.concurrent.atomic.AtomicLong;

/** A second file, so multi-file compilation is covered too. */
public final class Counter {

    private final AtomicLong count = new AtomicLong();

    public void bump() {
        count.incrementAndGet();
    }

    public long value() {
        return count.get();
    }
}
CNT

python3 - "$RUN/vibemod/mods/SmokeCanary/meta.json" "$MC_VERSION" <<'META'
import json, sys, time
meta = {
    # meta.json v3 (ARCHITECTURE-V2 5): stamped for neoforge, so restore-on-boot
    # accepts it. A mod stamped "paper" here would be skipped, which the gate
    # asserts separately below.
    "schema": 3,
    "platform": "neoforge",
    "mcVersion": sys.argv[2],
    "side": "both",
    "name": "SmokeCanary",
    "description": "Phase E smoke canary: one command, one action, one hook, one task.",
    "usage": "/smokeping",
    "manual": "## SmokeCanary\n\nA **canary** for the Phase E acceptance gate.\n\n### Settings\n\n- `greeting` - what /smokeping echoes back (default `hi`).\n- `count` - what the ping action echoes back (default **7**).",
    "icon": "FEATHER",
    "mainClass": "vibemod.smokecanary.SmokeCanary",
    "currentVersion": 1,
    "enabled": True,
    "creator": "smoke",
    "versions": [{
        "version": 1,
        "prompt": "a canary for the phase E smoke gate",
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

# ------------------------------------------------- the wrong-loader-API canary
# V3 Phase 0 gave Fabric bytecode seams for `Event.register`; NeoForge has none
# yet. So a mod written against the Fabric API is a mod this host cannot honour,
# and the requirement is that it says so CLEARLY rather than crashing or half-
# loading. Stamped for neoforge on purpose, so restore-on-boot really attempts
# it and the refusal is measured on the boot log rather than assumed.
mkdir -p "$RUN/vibemod/mods/FabricOnNeo/v1"
cat > "$RUN/vibemod/mods/FabricOnNeo/v1/FabricOnNeo.java" <<'FABRICONNEO'
package vibemod.fabriconneo;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/** A Fabric mod on the wrong loader. Must be refused with a clear diagnostic. */
public final class FabricOnNeo implements ModInitializer {

    private int ticks;

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> ticks++);
    }
}
FABRICONNEO

python3 - "$RUN/vibemod/mods/FabricOnNeo/meta.json" "$MC_VERSION" <<'META3'
import json, sys, time
open(sys.argv[1], "w").write(json.dumps({
    "schema": 3, "platform": "neoforge", "mcVersion": sys.argv[2], "side": "server",
    "name": "FabricOnNeo",
    "description": "A Fabric-API mod on NeoForge: must be refused, not crash.",
    "usage": "", "manual": "", "icon": "STONE",
    "mainClass": "vibemod.fabriconneo.FabricOnNeo",
    "currentVersion": 1, "enabled": True, "creator": "smoke",
    "versions": [{"version": 1, "prompt": "p", "model": "none",
                  "createdAt": int(time.time() * 1000), "changelog": "", "kind": "create",
                  "costUsd": 0.0, "requester": "smoke"}],
    "config": [], "configValues": {},
}, indent=2))
META3

# A second mod, stamped for the WRONG platform, so the §5 refusal is gated too.
mkdir -p "$RUN/vibemod/mods/WrongPlatform/v1"
cat > "$RUN/vibemod/mods/WrongPlatform/v1/WrongPlatform.java" <<'WRONG'
package vibemod.wrongplatform;

import com.gijsm.vibemod.api.Mod;
import com.gijsm.vibemod.api.VibeContext;
import org.bukkit.Bukkit;

/** Deliberately Bukkit-typed: this must never be compiled on a NeoForge host. */
public final class WrongPlatform implements Mod {
    @Override
    public void onEnable(VibeContext ctx) {
        Bukkit.getLogger().info("should never run");
    }
}
WRONG

python3 - "$RUN/vibemod/mods/WrongPlatform/meta.json" <<'META2'
import json, sys, time
open(sys.argv[1], "w").write(json.dumps({
    "schema": 3, "platform": "paper", "mcVersion": "1.21.8", "side": "server",
    "name": "WrongPlatform", "description": "A Paper mod that must not load here.",
    "usage": "", "manual": "", "icon": "STONE",
    "mainClass": "vibemod.wrongplatform.WrongPlatform",
    "currentVersion": 1, "enabled": True, "creator": "smoke",
    "versions": [{"version": 1, "prompt": "p", "model": "none",
                  "createdAt": int(time.time() * 1000), "changelog": "", "kind": "create",
                  "costUsd": 0.0, "requester": "smoke"}],
    "config": [], "configValues": {},
}, indent=2))
META2

# ------------------------------------------------------------------ boot
LOG="$RUN/boot.log"
note "booting NeoForge $NEO_VERSION / MC $MC_VERSION (log: $LOG)"
cd "$RUN"
java -Xms1G -Xmx2G "@$ARGS_FILE" nogui > "$LOG" 2>&1 < /dev/null &
SERVER_PID=$!

cleanup() {
  if kill -0 "$SERVER_PID" 2>/dev/null; then
    note "stopping server (pid $SERVER_PID)"
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
  grep -q 'Done (' "$LOG" 2>/dev/null && { note "booted after ${i}s"; break; }
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "!! server died during boot; tail:" >&2
    tail -60 "$LOG" >&2
    exit 1
  fi
  sleep 1
done
grep -q 'Done (' "$LOG" || { echo "!! server never booted; tail:" >&2; tail -60 "$LOG" >&2; exit 1; }

# Restore-on-boot compiles asynchronously and reports when the mod is actually
# live, so "Done (" is not the same thing as "the canary is running".
for i in $(seq 1 180); do
  grep -q 'SmokeCanary v1 is live' "$LOG" 2>/dev/null && { note "canary live after ${i}s"; break; }
  sleep 1
done

# ------------------------------------------------------------------ asserts
note "asserting on the boot log"
assert "the host initialised" in_file "$LOG" 'VibeMod ready'
assert "the platform probe says neoforge with dialogs" in_file "$LOG" 'Platform: neoforge'
assert "the neoforge prompt profile was selected" in_file "$LOG" 'prompt profile NeoForge'
assert "a compiler backend resolved" in_file "$LOG" 'Compiler backend:'
# ARCHITECTURE-V2 §10.3 asked Phase E to answer maxTargetRelease() empirically.
# This is the answer, measured on the server that is running, not reasoned.
assert "the hot-load class-file ceiling is the JVM's own version" \
  in_file "$LOG" 'load through BytesClassLoader'
assert "generated mods target the running JVM's release" in_file "$LOG" 'target=java25'
assert "the canned mod compiled and hot-loaded" in_file "$LOG" 'SmokeCanary v1 is live'
assert "the mod's own onEnable ran" in_file "$LOG" 'SmokeCanary enabled'
assert "ctx.client() was inert on a dedicated server" in_file "$LOG" 'hasClient=false'
assert "native dialogs were chosen as the renderer" in_file "$LOG" 'UI: native dialogs'
assert "the foreign-platform mod was skipped, not compiled" \
  in_file "$LOG" 'Skipping mod WrongPlatform: generated for paper'
assert "nothing threw during boot" not_in_file "$LOG" 'Exception in thread'
assert "no mod-loading issue was reported" not_in_file "$LOG" 'ModLoadingException'
# V3 Phase 0: a Fabric-API mod on NeoForge is refused at COMPILE time, with a
# diagnostic that names what is missing — not a crash, not a silent skip, and
# not a mod that loads and then does nothing. (The surgeon's own
# "Fabric API seams are not available on NeoForge yet" denial covers the case
# where those classes ARE on the classpath; on a real server javac gets there
# first, which is the better error anyway. Both are asserted — this one here,
# the policy one in :fabric:surgeonSelfTest.)
assert "a Fabric-API mod on NeoForge is refused with a compile diagnostic" \
  in_file "$LOG" 'Stored version failed to compile'
assert "and the diagnostic names the API that is not here" in_file "$LOG" 'net.fabricmc'
assert "the refusal did not stop the other mods loading" in_file "$LOG" 'SmokeCanary v1 is live'

note "driving over RCON"
RCON="$RUN/rcon.log"
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" \
  "vibe settings" \
  "vibe list" \
  "vibe info SmokeCanary" \
  "smokeping" \
  "vibe do SmokeCanary ping" \
  "vibe set SmokeCanary greeting howdy" \
  "smokeping" \
  "vibe set SmokeCanary count 3" \
  "vibe do SmokeCanary ping" \
  "vibe manual SmokeCanary" \
  "vibe source SmokeCanary" \
  "vibe errors SmokeCanary" \
  "vibe costs" \
  "vibe enable WrongPlatform" \
  "vibe disable SmokeCanary" \
  "smokeping" \
  "vibe enable SmokeCanary" \
  "smokeping" \
  "vibe help" \
  | tee "$RCON"

note "asserting on the RCON transcript"
assert "/vibe settings reports the neoforge platform" in_file "$RCON" "platform: neoforge $MC_VERSION"
assert "/vibe settings reports dialogs are available" in_file "$RCON" 'dialogs=true'
assert "/vibe list shows the canary" in_file "$RCON" 'SmokeCanary'
assert "the mod's own top-level command works" in_file "$RCON" 'smoke-pong hi'
assert "the mod's action works through /vibe do" in_file "$RCON" 'smoke-action 7'
assert "a config knob change applies live (text)" in_file "$RCON" 'smoke-pong howdy'
assert "a config knob change applies live (number)" in_file "$RCON" 'smoke-action 3'
assert "the manual renders for the console" in_file "$RCON" 'canary'
assert "the source dump works" in_file "$RCON" 'class SmokeCanary'
assert "/vibe help lists the subcommands" in_file "$RCON" '/vibe make'
assert "enabling a foreign-platform mod is refused with a friendly message" \
  in_file "$RCON" 'was generated for paper, and this server runs neoforge'
assert "disabling really removed the command" in_file "$RCON" 'Unknown or incomplete command'
assert "re-enabling reports success" in_file "$RCON" 'SmokeCanary enabled.'
# The interesting half: the command has to be back in the LIVE Brigadier
# dispatcher, which it was removed from by reflection a moment ago. The reply
# also proves the knob value set earlier survived the disable/enable round trip.
assert "re-enabling re-registered the command in the live dispatcher" \
  test "$(grep -c 'smoke-pong howdy' "$RCON")" -ge 2

note "asserting the teardown actually ran"
assert "onDisable ran on disable" in_file "$LOG" 'SmokeCanary disabled after'

cleanup
trap - EXIT

echo
if [[ "$FAILURES" -eq 0 ]]; then
  echo "== PHASE E DEDICATED-SERVER GATE PASSED"
  echo "   log: $LOG"
  echo "   rcon transcript: $RCON"
else
  echo "!! $FAILURES CHECK(S) FAILED (log: $LOG, rcon: $RCON)" >&2
  exit 1
fi
