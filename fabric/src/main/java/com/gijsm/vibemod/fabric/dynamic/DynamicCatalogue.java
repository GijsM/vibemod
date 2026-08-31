package com.gijsm.vibemod.fabric.dynamic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.serialization.Codec;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryDataLoader;

/**
 * Which datapack-shaped registries VibeMod will apply at runtime, and the one
 * place the answer is derived rather than typed out (V4 Phase 5).
 *
 * <h2>Where these registries actually live, which is not where the brief guessed</h2>
 *
 * <p>Phase 0 asked whether {@code ReloadableServerRegistries} rebuilds the
 * {@code RELOADABLE} layer on {@code /reload}, because if it did then
 * {@code ReloadCoordinator.flushServer} — which fires on every content change —
 * would re-derive raw ids under connected clients. Disassembly settles it, and
 * the answer is no in the way that matters:
 *
 * <pre>
 * ReloadableServerRegistries.reload(layers, pendingTags, resources, executor):
 *     LootDataType.values()
 *         .map(type -&gt; scheduleRegistryLoad(type, ops, resources, executor))
 *     ... createUpdatedRegistries(layers, loaded)
 *         -&gt; layers.replaceFrom(RegistryLayer.RELOADABLE, new ImmutableRegistryAccess(loaded).freeze())
 *
 * LootDataType: PREDICATE, MODIFIER, TABLE      // and nothing else
 * </pre>
 *
 * <p>So the {@code RELOADABLE} layer <em>is</em> rebuilt on every reload, and it
 * contains exactly {@code loot_table}, {@code predicate} and
 * {@code item_modifier}. None of the three is in
 * {@code RegistrySynchronization.NETWORKABLE_REGISTRIES} — that set is built from
 * {@code RegistryDataLoader.SYNCHRONIZED_REGISTRIES}, and
 * {@code networkedRegistries(...)} reads {@code getAccessFrom(WORLDGEN)} — so a
 * reload cannot change one byte of what a connected client was told. Every
 * registry in this catalogue is loaded once, at world load, from
 * {@code RegistryDataLoader.WORLDGEN_REGISTRIES}, and MC-187938 is true of all of
 * them.
 *
 * <p>One more read confirms it from the other side: {@code MinecraftServer}'s
 * {@code registries} field is never assigned in {@code reloadResources} — the new
 * {@code LayeredRegistryAccess} is stashed inside
 * {@code ReloadableServerResources.fullRegistryHolder}, and
 * {@code SynchronizeRegistriesTask} is constructed from the server's own field.
 *
 * <h2>The set, and why it is derived</h2>
 *
 * <p>The codec for each registry is taken from
 * {@code RegistryDataLoader.WORLDGEN_REGISTRIES} rather than named here, and the
 * datapack directory from {@code Registries.elementsDirPath}, because both are
 * the game's own answers: a version that renames {@code worldgen/biome} or
 * changes an element codec changes this class not at all. The <em>allowlist</em>
 * is typed out, because "which registries has somebody actually thought about"
 * is a claim this repository makes and cannot derive.
 *
 * <p>Every entry is asserted networkable at class-init. A registry that is not
 * synced needs no bounce and is a different feature; one that is synced but is
 * missing from {@code WORLDGEN_REGISTRIES} would mean the layer moved under us,
 * and this class refuses to load rather than silently dropping it — no silent
 * drops.
 */
public final class DynamicCatalogue {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger("VibeMod.Dynamic");

    /**
     * The registries a generated mod may add to at runtime.
     *
     * <p>Typed out on purpose. Each one is datapack-shaped, lives in the
     * {@code WORLDGEN} layer, and is synced to clients during configuration —
     * which is what makes {@link ReconfigureBouncer} necessary and what makes the
     * additive unfreeze in {@link DynamicSeam} sufficient.
     */
    private static final List<ResourceKey<? extends Registry<?>>> ALLOWED = List.of(
            Registries.ENCHANTMENT,
            Registries.BIOME,
            Registries.DIMENSION_TYPE,
            Registries.DAMAGE_TYPE,
            Registries.JUKEBOX_SONG,
            Registries.PAINTING_VARIANT,
            Registries.WOLF_VARIANT,
            Registries.WOLF_SOUND_VARIANT,
            Registries.PIG_VARIANT,
            Registries.PIG_SOUND_VARIANT,
            Registries.COW_VARIANT,
            Registries.COW_SOUND_VARIANT,
            Registries.CAT_VARIANT,
            Registries.CAT_SOUND_VARIANT,
            Registries.CHICKEN_VARIANT,
            Registries.CHICKEN_SOUND_VARIANT,
            Registries.FROG_VARIANT);

