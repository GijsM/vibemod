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

# NEWEST first, not lexicographically first. After a version bump the old jar is
# still in build/libs and sorts BEFORE the new one ("2.0.0" < "3.0.0"), so the
# plain `ls | head -1` this used to be would have gated the previous release and
# said nothing about it.
JAR="$(ls -t "$ROOT"/fabric/build/libs/vibemod-fabric-*.jar 2>/dev/null | head -1 || true)"
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
         "$RUN/vibemod/mods/ResourceCanary/v1/data/vibemod_resourcecanary/damage_type" \
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

# V4 Phase 5, and it is here rather than in a canary of its own because it is
# the same channel: a `damage_type` is datapack-shaped but is NOT one of the
# seven directories `/reload` re-reads. Vanilla loads the registry layer when
# the world loads, and this pack did not exist then — so without Phase 5 this
# file would sit on disk being correct and inert until the next boot, and the
# host says exactly that ("apply on next world load, not on this reload").
# DatapackSweep + DynamicSeam are what make it real now, and the assertions
# below check both halves: the honest deferral notice AND the id in the live
# registry anyway.
#
# The shape is vanilla's own (data/minecraft/damage_type/generic.json) and names
# nothing outside the game, so the decode cannot fail for a reason that is about
# this gate rather than about the seam.
cat > "$RUN/vibemod/mods/ResourceCanary/v1/data/vibemod_resourcecanary/damage_type/canary.json" <<'DMG'
{
  "message_id": "generic",
  "exhaustion": 0.0,
  "scaling": "when_caused_by_living_non_player"
}
DMG

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
# the RubySword few-shot teaches.
#
# V4 Phase 2 CHANGED WHAT THIS CANARY PROVES. Through V3 it proved a refusal:
# registration would technically have succeeded here (no vanilla client is
# attached at the moment a mod loads) and would then have broken the first
# client that joined, so the policy refused deterministically rather than
# opportunistically. Phase 2 answered the fact behind that policy — Lane A puts
# the server's ids on a VibeMod client BEFORE fabric-api's registry sync, and
# Phase 4's `configureClient` redirect hides them from a vanilla one — so
# `RegistrySeam.refuseOnDedicatedServer` now asks the installed
# `ContentSync` policy, which allows a registration that no connected client
# could be hurt by. Nobody is connected during boot restore, so this lands.
#
# The assertions below therefore assert the SUCCESS, at the same depth the old
# ones asserted the refusal: the id is in the live registry (asked of the
# running game over RCON, not of the log), the mod loads, and the ledger records
# it. The refusal itself is not gone and is not untested — see StateCanary,
# which puts the policy back to null and proves the V3 sentence verbatim.
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
    "description": "A plain Fabric mod that registers a real item; allowed on a dedicated server since V4 Phase 2.",
    "usage": "", "manual": "", "icon": "IRON_SWORD",
    "mainClass": "vibemod.registrycanary.RegistryCanary",
    "currentVersion": 1, "enabled": True, "creator": "smoke",
    "versions": [{"version": 1, "prompt": "the V3 registry canary", "model": "none",
                  "createdAt": int(time.time() * 1000), "changelog": "First registry canary.",
                  "kind": "create", "costUsd": 0.0, "requester": "smoke"}],
    "config": [], "configValues": {},
}, indent=2))
META5

# ------------------------------------------------------------------ V4 Phase 1
#
# BlockCanary: the same claim as RegistryCanary, one registry over, and it lands
# on a dedicated server for the same Phase 2 reason.
#
# A block is the one entry whose id can never be taken back: a saved section
# indexes its palette by position, so an id that stops existing renumbers every
# entry after it and scrambles terrain (finding 3c). That is why the assertions
# below are not content with "it registered" — they place it in a real chunk and
# read it back, and they check that a block landing on a dedicated server did
# not quietly widen the global palette on its way in.
mkdir -p "$RUN/vibemod/mods/BlockCanary/v1"
cat > "$RUN/vibemod/mods/BlockCanary/v1/BlockCanary.java" <<'BLOCK'
package vibemod.blockcanary;

import net.fabricmc.api.ModInitializer;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** No VibeMod import: an ordinary Fabric mod that registers an ordinary block. */
public final class BlockCanary implements ModInitializer {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath("vibemod_blockcanary", "canary_block");

    public static Block canaryBlock;

