#!/usr/bin/env bash
# Usage: scripts/palette-gate.sh
#
# The V4 Phase 1 palette-boundary gate: the one test in the phase that protects
# player worlds.
#
# Blockstate ids are append-only and are never reclaimed, and the global palette
# is a bit width baked into every live PalettedContainer's Strategy. Registering
# enough blocks to push BLOCK_STATE_REGISTRY.size() past a power of two makes
# every global-palette container one bit too narrow for an id that now exists.
# PaletteGuard's answer is to widen the Strategy in place and re-encode the
# sections that are already global, before the first wide id is minted. This
# gate makes that actually happen — against real chunks, in a real world — and
# then asks whether the world came through it unchanged.
#
# WHY THIS IS ITS OWN SCRIPT and not a section of smoke-fabric.sh:
#
#  1. A crossing is IRREVERSIBLE for the life of the JVM. IdMapper has no
#     remove, so once this gate has run, every palette number in that process is
#     different — smoke-fabric.sh's probe assertions would be measuring a
#     widened palette and its "nothing crossed the boundary" invariant could
#     never hold again.
#  2. It needs a world it is allowed to scribble in, at coordinates far from
#     spawn, and it force-loads and then evicts chunks to get a real disk round
#     trip. That is not a neighbourly thing to do inside an 89-assertion gate
#     that is measuring reload economy and tick counts.
#  3. Its canary deliberately BYPASSES the registry seam (see below), which is
#     the exact opposite of what smoke-fabric.sh's registry section asserts.
#
# WHAT THIS GATE CANNOT SEE, said out loud rather than skipped silently:
#
#  * The client half of the crossing (finding 3b — ClientSeam widening the
#    client level's strategy, and the chunk resend that follows) does not exist
#    on a dedicated server: Shims.clientSeam() is null and level.players() is
#    empty, so steps 2 and 4 of PaletteGuard.cross() are no-ops here. Proving
#    the client survives a crossing needs :fabric:runClientGameTest and a
#    display. The assertions below are explicit about which side they cover.
#  * That a crossed block RENDERS is likewise a client-gate question.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="$ROOT/fabric/palette-gate"
# Deliberately the same cache as smoke-fabric.sh: same server, same fabric-api,
# no second download.
CACHE="$ROOT/fabric/smoke-cache"
RCON_PORT=25587
RCON_PASSWORD="vibemod-palette"
BOOT_TIMEOUT=420

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
note "server: $SERVER_JAR"
note "vibemod: $JAR"

# ------------------------------------------------------------------- run dir
rm -rf "$RUN"
mkdir -p "$RUN/mods" "$RUN/vibemod/mods/PaletteCanary/v1"
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
# The crossing's straggler watch is TICKED (40 ticks, PaletteGuard.WATCH_TICKS),
# and chunk eviction is ticked too. An empty server stops ticking after a minute
# since 1.21.2, which would park both forever.
pause-when-empty-seconds=0
max-players=5
view-distance=4
simulation-distance=4
sync-chunk-writes=true
motd=VibeMod palette-boundary gate ($MC_VERSION)
PROPS

# ------------------------------------------------------------- the canary
# A plain Fabric ModInitializer, stored DISABLED. The gate enables it by hand,
# because everything below has to happen while a world is running and while the
# registration window is open — and the window is open for exactly the duration
# of a mod's onInitialize().
#
# TWO DELIBERATE BYPASSES, both stated here because either one would be a bug in
# a generated mod:
#
#  * It calls WritableRegistry.register directly instead of Registry.register.
#    The seam rewrites the latter, and on a dedicated server the seam REFUSES
#    all registry content (smoke-fabric.sh gates exactly that). This gate is not
#    testing the policy, it is testing the palette machinery underneath it, so
#    it goes around the policy on purpose and does vanilla's own state-append
#    loop itself.
#  * It imports VibeModFabric and PaletteGuard. It is a harness, not a mod: it
#    drives the guard directly and reports the guard's own numbers, which is the
#    only way to size the test from the REAL budget rather than from a constant.
#
# It still calls guard.admit(...) before appending a single state, because that
# is the contract the guard exists to enforce: IdMapper.add appends at nextId++
# and nothing ever takes an id back out.
cat > "$RUN/vibemod/mods/PaletteCanary/v1/PaletteCanary.java" <<'CANARY'
package vibemod.palettecanary;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import com.gijsm.vibemod.fabric.VibeModFabric;
import com.gijsm.vibemod.fabric.shim.PaletteGuard;

