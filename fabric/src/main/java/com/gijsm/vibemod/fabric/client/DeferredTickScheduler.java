package com.gijsm.vibemod.fabric.client;

import java.util.logging.Logger;

import com.gijsm.vibemod.fabric.VibeModFabric;
import com.gijsm.vibemod.platform.TaskHandle;
import com.gijsm.vibemod.platform.TickScheduler;

/**
 * A {@link TickScheduler} that resolves the live one at call time.
 *
 * <p>The client half of VibeMod initialises before any server exists — that is
 * the whole reason it is a separate entrypoint — but the render-thread
 * {@link com.gijsm.vibemod.runtime.Watchdog} it builds there needs a scheduler
 * to hop a trip onto the server thread, and the only real scheduler belongs to a
 * server that has not started yet.
 *
 * <p>Which is fine, because a trip cannot happen before a server exists: it
 * takes a loaded mod to trip a watchdog, and mods load with the world. So this
 * simply looks the scheduler up when asked. Before that, {@code onMain()} is
 * false (the render thread is not a server thread) and work is dropped with a
 * log line rather than queued for a server that may never start.
 */
final class DeferredTickScheduler implements TickScheduler {

    private static final Logger LOG = Logger.getLogger(DeferredTickScheduler.class.getName());

    private static TickScheduler live() {
        VibeModFabric.Services services = VibeModFabric.services();
        return services == null ? null : services.scheduler();
    }

    @Override
    public TaskHandle repeat(long delayTicks, long periodTicks, Runnable task) {
        TickScheduler live = live();
        return live == null ? inactive() : live.repeat(delayTicks, periodTicks, task);
    }

    @Override
    public TaskHandle later(long delayTicks, Runnable task) {
        TickScheduler live = live();
        return live == null ? inactive() : live.later(delayTicks, task);
    }

    @Override
    public TaskHandle async(Runnable task) {
        TickScheduler live = live();
        return live == null ? inactive() : live.async(task);
    }

    @Override
    public void runOnMain(Runnable task) {
        TickScheduler live = live();
        if (live == null) {
            LOG.fine("Dropped a main-thread hop: no server is running");
            return;
        }
        live.runOnMain(task);
    }

    @Override
    public boolean onMain() {
        TickScheduler live = live();
        return live != null && live.onMain();
    }

    private static TaskHandle inactive() {
        return new TaskHandle() {
            @Override
            public void close() {
            }

            @Override
            public boolean active() {
                return false;
            }
        };
    }
}
