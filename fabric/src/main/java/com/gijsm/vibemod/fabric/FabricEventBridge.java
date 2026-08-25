package com.gijsm.vibemod.fabric;

import java.util.function.Consumer;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

import com.gijsm.vibemod.loader.LoaderEventBridge;
import com.gijsm.vibemod.runtime.ModDispatch;

/**
 * The Fabric half of {@link LoaderEventBridge}: the ten curated hooks (§4.1)
 * wired to Fabric's own events. Everything below the subscriptions — the per-mod
 * registries, the revocation and the dispatch policy — is shared with NeoForge.
 */
public final class FabricEventBridge extends LoaderEventBridge {

    public FabricEventBridge(ModDispatch dispatch) {
        super(dispatch);
    }

    /**
     * Subscribes the host's one permanent listener to each Fabric event.
     *
     * <p><b>Static, and called exactly once from mod init — never per server.</b>
     * A Fabric event cannot be unregistered, which is the premise this entire
     * class is built on, and it applies to the host as much as to a generated
     * mod. An earlier version registered these from the per-server bootstrap: on
     * a client that loads a second world in the same session, that leaves a
     * second full set of hooks dispatching into the first world's dead bridge,
     * forever. The subscriptions therefore live as long as the process and
     * resolve the live bridge — which IS per server — each time they fire.
     *
     * @param live the current bridge, or null between worlds
     */
    public static void installDispatchers(Supplier<FabricEventBridge> live) {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                with(live, b -> b.dispatchPlayerJoin(handler.player)));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                with(live, b -> b.dispatchPlayerQuit(handler.player)));

        ServerTickEvents.END_SERVER_TICK.register(server ->
                with(live, b -> b.dispatchServerTick(server)));

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                with(live, b -> b.dispatchRespawn(newPlayer)));

        // ALLOW_DEATH is the only player-death hook Fabric offers. VibeMod's
        // onPlayerDeath does not cancel (§4.1: it is a Consumer), so this always
        // allows the death and just notifies.
        ServerPlayerEvents.ALLOW_DEATH.register((player, source, amount) -> {
            with(live, b -> b.dispatchPlayerDeath(player));
            return true;
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) ->
                with(live, b -> b.dispatchEntityDeath(entity, source)));

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            FabricEventBridge bridge = live.get();
            return bridge == null
                    || bridge.dispatchChat(sender, message.decoratedContent().getString());
        });

        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            FabricEventBridge bridge = live.get();
            if (bridge == null || !(player instanceof ServerPlayer serverPlayer)) {
                return true;
            }
            return bridge.dispatchBlockBreak(serverPlayer, pos, state);
        });

        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            FabricEventBridge bridge = live.get();
            if (bridge == null || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            return bridge.dispatchUseBlock(serverPlayer, hand, hitResult.getBlockPos())
                    ? InteractionResult.PASS : InteractionResult.FAIL;
        });

        UseItemCallback.EVENT.register((player, level, hand) -> {
            FabricEventBridge bridge = live.get();
            if (bridge == null || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            return bridge.dispatchUseItem(serverPlayer, hand)
                    ? InteractionResult.PASS : InteractionResult.FAIL;
        });
    }

    /** Runs {@code body} against the live bridge, or does nothing when there is none. */
    private static void with(Supplier<FabricEventBridge> live, Consumer<FabricEventBridge> body) {
        FabricEventBridge bridge = live.get();
        if (bridge != null) {
            body.accept(bridge);
        }
    }
}