    @Override
    public void onInitialize() {
        // A plain cube with no properties: exactly one blockstate, which is the
        // shape the prompt profile teaches, and which makes the palette
        // arithmetic below checkable by hand — one block, one appended state.
        canaryBlock = Registry.register(BuiltInRegistries.BLOCK, ID, new Block(
                BlockBehaviour.Properties.of()
                        .strength(1.0F)
                        .setId(ResourceKey.create(Registries.BLOCK, ID))));
        System.out.println("block-canary-registered");
    }
}
BLOCK

python3 - "$RUN/vibemod/mods/BlockCanary/meta.json" <<'META6'
import json, sys, time
open(sys.argv[1], "w").write(json.dumps({
    "schema": 3, "platform": "fabric", "mcVersion": "26.2", "side": "server",
    "name": "BlockCanary",
    "description": "A plain Fabric mod that registers a real block; allowed on a dedicated server since V4 Phase 2.",
    "usage": "", "manual": "", "icon": "STONE",
    "mainClass": "vibemod.blockcanary.BlockCanary",
    "currentVersion": 1, "enabled": True, "creator": "smoke",
    "versions": [{"version": 1, "prompt": "the V4 block canary", "model": "none",
                  "createdAt": int(time.time() * 1000), "changelog": "First block canary.",
                  "kind": "create", "costUsd": 0.0, "requester": "smoke"}],
    "config": [], "configValues": {},
}, indent=2))
META6

# -------------------------------------------------------- V4 Phases 2/4/5/6
#
# StateCanary: the one canary that is allowed to know VibeMod exists, because
# the two things it does cannot be done from outside the host.
#
# 1. IT EXERCISES THE V3 REFUSAL, WHICH IS STILL LIVE CODE. A null
#    `DedicatedPolicy` is not "allow" — RegistrySeam treats it as the
#    pre-Phase-2 answer and refuses with DEDICATED_REFUSAL verbatim. That path
#    is reachable in production (any boot in which ContentSync did not install)
#    and it is the fallback the whole feature rests on, so the gate exercises it
#    rather than asserting it away: the policy is set to null, one item and one
#    block are registered the way an ordinary mod would, both refusals are
#    caught and echoed, and the policy is put back in a `finally`.
#
#    Its own mod, deliberately. A refused registration leaves a
#    constructed-but-unregistered intrusive holder and a stray data-component
#    initializer, and the window's rollback is ALL-OR-NOTHING for the window it
#    closes (see RegistrySeam.rollBackComponentInitializers). Doing this inside
#    RegistryCanary would roll back the successful item's components too. Here
#    the blast radius is a mod that registers nothing on purpose — and the two
#    orphan-discard lines and the rollback line are then asserted, which is the
#    same machinery the V3 gate proved, still proved.
#
# 2. IT READS THE STATE LINES BACK OUT. ContentSync, VanillaLane, DynamicContent
#    and DimensionContent all keep a `describeState()` of `name=value` pairs
#    "for the gates", and on a dedicated server nothing logs them. `/vibestate`
#    is how this gate asks. Same trick the palette gate's canary uses; the
#    generated-mod policy allows a mod to import the host, and only a gate ever
#    should.
mkdir -p "$RUN/vibemod/mods/StateCanary/v1"
cat > "$RUN/vibemod/mods/StateCanary/v1/StateCanary.java" <<'STATE'
package vibemod.statecanary;

import java.util.logging.Logger;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

import com.gijsm.vibemod.fabric.VibeModFabric;
import com.gijsm.vibemod.fabric.net.ContentSync;
import com.gijsm.vibemod.fabric.project.VanillaLane;
import com.gijsm.vibemod.fabric.shim.RegistrySeam;

/** Exercises the null-policy refusal, then reports every phase's state line. */
public final class StateCanary implements ModInitializer {

    private static final Logger LOG = Logger.getLogger("VibeMod.StateCanary");

    private static final String NS = "vibemod_statecanary";

    // Static finals and one try/catch per method, both deliberately. The
    // surgeon regenerates stack map frames with java.lang.classfile's DEFAULT
    // ClassHierarchyResolver, which cannot see net.minecraft classes — a method
    // holding a game-typed local across a try/catch/finally merge fails the
    // rewrite with "Could not resolve class Identifier" and no useful advice.
    // Reported, not fixed here; this canary keeps its frames trivial so that it
    // is testing the registry policy rather than that.
    private static final Identifier ITEM_ID =
            Identifier.fromNamespaceAndPath(NS, "null_policy_item");
    private static final Identifier BLOCK_ID =
            Identifier.fromNamespaceAndPath(NS, "null_policy_block");

