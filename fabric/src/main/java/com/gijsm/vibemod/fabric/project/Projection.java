package com.gijsm.vibemod.fabric.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Logger;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * The pure function at the middle of Lane B: a VibeMod stack in, a stack a
 * vanilla client can hold out, and the way back (V4 Phase 4).
 *
 * <p>Nothing here touches a connection, a packet or a thread. That is
 * deliberate — the packet layer above is a large table of shapes, and the one
 * thing that must be exactly right is this: a projection that loses a creative
 * player's item is worse than no projection at all.
 *
 * <h2>The mechanism: ITEM_MODEL, not custom_model_data</h2>
 *
 * <p>A projected stack is the configured vanilla base item (default
 * {@code minecraft:paper}) carrying three components:
 *
 * <ul>
 *   <li>{@code minecraft:item_model} — an {@code Identifier} pointing at the
 *       same {@code assets/&lt;ns&gt;/items/&lt;path&gt;.json} the true item
 *       uses. This is the 1.21.4+ mechanism and it is the reason there is no
 *       {@code custom_model_data} anywhere in this file: CMD is a numeric
 *       override on the <em>base</em> item's model, which means a shared,
 *       finite, collision-prone integer space negotiated with every other mod
 *       on the server. An item-model id is a namespaced path and collides with
 *       nobody.</li>
 *   <li>{@code minecraft:item_name} — a {@code Component}, not a lang key.
 *       {@link #nameOf} rebuilds a bare {@code TranslatableContents} as
 *       {@code Component.translatableWithFallback(key, prettyPath)}, so a client
 *       that <em>declined</em> the resource pack still reads "Ruby Charm"
 *       instead of {@code item.vibemod_rubycharm.ruby_charm}. That single
 *       fallback is what makes an optional pack an honest default.</li>
 *   <li>{@code minecraft:custom_data} — the round trip. It carries the true id
 *       and the original component patch, so a stack the client echoes back
 *       ({@code ServerboundSetCreativeModeSlotPacket}, and the hashes in
 *       {@code ServerboundContainerClickPacket}) can be turned back into
 *       exactly the stack the server sent.</li>
 * </ul>
 *
 * <h2>What else is carried, and why so little</h2>
 *
 * <p>Only the presentation components a vanilla client can definitely resolve:
 * stack size, durability, rarity, lore, custom name, and a glint <em>override</em>
 * rather than the enchantments themselves. Copying {@code ENCHANTMENTS} would
 * put registry entries on the wire, and once Phase 5 can register an
 * enchantment at runtime that is a reference to an id the vanilla client does
 * not have — which is a kick, not a missing tooltip. The glint override buys
 * the visible half at no protocol risk.
 *
 * <p>Stack size and durability are copied <b>together or not at all</b>: vanilla
 * refuses a stack that is both stackable and damageable, and the base item's
 * prototype supplies a max stack of 64 that a lone {@code MAX_DAMAGE} would
 * contradict.
 *
 * <h2>The bound, stated rather than discovered</h2>
 *
 * <p>A component whose <em>value</em> references a VibeMod id — a container
 * component holding one of our items, say — is not projected recursively in
 * this version. {@link #refuseUnprojectableComponents} is the deny-list that
 * catches those at mod-load time, so a mod is told at registration instead of a
 * player finding out at a screen. Shipping half a projection quietly is the one
 * outcome this phase is not allowed to have.
 */
public final class Projection {

    private static final Logger LOG = Logger.getLogger("VibeMod.Lane");

    /** The subtag of {@code minecraft:custom_data} the round trip lives under. */
    static final String ROOT = "vibemod";

    /** {@code custom_data.vibemod.id} — the true registry id, as a string. */
    static final String KEY_ID = "id";

    /** {@code custom_data.vibemod.patch} — the original {@code DataComponentPatch}. */
    static final String KEY_PATCH = "patch";

    /**
     * The components a projected stack carries beside the three that define it.
     *
     * <p>Order matters for the two size/durability entries only, and that pair
     * is applied together in {@link #project}; the rest are independent.
     */
    private static final List<DataComponentType<?>> CARRIED = List.of(
            DataComponents.LORE,
            DataComponents.CUSTOM_NAME,
            DataComponents.RARITY);

    /** Per-entry base items, keyed by the true id; empty means "use the default". */
    private static final Map<Identifier, Item> BASES = new ConcurrentHashMap<>();

    /**
     * Whether an {@code Item} is one of ours, memoised.
     *
     * <p>The alternative is {@code BuiltInRegistries.ITEM.getKey(item)} per
     * stack per packet, which is a hash lookup and a string prefix test on a
     * path that runs for every slot of every container a Lane B player opens.
     * Items are process-lived and never unregistered (there is no
     * {@code MappedRegistry.remove}), so a memo that only grows is exactly
     * right rather than a leak.
     */
    private static final Map<Item, Boolean> OURS = new ConcurrentHashMap<>();

    private static volatile Item defaultBase = Items.PAPER;
    private static volatile Supplier<HolderLookup.Provider> registries = () -> null;

    /**
     * The {@code RegistryOps} the component round trip encodes through, memoised
     * against the provider it was built from.
     *
     * <p>{@code RegistryOps.create} allocates a lookup wrapper, and this runs
     * once per projected stack on a Netty thread — a container full of one of
     * our items is 54 of them in a single packet. The provider is per-server and
     * changes only when a world is loaded, so a two-field memo compared by
     * identity is exact rather than approximate: a new world means a new
     * provider means a new ops, and there is no staleness window at all.
     */
    private static volatile HolderLookup.Provider opsProvider;
    private static volatile RegistryOps<Tag> ops;

    private static final AtomicInteger PROJECTED = new AtomicInteger();
    private static final AtomicInteger UNPROJECTED = new AtomicInteger();
    private static final AtomicInteger STRANDED = new AtomicInteger();

    private Projection() {
    }

    /**
     * Installs the registry access the component-patch codec needs.
     *
     * <p>A supplier rather than a value because the projection is process-lived
     * and a {@code RegistryAccess} is per-server: it is null between worlds, and
     * the projection has to keep working when a world is loaded a second time.
     * With no provider the round trip carries the true id but not the original
     * components, and {@link #project} says so once rather than silently
     * shipping a lesser stack.
     */
    public static void useRegistries(Supplier<HolderLookup.Provider> provider) {
        registries = provider == null ? () -> null : provider;
    }

    /** Sets the vanilla item every unconfigured projection lands on. */
    public static void setDefaultBase(Item base) {
        defaultBase = base == null ? Items.PAPER : base;
    }

    /** Sets the vanilla base one specific VibeMod id projects onto. */
    public static void setBase(Identifier trueId, Item base) {
        if (base == null) {
            BASES.remove(trueId);
        } else {
            BASES.put(trueId, base);
        }
    }

    /** The vanilla base a given true id projects onto. */
    public static Item baseFor(Identifier trueId) {
        Item configured = BASES.get(trueId);
        return configured == null ? defaultBase : configured;
    }

    // ------------------------------------------------------------------ the test

    /**
     * True for an item a vanilla client has never heard of.
     *
     * <p>The namespace is the whole test, and it is sound because every id
     * VibeMod mints goes through {@code ModResources.canonicalNamespace} — items,
     * blocks, pinned stubs, all of them {@code vibemod_<modname>}. An item from
     * some other mod is somebody else's projection problem and is left alone.
     */
    public static boolean isProjectable(Item item) {
        Boolean known = OURS.get(item);
        if (known != null) {
            return known;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        boolean ours = id != null && RegistryHiding.isVibeModNamespace(id.getNamespace());
        OURS.put(item, ours);
        return ours;
    }

    /** True when {@link #project} would change this stack. */
    public static boolean touches(ItemStack stack) {
        return !stack.isEmpty() && isProjectable(stack.getItem());
    }

    // ------------------------------------------------------------------ out

    /**
     * A VibeMod stack as a vanilla client can hold it, or the same stack back
     * when there is nothing to do.
     *
     * <p>Identity on the free path: a vanilla stack is returned by reference, so
     * a container packet full of cobblestone allocates nothing at all.
     */
    public static ItemStack project(ItemStack stack) {
        if (!touches(stack)) {
            return stack;
        }
        Item item = stack.getItem();
        Identifier trueId = BuiltInRegistries.ITEM.getKey(item);
        Item base = baseFor(trueId);

        ItemStack out = new ItemStack(BuiltInRegistries.ITEM.wrapAsHolder(base), stack.getCount());
        out.set(DataComponents.ITEM_MODEL, modelOf(stack, trueId));
        out.set(DataComponents.ITEM_NAME, nameOf(stack, item, trueId));
        out.set(DataComponents.CUSTOM_DATA, CustomData.of(roundTrip(stack, trueId)));

        // Together or not at all: vanilla refuses a stack that is both
        // stackable and damageable, and the base's prototype already claims a
        // max stack of 64.
        Integer maxDamage = stack.get(DataComponents.MAX_DAMAGE);
        if (maxDamage != null && maxDamage > 0) {
            out.set(DataComponents.MAX_STACK_SIZE, 1);
            out.set(DataComponents.MAX_DAMAGE, maxDamage);
            Integer damage = stack.get(DataComponents.DAMAGE);
            out.set(DataComponents.DAMAGE, damage == null ? 0 : damage);
        } else {
            Integer maxStack = stack.get(DataComponents.MAX_STACK_SIZE);
            if (maxStack != null) {
                out.set(DataComponents.MAX_STACK_SIZE, maxStack);
            }
        }

        for (DataComponentType<?> type : CARRIED) {
            carry(stack, out, type);
        }

        // The visible half of an enchantment with none of the protocol risk:
        // ENCHANTMENTS would put registry references on the wire, and Phase 5
        // can mint an enchantment a vanilla client does not have, which is a
        // kick rather than a missing tooltip.
        if (stack.isEnchanted()) {
            out.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, Boolean.TRUE);
        }

        PROJECTED.incrementAndGet();
        return out;
    }

    /** Projects every stack in a list, returning the same list when none changed. */
    public static List<ItemStack> projectAll(List<ItemStack> stacks) {
        List<ItemStack> out = null;
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            ItemStack projected = project(stack);
            if (projected != stack && out == null) {
                out = new ArrayList<>(stacks);
            }
            if (out != null) {
                out.set(i, projected);
            }
        }
        return out == null ? stacks : out;
    }

    @SuppressWarnings("unchecked")
    private static <T> void carry(ItemStack from, ItemStack to, DataComponentType<?> type) {
        DataComponentType<T> typed = (DataComponentType<T>) type;
        T value = from.get(typed);
        if (value != null) {
            to.set(typed, value);
        }
    }

    private static Identifier modelOf(ItemStack stack, Identifier trueId) {
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        // The seam sets ITEM_MODEL on every registered item, so the fallback is
        // for a stack whose item somehow has none — the id itself, which is
        // where the pack writer puts the model anyway.
        return model == null ? trueId : model;
    }

    /**
     * A name that survives a declined resource pack.
     *
     * <p>{@code Component.translatableWithFallback(key, fallback)} renders the
     * fallback when the key is missing from the client's language, which is
     * exactly the case a Lane B client without the pack is in. Rebuilding a bare
     * {@code TranslatableContents} with a fallback rather than replacing it with
     * a literal keeps the translation working for the client that <em>did</em>
     * take the pack.
     */
    static Component nameOf(ItemStack stack, Item item, Identifier trueId) {
        Component name = stack.get(DataComponents.ITEM_NAME);
        if (name == null) {
            name = item.getName(stack);
        }
        if (name != null && name.getContents() instanceof TranslatableContents contents
                && contents.getFallback() == null) {
            MutableComponent rebuilt = Component.translatableWithFallback(contents.getKey(),
                    prettify(trueId.getPath()), contents.getArgs());
            rebuilt.setStyle(name.getStyle());
            for (Component sibling : name.getSiblings()) {
                rebuilt.append(sibling);
            }
            return rebuilt;
        }
        return name == null ? Component.literal(prettify(trueId.getPath())) : name;
    }

    /** {@code "ruby_charm"} → {@code "Ruby Charm"}. */
    static String prettify(String path) {
        StringBuilder out = new StringBuilder(path.length());
        boolean capitalise = true;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '_' || c == '/' || c == '.' || c == '-') {
                out.append(' ');
                capitalise = true;
            } else if (capitalise) {
                out.append(Character.toUpperCase(c));
                capitalise = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static CompoundTag roundTrip(ItemStack stack, Identifier trueId) {
        CompoundTag payload = new CompoundTag();
        payload.putString(KEY_ID, trueId.toString());

        DataComponentPatch original = stack.getComponentsPatch();
        HolderLookup.Provider provider = registries.get();
        if (!original.isEmpty()) {
            if (provider == null) {
                if (STRANDED.getAndIncrement() == 0) {
                    LOG.warning("projecting " + trueId + " without its original components: no "
                            + "RegistryAccess is installed, so DataComponentPatch cannot be encoded. "
                            + "A stack this client echoes back will come home as a bare " + trueId
                            + " with its components lost. This is a wiring fault — "
                            + "Projection.useRegistries was never called — not a protocol limit");
                }
            } else {
                payload.store(KEY_PATCH, DataComponentPatch.CODEC, opsFor(provider), original);
            }
        }

        CompoundTag custom = new CompoundTag();
        custom.put(ROOT, payload);
        return custom;
    }

    private static RegistryOps<Tag> opsFor(HolderLookup.Provider provider) {
        RegistryOps<Tag> known = ops;
        if (known != null && opsProvider == provider) {
            return known;
        }
        RegistryOps<Tag> built = RegistryOps.create(NbtOps.INSTANCE, provider);
        opsProvider = provider;
        ops = built;
        return built;
    }

    // ------------------------------------------------------------------ back

    /**
     * A stack a Lane B client sent us, turned back into what the server sent it.
     *
     * <p><b>This is why the seam is duplex.</b> A creative client puts the stack
     * it is holding into {@code ServerboundSetCreativeModeSlotPacket} verbatim;
     * without this, the server writes a piece of paper into the slot and the
     * player's item is destroyed the first time they touch it.
     *
     * <p>An unrecognised true id — a mod disabled since the stack was sent — is
     * left as the projected stack rather than guessed at, and named in the log.
     * Handing back a made-up item would be worse than handing back paper.
     */
    public static ItemStack unproject(ItemStack stack) {
        if (stack.isEmpty()) {
            return stack;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return stack;
        }
        CompoundTag tag = data.copyTag();
        if (!tag.contains(ROOT)) {
            return stack;
        }
        CompoundTag payload = tag.getCompoundOrEmpty(ROOT);
        String raw = payload.getStringOr(KEY_ID, "");
        Identifier trueId = Identifier.tryParse(raw);
        if (trueId == null) {
            LOG.warning("a client echoed a VibeMod round-trip tag whose id \"" + raw + "\" is not a "
                    + "valid Identifier; the stack is left as the projected one rather than guessed at");
            return stack;
        }
        Item item = BuiltInRegistries.ITEM.getOptional(trueId).orElse(null);
        if (item == null) {
            LOG.warning("a client echoed " + trueId + ", which is not in this server's item registry "
                    + "any more; the stack is left as the projected " + BuiltInRegistries.ITEM
                    .getKey(stack.getItem()) + " rather than turned into an item that does not exist");
            return stack;
        }

        DataComponentPatch patch = DataComponentPatch.EMPTY;
        HolderLookup.Provider provider = registries.get();
        if (provider != null) {
            patch = payload.read(KEY_PATCH, DataComponentPatch.CODEC, opsFor(provider))
                    .orElse(DataComponentPatch.EMPTY);
        }
        UNPROJECTED.incrementAndGet();
        return new ItemStack(BuiltInRegistries.ITEM.wrapAsHolder(item), stack.getCount(), patch);
    }

    // ------------------------------------------------------------------ text

    /**
     * A {@code Component} with every {@code HoverEvent.ShowItem} projected,
     * siblings and all.
     *
     * <p>Chat, tab list, boss bar titles and screen titles all reach this. The
     * traversal returns the original instance when nothing changed, so an
     * ordinary chat line costs one style read per node and no allocation.
     *
     * <p>{@code HoverEvent.ShowEntity} is deliberately untouched: entities are
     * withheld from Lane B rather than projected (see {@code EntityRefusal}),
     * so there is never a VibeMod entity for a vanilla client to hover.
     */
    public static Component projectText(Component text) {
        if (text == null) {
            return null;
        }
        Style style = text.getStyle();
        Style newStyle = style;
        if (style.getHoverEvent() instanceof HoverEvent.ShowItem show) {
            ItemStack shown = show.item().create();
            ItemStack projected = project(shown);
            if (projected != shown) {
                newStyle = style.withHoverEvent(
                        new HoverEvent.ShowItem(ItemStackTemplate.fromStack(projected)));
            }
        }

        List<Component> siblings = text.getSiblings();
        List<Component> newSiblings = null;
        for (int i = 0; i < siblings.size(); i++) {
            Component sibling = siblings.get(i);
            Component projected = projectText(sibling);
            if (projected != sibling && newSiblings == null) {
                newSiblings = new ArrayList<>(siblings);
            }
            if (newSiblings != null) {
                newSiblings.set(i, projected);
            }
        }

        if (newStyle == style && newSiblings == null) {
            return text;
        }
        MutableComponent rebuilt = MutableComponent.create(text.getContents()).setStyle(newStyle);
        for (Component sibling : newSiblings == null ? siblings : newSiblings) {
            rebuilt.append(sibling);
        }
        return rebuilt;
    }

    // ------------------------------------------------------------------ the deny-list

    /**
     * Refuses, at mod-load time, a component whose value would have to be
     * projected recursively and is not.
     *
     * <p>The rule this enforces is "refuse rather than ship half a projection".
     * A {@code minecraft:container} or {@code minecraft:bundle_contents} holding
     * one of our items would reach a Lane B client as a stack-inside-a-stack we
     * did not rewrite, which is a VibeMod id on the wire and therefore a kick.
     * Telling the mod at registration is a sentence a model can act on; letting
     * it through is a bug report from a player.
     *
     * @return null when the item may be registered, or the refusal
     */
    public static String refuseUnprojectableComponents(Identifier id, ItemStack prototype) {
        List<String> offenders = new ArrayList<>();
        for (DataComponentType<?> type : List.of(DataComponents.CONTAINER,
                DataComponents.BUNDLE_CONTENTS)) {
            if (prototype.has(type)) {
                offenders.add(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).toString());
            }
        }
        if (offenders.isEmpty()) {
            return null;
        }
        return "refusing " + id + " for vanilla-client projection: it carries "
                + String.join(", ", offenders) + ", whose value is itself a list of stacks. Lane B "
                + "projects a stack's identity, not the stacks nested inside its components, so a "
                + "vanilla client would receive a VibeMod item id it has never heard of and be "
                + "disconnected. Register this item without that component, or run this mod on a "
                + "server where every client has VibeMod";
    }

    // ------------------------------------------------------------------ state, for the gates

    /** {@code "projectedStacks=12 unprojectedStacks=3 componentlessProjections=0"}. */
    public static String describeState() {
        return "projectedStacks=" + PROJECTED.get()
                + " unprojectedStacks=" + UNPROJECTED.get()
                + " componentlessProjections=" + STRANDED.get();
    }
}
