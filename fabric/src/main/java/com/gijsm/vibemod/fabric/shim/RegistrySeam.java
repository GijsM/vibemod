package com.gijsm.vibemod.fabric.shim;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponentLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.item.Item;

import com.gijsm.vibemod.fabric.mixin.DataComponentInitializersAccessor;
import com.gijsm.vibemod.fabric.mixin.DefaultAttributesAccessor;
import com.gijsm.vibemod.fabric.mixin.MappedRegistryAccessor;
import com.gijsm.vibemod.loader.ModAttribution;
import com.gijsm.vibemod.loader.content.ReloadCoordinator;
import com.gijsm.vibemod.platform.Registration;
import com.gijsm.vibemod.runtime.ModHandle;
import com.gijsm.vibemod.store.ModResources;
import com.gijsm.vibemod.store.RegistryLedger;

/**
 * A generated mod registers a real item the way every Fabric mod does, and it
 * works in the live game (V3 Phase 3 §A/§B).
 *
 * <p>Three mechanisms, and the order they are described in is the order the
 * disassembly forced them into.
 *
 * <h2>1. The window, not the call site</h2>
 *
 * <p>The brief's shape — unfreeze inside {@code Registry.register}, register,
 * refreeze — cannot work, and {@code javap} says why in one line:
 *
 * <pre>
 * public net.minecraft.world.item.Item(Item$Properties);
 *   5: getstatic  BuiltInRegistries.ITEM
 *   9: invokeinterface DefaultedRegistry.createIntrusiveHolder:(Ljava/lang/Object;)…
 * </pre>
 *
 * <p>{@code Item.<init>} writes to the registry. In
 * {@code Registry.register(BuiltInRegistries.ITEM, id, new Item(props))} the
 * constructor is an <em>argument</em>: it runs to completion before the shim
 * that was supposed to unfreeze the registry is even entered, and it throws
 * (post-freeze, {@code unregisteredIntrusiveHolders} is null, so
 * {@code createIntrusiveHolder} refuses).
 *
 * <p>So the unfreeze is a <b>window around the mod's whole
 * {@code onInitialize()}</b>, opened by {@code FabricEntrypointAdapter} on the
 * server thread. Inside it the supported registries behave exactly as they do
 * during vanilla bootstrap; outside it nothing has changed. The seam on
 * {@code Registry.register} stays — it is what namespaces the id, refuses the
 * unsupported cases, journals the entry and makes the registration revocable in
 * every sense that it can be — but it is no longer the thing that makes writing
 * legal.
 *
 * <h2>2. The refreeze is not {@code freeze()}</h2>
 *
 * <p>{@code MappedRegistry.freeze()} is not idempotent-safe on a re-entry: it
 * throws {@code "Tags already present before freezing"} if {@code allTags} is
 * bound, which it is after the first freeze. So closing the window sets
 * {@code frozen} back to true directly and then does, explicitly, the two
 * things {@code freeze()} does that a freshly registered holder needs and
 * {@code register()} does not: bind its tags ({@code refreshTagsInHolders},
 * without which {@code Holder.Reference.is(TagKey)} throws "Tags not bound")
 * and rebuild the component lookup. Components themselves are bound by running
 * vanilla's own {@code DataComponentInitializers.build(provider).apply()} — the
 * same call {@code ReloadableServerResources} makes on every datapack reload,
 * which is also why the coordinator's reload repairs anything this misses.
 *
 * <h2>3. There is no unregister, so there is a ledger</h2>
 *
 * <p>See {@link RegistryLedger}. Draining a mod removes its items from the
 * creative tabs and drains everything they could <em>do</em>; the id itself
 * stays, and the ledger is the honest record of that.
 */
public final class RegistrySeam implements RegistryTarget {

    private static final Logger LOG = Logger.getLogger("VibeMod.Registry");