    @Override
    public void onInitialize() {
        probeNullPolicy();
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("vibestate").executes(ctx -> {
                    report(ctx.getSource());
                    return 1;
                })));
    }

    /**
     * The V3 refusal, still live, exercised rather than assumed.
     *
     * <p>Both registrations MUST throw. If either lands, the line it logs says
     * so in a way the gate fails on, because a null policy that silently allows
     * content is the one failure mode with no symptom until a vanilla client
     * joins.
     */
    private void probeNullPolicy() {
        RegistrySeam seam = VibeModFabric.registrySeam();
        ContentSync policy = ContentSync.installed();
        if (seam == null || policy == null) {
            LOG.severe("state-canary-policy-unavailable: seam=" + seam + " contentSync=" + policy);
            return;
        }
        try {
            // Null is the pre-ContentSync state, which is exactly what this
            // probe is for. Restored in the finally below, on every path.
            seam.setDedicatedPolicy(null);
            probeItem();
            probeBlock();
        } finally {
            seam.setDedicatedPolicy(policy);
            LOG.info("state-canary-policy-restored");
        }
    }

    /**
     * One item, the way any mod would register one.
     *
     * <p>{@code UnsupportedOperationException}, not {@code RuntimeException},
     * and NOT for taste: {@code SurgeonPolicy} denies the PREFIX
     * {@code java/lang/Runtime}, which also matches
     * {@code java/lang/RuntimeException}, so a mod that catches one is refused
     * with "forbidden API: java.lang.RuntimeException — the process runtime".
     * Reported, not worked around silently. Naming the exact type is the
     * sharper assertion anyway: it is the type {@code RegistrySeam} documents,
     * and anything else fails this mod's load loudly rather than being caught.
     */
    private static void probeItem() {
        try {
            Registry.register(BuiltInRegistries.ITEM, ITEM_ID, new Item(
                    new Item.Properties().setId(ResourceKey.create(Registries.ITEM, ITEM_ID))));
            LOG.severe("state-canary-item-REGISTERED under a null policy");
        } catch (UnsupportedOperationException refused) {
            LOG.info("state-canary-item-refused: " + refused.getMessage());
        }
    }

    /** The same claim one registry over, so the refusal has to name that one. */
    private static void probeBlock() {
        try {
            Registry.register(BuiltInRegistries.BLOCK, BLOCK_ID, new Block(
                    BlockBehaviour.Properties.of()
                            .setId(ResourceKey.create(Registries.BLOCK, BLOCK_ID))));
            LOG.severe("state-canary-block-REGISTERED under a null policy");
        } catch (UnsupportedOperationException refused) {
            LOG.info("state-canary-block-refused: " + refused.getMessage());
        }
    }

    /** One line per phase, each a bare {@code name=value} string the gate greps. */
    private void report(CommandSourceStack source) {
        RegistrySeam seam = VibeModFabric.registrySeam();
        ContentSync sync = ContentSync.installed();
        VanillaLane lane = VanillaLane.installed();
        VibeModFabric.Services live = VibeModFabric.services();
        say(source, "state-registry " + (seam == null ? "none" : seam.describeState()));
        say(source, "state-lane-a " + (sync == null ? "none" : sync.describeState()));
        say(source, "state-lane-b " + (lane == null ? "none" : lane.describeState()));
        say(source, "state-dynamic " + (VibeModFabric.dynamicContent() == null
                ? "none" : VibeModFabric.dynamicContent().describeState()));
        say(source, "state-dimension " + (live == null ? "none" : live.dimensions().describeState()));
    }

    private static void say(CommandSourceStack source, String line) {
        // sendSystemMessage, not sendSuccess: this has to come back over RCON,
        // and that is the call the rest of this gate already proves does.
        source.sendSystemMessage(Component.literal(line));
    }
}
STATE

python3 - "$RUN/vibemod/mods/StateCanary/meta.json" <<'META7'
import json, sys, time
open(sys.argv[1], "w").write(json.dumps({
    "schema": 3, "platform": "fabric", "mcVersion": "26.2", "side": "server",
    "name": "StateCanary",
    "description": "Exercises the null DedicatedPolicy refusal and reports every V4 phase's state line.",
    "usage": "/vibestate", "manual": "", "icon": "COMPASS",
    "mainClass": "vibemod.statecanary.StateCanary",
    "currentVersion": 1, "enabled": True, "creator": "smoke",
    "versions": [{"version": 1, "prompt": "the V4 state canary", "model": "none",
                  "createdAt": int(time.time() * 1000), "changelog": "First state canary.",
                  "kind": "create", "costUsd": 0.0, "requester": "smoke"}],
    "config": [], "configValues": {},
}, indent=2))
META7

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
  grep -q 'RegistryCanary v1 is live' "$LOG" 2>/dev/null \
    && { note "registry canary live after ${i}s"; break; }
  sleep 1
