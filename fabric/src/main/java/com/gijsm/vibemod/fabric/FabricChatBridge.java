package com.gijsm.vibemod.fabric;

import java.util.function.Supplier;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import com.gijsm.vibemod.loader.LoaderChatBridge;
import com.gijsm.vibemod.platform.TickScheduler;

/**
 * {@link LoaderChatBridge} over {@code ServerMessageEvents.ALLOW_CHAT_MESSAGE}.
 */
public final class FabricChatBridge extends LoaderChatBridge {

    public FabricChatBridge(TickScheduler scheduler) {
        super(scheduler);
    }

    /**
     * Subscribes the host's one permanent chat listener.
     *
     * <p>Static and called once from mod init, for the same reason as
     * {@code FabricEventBridge.installDispatchers}: Fabric events cannot be
     * unregistered, so registering per server would leave a listener per world
     * loaded in the session, each still holding its own dead capture map.
     *
     * @param live the current bridge, or null between worlds
     */
    public static void installDispatcher(Supplier<FabricChatBridge> live) {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            FabricChatBridge bridge = live.get();
            // A swallowed line is one the flow consumed; anything else passes.
            return bridge == null || !bridge.onChatLine(sender.getUUID(), message.signedContent());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            FabricChatBridge bridge = live.get();
            if (bridge != null) {
                bridge.onDisconnect(handler.player.getUUID());
            }
        });
    }
}
