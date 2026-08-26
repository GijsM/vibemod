package com.gijsm.vibemod.fabric.project;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * The one thing in Lane B that loses nothing (V4 Phase 4).
 *
 * <p>Every other kind of content has to be projected, and a projection is a
 * lie told carefully. A sound is not: a vanilla client with the resource pack
 * plays a <b>genuinely new sound</b>, by its real name, with no stand-in and no
 * round trip.
 *
 * <p>The reason is the wire form, and it is worth reading off the jar rather
 * than trusting. {@code SoundEvent.<clinit>}, disassembled:
 *
 * <pre>
 * DIRECT_STREAM_CODEC = StreamCodec.composite(Identifier.STREAM_CODEC, …, SoundEvent::create)
 * STREAM_CODEC        = ByteBufCodecs.holder(Registries.SOUND_EVENT, DIRECT_STREAM_CODEC)
 * </pre>
 *
 * <p>{@code ByteBufCodecs.holder} writes a registry reference as its raw id and
 * a <b>direct</b> holder <em>inline</em> — the sound's {@code Identifier} and
 * range, as bytes, with no registry lookup on the far side. So a sound packet
 * carrying {@code Holder.direct(...)} names the sound the way a resource pack
 * names it, and the client resolves it against its own sound atlas. The atlas
 * is exactly what Phase 3's pack adds to.
 *
 * <p>That also means VibeMod does not need {@code Registries.SOUND_EVENT} in
 * its supported set, on either lane: there is no id to negotiate, no order to
 * agree on, and nothing for {@code RegistryHiding} to strip. The one caveat is
 * the honest one — a client that <em>declined</em> the pack has no such sound in
 * its atlas and hears silence, which vanilla already treats as a missing sound
 * rather than an error.
 *
 * <p>{@link #inline} is the whole API.
 */
public final class Sounds {

    private Sounds() {
    }

    /**
     * A sound holder any client can play, VibeMod or not.
     *
     * <p>{@code Holder.direct}, not {@code Registry.getOrThrow}: a reference
     * holder writes a raw id the vanilla client would look up in a registry that
     * does not have it, which is the failure this whole phase is about. A direct
     * holder writes the name.
     *
     * <p>Variable range, matching what {@code SoundEvents} uses for almost every
     * vanilla sound, so attenuation behaves the way a player expects.
     */
    public static Holder<SoundEvent> inline(Identifier sound) {
        return Holder.direct(SoundEvent.createVariableRangeEvent(sound));
    }

    /** The same, with a fixed audible range in blocks. */
    public static Holder<SoundEvent> inline(Identifier sound, float range) {
        return Holder.direct(SoundEvent.createFixedRangeEvent(sound, range));
    }
}
