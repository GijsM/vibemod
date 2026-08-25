package com.gijsm.vibemod.platform;

/**
 * Tick-based scheduling over the platform's main server thread.
 *
 * <p>Implementations: Bukkit scheduler on Paper; an END_SERVER_TICK-driven
 * tick wheel on Fabric; ServerTickEvent.Post on NeoForge. {@code async} runs
 * on a platform or JDK pool. All callbacks except {@code async} run on the
 * main server thread. Delays/periods are in game ticks (20/s).
 */
public interface TickScheduler {

    /** Repeating main-thread task; first run after {@code delayTicks}. */
    TaskHandle repeat(long delayTicks, long periodTicks, Runnable task);

    /** One-shot main-thread task after {@code delayTicks}. */
    TaskHandle later(long delayTicks, Runnable task);

    /** Off-thread task (LLM calls, compilation, IO). */
    TaskHandle async(Runnable task);

    /** Runs {@code task} on the main thread — immediately when already there, else next tick. */
    void runOnMain(Runnable task);

    /** True when the calling thread is the main server thread. */
    boolean onMain();
}
