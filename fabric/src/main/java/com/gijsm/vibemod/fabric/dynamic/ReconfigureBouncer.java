package com.gijsm.vibemod.fabric.dynamic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import com.gijsm.vibemod.fabric.mixin.ServerGamePacketListenerAccessor;
import com.gijsm.vibemod.loader.content.ReloadCoordinator;

/**
 * The twenty seconds a player pays so that new dynamic content is real for them,
 * spent as rarely as the design can manage (V4 Phase 5).
 *
 * <h2>The mechanism is one line; the policy is the work</h2>
 *
 * <p>{@code ServerPlayNetworking.reconfigure(ServerPlayer)} is present in this
 * build's {@code fabric-networking-api-v1} 6.3.3 (verified by {@code javap}), and
 * it calls {@code ServerGamePacketListenerImpl.switchToConfig()}. Re-running
 * configuration re-runs {@code SynchronizeRegistriesTask}, which reads the
 * server's {@code LayeredRegistryAccess} <b>at construction</b> — so registration
 * has to be complete and verified before a bounce is issued, never during one.
 * {@link DynamicSeam} does the verifying; this class does the waiting.
 *
 * <h2>What a bounce actually costs, disassembled</h2>
 *
 * <p>More than the brief listed, and every item here was read rather than
 * assumed. {@code switchToConfig()} calls {@code removePlayerFromWorld()}, which:
 *
 * <ul>
 *   <li><b>broadcasts {@code multiplayer.player.left} in yellow, to everyone</b> —
 *       and {@code PrepareSpawnTask} → {@code placeNewPlayer} broadcasts the
 *       matching join. A bounce is visible to every player on the server as a
 *       fake leave/join pair, not only to the one being bounced;</li>
 *   <li>calls {@code ServerPlayer.disconnect()}, which closes the open container —
 *       the cursor stack is lost, which is why {@link SkipReason#OPEN_CONTAINER}
 *       exists;</li>
 *   <li>calls {@code PlayerList.remove(player)}, which saves the player and
 *       <b>destroys the {@code ServerPlayer} object</b>. {@code PrepareSpawnTask}
 *       rebuilds it from {@code PlayerList.loadPlayerData}. Anything holding a
 *       {@code ServerPlayer} reference across a bounce is holding a corpse; state
 *       keyed by {@code UUID} (which is all of VibeMod's) is fine.</li>
 * </ul>
 *
 * <p>On top of that, the vanilla client shows a "Reconfiguring…" screen whose
 * Disconnect button is greyed out for twenty seconds, the player leaves the tab
 * list, and chat history is cleared.
 *
 * <h2>What vanilla puts back, which is more than the brief expected</h2>
 *
 * <p>{@code PlayerList.placeNewPlayer} was disassembled to find out rather than
 * guessed at, and it already calls both
 * {@code updateEntireScoreboard(level.getScoreboard(), player)} — objectives,
 * teams <em>and</em> display slots — and
 * {@code server.getCustomBossEvents().onPlayerConnect(player)}. So scoreboards and
 * {@code /bossbar} boss bars need nothing from this class.
 *
 * <p>What vanilla does <em>not</em> put back is anything it does not own: a
 * {@code ServerBossEvent} a mod constructed itself is not in
 * {@code CustomBossEvents}, nothing removes the old {@code ServerPlayer} from its
 * player set, and the new one is never added — so the bar goes dead and the event
 * leaks a stale reference. Same for a VibeMod dialog that was open when the
 * bounce landed: the client's screen is gone. Both are {@link BounceRestore}'s
 * job, which is a hook rather than an implementation because the things that need
 * re-sending belong to whoever created them.
 *
 * <h2>The batching, and why it is not the coordinator's forty ticks</h2>
 *
 * <p>{@code ReloadCoordinator.DEBOUNCE_TICKS} is 40, and that is right for what it
 * debounces: a datapack reload costs the server thread a second and costs a
 * player a progress bar. A bounce costs a player twenty seconds of a greyed-out
 * button and their chat history. So this debounce is its own, an order of
 * magnitude longer ({@value #DEBOUNCE_TICKS} ticks), announced while it counts
 * down, and rate-limited on top: at most one bounce per
 * {@value #MIN_INTERVAL_TICKS} ticks whatever else happens, and at most
 * {@value #MAX_BOUNCES_PER_SESSION} in a session.
 *
 * <h2>Failure is a one-way door, so the exit is before it</h2>
 *
 * <p>There is no rollback after {@code switchToConfig()} — the play listener is
 * gone and the outbound protocol has already been swapped. So a player who does
 * not come back within {@link Reconfiguring#WINDOW_TICKS} is counted lost, and if
 * more than {@value #FAILURE_FRACTION_PERCENT}% of a bounce's players are lost,
 * bouncing is disabled for the rest of the session and every later change goes
 * out on next join instead. That fallback is not a last resort bolted on: it is
 * the same code path the proxy gate uses, which means it is exercised by every
 * proxy deployment rather than only by the disaster it exists for.
 */
