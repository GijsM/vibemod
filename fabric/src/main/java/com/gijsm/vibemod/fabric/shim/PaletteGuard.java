package com.gijsm.vibemod.fabric.shim;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;

import com.gijsm.vibemod.fabric.mixin.ChunkMapAccessor;
import com.gijsm.vibemod.fabric.mixin.LevelChunkSectionAccessor;
import com.gijsm.vibemod.fabric.mixin.StrategyAccessor;

/**
 * The one thing that stands between a generated block and a corrupted world
 * (V4 Phase 1).
 *
 * <p>V3 refused blocks outright, and its stated reason was that adding
 * blockstates mid-session "changes the id space under live containers, and does
 * not necessarily throw". Disassembling 26.2 says that verdict is half right,
 * and the correct half is both narrower and more dangerous than it sounds.
 *
 * <h2>What is actually safe</h2>
 *
 * <p>Blockstate ids are <b>append-only</b>. {@code IdMapper.add(T)} stores at
 * {@code nextId++} and nothing renumbers, so every id already sitting in a saved
 * chunk still means what it meant. And a section with a local palette is immune
 * in memory regardless: {@code LinearPalette} holds a {@code T[]} and
 * {@code HashMapPalette} a {@code CrudeIncrementalIntIdentityHashBiMap<T>} —
 * <em>object references</em>. Global ids appear only in
 * {@code Palette.read/write/getSerializedSize}. Below 9 bits per entry, registry
 * growth cannot touch a container at all.
 *
 * <h2>The one real hazard</h2>
 *
 * <p>{@code Strategy.globalPaletteBitsInMemory} is
 * {@code Mth.ceillog2(idMap.size())} computed <b>once</b>, in the constructor,
 * and handed to {@code Configuration$Global} for every container that promotes
 * past 8 bits. Grow the registry past a power of two and every live global
 * container is one bit too narrow for an id that now exists. It fails loudly —
 * {@code SimpleBitStorage.set} opens with
 * {@code Validate.inclusiveBetween(0L, mask, value)} — which is what makes this
 * gateable rather than corrupting, and is why this class can exist at all.
 *
 * <p>On 26.2 that is not a rare edge case. Vanilla ships 32,366 blockstates
 * against a 15-bit ceiling of 32,768: <b>402 states of headroom</b>, about five
 * stairs blocks. {@link #probe()} reports the real number on whatever version is
 * actually running rather than trusting that one.
 *
 * <h2>What this class does about it</h2>
 *
 * <p>Two paths, and the free one is genuinely free. Below the boundary,
 * {@link #admit} does nothing whatsoever — no sweep, no repack, no packets.
 * Across it, {@link #admit} performs a crossing whose <b>order is the entire
 * correctness argument</b>:
 *
 * <ol>
 *   <li>widen every {@code ServerLevel}'s block-state strategy,</li>
 *   <li>widen the client level's strategy (finding 3b: the integrated server
 *       really does serialise chunk packets, and
 *       {@code PalettedContainer.read} sizes its long array from the
 *       <em>receiving</em> container's own width),</li>
 *   <li>repack the sections that are already global, and</li>
 *   <li>resend the chunks.</li>
 * </ol>
 *
 * <p>Only then does the caller append the states. Both sides are wide
 * <em>before</em> the first wide id exists, so there is no window in which a
 * 16-bit section can reach a 15-bit reader.
 *
 * <p><b>Call {@link #admit} before appending to
 * {@code Block.BLOCK_STATE_REGISTRY}, not after.</b> Every budget figure here is
 * read live off that registry, so calling it afterwards measures a world that
 * has already broken.
 *
 * <p><b>What deliberately needs nothing:</b> local-palette sections, unloaded
 * chunks, and everything on disk. A saved section records the palette-derived
 * {@code bitsInStorage}, which is width-independent, and
 * {@code Configuration$Global.alwaysRepack()} is always true, so {@code unpack}
 * rebuilds at the current width on load. Staleness only bites on a live write or
 * a live send, and the crossing closes both.
 *
 * <p><b>Threading:</b> server thread only, inside the registration window, where
 * chunk ticking is not concurrent. {@code PalettedContainer.pack} goes through
 * the container's own {@code ThreadingDetector}, so a worker that touches one
 * mid-sweep trips vanilla's own detector rather than corrupting quietly.
 */
public final class PaletteGuard {

    private static final Logger LOG = Logger.getLogger("VibeMod.Palette");

    /**
     * The sanity fence, well above anything vanilla defines.
     *
     * <p>Vanilla's widest block is the wall at 324 states. A generated block
     * asking for more than 4096 has a bug in its {@code StateDefinition} — a
     * property with an unbounded value set, most likely — and refusing it by
     * count is a better diagnostic than letting it eat an entire bit of a shared,
     * append-only, never-reclaimable id space.
     */
    private static final int MAX_STATES_PER_BLOCK = 4096;

    /** Below this much remaining headroom, say so: the next block is likely to cross. */
    private static final int LOW_BUDGET_WARNING = 64;