    /**
     * The dedicated-server policy, verbatim, because the smoke gate asserts it.
     *
     * <p>Registration would technically succeed on a dedicated server — no
     * vanilla client is attached at the moment a mod loads, so nothing desyncs
     * right then. It is refused anyway, deterministically, because the next
     * client to join negotiates a registry sync that does not contain the id,
     * and "it worked until somebody logged in" is the worst possible failure
     * mode for a feature whose whole promise is that generated content is real.
     */
    public static final String DEDICATED_REFUSAL =
            "registry content is singleplayer/LAN-host only in v1; applies after restart on dedicated";

    /** The registries a mod may actually register INTO, by their {@code ResourceKey}. */
    private static final Map<ResourceKey<? extends Registry<?>>, String> SUPPORTED = Map.of(
            Registries.ITEM, "item",
            Registries.ENTITY_TYPE, "entity type");

    /**
     * The registries the window unfreezes, which is a strictly larger set than
     * the one a mod may write to — and {@code BLOCK} is why.
     *
     * <p>{@code Item.Properties.sword(ToolMaterial.IRON, …)} does this
     * (disassembled from {@code ToolMaterial.applySwordProperties}):
     *
     * <pre>
     * BuiltInRegistries.acquireBootstrapRegistrationLookup(BuiltInRegistries.BLOCK)
     *     .getOrThrow(BlockTags.SWORD_EFFICIENT)
     * </pre>
     *
     * <p>and {@code acquireBootstrapRegistrationLookup} is
     * {@code WritableRegistry.createRegistrationLookup()}, which calls
     * {@code validateWrite()} — so building ANY tool item touches the frozen
     * BLOCK registry before it touches the item registry at all. The smoke gate
     * found this: {@code IllegalStateException: Registry is already frozen} out
     * of {@code Item$Properties.sword}, three frames before the seam.
     *
     * <p>Unfreezing it is not the same as allowing blocks. {@link #register}
     * still refuses {@code BuiltInRegistries.BLOCK} by name, with the reason;
     * what the window buys is that the refusal is OURS and legible rather than
     * a vanilla frozen-registry stack trace from inside a builder.
     */
    private static final List<ResourceKey<? extends Registry<?>>> WINDOW = List.of(
            Registries.ITEM, Registries.ENTITY_TYPE, Registries.BLOCK);

    private final Supplier<MinecraftServer> server;
    private final Supplier<ReloadCoordinator> reloads;
    private volatile RegistryLedger ledger;

    /** Live entries per mod, in registration order; a mod with none is absent. */
    private final Map<String, List<Live>> live = new ConcurrentHashMap<>();
    /** Default-attribute suppliers this seam installed, so a drain can remove them. */
    private final Map<String, List<EntityType<?>>> attributes = new ConcurrentHashMap<>();

    private final AtomicInteger tabRebuilds = new AtomicInteger();
    private int windowDepth;
    private int registeredInWindow;
    /** {@code DATA_COMPONENT_INITIALIZERS} size when the window opened, or -1 if unreadable. */
    private int initializersBefore = -1;

    public RegistrySeam(Supplier<MinecraftServer> server, Supplier<ReloadCoordinator> reloads) {
        this.server = server;
        this.reloads = reloads;
    }

    /** One id one mod put into one registry, and the object behind it. */
    public record Live(String modName, String registry, Identifier id, Object value) {
    }

    /** Installs the per-installation ledger. Called when a server starts. */
    public void setLedger(RegistryLedger ledger) {
        this.ledger = ledger;
    }

    /** The ledger, or null before a server has started. */
    public RegistryLedger ledger() {
        return ledger;
    }

    // ------------------------------------------------------------------ the window

    /**
     * Runs a mod's {@code onInitialize()} with the supported registries open.
     *
     * <p>Server thread only, and single-threaded by construction: mods are
     * loaded one at a time from {@code ModLifecycle}, which asserts the main
     * thread. The depth counter is for the pathological case of a mod loading a
     * mod, not for concurrency.
     */
    @Override
    public void withWindow(Runnable body) {
        open();
        try {
            body.run();
        } finally {
            close();
        }
    }

