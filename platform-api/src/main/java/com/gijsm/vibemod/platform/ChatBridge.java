package com.gijsm.vibemod.platform;

import java.util.UUID;

/**
 * Temporarily captures a player's chat lines (the ChatRenderer's text-input
 * mechanism and the v1 {@code ChatMode} pattern). While a capture is active
 * the player's chat lines are swallowed (not broadcast) and delivered to the
 * handler on the main thread.
 */
public interface ChatBridge {

    /** What to do after a captured line. */
    enum CaptureResult {
        /** Keep capturing further lines. */
        CONTINUE,
        /** Capture finished normally. */
        DONE,
        /** Capture aborted (player typed the cancel word, or the flow gave up). */
        CANCELLED
    }

    /** Handles one captured chat line; runs on the main thread. */
    @FunctionalInterface
    interface ChatCaptureHandler {
        CaptureResult onLine(String line);
    }

    /**
     * Starts capturing {@code player}'s chat. At most one capture per player:
     * starting a new one closes the previous (as CANCELLED). The returned
     * registration stops the capture; it also closes itself when the handler
     * returns DONE or CANCELLED, or when the player disconnects.
     */
    Registration capture(UUID player, ChatCaptureHandler handler);
}
