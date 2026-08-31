package com.gijsm.vibemod.fabric.shim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import com.gijsm.vibemod.store.BlockSchema;

/**
 * What a deleted mod's block id comes back as (V4 Phase 1).
 *
 * <p>A block id can never be released — {@code RegistryLedger}'s class comment
 * has the palette-shift mechanism — so a deleted block mod's ids are pinned, and
 * on every subsequent boot each one is registered again as one of these. The
 * only thing a stub owes the world is that a saved chunk still decodes: a
 * section stores a state as its property names and value strings, so a stub
 * whose {@code StateDefinition} reproduces the original's is a correct answer,
 * and anything else is not.
 *
 * <h2>Why the schema arrives through a ThreadLocal</h2>
 *
 * <p>Not a shortcut — {@code javap} leaves no alternative:
 *
 * <pre>
 * public Block(BlockBehaviour$Properties);
 *   18: new           StateDefinition$Builder
 *   29: invokevirtual createBlockStateDefinition:(LStateDefinition$Builder;)V
 *   44: invokevirtual StateDefinition$Builder.create:(…)LStateDefinition;
 * </pre>
 *
 * <p>{@code createBlockStateDefinition} is called from {@code Block.<init>},
 * before any subclass field has been assigned. Vanilla's blocks get away with
 * reading static constants there; a stub's properties differ per instance and
 * are only known to the caller. {@link #PENDING} is the handoff, set and cleared
 * around one constructor call on one thread — which is the server thread, inside
 * the registration window, where block construction is single-threaded anyway.
 *
 * <h2>Property order needs no recording</h2>
 *
 * <p>{@code StateDefinition.propertiesByName} is an {@code ImmutableSortedMap}
 * and {@code StateDefinition$Builder.properties} is a plain {@code HashMap}
 * (both verified), so the cartesian product is a pure function of the properties
 * sorted by name and the order they were added in cannot be observed.
 * {@link #createBlockStateDefinition} adds them in name order regardless, so the
 * code says what the game does instead of relying on the reader knowing it.
 *
 * <h2>The state-count check is a refusal, not an assertion</h2>
 *
 * <p>{@link #create} rebuilds the definition and compares
 * {@code getPossibleStates().size()} against the recorded count, and refuses on
 * a mismatch. <b>A wrong stub is worse than a missing one.</b> A missing id is
 * one loud decode error per section; a stub with the wrong schema decodes a
 * saved property to a different state index, quietly, forever.
 *
 * <h2>Visibly inert</h2>
 *
 * <p>Solid, stone-sounding, breakable, dropping nothing, and — because no
 * resource pack ships for a mod that no longer exists — rendered as the missing
 * model. That is the honest presentation: something was here and its mod is
 * gone.
 */
public final class StubBlock extends Block {

    /**
     * The schema {@link #createBlockStateDefinition} is about to read, live only
     * for the duration of one constructor call. See the class comment.
     */
    private static final ThreadLocal<BlockSchema> PENDING = new ThreadLocal<>();

    private StubBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /**
     * Reads a live block's state schema, at registration time, in the form a
     * later boot can rebuild it from.
     *
     * <p>{@code Property.getName(T)} rather than {@code toString()} on the
     * value: the save codec writes exactly what {@code getName} returns, and for
     * an enum property those two differ the moment a mod's enum has a
     * {@code StringRepresentable} name that is not its Java constant name.
     */
    public static BlockSchema schemaOf(Identifier id, Block block) {
        StateDefinition<Block, BlockState> definition = block.getStateDefinition();
        List<BlockSchema.Prop> properties = new ArrayList<>();
        for (Property<?> property : definition.getProperties()) {
            properties.add(new BlockSchema.Prop(property.getName(), valueNames(property)));
        }
        properties.sort(Comparator.comparing(BlockSchema.Prop::name));
        return new BlockSchema(id.toString(), properties,
                definition.getPossibleStates().size());
    }

