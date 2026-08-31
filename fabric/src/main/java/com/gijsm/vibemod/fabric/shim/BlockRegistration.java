package com.gijsm.vibemod.fabric.shim;

import java.util.List;
import java.util.logging.Logger;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The three things vanilla does to a block that {@code Registry.register} does
 * not (V4 Phase 1).
 *
 * <p>Extracted from {@link RegistrySeam} because it is not registration — it is
 * the tail of {@code Blocks.<clinit>}, which a generated mod never runs. Read
 * off the jar rather than remembered; this is the last 70 bytes of it:
 *
 * <pre>
 * 31818: getstatic       BuiltInRegistries.BLOCK
 * 31821: invokeinterface DefaultedRegistry.iterator:()Ljava/util/Iterator;
 *   …
 * 31847: invokevirtual   Block.getStateDefinition:()…StateDefinition;
 * 31850: invokevirtual   StateDefinition.getPossibleStates:()…ImmutableList;
 *   …
 * 31876: getstatic       Block.BLOCK_STATE_REGISTRY:Lnet/minecraft/core/IdMapper;
 * 31880: invokevirtual   IdMapper.add:(Ljava/lang/Object;)V
 * 31884: invokevirtual   BlockState.initCache:()V
 * </pre>
 *
 * <p>Both calls, in that order, per state. {@code initCache()} is not optional
 * and its omission does not look like an omission: the state registers, the
 * block places, and then the light engine dereferences a null cache the first
 * time somebody puts one down.
 *
 * <h2>Why the guard runs before the registration and not after</h2>
 *
 * <p>{@code IdMapper.add} appends at {@code nextId++} and nothing ever takes an
 * id back out, so a block whose states would push
 * {@code BLOCK_STATE_REGISTRY.size()} past the global palette's bit boundary
 * has to be refused <em>before</em> it is registered. {@link #admit} is
 * therefore called on the near side of {@code MappedRegistry.register}: a
 * refusal there leaves the block as a constructed-but-unregistered intrusive
 * holder, which {@code RegistrySeam}'s window close already discards loudly,
 * rather than as a live id whose states never landed — which would be a block
 * the registry knows about and no chunk could ever hold.
 *
 * <h2>{@code Item.BY_BLOCK}</h2>
 *
 * <p>The easiest thing in the phase to miss, because nothing throws. Vanilla's
 * {@code Items.registerItem} does this before it registers anything:
 *
 * <pre>
 * 16: instanceof    class net/minecraft/world/item/BlockItem
 * 30: getstatic     Item.BY_BLOCK:Ljava/util/Map;
 * 34: invokevirtual BlockItem.registerBlocks:(Ljava/util/Map;Lnet/minecraft/world/item/Item;)V
 * </pre>
 *
 * <p>Generated code calls {@code Registry.register} directly and never reaches
 * that private helper, so without {@link #linkBlockItem} the map has no entry,
 * {@code Block.asItem()} falls through to air, and pick-block,
 * {@code getCloneItemStack} and every block-derived recipe hand back nothing.
 * {@code BY_BLOCK} is a plain {@code Maps.newHashMap()} (verified in
 * {@code Item.<clinit>}), which is what makes {@link #unlinkBlockItem}
 * possible — this is the one part of a block registration that really is
 * revocable.
 */
public final class BlockRegistration {

    private static final Logger LOG = Logger.getLogger("VibeMod.Registry.Block");

    private BlockRegistration() {
    }

    /**
     * Asks the palette guard whether this block's states fit, before anything
     * is mutated.
     *
     * @throws UnsupportedOperationException the guard's own refusal, propagated
     *         unchanged so the mod is told the guard's budget arithmetic rather
     *         than a paraphrase of it
     */
    public static void admit(PaletteGuard guard, String modName, Identifier id, Block block) {
        int states = block.getStateDefinition().getPossibleStates().size();
        if (guard == null) {
            // Not fatal, but said out loud rather than skipped silently: with no
            // guard installed nothing is checking the global palette's bit
            // width, and a crossing then surfaces as an IllegalArgumentException
            // out of SimpleBitStorage.set on a live chunk write instead of as a
            // refusal here.
            LOG.warning("Registering block " + id + " for mod " + modName + " with " + states
                    + " state(s) and NO palette guard installed: the blockstate budget is "
                    + "unchecked, so crossing the global palette's bit boundary will surface as "
                    + "an IllegalArgumentException from SimpleBitStorage on the first chunk "
                    + "write instead of as a refusal here");
            return;
        }
        guard.admit(modName, id.toString(), states);
    }

    /**
     * Vanilla's loop, verbatim, for one block.
     *
     * @return how many states were appended — the number the palette guard was
     *         asked to admit, and the number {@code describeState} reports
     */
    public static int appendStates(Block block) {
        List<BlockState> states = block.getStateDefinition().getPossibleStates();
        for (BlockState state : states) {
            Block.BLOCK_STATE_REGISTRY.add(state);
            state.initCache();
        }
        return states.size();
    }

    /**
     * {@code Items.registerItem}'s {@code BlockItem} branch, for an item a mod
     * registered itself. A no-op unless {@code item} is a {@link BlockItem}.
     */
    public static void linkBlockItem(Item item) {
        if (item instanceof BlockItem blockItem) {
            // registerBlocks, not a bare put: a BlockItem subclass may stand for
            // several blocks — vanilla's own DoubleHighBlockItem does — and only
            // the item knows which.
            blockItem.registerBlocks(Item.BY_BLOCK, item);
            LOG.fine(() -> "Linked " + item + " into Item.BY_BLOCK so Block.asItem() resolves");
        }
    }

    /**
     * Takes the same entries back out when the mod is drained.
     *
     * <p>By value identity rather than by block, for the same reason
     * {@link #linkBlockItem} delegates: the item chose which keys it claimed,
     * and the only thing certain from out here is that every entry pointing at
     * <em>this</em> item was one of them.
     *
     * @return how many mappings were removed
     */
    public static int unlinkBlockItem(Item item) {
        if (!(item instanceof BlockItem)) {
            return 0;
        }
        int before = Item.BY_BLOCK.size();
        Item.BY_BLOCK.values().removeIf(mapped -> mapped == item);
        int removed = before - Item.BY_BLOCK.size();
        if (removed > 0) {
            LOG.fine(() -> "Removed " + removed + " Item.BY_BLOCK mapping(s) for a drained item");
        }
        return removed;
    }
}
