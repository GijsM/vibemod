package com.gijsm.vibemod.loader;

import com.gijsm.vibemod.runtime.ModHandle;

/**
 * Who the current thread is running mod code on behalf of (V3 Phase 0 §B).
 *
 * <p>V3's whole trick is that a generated mod calls the loader's real API and
 * the host quietly stands in the middle. That works for teardown only if the
 * host knows <em>whose</em> registration it is holding — and unlike the v2
 * {@code ctx.on*} hooks, a native call site carries no mod identity at all:
 * {@code ServerTickEvents.END_SERVER_TICK.register(this::tick)} says nothing
 * about which mod is speaking.
 *
 * <p>So the identity travels on the thread instead. The host sets it around
 * the mod's entrypoint and around every dispatch into the mod's own callbacks,
 * and the shims read it. The second half is what makes nesting work: a mod that
 * registers another listener from inside a tick handler is still attributed to
 * itself, because the dispatch that called the handler set the attribution
 * first.
 *
 * <p>A {@link ThreadLocal} rather than a field because the render thread and
 * the server thread both run mod code in a singleplayer client (§8.4), and a
 * shared field would attribute one side's registrations to the other's mod.
 */
public final class ModAttribution {

    private static final ThreadLocal<ModHandle> CURRENT = new ThreadLocal<>();

    private ModAttribution() {
    }

    /** The mod this thread is currently running code for, or null outside mod code. */
    public static ModHandle current() {
        return CURRENT.get();
    }

    /**
     * Runs {@code body} attributed to {@code handle}, restoring whatever was
     * attributed before.
     *
     * <p>Restores rather than clears, because these nest: a mod's tick handler
     * that enables another mod would otherwise return to an unattributed
     * thread halfway through its own dispatch.
     */
    public static void runAs(ModHandle handle, Runnable body) {
        ModHandle previous = CURRENT.get();
        CURRENT.set(handle);
        try {
            body.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * {@link #runAs} for a call that returns a value and may throw.
     *
     * <p>Needed by the client half of the event fanout (V3 Phase 1 §B), where
     * the merge policy wants the listener's return value and the guard wants the
     * exception — so neither {@link Runnable} nor a captured array is honest
     * about what is happening.
     */
    public static <T> T call(ModHandle handle, Body<T> body) throws Exception {
        ModHandle previous = CURRENT.get();
        CURRENT.set(handle);
        try {
            return body.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** A call into mod code that returns a value and may throw a checked exception. */
    @FunctionalInterface
    public interface Body<T> {
        T run() throws Exception;
    }
}
