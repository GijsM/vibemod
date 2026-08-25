package com.gijsm.vibemod.fabric;

import net.fabricmc.api.ModInitializer;

import com.gijsm.vibemod.loader.EntrypointAdapter;

/**
 * The Fabric half of the V3 entrypoint check (Phase 0 §C).
 *
 * <p>Three lines, and every one of them is here rather than in
 * {@code LoaderModHost} because that class is compiled into the NeoForge jar
 * too and may not name {@code net.fabricmc.*} (§10.4). Naming
 * {@link ModInitializer} in {@code loader-common} would not merely be untidy —
 * it would not link on NeoForge, where the class does not exist.
 */
public final class FabricEntrypointAdapter implements EntrypointAdapter {

    @Override
    public Runnable adapt(Object instance) {
        return instance instanceof ModInitializer initializer ? initializer::onInitialize : null;
    }

    @Override
    public String describe() {
        return ModInitializer.class.getName();
    }
}
