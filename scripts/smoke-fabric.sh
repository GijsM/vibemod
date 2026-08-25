#!/usr/bin/env bash
# Usage: scripts/smoke-fabric.sh
#
# The Phase D dedicated-server acceptance gate (ARCHITECTURE-V2 §9) in one
# command. Downloads a real Fabric server launcher and fabric-api, installs the
# freshly built VibeMod jar next to a pre-seeded canned mod, boots it, drives it
# over RCON, and ASSERTS on every reply.
#
# Two things make this a real gate rather than a boot check.
#
# The canned mod is a mod-flavor source written straight to the store, so
# restore-on-boot compiles and hot-loads it exactly as a generated one would —
# no LLM, no API key, and the whole compile -> load -> command -> config -> unload
# path is exercised. It uses a curated ctx.on* hook, a top-level command, an
# action, a task and a live config knob, so one canary covers every registration
# path the host tracks.
#
# And it runs against the INSTALLED jar, not Loom's dev classpath. That is the
# only way to prove the Jar-in-Jar half works: on the dev classpath Adventure and
# ECJ are plain classpath entries, while in the shipped jar they are nested and
# have to be found through the loader and materialized by the cpcache before
# javac can read them.
#
# Everything lives under fabric/smoke/, which is git-ignored runtime state, and
# is deleted and recreated on every run.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="$ROOT/fabric/smoke"
CACHE="$ROOT/fabric/smoke-cache"
RCON_PORT=25586
RCON_PASSWORD="vibemod-smoke"
BOOT_TIMEOUT=420

prop() { grep -E "^$1=" "$ROOT/gradle.properties" | cut -d= -f2- ; }
MC_VERSION="$(prop minecraftVersion)"
LOADER_VERSION="$(prop fabricLoaderVersion)"
FABRIC_API_VERSION="$(prop fabricApiVersion)"

JAR="$(ls "$ROOT"/fabric/build/libs/vibemod-fabric-*.jar 2>/dev/null | head -1 || true)"
if [[ -z "$JAR" ]]; then
  echo "!! no vibemod-fabric jar - run ./gradlew :fabric:build first" >&2
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
# `grep -q` against a file, as an assert predicate.
in_file() { grep -qF -- "$2" "$1"; }
not_in_file() { ! grep -qF -- "$2" "$1"; }

# ------------------------------------------------------------------ downloads
mkdir -p "$CACHE"
SERVER_JAR="$CACHE/fabric-server-$MC_VERSION-$LOADER_VERSION.jar"
if [[ ! -f "$SERVER_JAR" ]]; then
  note "downloading the Fabric server launcher ($MC_VERSION / loader $LOADER_VERSION)"
  # Fabric's meta service builds a self-contained server launcher on demand.
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
note "server: $SERVER_JAR"
note "vibemod: $JAR"

# ------------------------------------------------------------------- run dir
rm -rf "$RUN"
mkdir -p "$RUN/mods" "$RUN/vibemod/mods/SmokeCanary/v1"
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
max-players=5
view-distance=4
simulation-distance=4
sync-chunk-writes=false
motd=VibeMod Phase D smoke ($MC_VERSION)
PROPS

# ---------------------------------------------------------------- canned mod
# Mod flavor: Mojang-typed, a curated hook, no Bukkit anywhere. This is exactly
# what the fabric prompt profile teaches a model to write.
cat > "$RUN/vibemod/mods/SmokeCanary/v1/SmokeCanary.java" <<'MOD'
package vibemod.smokecanary;

import com.gijsm.vibemod.api.Mod;
import com.gijsm.vibemod.api.VibeContext;

import net.minecraft.network.chat.Component;

/**
 * The Phase D smoke canary. One top-level command, one action, one curated
 * server hook, one task and one live config knob, so a single mod exercises
 * every registration path the Fabric host tracks.
 */
public final class SmokeCanary implements Mod {

