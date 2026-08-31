package com.gijsm.vibemod.loader;

import com.gijsm.vibemod.runtime.ModHandle;
import com.gijsm.vibemod.runtime.ModLoadException;

/**
 * Recognises a mod main class that is a <em>native loader entrypoint</em>
 * rather than a VibeMod {@code Mod} (V3 Phase 0 §C, widened in Phase 1 §B).
 *
 * <p>This interface exists for one reason and it is a hard constraint, not a
 * taste: {@code loader-common} is compiled into both loader hosts and must not
 * name {@code net.fabricmc.*} or {@code net.neoforged.*} (§10.4). The check the
 * host needs is literally {@code obj instanceof ModInitializer} — a Fabric type
 * — so the check is injected and {@link LoaderModHost} only ever sees an
 * {@code Object} and a {@link Runnable}.
 *
 * <p>Returning a {@link Runnable} rather than a boolean is deliberate too: the
 * adapter has already done the cast, so the host never reflects and never
 * downcasts a second time.
 *
 * <p><b>Phase 1</b> hands the adapter the mod's {@link ModHandle} and its class
 * loader as well, and that is what keeps the client half of the contract on the
 * Fabric side of the wall. A Fabric mod's second entrypoint
 * ({@code ClientModInitializer}) has to run on the render thread, under the
 * mod's attribution, and a mod that opened its own {@code Screen} has to have it
 * closed when the mod is disabled — all three need the handle or the loader, and
 * all three name client-only types. Passing both here means {@code LoaderModHost}
 * still knows nothing about any of it.
 */
@FunctionalInterface
public interface EntrypointAdapter {

    /**
     * @param handle   the mod being activated, for attribution and for tracking
     *                 anything the adapter itself registers
     * @param loader   the mod's own {@code BytesClassLoader}, so the adapter can
     *                 recognise objects the mod defined
     * @param instance the freshly constructed mod main class
     * @return the mod's initializer, or null when {@code instance} is not a
     *         native entrypoint for this loader
     * @throws ModLoadException when {@code instance} <em>is</em> a native
     *         entrypoint that this side cannot run at all (a client-only mod on
     *         a dedicated server); the caller rolls the activation back
     */
    Runnable adapt(ModHandle handle, ClassLoader loader, Object instance) throws ModLoadException;

    /** The interface this adapter looks for, for the "implements neither" error message. */
    default String describe() {
        return "a native loader entrypoint";
    }

    /** What a host with no native entrypoint support passes (NeoForge, Phase 0). */
    EntrypointAdapter NONE = new EntrypointAdapter() {
        @Override
        public Runnable adapt(ModHandle handle, ClassLoader loader, Object instance) {
            return null;
        }

        @Override
        public String describe() {
            return "a native loader entrypoint (not supported on this loader yet)";
        }
    };
}
