package com.gijsm.vibemod.loader;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.gijsm.vibemod.platform.ChatBridge;
import com.gijsm.vibemod.platform.Registration;
import com.gijsm.vibemod.platform.TickScheduler;

/**
 * {@link ChatBridge} over whatever chat event the loader offers — the half that
 * is identical on Fabric and NeoForge.
 *
 * <p>Two callers, one mechanism, exactly as on Paper. {@code /vibe chat} takes a
 * capture that never finishes until the player types {@code off}; the chat
 * renderer takes short-lived captures for individual text and number inputs.
 * One capture per player, so opening a chat-rendered form ends chat mode.
 *
 * <p>Both loaders' chat events already run on the server thread (they fire
 * inside packet handling, which {@code ensureRunningOnSameThread} has already
 * hopped), so unlike Paper's {@code AsyncChatEvent} no thread hop is needed. The
 * hop is kept anyway for one case: a capture handler that opens a screen or
 * starts a generation is doing real work inside a "should this message be
 * allowed" predicate, and doing that on the next tick instead keeps the
 * predicate cheap and honest. The answer — swallow the line — is returned
 * immediately either way.
 *
 * <p>A subclass supplies only the subscriptions that call {@link #onChatLine}
 * and {@link #onDisconnect}.
 */
public abstract class LoaderChatBridge implements ChatBridge {

    private final TickScheduler scheduler;
    private final Map<UUID, Capture> captures = new ConcurrentHashMap<>();

    protected LoaderChatBridge(TickScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * One chat line from one player.
     *
     * @return true when the line was swallowed (it was input to a flow, not
     *         something to broadcast); false when the loader should let it through
     */
    public boolean onChatLine(UUID player, String text) {
        Capture capture = captures.get(player);
        if (capture == null) {
            return false;
        }
        scheduler.runOnMain(() -> deliver(player, capture, text));
        return true;
    }

    /** Ends a leaving player's capture, so nothing waits on a line that will never come. */
    public void onDisconnect(UUID player) {
        Capture capture = captures.get(player);
        if (capture != null) {
            capture.registration.close();
        }
    }

    @Override
    public Registration capture(UUID player, ChatCaptureHandler handler) {
        Capture capture = new Capture(player, handler);
        Capture previous = captures.put(player, capture);
        if (previous != null) {
            previous.registration.close();
        }
        return capture.registration;
    }

    /** Whether this player currently has an active capture — the {@code /vibe chat} state readout. */
    public boolean capturing(UUID player) {
        return captures.containsKey(player);
    }

    private void deliver(UUID id, Capture capture, String text) {
        // Re-check: the capture may have ended between the event and this hop.
        Capture live = captures.get(id);
        if (live != capture) {
            return;
        }
        CaptureResult result;
        try {
            result = capture.handler.onLine(text);
        } catch (Throwable t) {
            result = CaptureResult.CANCELLED;
        }
        if (result != CaptureResult.CONTINUE) {
            capture.registration.close();
        }
    }

    /** One player's active capture, self-removing on close. */
    private final class Capture {

        private final ChatCaptureHandler handler;
        private final Registration registration;

        Capture(UUID player, ChatCaptureHandler handler) {
            this.handler = handler;
            this.registration = Registration.of(() -> captures.remove(player, this));
        }
    }
}
