package com.gijsm.vibemod.fabric.shim;

import com.gijsm.vibemod.platform.ModFailure;
import com.gijsm.vibemod.runtime.Watchdog;

/**
 * The render thread, as the server-side half of the host is allowed to see it
 * (V3 Phase 1 §B/§E).
 *
 * <p>Every type in this interface is either a JDK type or one of VibeMod's own,
 * and that is the whole design constraint. {@link EventFanout} and
 * {@code FabricEntrypointAdapter} both run on a dedicated server, where
 * {@code net.minecraft.client.Minecraft} does not exist; naming it in a
 * descriptor either of those classes carries would make them unloadable there.
 * So the three things they need from the client — "am I on the render thread",
 * "run this there", and "close a screen this mod defined" — arrive through an
 * interface that mentions no client type at all, and the implementation lives in
 * the client entrypoint's own class.
 *
 * <p>Null when there is no physical client, which is also how the server side
 * <em>asks</em>: {@code Shims.clientSeam() == null} is the dedicated-server
 * test, exactly as {@code clientContexts == null} already is in
 * {@code LoaderModHost}.
 *
 * <p>The client-only registrations (keybinds, HUD) deliberately do NOT live
 * here — see {@link ClientShims} and {@code ClientRegistrations}, which are
 * only ever loaded by bytecode that already needs client classes.
 */
public interface ClientSeam {

    /** Whether the calling thread is the render thread. */
    boolean onRenderThread();

    /**
     * Runs {@code body} on the render thread, now if already there and on the
     * client's task queue otherwise. Never blocks.
     */
    void runOnRenderThread(Runnable body);

    /** The render-thread watchdog: same budgets, same trip path as the server one (§8.4). */
    Watchdog renderWatchdog();

    /** Where a render-thread failure is journalled, so it counts towards the mod's error storm. */
    ModFailure failures();

    /**
     * Closes the open screen if its class was defined by {@code modLoader}
     * (V3 Phase 1 §E).
     *
     * <p>Called as part of a mod's teardown. A mod that opened its own
     * {@code Screen} subclass and is then disabled would otherwise leave a live
     * object from a dead class loader on the player's display, drawing and
     * handling clicks with the rest of its mod already gone.
     */
    void closeScreensFrom(ClassLoader modLoader);
}