    private final Supplier<MinecraftServer> server;

    /** Sections re-encoded across every crossing this process has done. */
    private int repacks;

    public PaletteGuard(Supplier<MinecraftServer> server) {
        this.server = server;
    }

    /**
     * The one probe line, logged when a server starts.
     *
     * <p>This exists because the headroom figure this whole design is calibrated
     * against was computed from data dumps, and a number computed from a data
     * dump is a claim, not a fact. One line at boot replaces it with ground truth
     * from the jar that is actually running.
     */
    public void probe() {
        int states = states();
        int bits = bits();
        LOG.info("block palette: blockStates=" + states + " paletteBits=" + bits
                + " paletteBudget=" + budget()
                + " (states that fit before the global palette has to widen to " + (bits + 1) + " bits)");
    }

    /** How many blockstates exist right now, read live off the registry. */
    public int states() {
        return Block.BLOCK_STATE_REGISTRY.size();
    }

    /**
     * The global palette width those states imply — vanilla's own arithmetic.
     *
     * <p>{@code Strategy.minimumBitsRequiredForDistinctValues} is, disassembled,
     * a single {@code invokestatic Mth.ceillog2}, so this is the same number the
     * game computes for itself and not an approximation of it.
     */
    public int bits() {
        return Mth.ceillog2(states());
    }

    /** How many more states fit before the width has to grow. */
    public int budget() {
        return (1 << bits()) - states();
    }

    /**
     * Admits a block that is about to add {@code newStates} states, doing
     * whatever the world needs first.
     *
     * <p>Returns normally when the registration may proceed, and throws
     * {@link UnsupportedOperationException} — with the mechanism in the message,
     * because the message is what a player and an LLM both have to act on — when
     * it may not.
     *
     * @throws UnsupportedOperationException if the block cannot be admitted
     */
    public void admit(String modName, String blockId, int newStates) {
        String who = blockId + " (from " + modName + ")";

        if (newStates > MAX_STATES_PER_BLOCK) {
            throw new UnsupportedOperationException(
                    "refusing " + who + ": it defines " + newStates + " blockstates, and no single block may"
                    + " claim more than " + MAX_STATES_PER_BLOCK + ". Blockstate ids are a shared,"
                    + " append-only id space that is never reclaimed — vanilla's widest block, the wall,"
                    + " is 324 states — so a number this large is a bug in the block's property set,"
                    + " not a budget question");
        }

        int budget = budget();
        if (newStates <= budget) {
            // The free path, and it is free on purpose: nothing is swept,
            // nothing is repacked, no packet is sent. Every live container is
            // already wide enough for every id this registration will mint.
            int left = budget - newStates;
            if (left < LOW_BUDGET_WARNING) {
                LOG.warning("block palette headroom is nearly gone: " + left + " blockstates left at "
                        + bits() + " bits after " + who + ". The next block that does not fit forces a"
                        + " global palette crossing, which repacks every loaded chunk section and"
                        + " resends every chunk");
            }
            return;
        }

        MinecraftServer live = server.get();
        if (live == null) {
            throw new UnsupportedOperationException(
                    "refusing " + who + ": it needs " + newStates + " blockstates and only " + budget
                    + " fit at the current " + bits() + "-bit global palette. Crossing that boundary means"
                    + " widening every live PalettedContainer's Strategy and re-encoding every"
                    + " global-palette chunk section, and there is no running server to do that to");
        }

        int players = live.getPlayerList().getPlayerCount();
        if (players > 1) {
            throw new UnsupportedOperationException(
                    "refusing " + who + ": it needs " + newStates + " blockstates and only " + budget
                    + " fit at the current " + bits() + "-bit global palette, and " + players
                    + " players are connected. A remote client builds its own Strategy from its own"
                    + " BLOCK_STATE_REGISTRY, which does not have these states; widening ours would make"
                    + " every chunk packet we send undecodable to it, because PalettedContainer.read sizes"
                    + " its long array from the receiving container's own width. Register this block with"
                    + " nobody else connected, or apply it at the next restart");
        }

        int oldBits = bits();
        int newBits = Mth.ceillog2(states() + newStates);
        cross(live, oldBits, newBits, who);
    }

    /** e.g. {@code "paletteBits=15 paletteBudget=402 paletteRepacks=0"}. */
    public String describeState() {
        return "paletteBits=" + bits() + " paletteBudget=" + budget() + " paletteRepacks=" + repacks;
    }

    // ------------------------------------------------------------- crossing

