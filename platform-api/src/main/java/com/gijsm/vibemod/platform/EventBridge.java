package com.gijsm.vibemod.platform;

/**
 * Registers a platform-native listener object on behalf of a mod, wrapped in
 * the host's watchdog and error-storm accounting, and revocable.
 *
 * <p>The listener's type is platform-defined — {@code org.bukkit.event.Listener}
 * on Paper (methods annotated {@code @EventHandler}); on the loaders the
 * curated {@code VibeContext} hooks are used instead and this bridge backs
 * them internally. Implementations must reject (throw
 * {@code IllegalArgumentException}) objects of the wrong type rather than
 * silently ignoring them.
 *
 * <p>Main thread only.
 */
public interface EventBridge {

    /**
     * Registers {@code nativeListener} for {@code modName}. The returned
     * registration unhooks every handler the object contributed.
     */
    Registration listen(Object nativeListener, String modName);
}
