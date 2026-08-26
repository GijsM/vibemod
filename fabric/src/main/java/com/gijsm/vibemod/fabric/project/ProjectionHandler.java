package com.gijsm.vibemod.fabric.project;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;

import net.minecraft.network.protocol.Packet;

/**
 * The seam: one Netty handler per Lane B connection, and none at all for a
 * Lane A one (V4 Phase 4).
 *
 * <h2>Why a pipeline handler and not a {@code Connection.send} mixin</h2>
 *
 * <p>Both were on the table and the pipeline won on one question — which
 * survives a config→play→config transition. Disassembling 26.2's
 * {@code Connection} answers it:
 *
 * <pre>
 * setupOutboundProtocol → UnconfiguredPipelineHandler.setupOutboundProtocol(info)
 *     → pipeline.replace(ctx.name(), "encoder", new PacketEncoder(info))
 * setupInboundProtocol  → pipeline.replace(ctx.name(), "decoder", new PacketDecoder(info))
 *     → pipeline.addAfter("encoder", "unbundler", …) / addAfter("decoder", "bundler", …)
 * </pre>
 *
 * <p>Every state change <em>replaces the codec handlers by name, in place</em>,
 * and adds or removes the bundler pair around them. Nothing removes, re-creates
 * or reorders anything else. A handler installed at a stable position therefore
 * survives every transition without being re-installed, and {@code send()} keeps
 * working across the same transitions either way — so the pipeline gives the
 * same coverage as the mixin, plus the inbound half, plus per-connection state
 * that <em>is</em> the handler instance rather than a map keyed by connection.
 *
 * <p><b>It must be duplex</b>, and that is the deciding argument rather than a
 * bonus: {@code ServerboundSetCreativeModeSlotPacket} and
 * {@code ServerboundContainerClickPacket} echo projected stacks back, and
 * without un-projection a creative player destroys their own items the first
 * time they touch one. A {@code Connection.send} mixin sees none of that.
 *
 * <h2>Where exactly it goes, and why not "just before the encoder"</h2>
 *
 * <p>{@code addBefore("packet_handler", …)}. The pipeline that
 * {@code configureSerialization} + {@code configurePacketHandler} build reads,
 * head to tail:
 *
 * <pre>
 * splitter, FlowControlHandler, decoder, [bundler], prepender, encoder, [unbundler], hackfix, packet_handler
 * </pre>
 *
 * <p>Outbound travels tail→head, so a write from {@code packet_handler} (the
 * {@code Connection} itself) reaches us <b>first</b>, while the message is still
 * a {@code Packet<?>} and before the encoder turns it into bytes. Inbound
 * travels head→tail, so a decoded packet reaches us <b>last</b>, immediately
 * before the {@code Connection} hands it to the listener. Both are exactly the
 * window the projection needs.
 *
 * <p>Anchoring on {@code packet_handler} rather than on {@code encoder} is
 * deliberate. {@code encoder} is renamed into existence
 * ({@code "outbound_config"} → {@code "encoder"} on the first
 * {@code setupOutboundProtocol}) and the {@code unbundler} is inserted and
 * removed <em>next to it</em> on every play transition, so a position defined
 * relative to the encoder moves relative to the bundler across a bounce.
 * {@code packet_handler} is added last, once, and never moves. The cost is that
 * we see {@code ClientboundBundlePacket} whole rather than pre-split, which
 * {@code PacketProjection} handles by recursing into it — a known shape rather
 * than a position that shifts under a reconfiguration.
 *
 * <h2>Cost to Lane A</h2>
 *
 * <p>Zero, structurally. This handler is installed only on connections that
 * cannot receive our manifest, so a VibeMod client's pipeline is the vanilla one
 * and there is no branch to take on its hot path. That is the answer to lane
 * coexistence: not a flag read per packet, but a handler that is not there.
 */
public final class ProjectionHandler extends ChannelDuplexHandler {

    private static final Logger LOG = Logger.getLogger("VibeMod.Lane");

    /** The pipeline name, so a second install is a no-op rather than a duplicate. */
    public static final String NAME = "vibemod_projection";

    /** The vanilla handler this one anchors itself in front of. See the class comment. */
    private static final String ANCHOR = "packet_handler";

    /** Handlers currently installed, for the gates. */
    private static final AtomicInteger LIVE = new AtomicInteger();

