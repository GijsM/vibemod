package com.gijsm.vibemod.fabric.dynamic;

import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.server.MinecraftServer;

/**
 * Dynamic-registry content, end to end: sweep the datapack VibeMod already wrote,
 * apply what the live registries do not have, and schedule exactly one
 * reconfiguration bounce for the batch (V4 Phase 5).
 *
 * <p>Three objects and one order, and the order is the whole safety argument:
 *
 * <ol>
 *   <li>{@link DatapackSweep} reads every {@code vibemod-} datapack's
 *       {@code data} tree off disk — the files
 *       {@code LoaderModContent.materialize} has already staged and renamed.</li>
 *   <li>{@link DynamicSeam} decodes each one against the <em>live</em>
 *       {@code registryAccess()}, appends it to the live {@code MappedRegistry},
 *       and <b>verifies it</b> — holder bound, raw id assigned, and a full
 *       re-encode through the same codec {@code RegistrySynchronization.packRegistry}
 *       will run during configuration.</li>
 *   <li>Only then does {@link ReconfigureBouncer} arm. There is no rollback past
 *       {@code switchToConfig()}, so everything that can fail has to have already
 *       failed by the time a player's screen goes grey.</li>
 * </ol>
 *
 * <p>The sweep is idempotent by construction, so this class is safe to call on
 * every content change, and it is a no-op after a restart: by then vanilla has
 * loaded the same files itself, in its own deterministic order, and every id is
 * already present.
 *
 * <p>Per-server, like {@code ReloadCoordinator} and unlike {@code RegistrySeam} —
 * everything it holds is a property of one world. The one exception is the
 * {@code JOIN} subscription, which is an {@code Event.register} that cannot be
 * undone and therefore lives for the process and resolves the live instance when
 * it fires, exactly as every other permanent subscription in this codebase does
 * (§4.1).
 */
public final class DynamicContent {

    private static final Logger LOG = Logger.getLogger("VibeMod.Dynamic");

    /** The live instance, or null between worlds. Read by the process-lived JOIN listener. */
    private static volatile Supplier<DynamicContent> current = () -> null;
    private static volatile boolean listenersInstalled;

    private final Supplier<MinecraftServer> server;
    private final DynamicSeam seam = new DynamicSeam();
    private final DatapackSweep sweep;
    private final ProxyGate proxies;
    private final ReconfigureBouncer bouncer;

    public DynamicContent(Supplier<MinecraftServer> server, ProxyGate proxies,
                          ReconfigureBouncer.Announcer announcer) {
        this.server = server;
        this.proxies = proxies;
        this.sweep = new DatapackSweep(seam);
        this.bouncer = new ReconfigureBouncer(server, proxies, announcer);
    }

    /**
     * Registers the one permanent subscription this feature needs, exactly once
     * per process.
     *
     * <p>Called from {@code VibeModFabric.onInitialize()} with a supplier of the
     * live instance, for the reason §4.1 gives: a Fabric {@code Event} cannot be
     * unregistered, and a client can load world A, quit, and load world B in one
     * JVM. Registering per-server would leave one dead listener per world ever
     * loaded.
     */
    public static void installProcessListeners(Supplier<DynamicContent> live) {
        current = live == null ? () -> null : live;
        if (listenersInstalled) {
            return;
        }
        listenersInstalled = true;
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            DynamicContent content = current.get();
            if (content != null && handler.player != null) {
                content.bouncer.noteJoined(handler.player);
            }
        });
    }

    /**
     * The question {@code VibeModFabric}'s DISCONNECT listener has to ask before
     * it forgets a player.
     *
     * <p>Static and instance-free on purpose: the listener that calls it is
     * process-lived and fires between worlds too. See {@link Reconfiguring} for
     * what was verified about when DISCONNECT actually fires — on 26.2 with
     * fabric-api 0.158.0 it does <em>not</em> fire on a bounce, and this is the
     * belt rather than the braces.
     */
    public static boolean isReconfiguring(java.util.UUID playerId) {
        DynamicContent content = current.get();
        long tick = content == null ? 0L : content.bouncer.currentTick();
        return Reconfiguring.isReconfiguring(playerId, tick);
    }

    /** Clears every in-flight mark. Called when a server stops. */
    public static void serverStopped() {
        Reconfiguring.clearAll();
    }

    /**
     * Applies everything the materialised datapacks declare and, if any of it is
     * new, arms a bounce.
     *
     * <p>Server thread only. Call it after content changes — from the same place
     * {@code ReloadCoordinator.markServerDirty} is called, and once at
     * {@code SERVER_STARTED} so that a world whose datapacks were edited while it
     * was down catches up.
     *
     * @return every entry the sweep touched, refusals included
     */
    public List<DynamicSeam.Result> apply(String why) {
        MinecraftServer live = server.get();
        if (live == null) {
            return List.of();
        }
        List<DynamicSeam.Result> results = sweep.sweep(live);
        int applied = 0;
        for (DynamicSeam.Result result : results) {
            if (result.isNewContent()) {
                applied++;
            }
        }
        if (applied > 0) {
            LOG.info("Applied " + applied + " dynamic registry entr" + (applied == 1 ? "y" : "ies")
                    + " (" + why + "); they are in the live registries now and reach connected"
                    + " players at the next reconfiguration or their next join");
            bouncer.markDirty(why, applied);
        }
        return results;
    }

    /**
     * One tick of the bounce debounce, from the host's existing
     * {@code END_SERVER_TICK} subscription — the same one
     * {@code ReloadCoordinator.tick()} and {@code PaletteGuard.tick()} ride.
     */
    public void tick() {
        bouncer.tick();
    }

    /** The seam, for {@code /vibe info} and the gates. */
    public DynamicSeam seam() {
        return seam;
    }

    /** The bouncer, for wiring a {@link ReconfigureBouncer.BounceRestore}. */
    public ReconfigureBouncer bouncer() {
        return bouncer;
    }

    /** The proxy gate, so a brand or handshake observer can report into it. */
    public ProxyGate proxies() {
        return proxies;
    }

    /**
     * One line for the gates, e.g.
     * {@code "dynamicApplied=1 dynamicInactive=0 dynamicRefused=0 bounces=1 …
     * proxyGate=open proxySignals=0"}.
     */
    public String describeState() {
        return seam.describeState() + " " + bouncer.describeState() + " " + proxies.describeState();
    }
}