public final class ReconfigureBouncer {

    private static final Logger LOG = Logger.getLogger("VibeMod.Dynamic");

    /**
     * Thirty seconds. Fifteen times {@link ReloadCoordinator#DEBOUNCE_TICKS}, and
     * the multiple is the argument: a bounce is not fifteen times worse than a
     * datapack reload for the server, but it is for the player, and the player is
     * who is being batched for.
     */
    public static final int DEBOUNCE_TICKS = 600;

    /** Five minutes. The hard floor between two bounces, whatever changes land. */
    public static final int MIN_INTERVAL_TICKS = 6000;

    /** After this many, something is wrong with the caller, not with the players. */
    public static final int MAX_BOUNCES_PER_SESSION = 20;

    /** Lose more than this share of a bounce's players and stop bouncing. */
    public static final int FAILURE_FRACTION_PERCENT = 25;

    /**
     * Ticks remaining at which the countdown speaks, longest first.
     *
     * <p>Two lines, not five. The point of a countdown is that somebody standing
     * at a furnace has time to step away from it; the point is not to fill chat.
     */
    private static final int[] ANNOUNCE_AT = {200, 60};

    /** Why one player was not bounced. Each one is a thing a bounce would break. */
    public enum SkipReason {
        /** Their cursor stack dies: {@code ServerPlayer.disconnect()} closes the menu. */
        OPEN_CONTAINER("has a container open"),
        /** The vehicle stays, the rider is rebuilt from disk, and the two disagree. */
        RIDING("is riding something"),
        /** {@code portalProcess} is a per-entity state machine that does not survive. */
        PORTAL("is standing in a portal"),
        /** {@code isChangingDimension()} — a dimension change is already tearing down. */
        CHANGING_DIMENSION("is changing dimension"),
        /** A teleport the client has not confirmed. See {@code ServerGamePacketListenerAccessor}. */
        TELEPORT("has an unconfirmed teleport in flight"),
        /** The connection is already going away; a bounce would race a real disconnect. */
        LEAVING("is already disconnecting"),
        /** A bounce for this player is still in flight from a previous round. */
        ALREADY_BOUNCING("is already reconfiguring");

        private final String text;

        SkipReason(String text) {
            this.text = text;
        }

        /** The phrase that goes in the chat line, so the player knows what to change. */
        public String text() {
            return text;
        }
    }

    /** Re-sends what a bounce tore down and vanilla does not put back. */
    @FunctionalInterface
    public interface BounceRestore {

        /**
         * Called on the server thread when a bounced player has re-joined, with
         * the <b>new</b> {@code ServerPlayer} — the old one no longer exists.
         */
        void restore(ServerPlayer player);
    }

    /** How this class talks to players. Deliberately not a UI dependency. */
    public interface Announcer {

        /** To everyone on the server. */
        void all(String message);

        /** To one player, who may have gone; a no-op then is correct. */
        void player(UUID playerId, String message);
    }

    private final Supplier<MinecraftServer> server;
    private final ProxyGate proxies;
    private final Announcer announcer;
    private final List<BounceRestore> restores = new CopyOnWriteArrayList<>();

    /** Players whose synced registries are behind, waiting for their next join. */
    private final Set<UUID> stale = ConcurrentHashMap.newKeySet();
    /** Reasons a given player was skipped last round, for the chat line on the next. */
    private final Map<UUID, SkipReason> lastSkip = new ConcurrentHashMap<>();

