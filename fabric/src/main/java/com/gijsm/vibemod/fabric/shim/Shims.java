package com.gijsm.vibemod.fabric.shim;

import java.util.logging.Logger;

import net.fabricmc.fabric.api.event.Event;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * The static methods a generated mod's rewritten bytecode actually calls
 * (V3 Phase 0 §B).
 *
 * <p>Nothing here is called from Java. Every entry point exists because
 * {@code BytecodeSurgeon} turned an {@code invokevirtual Event.register} in a
 * mod's compiled code into an {@code invokestatic} pointing at it, with the
 * receiver prepended to the arguments — so the signatures below are a contract
 * with the seam table in {@code FabricSeams}, and changing one without the
 * other produces a mod that fails to link rather than a compile error.
 *
 * <p>Resolvable from a mod's class loader because it is not in the mod: the
 * mod's {@code BytesClassLoader} parents to the host's loader (Knot), so a
 * class in the VibeMod jar is found parent-first exactly like {@code Event}
 * itself.
 *
 * <p>Deliberately thin. The policy lives in {@link EventFanout}; this class is
 * only the address the bytecode knows.
 */
public final class Shims {

    private static final Logger LOG = Logger.getLogger(Shims.class.getName());

    /**
     * Installed once per process at mod init, never per server.
     *
     * <p>Process-lived for the same reason every other host subscription is
     * (§10.3): the fanout owns permanent {@code Event.register} calls that
     * cannot be undone, so a client that loads a second world must find the
     * same fanout, not a second one dispatching into a dead bridge.
     */
    private static volatile EventSeam seam;

    /**
     * The render thread, or null on a dedicated server (V3 Phase 1 §B).
     *
     * <p>Lives here rather than in {@code ClientShims} because the SERVER side
     * needs to ask: the fanout decides whether a client callback may be
     * registered, and the entrypoint adapter decides whether a mod has a client
     * half to run. {@link ClientSeam} names no client type, precisely so this
     * field can exist in a class the dedicated server loads.
     */
    private static volatile ClientSeam client;

    /**
     * The registry seam (V3 Phase 3 §A), process-lived for the same reason the
     * event fanout is: it owns the unfreeze/refreeze window around every mod's
     * {@code onInitialize()}, and a client that loads a second world must find
     * the same one.
     */
    private static volatile RegistryTarget registries;

    private Shims() {
    }

    /** Installs the process-lived registry seam. Called from {@code onInitialize()}. */
    public static void installRegistries(RegistryTarget seam) {
        Shims.registries = seam;
    }

    /** The registry seam, or null before mod init. */
    public static RegistryTarget registries() {
        return registries;
    }

    /** Installs the process-lived seam. Called from {@code VibeModFabric.onInitialize()}. */
    public static void install(EventSeam seam) {
        Shims.seam = seam;
    }

    /** The installed seam, or null before mod init. */
    public static EventSeam seam() {
        return seam;
    }

    /** Installs the render-thread seam. Called from {@code onInitializeClient()}; never on a server. */
    public static void installClient(ClientSeam client) {
        Shims.client = client;
    }

    /** The render-thread seam, or null when there is no physical client. */
    public static ClientSeam clientSeam() {
        return client;
    }

    /**
     * {@code Event.register(T)} — the erased {@code (Ljava/lang/Object;)V}
     * call every Fabric event registration compiles down to.
     */
    public static void eventRegister(Event<?> event, Object listener) {
        require().register(event, listener);
    }

    /**
     * {@code Event.register(Identifier, T)} — the phased overload.
     *
     * <p>The phase is dropped on purpose, and it is not a silent drop: phases
     * only mean anything relative to an {@code addPhaseOrdering} call, which
     * the surgeon's policy forbids outright (a mod that reorders an event's
     * phases changes behaviour for every other mod and cannot be undone when it
     * is disabled). With no orderings in play, every phase runs in registration
     * order, which is exactly what the fanout does.
     */
    public static void eventRegister(Event<?> event, Identifier phase, Object listener) {
        LOG.fine(() -> "Ignoring event phase " + phase + " (phase ordering is not available to mods)");
        require().register(event, listener);
    }

    // ------------------------------------------------------------------ V3 Phase 3

