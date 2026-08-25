package com.gijsm.vibemod.platform;

import java.util.UUID;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;

/**
 * Message routing. Adventure {@link Audience} is the text/UI currency on all
 * platforms (native on Paper; adventure-platform-mod on Fabric/NeoForge).
 * Main thread unless stated otherwise.
 */
public interface Messenger {

    /** The audience for an online player, or {@link Audience#empty()} when offline. */
    Audience player(UUID playerId);

    /** The server console. */
    Audience console();

    /**
     * Whether that player is connected right now. The progress boss bar needs
     * it to stop animating for someone who logged off mid-generation, which
     * {@link Audience} alone cannot answer.
     */
    boolean online(UUID playerId);

    /** Broadcast to everyone + console. */
    void broadcast(Component message);

    /** Broadcast to holders of {@code permission} + console. */
    void broadcast(Component message, String permission);

    /**
     * Broadcast to holders of {@code permission} but NOT the console. The
     * debug-echo surface needs this: it mirrors a mod's JUL records, which the
     * console already prints through its own log handler, so including it would
     * double every line.
     */
    void broadcastToPlayers(Component message, String permission);

    /**
     * A small celebration at the player (particles/sound — v1 Paper: firework
     * particles + toast sound). Purely cosmetic; implementations may no-op.
     */
    void celebrate(UUID playerId);
}