    /**
     * Widen both sides, then repack, then resend — in that order, which is the
     * correctness argument rather than a preference.
     */
    private void cross(MinecraftServer live, int oldBits, int newBits, String who) {
        LOG.info("crossing the global block palette boundary: " + oldBits + " -> " + newBits + " bits, for "
                + who + ". Widening every level's strategy, re-encoding global-palette sections and"
                + " resending chunks BEFORE any id that wide exists");

        // 1. Every server level. A container consults the Strategy it was built
        //    with, so this one int store reaches every one of them at once,
        //    including ProtoChunks a worldgen worker holds right now and that
        //    the sweep below cannot see.
        for (ServerLevel level : live.getAllLevels()) {
            widen(level.palettedContainerFactory().blockStatesStrategy(), newBits);
        }

        // 2. The client, which builds its own factory and therefore its own
        //    width. Null on a dedicated server, which is the ask rather than an
        //    error. Inline, not a render-thread hop — see ClientSeam.
        ClientSeam client = Shims.clientSeam();
        if (client != null) {
            int on = client.widenBlockStatePalette(newBits);
            LOG.info("client block palette is now " + (on < 0 ? "absent (no level)" : on + " bits"));
        }

        // 3. The sections that are already global. Everything else is either
        //    holding object references (local palette) or on disk, and unpack
        //    rebuilds at the current width on load.
        int repacked = 0;
        for (ServerLevel level : live.getAllLevels()) {
            repacked += repack(level, newBits);
        }
        repacks += repacked;

        // 4. And resend, because a client's copy of a section was encoded at the
        //    old width and nothing else will re-encode it.
        int resent = 0;
        for (ServerLevel level : live.getAllLevels()) {
            resent += resend(level);
        }

        LOG.info("global block palette is now " + newBits + " bits: " + repacked
                + " sections re-encoded, " + resent + " chunk sends queued");
    }

    private static void widen(Strategy<BlockState> strategy, int newBits) {
        StrategyAccessor access = (StrategyAccessor) strategy;
        if (access.getGlobalPaletteBitsInMemory() < newBits) {
            access.setGlobalPaletteBitsInMemory(newBits);
        }
    }

    /**
     * Re-encodes every loaded section in one level that is on the global palette
     * at the wrong width.
     *
     * <p>{@code bitsPerEntry() > 8} is the whole predicate: at 8 bits or below a
     * container is on a local palette holding object references and cannot care
     * what the global width is. {@code != newBits} skips the ones a previous
     * crossing already fixed.
     */
    private int repack(ServerLevel level, int newBits) {
        Strategy<BlockState> strategy = level.palettedContainerFactory().blockStatesStrategy();
        int repacked = 0;

        for (ChunkAccess chunk : loadedChunks(level)) {
            for (LevelChunkSection section : chunk.getSections()) {
                if (section == null) {
                    continue;
                }
                PalettedContainer<BlockState> states = section.getStates();
                if (states.bitsPerEntry() <= 8 || states.bitsPerEntry() == newBits) {
                    continue;
                }
                PalettedContainerRO.PackedData<BlockState> packed = states.pack(strategy);
                PalettedContainer<BlockState> rebuilt = PalettedContainer
                        .unpack(strategy, packed)
                        .getOrThrow(reason -> new IllegalStateException(
                                "re-encoding a chunk section at " + newBits + " bits failed in "
                                + level.dimension().identifier() + " at " + chunk.getPos() + ": " + reason));
                ((LevelChunkSectionAccessor) section).setStates(rebuilt);
                repacked++;
            }
        }
        return repacked;
    }

    /**
     * Drops and re-queues every chunk each player in this level is tracking.
     *
     * <p>Drop first, then mark: that is the pair {@code ChunkMap} itself uses
     * when a player's tracking view changes, and the forget packet is what makes
     * the client discard the section it decoded at the old width instead of
     * merging into it.
     */
    private int resend(ServerLevel level) {
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return 0;
        }
        ChunkMap map = level.getChunkSource().chunkMap;
        int sends = 0;

        for (ChunkAccess chunk : loadedChunks(level)) {
            if (!(chunk instanceof LevelChunk full)) {
                continue;
            }
            ChunkPos pos = full.getPos();
            for (ServerPlayer player : players) {
                if (!map.isChunkTracked(player, pos.x(), pos.z())) {
                    continue;
                }
                player.connection.chunkSender.dropChunk(player, pos);
                player.connection.chunkSender.markChunkPendingToSend(full);
                sends++;
            }
        }
        return sends;
    }

    /**
     * Every chunk this level has in memory.
     *
     * <p>Deduplicated by identity because {@code pendingUnloads} holders have
     * left {@code updatingChunkMap} but their chunks are still writable, and
     * because an {@code ImposterProtoChunk} and its {@code LevelChunk} can both
     * be reachable — re-encoding one section twice would be harmless but the
     * count would lie, and this count is what the gates assert on.
     */
    private static Iterable<ChunkAccess> loadedChunks(ServerLevel level) {
        ChunkMapAccessor map = (ChunkMapAccessor) level.getChunkSource().chunkMap;
        Set<ChunkAccess> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        collect(map.getUpdatingChunkMap().values(), seen);
        collect(map.getPendingUnloads().values(), seen);
        return seen;
    }

    private static void collect(Iterable<ChunkHolder> holders, Set<ChunkAccess> into) {
        for (ChunkHolder holder : holders) {
            ChunkAccess chunk = holder.getLatestChunk();
            if (chunk != null) {
                into.add(chunk);
            }
        }
    }
}
