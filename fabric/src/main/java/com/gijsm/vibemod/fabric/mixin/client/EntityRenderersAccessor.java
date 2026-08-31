package com.gijsm.vibemod.fabric.mixin.client;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.world.entity.EntityType;

/**
 * The other half of a revocable entity renderer (V3 Phase 3 §B).
 *
 * <p>fabric-rendering-v1's {@code EntityRendererRegistry.register} is a one-way
 * door: after {@code EntityRenderers.createEntityRenderers} first runs, Fabric
 * swaps its own handler for one that puts straight into vanilla's
 * {@code EntityRenderers.PROVIDERS} — which is exactly why late registration
 * WORKS (the map is a mutable {@code Object2ObjectOpenHashMap}, and
 * {@code EntityRenderDispatcher} is a {@code ResourceManagerReloadListener} that
 * rebuilds every renderer from it on each resource reload). There is simply no
 * unregister to match it, and a renderer left behind for a mod that is gone is
 * a class loader that can never be collected and a crash the first time an
 * entity of that type is drawn.
 *
 * <p>So VibeMod removes the entry itself and lets the same reload that
 * installed the renderer take it away again.
 */
@Mixin(EntityRenderers.class)
public interface EntityRenderersAccessor {

    @Accessor("PROVIDERS")
    static Map<EntityType<?>, EntityRendererProvider<?>> getProviders() {
        throw new AssertionError("mixin did not apply");
    }
}
