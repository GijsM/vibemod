package com.gijsm.vibemine.api;

import java.nio.file.Path;
import java.util.logging.Logger;

import org.bukkit.Server;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Everything a generated mod may touch. All registrations are tracked per mod
 * and undone exactly when the mod is disabled or unloaded.
 *
 * All methods must be called from the main server thread.
 */
public interface VibeContext {

    /** The host plugin (VibeCore). For advanced use only. */
    Plugin plugin();

    /** Convenience for {@code plugin().getServer()}. */
    Server server();

    /** This mod's name. */
    String modName();

    /** Logger prefixed with the mod name. */
    Logger log();

    /** Per-mod data directory, created on first call. */
    Path dataFolder();

    /** Register an event listener (methods annotated with @EventHandler). Tracked. */
    void listen(Listener listener);

    /** Schedule a repeating main-thread task with an initial delay. Tracked. */
    BukkitTask repeat(long delayTicks, long periodTicks, Runnable task);

    /** Schedule a repeating main-thread task starting after one period. Tracked. */
    default BukkitTask repeat(long periodTicks, Runnable task) {
        return repeat(periodTicks, periodTicks, task);
    }

    /** Schedule a one-shot delayed main-thread task. Tracked. */
    BukkitTask later(long delayTicks, Runnable task);

    /**
     * Register a real top-level command, e.g. {@code command("boom", "Explodes things", h)}
     * gives players {@code /boom}. Falls back to an action (see {@link #action}) if
     * top-level registration is unavailable. Tracked and removed on disable.
     */
    void command(String name, String description, ModCommandHandler handler);

    /** Register a named action invocable as {@code /vibe do <mod> <name> [args]}. Tracked. */
    void action(String name, ModCommandHandler handler);
}
