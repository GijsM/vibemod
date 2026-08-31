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
     * Makes sure a runtime-registered entity type has SOME renderer, right now
     * (V3 Phase 3 §B).
     *
     * <p>The client gate found why this has to exist. {@code EntityRenderDispatcher}
     * bakes {@code EntityType -> EntityRenderer} once per resource reload; a
     * type registered afterwards is simply absent from that map, and the first
     * frame in which one is visible dies on the render thread with
     * {@code NullPointerException: Cannot invoke "EntityRenderer.shouldRender(…)"
     * because "renderer" is null}. Registering the mod's real renderer fixes it
     * — but the mod registers that from {@code onInitializeClient}, which is
     * DEFERRED to the render thread, while the entity type is registered
     * synchronously in {@code onInitialize}. Between the two there is a window
     * in which a mod (or a command) can spawn one and take the client down.
     *
     * <p>So the entity type's registration installs vanilla's own
     * {@code NoopRenderer} immediately, and the mod's real renderer replaces it
     * a frame later. An invisible mob is a bug report; a crashed client is a
     * lost world.
     *
     * <p>{@code EntityType} is {@code net.minecraft.world.entity}, which exists
     * on a dedicated server, so this signature keeps the no-client-types rule.
     */
    void ensureEntityRenderer(net.minecraft.world.entity.EntityType<?> type);

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

    /**
     * Widens the client level's block-state palette strategy to {@code bits}
     * (V4 Phase 1).
     *
     * <p>Ints only, and that is the whole reason this lives here rather than
     * anywhere more natural. The type it actually operates on is
     * {@code net.minecraft.world.level.chunk.Strategy}, reached through
     * {@code ClientLevel} — and {@code ClientSeam} is held by
     * {@code EventFanout} and {@code FabricEntrypointAdapter}, which a dedicated
     * server loads. A client type in this descriptor would be a
     * {@code NoClassDefFoundError} on somebody's server.
     *
     * <p><b>Why this is not a {@code runOnRenderThread} hop.</b> The caller is
     * the server thread, mid-registration, and the ordering it is enforcing is
     * the correctness argument for the whole crossing: both sides widen
     * <em>before</em> any id wide enough to need the extra bit exists. An async
     * hop would put the client's widen an unbounded number of frames later and
     * reopen exactly the window this ordering closes — a chunk packet encoded at
     * 16 bits reaching a client still decoding at 15, which is a length mismatch
     * and a disconnect, in singleplayer included
     * ({@code Connection.configureInMemoryPipeline} really does serialise). So
     * this is a direct field write, and it is safe to be one because it writes a
     * single {@code int} that only ever grows, and every reader of it recomputes
     * from scratch on the next container operation.
     *
     * @return the width the client level is on afterwards, or -1 if there is no
     *         level — which is the normal answer at the main menu, and the
     *         reason this returns a width rather than nothing
     */
    int widenBlockStatePalette(int bits);
}