    private final Counter counter = new Counter();

    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        ctx.command("smokeping", "Phase D smoke canary", (src, args) ->
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

python3 - "$RUN/vibemod/mods/SmokeCanary/meta.json" <<'META'
import json, sys, time
meta = {
    # meta.json v3 (ARCHITECTURE-V2 5): stamped for fabric, so restore-on-boot
    # accepts it. A mod stamped "paper" here would be skipped, which the gate
    # asserts separately below.
    "schema": 3,
    "platform": "fabric",
    "mcVersion": "26.2",
    "side": "both",
    "name": "SmokeCanary",
    "description": "Phase D smoke canary: one command, one action, one hook, one task.",
    "usage": "/smokeping",
    "manual": "## SmokeCanary\n\nA **canary** for the Phase D acceptance gate.\n\n### Settings\n\n- `greeting` - what /smokeping echoes back (default `hi`).\n- `count` - what the ping action echoes back (default **7**).",
    "icon": "FEATHER",
    "mainClass": "vibemod.smokecanary.SmokeCanary",
    "currentVersion": 1,
    "enabled": True,
    "creator": "smoke",
    "versions": [{
        "version": 1,
        "prompt": "a canary for the phase D smoke gate",
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

# ------------------------------------------------------- the V3 native canary
# The thesis test (V3 Phase 0). A PLAIN FABRIC MOD: it implements
# net.fabricmc.api.ModInitializer, registers to real Fabric events, and contains
# ZERO VibeMod imports — grep it, there is not one. It only runs here because
# the host rewrote its `Event.register` call sites into a shim before
# defineClass, and it only DISABLES because that shim keeps a revocable per-mod
# list behind one permanent subscription.
#
# The tick counter is the observable, and it is the right one: a Fabric Event
# cannot be unsubscribed, so "the log stopped growing after /vibe disable" is
# exactly the claim that would be false if the seam did not work.
mkdir -p "$RUN/vibemod/mods/NativeCanary/v1"
cat > "$RUN/vibemod/mods/NativeCanary/v1/NativeCanary.java" <<'NATIVE'
package vibemod.nativecanary;

import java.util.logging.Logger;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;

import net.minecraft.world.InteractionResult;

/** An ordinary Fabric mod. No VibeMod import appears anywhere in this file. */
public final class NativeCanary implements ModInitializer {

    private static final Logger LOG = Logger.getLogger("VibeMod.NativeCanary");

    private int ticks;

    @Override
    public void onInitialize() {
        LOG.info("NativeCanary onInitialize");
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++ticks % 40 == 0) {
                LOG.info("native-tick " + ticks);
            }
        });
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            LOG.info("native-attack");
            return InteractionResult.PASS;
        });
    }
}
NATIVE

