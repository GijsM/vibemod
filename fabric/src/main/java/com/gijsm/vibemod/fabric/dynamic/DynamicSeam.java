package com.gijsm.vibemod.fabric.dynamic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.Strategy;

import com.gijsm.vibemod.fabric.mixin.MappedRegistryAccessor;
import com.gijsm.vibemod.fabric.mixin.StrategyAccessor;

/**
 * Datapack-shaped content becomes real without a world reload, by <b>appending to
 * the live {@code MappedRegistry}</b> — never by swapping a registry layer
 * (V4 Phase 5).
 *
 * <h2>1. Why not {@code LayeredRegistryAccess.replaceFrom}</h2>
 *
 * <p>{@code replaceFrom} is public and is what vanilla itself uses for the
 * {@code RELOADABLE} layer, so it looks like the sanctioned door. It is not the
 * one to walk through here, and the reason is object identity rather than taste:
 * it builds <em>new</em> registry objects, and every live {@code Holder.Reference}
 * already handed out points at the old ones. Every {@code ItemStack}'s
 * enchantments component, every {@code Biome} holder in every loaded chunk's
 * biome container, every cached {@code DamageSource} holds a reference from the
 * previous generation. {@code Holder.Reference} identity <em>is</em> the equality
 * {@code Holder#is} and {@code HolderSet} membership use, so a layer swap
 * silently splits the world into holders from two generations — and, like the
 * hazard Decision 8 disqualified, it does not throw.
 *
 * <p>So this class reuses the machinery {@link com.gijsm.vibemod.fabric.shim.RegistrySeam}
 * already proved: {@code MappedRegistryAccessor.setFrozen(false)}, register,
 * {@code setFrozen(true)}, then the two repairs {@code freeze()} does that
 * {@code register()} does not — {@code refreshTagsInHolders()} and a fresh
 * {@code DataComponentLookup}. Calling {@code freeze()} again is not an option:
 * it throws {@code "Tags already present before freezing"} on a bound
 * {@code allTags}. What is <em>not</em> reused is the intrusive-holder map: the
 * worldgen registries are not built with
 * {@code registerDefaultedWithIntrusiveHolders}, so nothing writes to the
 * registry from a constructor and there is nothing to restore.
 *
 * <h2>2. Files first, registry second</h2>
 *
 * <p>Every entry this class applies has already been materialised as a datapack
 * file by {@code LoaderModContent.materialize}, and {@link DatapackSweep} reads it
 * back off disk. That is what shrinks the claim: the additive unfreeze bridges
 * only "until the next world load", after which vanilla loads the same file
 * itself, in its own deterministic order, and this class has nothing to do. The
 * sentence it has to defend is therefore not "we mutate dynamic registries at
 * runtime" but <b>"we apply now what the datapack on disk already declares"</b>.
 *
 * <p>Each entry is decoded with a {@code RegistryOps} over the <em>live</em>
 * {@code server.registryAccess()}, so a cross-reference — a damage type naming a
 * message id, a biome naming a placed feature — resolves against registries that
 * already hold every existing holder.
 *
 * <h2>3. The rollback lives before the write</h2>
 *
 * <p>There is no {@code MappedRegistry.remove} and there never was (Decision 7),
 * so a registration that has happened cannot be undone. Everything that can fail
 * is therefore done <em>first</em>: the JSON is parsed and the codec is run
 * against the live access before the registry is unfrozen, and a decode failure
 * never touches it. {@code getOrThrow} rather than {@code promotePartial}, for the
 * same reason §2.3 gives for chunk palettes — a partial decode is a silent drop.
 *
 * <p>What can still fail after the write is verification, and a verification
 * failure is recorded as {@link Status#FAILED} and flagged inactive rather than
 * pretended away: the id stays in the registry, is never offered to
 * {@link ReconfigureBouncer}, and is named in {@link #describeState()}. Never
 * remove, flag inactive.
 *
 * <h2>4. Biomes have their own palette, and it is 7 bits wide</h2>
 *
 * <p>{@code PalettedContainerFactory} holds a second {@code Strategy}, over
 * {@code Holder<Biome>}, whose {@code globalPaletteBitsInMemory} is
 * {@code Mth.ceillog2(biomeRegistry.size())} — computed once, exactly like the
 * block one. Vanilla ships about 65 biomes against a 7-bit ceiling of 128, so
 * the headroom is real but finite, and crossing it is the same hazard
 * {@code PaletteGuard} exists for. This class does not cross it: it refuses,
 * names the mechanism, and says that generalising {@code PaletteGuard}'s crossing
 * to {@code biomeStrategy()} is the fix. Refusing is honest; widening a strategy
 * that another class owns, from here, would not be.
 */
