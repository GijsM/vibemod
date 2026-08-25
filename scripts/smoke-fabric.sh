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
# No players ever join this gate, and since 1.21.2 an empty server STOPS TICKING
# after a minute. Every tick-counting assertion here (and V3 Phase 2's whole
# reload debounce, which is ticked) would then be measuring a paused server. Not
# a weakening: the assertions get MORE deterministic, not less.
pause-when-empty-seconds=0
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

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;

/**
 * An ordinary Fabric mod. No VibeMod import appears anywhere in this file.
 *
 * <p>It implements BOTH entrypoints on purpose (V3 Phase 1 §B). This is a
 * dedicated server, so the client half must be skipped without a crash and
 * without running — which is the same bargain ctx.client(...) already makes,
 * and which the gate asserts in both directions.
 */
public final class NativeCanary implements ModInitializer, ClientModInitializer {

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
        // V3 Phase 1 A: a real Brigadier tree, registered through the loader's
        // own callback. Live immediately, gone on /vibe disable.
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("nativecmd").executes(ctx -> {
                    // sendSystemMessage rather than sendSuccess: this reply has to
                    // come back over RCON, and that is the call the rest of this
                    // gate already proves reaches an RCON console.
                    ctx.getSource().sendSystemMessage(
                            Component.literal("native-cmd-ok " + ticks));
                    return 1;
                })));
    }

    @Override
    public void onInitializeClient() {
        // Must NEVER appear in a dedicated server's log.
        LOG.info("native-client-init");
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

# ----------------------------------------------------- the V3 resource canary
# V3 Phase 2. A plain Fabric mod whose interesting content is NOT Java: a
# recipe, an advancement, an mcfunction and a lang file, shipped the way a real
# mod jar ships them. The Java is eleven lines whose only job is to ANSWER the
# question this gate cannot ask over RCON any other way — "is the recipe really
# in the live RecipeManager, and the advancement in the live tree?" — because
# `/recipe give @a <id>` resolves its target selector before its recipe
# argument (verified by disassembling RecipeCommand), so with no players online
# it fails identically for a real recipe and a made-up one.
#
# The files are written in the CANONICAL namespace already. A generated mod's
# would be rewritten there by ModStore.saveNewVersion; this canary is written
# straight to disk, so it has to arrive canonical. The rewrite itself is gated
# in :core:selfTestStore, against the same helper.
mkdir -p "$RUN/vibemod/mods/ResourceCanary/v1/data/vibemod_resourcecanary/recipe" \
         "$RUN/vibemod/mods/ResourceCanary/v1/data/vibemod_resourcecanary/advancement" \
         "$RUN/vibemod/mods/ResourceCanary/v1/data/vibemod_resourcecanary/function" \
         "$RUN/vibemod/mods/ResourceCanary/v1/assets/vibemod_resourcecanary/lang"

cat > "$RUN/vibemod/mods/ResourceCanary/v1/ResourceCanary.java" <<'RES'
package vibemod.resourcecanary;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Reports whether this mod's own datapack content reached the live game.
 *
 * <p>No VibeMod import anywhere, exactly like the other native canaries: it
 * asks the vanilla RecipeManager and the vanilla advancement tree, which are
 * the two objects that would be empty if the datapack channel did not work.
 */
public final class ResourceCanary implements ModInitializer {

    private static final String NS = "vibemod_resourcecanary";

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("rescanary").executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    boolean recipe = false;
                    for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
                        if (holder.id().identifier().toString().equals(NS + ":ruby")) {
                            recipe = true;
                            break;
                        }
                    }
                    boolean advancement = server.getAdvancements()
                            .get(Identifier.fromNamespaceAndPath(NS, "ruby")) != null;
                    ctx.getSource().sendSystemMessage(Component.literal(
                            "resource-canary recipe=" + recipe + " advancement=" + advancement));
                    return 1;
                })));
    }
}
RES

# The recipe shape is vanilla's own, read out of the 26.2 server jar
# (data/minecraft/recipe/golden_apple.json for the pattern, and
# suspicious_stew_from_blue_orchid.json for the result components block).
cat > "$RUN/vibemod/mods/ResourceCanary/v1/data/vibemod_resourcecanary/recipe/ruby.json" <<'RECIPE'
{
  "type": "minecraft:crafting_shaped",
  "key": {"#": "minecraft:redstone", "X": "minecraft:amethyst_shard"},
  "pattern": [" # ", "#X#", " # "],
  "result": {
    "id": "minecraft:amethyst_shard",
    "components": {
      "minecraft:custom_name": {"text": "Ruby Charm", "color": "red", "italic": false},
      "minecraft:item_model": "vibemod_resourcecanary:ruby"
    }
  }
}
RECIPE

