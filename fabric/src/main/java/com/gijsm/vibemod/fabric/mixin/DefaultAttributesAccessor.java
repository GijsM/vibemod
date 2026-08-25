package com.gijsm.vibemod.fabric.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;

/**
 * The removal half of {@code FabricDefaultAttributeRegistry} (V3 Phase 3 §B).
 *
 * <p>Registering default attributes needs no seam to <em>work</em>:
 * {@code FabricDefaultAttributeRegistry.register} is a plain
 * {@code SUPPLIERS.put}, and fabric-object-builder-api's own
 * {@code DefaultAttributesMixin} has already replaced vanilla's immutable map
 * with an {@code IdentityHashMap} by the time any mod runs (disassembled:
 * {@code SUPPLIERS = new IdentityHashMap<>(SUPPLIERS)}). What it needs a seam
 * for is the other direction — there is no {@code unregister}, and a supplier
 * left behind for a disabled mod keys a map on an {@code EntityType} whose
 * class loader is meant to be collectable.
 *
 * <p>Our own accessor rather than fabric's internal
 * {@code net.fabricmc.fabric.mixin.object.builder.DefaultAttributesAccessor}:
 * that one is an implementation detail of another mod, and depending on an
 * internal mixin interface is exactly the kind of link that breaks on a
 * fabric-api bump with no compile error anywhere in this repo.
 */
@Mixin(DefaultAttributes.class)
public interface DefaultAttributesAccessor {

    @Accessor("SUPPLIERS")
    static Map<EntityType<? extends LivingEntity>, AttributeSupplier> getSuppliers() {
        throw new AssertionError("mixin did not apply");
    }
}
