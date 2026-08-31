package com.gijsm.vibemod.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;

/**
 * One private field, and it is the only way to ask "is this player mid-teleport?"
 * (V4 Phase 5).
 *
 * <p>Read off the 26.2 jar with {@code javap}:
 *
 * <pre>
 * public class net.minecraft.server.network.ServerGamePacketListenerImpl … {
 *   private net.minecraft.world.phys.Vec3 awaitingPositionFromClient;
 *   private int awaitingTeleport;
 *   private boolean updateAwaitingTeleport();
 * }
 * </pre>
 *
 * <p>A non-null {@code awaitingPositionFromClient} means the server has sent a
 * {@code ClientboundPlayerPositionPacket} and is waiting for the client's
 * {@code ServerboundAcceptTeleportationPacket} to confirm it. Bouncing a player
 * through configuration in that window destroys the connection the confirmation
 * would have come back on: {@code switchToConfig()} calls
 * {@code removePlayerFromWorld()}, which calls {@code PlayerList.remove(player)},
 * and the {@code ServerPlayer} the teleport was addressed to stops existing —
 * {@code PrepareSpawnTask} builds a <em>new</em> one from
 * {@code PlayerList.loadPlayerData}. So the player lands wherever the last save
 * put them, which is not where the teleport was sending them.
 *
 * <p>There is no public predicate for this. {@code updateAwaitingTeleport()} is
 * private and has a side effect (it counts down {@code awaitingTeleportTime}),
 * so reading the field is both the only option and the correct one — a query
 * must not advance the state it is querying.
 *
 * <p>Common, not client-only: the class is the dedicated server's play listener.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerAccessor {

    @Accessor("awaitingPositionFromClient")
    Vec3 getAwaitingPositionFromClient();
}