# The `recipe_id` criterion field was read off RecipeCraftedTrigger$TriggerInstance's
# codec, not recalled.
cat > "$RUN/vibemod/mods/ResourceCanary/v1/data/vibemod_resourcecanary/advancement/ruby.json" <<'ADV'
{
  "parent": "minecraft:adventure/root",
  "criteria": {
    "crafted": {
      "trigger": "minecraft:recipe_crafted",
      "conditions": {"recipe_id": "vibemod_resourcecanary:ruby"}
    }
  },
  "display": {
    "icon": {"id": "minecraft:amethyst_shard"},
    "title": "A Warm Glow",
    "description": "Craft a ruby charm."
  }
}
ADV

# The one observable that is pure datapack, with no mod code in the path at all.
cat > "$RUN/vibemod/mods/ResourceCanary/v1/data/vibemod_resourcecanary/function/hello.mcfunction" <<'FN'
say resource-canary-fn-ok
FN

# assets/** on a dedicated server: stored, inert, and SAID SO once.
cat > "$RUN/vibemod/mods/ResourceCanary/v1/assets/vibemod_resourcecanary/lang/en_us.json" <<'LANG'
{"advancements.vibemod_resourcecanary.ruby.title": "A Warm Glow"}
LANG

python3 - "$RUN/vibemod/mods/ResourceCanary/meta.json" <<'META4'
import json, sys, time
open(sys.argv[1], "w").write(json.dumps({
    "schema": 3, "platform": "fabric", "mcVersion": "26.2", "side": "server",
    "name": "ResourceCanary",
    "description": "A plain Fabric mod that ships a datapack: a recipe, an advancement and a function.",
    "usage": "", "manual": "", "icon": "AMETHYST_SHARD",
    "mainClass": "vibemod.resourcecanary.ResourceCanary",
    "currentVersion": 1, "enabled": True, "creator": "smoke",
    "versions": [{"version": 1, "prompt": "the V3 resource canary", "model": "none",
                  "createdAt": int(time.time() * 1000), "changelog": "First resource canary.",
                  "kind": "create", "costUsd": 0.0, "requester": "smoke"}],
    "config": [], "configValues": {},
}, indent=2))
META4

# ------------------------------------------------------------------ V3 Phase 3
#
# RegistryCanary: a plain Fabric mod that registers a REAL item, exactly the way
# the RubySword few-shot teaches. On a dedicated server that is refused, and the
# refusal is the whole assertion: registration would technically succeed here
# (no vanilla client is attached at the moment a mod loads) and would then break
# the first client that joined, so the policy is deterministic rather than
# opportunistic.
mkdir -p "$RUN/vibemod/mods/RegistryCanary/v1"
cat > "$RUN/vibemod/mods/RegistryCanary/v1/RegistryCanary.java" <<'REG'
package vibemod.registrycanary;

import net.fabricmc.api.ModInitializer;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ToolMaterial;

/** No VibeMod import: an ordinary Fabric mod that registers an ordinary item. */
public final class RegistryCanary implements ModInitializer {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath("vibemod_registrycanary", "ruby_sword");

    public static Item rubySword;

    @Override
    public void onInitialize() {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, ID);
        rubySword = Registry.register(BuiltInRegistries.ITEM, ID, new Item(
                new Item.Properties().sword(ToolMaterial.IRON, 4.0F, -2.4F).setId(key)));
        System.out.println("registry-canary-registered");
    }
}
REG

python3 - "$RUN/vibemod/mods/RegistryCanary/meta.json" <<'META5'
import json, sys, time
open(sys.argv[1], "w").write(json.dumps({
    "schema": 3, "platform": "fabric", "mcVersion": "26.2", "side": "server",
    "name": "RegistryCanary",
    "description": "A plain Fabric mod that registers a real item; refused on a dedicated server.",
    "usage": "", "manual": "", "icon": "IRON_SWORD",
    "mainClass": "vibemod.registrycanary.RegistryCanary",
    "currentVersion": 1, "enabled": True, "creator": "smoke",
    "versions": [{"version": 1, "prompt": "the V3 registry canary", "model": "none",
                  "createdAt": int(time.time() * 1000), "changelog": "First registry canary.",
                  "kind": "create", "costUsd": 0.0, "requester": "smoke"}],
    "config": [], "configValues": {},
}, indent=2))
META5

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
for i in $(seq 1 180); do
  grep -q 'ResourceCanary v1 is live' "$LOG" 2>/dev/null && { note "resource canary live after ${i}s"; break; }
  sleep 1
