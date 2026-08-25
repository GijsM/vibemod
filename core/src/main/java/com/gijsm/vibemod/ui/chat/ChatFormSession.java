package com.gijsm.vibemod.ui.chat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.gijsm.vibemod.platform.Registration;
import com.gijsm.vibemod.platform.ui.Screen;

/**
 * One player's open chat screen: the screen itself plus the pending input
 * values they have edited but not yet submitted (ARCHITECTURE-V2 §3.2).
 *
 * <p>Chat has no widget state — a re-render is just the whole block printed
 * again — so the "current" value of an input has to live somewhere between
 * clicks. That somewhere is this object: {@code pending} holds only the keys
 * the player actually touched, and everything else falls back to the input's
 * {@code initial} at submit time. One session per player (opening a screen
 * discards the previous one), 5-minute TTL, pruned lazily on access.
 *
 * <p>Not synchronized: {@link ChatRenderer} hops every mutation onto the main
 * thread, so this is main-thread-confined by construction. The one field that
 * is touched from teardown paths ({@code capture}) is only ever closed, and
 * {@link Registration#close()} is idempotent and thread-safe by contract.
 */
final class ChatFormSession {

    private final UUID player;
    private final Screen screen;
    private final long expiresAt;

    /** Player-edited values, keyed by {@code Input.key()}: String, Boolean or Double. */
    private final Map<String, Object> pending = new LinkedHashMap<>();

    /** The chat capture opened by a {@code [change]} click, or null when none is running. */
    private Registration capture;

    ChatFormSession(UUID player, Screen screen, long expiresAt) {
        this.player = player;
        this.screen = screen;
        this.expiresAt = expiresAt;
    }

    UUID player() {
        return player;
    }

    Screen screen() {
        return screen;
    }

    boolean expired(long nowMillis) {
        return nowMillis > expiresAt;
    }

    /** The value the player edited for {@code key}, or null when they never touched it. */
    Object pending(String key) {
        return pending.get(key);
    }

    void put(String key, Object value) {
        pending.put(key, value);
    }

    /**
     * Installs a new capture, closing any previous one first. Two captures for
     * one player would fight over their chat lines; the {@link com.gijsm.vibemod.platform.ChatBridge}
     * contract already allows only one, so closing here keeps our own handle honest.
     */
    void capture(Registration registration) {
        closeCapture();
        this.capture = registration;
    }

    /** Ends any running capture. Idempotent; never throws. */
    void closeCapture() {
        Registration open = this.capture;
        this.capture = null;
        if (open != null) {
            open.close();
        }
    }
}