python3 - "$RUN/vibemod/mods/NativeCanary/meta.json" <<'META3'
import json, sys, time
open(sys.argv[1], "w").write(json.dumps({
    "schema": 3, "platform": "fabric", "mcVersion": "26.2", "side": "server",
    "name": "NativeCanary",
    "description": "A plain Fabric ModInitializer, hot-loaded through the bytecode seam.",
    "usage": "", "manual": "", "icon": "FEATHER",
    "mainClass": "vibemod.nativecanary.NativeCanary",
    "currentVersion": 1, "enabled": True, "creator": "smoke",
    "versions": [{"version": 1, "prompt": "the V3 native canary", "model": "none",
                  "createdAt": int(time.time() * 1000), "changelog": "First native canary.",
                  "kind": "create", "costUsd": 0.0, "requester": "smoke"}],
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

/** Deliberately Bukkit-typed: this must never be compiled on a Fabric host. */
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
note "booting Fabric $MC_VERSION (log: $LOG)"
cd "$RUN"
java -Xms1G -Xmx2G -jar "$SERVER_JAR" nogui > "$LOG" 2>&1 < /dev/null &
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
for i in $(seq 1 180); do
  grep -q 'NativeCanary v1 is live' "$LOG" 2>/dev/null && { note "native canary live after ${i}s"; break; }
  sleep 1
done
# "Live" is not "dispatching": restore-on-boot runs inside SERVER_STARTING, so
# the mod is loaded a beat before the server begins ticking at all. Wait for the
# first tick through the fanout, or the disable half of the gate below has no
# baseline to compare against.
for i in $(seq 1 60); do
  grep -q 'native-tick' "$LOG" 2>/dev/null && { note "native tick fired after ${i}s"; break; }
  sleep 1
done

# ------------------------------------------------------------------ asserts
note "asserting on the boot log"
assert "the host initialised" in_file "$LOG" 'VibeMod ready'
assert "the platform probe says fabric with dialogs" in_file "$LOG" 'Platform: fabric'
assert "the fabric prompt profile was selected" in_file "$LOG" 'prompt profile Fabric'
assert "a compiler backend resolved" in_file "$LOG" 'Compiler backend:'
assert "the canned mod compiled and hot-loaded" in_file "$LOG" 'SmokeCanary v1 is live'
assert "the mod's own onEnable ran" in_file "$LOG" 'SmokeCanary enabled'
assert "ctx.client() was inert on a dedicated server" in_file "$LOG" 'hasClient=false'
assert "the foreign-platform mod was skipped, not compiled" \
  in_file "$LOG" 'Skipping mod WrongPlatform: generated for paper'
assert "no mixin failed to apply" not_in_file "$LOG" 'Mixin apply failed'
assert "nothing threw during boot" not_in_file "$LOG" 'Exception in thread'

note "asserting on the V3 native canary (the thesis test)"
assert "the bytecode seam was installed" in_file "$LOG" 'Bytecode seams:'
assert "a plain Fabric mod compiled and hot-loaded" in_file "$LOG" 'NativeCanary v1 is live'
assert "its ModInitializer entrypoint ran" in_file "$LOG" 'NativeCanary onInitialize'
assert "the host fanned out the event it subscribed to" \
  in_file "$LOG" 'Fanning out ServerTickEvents.EndTick'
assert "its END_SERVER_TICK subscription dispatches" in_file "$LOG" 'native-tick'
assert "nothing it registered was refused" not_in_file "$LOG" 'UnsupportedOperationException'
assert "the mod source really has no VibeMod import" \
  not_in_file "$RUN/vibemod/mods/NativeCanary/v1/NativeCanary.java" 'com.gijsm.vibemod'

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
assert "/vibe settings reports the fabric platform" in_file "$RCON" 'platform: fabric 26.2'
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
  in_file "$RCON" 'was generated for paper, and this server runs fabric'
assert "disabling really removed the command" in_file "$RCON" 'Unknown or incomplete command'
assert "re-enabling reports success" in_file "$RCON" 'SmokeCanary enabled.'
# The interesting half: the command has to be back in the LIVE Brigadier
# dispatcher, which it was removed from by reflection a moment ago. The reply
# also proves the knob value set earlier survived the disable/enable round trip.
assert "re-enabling re-registered the command in the live dispatcher" \
  test "$(grep -c 'smoke-pong howdy' "$RCON")" -ge 2

note "asserting the teardown actually ran"
assert "onDisable ran on disable" in_file "$LOG" 'SmokeCanary disabled after'

# -------------------------------------------------- the native disable/enable
# The half that cannot be faked. A Fabric Event cannot be unsubscribed, so if
# `/vibe disable` really drains the mod, the tick log stops growing — and if the
# seam were a no-op it would keep growing forever. Measured over wall time
# rather than asserted on a reply, because the reply proves a command ran and
# this proves the subscription is gone.
note "round-tripping the native canary through disable/enable"
NRCON="$RUN/native-rcon.log"
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "vibe disable NativeCanary" \
  | tee "$NRCON"
sleep 3
BEFORE="$(grep -c 'native-tick' "$LOG" || true)"
sleep 4
AFTER_DISABLE="$(grep -c 'native-tick' "$LOG" || true)"
assert "disabling a native mod drained its Fabric event subscription (ticks $BEFORE -> $AFTER_DISABLE)" \
  test "$BEFORE" -eq "$AFTER_DISABLE"

"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "vibe enable NativeCanary" \
  | tee -a "$NRCON"
sleep 6
AFTER_ENABLE="$(grep -c 'native-tick' "$LOG" || true)"
assert "re-enabling reports success" in_file "$NRCON" 'NativeCanary enabled.'
assert "re-enabling brought the subscription back (ticks $AFTER_DISABLE -> $AFTER_ENABLE)" \
  test "$AFTER_ENABLE" -gt "$AFTER_DISABLE"
assert "the entrypoint ran a second time" \
  test "$(grep -c 'NativeCanary onInitialize' "$LOG")" -ge 2
assert "no registration was silently refused across the round trip" \
  not_in_file "$LOG" 'UnsupportedOperationException'

cleanup
trap - EXIT

echo
if [[ "$FAILURES" -eq 0 ]]; then
  echo "== PHASE D DEDICATED-SERVER GATE PASSED"
  echo "   log: $LOG"
  echo "   rcon transcript: $RCON"
else
  echo "!! $FAILURES CHECK(S) FAILED (log: $LOG, rcon: $RCON)" >&2
  exit 1
fi
