package com.gijsm.vibemod.fabric.client;

import java.util.logging.Logger;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import com.gijsm.vibemod.fabric.VibeModFabric;
import com.gijsm.vibemod.fabric.shim.ClientShims;
import com.gijsm.vibemod.fabric.shim.Shims;
import com.gijsm.vibemod.loader.client.DeferredTickScheduler;
import com.gijsm.vibemod.loader.client.LoaderClientContext;
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
    /** The process-lived runtime resource pack (V3 Phase 2 §D); built once, here. */
    private static volatile FabricClientPacks packs;

    /** The live client bridge, for the acceptance gate's state assertions. */
    public static FabricClientEventBridge bridge() {
        return bridge;
    }

    /** The runtime resource pack, for the acceptance gate's state assertions. */
    public static FabricClientPacks packs() {
        return packs;
    }

    @Override
    public void onInitializeClient() {
        // The render watchdog's one scheduler use is hopping a trip onto the
        // server thread — and `ModLifecycle.disable` asserts it is on that
        // thread, so the hop is not optional. A deferred scheduler resolves the
        // live one at call time, because a trip cannot happen before a server
        // exists (it takes a loaded mod to trip a watchdog).
        Watchdog renderWatchdog = new Watchdog(new DeferredTickScheduler(() -> {
            VibeModFabric.Services live = VibeModFabric.services();
            return live == null ? null : live.scheduler();
        }), "render",
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

        // V3 Phase 1 (§B, §C, §D, §E). Both installs happen HERE, in the client
        // entrypoint, for the same reason the bridge itself is built here: they
        // are process-lived, they must exist before the first mod loads, and
        // their absence on a dedicated server is how the server side knows there
        // is no client. `Shims` holds the server-safe half (thread checks,
        // watchdog, screen close) and `ClientShims` the half whose descriptors
        // name client-only classes.
        Shims.installClient(created);
        ClientShims.install(created);

        // V3 Phase 2 §D. Built here for the same "process-lived, before the first
        // mod" reason — and RESET here, which is the stale guard: at client init
        // no world is loaded, so no mod is live, so the pack must be empty.
        // Anything on disk at this moment is residue from a crash.
        FabricClientPacks createdPacks =
                new FabricClientPacks(FabricLoader.getInstance().getGameDir().resolve("vibemod"));
        try {
            createdPacks.resetOnClientInit();
            // Best-effort: Minecraft's own repository field may not be assigned
            // yet at this point in its constructor. The coordinator joins again
            // before every reload, so "not yet" is never "not at all".
            createdPacks.joinRepository();
        } catch (Throwable t) {
            LOG.warning("Could not prepare VibeMod's runtime resource pack: " + t);
        }
        packs = createdPacks;

        VibeModFabric.setClientHooks(new VibeModFabric.ClientHooks(
                created,
                handle -> new LoaderClientContext(created, handle),
                renderWatchdog,
                createdPacks,
                createdPacks));
        LOG.info("VibeMod client hooks installed (" + created.describeState()
                + " " + createdPacks.describeState() + ")");
    }
}