done
for i in $(seq 1 180); do
  grep -q 'BlockCanary v1 is live' "$LOG" 2>/dev/null \
    && { note "block canary live after ${i}s"; break; }
  sleep 1
done
for i in $(seq 1 180); do
  grep -q 'StateCanary v1 is live' "$LOG" 2>/dev/null \
    && { note "state canary live after ${i}s"; break; }
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

# ------------------------------------------------- V4 Phase 1: the palette probe
#
# The probe is the honest replacement for a headroom figure computed from data
# dumps, so the assertion has to be about ARITHMETIC and not about a number:
# states + spare == 2^bits is true on every version, and 32366 is true on
# exactly one. Hard-coding the count here would turn the next Mojang release
# into a red gate that says nothing about VibeMod.
note "asserting on the V4 palette probe (V4 Phase 1)"
PROBE_LINE="$(grep -m1 -oE 'blockStates=[0-9]+ paletteBits=[0-9]+ paletteBudget=[0-9]+' "$LOG" || true)"
P_STATES="${PROBE_LINE#blockStates=}"; P_STATES="${P_STATES%% *}"
P_REST="${PROBE_LINE#*paletteBits=}"; P_BITS="${P_REST%% *}"
P_BUDGET="${PROBE_LINE##*paletteBudget=}"
assert "the palette probe ran at server start" in_file "$LOG" 'block palette: blockStates='
assert "and its three numbers parsed" test -n "$PROBE_LINE"
assert "the probe's arithmetic is self-consistent (${P_STATES:-?} + ${P_BUDGET:-?} == 2^${P_BITS:-?})" \
  test "$((P_STATES + P_BUDGET))" -eq "$((1 << P_BITS))"
assert "the probe says how many states are left before the palette has to widen" \
  in_file "$LOG" 'before the global palette has to widen to'
# Informational, deliberately: a version bump that moves the blockstate count
# must be VISIBLE without being a failure, because the budget is measured at
# runtime and the gate above already proves it adds up.
# The probe runs at SERVER_STARTED and restore-on-boot compiles asynchronously,
# so whether the block canary's single state is already in this figure is a
# race — hence 32366 OR 32367, and informational either way.
if [[ "$P_STATES" == "32366" || "$P_STATES" == "32367" ]]; then
  note "blockstate count is $P_STATES, the 26.2 figure this phase was designed against"
  note "      (32366 vanilla, +1 if the block canary had already registered)"
else
  note "NOTE: blockstate count is ${P_STATES:-unknown}, not the 32366 that V4 Phase 1 was"
  note "      designed against. Not a failure — the budget is read live off the registry —"
  note "      but the headroom table in the V4 plan is now describing a different version."
fi
# This gate DOES register a block now (V4 Phase 2 let it land on a dedicated
# server), so this is no longer "nothing was registered" — it is the budget
# claim: one plain cube is one state, and 26.2 has hundreds of states of
# headroom, so nothing may widen. If this ever fires, every palette number
# measured below is measuring a different id space.
assert "nothing in this gate crossed the palette boundary" \
  not_in_file "$LOG" 'crossing the global block palette boundary'
assert "so the straggler watch never armed either" \
  not_in_file "$LOG" 'watching for straggler chunk sections'

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
# Three now: NativeCanary's /nativecmd, ResourceCanary's /rescanary and the V4
# StateCanary's /vibestate.
assert "the host replayed every live mod's command registrations into the new dispatcher" \
  in_file "$LOG" 'Replaying 3 mod command registration'
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
# V4 Phase 3 retired the assertion that used to live here — "assets/** were
# stored but reported inert on a dedicated server", keyed on 'this host has no
# client resource pack'. A dedicated server now HAS an asset route: the tree is
# zipped deterministically and served at /<sha1>.zip. Asserting the old line
# would be asserting the absence of the feature.
assert "the pack server is serving the canary's assets on a dedicated server" \
  in_file "$LOG" 'Pack server listening on'
assert "and it named the content-addressed archive it built" \
  in_file "$LOG" 'asset file(s) as'

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

