package com.gijsm.vibemod.fabric.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * What the client says back about a {@link ContentManifest} (V4 Phase 2).
 *
 * <p>The important field is {@link #orderHash}, and what it is taken over is
 * the point: the <b>ordered id list the client actually registered</b>, never
 * raw numeric ids. Raw ids are meaningless at this moment — Fabric's
 * {@code SyncConfigurationTask} has not run yet and is the thing that will
 * settle them — so hashing them would compare two numbers neither end has
 * agreed on. Hashing the ids compares the one thing both ends are claiming to
 * have built.
 *
 * <p>{@link #sampleIds} exists so a refusal can be read. A server that finds a
 * hash mismatch has its own ordered list and now the client's first few, which
 * turns "the registries disagree" into "we sent {@code vibemod_a:x} first and
 * you registered {@code vibemod_b:y} first" — the difference between a bug
 * report and a fix.
 *
 * <p>{@link #problem} is the client's own refusal, spoken in the client's
 * words, for the cases only the client can see: an id it already holds from a
 * different VibeMod installation, a blockstate baseline that does not match, a
 * schema it could not rebuild. The server relays it into the disconnect message
 * rather than paraphrasing it, because the client is the only end that knows
 * what it is already carrying.
 *
 * @param protocol  echoed back, so a protocol mismatch is caught from both
 *                  directions rather than only where the manifest was read
 * @param orderHash the client's hash over what it registered, or the empty
 *                  string when it registered nothing because it refused
 * @param count     how many entries it registered or already had
 * @param sampleIds the first few ids in the client's order
 * @param problem   null when the client is happy; otherwise why it is not
 */
public record ContentAck(int protocol, String orderHash, int count, List<String> sampleIds,
                         String problem) implements CustomPacketPayload {

    /** How many ids a refusal names from each side. Enough to see the shape of a drift. */
    public static final int SAMPLE = 6;

    /** The channel. Serverbound, configuration phase. */
    public static final Type<ContentAck> TYPE =
            CustomPacketPayload.createType("vibemod:content_ack");

    /**
     * The split threshold.
     *
     * <p>Serverbound configuration payloads split above 32767 bytes, which
     * {@link #sampleIds} could not reach — but {@link #problem} is a message
     * built from ids and a mod name, and a payload that is refused for being
     * too long is a refusal nobody can read. 256 KiB costs nothing and removes
     * the failure mode.
     */
    public static final int MAX_BYTES = 256 * 1024;

    public static final StreamCodec<FriendlyByteBuf, ContentAck> STREAM_CODEC =
            StreamCodec.of(ContentAck::write, ContentAck::read);

    public ContentAck {
        sampleIds = sampleIds == null ? List.of() : List.copyOf(sampleIds);
    }

    /** The happy answer. */
    public static ContentAck ok(String orderHash, int count, List<String> sampleIds) {
        return new ContentAck(ContentManifest.PROTOCOL, orderHash, count, sampleIds, null);
    }

    /** The client refusing, in its own words. */
    public static ContentAck refused(String problem) {
        return new ContentAck(ContentManifest.PROTOCOL, "", 0, List.of(), problem);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buf, ContentAck ack) {
        buf.writeVarInt(ack.protocol());
        buf.writeUtf(ack.orderHash());
        buf.writeVarInt(ack.count());
        buf.writeVarInt(ack.sampleIds().size());
        for (String id : ack.sampleIds()) {
            buf.writeUtf(id);
        }
        buf.writeBoolean(ack.problem() != null);
        if (ack.problem() != null) {
            buf.writeUtf(ack.problem(), 4096);
        }
    }

    private static ContentAck read(FriendlyByteBuf buf) {
        int protocol = buf.readVarInt();
        String orderHash = buf.readUtf();
        int count = buf.readVarInt();
        int samples = buf.readVarInt();
        List<String> sampleIds = new ArrayList<>(Math.min(samples, 64));
        for (int i = 0; i < samples; i++) {
            sampleIds.add(buf.readUtf());
        }
        String problem = buf.readBoolean() ? buf.readUtf(4096) : null;
        return new ContentAck(protocol, orderHash, count, sampleIds, problem);
    }
}
