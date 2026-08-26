package com.gijsm.vibemod.fabric.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * What the client says back about a {@link ContentBind} (V4 Phase 2).
 *
 * <p>Three answers, and they are deliberately three rather than a boolean:
 *
 * <ul>
 *   <li><b>bound now</b> ({@code deferred} false, {@code problem} null) — the
 *       connection already had a full registry provider, which happens on a
 *       reconfiguration bounce.</li>
 *   <li><b>armed</b> ({@code deferred} true, {@code problem} null) — the normal
 *       first join. The bind runs at {@code ClientPlayConnectionEvents.JOIN};
 *       {@link ContentBind}'s class comment has the queue arithmetic that makes
 *       this the earliest moment it can succeed.</li>
 *   <li><b>refused</b> ({@code problem} non-null) — the client could not bind
 *       and knows it will not be able to. The server disconnects rather than
 *       admitting a player whose next inventory packet would throw.</li>
 * </ul>
 *
 * <p>The distinction is logged per join rather than acted on, because "armed"
 * is the expected answer and a server that treated it as a failure would refuse
 * every first join. What it buys is that the operator log says which of the
 * two happened, which is the only way the deferral is ever noticed if it stops
 * working.
 */
public record BindAck(int protocol, boolean deferred, String problem)
        implements CustomPacketPayload {

    /** The channel. Serverbound, configuration phase. */
    public static final Type<BindAck> TYPE = CustomPacketPayload.createType("vibemod:bind_ack");

    public static final StreamCodec<FriendlyByteBuf, BindAck> STREAM_CODEC =
            StreamCodec.of(BindAck::write, BindAck::read);

    /** Bound against a provider that already existed. */
    public static BindAck bound() {
        return new BindAck(ContentManifest.PROTOCOL, false, null);
    }

    /** Armed for play join; the normal answer. */
    public static BindAck armed() {
        return new BindAck(ContentManifest.PROTOCOL, true, null);
    }

    /** The client knows it cannot bind. */
    public static BindAck refused(String problem) {
        return new BindAck(ContentManifest.PROTOCOL, false, problem);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buf, BindAck ack) {
        buf.writeVarInt(ack.protocol());
        buf.writeBoolean(ack.deferred());
        buf.writeBoolean(ack.problem() != null);
        if (ack.problem() != null) {
            buf.writeUtf(ack.problem(), 4096);
        }
    }

    private static BindAck read(FriendlyByteBuf buf) {
        return new BindAck(buf.readVarInt(), buf.readBoolean(),
                buf.readBoolean() ? buf.readUtf(4096) : null);
    }
}
