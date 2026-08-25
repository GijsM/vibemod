package com.gijsm.vibemod.api;

/**
 * What {@link VibeContext#repeat} and {@link VibeContext#later} hand back:
 * a cancellable scheduled task.
 *
 * <p>Cancelling is optional — every task a mod schedules through its context is
 * cancelled for it when the mod is disabled or unloaded. This exists for the
 * mod that wants to stop one of its own tasks early.
 *
 * <p>Declared in {@code com.gijsm.vibemod.api} rather than reusing the host's
 * own internal handle type so that the generated-code import surface stays
 * exactly {@code java.*}, {@code com.gijsm.vibemod.api.*} and
 * {@code net.minecraft.*} — the three roots the prompt allows. (The Paper
 * flavor of this contract returns its server's native task type instead, a
 * frozen signature the whole stored corpus compiles against; this file is the
 * loader half and is never seen on Paper.)
 */
public interface TaskHandle {

    /** Cancels the task. Idempotent; cancelling a finished one-shot task is a no-op. */
    void cancel();

    /** Whether the task is still scheduled. */
    boolean active();
}
