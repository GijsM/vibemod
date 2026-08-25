package com.gijsm.vibemod.platform;

/**
 * A revocable registration: the universal teardown currency of VibeMod v2.
 * Every listener, task, command, HUD element, key lease or capture made on
 * behalf of a generated mod returns one; the mod's handle tracks them and
 * drains the list on disable/unload — the cross-platform generalization of
 * the v1 {@code HandlerList.unregisterAll} + task-cancel + command-unregister
 * teardown.
 *
 * <p>{@link #close()} is idempotent, thread-safe and never throws.
 */
public interface Registration extends AutoCloseable {

    /** Revokes this registration. Idempotent; never throws. */
    @Override
    void close();

    /** Whether this registration is still active (i.e. not yet closed). */
    boolean active();

    /**
     * The canonical implementation: {@code revoke} runs at most once, on the
     * first {@link #close()}, and anything it throws is swallowed (teardown is
     * best-effort by contract).
     */
    static Registration of(Runnable revoke) {
        return new Registration() {
            private final java.util.concurrent.atomic.AtomicBoolean open =
                    new java.util.concurrent.atomic.AtomicBoolean(true);

            @Override
            public void close() {
                if (open.compareAndSet(true, false)) {
                    try {
                        revoke.run();
                    } catch (Throwable ignored) {
                        // teardown is best-effort; close() never throws
                    }
                }
            }

            @Override
            public boolean active() {
                return open.get();
            }
        };
    }

    /** A registration that never did anything — what an unavailable capability returns. */
    static Registration inactive() {
        return new Registration() {
            @Override
            public void close() {
            }

            @Override
            public boolean active() {
                return false;
            }
        };
    }

    /** Closes every registration in {@code list} and clears it. Never throws. */
    static void closeAll(java.util.Collection<? extends Registration> list) {
        for (Registration r : java.util.List.copyOf(list)) {
            try {
                r.close();
            } catch (Throwable ignored) {
                // best-effort
            }
        }
        list.clear();
    }
}
