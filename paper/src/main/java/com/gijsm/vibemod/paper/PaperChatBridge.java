package com.gijsm.vibemod.paper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.papermc.paper.event.player.AsyncChatEvent;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import com.gijsm.vibemod.platform.ChatBridge;
import com.gijsm.vibemod.platform.Registration;
import com.gijsm.vibemod.platform.TickScheduler;

/**
 * {@link ChatBridge} over Paper's {@link AsyncChatEvent}: v1's {@code ui/ChatMode}
 * generalized (ARCHITECTURE-V2 §1.1, §3.2).
 *
 * <p>Two callers, one mechanism. {@code /vibe chat} takes a capture that never
 * finishes until the player types {@code off} — that is chat-as-prompt mode,
 * unchanged. The chat renderer takes short-lived captures for individual text
 * and number inputs. Because the contract is one capture per player, opening a
 * form while chat mode is on ends chat mode; that is the honest behaviour (both
 * want the player's next line) and it only ever happens on a server with no
 * dialog support, where the chat renderer is the UI.
 *
 * <p>Chat events fire off the main thread, so every handler call is hopped back
 * before it runs — core code and generated mods must never see an off-main
 * callback.
 */
public final class PaperChatBridge implements ChatBridge, Listener {

    private final TickScheduler scheduler;
    private final Map<UUID, Capture> captures = new ConcurrentHashMap<>();

    public PaperChatBridge(Plugin plugin, TickScheduler scheduler) {
        this.scheduler = scheduler;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
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

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Capture capture = captures.get(id);
        if (capture == null) {
            return;
        }
        // Swallow the line: it is input to a flow, not something to broadcast.
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        scheduler.runOnMain(() -> {
            // Re-check on the main thread: the capture may have ended between the
            // async event and this hop.
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
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Capture capture = captures.get(event.getPlayer().getUniqueId());
        if (capture != null) {
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
