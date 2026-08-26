package com.gijsm.vibemod.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;

/**
 * How a configuration listener becomes the connection underneath it
 * (V4 Phase 4).
 *
 * <p>Read off the 26.2 jar with {@code javap}:
 *
 * <pre>
 * public abstract class net.minecraft.server.network.ServerCommonPacketListenerImpl … {
 *   protected final net.minecraft.server.MinecraftServer server;
 *   protected final net.minecraft.network.Connection connection;
 * }
 * </pre>
 *
 * <p>{@code protected} is not reachable from another package without
 * subclassing, and there is no accessor — so this one line is what lets Lane B
 * turn the {@code ServerConfigurationPacketListenerImpl} that
 * {@code BEFORE_CONFIGURE} hands it into the {@code Connection}, and from there
 * (via {@link ConnectionAccessor}) into the Netty pipeline the projection
 * installs itself on.
 *
 * <p>The mixin goes on the abstract superclass rather than on the two concrete
 * listeners, so the same cast works for a connection in configuration and the
 * same connection in play — which matters, because the projection is installed
 * during configuration and has to survive into play on the same channel.
 *
 * <p>Common, not client-only.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface ServerCommonPacketListenerAccessor {

    @Accessor("connection")
    Connection getConnection();
}
