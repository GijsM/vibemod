package com.gijsm.vibemine.api;

/**
 * The contract every generated mod implements. Exactly one public class per mod
 * implements this interface; it must have a public no-arg constructor.
 *
 * All registrations (listeners, tasks, commands, actions) MUST go through the
 * supplied {@link VibeContext} so the mod can be torn down exactly on
 * disable/unload. A mod must never call Bukkit registration APIs directly.
 */
public interface VibeMod {

    /**
     * Called on the main server thread when the mod is enabled.
     * Register listeners/tasks/commands via {@code ctx} here.
     */
    void onEnable(VibeContext ctx) throws Exception;

    /**
     * Called on the main server thread just before teardown. Registrations made
     * through the context are cleaned up automatically after this returns; only
     * override to release resources the context does not know about.
     */
    default void onDisable(VibeContext ctx) {
    }
}
