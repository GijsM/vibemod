package com.gijsm.vibemod.fabric.dimension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.google.gson.JsonElement;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;

import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.phys.Vec3;

import com.gijsm.vibemod.fabric.mixin.MappedRegistryAccessor;
import com.gijsm.vibemod.fabric.mixin.MinecraftServerAccessor;

/**
 * A real dimension at runtime: a {@code ServerLevel} that ticks, generates,
 * saves, and that a player can be teleported into and back out of (V4 Phase 6).
 *
 * <h2>1. The load-bearing fact: {@code LEVEL_STEM} is not client-synced</h2>
 *
 * <p>Everything else in this phase follows from one line of the 26.2
 * disassembly. {@code RegistryDataLoader} keeps three lists, and
 * {@code Registries.LEVEL_STEM} appears in exactly one of them:
 *
 * <pre>
 * DIMENSION_REGISTRIES   = List.of(new RegistryData&lt;&gt;(Registries.LEVEL_STEM, LevelStem.CODEC));
 * SYNCHRONIZED_REGISTRIES = [ BANNER_PATTERN, BIOME, CHAT_TYPE, DAMAGE_TYPE, DIALOG,
 *                             DIMENSION_TYPE, ENCHANTMENT, … ]   // 29 entries, no LEVEL_STEM
 * </pre>
 *
 * <p>and {@code RegistrySynchronization.NETWORKABLE_REGISTRIES} is derived from
 * {@code SYNCHRONIZED_REGISTRIES}. <b>Adding a level costs a connected client
 * nothing at all.</b> No packet, no registry delta, no reconfiguration, no kick.
 * Compare Phase 1's blocks and Phase 2's items — {@code BuiltInRegistries},
 * synced, and the reason those two phases needed a manifest, a lane split and a
 * {@code configureClient} redirect between them. This phase needs none of it.
 * That asymmetry is not a convenience; it is the whole reason the ecosystem's one
 * production-proven runtime-content pattern (NucleoidMC's Fantasy, which this
 * class follows step for step) is about dimensions and nothing else.
 *
 * <h2>2. The four steps, and where each one was verified</h2>
 *
 * <ol>
 *   <li><b>Register a {@code LevelStem}.</b> Additive unfreeze of the live
 *       {@code MappedRegistry}, reusing Phase 5's {@link MappedRegistryAccessor}
 *       and the exact open/register/refreeze shape {@code DynamicSeam.apply} uses
 *       — not a second unfreeze mechanism with its own bugs.</li>
 *   <li><b>Construct a {@code ServerLevel}.</b> Its constructor is public, and it
 *       is the same one {@code MinecraftServer.createLevels} calls for the nether
 *       and the end:
 *       <pre>
 * public ServerLevel(MinecraftServer, Executor, LevelStorageSource$LevelStorageAccess,
 *                    ServerLevelData, ResourceKey&lt;Level&gt;, LevelStem,
 *                    boolean isDebug, long obfuscatedSeed, List&lt;CustomSpawner&gt;, boolean tickTime)
 *       </pre>
 *       Vanilla's own non-overworld branch passes {@code new DerivedLevelData(worldData,
 *       overworldData)}, {@code ImmutableList.of()} and {@code false}, and so does
 *       this class — reproducing what the game does rather than inventing
 *       arguments. Two of the ten are private fields, which is what
 *       {@link MinecraftServerAccessor} is for.</li>
 *   <li><b>Insert into {@code MinecraftServer.levels}.</b> A {@code private final
 *       Map} whose <em>object</em> is mutable, so one accessor is the whole
 *       mechanism.</li>
 *   <li><b>Guard the tick loop.</b> {@code tickChildren} walks
 *       {@code getAllLevels().iterator()} — a live view of that same map — so
 *       opening or closing a dimension from inside any level tick is a
 *       {@code ConcurrentModificationException}. See
 *       {@code MinecraftServerTickLevelsMixin} and {@link LevelTickGuard}; this
 *       class <b>refuses to open anything</b> until that redirect has
 *       demonstrably run.</li>
 * </ol>
 *
 * <p>Then {@code ServerLevelEvents.LOAD} / {@code UNLOAD}, so every other mod on
 * the server learns about the level the same way it learns about the nether.
 *
 * <h2>3. The {@code DimensionType} problem, and what VibeMod has that Fantasy
 * does not</h2>
 *
 * <p>A level needs a {@code DimensionType}, and {@code DIMENSION_TYPE} <em>is</em>
 * synced — during configuration only. Fantasy sidesteps this by shipping its
 * dimension types as a static datapack, so a Fantasy dimension can only ever use
 * a type the server already had at boot.
 *
 * <p>Phase 5 removes that restriction, and the reason is a verified property of
 * {@code RegistrySynchronization.packRegistry}: an entry is sent <b>by id
 * only</b> when its {@code RegistrationInfo.knownPackInfo()} is one of the
 * client's known packs, and <b>in full, data and all</b> otherwise. Phase 5
 * registers under {@code RegistrationInfo.BUILT_IN}, whose {@code knownPackInfo}
 * is empty. So a dimension type VibeMod created at runtime is delivered complete
 * to a joining client — a pure-vanilla client, with no pack and no mod — at its
 * next configuration.
 *
 * <p>Both cases are therefore supported, and they differ only in <em>when</em>:
 *
 * <ul>
 *   <li>a dimension naming an <b>existing</b> type works immediately, for players
 *       who are already connected;</li>
 *   <li>a dimension naming a <b>new</b> type works for players who join
 *       afterwards, or who are bounced through configuration by Phase 5's
 *       {@code ReconfigureBouncer}.</li>
 * </ul>
 *
 * <p>In between, a teleport is <b>refused</b>, by {@link DimensionRoster}, and the
 * refusal names the mechanism: {@code CommonPlayerSpawnInfo} encodes the type as
 * {@code ByteBufCodecs.holderRegistry(Registries.DIMENSION_TYPE)}, a bare registry
 * index with no inline branch, so a client one entry short does not degrade — it
 * fails the decode and the connection ends. Sending them anyway is a kick wearing
 * a teleport's clothes. No silent drops.
 *
 * <h2>4. Removal: VibeMod does not do what Fantasy does, and here is why</h2>
 *
 * <p>Fantasy deletes a closed dimension out of {@code MappedRegistry}'s internal
 * maps. That leaves a <b>null hole in {@code byId}</b>, which is why Fantasy also
 * ships a {@code listElements} null-filter mixin: {@code listElements()} is
 * literally {@code byId.stream()}, and its consumers include
 * {@code RegistrySynchronization.packRegistry}, which calls
 * {@code Holder.Reference::key} on every element. Ids are never compacted, so the
 * hole is permanent for the session.
 *
 * <p>VibeMod keeps its house rule instead — <b>no {@code MappedRegistry.remove};
 * the stem stays</b> (Decision 7). Two things have to be said about that, and the
 * first is that the usual argument for it does <em>not</em> apply here.
 *
 * <p><b>The block argument does not carry.</b> §2.3 refuses to release a block id
 * because a section palette is a {@code ListCodec} indexed <em>by position</em>,
 * so a dropped entry renumbers every entry after it and scrambles terrain. A
 * {@code LEVEL_STEM} id is not positional in anything. A level's identity on disk
 * is a directory name derived from its {@code ResourceKey}
 * ({@code DimensionType.getStorageFolder}), and a player's saved dimension is the
 * same identifier as a string. Nothing is indexed by a level stem's raw id.
 * Removing one would corrupt no save.
 *
 * <p><b>It is still refused, for two other reasons.</b> First, the null hole is a
 * defect introduced deliberately and then hidden with a second mixin, and every
 * consumer of {@code listElements()} — including ones in other mods — pays for
 * it. Second, and decisively: <b>the residue Fantasy pays that price to remove is
 * gone at the next boot anyway.</b> {@code WorldDimensions.bake(Registry)}
 * rebuilds {@code LEVEL_STEM} at every world load from the saved
 * {@code WorldGenSettings} map unioned with the datapack {@code dimension/}
 * files, and a runtime registration is in neither. A leftover stem is
 * <em>session-scoped</em>, unsynced, and costs one map entry. Paying a permanent
 * registry defect to reclaim it would be the worse trade.
 *
 * <p>What is actually removed is the {@code ServerLevel} — from
 * {@code server.levels}, which is the thing players can reach — and, for a
 * temporary dimension, its directory.
 *
 * <p>The price, stated rather than hidden: <b>an id cannot be re-opened with a
 * different generator in the same session.</b> Re-opening it with the same recipe
 * is fine and is what a restart-and-restore does; a different recipe is refused,
 * naming {@code MappedRegistry.remove}'s absence as the reason, and works after a
 * restart.
 *
 * <h2>5. Teardown matrix</h2>
 *
 * <p>In §5's format, because the honest answer to "is it really gone" is a table:
 *
 * <table border="1">
 * <caption>What closing a dimension takes away</caption>
 * <tr><th>Thing</th><th>Temporary</th><th>Persistent</th><th>Residue, and why</th></tr>
 * <tr><td>Players inside it</td><td colspan="2">Teleported to their respawn point before anything
 *     else happens</td>
 *     <td>None. A player left in a level being removed would be in a level with no server</td></tr>
 * <tr><td>The {@code ServerLevel}</td><td colspan="2">Removed from {@code server.levels}, saved
 *     (persistent) or not (temporary), {@code close()}d</td>
 *     <td>None reachable. {@code getLevel} returns null and {@code getAllLevels} no longer yields
 *         it</td></tr>
 * <tr><td>Non-player entities inside it</td><td>Gone with the region files</td><td>Saved, and back
 *     when it re-opens</td><td>Deliberate, and the difference between the two kinds</td></tr>
 * <tr><td>The {@code LevelStem} registry entry</td><td colspan="2"><b>Stays</b></td>
 *     <td>There is no {@code MappedRegistry.remove} (Decision 7). Unsynced, session-scoped, and
 *         rebuilt from scratch at the next world load — see §4</td></tr>
 * <tr><td>The dimension's directory</td><td>Deleted</td><td>Kept</td>
 *     <td>A temporary dimension that left its chunks behind would be a disk leak with no owner</td>
 *     </tr>
 * <tr><td>The ledger entry</td><td>Never written</td><td>Removed on a deliberate close</td>
 *     <td>A server killed between close and write re-opens an empty dimension once, then closes
 *         it</td></tr>
 * <tr><td>The {@code BorderChangeListener}</td><td colspan="2">Dies with the level</td>
 *     <td>None — {@code PlayerList.addWorldborderListener} adds it to the <em>new</em> level's own
 *         border, not the overworld's. Verified, because the opposite would have leaked one
 *         listener per dimension ever opened</td></tr>
 * <tr><td>Chunks still loading when close was asked for</td><td colspan="2">Waited out</td>
 *     <td>Up to {@value #DRAIN_DEADLINE_TICKS} ticks. Past that the level is removed anyway and the
 *         loaded-chunk and pending-task counts are logged, because a dimension that never closes is
 *         worse than one that closes loudly</td></tr>
 * </table>
 *
 * <h2>6. Persistence</h2>
 *
 * <p>Fantasy's persistent worlds are not auto-restored — the caller must re-open
 * them by id after a restart. VibeMod does restore them, from
 * {@link DimensionLedger}, which records the {@code LevelStem} as JSON written by
 * vanilla's own {@code LevelStem.CODEC}. See that class for why the record lives
 * there rather than in {@code RegistryLedger}, and for why the shape it writes is
 * deliberately the shape of a datapack {@code dimension/} file.
 *
 * <p><b>Threading:</b> server thread only, like {@code DynamicSeam} and
 * {@code PaletteGuard}. Opening unfreezes a registry for the same microseconds
 * and under the same accepted risk §9 records for the block window.
 */
