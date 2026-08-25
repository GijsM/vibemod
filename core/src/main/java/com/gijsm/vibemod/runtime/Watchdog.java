package com.gijsm.vibemod.runtime;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.gijsm.vibemod.platform.TickScheduler;

/**
 * Times mod entry points (listener callbacks, tasks, commands) and trips a mod
 * off when it is too slow, either in a single invocation or accumulated over a
 * rolling one second window. A tripped mod's wrapped bodies are short-circuited
 * (not run) until {@link #reset(String)} is called.
 *
 * <p>v2 change (ARCHITECTURE-V2 §1.1, §8.4): the measured thread is a
 * constructor parameter. The server instance measures the main server thread;
 * a loader host builds a second instance measuring the render thread, with the
 * same budgets and the same trip path — that is the whole difference between
 * watchdogging a listener and watchdogging a HUD renderer.
 */
public final class Watchdog {

    private static final long WINDOW_NANOS = 1_000_000_000L;

    private static final Logger LOG = Logger.getLogger(Watchdog.class.getName());

    private final TickScheduler scheduler;
    private final String threadLabel;
    private volatile long singleInvocationMs;
    private volatile long perSecondBudgetMs;
    private final ConcurrentHashMap<String, ModStats> stats = new ConcurrentHashMap<>();
    private volatile Consumer<String> onTrip;

    /**
     * @param threadLabel which thread this instance measures ({@code "main"},
     *                    {@code "render"}) — appears in trip diagnostics
     */
    public Watchdog(TickScheduler scheduler, String threadLabel,
                    long singleInvocationMs, long perSecondBudgetMs) {
        this.scheduler = scheduler;
        this.threadLabel = threadLabel;
        this.singleInvocationMs = singleInvocationMs;
        this.perSecondBudgetMs = perSecondBudgetMs;
    }

    /** Which thread this watchdog measures. */
    public String threadLabel() {
        return threadLabel;
    }

    /** Registry hooks in so a tripped mod is auto-disabled + broadcast. */
    public void onTrip(Consumer<String> handler) {
        this.onTrip = handler;
    }

    /**
     * Replace the budgets used by subsequent invocations. Same semantics as the
     * constructor: a single-invocation budget of zero or less disables timing.
     */
    public void setBudgets(long singleInvocationMs, long perSecondBudgetMs) {
        this.singleInvocationMs = singleInvocationMs;
        this.perSecondBudgetMs = perSecondBudgetMs;
    }

    /** Wrap a mod entry point: times it, records, and returns whether the mod is still healthy. */
    public void time(String mod, Runnable body) {
        long singleBudget = singleInvocationMs;
        if (singleBudget <= 0) {
            body.run();
            return;
        }
        ModStats st = stats.computeIfAbsent(mod, m -> new ModStats());
        if (st.tripped) {
            return;
        }
        long perSecondBudget = perSecondBudgetMs;
        long t0 = System.nanoTime();
        try {
            body.run();
        } finally {
            long elapsedNanos = System.nanoTime() - t0;
            long elapsedMs = elapsedNanos / 1_000_000L;
            boolean trip = false;
            synchronized (st) {
                if (!st.tripped) {
                    long now = System.nanoTime();
                    if (now - st.windowStartNanos > WINDOW_NANOS) {
                        st.windowStartNanos = now;
                        st.accumulatedNanosInWindow = 0L;
                    }
                    st.accumulatedNanosInWindow += elapsedNanos;
                    long windowMs = st.accumulatedNanosInWindow / 1_000_000L;
                    if (elapsedMs > singleBudget || windowMs > perSecondBudget) {
                        st.tripped = true;
                        trip = true;
                    }
                }
            }
            if (trip) {
                fireTrip(mod);
            }
        }
    }

    private void fireTrip(String mod) {
        Consumer<String> handler = this.onTrip;
        if (handler == null) {
            return;
        }
        try {
            scheduler.runOnMain(() -> handler.accept(mod));
        } catch (Throwable t) {
            LOG.log(Level.WARNING,
                    "Watchdog (" + threadLabel + ") could not schedule the trip handler for mod " + mod
                            + ", running inline", t);
            handler.accept(mod);
        }
    }

    public boolean isTripped(String mod) {
        ModStats st = stats.get(mod);
        return st != null && st.tripped;
    }

    public void reset(String mod) {
        stats.remove(mod);
    }

    private static final class ModStats {
        long windowStartNanos = System.nanoTime();
        long accumulatedNanosInWindow;
        volatile boolean tripped;
    }
}