public final class DynamicSeam {

    private static final Logger LOG = Logger.getLogger("VibeMod.Dynamic");

    /** What happened to one entry. */
    public enum Status {
        /** Decoded, registered, verified. It is real and a bounce will carry it. */
        APPLIED,
        /** The registry already held this id — vanilla loaded it, or we did. */
        ALREADY_PRESENT,
        /** Nothing was written. The registry is exactly as it was. */
        REFUSED,
        /**
         * Written and then found wanting. The id cannot be removed, so it is
         * flagged inactive and never offered to a bounce.
         */
        FAILED
    }

    /** One entry's fate, with enough detail to put in a chat line or a log. */
    public record Result(String modName, ResourceKey<? extends Registry<?>> registry, Identifier id,
                         Status status, String detail) {

        /** True when this entry is new content a connected client does not have yet. */
        public boolean isNewContent() {
            return status == Status.APPLIED;
        }
    }

    /** Entries applied this session, in application order, by id. */
    private final Map<Identifier, Result> applied = new ConcurrentHashMap<>();
    /** Ids that were written and then failed verification. Never re-attempted. */
    private final Map<Identifier, String> inactive = new ConcurrentHashMap<>();
    private final AtomicInteger refusals = new AtomicInteger();

    /**
     * Applies one datapack-declared entry to the live registries.
     *
     * <p>Server thread only. It unfreezes a registry that the render thread may
     * be reading in singleplayer, for the same microseconds and under the same
     * accepted risk §9 already records for the block window.
     *
     * @param server  the running server; its {@code registryAccess()} is the decode context
     * @param kind    which registry, from {@link DynamicCatalogue}
     * @param id      the fully-namespaced id, as the datapack file path spells it
     * @param json    the file's contents
     * @param modName the mod the file came from, for attribution in messages
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result apply(MinecraftServer server, DynamicCatalogue.Kind kind, Identifier id,
                        String json, String modName) {
        if (server == null) {
            return refuse(modName, kind, id, "there is no running server to apply it to");
        }
        if (inactive.containsKey(id)) {
            return new Result(modName, kind.key(), id, Status.FAILED,
                    "already flagged inactive this session: " + inactive.get(id));
        }

        RegistryAccess.Frozen access = server.registryAccess();
        Optional<? extends Registry<?>> maybe = access.lookup((ResourceKey) kind.key());
        if (maybe.isEmpty()) {
            return refuse(modName, kind, id, "this world has no " + kind.key().identifier() + " registry");
        }
        Registry<?> registry = maybe.get();
        if (registry.containsKey(id)) {
            return new Result(modName, kind.key(), id, Status.ALREADY_PRESENT,
                    "the registry already holds it");
        }
        if (!(registry instanceof MappedRegistry<?>)) {
            return refuse(modName, kind, id, kind.key().identifier()
                    + " is not a MappedRegistry on this game version, so it cannot be appended to");
        }

        // Everything that can fail without consequence happens here, ABOVE the
        // unfreeze. A decode failure must leave the registry byte-for-byte as it
        // was, because a registration cannot be taken back.
        Object value;
        try {
            value = decode(access, kind.codec(), json);
        } catch (RuntimeException e) {
            return refuse(modName, kind, id, "it does not decode against the live registries: "
                    + firstLine(e.getMessage()));
        }

        if (Registries.BIOME.equals(kind.key())) {
            String crossing = biomeCrossingRefusal(server, registry.size());
            if (crossing != null) {
                return refuse(modName, kind, id, crossing);
            }
        }

        MappedRegistryAccessor accessor = (MappedRegistryAccessor) registry;
        boolean wasFrozen = accessor.isFrozen();
        ResourceKey entryKey = ResourceKey.create((ResourceKey) kind.key(), id);
        try {
            accessor.setFrozen(false);
            ((MappedRegistry) registry).register(entryKey, value, RegistrationInfo.BUILT_IN);
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "Could not register " + id + " into " + kind.key().identifier(), t);
            return refuse(modName, kind, id, "the registry refused the write: " + firstLine(t.getMessage()));
        } finally {
            accessor.setFrozen(wasFrozen);
        }

        // The two repairs freeze() does that register() does not. Without the
        // first, holder.is(TagKey) throws "Tags not bound" the first time
        // anything asks whether this enchantment is in #minecraft:curse.
        try {
            accessor.invokeRefreshTagsInHolders();
            accessor.setComponentLookup(new DataComponentLookup<>(accessor.getById()));
        } catch (Throwable t) {
            return fail(modName, kind, id,
                    "registered, but its tags could not be bound: " + firstLine(t.getMessage()), t);
        }

        String problem = verify(access, kind, registry, id);
        if (problem != null) {
            return fail(modName, kind, id, problem, null);
        }

        Result result = new Result(modName, kind.key(), id, Status.APPLIED,
                "decoded, registered and verified");
        applied.put(id, result);
        LOG.info("Mod " + modName + " added " + kind.what() + " " + id
                + " to the live registries (raw id " + rawIdOf(registry, id) + ")");
        return result;
    }

    // ------------------------------------------------------------------ verification

    /**
     * Everything that must be true before a bounce is worth a player's twenty
     * seconds. Returns null when it all is, or the first thing that is not.
     *
     * <p>The encode round-trip is the load-bearing one and it is not
     * speculative: {@code RegistrySynchronization.packRegistry} runs this exact
     * codec over this exact entry when the bounce reaches
     * {@code SynchronizeRegistriesTask}, and throws
     * {@code IllegalArgumentException} if it fails. Finding that out here costs a
     * log line; finding it out there costs every connected player their
     * connection, mid-configuration, with no way back.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private String verify(RegistryAccess.Frozen access, DynamicCatalogue.Kind kind,
                          Registry<?> registry, Identifier id) {
        Optional<? extends Holder.Reference<?>> holder = registry.get(id);
        if (holder.isEmpty()) {
            return "registered, but the registry does not hold it back";
        }
        Holder.Reference<?> reference = holder.get();
        if (!reference.isBound()) {
            return "registered, but its Holder.Reference never bound a value";
        }
        Object value = reference.value();
        int rawId = ((Registry) registry).getId(value);
        if (rawId < 0) {
            return "registered, but it has no raw id, so it cannot be put on the wire";
        }
        try {
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, access);
            DataResult<Tag> encoded = ((Codec) kind.codec()).encodeStart(ops, value);
            Optional<DataResult.Error<Tag>> error = encoded.error();
            if (error.isPresent()) {
                return "registered, but it does not re-encode for the wire ("
                        + error.get().message() + "), and SynchronizeRegistriesTask would throw"
                        + " IllegalArgumentException on it mid-configuration";
            }
        } catch (Throwable t) {
            return "registered, but re-encoding it for the wire threw: " + firstLine(t.getMessage());
        }
        return null;
    }

    /**
     * The biome palette's own boundary, refused rather than crossed.
     *
     * <p>Same shape as the block one and a different {@code Strategy}: vanilla's
     * ~65 biomes sit at 7 bits with a 128 ceiling. A container built under a
     * 7-bit biome strategy cannot represent holder id 128, and
     * {@code SimpleBitStorage.set} says so with
     * {@code Validate.inclusiveBetween(0L, mask, value)} — loud, which is the
     * only reason this is a refusal rather than a corruption.
     *
     * @return null when the biome fits, or the refusal text when it does not
     */
    private static String biomeCrossingRefusal(MinecraftServer server, int currentBiomes) {
        int needed = Mth.ceillog2(currentBiomes + 1);
        int narrowest = Integer.MAX_VALUE;
        for (ServerLevel level : server.getAllLevels()) {
            Strategy<Holder<net.minecraft.world.level.biome.Biome>> strategy =
                    level.palettedContainerFactory().biomeStrategy();
            narrowest = Math.min(narrowest, ((StrategyAccessor) strategy).getGlobalPaletteBitsInMemory());
        }
        if (narrowest == Integer.MAX_VALUE || needed <= narrowest) {
            return null;
        }
        return "adding it would take the biome registry to " + (currentBiomes + 1) + " entries, which needs "
                + needed + " bits, and every live biome PalettedContainer was built under a " + narrowest
                + "-bit Strategy whose globalPaletteBitsInMemory was computed once in its constructor."
                + " The next write of a wide biome id would throw out of SimpleBitStorage.set. Crossing"
                + " that boundary is exactly what PaletteGuard does for blockStatesStrategy(); until it"
                + " also does it for biomeStrategy(), this applies at the next world load instead";
    }

