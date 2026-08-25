package com.gijsm.vibemod.api;

/**
 * What {@link VibeContext#repeat} and {@link VibeContext#later} hand back:
 * a cancellable scheduled task.
 *
 * <p>Cancelling is optional — every task a mod schedules through its context is
 * cancelled for it when the mod is disabled or unloaded. This exists for the
 * mod that wants to stop one of its own tasks early.
 *
 * <p>Mod flavor only. The Paper flavor returns Bukkit's {@code BukkitTask}, a
 * frozen v3 signature the whole stored corpus compiles against. Declared here in
 * {@code com.gijsm.vibemod.api} rather than reusing the host's internal
 * {@code platform.TaskHandle} so that the generated-code import surface stays
 * exactly {@code java.*}, {@code com.gijsm.vibemod.api.*} and
 * {@code net.minecraft.*} — the three roots the prompt allows.
 */
public interface TaskHandle {

    /** Cancels the task. Idempotent; cancelling a finished one-shot task is a no-op. */
    void cancel();

    /** Whether the task is still scheduled. */
    boolean active();
}