    /**
     * {@code Registry.register(Registry<? super T>, String, T)} — vanilla's own
     * {@code "name"} overload, which means {@code minecraft:name} to vanilla and
     * {@code vibemod_<mod>:name} here.
     */
    public static Object registryRegister(Registry<?> registry, String id, Object value) {
        return requireRegistries().register(registry, id, value);
    }

    /** {@code Registry.register(Registry<V>, Identifier, T)} — the idiomatic mod overload. */
    public static Object registryRegister(Registry<?> registry, Identifier id, Object value) {
        return requireRegistries().register(registry, id, value);
    }

    /** {@code Registry.register(Registry<V>, ResourceKey<V>, T)}. */
    public static Object registryRegister(Registry<?> registry, ResourceKey<?> id, Object value) {
        return requireRegistries().register(registry, id, value);
    }

    /** {@code Registry.registerForHolder(Registry<R>, ResourceKey<R>, T)}. */
    public static Holder.Reference<?> registryRegisterForHolder(Registry<?> registry,
                                                                ResourceKey<?> id, Object value) {
        return requireRegistries().registerForHolder(registry, id, value);
    }

    /** {@code Registry.registerForHolder(Registry<R>, Identifier, T)}. */
    public static Holder.Reference<?> registryRegisterForHolder(Registry<?> registry,
                                                                Identifier id, Object value) {
        return requireRegistries().registerForHolder(registry, id, value);
    }

    /**
     * {@code Item.Properties.setId(ResourceKey<Item>)}.
     *
     * <p>Seamed because 26.2 requires the id <em>before</em> the item exists:
     * {@code Item.<init>} reads {@code Properties.itemIdOrThrow()} twice, for
     * the description id and the model id. Rewriting the namespace at
     * {@code Registry.register} time would be too late — the item would already
     * be pointing at an {@code assets/} path in a namespace nothing writes to.
     */
    public static Item.Properties itemId(Item.Properties properties, ResourceKey<Item> key) {
        return requireRegistries().itemId(properties, key);
    }

    /**
     * {@code BlockBehaviour.Properties.setId(ResourceKey<Block>)} (V4 Phase 1).
     *
     * <p>Seamed for the same reason {@link #itemId} is, and for one more:
     * {@code BlockBehaviour.<init>} reads the id twice — once for the
     * {@code descriptionId} and once for the loot-table {@code drops} key — so
     * a namespace rewritten later at {@code Registry.register} leaves the lang
     * key and the loot path in a namespace VibeMod never writes a file into.
     * The block would register, place and break, and drop nothing, with no
     * error anywhere.
     */
    public static BlockBehaviour.Properties blockId(BlockBehaviour.Properties properties,
                                                    ResourceKey<Block> key) {
        return requireRegistries().blockId(properties, key);
    }

    /** {@code EntityType.Builder.build(ResourceKey<EntityType<?>>)}, namespaced. */
    @SuppressWarnings("unchecked")
    public static EntityType<?> entityTypeBuild(EntityType.Builder<?> builder, ResourceKey<?> key) {
        return requireRegistries().entityTypeBuild(builder, (ResourceKey<EntityType<?>>) key);
    }

    /** {@code FabricDefaultAttributeRegistry.register(EntityType, AttributeSupplier.Builder)}. */
    @SuppressWarnings("unchecked")
    public static void defaultAttributes(EntityType<?> type, AttributeSupplier.Builder builder) {
        requireRegistries().defaultAttributes((EntityType<? extends LivingEntity>) type, builder);
    }

    /** {@code FabricDefaultAttributeRegistry.register(EntityType, AttributeSupplier)}. */
    @SuppressWarnings("unchecked")
    public static void defaultAttributes(EntityType<?> type, AttributeSupplier supplier) {
        requireRegistries().defaultAttributes((EntityType<? extends LivingEntity>) type, supplier);
    }

    private static EventSeam require() {
        EventSeam live = seam;
        if (live == null) {
            throw new IllegalStateException(
                    "VibeMod's event seam is not installed — a rewritten mod class was loaded "
                            + "before the host initialised");
        }
        return live;
    }

    private static RegistryTarget requireRegistries() {
        RegistryTarget live = registries;
        if (live == null) {
            throw new IllegalStateException(
                    "VibeMod's registry seam is not installed — a rewritten mod class was loaded "
                            + "before the host initialised");
        }
        return live;
    }
}
