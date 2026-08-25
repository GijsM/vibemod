package com.gijsm.vibemod.platform;

/**
 * A scheduled task's handle. {@code close()} cancels the task (idempotent;
 * cancelling an already-finished one-shot task is a no-op).
 */
public interface TaskHandle extends Registration {

    /** Alias for {@link #close()}, matching scheduler vocabulary. */
    default void cancel() {
        close();
    }
}
