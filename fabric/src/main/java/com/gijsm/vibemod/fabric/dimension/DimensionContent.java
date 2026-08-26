package com.gijsm.vibemod.fabric.dimension;

import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;

import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.server.MinecraftServer;

/**
 * Runtime dimensions, end to end: the two subscriptions the feature needs, the
 * per-server objects behind them, and the boot restore (V4 Phase 6).
 *
 * <p>Three objects, and the split between them is the same one Phase 5 uses:
 *
 * <ul>
 *   <li>{@link DimensionSeam} creates and destroys levels.</li>
 *   <li>{@link DimensionRoster} knows which clients hold which dimension types,
 *       which is the one thing that decides whether a teleport is a doorway or a
 *       disconnect.</li>
 *   <li>{@link DimensionLedger} remembers which dimensions are meant to come
 *       back.</li>
 * </ul>
 *
 * <p>Per-server, like {@code DynamicContent} and unlike {@code RegistrySeam}:
 * every level, every roster snapshot and the ledger's file path are properties of
 * one world. The two {@code Event.register} calls are the exception, for the
 * reason §4.1 gives — a Fabric event cannot be unregistered, and one JVM can load
 * world A, quit, and load world B — so they live for the process and resolve the
 * live instance when they fire.
 *
 * <p><b>Why {@code BEFORE_CONFIGURE} and not {@code JOIN}.</b> The roster records
 * what a client's {@code dimension_type} registry will contain, and that is
 * settled when {@code SynchronizeRegistriesTask} is constructed at the head of
 * {@code startConfiguration}. {@code BEFORE_CONFIGURE} fires before any
 * configuration task is queued, so its snapshot can only ever be a subset of what
 * the client is actually sent. Snapshotting at {@code JOIN} instead would leave a
 * window — small, on one thread, but real — in which a type registered between
 * the sync and the join would be counted as delivered when it was not. Erring
 * small is the only direction that cannot cost somebody their connection.
 *
 * <p>It also fires on a <em>reconfiguration bounce</em>, which is exactly right:
 * Phase 5's {@code ReconfigureBouncer} exists to put new dynamic-registry content
 * in front of players who are already connected, and a bounced player's snapshot
 * is replaced with the larger set they are about to receive.
 *
 * <p>The listener registers in the default phase, deliberately. {@code ContentSync}
 * orders its own listener <em>before</em> the default phase because it has to
 * queue a task ahead of fabric-api's registry sync; this one queues nothing and
 * only reads, so it has no reason to compete for position.
 */
public final class DimensionContent {

    private static final Logger LOG = Logger.getLogger("VibeMod.Dimension");

    /** The live instance, or null between worlds. Read by the process-lived listeners. */
    private static volatile Supplier<DimensionContent> current = () -> null;
    private static volatile boolean listenersInstalled;

    private final Supplier<MinecraftServer> server;
    private final DimensionRoster roster = new DimensionRoster();
    private final DimensionSeam seam = new DimensionSeam(roster);

    public DimensionContent(Supplier<MinecraftServer> server) {
        this.server = server;
    }

    /**
     * Registers the two permanent subscriptions this feature needs, exactly once
     * per process.
     *
     * <p>Called from {@code VibeModFabric.onInitialize()} with a supplier of the
     * live instance, for the same reason {@code DynamicContent.installProcessListeners}
     * is: registering per-server would leave one dead listener per world ever
     * loaded.
     */
    public static void installProcessListeners(Supplier<DimensionContent> live) {
        current = live == null ? () -> null : live;
        if (listenersInstalled) {
            return;
        }
        listenersInstalled = true;
        ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.register((handler, server) -> {
            DimensionContent content = current.get();
            if (content != null && handler.getOwner() != null) {
                content.roster.noteConfiguring(server, handler.getOwner().id(),
                        handler.getOwner().name());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            DimensionContent content = current.get();
            if (content != null && handler.player != null) {
                content.roster.forget(handler.player.getUUID());
            }
        });
    }

    /**
     * The boot half: record the dimension-type floor, then re-open every
     * dimension the ledger says should exist.
     *
     * <p>Call it once at {@code SERVER_STARTED}, and after Phase 5's sweep — a
     * recorded dimension may name a dimension type that only the sweep puts back
     * in the registry, and {@link DimensionSeam#open} refuses a type that is not
     * there rather than half-creating a level around it.
     */
    public List<DimensionSeam.Result> serverStarted(MinecraftServer live) {
        roster.noteServerStarted(live);
        List<DimensionSeam.Result> restored = seam.restore(live);
        LOG.info("Runtime dimensions ready. " + LevelTickGuard.describeState()
                + " — a dimension cannot be opened until the tickChildren guard reads armed");
        return restored;
    }

    /**
     * One tick of the close drain, from the host's existing
     * {@code END_SERVER_TICK} subscription — the same one
     * {@code ReloadCoordinator.tick()}, {@code PaletteGuard.tick()} and
     * {@code DynamicContent.tick()} ride.
     */
    public void tick() {
        seam.tick(server.get());
    }

    /**
     * Closes every runtime dimension.
     *
     * <p>Wired to {@code SERVER_STOPPING}, not {@code SERVER_STOPPED}, and the
     * difference is load-bearing: {@code STOPPING} fires while the levels are
     * still open and the world directory lock is still held, which is what a
     * temporary dimension needs in order to close its chunk source and then delete
     * its own folder. By {@code STOPPED} vanilla has already saved and closed
     * every level and released the lock, and this would be deleting files out from
     * under a shut-down server.
     *
     * <p>Not optional and not merely tidy: a temporary dimension whose directory
     * is only deleted on close would otherwise leak one chunk folder per session,
     * and a player logged out inside one would log back in inside a level the next
     * boot does not create.
     */
    public void serverStopping(MinecraftServer live) {
        if (live != null) {
            seam.closeAll(live, "server stopping");
        }
        roster.clear();
    }

    /** The seam, for {@code /vibe info}, generated mods and the gates. */
    public DimensionSeam seam() {
        return seam;
    }

    /** The roster, for a "can this player see it" question outside a teleport. */
    public DimensionRoster roster() {
        return roster;
    }

    /**
     * One line for the gates, e.g. {@code "dimOpen=1 dimClosing=0 … levelTickGuard=armed
     * levelSnapshots=1200 dimTypeFloor=4 dimTypeTracked=1 dimRecorded=1"}.
     */
    public String describeState() {
        DimensionLedger ledger = seam.ledger();
        return seam.describeState()
                + " " + LevelTickGuard.describeState()
                + " " + roster.describeState()
                + " " + (ledger == null ? "dimRecorded=0" : ledger.describeState());
    }
}