    private long tick;
    private boolean dirty;
    private int timer;
    private String reason = "-";
    private int pendingEntries;
    private long lastBounceTick = Long.MIN_VALUE;
    private int bounces;
    private int bounced;
    private int returned;
    private int lost;
    private int skipped;
    private int nextJoinDeliveries;
    private final int[] announcedAt = new int[ANNOUNCE_AT.length];
    private volatile String disabledBecause;

    public ReconfigureBouncer(Supplier<MinecraftServer> server, ProxyGate proxies, Announcer announcer) {
        this.server = server;
        this.proxies = proxies;
        this.announcer = announcer;
    }

    /** Adds something to re-send after a bounce. Never removed; this object is per-server. */
    public void addRestore(BounceRestore restore) {
        restores.add(restore);
    }

    // ------------------------------------------------------------------ arming

    /**
     * Arms (or re-arms) a bounce because {@code newEntries} verified entries were
     * added.
     *
     * <p>Call this only after {@link DynamicSeam} has said {@code APPLIED} for
     * every one of them. Arming on unverified content is the one ordering mistake
     * that cannot be walked back: the bounce reads the registries at
     * {@code SynchronizeRegistriesTask}'s construction, and a bad entry throws
     * there, mid-configuration, for every player at once.
     */
    public synchronized void markDirty(String why, int newEntries) {
        if (newEntries <= 0) {
            return;
        }
        pendingEntries += newEntries;
        reason = why;
        dirty = true;
        timer = DEBOUNCE_TICKS;
        java.util.Arrays.fill(announcedAt, 0);
        if (blocked()) {
            // Nothing is scheduled, so say so now rather than letting a player
            // wait for a countdown that will never start.
            deliverOnNextJoin("blocked");
            return;
        }
        announcer.all("VibeMod: " + pendingEntries + " new registry entr"
                + (pendingEntries == 1 ? "y" : "ies") + " (" + why + "). Everyone will be briefly"
                + " reconfigured in " + (DEBOUNCE_TICKS / 20) + "s — you will see a \"Reconfiguring…\""
                + " screen for about 20 seconds. Close any container first.");
    }

    /**
     * One tick, from the host's existing {@code END_SERVER_TICK} subscription —
     * the same subscription {@link ReloadCoordinator#tick()} and the palette
     * guard's straggler watch already ride, for the same reason.
     */
    public void tick() {
        tick++;
        sweepLostBounces();
        boolean fire = false;
        synchronized (this) {
            if (dirty) {
                timer--;
                announceCountdown();
                if (timer <= 0) {
                    fire = true;
                }
            }
        }
        if (fire) {
            flush();
        }
    }

    private synchronized void announceCountdown() {
        for (int i = 0; i < ANNOUNCE_AT.length; i++) {
            if (announcedAt[i] == 0 && timer == ANNOUNCE_AT[i]) {
                announcedAt[i] = 1;
                announcer.all("VibeMod: reconfiguring in " + (ANNOUNCE_AT[i] / 20) + "s.");
            }
        }
    }

    // ------------------------------------------------------------------ the bounce