    // ------------------------------------------------------------------ decode

    /**
     * Vanilla's own codec over the live {@code RegistryAccess}.
     *
     * <p>{@code getOrThrow}, never {@code promotePartial}: a codec that partially
     * decodes hands back a shortened or defaulted value and a recoverable-error
     * line, which is the failure shape §2.3 spent a phase learning to distrust.
     */
    private static Object decode(RegistryAccess.Frozen access, Codec<?> codec, String json) {
        JsonElement element = JsonParser.parseString(json);
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, access);
        return codec.parse(ops, element).getOrThrow(message ->
                new IllegalArgumentException(message));
    }

    // ------------------------------------------------------------------ bookkeeping

    private Result refuse(String modName, DynamicCatalogue.Kind kind, Identifier id, String why) {
        refusals.incrementAndGet();
        LOG.warning("Refusing " + kind.what() + " " + id + " from " + modName + ": " + why);
        return new Result(modName, kind.key(), id, Status.REFUSED, why);
    }

    private Result fail(String modName, DynamicCatalogue.Kind kind, Identifier id, String why,
                        Throwable cause) {
        inactive.put(id, why);
        // SEVERE rather than WARNING, and it says the irreversible part out loud:
        // this id is in the registry for the life of the process and there is no
        // MappedRegistry.remove to take it back.
        LOG.log(Level.SEVERE, "Flagging " + kind.what() + " " + id + " from " + modName + " INACTIVE: "
                + why + ". The id cannot be removed from a live registry, so it stays claimed for this"
                + " session and is never offered to a reconfiguration bounce", cause);
        return new Result(modName, kind.key(), id, Status.FAILED, why);
    }

    /**
     * The wire id of a just-registered entry, for the log line.
     *
     * <p>Raw rather than generic because the registry's element type is a
     * wildcard here and there is nothing to gain from re-capturing it: the value
     * came out of this very registry a line ago.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int rawIdOf(Registry<?> registry, Identifier id) {
        Object value = registry.getValue(id);
        return value == null ? -1 : ((Registry) registry).getId(value);
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "no detail";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    /** Everything applied this session, in application order. */
    public List<Result> appliedEntries() {
        return List.copyOf(new LinkedHashMap<>(applied).values());
    }

    /** Ids that were written and then flagged inactive, with the reason. */
    public Map<Identifier, String> inactiveEntries() {
        return Map.copyOf(inactive);
    }

    /**
     * Counts for the gates, e.g.
     * {@code "dynamicApplied=2 dynamicInactive=0 dynamicRefused=0"}.
     *
     * <p>{@code name=value} throughout and stable names, because the gates match
     * on full prefixes of this string rather than parsing it — the same contract
     * {@code RegistrySeam.describeState()} keeps.
     */
    public String describeState() {
        return "dynamicApplied=" + applied.size()
                + " dynamicInactive=" + inactive.size()
                + " dynamicRefused=" + refusals.get();
    }
}