# ------------------------------------------------------------ V4 Phase 2 §D
#
# The dedicated-server registry policy, as it stands after V4 Phase 2.
#
# WHAT THIS SECTION USED TO ASSERT, AND WHY IT DOES NOT ANY MORE. Through V3
# every assertion here was about a REFUSAL: an ordinary Fabric mod registering
# an ordinary item had to fail to load, loudly, rather than register and break
# the first client that joined. Phase 2 answered the fact that made that policy
# necessary. `RegistrySeam.refuseOnDedicatedServer` now asks an installed
# `DedicatedPolicy`; `ContentSync` is that policy and it allows a registration
# nobody connected could be hurt by, because a Lane A client is handed the ids
# before fabric-api's registry sync and a Lane B one has them hidden from its
# sync map. Sixteen assertions in this section were therefore asserting the
# ABSENCE of a shipped feature, which is worse than no gate at all: the
# tempting way to make them green is to break the code back.
#
# They are replaced, not deleted, and the replacements are the sharper claim in
# every case where there is one. "Nothing was written to a ledger" becomes "the
# ledger records both ids"; "the item id is absent from the running game"
# becomes "the running game hands the item back when asked for it".
#
# The V3 refusal is NOT retired — a null `DedicatedPolicy` still means it,
# verbatim, and that path is exercised further down by StateCanary.
note "asserting registry content LANDS on a dedicated server (V4 Phase 2)"
assert "the seam admitted an item on a dedicated server, and named it" \
  in_file "$LOG" 'Mod RegistryCanary registered item vibemod_registrycanary:ruby_sword'
assert "the mod's own onInitialize ran past the registration" \
  in_file "$LOG" 'registry-canary-registered'
assert "so the mod is LIVE rather than failed" in_file "$LOG" 'RegistryCanary v1 is live'
assert "nothing refused it" not_in_file "$LOG" 'Refusing registry content from RegistryCanary'
assert "and its load did not fail" not_in_file "$LOG" 'Failed to start RegistryCanary'
# V4 Phase 1's registry, one over, and the one whose ids can never be released.
assert "the same is true for a block" \
  in_file "$LOG" 'Mod BlockCanary registered block vibemod_blockcanary:canary_block'
assert "the block canary's onInitialize ran past the registration too" \
  in_file "$LOG" 'block-canary-registered'
assert "and it is live" in_file "$LOG" 'BlockCanary v1 is live'
assert "nothing refused the block either" \
  not_in_file "$LOG" 'Refusing registry content from BlockCanary'
assert "content landing did not stop the other mods loading" in_file "$LOG" 'NativeCanary v1 is live'
assert "and did not stop the resource channel either" in_file "$LOG" 'ResourceCanary v1 is live'

# The inverse of V3's "nothing was tombstoned or written to a ledger", and the
# sharper claim: a host that registers content MUST write the ledger, because
# the ledger is what a later boot replays in order and what a Lane A manifest is
# hashed over. A missing entry here is a registry that renumbers on the next
# restart, which is exactly the failure the ledger exists to prevent.
LEDGER="$RUN/vibemod/registry-ledger.json"
note "asserting the registry ledger recorded what landed"
assert "the installation wrote a registry ledger" test -f "$LEDGER"
assert "and it records the item id" in_file "$LEDGER" 'vibemod_registrycanary:ruby_sword'
assert "and the block id" in_file "$LEDGER" 'vibemod_blockcanary:canary_block'
assert "the block entry carries the state schema a pin would need to rebuild it" \
  in_file "$LEDGER" '"block":'
assert "both mods are recorded live, not tombstoned" not_in_file "$LEDGER" '"tombstone"'
assert "and neither is pinned while it is still installed" not_in_file "$LEDGER" '"pinned"'

# The half that cannot be faked by a log line: ask the RUNNING GAME. `setblock`
# and `item replace` resolve their id arguments against the live
# BuiltInRegistries during the Brigadier parse, and `data get` reads the id back
# out of a real container in a real chunk — which also proves the item's data
# components are BOUND, because building the stack would otherwise throw
# "Components not bound yet" (RegistrySeam.bindComponents runs at window close;
# this is what says it worked).
note "asking the running game for the ids, not the log"
LIVE_LOG="$RUN/live-content.log"
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "forceload add 0 0" | tee "$LIVE_LOG"
# forceload takes effect on the next chunk-load pass, and every command below
# needs that chunk present. Cheaper than polling for a reply that would be
# identical either way.
sleep 3
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" \
  "setblock 0 100 0 vibemod_blockcanary:canary_block" \
  "execute if block 0 100 0 vibemod_blockcanary:canary_block run say block-canary-is-in-the-world" \
  "setblock 0 102 0 minecraft:chest" \
  "item replace block 0 102 0 container.0 with vibemod_registrycanary:ruby_sword" \
  "data get block 0 102 0 Items" \
  | tee -a "$LIVE_LOG"
