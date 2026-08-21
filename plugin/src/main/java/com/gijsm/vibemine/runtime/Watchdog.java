package com.gijsm.vibemine.runtime;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Times mod entry points (listener callbacks, tasks, commands) and trips a mod
 * off when it is too slow, either in a single invocation or accumulated over a
 * rolling one second window. A tripped mod's wrapped bodies are short-circuited
 * (not run) until {@link #reset(String)} is called.
 */
public final class Watchdog {

    private static final long WINDOW_NANOS = 1_000_000_000L;

    private final Plugin plugin;
    private final long singleInvocationMs;
    private final long perSecondBudgetMs;
    private final boolean enabled;
    private final ConcurrentHashMap<String, ModStats> stats = new ConcurrentHashMap<>();
    private volatile Consumer<String> onTrip;

    public Watchdog(Plugin plugin, long singleInvocationMs, long perSecondBudgetMs) {
        this.plugin = plugin;
        this.singleInvocationMs = singleInvocationMs;
        this.perSecondBudgetMs = perSecondBudgetMs;
        this.enabled = singleInvocationMs > 0;
    }

    /** Registry hooks in so a tripped mod is auto-disabled + broadcast. */
    public void onTrip(Consumer<String> handler) {
        this.onTrip = handler;
    }

    /** Wrap a mod entry point: times it, records, and returns whether the mod is still healthy. */
    public void time(String mod, Runnable body) {
        if (!enabled) {
            body.run();
            return;
        }
        ModStats st = stats.computeIfAbsent(mod, m -> new ModStats());
        if (st.tripped) {
            return;
        }
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
                    if (elapsedMs > singleInvocationMs || windowMs > perSecondBudgetMs) {
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
            Bukkit.getScheduler().runTask(plugin, () -> handler.accept(mod));
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING,
                    "Watchdog could not schedule trip handler for mod " + mod + ", running inline", t);
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
