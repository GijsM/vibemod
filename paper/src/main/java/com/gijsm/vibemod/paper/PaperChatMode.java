package com.gijsm.vibemod.paper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import com.gijsm.vibemod.platform.ChatBridge;
import com.gijsm.vibemod.platform.Registration;

/**
 * "Chat mode": while it is on for a player, their chat lines become mod prompts
 * instead of chat, and typing {@code off} turns it back off. Behind
 * {@code /vibe chat}.
 *
 * <p>v1 had its own {@code AsyncChatEvent} listener for this. It is now just a
 * long-lived {@link ChatBridge} capture that never returns {@code DONE}
 * (ARCHITECTURE-V2 §1.1) — the same mechanism the chat renderer uses for a
 * single text input, held open indefinitely. Since the bridge allows one capture
 * per player, opening a chat-rendered form ends chat mode; that is the honest
 * outcome when two flows both want the player's next line, and it can only
 * happen on a server with no dialog support.
 */
public final class PaperChatMode {

    /** The word that ends chat mode, matching v1. */
    private static final String OFF_WORD = "off";

    private final ChatBridge chat;
    private final BiConsumer<UUID, String> onPrompt;
    private final Map<UUID, Registration> active = new ConcurrentHashMap<>();

    public PaperChatMode(ChatBridge chat, BiConsumer<UUID, String> onPrompt) {
        this.chat = chat;
        this.onPrompt = onPrompt;
    }

    /** Flips chat mode for {@code player} and returns the new state. */
    public boolean toggle(UUID player) {
        Registration existing = active.remove(player);
        if (existing != null) {
            existing.close();
            return false;
        }
        Registration registration = chat.capture(player, line -> {
            if (line.trim().equalsIgnoreCase(OFF_WORD)) {
                active.remove(player);
                return ChatBridge.CaptureResult.CANCELLED;
            }
            onPrompt.accept(player, line);
            return ChatBridge.CaptureResult.CONTINUE;
        });
        active.put(player, registration);
        return true;
    }

    /** Whether chat mode is on for this player. */
    public boolean enabled(UUID player) {
        Registration registration = active.get(player);
        return registration != null && registration.active();
    }
}