    private static <T extends Comparable<T>> List<String> valueNames(Property<T> property) {
        List<String> names = new ArrayList<>();
        for (T value : property.getPossibleValues()) {
            names.add(property.getName(value));
        }
        return names;
    }

    /**
     * Builds the stub for one pinned id, or refuses.
     *
     * <p>Refuses twice, on purpose. Before construction, on
     * {@link BlockSchema#problems()} — a name the game's own
     * {@code NAME_PATTERN} rejects, or a single-valued property, would otherwise
     * throw {@code IllegalArgumentException} from inside {@code Block.<init>},
     * which is to say from inside the constructor of an object that has already
     * taken an intrusive registry holder. And after construction, on the state
     * count, which is the only check that can catch a schema that is internally
     * consistent and still not the original's.
     *
     * <p>A refusal after construction does leave a constructed-but-unregistered
     * intrusive holder behind. That is already handled: {@code RegistrySeam}'s
     * window close discards those and says so.
     *
     * @throws IllegalStateException with the mismatch named, so the operator
     *         reading the log knows which id was lost and why
     */
    public static StubBlock create(BlockSchema schema, ResourceKey<Block> key) {
        List<String> problems = schema.problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("refusing to build a stub for pinned block "
                    + key.identifier() + ": " + String.join("; ", problems));
        }

        PENDING.set(schema);
        StubBlock stub;
        try {
            stub = new StubBlock(inert(key));
        } finally {
            PENDING.remove();
        }

        int rebuilt = stub.getStateDefinition().getPossibleStates().size();
        if (rebuilt != schema.stateCount()) {
            throw new IllegalStateException("refusing to register a stub for pinned block "
                    + key.identifier() + ": it rebuilds to " + rebuilt + " blockstate(s) but "
                    + schema.stateCount() + " were recorded (" + schema + "). A stub with the "
                    + "wrong schema decodes a saved property to the wrong state index silently, "
                    + "which is worse than the id being missing — no silent drops");
        }
        return stub;
    }

    /**
     * The properties of a block that does nothing.
     *
     * <p>{@code setId} before anything else because
     * {@code BlockBehaviour.<init>} reads the id twice — for the description id
     * and for the loot-table key — and both are baked before
     * {@code Registry.register} is reached; see {@code RegistrySeam.blockId}.
     * {@code noLootTable()} is what makes the loot key irrelevant here anyway:
     * the stub drops nothing, because whatever it used to drop belonged to a mod
     * that no longer exists.
     *
     * <p>The description id is the block's own id plus a plain-English tail.
     * There is no lang file for a deleted mod, and a client renders an unknown
     * translation key verbatim — so the key IS the label, and it may as well say
     * something true.
     */
    private static BlockBehaviour.Properties inert(ResourceKey<Block> key) {
        return BlockBehaviour.Properties.of()
                .setId(key)
                .sound(SoundType.STONE)
                .strength(1.5F, 6.0F)
                .noLootTable()
                .overrideDescription(key.identifier() + " (mod deleted, block pinned)");
    }

    /**
     * The recorded properties, in name order.
     *
     * <p>Reads {@link #PENDING} rather than a field because it is called from
     * {@code Block.<init>}, before this object's fields exist. A null there means
     * somebody constructed a {@code StubBlock} outside {@link #create}, which
     * the private constructor makes impossible; it degrades to a property-less
     * block rather than throwing out of a constructor, and the state-count check
     * in {@link #create} catches it a moment later with a message that names the
     * id.
     */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        BlockSchema schema = PENDING.get();
        if (schema == null) {
            return;
        }
        List<BlockSchema.Prop> properties = new ArrayList<>(schema.properties());
        properties.sort(Comparator.comparing(BlockSchema.Prop::name));
        for (BlockSchema.Prop property : properties) {
            builder.add(new StubProperty(property.name(), property.values()));
        }
    }
}
