package com.gijsm.vibemod.fabric.shim;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * What {@link Shims} delegates a rewritten registry call to (V3 Phase 3 §A).
 *
 * <p>An interface rather than a direct reference to {@link RegistrySeam}, for
 * exactly the reason {@link EventSeam} is one: the surgeon self-test rewrites a
 * real class, defines it, and runs it, and it needs somewhere for the
 * registration to land that is not a live game. A recorder implements this and
 * the round trip is provable end to end; without it the test could only assert
 * on the constant pool, which shows the rewrite happened but not that the shim
 * it points at exists and accepts what the bytecode pushes.
 */
public interface RegistryTarget {

    /** All three {@code Registry.register} overloads; {@code id} is an Identifier, ResourceKey or String. */
    Object register(Registry<?> registry, Object id, Object value);

    /** Both {@code Registry.registerForHolder} overloads. */
    Holder.Reference<?> registerForHolder(Registry<?> registry, Object id, Object value);

    /** {@code Item.Properties.setId}, namespaced. */
    Item.Properties itemId(Item.Properties properties, ResourceKey<Item> key);

    /**
     * {@code BlockBehaviour.Properties.setId}, namespaced.
     *
     * <p>Separate from {@link #itemId} rather than folded into it because the
     * two are different classes with different return types — the seam table
     * matches on the whole descriptor, and so must this.
     */
    BlockBehaviour.Properties blockId(BlockBehaviour.Properties properties, ResourceKey<Block> key);

    /** {@code EntityType.Builder.build}, namespaced. */
    EntityType<?> entityTypeBuild(EntityType.Builder<?> builder, ResourceKey<EntityType<?>> key);

    /** {@code FabricDefaultAttributeRegistry.register(EntityType, AttributeSupplier)}. */
    void defaultAttributes(EntityType<? extends LivingEntity> type, AttributeSupplier supplier);

    /** {@code FabricDefaultAttributeRegistry.register(EntityType, AttributeSupplier.Builder)}. */
    void defaultAttributes(EntityType<? extends LivingEntity> type, AttributeSupplier.Builder builder);

    /**
     * Runs a mod's {@code onInitialize()} with the game's registries open.
     *
     * <p>Defaulted to "just run it" so a recorder needs no unfreeze machinery:
     * the window is the real seam's business, and a test that only wants to see
     * where a call site went should not have to open the item registry to find
     * out.
     */
    default void withWindow(Runnable body) {
        body.run();
    }
}
