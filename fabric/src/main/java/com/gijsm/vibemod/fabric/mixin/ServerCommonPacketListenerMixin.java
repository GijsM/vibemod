package com.gijsm.vibemod.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import com.gijsm.vibemod.loader.DialogClicks;

/**
 * The one mixin (ARCHITECTURE-V2 §8.5), and the reason it has to exist.
 *
 * <p>A vanilla dialog's button ships its form values back as a
 * {@code custom_click_action} packet. Vanilla's handler for it —
 * {@code ServerCommonPacketListenerImpl#handleCustomClickAction} — forwards to
 * {@code MinecraftServer#handleCustomClickAction(Identifier, Optional&lt;Tag&gt;)},
 * which is a debug log line and, crucially, <b>does not receive the player</b>.
 * There is no event, no registry, and no other place the click surfaces. So the
 * only hook that knows <em>who</em> clicked is this method, on this class, where
 * {@code this} is the connection.
 *
 * <p>{@code ServerCommonPacketListenerImpl} is abstract and shared by the
 * configuration and play phases; only the play phase has a player, hence the
 * {@code instanceof} rather than a mixin on the game listener (the method is not
 * overridden there, so there would be nothing to inject into).
 *
 * <p>Injected at HEAD and hopped explicitly. The vanilla body starts with
 * {@code PacketUtils.ensureRunningOnSameThread}, which means at HEAD we may
 * still be on a Netty thread; {@code server.execute} is the unambiguous fix and
 * costs one tick at most. Nothing is cancelled — vanilla's no-op is welcome to
 * run.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerMixin {

    @Shadow
    @org.spongepowered.asm.mixin.Final
    protected MinecraftServer server;

    @Inject(method = "handleCustomClickAction", at = @At("HEAD"))
    private void vibemod$routeCustomClick(ServerboundCustomClickActionPacket packet, CallbackInfo ci) {
        if (!(((Object) this) instanceof ServerGamePacketListenerImpl play)) {
            return;
        }
        ServerPlayer player = play.player;
        MinecraftServer runningOn = this.server;
        if (player == null || runningOn == null) {
            return;
        }
        runningOn.execute(() -> DialogClicks.handle(player, packet.id(), packet.payload()));
    }
}