done
for i in $(seq 1 180); do
  grep -q 'Refusing registry content from RegistryCanary' "$LOG" 2>/dev/null \
    && { note "registry canary refused after ${i}s"; break; }
  sleep 1
done
# V3 Phase 2 §C: the datapack is materialized during the load, but the reload
# that makes it LIVE is debounced by 40 ticks and only runs once the server is
# ticking. Nothing below is true until it has.
for i in $(seq 1 60); do
  grep -q 'Server data reloaded in' "$LOG" 2>/dev/null && { note "first data reload after ${i}s"; break; }
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
assert "the V3 resource canary compiled and hot-loaded" in_file "$LOG" 'ResourceCanary v1 is live'
assert "its resource files never reached the compiler" \
  not_in_file "$LOG" 'ResourceCanary v1 failed to compile'
assert "the resource canary's own source has no VibeMod import either" \
  not_in_file "$RUN/vibemod/mods/ResourceCanary/v1/ResourceCanary.java" 'com.gijsm.vibemod'

# V3 Phase 1 B: the client half of a two-entrypoint mod, on a machine with no
# client. The mod must still load, the client entrypoint must not run, and the
# host must SAY it skipped it rather than leaving the operator to guess.
note "asserting on the client entrypoint's inertness (V3 Phase 1 B)"
assert "a ClientModInitializer half was skipped on a dedicated server" \
  in_file "$LOG" 'has a ClientModInitializer half; skipping it on a dedicated server'
assert "and it really did not run" not_in_file "$LOG" 'native-client-init'
assert "the mod loaded anyway" in_file "$LOG" 'NativeCanary onInitialize'

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
# V3 Phase 1 A: the command the mod registered through CommandRegistrationCallback
# has to be in the LIVE dispatcher already — the callback fired long before this
# mod existed, so if the seam did not invoke it immediately there would be
# nothing to run until a /reload.
note "asserting on the native mod's own Brigadier command (V3 Phase 1 A)"
CRCON="$RUN/cmd-rcon.log"
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "nativecmd" | tee "$CRCON"
assert "a command registered via CommandRegistrationCallback runs immediately" \
  in_file "$CRCON" 'native-cmd-ok'

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

# The other half of A, and the one that cannot be faked: Brigadier has no
# remove, so "the command is gone" is only true if the reflective node surgery
# ran. Checked as ABSENCE of the reply after the disable, and presence again
# after the enable — the same shape as the tick counter above.
note "asserting the mod's command went with it"
DRCON="$RUN/cmd-disabled.log"
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "vibe disable NativeCanary" "nativecmd" \
  | tee "$DRCON"
assert "disabling the mod removed the command it registered" \
  not_in_file "$DRCON" 'native-cmd-ok'
assert "and the server says the command is unknown" \
  in_file "$DRCON" 'Unknown or incomplete command'

ERCON="$RUN/cmd-enabled.log"
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "vibe enable NativeCanary" \
  | tee "$ERCON"
# The enable recompiles asynchronously; the command is only back once the mod is.
for i in $(seq 1 30); do
  "$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "nativecmd" >> "$ERCON" 2>&1 || true
  grep -q 'native-cmd-ok' "$ERCON" && break
  sleep 1
done
assert "re-enabling put the command back in the live dispatcher" \
  in_file "$ERCON" 'native-cmd-ok'
assert "no command name collision was reported" not_in_file "$LOG" 'is already registered'

# The third leg of A. A datapack /reload throws the whole Brigadier tree away
# and builds a new one, firing CommandRegistrationCallback again — for the mods
# that were on disk when the game started, which a hot-loaded one never is. So
# the host has to replay every live mod's callback into the fresh dispatcher, or
# every generated command silently disappears the first time an operator types
# /reload.
note "asserting the mod's command survives a datapack reload (V3 Phase 1 A)"
RRCON="$RUN/cmd-reload.log"
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "reload" | tee "$RRCON"
sleep 5
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "nativecmd" "smokeping" "vibe list" \
  | tee -a "$RRCON"
# Two now, not one: the V3 Phase 2 resource canary registers /rescanary as well.
assert "the host replayed both mods' command registrations into the new dispatcher" \
  in_file "$LOG" 'Replaying 2 mod command registration'