/**
 * Forces a global-palette boundary crossing against a real world and reports
 * whether the world came through it unchanged.
 *
 * <p>Every number here is measured. The synthetic blocks are sized from
 * {@code PaletteGuard.budget()} at the moment the mod loads, so the gate works
 * on a version with 402 states of headroom and on one with thirty thousand.
 */
public final class PaletteCanary implements ModInitializer {

    private static final Logger LOG = Logger.getLogger("VibeMod.PaletteCanary");

    /** The canonical namespace this mod's ids land in; the seam rewrites to it anyway. */
    private static final String NS = "vibemod_palettecanary";

    /** PaletteGuard's own per-block fence, so a synthetic block is never refused by size. */
    private static final int MAX_BLOCK_STATES = 4096;

    /** Far from spawn, so removing the force ticket really does evict the chunk. */
    private static final int TEST_CX = 312;
    private static final int TEST_CZ = 312;
    private static final int LOCAL_CX = 313;
    private static final int LOCAL_CZ = 312;

    /** Air in a flat world, so nothing here disturbs generated terrain. */
    private static final int SECTION_Y = 64;

    private static final int BASE_X = TEST_CX * 16;
    private static final int BASE_Z = TEST_CZ * 16;

    /**
     * More than 256, which is the whole point: at 257 distinct entries a
     * PalettedContainer leaves HashMapPalette and goes GLOBAL, and only a global
     * container can be too narrow for a new id.
     */
    private static final int DISTINCT = 300;

    /**
     * "Put bytes in the container and do nothing else."
     *
     * <p>Some of the 300 vanilla states this gate places have an
     * {@code onPlace} — redstone especially — and a side effect that moves a
     * block would move the checksum for a reason that has nothing to do with
     * the palette.
     */
    private static final int SET_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

    /** A known vanilla block beside the test block: the palette-shift detector. */
    private static final BlockPos NEIGHBOUR = new BlockPos(BASE_X + 1, SECTION_Y + 1, BASE_Z + 1);

    /** Where the past-the-old-boundary state gets written. */
    private static final BlockPos WIDE = new BlockPos(BASE_X + 2, SECTION_Y + 2, BASE_Z + 2);

    /** The section's contents as of the last save, for the round-trip check. */
    private static volatile long savedChecksum;