    private synchronized void open() {
        if (windowDepth++ > 0) {
            return;
        }
        registeredInWindow = 0;
        // Item.<init> appends to this list as a side effect, and nothing else
        // ever removes an entry — see DataComponentInitializersAccessor for what
        // that does to every later datapack reload.
        initializersBefore = initializerCount();
        for (ResourceKey<? extends Registry<?>> key : WINDOW) {
            MappedRegistryAccessor accessor = accessorFor(key);
            if (accessor == null) {
                continue;
            }
            accessor.setFrozen(false);
            if (accessor.getUnregisteredIntrusiveHolders() == null) {
                // freeze() nulled it; Item.<init>/EntityType construction needs
                // it back or the constructor throws before our shim is reached.
                accessor.setUnregisteredIntrusiveHolders(new IdentityHashMap<>());
            }
        }
    }

    private synchronized void close() {
        if (--windowDepth > 0) {
            return;
        }
        int orphaned = 0;
        for (ResourceKey<? extends Registry<?>> key : WINDOW) {
            MappedRegistryAccessor accessor = accessorFor(key);
            if (accessor == null) {
                continue;
            }
            Map<Object, Holder.Reference<Object>> intrusive = accessor.getUnregisteredIntrusiveHolders();
            if (intrusive != null && !intrusive.isEmpty()) {
                // Something was constructed and never registered — a mod whose
                // registration was refused, or one that built an object and then
                // threw. Vanilla would fail the whole freeze over this; dropping
                // it is the only answer that leaves a running game, and it is
                // said out loud.
                orphaned += intrusive.size();
                LOG.warning("Discarding " + intrusive.size() + " constructed-but-unregistered "
                        + key.identifier() + " object(s): a mod built one and never registered it, "
                        + "so it has no id and nothing can reach it");
                intrusive.clear();
            }
            accessor.setUnregisteredIntrusiveHolders(null);
            accessor.setFrozen(true);
        }
        if (orphaned > 0) {
            rollBackComponentInitializers();
        }
        if (registeredInWindow == 0) {
            return;
        }
        repairAfterRegistration();
    }

