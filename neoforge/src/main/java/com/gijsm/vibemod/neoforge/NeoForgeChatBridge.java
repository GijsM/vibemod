package com.gijsm.vibemod.neoforge;

import java.util.function.Supplier;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import net.minecraft.server.level.ServerPlayer;

import com.gijsm.vibemod.loader.LoaderChatBridge;
import com.gijsm.vibemod.platform.TickScheduler;

/**
 * {@link LoaderChatBridge} over {@code ServerChatEvent}.
 *
 * <p>Registered at {@link EventPriority#HIGHEST} so a captured line — one the
 * player typed into a chat-rendered form, or into {@code /vibe chat} — is
 * swallowed before any other mod's chat handling sees it. A form's answer is
 * input to a flow, not a message; letting a chat-formatting mod broadcast
 * "howdy" to the server because someone was filling in a text field would be a
 * privacy bug as much as a cosmetic one.
 *
 * <p>Registered once per process for the same reason as
 * {@link NeoForgeEventBridge}: the bridge is per world, the subscription is not.
 */
public final class NeoForgeChatBridge extends LoaderChatBridge {

    public NeoForgeChatBridge(TickScheduler scheduler) {
        super(scheduler);
    }

    /**
     * @param bus  {@code NeoForge.EVENT_BUS}
     * @param live the current bridge, or null between worlds
     */
    public static void installDispatcher(IEventBus bus, Supplier<NeoForgeChatBridge> live) {
        bus.addListener(EventPriority.HIGHEST, ServerChatEvent.class, event -> {
            NeoForgeChatBridge bridge = live.get();
            if (bridge == null) {
                return;
            }
            if (bridge.onChatLine(event.getPlayer().getUUID(), event.getRawText())) {
                event.setCanceled(true);
            }
        });

        bus.addListener(PlayerEvent.PlayerLoggedOutEvent.class, event -> {
            NeoForgeChatBridge bridge = live.get();
            if (bridge != null && event.getEntity() instanceof ServerPlayer player) {
                bridge.onDisconnect(player.getUUID());
            }
        });
    }
}