public final class DimensionSeam {

    private static final Logger LOG = Logger.getLogger("VibeMod.Dimension");

    /**
     * How long a close waits for a level to go genuinely idle before removing it
     * anyway.
     *
     * <p>Ten seconds. Long enough for player tickets to expire and the chunk
     * cache to write and drop what it holds; short enough that a
     * {@code /vibe delete} does not appear to hang. The overrun is logged with
     * the exact counts rather than swallowed.
     */
    private static final int DRAIN_DEADLINE_TICKS = 200;

    /** Whether a closed dimension's chunks are kept. The one axis that matters to a caller. */
    public enum Persistence {
        /** Directory deleted on close. Nothing is recorded and nothing comes back. */
        TEMPORARY,
        /** Directory kept, recipe recorded, re-opened at the next boot. */
        PERSISTENT
    }

    /** What happened. */
    public enum Status {
        /** The level exists, ticks, and is in {@code server.levels}. */
        OPENED,
        /** It was already open; the existing level is unchanged. */
        ALREADY_OPEN,
        /** Nothing was created or destroyed. */
        REFUSED,
        /** Close accepted; players are being evacuated and the level goes when it is idle. */
        CLOSING,
        /** Removed from {@code server.levels}. */
        CLOSED,
        /** Something went wrong after the point of no return. Named, never hidden. */
        FAILED
    }