    private void flush() {
        MinecraftServer live = server.get();
        synchronized (this) {
            dirty = false;
            timer = 0;
        }
        if (live == null) {
            return;
        }
        if (blocked()) {
            deliverOnNextJoin("blocked at flush");
            return;
        }
        long since = tick - lastBounceTick;
        if (lastBounceTick != Long.MIN_VALUE && since < MIN_INTERVAL_TICKS) {
            int wait = (int) (MIN_INTERVAL_TICKS - since);
            synchronized (this) {
                dirty = true;
                timer = wait;
                java.util.Arrays.fill(announcedAt, 0);
            }
            LOG.info("Holding a reconfiguration bounce for another " + (wait / 20) + "s: the rate limit"
                    + " is one bounce per " + (MIN_INTERVAL_TICKS / 20) + "s");
            return;
        }
        if (bounces >= MAX_BOUNCES_PER_SESSION) {
            disable("the session's limit of " + MAX_BOUNCES_PER_SESSION + " bounces was reached");
            deliverOnNextJoin("bounce limit reached");
            return;
        }

        List<ServerPlayer> going = new ArrayList<>();
        Map<UUID, SkipReason> skipping = new LinkedHashMap<>();
        for (ServerPlayer player : live.getPlayerList().getPlayers()) {
            SkipReason why = skipReason(player);
            if (why == null) {
                going.add(player);
            } else {
                skipping.put(player.getUUID(), why);
            }
        }

        int entries;
        synchronized (this) {
            entries = pendingEntries;
            pendingEntries = 0;
        }

        for (Map.Entry<UUID, SkipReason> entry : skipping.entrySet()) {
            skipped++;
            stale.add(entry.getKey());
            lastSkip.put(entry.getKey(), entry.getValue());
            announcer.player(entry.getKey(), "VibeMod: not reconfiguring you — you "
                    + entry.getValue().text() + ". The " + entries + " new registry entr"
                    + (entries == 1 ? "y" : "ies") + " will apply the next time you join.");
        }

        if (going.isEmpty()) {
            LOG.info("Nothing to bounce for \"" + reason + "\": " + skipping.size()
                    + " player(s) skipped, " + entries + " entr(y/ies) will apply on their next join");
            return;
        }

        bounces++;
        lastBounceTick = tick;
        LOG.info("Reconfiguring " + going.size() + " player(s) for \"" + reason + "\" (" + entries
                + " new registry entr" + (entries == 1 ? "y" : "ies") + "); " + skipping.size()
                + " skipped. A bounce also broadcasts a fake leave/join pair, because"
                + " switchToConfig() -> removePlayerFromWorld() broadcasts multiplayer.player.left");
        for (ServerPlayer player : going) {
            UUID id = player.getUUID();
            try {
                Reconfiguring.mark(id, tick);
                stale.remove(id);
                lastSkip.remove(id);
                bounced++;
                ServerPlayNetworking.reconfigure(player);
            } catch (Throwable t) {
                // There is no rollback past switchToConfig(), and the throw could
                // have come from either side of it. Treat it as lost rather than
                // as fine: the player is either back in a moment (and noteJoined
                // clears the mark) or they are not, and the sweep counts them.
                LOG.log(Level.SEVERE, "Could not reconfigure " + player.getName().getString()
                        + "; there is no way back from a half-issued bounce, so this player is counted"
                        + " lost and their content will apply on their next join", t);
            }
        }
    }

    /**
     * Whether one player can afford a bounce right now.
     *
     * <p>Each of these is something {@code removePlayerFromWorld()} destroys, and
     * they are ordered cheapest-check first.
     *
     * @return the reason to skip, or null when the player may be bounced
     */
    public SkipReason skipReason(ServerPlayer player) {
        if (player.hasDisconnected() || player.connection == null
                || !player.connection.isAcceptingMessages()) {
            return SkipReason.LEAVING;
        }
        if (Reconfiguring.isReconfiguring(player.getUUID(), tick)) {
            return SkipReason.ALREADY_BOUNCING;
        }
        if (player.containerMenu != player.inventoryMenu) {
            return SkipReason.OPEN_CONTAINER;
        }
        if (player.isPassenger() || player.isVehicle()) {
            return SkipReason.RIDING;
        }
        if (player.portalProcess != null) {
            return SkipReason.PORTAL;
        }
        if (player.isChangingDimension()) {
            return SkipReason.CHANGING_DIMENSION;
        }
        if (((ServerGamePacketListenerAccessor) player.connection)
                .getAwaitingPositionFromClient() != null) {
            return SkipReason.TELEPORT;
        }
        return null;
    }

    // ------------------------------------------------------------------ next-join delivery

    /**
     * The other delivery path, and a first-class one.
     *
     * <p>It needs no machinery to <em>work</em> — a player who connects after the
     * registration goes through configuration fresh, and
     * {@code SynchronizeRegistriesTask} reads the server's registries when it is
     * constructed, so they get the new entries for free. What it needs is to be
     * <em>said</em>: a player who is told nothing concludes the feature is broken.
     * So this marks everyone stale, tells them once, and puts the reason and both
     * upstream issue numbers in the operator log.
     */
    private void deliverOnNextJoin(String when) {
        MinecraftServer live = server.get();
        int entries;
        synchronized (this) {
            entries = pendingEntries;
            pendingEntries = 0;
            dirty = false;
            timer = 0;
        }
        nextJoinDeliveries++;
        String why = disabledBecause != null ? disabledBecause : proxies.describeBlock();
        LOG.info("Not reconfiguring anybody (" + when + "): " + why);
        if (live == null) {
            return;
        }
        for (ServerPlayer player : live.getPlayerList().getPlayers()) {
            stale.add(player.getUUID());
            announcer.player(player.getUUID(), "VibeMod: " + entries + " new registry entr"
                    + (entries == 1 ? "y is" : "ies are") + " installed but cannot be sent to you"
                    + " while you are connected. Rejoin the server to see "
                    + (entries == 1 ? "it" : "them") + ".");
        }
    }