    @Override
    public void onInitialize() {
        // Registered first, and outside the try: if the crossing throws, the
        // gate still needs a way to ask what the world looks like.
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("pgcheck")
                        .then(Commands.literal("forget").executes(ctx -> {
                            ServerLevel level = ctx.getSource().getServer().overworld();
                            level.setChunkForced(TEST_CX, TEST_CZ, false);
                            level.setChunkForced(LOCAL_CX, LOCAL_CZ, false);
                            say(ctx.getSource(), "pgcheck forget=ok");
                            return 1;
                        }))
                        .then(Commands.literal("loaded").executes(ctx -> {
                            ServerLevel level = ctx.getSource().getServer().overworld();
                            say(ctx.getSource(), "pgcheck loaded="
                                    + level.getChunkSource().hasChunk(TEST_CX, TEST_CZ));
                            return 1;
                        }))
                        .then(Commands.literal("reload").executes(ctx -> {
                            ServerLevel level = ctx.getSource().getServer().overworld();
                            level.setChunkForced(TEST_CX, TEST_CZ, true);
                            level.getChunk(TEST_CX, TEST_CZ);
                            long now = checksum(level);
                            say(ctx.getSource(), "pgcheck roundtrip"
                                    + " checksumHeld=" + (now == savedChecksum)
                                    + " neighbour=" + nameAt(level, NEIGHBOUR)
                                    + " wide=" + nameAt(level, WIDE)
                                    + " wideId=" + Block.getId(level.getBlockState(WIDE)));
                            return 1;
                        }))));

        try {
            run();
        } catch (Throwable t) {
            // Logged rather than rethrown: a rethrow fails the mod load, which
            // drains the command above and leaves the gate with nothing to ask.
            LOG.log(Level.SEVERE, "palette-gate: aborted", t);
        }
    }

    private void run() {
        MinecraftServer server = VibeModFabric.services().server();
        ServerLevel level = server.overworld();
        PaletteGuard guard = VibeModFabric.paletteGuard();

        level.setChunkForced(TEST_CX, TEST_CZ, true);
        level.setChunkForced(LOCAL_CX, LOCAL_CZ, true);

        // ---------------------------------------------- the local-palette witness
        // Four distinct states in one section. LinearPalette holds a T[] of
        // OBJECT REFERENCES, so registry growth cannot reach it. The claim is
        // that the crossing leaves this container alone; the proof is that the
        // section still holds the same object afterwards.
        fillLocal(level);
        LevelChunk localChunk = level.getChunk(LOCAL_CX, LOCAL_CZ);
        LevelChunkSection localSection = localChunk.getSection(level.getSectionIndex(SECTION_Y));
        int localIdentityBefore = System.identityHashCode(localSection.getStates());
        int localBitsBefore = localSection.getStates().bitsPerEntry();
        LOG.info("palette-gate: localWitness=" + (localBitsBefore <= 8)
                + " localBitsBefore=" + localBitsBefore);

        // --------------------------------------------- the global-palette witness
        fillGlobal(level);
        level.setBlock(NEIGHBOUR, Blocks.GOLD_BLOCK.defaultBlockState(), SET_FLAGS);
        LevelChunk testChunk = level.getChunk(TEST_CX, TEST_CZ);
        LevelChunkSection testSection = testChunk.getSection(level.getSectionIndex(SECTION_Y));
        int globalIdentityBefore = System.identityHashCode(testSection.getStates());
        int globalBitsBefore = testSection.getStates().bitsPerEntry();
        long checksumBefore = checksum(level);
        LOG.info("palette-gate: globalWitness=" + (globalBitsBefore > 8)
                + " globalBitsBefore=" + globalBitsBefore
                + " checksumBefore=" + checksumBefore);

        // ------------------------------------------------------- the real budget
        int oldStates = guard.states();
        int oldBits = guard.bits();
        int oldBudget = guard.budget();
        int oldCeiling = 1 << oldBits;
        LOG.info("palette-gate: budget states=" + oldStates + " bits=" + oldBits
                + " spare=" + oldBudget + " ceiling=" + oldCeiling);

        // -------------------------------------- synthetic blocks, sized to cross
        // Powers of two of states, built from boolean properties: 4096 states
        // costs twelve properties and 4096*12 neighbour-table entries, where one
        // 4096-value IntegerProperty would cost 4096*4095 of them.
        int made = 0;
        while (guard.budget() >= MAX_BLOCK_STATES) {
            registerSynthetic(guard, made++, MAX_BLOCK_STATES);
        }
        int need = guard.budget() + 1;
        int size = 1;
        while (size < need) {
            size <<= 1;
        }
        Block crosser = registerSynthetic(guard, made++, size);
        List<BlockState> crosserStates = crosser.getStateDefinition().getPossibleStates();

        int newBits = guard.bits();
        LOG.info("palette-gate: after states=" + guard.states() + " " + guard.describeState());
        LOG.info("palette-gate: crossed=" + (newBits > oldBits)
                + " oldBits=" + oldBits + " newBits=" + newBits + " blocks=" + made);

        // ------------------------------------------------------------- integrity
        PalettedContainer<BlockState> localNow = localSection.getStates();
        LOG.info("palette-gate: localRepacked="
                + (System.identityHashCode(localNow) != localIdentityBefore)
                + " localBitsAfter=" + localNow.bitsPerEntry());

        PalettedContainer<BlockState> globalNow = testSection.getStates();
        LOG.info("palette-gate: globalRepacked="
                + (System.identityHashCode(globalNow) != globalIdentityBefore)
                + " globalBitsAfter=" + globalNow.bitsPerEntry());

        LOG.info("palette-gate: checksumHeld=" + (checksum(level) == checksumBefore));
        LOG.info("palette-gate: neighbour=" + nameAt(level, NEIGHBOUR));

        // ------------------------------ the direct Validate.inclusiveBetween case
        // SimpleBitStorage.set opens with Validate.inclusiveBetween(0, mask,
        // value). Writing the highest state of the crossing block into a section
        // that is ALREADY on the global palette is that check, exactly.
        BlockState widest = crosserStates.get(crosserStates.size() - 1);
        int wideId = Block.getId(widest);
        try {
            level.setBlock(WIDE, widest, SET_FLAGS);
            LOG.info("palette-gate: wideWrite=ok wideId=" + wideId
                    + " pastOldBoundary=" + (wideId >= oldCeiling)
                    + " readBack=" + (level.getBlockState(WIDE) == widest));
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "palette-gate: wideWrite=threw", t);
            LOG.info("palette-gate: wideWrite=threw " + t);
        }

        // ------------------------------------------- crossing twice is a no-op
        // One more state now fits in the widened palette, so admit() must take
        // the free path: no sweep, no repack, no second crossing.
        registerSynthetic(guard, made++, 1);
        LOG.info("palette-gate: secondCrossed=" + (guard.bits() != newBits)
                + " " + guard.describeState());

        savedChecksum = checksum(level);
        LOG.info("palette-gate: savedChecksum=" + savedChecksum);
        LOG.info("palette-gate: done");
    }

    /**
     * Registers one synthetic block the way {@code Blocks.<clinit>} does, guard
     * first.
     */
    @SuppressWarnings("unchecked")
    private static Block registerSynthetic(PaletteGuard guard, int index, int states) {
        Identifier id = Identifier.fromNamespaceAndPath(NS, "synthetic_" + index);
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        Block block = Synthetic.of(states,
                BlockBehaviour.Properties.of().strength(1.0F).setId(key));
        List<BlockState> possible = block.getStateDefinition().getPossibleStates();

        // BEFORE anything is appended. IdMapper.add stores at nextId++ and there
        // is no remove, so a block that would not fit has to be stopped here.
        guard.admit("PaletteCanary", id.toString(), possible.size());

        ((WritableRegistry<Block>) BuiltInRegistries.BLOCK)
                .register(key, block, RegistrationInfo.BUILT_IN);
        for (BlockState state : possible) {
            Block.BLOCK_STATE_REGISTRY.add(state);
            state.initCache();
        }
        LOG.info("palette-gate: synthetic " + id + " states=" + possible.size());
        return block;
    }

    /** Four vanilla states in one section: a linear palette, and immune. */
    private static void fillLocal(ServerLevel level) {
        BlockState[] four = {
            Blocks.STONE.defaultBlockState(),
            Blocks.DIRT.defaultBlockState(),
            Blocks.GRAVEL.defaultBlockState(),
            Blocks.SAND.defaultBlockState(),
        };
        int baseX = LOCAL_CX * 16;
        int baseZ = LOCAL_CZ * 16;
        int i = 0;
        for (int dy = 0; dy < 16; dy++) {
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    level.setBlock(new BlockPos(baseX + dx, SECTION_Y + dy, baseZ + dz),
                            four[i++ % four.length], SET_FLAGS);
                }
            }
        }
    }

    /** {@value #DISTINCT} distinct vanilla states in one section: a global palette. */
    private static void fillGlobal(ServerLevel level) {
        List<BlockState> palette = new ArrayList<>(DISTINCT);
        for (int id = 0; id < Block.BLOCK_STATE_REGISTRY.size() && palette.size() < DISTINCT; id++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
            // Block entities and fluids bring bookkeeping this gate has no
            // business exercising; everything else is just bytes in a container.
            if (state != null && !state.isAir() && !state.hasBlockEntity()
                    && state.getFluidState().isEmpty()) {
                palette.add(state);
            }
        }
        int i = 0;
        for (int dy = 0; dy < 16; dy++) {
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    level.setBlock(new BlockPos(BASE_X + dx, SECTION_Y + dy, BASE_Z + dz),
                            palette.get(i++ % palette.size()), SET_FLAGS);
                }
            }
        }
    }

    /** Every cell of the test section, by global id, order-sensitive. */
    private static long checksum(ServerLevel level) {
        long hash = 1L;
        for (int dy = 0; dy < 16; dy++) {
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    hash = hash * 31L + Block.getId(level.getBlockState(
                            new BlockPos(BASE_X + dx, SECTION_Y + dy, BASE_Z + dz)));
                }
            }
        }
        return hash;
    }

    private static String nameAt(ServerLevel level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
    }

    /** sendSystemMessage, because that is the reply shape that reaches an RCON console. */
    private static void say(CommandSourceStack source, String text) {
        source.sendSystemMessage(Component.literal(text));
    }

    /**
     * A block with an arbitrary power-of-two number of states.
     *
     * <p>Built from boolean properties rather than one wide IntegerProperty
     * because the neighbour tables StateDefinition builds are
     * {@code states * sum(values - 1)} entries: twelve booleans give 4096 states
     * for 49,152 entries, while one 4096-value integer property would ask for
     * 16.7 million.
     *
     * <p>{@code createBlockStateDefinition} is called from {@code Block.<init>},
     * before any subclass field exists, which is why the property count arrives
     * through a thread-local instead of a constructor argument — the same
     * problem vanilla solves by making every property a static final.
     */
    public static final class Synthetic extends Block {

        private static final BooleanProperty[] BITS = {
            BooleanProperty.create("a"), BooleanProperty.create("b"),
            BooleanProperty.create("c"), BooleanProperty.create("d"),
            BooleanProperty.create("e"), BooleanProperty.create("f"),
            BooleanProperty.create("g"), BooleanProperty.create("h"),
            BooleanProperty.create("i"), BooleanProperty.create("j"),
            BooleanProperty.create("k"), BooleanProperty.create("l"),
        };

        private static final ThreadLocal<Integer> PENDING = new ThreadLocal<>();

        static Block of(int states, BlockBehaviour.Properties properties) {
            PENDING.set(Integer.numberOfTrailingZeros(states));
            try {
                return new Synthetic(properties);
            } finally {
                PENDING.remove();
            }
        }

        private Synthetic(BlockBehaviour.Properties properties) {
            super(properties);
        }

        @Override
        protected void createBlockStateDefinition(
                StateDefinition.Builder<Block, BlockState> builder) {
            Integer count = PENDING.get();
            if (count == null) {
                return;
            }
            for (int i = 0; i < count; i++) {
                builder.add(BITS[i]);
            }
        }
    }
}
CANARY