assert "the block id parsed against the live BLOCK registry" \
  not_in_file "$LIVE_LOG" 'Unknown block type'
assert "and the block really is in the world, read back by the server itself" \
  in_file "$LOG" 'block-canary-is-in-the-world'
assert "the item id parsed against the live ITEM registry" \
  not_in_file "$LIVE_LOG" 'Unknown item'
assert "a stack of it could be BUILT, so its data components are bound" \
  not_in_file "$LIVE_LOG" 'Components not bound'
assert "and the id reads back out of a real container in a real chunk" \
  in_file "$LIVE_LOG" 'vibemod_registrycanary:ruby_sword'

# ------------------------------------------------- the state lines (V4 2/4/5/6)
#
# Four phases keep a `describeState()` of `name=value` pairs "for the gates" and
# NOTHING logs them on a dedicated server. /vibestate is StateCanary asking each
# one and echoing it over RCON. Read twice, three seconds apart, because one of
# the assertions below is about a counter that has to be GROWING.
note "reading the V4 state lines back off the running server"
SRCON="$RUN/state.log"
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "vibestate" | tee "$SRCON"
sleep 3
SRCON2="$RUN/state-2.log"
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "vibestate" | tee "$SRCON2"
assert "the state canary answers at all" in_file "$SRCON" 'state-registry registryMods='

# ---- V4 Phase 1: the budget still holds, with a block through the seam
#
# The probe assertions further up measured the palette at SERVER_STARTED. These
# measure it now, after a block has actually gone through the seam on a
# dedicated server, and the claim is arithmetic rather than a number: one plain
# cube is one blockstate, and one blockstate must not have moved the width.
S_BITS="$(grep -m1 -oE 'paletteBits=[0-9]+' "$SRCON" | cut -d= -f2 || true)"
note "asserting the blockstate budget after a real block registration (V4 Phase 1)"
assert "the seam appended exactly the one state a plain cube needs" \
  in_file "$SRCON" 'registryBlockStates=1 '
assert "and it counts the block as live content" in_file "$SRCON" 'registryBlocks=1'
assert "beside the one item" in_file "$SRCON" 'registryItems=1'
assert "no pinned stub was minted in a world that has deleted nothing" \
  in_file "$SRCON" 'registryPinnedStubs=0'
assert "the palette is still the width the boot probe measured (${S_BITS:-?} == ${P_BITS:-?})" \
  test "${S_BITS:-x}" = "${P_BITS:-y}"
assert "so registering a block on a dedicated server crossed no boundary" \
  not_in_file "$LOG" 'crossing the global block palette boundary'
assert "and no section anywhere had to be repacked" in_file "$SRCON" 'paletteRepacks=0'

# ---- V4 Phase 2 / Phase 4: lane detection
note "asserting the lane machinery (V4 Phase 2 / Phase 4)"
assert "Lane A delivery is armed for the life of the process" \
  in_file "$LOG" 'Lane A delivery armed: manifest before'
assert "and its task is ordered ahead of fabric-api's registry sync" \
  in_file "$LOG" 'phase vibemod:content_first ordered before'
assert "Lane B is armed with projection ON" \
  in_file "$LOG" 'Lane B armed: vanilla-client projection on'
# THE load-bearing one. The configureClient redirect is a @Redirect against
# another mod's implementation class; `required: true` catches a rename, but
# only this self-check catches "the mixin applied and the filter did nothing",
# which is the failure that silently kicks every vanilla player. It runs at
# boot, so a lone dedicated server is exactly the right place to check it.
assert "the configureClient redirect passed its BOOT self-check" \
  in_file "$LOG" 'Lane B step zero verified'
assert "and the self-check proved both halves it claims" \
  in_file "$LOG" 'is merged onto RegistrySyncManager and the filter strips VibeMod namespaces'
assert "it did not fail and fall back to the V3 refusal" \
  not_in_file "$LOG" 'Lane B step zero FAILED'
assert "the Lane B state line agrees that hiding is on" \
  in_file "$SRCON" 'state-lane-b laneB=projecting registryHiding=on'
assert "the server can build a Lane A manifest, and it holds both ids" \
  in_file "$SRCON" 'state-lane-a laneA=0 laneB=0 manifestEntries=2'
assert "the manifest carries an order hash and a blockstate baseline" \
  in_file "$SRCON" 'manifestBlockStateBaseline='