    /** One outcome, with enough detail for a chat line or a log. */
    public record Result(Identifier id, Status status, String detail) {
        /** True when the caller got what it asked for. */
        public boolean ok() {
            return status == Status.OPENED || status == Status.ALREADY_OPEN
                    || status == Status.CLOSING || status == Status.CLOSED;
        }
    }

    /** One live runtime dimension. */
    private record Open(Identifier id, ResourceKey<net.minecraft.world.level.Level> levelKey,
                        ServerLevel level, Persistence persistence, String modName) {
    }

    /** One dimension on its way out. */
    private static final class Closing {
        private final Open open;
        private final String reason;
        private final long deadlineTick;

        private Closing(Open open, String reason, long deadlineTick) {
            this.open = open;
            this.reason = reason;
            this.deadlineTick = deadlineTick;
        }
    }

    private final DimensionRoster roster;
    private final Map<Identifier, Open> open = new LinkedHashMap<>();
    private final Map<Identifier, Closing> closing = new LinkedHashMap<>();
    private final AtomicInteger refusals = new AtomicInteger();
    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();
    private DimensionLedger ledger;
    private long tick;

    public DimensionSeam(DimensionRoster roster) {
        this.roster = roster;
    }

    /** The ledger, once a world is open. Null before {@link #restore}. */
    public DimensionLedger ledger() {
        return ledger;
    }

    // ------------------------------------------------------------------ opening

    /**
     * Creates a dimension from a dimension type and a chunk generator.
     *
     * <p>Server thread only.
     *
     * @param server      the running server
     * @param id          the dimension's id; it becomes both the {@code LEVEL_STEM} key and the
     *                    {@code ResourceKey<Level>}, exactly as vanilla derives the nether's
     * @param type        the dimension type. It must be a registry holder — a direct holder has no
     *                    id to put on the wire
     * @param generator   the chunk generator, whichever kind
     * @param persistence whether the chunks survive a close
     * @param modName     the mod asking, for attribution in messages
     */
    public Result open(MinecraftServer server, Identifier id, Holder<DimensionType> type,
                       ChunkGenerator generator, Persistence persistence, String modName) {
        if (type == null || generator == null) {
            return refuse(id, modName, "a dimension needs both a type and a generator");
        }
        return open(server, id, new LevelStem(type, generator), persistence, modName);
    }