python3 - "$RUN/vibemod/mods/PaletteCanary/meta.json" <<'META'
import json, sys, time
open(sys.argv[1], "w").write(json.dumps({
    "schema": 3, "platform": "fabric", "mcVersion": "26.2", "side": "server",
    "name": "PaletteCanary",
    "description": "Forces a global blockstate palette crossing and checks the world survived it.",
    "usage": "", "manual": "", "icon": "STONE",
    "mainClass": "vibemod.palettecanary.PaletteCanary",
    "currentVersion": 1,
    # Stored DISABLED on purpose: the crossing needs a running world, and the
    # gate wants to snapshot the palette probe before anything moves it.
    "enabled": False,
    "creator": "palette-gate",
    "versions": [{"version": 1, "prompt": "the V4 palette boundary canary", "model": "none",
                  "createdAt": int(time.time() * 1000), "changelog": "First palette canary.",
                  "kind": "create", "costUsd": 0.0, "requester": "palette-gate"}],
    "config": [], "configValues": {},
}, indent=2))
META

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

# ------------------------------------------------- the budget, before anything
note "reading the real palette budget at boot"
PROBE_LINE="$(grep -m1 -oE 'blockStates=[0-9]+ paletteBits=[0-9]+ paletteBudget=[0-9]+' "$LOG" || true)"
P_STATES="${PROBE_LINE#blockStates=}"; P_STATES="${P_STATES%% *}"
P_REST="${PROBE_LINE#*paletteBits=}"; P_BITS="${P_REST%% *}"
P_BUDGET="${PROBE_LINE##*paletteBudget=}"
assert "the palette probe reported a budget to size the test from" test -n "$PROBE_LINE"
assert "and it adds up (${P_STATES:-?} + ${P_BUDGET:-?} == 2^${P_BITS:-?})" \
  test "$((P_STATES + P_BUDGET))" -eq "$((1 << P_BITS))"
