package com.gijsm.vibemod.runtime;

import com.gijsm.vibemod.platform.Registration;

/**
 * The host's half of a mod's non-Java files (V3 Phase 2 §B).
 *
 * <p>Installed on {@link ModLifecycle}, called once per activation, and — like
 * everything else a mod acquires — it answers with a {@link Registration} that
 * takes it all away again. Which is why the hook is here and not at the call
 * sites that load a mod: {@code /vibe enable} on an already-compiled mod goes
 * straight to {@code lifecycle.enable(...)} and never touches the store, so a
 * content installer wired into the compile path would materialize a datapack on
 * first load and silently skip it on every re-enable afterwards.
 *
 * <p>The implementation ({@code LoaderModContent}) lives in {@code loader-common}
 * because materializing a datapack is Mojang-typed work both loaders share.
 * Paper passes nothing and gets {@link #NONE}.
 */
@FunctionalInterface
public interface ModContent {

    /**
     * Materializes {@code handle}'s stored resources. Returns the
     * {@link Registration} that removes them again, or {@code null} when the
     * mod ships none.
     *
     * <p>Called on the main server thread, after the mod's own entrypoint has
     * run. Must not block on a resource reload: the returned registration's
     * {@code close()} is called from inside a teardown the watchdog is timing.
     */
    Registration install(ModHandle handle);

    /** A host with no content channel at all. */
    ModContent NONE = handle -> null;
}
