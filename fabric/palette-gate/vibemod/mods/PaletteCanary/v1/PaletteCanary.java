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
import net.minecraft.core.MappedRegistry;
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
import com.gijsm.vibemod.fabric.mixin.MappedRegistryAccessor;
import com.gijsm.vibemod.fabric.shim.BlockRegistration;
import com.gijsm.vibemod.fabric.shim.PaletteGuard;
import com.gijsm.vibemod.fabric.shim.RegistryTarget;
import com.gijsm.vibemod.fabric.shim.Shims;

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

    /**
     * The section's contents as of the moment the chunk was let go, for the
     * round-trip check.
     *
     * <p>Written twice, and the second write is the one that matters. The
     * crossing sets it so a crash between here and the save still leaves a
     * number to compare against; {@code pgcheck forget} <b>re-reads it</b>
     * immediately before dropping the force ticket, and reports whether the two
     * agreed.
     *
     * <p>They do not always agree, and that is a real world doing real work
     * rather than a palette problem. Leaves decay is a <em>scheduled</em> tick,
     * not a random one, so {@code gamerule randomTickSpeed 0} does not stop it,
     * and leaves are among the first 300 blockstate ids this gate fills a
     * section with. Comparing the reload against a number taken thirty seconds
     * and several hundred ticks earlier would be asserting that a live world
     * stands still. Comparing it against the section as it was actually let go
     * is the round trip.
     */
    private static volatile long savedChecksum;

    /**
     * Whether the crossing has already run.
     *
     * <p>A crossing is irreversible for the life of the JVM — {@code IdMapper}
     * has no remove — so a second {@code /pgcheck cross} must decline rather than
     * register a second batch of blocks against a budget that has already moved.
     */
    private static volatile boolean crossingRan;

    /**
     * Arms, and does nothing else.
     *
     * <p>This mod is stored enabled, so this runs from
     * {@code restoreModsFromDisk()} while the server is starting. The crossing
     * deliberately does NOT happen here: the gate has to measure the real
     * palette budget against a world that nothing has touched before anything
     * moves it, so the whole harness hangs off {@code /pgcheck cross} instead.
     */
    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("pgcheck")
                        .then(Commands.literal("cross").executes(ctx -> cross(ctx.getSource())))
                        .then(Commands.literal("forget").executes(ctx -> {
                            ServerLevel level = ctx.getSource().getServer().overworld();
                            // Re-read BEFORE the ticket goes, so the round trip
                            // below is measured against the section as it is let
                            // go rather than as it was at the crossing.
                            long atCrossing = savedChecksum;
                            savedChecksum = checksum(level);
                            level.setChunkForced(TEST_CX, TEST_CZ, false);
                            level.setChunkForced(LOCAL_CX, LOCAL_CZ, false);
                            say(ctx.getSource(), "pgcheck forget=ok"
                                    + " settled=" + (savedChecksum == atCrossing)
                                    + " checksum=" + savedChecksum);
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

        // The gate waits for this line before it asserts that nothing has
        // crossed yet: "the canary is loaded" and "the canary has not moved the
        // palette" are two different claims and both have to be provable.
        LOG.info("palette-gate: armed");
    }

    /**
     * The trigger, and the whole reason this mod does nothing at load.
     *
     * <p>{@code FabricEntrypointAdapter} opens the registration window around
     * {@code onInitialize()} and around nothing else, so this reopens it —
     * through the host's own seam, so the refreeze and the intrusive-holder
     * cleanup are the real ones — for the duration of the crossing.
     */
    private int cross(CommandSourceStack source) {
        if (crossingRan) {
            // Refused rather than repeated: the ids the first run minted are
            // still there, and a second run would size itself from a budget the
            // first one already spent.
            say(source, "pgcheck cross=already-done");
            return 1;
        }
        crossingRan = true;
        try {
            RegistryTarget registries = Shims.registries();
            try {
                if (registries == null) {
                    run();
                } else {
                    registries.withWindow(this::run);
                }
            } finally {
                // In a finally, not after the happy path: a run that threw
                // halfway may still have registered a block, and a half-repaired
                // registry crashes the tick loop either way.
                rebindBlockTags();
            }
            say(source, "pgcheck cross=ok");
        } catch (Throwable t) {
            // Logged AND answered: the log is what the gate asserts on, and the
            // reply is what a human running this by hand sees.
            LOG.log(Level.SEVERE, "palette-gate: aborted", t);
            say(source, "pgcheck cross=aborted " + t);
        }
        return 1;
    }

    /**
     * The half of {@code MappedRegistry.freeze()} that {@code register()} does
     * not do, done by hand because this harness registers around the seam.
     *
     * <p>{@code RegistrySeam.close()} runs this for a real registration, but
     * only when its own {@code register()} counted one — and this canary calls
     * {@code WritableRegistry.register} directly, on purpose, so that counter
     * stays at zero and the repair is skipped. A {@code Holder.Reference} whose
     * tags are unbound throws {@code IllegalStateException: Tags not bound} out
     * of {@code is(TagKey)}, and the first thing to ask is
     * {@code LeavesBlock.tick()} — which this gate schedules itself, because
     * leaves are among the 300 vanilla states it fills a section with.
     */
    private static void rebindBlockTags() {
        if (BuiltInRegistries.BLOCK instanceof MappedRegistry<?> mapped) {
            ((MappedRegistryAccessor) mapped).invokeRefreshTagsInHolders();
            LOG.info("palette-gate: tagsRebound=true");
        } else {
            LOG.info("palette-gate: tagsRebound=false (BLOCK is not a MappedRegistry)");
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
        //
        // The highest id is read back off BLOCK_STATE_REGISTRY rather than taken
        // from StateDefinition.getPossibleStates(), which returns a Guava
        // ImmutableList that a generated mod may not so much as name. It is also
        // the stronger question: "the last id that now exists" is what the old
        // 15-bit storage cannot hold, whatever order the state definition
        // happened to build its states in. Which block it belongs to is asserted
        // rather than assumed.
        BlockState widest = Block.BLOCK_STATE_REGISTRY.byId(guard.states() - 1);
        int wideId = Block.getId(widest);
        LOG.info("palette-gate: widestBelongsToCrosser=" + (widest.getBlock() == crosser));
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
     *
     * <p>{@code states} is what {@link Synthetic} was asked to build and
     * therefore what the guard is asked to admit, because the guard has to be
     * asked <em>before</em> a single id is minted and the only way to count the
     * states first is {@code getPossibleStates()}, which a generated mod may not
     * name. {@code appendStates} returns the real number, and the two are logged
     * side by side so a mismatch is a visible failure rather than a silent
     * miscount of the budget.
     */
    @SuppressWarnings("unchecked")
    private static Block registerSynthetic(PaletteGuard guard, int index, int states) {
        Identifier id = Identifier.fromNamespaceAndPath(NS, "synthetic_" + index);
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        Block block = Synthetic.of(states,
                BlockBehaviour.Properties.of().strength(1.0F).setId(key));

        // BEFORE anything is appended. IdMapper.add stores at nextId++ and there
        // is no remove, so a block that would not fit has to be stopped here.
        guard.admit("PaletteCanary", id.toString(), states);

        ((WritableRegistry<Block>) BuiltInRegistries.BLOCK)
                .register(key, block, RegistrationInfo.BUILT_IN);
        int appended = BlockRegistration.appendStates(block);
        LOG.info("palette-gate: synthetic " + id + " states=" + appended
                + " admitted=" + states + " countMatched=" + (appended == states));
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
     * through a static instead of a constructor argument — the same problem
     * vanilla solves by making every property a static final.
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

        /**
         * How many of {@link #BITS} the block currently under construction wants,
         * or -1 between constructions.
         *
         * <p>A plain static and not a {@code ThreadLocal}, for two reasons and
         * the first one is the real one: block registration is server-thread
         * only, inside the registration window, which is the same confinement
         * vanilla relies on to build every block in {@code Blocks.<clinit>} out
         * of static finals. The second is that the surgeon's forbidden-API
         * policy denies {@code java/lang/Thread} + {@code <init>} by
         * <em>prefix</em>, and {@code java.lang.ThreadLocal} starts with
         * {@code java/lang/Thread} — so a canary built on one is refused at
         * compile time with "forbidden API: java.lang.ThreadLocal.&lt;init&gt;
         * — creating threads". That refusal is how this gate first failed.
         */
        private static int pending = -1;

        static Block of(int states, BlockBehaviour.Properties properties) {
            pending = Integer.numberOfTrailingZeros(states);
            try {
                return new Synthetic(properties);
            } finally {
                pending = -1;
            }
        }

        private Synthetic(BlockBehaviour.Properties properties) {
            super(properties);
        }

        @Override
        protected void createBlockStateDefinition(
                StateDefinition.Builder<Block, BlockState> builder) {
            int count = pending;
            if (count < 0) {
                return;
            }
            for (int i = 0; i < count; i++) {
                builder.add(BITS[i]);
            }
        }
    }
}