note "measured budget: $P_BUDGET states of headroom at $P_BITS bits"
assert "nothing has crossed the boundary yet" \
  not_in_file "$LOG" 'crossing the global block palette boundary'

# Random ticks would grow, break or drop some of the 300 vanilla states this
# gate places in one section, and the checksum would move for a reason that has
# nothing to do with the palette.
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" \
  "gamerule randomTickSpeed 0" "gamerule doFireTick false" > /dev/null

# ------------------------------------------------------------- the crossing
note "enabling the palette canary (this registers blocks and forces a crossing)"
ERCON="$RUN/enable.log"
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "vibe enable PaletteCanary" | tee "$ERCON"

for i in $(seq 1 180); do
  grep -q 'palette-gate: done' "$LOG" 2>/dev/null && { note "crossing finished after ${i}s"; break; }
  grep -q 'palette-gate: aborted' "$LOG" 2>/dev/null && break
  sleep 1
done
assert "the canary compiled, loaded and ran to completion" in_file "$LOG" 'palette-gate: done'
assert "and nothing in it aborted" not_in_file "$LOG" 'palette-gate: aborted'

note "asserting the witnesses were the right shape before the crossing"
assert "the local witness really was on a local palette (<= 8 bits)" \
  in_file "$LOG" 'palette-gate: localWitness=true'
