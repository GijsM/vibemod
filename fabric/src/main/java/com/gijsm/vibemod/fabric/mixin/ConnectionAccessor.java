package com.gijsm.vibemod.fabric.mixin;

import io.netty.channel.Channel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.Connection;

/**
 * The one field that turns a packet listener into a Netty pipeline (V4 Phase 4).
 *
 * <p>Read off the 26.2 jar with {@code javap}:
 *
 * <pre>
 * public class net.minecraft.network.Connection extends SimpleChannelInboundHandler&lt;Packet&lt;?&gt;&gt; {
 *   private io.netty.channel.Channel channel;
 *   public void configurePacketHandler(io.netty.channel.ChannelPipeline);
 * }
 * </pre>
 *
 * <p>There is no getter. {@code Connection} exposes {@code isConnected()},
 * {@code getRemoteAddress()} and {@code send(...)} and keeps the channel to
 * itself, so the only way to reach the pipeline Lane B's projection has to
 * insert into is to read the field.
 *
 * <p><b>Read-only on purpose.</b> Nothing here writes the channel: the
 * projection adds and removes a named handler on the pipeline the field points
 * at, which is a Netty operation on Netty's own object, not a change to
 * Minecraft's state. Writing this field would swap a live connection's socket,
 * which is not something any part of VibeMod has business doing.
 *
 * <p>Common, not client-only: the class is the dedicated server's connection.
 */
@Mixin(Connection.class)
public interface ConnectionAccessor {

    @Accessor("channel")
    Channel getChannel();
}