assert "the mod's own command still runs after /reload" in_file "$RRCON" 'native-cmd-ok'
assert "and the v2 command bridge survived the same reload" in_file "$RRCON" 'smoke-pong howdy'
assert "and /vibe itself survived the same reload" in_file "$RRCON" 'SmokeCanary'

# ------------------------------------------------- V3 Phase 2: the datapack
# The half that cannot be faked: a recipe and an advancement that exist in the
# LIVE managers, asked of the managers themselves, and gone again when the mod
# is. `/function` is the same claim with no mod code in the path at all — if
# the datapack were merely written to disk and never reloaded, the function
# would not resolve.
note "asserting on the V3 resource canary's datapack (V3 Phase 2 B/C)"
PACK_DIR="$RUN/world/datapacks/vibemod-resourcecanary"
assert "a mod's data/** was materialized as a world datapack" test -d "$PACK_DIR"
assert "the pack carries a manifest the running game wrote the format for" \
  test -f "$PACK_DIR/pack.mcmeta"
assert "the recipe landed in the pack at its canonical namespace" \
  test -f "$PACK_DIR/data/vibemod_resourcecanary/recipe/ruby.json"
assert "the host logged the materialization" \
  in_file "$LOG" 'Datapack vibemod-resourcecanary materialized'
assert "the coordinator ran a reload for it" in_file "$LOG" 'Server data reloaded in'
assert "assets/** were stored but reported inert on a dedicated server" \
  in_file "$LOG" 'this host has no client resource pack'

PRCON="$RUN/pack-rcon.log"
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" \
  "datapack list enabled" \
  "rescanary" \
  "function vibemod_resourcecanary:hello" \
  | tee "$PRCON"
assert "the world enabled the pack (no operator ever touched it)" \
  in_file "$PRCON" 'vibemod-resourcecanary'
assert "the recipe is in the LIVE recipe manager" in_file "$PRCON" 'recipe=true'
assert "the advancement is in the LIVE advancement tree" in_file "$PRCON" 'advancement=true'
assert "the mod's mcfunction runs" in_file "$LOG" 'resource-canary-fn-ok'

# level.dat is where "Missing data pack" comes from: reloadResources writes the
# surviving selection back through WorldData#setDataConfiguration, and the world
# save persists it. Asserted in BOTH directions — remembered while the mod is
# live, forgotten once it is not — because only the pair proves the write
# happened rather than the read being broken.
in_level_dat() {
  python3 -c "import gzip,sys;print('yes' if b'$1' in gzip.open('$RUN/world/level.dat','rb').read() else 'no')"
}
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "save-all flush" > /dev/null
assert "level.dat remembers the pack while the mod is live" \
  test "$(in_level_dat vibemod-resourcecanary)" = "yes"

# Reload economy (§C): restoring four mods, three of which are live, must not
# have cost one reload each. Asserted as <=, not ==, exactly as the brief says.
RELOADS_BEFORE="$(grep -c 'Reloading server data' "$LOG" || true)"
DONE_BEFORE="$(grep -c 'Server data reloaded in' "$LOG" || true)"
assert "boot restore coalesced its reloads (${RELOADS_BEFORE} <= 2)" \
  test "$RELOADS_BEFORE" -le 2

note "asserting the datapack goes away with the mod"
URCON="$RUN/pack-unload.log"
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "vibe disable ResourceCanary" \
  | tee "$URCON"
# The teardown only MARKS a reload pending — it must not run one inline, because
# drain() is timed by the 250ms watchdog and a reload takes 200ms-2s. So the
# directory goes at once and the reload lands a couple of seconds later.
assert "disabling removed the datapack directory immediately" test ! -d "$PACK_DIR"
# Waiting on the COMPLETION line, not the start line, and the difference is not
# pedantry: reloadResources called on the server thread managed-blocks, and
# managedBlock pumps the server's task queue — so an RCON command sent between
# the two lines executes against the OLD function library and answers as if
# nothing had changed. The first run of this gate asserted on the start line and
# saw exactly that.
for i in $(seq 1 30); do
  test "$(grep -c 'Server data reloaded in' "$LOG")" -gt "$DONE_BEFORE" && break
  sleep 1
done
assert "and a final reload was scheduled, not skipped" \
  test "$(grep -c 'Reloading server data' "$LOG")" -gt "$RELOADS_BEFORE"
assert "and that final reload finished" \
  test "$(grep -c 'Server data reloaded in' "$LOG")" -gt "$DONE_BEFORE"