# That a DedicatedPolicy is INSTALLED is not asserted again here, and that is
# deliberate rather than an omission: it is already proved twice over by the
# pair of sections around this one. Content landed on a dedicated server (§D
# above) and a null policy refuses verbatim (the last section), so a policy that
# said yes is the only thing that can be between them. A third assertion on the
# same predicate would inflate the count without adding a claim.

note "OWED, and said out loud rather than implied: this gate has no second client."
note "      laneA=0 / laneB=0 / filteredMaps=0 above are honest zeros. The manifest is"
note "      BUILT, hashed and sized here, but never sent; no connection is ever"
note "      classified; and RegistryHiding's filter is proved only against the synthetic"
note "      map its own self-check builds, never against a real one. A real Lane A join,"
note "      a real vanilla Lane B join and the projection itself belong to"
note "      :fabric:runClientGameTest, which has a client to join with."

# ---- V4 Phase 5: dynamic registries and the proxy gate
#
# A damage_type is datapack-shaped but is NOT one of the seven directories a
# /reload re-reads: vanilla builds that registry layer when the world loads, and
# this pack did not exist then. So the host says the honest thing ("apply on
# next world load") and Phase 5's sweep then makes it real anyway. Both halves
# are asserted, because either one alone is a half-truth.
note "asserting V4 Phase 5 (dynamic registries, proxy gate)"
assert "the host said a registry-layer file does not apply on a reload" \
  in_file "$LOG" 'ResourceCanary ships registry-layer data (damage_type)'
assert "and the sweep applied it to the live registry anyway" \
  in_file "$LOG" 'added damage_type vibemod_resourcecanary:canary'
assert "the seam counts it" in_file "$SRCON" 'state-dynamic dynamicApplied=1'
assert "nothing was flagged inactive and nothing was refused" \
  in_file "$SRCON" 'dynamicInactive=0 dynamicRefused=0'
assert "the bounce machinery reports state and is not disabled" \
  in_file "$SRCON" 'bounceDisabled=false'
# The proxy gate answers from the GAME DIRECTORY rather than from a setting,
# which is the whole point of it, and this gate's own server.properties says
# online-mode=false — the one thing every proxy setup needs, and the reason a
# harness that nobody logs into can set it. So the honest assertion here is not
# "open": it is that the gate read that file, closed itself over it, and said
# which signal did it. An `open` gate here would mean the gate is not reading
# server.properties at all.
assert "the proxy gate read server.properties and closed over online-mode=false" \
  in_file "$SRCON" 'proxyGate=closed proxySignals=1'
# And the closed gate's consequence, which is the behaviour that matters: no
# bounce is attempted, and the content is queued for each player's next join
# instead of being dropped.
assert "so no bounce was attempted on a proxy-shaped server" in_file "$SRCON" 'bounces=0'
assert "and the new content was deferred to the next join rather than lost" \
  in_file "$SRCON" 'nextJoinDeliveries=1'
note "OWED: the SERVER_STARTED sweep runs on every boot, but on THIS boot it"
note "      necessarily finds nothing — restore-on-boot compiles asynchronously, so no"
note "      mod's data/** is on disk yet when SERVER_STARTED fires. What is asserted"
note "      above is the same DynamicContent.apply(), through the per-mod call, which is"
note "      the one that has content to see. Proving the STARTED call specifically needs"
note "      a second boot against a world whose datapacks are already on disk."
note "      The bounce itself needs connected players and is likewise not reachable here."

# ---- V4 Phase 6: runtime dimensions and the tick-loop redirect
#
# MinecraftServerTickLevelsMixin's redirect is require = 0, so a version bump
# that moves the call site disables it SILENTLY — which is precisely the failure
# mode §10 refuses. LevelTickGuard counts every invocation, so "the redirect
# applied" is provable rather than assumed, and "it is still applying" is
# provable by reading the counter twice.
note "asserting V4 Phase 6 (runtime dimensions, tickChildren redirect)"
assert "the dimension machinery came up with the server" \
  in_file "$LOG" 'Runtime dimensions ready.'
assert "and reports its state" in_file "$SRCON" 'state-dimension dimOpen=0 dimClosing=0'
assert "nothing was refused a dimension it should have had" in_file "$SRCON" 'dimRefused=0'
assert "the roster recorded the boot dimension-type floor" in_file "$SRCON" 'dimTypeFloor='
assert "the tickChildren redirect APPLIED - the guard reads armed" \
  in_file "$SRCON" 'levelTickGuard=armed'
