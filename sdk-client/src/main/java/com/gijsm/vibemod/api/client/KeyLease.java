package com.gijsm.vibemod.api.client;

/**
 * A leased key slot (see {@link ClientContext#key}). Released automatically
 * when the mod is disabled; call {@link #release()} only to give the slot
 * back early.
 */
public interface KeyLease {

    /** Returns the slot to the pool (clears the handler; unbinds only auto-bound keys). Idempotent. */
    void release();

    /** Whether this lease still holds its slot. */
    boolean active();

    /** The pooled slot's name as shown in the Controls screen, e.g. {@code "VibeMod Slot 3"}. */
    String slotName();

    /** Polling alternative to the {@code onPress} callback: true while the key is held. */
    boolean pressed();
}
