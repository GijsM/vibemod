package com.gijsm.vibemod.paper;

import org.bukkit.Bukkit;
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
public final class PaperTickScheduler implements TickScheduler {

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

    @Override
    public void runOnMain(Runnable task) {
        if (onMain()) {
            task.run();
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    @Override
    public boolean onMain() {
        return Bukkit.isPrimaryThread();
    }

    /** A {@link BukkitTask} as a revocable {@link TaskHandle}. */
    public static final class PaperTaskHandle implements TaskHandle {

        private final BukkitTask task;

        private PaperTaskHandle(BukkitTask task) {
            this.task = task;
        }

        /** The Bukkit task, for the sdk's frozen {@code BukkitTask}-returning signatures. */
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