SNAP1="$(grep -m1 -oE 'levelSnapshots=[0-9]+' "$SRCON" | cut -d= -f2 || true)"
SNAP2="$(grep -m1 -oE 'levelSnapshots=[0-9]+' "$SRCON2" | cut -d= -f2 || true)"
assert "and it has counted real invocations (${SNAP1:-?} > 0)" test "${SNAP1:-0}" -gt 0
assert "which keep growing on a ticking server (${SNAP1:-?} -> ${SNAP2:-?})" \
  test "${SNAP2:-0}" -gt "${SNAP1:-0}"

# ------------------------------------------- the V3 refusal, where it still is
#
# A null DedicatedPolicy is NOT "allow" — RegistrySeam treats it as the
# pre-Phase-2 answer and refuses with DEDICATED_REFUSAL verbatim. It is the
# fallback the whole feature rests on (a boot in which ContentSync did not
# install has no way of knowing who is connected), so it is exercised rather
# than assumed: StateCanary sets the policy to null, registers one item and one
# block exactly as an ordinary mod would, catches both refusals, and restores
# the policy in a finally.
#
# The three lines this also keeps proving are the ones the V3 gate found bugs
# with: a refused registration leaves a constructed-but-unregistered intrusive
# holder in BOTH registries, and a stray data-component initializer that
# poisons every later datapack reload unless it is rolled back.
note "asserting a null DedicatedPolicy still means the V3 refusal, verbatim"
assert "the state canary ran its probe and put the policy back" \
  in_file "$LOG" 'state-canary-policy-restored'
assert "a null policy refused a dedicated server, and told the operator" \
  in_file "$LOG" 'Refusing registry content from StateCanary on a dedicated server'
assert "stating the deterministic V3 policy verbatim" \
  in_file "$LOG" 'registry content is singleplayer/LAN-host only in v1; applies after restart on dedicated'
assert "the refusal reached the mod as a throw naming the ITEM registry" \
  in_file "$LOG" 'state-canary-item-refused: Mod StateCanary tried to register null_policy_item into minecraft:item on a dedicated server'
assert "and the block half named the BLOCK registry, not just 'registry content'" \
  in_file "$LOG" 'state-canary-block-refused: Mod StateCanary tried to register null_policy_block into minecraft:block on a dedicated server'
assert "the refused item did not quietly land" not_in_file "$LOG" 'state-canary-item-REGISTERED'
assert "nor the refused block" not_in_file "$LOG" 'state-canary-block-REGISTERED'
assert "the orphaned item object was discarded, loudly" \
  in_file "$LOG" 'constructed-but-unregistered minecraft:item object(s)'
assert "and the orphaned block object was, NAMED as a block" \
  in_file "$LOG" 'constructed-but-unregistered minecraft:block object(s)'
assert "the refused item's half-built component initializer was rolled back" \
  in_file "$LOG" 'data-component initializer(s) left behind'
assert "so no later datapack reload was poisoned by it" \
  not_in_file "$LOG" 'Missing element ResourceKey[minecraft:item'
assert "and the probe cost the refusing mod nothing - it is live" \
  in_file "$LOG" 'StateCanary v1 is live'

# ------------------------------------------------- what /vibe now says about it
#
# The inverse of V3's four journalling assertions. There is no refusal to
# journal any more, so the claim is that /vibe errors is EMPTY for both mods and
# that the install card names the ids they registered — which is the line an
# operator reads to find out that a disabled mod's id is still taken.
RRCON="$RUN/registry.log"
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" \
  "vibe errors RegistryCanary" "vibe info RegistryCanary" \
  "vibe errors BlockCanary" "vibe info BlockCanary" | tee "$RRCON"
note "asserting /vibe reports content rather than a refusal"
assert "/vibe errors journalled no dedicated-server refusal for the item mod" \
  not_in_file "$RRCON" 'singleplayer/LAN-host only'
assert "and no onInitialize failure for either" \
  not_in_file "$RRCON" 'onInitialize failed for mod'
assert "the install card shows both mods loaded, not absent" \
  not_in_file "$RRCON" 'not currently loaded'
assert "and it names the id the item mod registered" \
  in_file "$RRCON" 'registered content: vibemod_registrycanary:ruby_sword'
assert "the block mod's card names its block id under its own heading" \
  in_file "$RRCON" 'blocks: vibemod_blockcanary:canary_block'
assert "and says out loud that the id outlives a disable" \
  in_file "$RRCON" 'stays registered until the world is restarted'

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
