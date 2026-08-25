package com.gijsm.vibemod.loader;

/**
 * Recognises a mod main class that is a <em>native loader entrypoint</em>
 * rather than a VibeMod {@code Mod} (V3 Phase 0 §C).
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
 */
@FunctionalInterface
public interface EntrypointAdapter {

    /**
     * @param instance the freshly constructed mod main class
     * @return the mod's initializer, or null when {@code instance} is not a
     *         native entrypoint for this loader
     */
    Runnable adapt(Object instance);

    /** The interface this adapter looks for, for the "implements neither" error message. */
    default String describe() {
        return "a native loader entrypoint";
    }

    /** What a host with no native entrypoint support passes (NeoForge, Phase 0). */
    EntrypointAdapter NONE = new EntrypointAdapter() {
        @Override
        public Runnable adapt(Object instance) {
            return null;
        }

        @Override
        public String describe() {
            return "a native loader entrypoint (not supported on this loader yet)";
        }
    };
}