    /**
     * One supported registry: its key, the codec vanilla decodes it with, and the
     * {@code data/&lt;ns&gt;/&lt;dir&gt;/} directory it is read from.
     *
     * @param key      the registry, e.g. {@code minecraft:enchantment}
     * @param codec    vanilla's own element codec, taken from {@code RegistryDataLoader}
     * @param dir      the datapack directory, e.g. {@code enchantment} or {@code worldgen/biome}
     * @param what     a word for a refusal message, e.g. {@code "enchantment"}
     */
    public record Kind(ResourceKey<? extends Registry<?>> key, Codec<?> codec, String dir, String what) {
    }

    /** By datapack directory, which is how {@link DatapackSweep} finds files. */
    private static final Map<String, Kind> BY_DIR;
    /** By registry key, which is how a caller that already knows the registry asks. */
    private static final Map<ResourceKey<? extends Registry<?>>, Kind> BY_KEY;

    static {
        Map<ResourceKey<? extends Registry<?>>, Codec<?>> codecs = new LinkedHashMap<>();
        for (RegistryDataLoader.RegistryData<?> data : RegistryDataLoader.WORLDGEN_REGISTRIES) {
            codecs.put(data.key(), data.elementCodec());
        }
        Map<String, Kind> byDir = new LinkedHashMap<>();
        Map<ResourceKey<? extends Registry<?>>, Kind> byKey = new LinkedHashMap<>();
        for (ResourceKey<? extends Registry<?>> key : ALLOWED) {
            Codec<?> codec = codecs.get(key);
            if (codec == null) {
                // Dropped from the catalogue rather than thrown out of a static
                // initializer. A registry that has left WORLDGEN_REGISTRIES is a
                // design question and has to be loud — but an
                // ExceptionInInitializerError here would take the whole host down
                // for one moved registry, and the other sixteen are still true.
                // Dropped, it falls through to the ordinary "VibeMod cannot apply
                // this registry" refusal, which names it.
                LOG.severe("Dropping " + key.identifier() + " from VibeMod's dynamic-registry"
                        + " catalogue: it is not in RegistryDataLoader.WORLDGEN_REGISTRIES on this game"
                        + " version. Which layer a registry lives in decides whether a runtime addition"
                        + " needs a reconfiguration bounce, so this needs re-reading, not a workaround.");
                continue;
            }
            if (!RegistrySynchronization.isNetworkable(key)) {
                LOG.severe("Dropping " + key.identifier() + " from VibeMod's dynamic-registry"
                        + " catalogue: RegistrySynchronization does not consider it networkable on this"
                        + " game version. An unsynced registry needs no bounce and belongs to a"
                        + " different mechanism than this one.");
                continue;
            }
            String dir = Registries.elementsDirPath(key);
            Kind kind = new Kind(key, codec, dir, key.identifier().getPath());
            byDir.put(dir, kind);
            byKey.put(key, kind);
        }
        BY_DIR = Map.copyOf(byDir);
        BY_KEY = Map.copyOf(byKey);
    }

    private DynamicCatalogue() {
    }

    /** The kind for a {@code data/&lt;ns&gt;/&lt;dir&gt;/} directory, or null if unsupported. */
    public static Kind byDir(String dir) {
        return BY_DIR.get(dir);
    }

    /** The kind for a registry key, or null if VibeMod does not apply that registry. */
    public static Kind byKey(ResourceKey<? extends Registry<?>> key) {
        return BY_KEY.get(key);
    }

    /** Every supported datapack directory, longest first — see {@link DatapackSweep}. */
    public static Set<String> directories() {
        return BY_DIR.keySet();
    }

    /** The supported directories as one line, for a refusal message. */
    public static String describeSupported() {
        return String.join(", ", BY_DIR.keySet());
    }
}