    /**
     * A player joined — either coming back from a bounce, or for the first time.
     *
     * <p>Wire this to {@code ServerPlayConnectionEvents.JOIN}. Both cases clear
     * the player's stale mark, because a join is a fresh configuration phase
     * either way; only the first runs the restores.
     */
    public void noteJoined(ServerPlayer player) {
        UUID id = player.getUUID();
        stale.remove(id);
        lastSkip.remove(id);
        boolean cameBack = Reconfiguring.isReconfiguring(id, tick);
        Reconfiguring.clear(id);
        if (!cameBack) {
            return;
        }
        returned++;
        for (BounceRestore restore : restores) {
            try {
                restore.restore(player);
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "A post-bounce restore threw for "
                        + player.getName().getString(), t);
            }
        }
        announcer.player(id, "VibeMod: you are back, and the new content is live.");
    }

    /**
     * Counts a bounce nobody came back from, and pulls the handbrake if too many
     * of one round did.
     */
    private void sweepLostBounces() {
        List<UUID> gone = Reconfiguring.expired(tick);
        if (gone.isEmpty()) {
            return;
        }
        for (UUID id : gone) {
            Reconfiguring.clear(id);
            stale.add(id);
            lost++;
            LOG.warning("Player " + id + " did not come back from a reconfiguration bounce within "
                    + (Reconfiguring.WINDOW_TICKS / 20) + "s. There is no rollback past"
                    + " switchToConfig(), so this is counted rather than repaired");
        }
        if (bounced > 0 && lost * 100 / bounced > FAILURE_FRACTION_PERCENT) {
            disable(lost + " of " + bounced + " bounced players did not come back, which is over the "
                    + FAILURE_FRACTION_PERCENT + "% failure threshold");
        }
    }

    private void disable(String why) {
        if (disabledBecause != null) {
            return;
        }
        disabledBecause = why;
        LOG.severe("Disabling mid-play reconfiguration for the rest of this session: " + why
                + ". New dynamic content applies on a player's next join from here on. If this server"
                + " is behind a proxy, that is the known cause: " + ProxyGate.ISSUES);
        announcer.all("VibeMod: reconfiguration is switched off for this session (" + why
                + "). New content now applies when you rejoin.");
    }

    private boolean blocked() {
        return disabledBecause != null || proxies.blocked();
    }

    /**
     * The server tick this bouncer has counted, which is what
     * {@link Reconfiguring}'s window is measured in.
     *
     * <p>Its own counter rather than {@code server.getTickCount()}, because the
     * marks it dates are only ever compared against other marks it made, and a
     * counter that starts at zero with this object cannot be confused by a world
     * swap in the same JVM.
     */
    public long currentTick() {
        return tick;
    }

    /** True when this server will never bounce anybody again this session. */
    public boolean isDisabled() {
        return disabledBecause != null;
    }

    /** Players whose synced registries are behind the server's. */
    public Set<UUID> stalePlayers() {
        return Set.copyOf(stale);
    }

    /** Why a given player was skipped last round, or null. */
    public SkipReason lastSkipReason(UUID playerId) {
        return lastSkip.get(playerId);
    }

    /**
     * Counts for the gates, e.g.
     * {@code "bounces=1 bounced=2 bouncesReturned=2 bouncesLost=0 bouncesSkipped=1
     * nextJoinDeliveries=0 bouncePending=0 bounceDisabled=false"}.
     *
     * <p>{@code name=value} throughout with stable names, matching the contract
     * {@code RegistrySeam} and {@code PaletteGuard} already keep with the gates.
     */
    public synchronized String describeState() {
        return "bounces=" + bounces
                + " bounced=" + bounced
                + " bouncesReturned=" + returned
                + " bouncesLost=" + lost
                + " bouncesSkipped=" + skipped
                + " nextJoinDeliveries=" + nextJoinDeliveries
                + " bouncePending=" + (dirty ? timer : 0)
                + " bounceStale=" + stale.size()
                + " bounceDisabled=" + (disabledBecause != null);
    }
}