    /**
     * Creates a dimension from a whole {@code LevelStem}, which is the form
     * vanilla's own datapack files decode to.
     *
     * <p>Everything that can fail without consequence happens before the registry
     * is unfrozen, for the reason {@code DynamicSeam} gives: a registration cannot
     * be taken back, so the rollback has to live before the write.
     */
    public Result open(MinecraftServer server, Identifier id, LevelStem stem,
                       Persistence persistence, String modName) {
        if (server == null) {
            return refuse(id, modName, "there is no running server to open it on");
        }
        if (id == null || stem == null) {
            return refuse(id, modName, "a dimension needs an id and a level stem");
        }
        if ("minecraft".equals(id.getNamespace())) {
            return refuse(id, modName, "the minecraft namespace belongs to the game. A generated"
                    + " dimension must live under its own mod's namespace");
        }
        if (!LevelTickGuard.isArmed()) {
            return refuse(id, modName, "the tickChildren level-iteration guard has not run yet."
                    + " Either the server has not ticked, or MinecraftServerTickLevelsMixin's"
                    + " @Redirect (require = 0) did not apply on this game version — in which case"
                    + " adding a level to MinecraftServer.levels would throw"
                    + " ConcurrentModificationException out of vanilla's tick loop the moment a"
                    + " generated mod opened one from inside a level tick. Opening is refused"
                    + " rather than gambled on");
        }
        if (closing.containsKey(id)) {
            return refuse(id, modName, "it is currently being closed; re-open it once it is gone");
        }
        Open live = open.get(id);
        if (live != null) {
            return new Result(id, Status.ALREADY_OPEN,
                    "already open, created by " + live.modName());
        }

        ResourceKey<LevelStem> stemKey = ResourceKey.create(Registries.LEVEL_STEM, id);
        ResourceKey<net.minecraft.world.level.Level> levelKey =
                ResourceKey.create(Registries.DIMENSION, id);
        if (server.getLevel(levelKey) != null) {
            return refuse(id, modName, "this server already has a level with that id, and it is not"
                    + " one VibeMod opened. VibeMod never adopts a level it did not create");
        }

        RegistryAccess.Frozen access = server.registryAccess();
        Optional<Registry<LevelStem>> maybe = access.lookup(Registries.LEVEL_STEM);
        if (maybe.isEmpty()) {
            return refuse(id, modName, "this world has no " + Registries.LEVEL_STEM.identifier()
                    + " registry");
        }
        Registry<LevelStem> registry = maybe.get();

        // The dimension type has to be a registry holder or nothing downstream
        // can name it: DimensionType.STREAM_CODEC is holderRegistry, which writes
        // an index and nothing else.
        Identifier typeId = stem.type().unwrapKey().map(ResourceKey::identifier).orElse(null);
        if (typeId == null) {
            return refuse(id, modName, "its dimension type is a direct Holder with no registry key."
                    + " DimensionType.STREAM_CODEC is ByteBufCodecs.holderRegistry — it can only put"
                    + " a registry index on the wire, so a type that is not in the registry cannot"
                    + " reach any client. Register the type first (Phase 5 does this) and name it");
        }
        if (!access.lookupOrThrow(Registries.DIMENSION_TYPE).containsKey(typeId)) {
            return refuse(id, modName, "its dimension type " + typeId + " is not in this world's"
                    + " dimension_type registry");
        }

        if (persistence == Persistence.PERSISTENT && ledger == null) {
            return refuse(id, modName, "the dimension ledger is not open yet, so a persistent"
                    + " dimension could not be recorded and would not come back after a restart."
                    + " DimensionContent.serverStarted has to have run first. Recording nothing and"
                    + " calling it persistent would be a silent drop");
        }

        // Encode BEFORE anything is written, for two reasons: a persistent
        // dimension whose recipe cannot be written down would come back as a
        // directory nothing can open, and encoding is the only thing here that
        // can fail on the caller's data rather than on ours.
        DataResult<JsonElement> result = LevelStem.CODEC.encodeStart(
                RegistryOps.create(JsonOps.INSTANCE, access), stem);
        Optional<DataResult.Error<JsonElement>> error = result.error();
        if (error.isPresent() && persistence == Persistence.PERSISTENT) {
            return refuse(id, modName, "it cannot be recorded for a restart: its LevelStem does"
                    + " not encode through LevelStem.CODEC (" + firstLine(error.get().message())
                    + "). A persistent dimension whose recipe cannot be written down would come"
                    + " back after a restart as a directory nothing knows how to open. Open it"
                    + " as TEMPORARY, or give it a generator that serialises");
        }
        JsonElement encoded = result.result().orElse(null);

        // The registry write. Reuses Phase 5's shape exactly — including the two
        // repairs freeze() does and register() does not — rather than being a
        // second unfreeze with its own bugs.
        LevelStem existing = registry.getValue(stemKey);
        if (existing == null) {
            String problem = registerStem(registry, stemKey, stem);
            if (problem != null) {
                return refuse(id, modName, problem);
            }
        } else if (!sameRecipe(access, existing, stem, encoded)) {
            return refuse(id, modName, "the level_stem registry already holds a DIFFERENT recipe"
                    + " under that id, and there is no MappedRegistry.remove to replace it"
                    + " (Decision 7). The id keeps the recipe it was first opened with for the life"
                    + " of this process; a different generator under the same id works after a"
                    + " restart, or under a different id now");
        } else {
            // Same recipe, id already claimed: this is a re-open after a close,
            // which is the normal case and not worth a word to the caller.
            stem = existing;
        }

        // Past here, a failure is a FAILED rather than a REFUSED: the registry
        // has been written to and cannot be put back.
        ServerLevel level;
        try {
            level = construct(server, levelKey, stem);
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "Constructing the ServerLevel for " + id + " threw", t);
            return fail(id, "its ServerLevel could not be constructed: " + firstLine(t.getMessage())
                    + ". The level_stem entry stays in the registry — there is no"
                    + " MappedRegistry.remove — and is inert until something opens it again");
        }

        MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;
        accessor.getLevels().put(levelKey, level);
        level.getWorldBorder().setAbsoluteMaxSize(server.getAbsoluteMaxWorldSize());
        server.getPlayerList().addWorldborderListener(level);

        Open record = new Open(id, levelKey, level, persistence, modName);
        open.put(id, record);
        opened.incrementAndGet();

        if (persistence == Persistence.PERSISTENT && ledger != null) {
            ledger.record(id, modName, encoded);
        }

        // Last, and after the level is genuinely reachable: a listener that calls
        // server.getLevel(...) from inside this event must find it.
        ServerLevelEvents.LOAD.invoker().onLevelLoad(server, level);

        LOG.info("Mod " + modName + " opened dimension " + id + " (type " + typeId + ", "
                + persistence.name().toLowerCase(java.util.Locale.ROOT) + "). LEVEL_STEM is not in"
                + " RegistrySynchronization's networkable set, so no connected client was told"
                + " anything and none needed to be");
        return new Result(id, Status.OPENED, "created, ticking, and in server.levels");
    }

    /**
     * Vanilla's own non-overworld construction, argument for argument.
     *
     * <p>{@code DerivedLevelData} over the overworld's data, no custom spawners,
     * {@code tickTime = false} — which is what {@code createLevels} passes for the
     * nether and the end, and what makes a runtime dimension follow the
     * overworld's clock instead of running one of its own. The seed is
     * {@code BiomeManager.obfuscateSeed(options().seed())}, the same value every
     * other level in this world was built with, because two levels of one world
     * disagreeing about the seed would make structure placement disagree with
     * itself.
     */
    private static ServerLevel construct(MinecraftServer server,
                                         ResourceKey<net.minecraft.world.level.Level> levelKey,
                                         LevelStem stem) {
        MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;
        long seed = BiomeManager.obfuscateSeed(server.getWorldGenSettings().options().seed());
        return new ServerLevel(
                server,
                accessor.getExecutor(),
                accessor.getStorageSource(),
                new DerivedLevelData(server.getWorldData(), server.getWorldData().overworldData()),
                levelKey,
                stem,
                false,
                seed,
                List.of(),
                false);
    }

    /**
     * Whether two level stems are the same recipe, compared the way the game
     * itself would write them down.
     *
     * <p>{@code LevelStem} is a record, so {@code equals} compares its two
     * components — and a {@code ChunkGenerator} does not override {@code equals},
     * so that half is reference identity. Two decodes of one datapack file are
     * therefore never {@code equal}, which would make re-opening a dimension after
     * a close, or after a restore, refuse a recipe that is in fact identical.
     *
     * <p>Comparing the encoded JSON instead uses the only structural definition of
     * "the same stem" the game has: its own codec. When either side fails to
     * encode — a generator that is not serialisable, which is legal for a
     * temporary dimension — the comparison falls back to {@code equals}, and
     * refusing on a false negative is the safe direction: it costs a mod one
     * renamed id, where a false positive would silently hand it somebody else's
     * generator.
     */
    private static boolean sameRecipe(RegistryAccess.Frozen access, LevelStem existing,
                                      LevelStem wanted, JsonElement wantedJson) {
        if (existing.equals(wanted)) {
            return true;
        }
        if (wantedJson == null) {
            return false;
        }
        return LevelStem.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, access), existing)
                .result()
                .map(wantedJson::equals)
                .orElse(false);
    }

    /**
     * Phase 5's unfreeze, applied to one more registry.
     *
     * <p>The two repairs at the end are the ones {@code freeze()} does that
     * {@code register()} does not, and they are done here for consistency with
     * {@code DynamicSeam} rather than because a level stem is ever tagged or ever
     * carries data components. Consistency is the point: a second unfreeze that
     * skips half the repairs is how a subtle registry bug gets written.
     *
     * @return null on success, or the refusal text
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String registerStem(Registry<LevelStem> registry, ResourceKey<LevelStem> key,
                                       LevelStem stem) {
        if (!(registry instanceof MappedRegistry<LevelStem>)) {
            return Registries.LEVEL_STEM.identifier() + " is not a MappedRegistry on this game"
                    + " version, so it cannot be appended to";
        }
        MappedRegistryAccessor accessor = (MappedRegistryAccessor) registry;
        boolean wasFrozen = accessor.isFrozen();
        try {
            accessor.setFrozen(false);
            ((MappedRegistry) registry).register(key, stem, RegistrationInfo.BUILT_IN);
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "Could not register level stem " + key.identifier(), t);
            return "the level_stem registry refused the write: " + firstLine(t.getMessage());
        } finally {
            accessor.setFrozen(wasFrozen);
        }
        try {
            accessor.invokeRefreshTagsInHolders();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Registered level stem " + key.identifier()
                    + " but could not rebind tags on the level_stem registry. Nothing tags a level"
                    + " stem, so this is recorded rather than fatal", t);
        }
        return null;
    }

    // ------------------------------------------------------------------ closing

    /**
     * Asks for a dimension to go away.
     *
     * <p>Returns {@link Status#CLOSING}, not {@code CLOSED}, and that is the
     * honest answer: players have to be teleported out and the chunk cache has to
     * finish what it is doing before the level can be removed. {@link #tick}
     * finishes the job, typically within a tick or two of the last player
     * leaving.
     */
    public Result close(MinecraftServer server, Identifier id, String reason) {
        if (server == null) {
            return refuse(id, reason, "there is no running server");
        }
        if (closing.containsKey(id)) {
            return new Result(id, Status.CLOSING, "already closing");
        }
        Open record = open.get(id);
        if (record == null) {
            return refuse(id, reason, "VibeMod did not open a dimension with that id, and it never"
                    + " closes a level it did not create");
        }
        closing.put(id, new Closing(record, reason, tick + DRAIN_DEADLINE_TICKS));
        open.remove(id);
        evacuate(server, record);
        LOG.info("Closing dimension " + id + " (" + reason + "): players evacuated, waiting for the"
                + " level to go idle");
        return new Result(id, Status.CLOSING, "players evacuated; the level is removed once its"
                + " chunks are done");
    }

    /**
     * One tick of the drain, from the host's existing {@code END_SERVER_TICK}
     * subscription — the same one {@code ReloadCoordinator}, {@code PaletteGuard}
     * and {@code DynamicContent} ride.
     *
     * <p>Deliberately <b>not</b> inside {@code tickChildren}: this runs after the
     * level loop has finished, so a removal here does not depend on the iteration
     * guard at all. The guard exists for the other caller — a generated mod
     * opening or closing a dimension from inside a block or entity tick.
     */
    public void tick(MinecraftServer server) {
        tick++;
        if (closing.isEmpty() || server == null) {
            return;
        }
        for (Closing pending : new ArrayList<>(closing.values())) {
            ServerLevel level = pending.open.level();
            // Re-run every tick: another mod can teleport a player into a level
            // right up until it leaves the map.
            if (!level.players().isEmpty()) {
                evacuate(server, pending.open);
            }
            ServerChunkCache chunks = level.getChunkSource();
            boolean idle = level.players().isEmpty()
                    && chunks.getLoadedChunksCount() == 0
                    && chunks.getPendingTasksCount() == 0
                    && !chunks.hasActiveTickets();
            boolean overdue = tick >= pending.deadlineTick;
            if (!idle && !overdue) {
                continue;
            }
            if (overdue && !idle) {
                LOG.warning("Dimension " + pending.open.id() + " did not go idle within "
                        + DRAIN_DEADLINE_TICKS + " ticks and is being removed anyway: "
                        + level.players().size() + " player(s), " + chunks.getLoadedChunksCount()
                        + " loaded chunk(s), " + chunks.getPendingTasksCount() + " pending chunk"
                        + " task(s), activeTickets=" + chunks.hasActiveTickets()
                        + ". A dimension that never closes is worse than one that closes loudly");
            }
            closing.remove(pending.open.id());
            remove(server, pending.open, pending.reason, true);
        }
    }

    /**
     * Everyone out, to where they would go if they died.
     *
     * <p>{@code findRespawnPositionAndUseSpawnBlock(false, …)} — {@code false} so
     * a respawn anchor is not silently drained by a teleport nobody asked for. If
     * that answer points back into the level being closed, which it does whenever
     * a player set their spawn inside it, the fallback is the server's respawn
     * dimension and its own respawn point. Built by hand rather than through
     * {@code TeleportTransition.missingRespawnBlock}, whose
     * {@code missingRespawnBlock = true} flag makes the client say the player's
     * bed was destroyed — which would be a lie.
     */
    private static void evacuate(MinecraftServer server, Open record) {
        ServerLevel dying = record.level();
        for (ServerPlayer player : new ArrayList<>(dying.players())) {
            TeleportTransition where =
                    player.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING);
            if (where == null || where.newLevel() == dying) {
                ServerLevel home = server.findRespawnDimension();
                LevelData.RespawnData spawn = home.getRespawnData();
                where = new TeleportTransition(home, Vec3.atBottomCenterOf(spawn.pos()), Vec3.ZERO,
                        spawn.yaw(), spawn.pitch(), TeleportTransition.DO_NOTHING);
            }
            LOG.info("Moving " + player.getName().getString() + " out of " + record.id()
                    + " into " + where.newLevel().dimension().identifier() + " before it closes");
            player.teleport(where);
        }
    }

    /**
     * The point of no return, in the order the residue table promises.
     *
     * <p>{@code UNLOAD} first, while {@code server.getLevel(...)} still answers,
     * so a listener can do something about it. Then out of the map, then saved or
     * not, then closed, then — temporary only — deleted.
     *
     * <p><b>{@code forgetRecord} is the difference between "the mod closed it" and
     * "the server stopped".</b> A deliberate close means the dimension is over and
     * its ledger entry goes with it; a shutdown means every persistent dimension
     * has to still be recorded when the process comes back, or the feature's whole
     * point is lost on the first restart. Same code path, one flag, and it is
     * spelled out because getting it backwards would silently un-persist every
     * persistent dimension on the server's very first clean stop.
     */
    private void remove(MinecraftServer server, Open record, String reason, boolean forgetRecord) {
        ServerLevel level = record.level();
        try {
            ServerLevelEvents.UNLOAD.invoker().onLevelUnload(server, level);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "A ServerLevelEvents.UNLOAD listener threw for " + record.id()
                    + "; the level is removed regardless", t);
        }

        ((MinecraftServerAccessor) server).getLevels().remove(record.levelKey());

        if (record.persistence() == Persistence.TEMPORARY) {
            // Set before close(), which is what actually flushes: a temporary
            // dimension writing its chunks out and then having them deleted is
            // just slower.
            level.noSave = true;
        } else {
            try {
                level.save(null, true, false);
            } catch (Throwable t) {
                LOG.log(Level.SEVERE, "Saving " + record.id() + " before close threw. Its chunks may"
                        + " be a few ticks stale; the directory is NOT deleted", t);
            }
        }
        try {
            level.close();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Closing " + record.id() + "'s chunk source threw", e);
        }

        String residue;
        if (record.persistence() == Persistence.TEMPORARY) {
            // Temporary means temporary in both directions: a shutdown deletes
            // the directory too, or every session would leak one chunk folder.
            residue = deleteDirectory(server, record);
        } else if (forgetRecord) {
            if (ledger != null) {
                ledger.forget(record.id());
            }
            residue = "its chunks are on disk; the ledger entry is gone, so it does NOT re-open"
                    + " at the next boot unless something opens it again";
        } else {
            residue = "its chunks are on disk and its ledger entry stands, so it re-opens at the"
                    + " next boot";
        }
        closed.incrementAndGet();
        LOG.info("Closed dimension " + record.id() + " (" + reason + "). The LevelStem entry stays"
                + " in the registry — no MappedRegistry.remove, Decision 7 — and disappears by"
                + " itself at the next world load, because WorldDimensions.bake rebuilds LEVEL_STEM"
                + " from the saved WorldGenSettings and the datapack dimension/ files, and a runtime"
                + " registration is in neither. " + residue);
    }

    /**
     * A temporary dimension's chunks, gone.
     *
     * <p>The path comes from {@code LevelStorageAccess.getDimensionPath}, which is
     * the same function vanilla uses to decide where to write them, so this
     * deletes exactly what was written and nothing above it. It is checked against
     * the world root anyway, because a path bug here deletes somebody's world.
     */
    private static String deleteDirectory(MinecraftServer server, Open record) {
        LevelStorageSource.LevelStorageAccess storage =
                ((MinecraftServerAccessor) server).getStorageSource();
        Path dir;
        try {
            dir = storage.getDimensionPath(record.levelKey()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return "its directory could not be located and is left on disk: " + firstLine(e.getMessage());
        }
        Path root = storage.getLevelPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .toAbsolutePath().normalize();
        if (!dir.startsWith(root) || dir.equals(root)) {
            LOG.severe("Refusing to delete " + dir + " for temporary dimension " + record.id()
                    + ": it is not strictly inside the world directory " + root);
            return "its directory was outside the world folder and was NOT deleted";
        }
        if (!Files.isDirectory(dir)) {
            return "it never wrote a directory, so there was nothing to delete";
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not fully delete " + dir, e);
            return "its directory is partly or wholly still on disk at " + dir;
        }
        return "its directory " + dir + " was deleted";
    }

    // ------------------------------------------------------------------ teleport

    /**
     * Puts a player in a runtime dimension, or refuses and says why.
     *
     * <p>This is the method the {@link DimensionRoster} gate exists for. Every
     * other path into a dimension — a portal, {@code /execute in}, another mod's
     * teleport — bypasses it, and there is no honest way to claim otherwise from
     * here: the refusal protects VibeMod's own door, not every door.
     */
    public Result teleport(ServerPlayer player, Identifier id, Vec3 position,
                           float yRot, float xRot) {
        if (player == null || id == null || position == null) {
            return refuse(id, "teleport", "a teleport needs a player, a dimension and a position");
        }
        if (closing.containsKey(id)) {
            return refuse(id, "teleport", "that dimension is closing");
        }
        Open record = open.get(id);
        if (record == null) {
            return refuse(id, "teleport", "VibeMod has no open dimension with that id");
        }
        Identifier typeId = record.level().dimensionTypeRegistration().unwrapKey()
                .map(ResourceKey::identifier).orElse(null);
        String blocked = roster.refusalFor(player, typeId);
        if (blocked != null) {
            return refuse(id, "teleport", "cannot send " + player.getName().getString()
                    + " there: " + blocked);
        }
        player.teleport(new TeleportTransition(record.level(), position, Vec3.ZERO, yRot, xRot,
                TeleportTransition.DO_NOTHING));
        return new Result(id, Status.OPENED, "teleported " + player.getName().getString());
    }

    /** Whether this player could be sent to this dimension right now, and why not. */
    public String teleportRefusal(ServerPlayer player, Identifier id) {
        Open record = open.get(id);
        if (record == null) {
            return "VibeMod has no open dimension with that id";
        }
        return roster.refusalFor(player, record.level().dimensionTypeRegistration().unwrapKey()
                .map(ResourceKey::identifier).orElse(null));
    }

    // ------------------------------------------------------------------ boot and shutdown

    /**
     * Re-opens every dimension the ledger records, once, at {@code SERVER_STARTED}.
     *
     * <p>Idempotent by construction: {@link #open} returns {@code ALREADY_OPEN}
     * for anything the server already has, which is what makes this safe to run on
     * a world whose datapacks have since grown a real
     * {@code data/&lt;ns&gt;/dimension/&lt;name&gt;.json} for the same id. On that
     * day vanilla creates the level itself at boot and this method quietly does
     * nothing — which is the direction the whole content pipeline is going, and
     * why the ledger writes the file in a datapack's shape.
     */
    public List<Result> restore(MinecraftServer server) {
        ledger = new DimensionLedger(server.getWorldPath(
                net.minecraft.world.level.storage.LevelResource.ROOT));
        List<Result> results = new ArrayList<>();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        for (DimensionLedger.Entry entry : ledger.entries()) {
            ResourceKey<net.minecraft.world.level.Level> levelKey =
                    ResourceKey.create(Registries.DIMENSION, entry.id());
            if (server.getLevel(levelKey) != null) {
                results.add(new Result(entry.id(), Status.ALREADY_OPEN,
                        "vanilla already loaded it from a datapack file"));
                continue;
            }
            DataResult<LevelStem> decoded = LevelStem.CODEC.parse(ops, entry.stem());
            Optional<DataResult.Error<LevelStem>> error = decoded.error();
            if (error.isPresent()) {
                // Not fatal, and deliberately not destructive: the record and the
                // directory both stay. A dimension whose generator no longer
                // decodes — a game update, a removed biome — is a thing to be told
                // about, not a thing to delete somebody's chunks over.
                LOG.severe("Not re-opening " + entry.id() + ": its recorded LevelStem no longer"
                        + " decodes against this world's registries ("
                        + firstLine(error.get().message()) + "). Its chunks are untouched on disk"
                        + " and its ledger entry is untouched; fix the cause and it comes back");
                results.add(new Result(entry.id(), Status.FAILED,
                        "its recorded recipe no longer decodes"));
                continue;
            }
            results.add(open(server, entry.id(), decoded.result().orElseThrow(),
                    Persistence.PERSISTENT, entry.modName()));
        }
        if (!results.isEmpty()) {
            LOG.info("Re-opened " + results.stream().filter(Result::ok).count() + " of "
                    + results.size() + " recorded dimension(s)");
        }
        return results;
    }

    /**
     * Closes every runtime dimension, for a server stop or a wholesale teardown.
     *
     * <p>Synchronous and without the drain: at shutdown there is no tick left to
     * drain on, and vanilla is about to close every level anyway. The evacuation
     * still runs, so a player logging out inside a temporary dimension does not
     * log back in inside one that no longer exists.
     */
    public void closeAll(MinecraftServer server, String reason) {
        for (Open record : new ArrayList<>(open.values())) {
            open.remove(record.id());
            evacuate(server, record);
            // forgetRecord = false: a shutdown is not a decision to stop
            // persisting anything. Every persistent dimension has to still be in
            // the ledger when the process comes back.
            remove(server, record, reason, false);
        }
        for (Closing pending : new ArrayList<>(closing.values())) {
            closing.remove(pending.open.id());
            // These WERE deliberately closed; the shutdown only cut the drain
            // short, so their records still go.
            remove(server, pending.open, reason, true);
        }
    }

    // ------------------------------------------------------------------ bookkeeping

    private Result refuse(Identifier id, String who, String why) {
        refusals.incrementAndGet();
        LOG.warning("Refusing dimension " + id + " from " + who + ": " + why);
        return new Result(id, Status.REFUSED, why);
    }

    private Result fail(Identifier id, String why) {
        LOG.severe("Dimension " + id + " FAILED after the point of no return: " + why);
        return new Result(id, Status.FAILED, why);
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "no detail";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    /** The ids currently open, in the order they were opened. */
    public List<Identifier> openIds() {
        return List.copyOf(open.keySet());
    }

    /**
     * Counts for the gates, e.g.
     * {@code "dimOpen=1 dimClosing=0 dimOpened=1 dimClosed=0 dimRefused=0"}.
     *
     * <p>{@code name=value} throughout and stable names, the same contract
     * {@code DynamicSeam.describeState()} and {@code RegistrySeam.describeState()}
     * keep — the gates match on prefixes of this string rather than parsing it.
     */
    public String describeState() {
        return "dimOpen=" + open.size()
                + " dimClosing=" + closing.size()
                + " dimOpened=" + opened.get()
                + " dimClosed=" + closed.get()
                + " dimRefused=" + refusals.get();
    }
}