assert "the global witness really was on the global palette (> 8 bits)" \
  in_file "$LOG" 'palette-gate: globalWitness=true'

note "asserting the crossing happened"
assert "PaletteGuard logged the transition" \
  in_file "$LOG" 'crossing the global block palette boundary'
assert "and reported the new width" in_file "$LOG" 'global block palette is now'
assert "paletteBits moved" in_file "$LOG" 'palette-gate: crossed=true'
assert "the synthetic blocks were sized from the measured budget, not a constant" \
  in_file "$LOG" 'palette-gate: synthetic vibemod_palettecanary:synthetic_0'

note "asserting object-reference immunity (the claim, not the assumption)"
# The one that would be easy to assume and wrong to assume: LinearPalette and
# HashMapPalette store object references, so a <= 8-bit section cannot be
# affected by registry growth and must not be touched. Same object afterwards is
# the proof.
assert "the local-palette section was NOT repacked" \
  in_file "$LOG" 'palette-gate: localRepacked=false'
assert "the global-palette section WAS repacked" \
  in_file "$LOG" 'palette-gate: globalRepacked=true'

note "asserting world integrity"
assert "the repacked section's contents are unchanged" \
  in_file "$LOG" 'palette-gate: checksumHeld=true'
assert "the known vanilla neighbour block is unchanged (the palette-shift detector)" \
  in_file "$LOG" 'palette-gate: neighbour=minecraft:gold_block'

note "asserting the Validate.inclusiveBetween regression is closed"
assert "writing a state past the old boundary into a global section did not throw" \
  in_file "$LOG" 'palette-gate: wideWrite=ok'