    private static int initializerCount() {
        try {
            return initializerEntries().size();
        } catch (Throwable t) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> initializerEntries() {
        return ((DataComponentInitializersAccessor) (Object)
                BuiltInRegistries.DATA_COMPONENT_INITIALIZERS).getInitializers();
    }

    /**
     * Throws away every component initializer this window added.
     *
     * <p>All-or-nothing on purpose. An entry keyed by an id that was never
     * registered makes {@code DataComponentInitializers.build} — and therefore
     * every datapack reload for the rest of the session — throw
     * {@code Missing element}, and there is no way from here to tell one
     * window's entries apart by key ({@code InitializerEntry} is package-private
     * in {@code net.minecraft.core.component}). So a window that left anything
     * unregistered loses all of its entries: an item with empty components is
     * visibly broken and is fixed by regenerating the mod, while a world that
     * can no longer complete a reload looks fine until somebody notices that
     * recipes stopped updating.
     */
    private void rollBackComponentInitializers() {
        if (initializersBefore < 0) {
            return;
        }
        try {
            List<Object> entries = initializerEntries();
            if (entries.size() > initializersBefore) {
                int dropped = entries.size() - initializersBefore;
                entries.subList(initializersBefore, entries.size()).clear();
                LOG.warning("Rolled back " + dropped + " data-component initializer(s) left behind "
                        + "by a refused or failed registration; without this every later datapack "
                        + "reload would fail with \"Missing element\"");
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Could not roll back the data component initializers", t);
        }
    }

    /**
     * Everything {@code freeze()} would have done for the new holders, plus the
     * two things that make them visible to a player.
     */
    private void repairAfterRegistration() {
        for (ResourceKey<? extends Registry<?>> key : WINDOW) {
            MappedRegistryAccessor accessor = accessorFor(key);
            if (accessor == null) {
                continue;
            }
            try {
                accessor.invokeRefreshTagsInHolders();
                accessor.setComponentLookup(new DataComponentLookup<>(accessor.getById()));
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "Could not refresh " + key.identifier()
                        + " after a runtime registration", t);
            }
        }
        bindComponents();
        rebuildCreativeTabs("registration");
        ReloadCoordinator coordinator = reloads.get();
        if (coordinator != null) {
            // The reload is what parses a recipe naming the new id, and it is
            // also vanilla's own belt for the braces above: ReloadableServerResources
            // rebuilds every item's components and every registry's tags.
            coordinator.markServerDirty("registry content");
            coordinator.markClientDirty("registry content");
        }
    }

    /**
     * Vanilla's own component pass, run early.
     *
     * <p>{@code Item.<init>} appends an initializer to
     * {@code BuiltInRegistries.DATA_COMPONENT_INITIALIZERS}; nothing binds it
     * until a datapack reload. Until then {@code Holder.Reference.components()}
     * throws "Components not bound yet" and the item cannot be put in a stack.
     * The coordinator's reload will do it two seconds later — this makes the
     * item usable on the tick it was registered.
     */
    private void bindComponents() {
        MinecraftServer live = server.get();
        if (live == null) {
            return;
        }
        try {
            for (DataComponentInitializers.PendingComponents<?> pending
                    : BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(live.registryAccess())) {
                pending.apply();
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Could not bind data components for runtime-registered content; "
                    + "the next datapack reload will", t);
        }
    }

    // ------------------------------------------------------------------ registration

    /**
     * Every {@code Registry.register}/{@code registerForHolder} overload lands
     * here (§A). {@code id} is whatever the mod passed: an {@link Identifier},
     * a {@link ResourceKey} or a bare {@link String}.
     *
     * @return the value, so the mod's {@code static final Item RUBY = Registry
     *         .register(...)} still gets its item
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Object register(Registry<?> registry, Object id, Object value) {
        ModHandle handle = requireHandle();
        if (registry == null || value == null) {
            throw new IllegalArgumentException("Registry.register(null) is not a registration");
        }
        ResourceKey<? extends Registry<?>> registryKey = registry.key();
        String what = SUPPORTED.get(registryKey);
        if (what == null) {
            throw new UnsupportedOperationException("VibeMod cannot register into "
                    + registryKey.identifier() + " yet. Runtime registration is supported for "
                    + "minecraft:item and minecraft:entity_type. Blocks, block entities and "
                    + "everything datapack-shaped (enchantments, loot tables, worldgen) go in "
                    + "your mod's data/** files instead.");
        }
        refuseOnDedicatedServer(handle, registryKey, id);

        RegistryLedger book = ledger;
        if (book != null && book.isTombstoned(handle.name())) {
            throw new UnsupportedOperationException("Mod " + handle.name()
                    + " was unloaded once, so its registry ids are tombstoned and will not be "
                    + "registered again in this world. Generate it under a new name.");
        }

        Identifier canonical = canonicalise(handle.name(), id);
        if (!(registry instanceof MappedRegistry<?> mapped)) {
            throw new UnsupportedOperationException(registryKey.identifier()
                    + " is not a writable registry on this game version");
        }
        MappedRegistryAccessor accessor = (MappedRegistryAccessor) mapped;
        if (accessor.isFrozen()) {
            throw new IllegalStateException("Mod " + handle.name() + " tried to register "
                    + canonical + " outside its own onInitialize(). VibeMod opens the game's "
                    + "registries only while a mod is initialising; register your items and entity "
                    + "types from onInitialize() and keep them in static fields.");
        }

        ResourceKey key = ResourceKey.create((ResourceKey) registryKey, canonical);
        ((MappedRegistry) mapped).register(key, value, RegistrationInfo.BUILT_IN);
        registeredInWindow++;

        Live entry = new Live(handle.name(), registryKey.identifier().toString(), canonical, value);
        live.computeIfAbsent(handle.name(), ignored -> new ArrayList<>()).add(entry);
        if (book != null) {
            book.record(handle.name(), handle.version(), entry.registry(), canonical.toString());
        }
        handle.track(ModHandle.Kind.CONTENT, Registration.of(() -> forget(entry)));
        if (value instanceof EntityType<?> type) {
            // Before the mod's own onInitializeClient (which is deferred to the
            // render thread) can install a real renderer. See
            // ClientSeam.ensureEntityRenderer for the render-thread NPE this
            // closes; the client gate produced it.
            ClientSeam client = Shims.clientSeam();
            if (client != null) {
                client.ensureEntityRenderer(type);
            }
        }
        LOG.info("Mod " + handle.name() + " registered " + what + " " + canonical);
        return value;
    }

    /** {@code Registry.registerForHolder} — same path, but the mod wants the holder. */
    @Override
    public Holder.Reference<?> registerForHolder(Registry<?> registry, Object id, Object value) {
        ModHandle handle = requireHandle();
        register(registry, id, value);
        Identifier canonical = canonicalise(handle.name(), id);
        Optional<? extends Holder.Reference<?>> holder = registry.get(canonical);
        if (holder.isEmpty()) {
            throw new IllegalStateException("registered " + canonical
                    + " but the registry does not hold it");
        }
        return holder.get();
    }

    /**
     * {@code FabricDefaultAttributeRegistry.register} (§B).
     *
     * <p>Seamed even though it would work untouched — it is a plain
     * {@code Map.put} into {@code DefaultAttributes.SUPPLIERS}, which
     * fabric-object-builder-api makes mutable — because a map entry nothing
     * removes is a leak with a mod's class loader on the end of it.
     */
    @Override
    public void defaultAttributes(EntityType<? extends LivingEntity> type, AttributeSupplier supplier) {
        ModHandle handle = requireHandle();
        net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry
                .register(type, supplier);
        attributes.computeIfAbsent(handle.name(), ignored -> new ArrayList<>()).add(type);
        handle.track(ModHandle.Kind.CONTENT, Registration.of(() -> {
            DefaultAttributesAccessor.getSuppliers().remove(type);
            List<EntityType<?>> mine = attributes.get(handle.name());
            if (mine != null) {
                mine.remove(type);
                if (mine.isEmpty()) {
                    attributes.remove(handle.name());
                }
            }
        }));
        LOG.fine(() -> "Mod " + handle.name() + " registered default attributes for "
                + BuiltInRegistries.ENTITY_TYPE.getKey(type));
    }

    /** The {@code AttributeSupplier.Builder} overload; builds and delegates. */
    @Override
    public void defaultAttributes(EntityType<? extends LivingEntity> type,
                                  AttributeSupplier.Builder builder) {
        defaultAttributes(type, builder.build());
    }

    /**
     * {@code Item.Properties.setId(ResourceKey)}, namespaced (§A).
     *
     * <p>Seamed because in 26.2 the id is required <em>before</em> construction
     * — {@code Item.<init>} calls {@code Properties.itemIdOrThrow()} for the
     * description id and the model id — so by the time
     * {@code Registry.register} rewrites the namespace, the item has already
     * baked the model path {@code <whatever-the-model-chose>:ruby} into its
     * components. Rewriting here keeps the object, the registry id, the
     * {@code assets/} tree and the translation key on one namespace.
     */
    @Override
    public Item.Properties itemId(Item.Properties properties, ResourceKey<Item> key) {
        ModHandle handle = ModAttribution.current();
        if (handle == null) {
            return properties.setId(key);
        }
        return properties.setId(ResourceKey.create(Registries.ITEM,
                canonicalise(handle.name(), key)));
    }

    /** {@code EntityType.Builder.build(ResourceKey)}, namespaced for the same reason (§B). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public EntityType<?> entityTypeBuild(EntityType.Builder<?> builder,
                                         ResourceKey<EntityType<?>> key) {
        ModHandle handle = ModAttribution.current();
        if (handle == null) {
            return ((EntityType.Builder) builder).build(key);
        }
        return ((EntityType.Builder) builder).build(
                ResourceKey.create(Registries.ENTITY_TYPE, canonicalise(handle.name(), key)));
    }

    // ------------------------------------------------------------------ teardown

    /**
     * A mod's registration is drained.
     *
     * <p>Deliberately cheap: the watchdog gives a whole teardown 250ms and a
     * creative-tab rebuild walks every item in the game. So this forgets the
     * entry, invalidates the tab cache, and marks the coordinator dirty — the
     * real work happens on the coordinator's own schedule, exactly as the
     * datapack channel already does it.
     */
    private void forget(Live entry) {
        List<Live> mine = live.get(entry.modName());
        if (mine != null) {
            mine.remove(entry);
            if (mine.isEmpty()) {
                live.remove(entry.modName());
            }
        }
        // Invalidate here (cheap), rebuild on the render thread's queue (not
        // cheap: it walks every item in the game). The watchdog gives a whole
        // teardown 250ms, so the rebuild must not happen inside it — but it must
        // happen, because CreativeModeTab.getDisplayItems() hands back the list
        // baked at the LAST rebuild and would keep offering a disabled mod's
        // item until somebody opened the creative menu.
        invalidateCreativeTabs();
        ClientSeam client = Shims.clientSeam();
        if (client != null) {
            client.runOnRenderThread(() -> rebuildCreativeTabs("mod disabled"));
        }
        ReloadCoordinator coordinator = reloads.get();
        if (coordinator != null) {
            coordinator.markClientDirty("registry content removed");
        }
    }

    /** Called when a mod is unloaded from the store: its ids are never coming back. */
    public void tombstone(String modName) {
        RegistryLedger book = ledger;
        if (book != null) {
            book.tombstone(modName);
        }
    }

    // ------------------------------------------------------------------ creative tabs

    /** Every item a currently-enabled mod registered, in registration order. */
    public List<Item> liveItems() {
        List<Item> out = new ArrayList<>();
        for (List<Live> entries : new LinkedHashMap<>(live).values()) {
            for (Live entry : entries) {
                if (entry.value() instanceof Item item) {
                    out.add(item);
                }
            }
        }
        return out;
    }

    /** Drops vanilla's cached tab parameters so the next rebuild actually rebuilds. */
    private void invalidateCreativeTabs() {
        try {
            CreativeTabs.invalidate();
        } catch (Throwable t) {
            LOG.log(Level.FINE, "Could not invalidate the creative tab cache", t);
        }
    }

    private void rebuildCreativeTabs(String why) {
        try {
            if (CreativeTabs.rebuild(server.get())) {
                tabRebuilds.incrementAndGet();
                LOG.fine(() -> "Rebuilt creative tab contents (" + why + ")");
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Could not rebuild the creative tab contents", t);
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The mod's canonical namespace, mirroring Phase 2's rule for resources so
     * a recipe's {@code "vibemod_x:ruby"} and the registry's {@code vibemod_x:ruby}
     * are the same string by construction rather than by the model's care.
     */
    private static Identifier canonicalise(String modName, Object id) {
        String path = pathOf(id);
        return Identifier.fromNamespaceAndPath(ModResources.canonicalNamespace(modName), path);
    }

    private static String pathOf(Object id) {
        if (id instanceof Identifier identifier) {
            return identifier.getPath();
        }
        if (id instanceof ResourceKey<?> key) {
            return key.identifier().getPath();
        }
        if (id instanceof String text) {
            int colon = text.indexOf(':');
            return colon < 0 ? text : text.substring(colon + 1);
        }
        throw new IllegalArgumentException("Not a registry id: " + id);
    }

    private void refuseOnDedicatedServer(ModHandle handle,
                                         ResourceKey<? extends Registry<?>> registryKey, Object id) {
        MinecraftServer live = server.get();
        if (live == null || !live.isDedicatedServer()) {
            return;
        }
        // Logged as well as thrown, and that is not double-reporting: the throw
        // is the MOD's news (it fails to load, the generator gets a repair
        // round), while this line is the OPERATOR's — somebody installed a mod
        // that cannot work on the server they are running, and the journal entry
        // behind /vibe errors is not where they will look first.
        LOG.warning("Refusing registry content from " + handle.name() + " on a dedicated server: "
                + DEDICATED_REFUSAL);
        throw new UnsupportedOperationException("Mod " + handle.name() + " tried to register "
                + pathOf(id) + " into " + registryKey.identifier() + " on a dedicated server: "
                + DEDICATED_REFUSAL + ". A vanilla client that joins later negotiates a registry "
                + "sync without this id and would be kicked, so VibeMod refuses rather than "
                + "working until somebody logs in. Ship the item as a data/** recipe whose result "
                + "carries minecraft:custom_name and minecraft:item_model instead, or run this mod "
                + "on a singleplayer or LAN-hosted world.");
    }

    private static ModHandle requireHandle() {
        ModHandle handle = ModAttribution.current();
        if (handle == null) {
            throw new IllegalStateException(
                    "Registry.register reached VibeMod outside any mod's own code, so the entry "
                            + "could never be attributed. Register from onInitialize().");
        }
        return handle;
    }

    /**
     * The two registries by name, rather than through the root registry.
     *
     * <p>Named fields on purpose: {@code BuiltInRegistries.REGISTRY} is typed
     * {@code Registry<? extends Registry<?>>}, and a lookup through it would be
     * an unchecked cast for no gain when there are exactly two.
     */
    private static MappedRegistryAccessor accessorFor(ResourceKey<? extends Registry<?>> key) {
        Registry<?> registry = null;
        if (Registries.ITEM.equals(key)) {
            registry = BuiltInRegistries.ITEM;
        } else if (Registries.ENTITY_TYPE.equals(key)) {
            registry = BuiltInRegistries.ENTITY_TYPE;
        } else if (Registries.BLOCK.equals(key)) {
            registry = BuiltInRegistries.BLOCK;
        }
        return registry instanceof MappedRegistry<?> mapped ? (MappedRegistryAccessor) mapped : null;
    }

    /**
     * Counts for the gates:
     * {@code "registryMods=1 registryItems=1 registryEntityTypes=0 registryAttributes=0
     * tabRebuilds=1 ledgerMods=1 ledgerIds=1 ledgerTombstones=0"}.
     */
    public String describeState() {
        int items = 0;
        int entities = 0;
        for (List<Live> entries : new LinkedHashMap<>(live).values()) {
            for (Live entry : entries) {
                if (entry.value() instanceof Item) {
                    items++;
                } else if (entry.value() instanceof EntityType<?>) {
                    entities++;
                }
            }
        }
        int suppliers = 0;
        for (List<EntityType<?>> types : new LinkedHashMap<>(attributes).values()) {
            suppliers += types.size();
        }
        RegistryLedger book = ledger;
        return "registryMods=" + live.size()
                + " registryItems=" + items
                + " registryEntityTypes=" + entities
                + " registryAttributes=" + suppliers
                + " tabRebuilds=" + tabRebuilds.get()
                + " " + (book == null ? "ledgerMods=0 ledgerIds=0 ledgerTombstones=0"
                        : book.describeState());
    }
}