assert "the watchdog never tripped on the teardown" not_in_file "$LOG" 'auto-disabled by the watchdog'

GRCON="$RUN/pack-gone.log"
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" \
  "datapack list enabled" \
  "function vibemod_resourcecanary:hello" \
  | tee "$GRCON"
assert "the pack is no longer selected" not_in_file "$GRCON" 'file/vibemod-resourcecanary'
assert "and its function no longer resolves" in_file "$GRCON" 'Unknown function'
# The reason the final reload is not optional: reloadResources writes the
# surviving selection back through WorldData#setDataConfiguration, so an id
# whose folder is gone stops being remembered. Skip it and every future world
# load warns about a missing pack forever.
assert "no missing-data-pack warning was produced" not_in_file "$LOG" 'Missing data pack'
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "save-all flush" > /dev/null
assert "and level.dat has FORGOTTEN the pack id, so a later boot cannot warn about it" \
  test "$(in_level_dat vibemod-resourcecanary)" = "no"

note "asserting the datapack comes back with the mod"
BRCON="$RUN/pack-back.log"
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "vibe enable ResourceCanary" \
  | tee "$BRCON"
for i in $(seq 1 40); do
  "$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "rescanary" >> "$BRCON" 2>&1 || true
  grep -q 'recipe=true' "$BRCON" && break
  sleep 1
done
assert "re-enabling put the datapack directory back" test -d "$PACK_DIR"
assert "re-enabling put the recipe back in the live manager" in_file "$BRCON" 'recipe=true'
assert "nothing in the resource channel threw" not_in_file "$LOG" 'Could not write the datapack'

# ------------------------------------------------------------------ V3 Phase 3 §D
#
# The dedicated-server registry policy. Every assertion here is about a REFUSAL,
# because that is the whole feature on this host: the mod is an ordinary Fabric
# mod registering an ordinary item, and it must fail to load, loudly, with a
# message an operator can act on — not register successfully and break the first
# client that joins.
note "asserting the registry seam refuses a dedicated server (V3 Phase 3 §A/§D)"
assert "the registry seam refused a dedicated server" \
  in_file "$LOG" 'Refusing registry content from RegistryCanary'
assert "and the refusal states the deterministic policy verbatim" \
  in_file "$LOG" 'registry content is singleplayer/LAN-host only in v1; applies after restart on dedicated'
assert "the refusal failed the mod's LOAD rather than being swallowed" \
  in_file "$LOG" 'Failed to start RegistryCanary: onInitialize failed for mod RegistryCanary'
assert "the mod's onInitialize never got past the refusal" \
  not_in_file "$LOG" 'registry-canary-registered'
assert "the refusal did not stop the other mods loading" in_file "$LOG" 'NativeCanary v1 is live'
assert "and did not stop the resource channel either" in_file "$LOG" 'ResourceCanary v1 is live'
assert "the item id really is absent from the running game" \
  not_in_file "$LOG" 'registered item vibemod_registrycanary'
assert "nothing was tombstoned or written to a ledger on a host that registers nothing" \
  test ! -e "$RUN/vibemod/registry-ledger.json"
# The bug this gate found: Item.<init> appends to DATA_COMPONENT_INITIALIZERS
# before the registration is refused, and nothing removes it — so every LATER
# datapack reload died with "Missing element ResourceKey[minecraft:item / …]".
assert "the refused item's half-built state was rolled back" \
  in_file "$LOG" 'Rolled back 1 data-component initializer'
assert "so no later datapack reload was poisoned by it" \
  not_in_file "$LOG" 'Missing element ResourceKey[minecraft:item'
assert "and the orphaned item object was discarded, loudly" \
  in_file "$LOG" 'constructed-but-unregistered minecraft:item object(s)'

RRCON="$RUN/registry.log"
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" \
  "vibe errors RegistryCanary" "vibe info RegistryCanary" | tee "$RRCON"
assert "the refusal is journalled where /vibe errors can show it" \
  in_file "$RRCON" 'singleplayer/LAN-host only'
assert "and it is journalled as an onInitialize failure, not a crash" \
  in_file "$RRCON" 'onInitialize'
# /vibe list reads the STORE, so a mod that failed to load is still listed as
# enabled — that is pre-existing and correct (it will be retried next boot).
# What must be true is that nothing is LIVE, which the install card says.
assert "and the refused mod is not live" \
  in_file "$RRCON" 'not currently loaded'

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