assert "and the id really was past the old boundary" in_file "$LOG" 'pastOldBoundary=true'
assert "and it read back as the state that was written" in_file "$LOG" 'readBack=true'

note "asserting the second crossing is a no-op"
assert "a block that fits took the free path" in_file "$LOG" 'palette-gate: secondCrossed=false'
assert "and no second transition was logged" \
  test "$(grep -c 'crossing the global block palette boundary' "$LOG")" -eq 1

# ------------------------------------------------- the straggler watch (Task 1)
note "asserting the post-crossing straggler watch armed and closed"
assert "the crossing armed the straggler watch" \
  in_file "$LOG" 'watching for straggler chunk sections'
for i in $(seq 1 30); do
  grep -q 'block palette straggler watch closed' "$LOG" 2>/dev/null && break
  sleep 1
done
assert "and it disarmed itself, reporting a total" \
  in_file "$LOG" 'block palette straggler watch closed'
assert "the guard reports the watch counter in its state line" \
  in_file "$LOG" 'paletteWatchRepacks='

# ------------------------------------------------------- the save/load round trip
# A REAL disk round trip, in this JVM: drop the force ticket, wait for the chunk
# to leave memory, then ask for it again. Not a restart, and that limit is
# stated rather than implied — a restart would also be testing whether the ids
# come back, which is the ledger's pinning story and not this gate's.
note "round-tripping the test chunk through disk"
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "save-all flush" > /dev/null
RRCON="$RUN/roundtrip.log"
"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "pgcheck forget" | tee "$RRCON"

EVICTED=no
for i in $(seq 1 90); do
  "$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "pgcheck loaded" >> "$RRCON" 2>&1 || true
  if grep -q 'pgcheck loaded=false' "$RRCON"; then
    EVICTED=yes
    note "test chunk left memory after ${i}s"
    break
  fi
  sleep 1
done
# A hard failure, not a skip: if the chunk never leaves memory the reload below
# reads it straight back out of the chunk map and proves nothing at all.
assert "the test chunk really left memory, so the reload comes off disk" test "$EVICTED" = yes

"$ROOT/scripts/smoke-rcon.py" "$RCON_PORT" "$RCON_PASSWORD" "pgcheck reload" | tee -a "$RRCON"
assert "pre-existing content survived the save/load round trip" \
  in_file "$RRCON" 'checksumHeld=true'
assert "the vanilla neighbour block survived it too" \
  in_file "$RRCON" 'neighbour=minecraft:gold_block'
assert "and the block registered across the boundary came back as itself" \
  in_file "$RRCON" 'wide=vibemod_palettecanary:'
assert "no section was silently shortened on load (finding 3c)" \
  not_in_file "$LOG" 'Recoverable errors when loading section'

note "asserting nothing else broke"
assert "no mixin failed to apply" not_in_file "$LOG" 'Mixin apply failed'
assert "nothing threw on a worker thread" not_in_file "$LOG" 'Exception in thread'
# ThreadingDetector builds its message with an invokedynamic string template, so
# there is no stable literal to grep for — but the crash report it attaches
# always carries a "Thread dumps" category, and that IS a literal (verified in
# ThreadingDetector.makeThreadingException).
assert "no container tripped vanilla's threading detector" \
  not_in_file "$LOG" 'Thread dumps'

cleanup
trap - EXIT

echo
note "NOT COVERED HERE, and it needs a display: the client half of a crossing."
note "  PaletteGuard.cross() steps 2 and 4 (ClientSeam.widenBlockStatePalette and"
note "  the chunk resend) are no-ops on a dedicated server — clientSeam() is null"
note "  and players() is empty. That the client's own Strategy widens in lockstep,"
note "  that no decoder length-mismatch kicks the player, and that the new block"
note "  renders, are :fabric:runClientGameTest assertions."
echo
if [[ "$FAILURES" -eq 0 ]]; then
  echo "== PALETTE BOUNDARY GATE PASSED"
  echo "   log: $LOG"
else
  echo "!! $FAILURES CHECK(S) FAILED (log: $LOG)" >&2
  exit 1
fi
