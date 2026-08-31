package com.gijsm.vibemod.fabric.dynamic;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Is this connection reconfiguring, or did the player actually leave?" — the
 * one question a bounce makes ambiguous, answered in one place (V4 Phase 5).
 *
 * <h2>What was checked, because the brief predicted the opposite</h2>
 *
 * <p>The phase brief expected a live bug: {@code ServerPlayConnectionEvents.DISCONNECT}
 * fires on a reconfiguration bounce, so {@code VibeModFabric}'s listener would
 * {@code forget(...)} a player who never left. <b>Disassembling
 * {@code fabric-networking-api-v1} 6.3.3 says that does not happen on this
 * version</b>, and the split is precise:
 *
 * <pre>
 * ConnectionMixin:
 *   &#64;Inject(method = "channelInactive")      -&gt; addon.handleDisconnect()  -&gt; DISCONNECT fires
 *   &#64;Inject(method = "handleDisconnection")  -&gt; addon.handleDisconnect()  -&gt; DISCONNECT fires
 *   &#64;Inject(method = "setListener"…)         -&gt; addon.endSession()        -&gt; DISCONNECT does NOT fire
 * </pre>
 *
 * <p>A bounce is the third row. {@code ServerPlayNetworkAddon.reconfigure()} calls
 * {@code ServerGamePacketListenerImpl.switchToConfig()}, which swaps the listener
 * and the outbound protocol; the channel stays open, so only {@code endSession()}
 * runs and the DISCONNECT event is never invoked. VibeMod's per-player state
 * survives a bounce untouched on 26.2 / fabric-api 0.158.0.
 *
 * <h2>Why this class exists anyway</h2>
 *
 * <p>Two reasons, and neither is superstition.
 *
 * <p><b>A bounce that fails is a real disconnect.</b> If the client drops during
 * configuration — which is exactly what the proxy bugs in {@link ProxyGate} cause —
 * {@code channelInactive} fires and DISCONNECT is correct. So the guard must be
 * a <em>window</em> that expires, not a flag that is set and cleared by the happy
 * path only; a guard that never expired would leak a player's form state and
 * click tokens forever after one failed bounce.
 *
 * <p><b>The endSession/handleDisconnect split is fabric-api's private
 * implementation detail, not a contract.</b> It is two {@code &#64;Inject}s in a
 * mixin on a vanilla class. A future version that routes a reconfiguration
 * through {@code handleDisconnect} would turn VibeMod's DISCONNECT listener into
 * the bug the brief predicted, silently, on somebody's server. Consulting this
 * class costs one {@code ConcurrentHashMap.get} on a path that runs once per
 * player per disconnect.
 *
 * <p>Static and process-lived for the same reason {@code VibeModFabric}'s
 * subscriptions are: the listener that reads it is registered once per process
 * and must find an answer between worlds as well as during one.
 */
public final class Reconfiguring {

    /**
     * How long a mark stands before it is assumed stale.
     *
     * <p>Thirty seconds, which is the greyed-out Disconnect button's twenty plus
     * room for a slow resource-pack ack. Past it, a player who has not come back
     * has genuinely gone, and the DISCONNECT that fired for them was the truth.
     */
    public static final int WINDOW_TICKS = 600;

    /** Player id to the server tick the bounce was issued on. */
    private static final Map<UUID, Long> BOUNCING = new ConcurrentHashMap<>();

    private Reconfiguring() {
    }

    /** Records that a bounce was issued for this player on tick {@code tick}. */
    public static void mark(UUID player, long tick) {
        BOUNCING.put(player, tick);
    }

    /**
     * True while a bounce issued for this player is still in flight.
     *
     * <p>This is the call {@code VibeModFabric}'s DISCONNECT listener must make
     * before it forgets anything.
     */
    public static boolean isReconfiguring(UUID player, long tick) {
        Long since = BOUNCING.get(player);
        if (since == null) {
            return false;
        }
        if (tick - since > WINDOW_TICKS) {
            // Expired rather than remembered. The player is gone and the forget
            // that was deferred should happen after all — see the class comment.
            BOUNCING.remove(player);
            return false;
        }
        return true;
    }

    /** Clears the mark, on a completed bounce or on a definite departure. */
    public static void clear(UUID player) {
        BOUNCING.remove(player);
    }

    /** Marks older than {@link #WINDOW_TICKS}, so the bouncer can count them lost. */
    public static java.util.List<UUID> expired(long tick) {
        java.util.List<UUID> out = new java.util.ArrayList<>();
        for (Map.Entry<UUID, Long> entry : BOUNCING.entrySet()) {
            if (tick - entry.getValue() > WINDOW_TICKS) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    /** How many bounces are in flight right now. */
    public static int inFlight() {
        return BOUNCING.size();
    }

    /** Drops every mark. Called when a server stops. */
    public static void clearAll() {
        BOUNCING.clear();
    }
}