    /** Handlers installed since boot. */
    private static final AtomicInteger INSTALLED = new AtomicInteger();

    private final String who;

    private ProjectionHandler(String who) {
        this.who = who;
    }

    /**
     * Puts a projection on one connection, or says why it could not.
     *
     * <p>Idempotent: a connection that already has one keeps it. That matters
     * because the lane is decided at {@code BEFORE_CONFIGURE} and a
     * reconfiguration bounce fires that event again on the same channel.
     *
     * @return true when the connection is projected after this call
     */
    public static boolean install(Channel channel, String who) {
        if (channel == null) {
            LOG.warning("cannot project for " + who + ": the connection has no channel yet");
            return false;
        }
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(NAME) != null) {
            return true;
        }
        if (pipeline.get(ANCHOR) == null) {
            LOG.warning("cannot project for " + who + ": this channel's pipeline has no \"" + ANCHOR
                    + "\" handler, so there is no stable position to insert at. Vanilla adds it in "
                    + "Connection.configurePacketHandler and never removes it, so this means the "
                    + "pipeline was built by something other than vanilla — a proxy or another mod");
            return false;
        }
        pipeline.addBefore(ANCHOR, NAME, new ProjectionHandler(who));
        LIVE.incrementAndGet();
        INSTALLED.incrementAndGet();
        LOG.info("Lane B: projecting for " + who + " — a duplex handler in front of " + ANCHOR
                + ", so outbound packets are rewritten before the encoder sees them and inbound "
                + "echoes are un-projected before the listener does");
        return true;
    }

    /** Takes the projection off one connection, if it has one. */
    public static void remove(Channel channel) {
        if (channel == null) {
            return;
        }
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(NAME) != null) {
            // The count comes down in handlerRemoved, which Netty calls for both
            // this path and a channel that simply closed — counting here as well
            // would make a clean disconnect look like two.
            pipeline.remove(NAME);
        }
    }

    // ------------------------------------------------------------------ outbound

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof Packet<?> packet)) {
            // Protocol-change tasks and the bundler's own markers travel this
            // way too. They are not content and must pass untouched.
            ctx.write(msg, promise);
            return;
        }
        Packet<?> projected;
        try {
            projected = PacketProjection.project(packet);
        } catch (RuntimeException failure) {
            // A projection that throws must not take the connection with it: the
            // un-projected packet would disconnect this client on a raw item id,
            // so the honest fallback is to drop the packet and name it.
            LOG.log(Level.SEVERE, "projection failed for " + packet.getClass().getSimpleName()
                    + " to " + who + "; the packet is dropped rather than sent, because an "
                    + "unprojected VibeMod item id is a decoder error on a vanilla client", failure);
            complete(promise);
            return;
        }
        if (projected == null) {
            // Withheld. The promise is completed so nothing upstream waits on a
            // write that will never happen.
            complete(promise);
            return;
        }
        ctx.write(projected, promise);
    }

    /**
     * Completes a promise for a packet that will never be written.
     *
     * <p>{@code isVoid()} first: a void promise is Netty's "nobody is listening"
     * marker and calling {@code trySuccess()} on one is an
     * {@code IllegalStateException}, which would turn a withheld packet into a
     * dropped connection.
     */
    private static void complete(ChannelPromise promise) {
        if (!promise.isVoid()) {
            promise.trySuccess();
        }
    }

    // ------------------------------------------------------------------ inbound

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof Packet<?> packet)) {
            ctx.fireChannelRead(msg);
            return;
        }
        Object out = msg;
        try {
            out = PacketProjection.unproject(packet);
        } catch (RuntimeException failure) {
            // The opposite trade from write(): passing the packet on unchanged
            // means the server reads the projected stack, which is how a
            // creative player loses an item. Dropping it costs one click.
            LOG.log(Level.SEVERE, "un-projection failed for " + packet.getClass().getSimpleName()
                    + " from " + who + "; the packet is dropped rather than handled, because "
                    + "handling it would write the projected stand-in into the player's inventory",
                    failure);
            return;
        }
        ctx.fireChannelRead(out);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        LIVE.decrementAndGet();
        super.handlerRemoved(ctx);
    }

    /** {@code "projectedConnections=1 projectionsInstalled=3"}. */
    public static String describeState() {
        return "projectedConnections=" + Math.max(0, LIVE.get())
                + " projectionsInstalled=" + INSTALLED.get();
    }
}
