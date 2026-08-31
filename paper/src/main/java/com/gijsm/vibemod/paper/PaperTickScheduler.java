package com.gijsm.vibemod.paper;

import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import com.gijsm.vibemod.platform.TaskHandle;
import com.gijsm.vibemod.platform.TickScheduler;

/**
 * {@link TickScheduler} over Bukkit's scheduler.
 *
 * <p>{@link PaperTaskHandle#task()} exposes the underlying {@link BukkitTask}
 * because the Paper sdk flavor's {@code VibeContext.repeat/later} return one —
 * that is a frozen v3 signature the whole stored corpus compiles against, so the
 * host cannot hide it behind {@link TaskHandle}. Core only ever sees the handle.
 */
public final class PaperTickScheduler implements BukkitTaskScheduler {

    private final Plugin plugin;

    public PaperTickScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public PaperTaskHandle repeat(long delayTicks, long periodTicks, Runnable task) {
        return new PaperTaskHandle(
                plugin.getServer().getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks));
    }

    @Override
    public PaperTaskHandle later(long delayTicks, Runnable task) {
        return new PaperTaskHandle(
                plugin.getServer().getScheduler().runTaskLater(plugin, task, delayTicks));
    }

    @Override
    public PaperTaskHandle async(Runnable task) {
        return new PaperTaskHandle(
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task));
    }

    /**
     * Hops to the main thread — or drops the work when there is no longer a main
     * thread to hop to.
     *
     * <p>Bukkit refuses to schedule for a disabled plugin, and an async compile
     * that lands during shutdown legitimately has nowhere to go: its result is a
     * mod nobody is going to run. Letting the refusal propagate turns a normal
     * shutdown race into "Plugin VibeMod generated an exception while executing
     * task N" in the console, which reads like a bug and is not one.
     */
    @Override
    public void runOnMain(Runnable task) {
        if (onMain()) {
            task.run();
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException disabled) {
            plugin.getLogger().log(Level.FINE,
                    "Dropped a main-thread hop: the plugin is no longer enabled", disabled);
        }
    }

    @Override
    public boolean onMain() {
        return Bukkit.isPrimaryThread();
    }

    /** A {@link BukkitTask} as a revocable {@link TaskHandle}. */
    public static final class PaperTaskHandle implements BukkitTaskHandle {

        private final BukkitTask task;

        private PaperTaskHandle(BukkitTask task) {
            this.task = task;
        }

        /** The Bukkit task, for the sdk's frozen {@code BukkitTask}-returning signatures. */
        @Override
        public BukkitTask task() {
            return task;
        }

        @Override
        public void close() {
            try {
                if (!task.isCancelled()) {
                    task.cancel();
                }
            } catch (Throwable ignored) {
                // close() never throws
            }
        }

        @Override
        public boolean active() {
            return !task.isCancelled();
        }
    }
}
