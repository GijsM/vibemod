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
}
