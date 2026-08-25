package com.gijsm.vibemod.fabric.client;

import java.util.logging.Logger;

import net.fabricmc.api.ClientModInitializer;

import com.gijsm.vibemod.fabric.VibeModFabric;
import com.gijsm.vibemod.platform.ModFailure;
import com.gijsm.vibemod.runtime.Watchdog;

/**
 * VibeMod's {@code client} entrypoint: the half of the mod that only exists on
 * a physical client (ARCHITECTURE-V2 §8).
 *
 * <p>Its whole job is timing. HUD elements, key mappings and the client command
 * root can only be registered during client startup — long before a world, a
 * server or a single generated mod exists — so the bridge is built and its
 * permanent hooks installed here, once, and handed to the server half through
 * {@link VibeModFabric#setClientHooks}. When a world later starts, the host
 * finds the hooks already in place and {@code ctx.client(...)} works from the
 * first mod loaded.
 *
 * <p>The render watchdog is created here too, with placeholder budgets: the real
 * ones live in the config, which is read when a server starts. The host pushes
 * them in at that point (and again on every {@code /vibe reload}), so the
 * render thread and the server thread are always measured against the same
 * numbers — §8.4's requirement, and the reason both watchdogs share a trip path.
 */
public final class VibeModFabricClient implements ClientModInitializer {

    private static final Logger LOG = Logger.getLogger("VibeMod");

    /** Until a server exists there is no lifecycle to report to; failures are logged. */
    private static final ModFailure PRE_SERVER_SINK = (mod, cause, where) ->
            LOG.warning("Mod " + mod + " failed in " + where + " before a server existed: " + cause);

    /** Conservative defaults; the host overwrites them from the config at server start. */
    private static final long DEFAULT_SINGLE_MS = 250;
    private static final long DEFAULT_BUDGET_MS = 500;

    private static volatile FabricClientEventBridge bridge;

    /** The live client bridge, for the acceptance gate's state assertions. */
    public static FabricClientEventBridge bridge() {
        return bridge;
    }

    @Override
    public void onInitializeClient() {
        // The render watchdog's one scheduler use is hopping a trip onto the
        // server thread — and `ModLifecycle.disable` asserts it is on that
        // thread, so the hop is not optional. A deferred scheduler resolves the
        // live one at call time, because a trip cannot happen before a server
        // exists (it takes a loaded mod to trip a watchdog).
        Watchdog renderWatchdog = new Watchdog(new DeferredTickScheduler(), "render",
                DEFAULT_SINGLE_MS, DEFAULT_BUDGET_MS);

        // Failures route to the live lifecycle when there is one, so a HUD
        // renderer that throws counts towards its mod's error storm exactly like
        // a listener that throws (§8.1). Read per call: the lifecycle is rebuilt
        // for every world.
        ModFailure sink = (mod, cause, where) -> {
            VibeModFabric.Services live = VibeModFabric.services();
            if (live == null) {
                PRE_SERVER_SINK.markFailure(mod, cause, where);
                return;
            }
            live.lifecycle().markFailure(mod, cause, where);
        };

        FabricClientEventBridge created = new FabricClientEventBridge(sink, renderWatchdog);
        created.install();
        bridge = created;

        VibeModFabric.setClientHooks(new VibeModFabric.ClientHooks(
                created,
                handle -> new FabricClientContext(created, handle),
                renderWatchdog));
        LOG.info("VibeMod client hooks installed (" + created.describeState() + ")");
    }
}
