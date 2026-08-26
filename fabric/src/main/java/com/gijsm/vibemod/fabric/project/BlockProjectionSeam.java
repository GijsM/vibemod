package com.gijsm.vibemod.fabric.project;

import net.minecraft.resources.Identifier;

/**
 * The seam block projection would attach to, reserved and deliberately empty
 * (V4 Phase 4).
 *
 * <p>This class builds nothing. It exists so the next person to open the
 * question finds the shape of the answer and the two facts that make it harder
 * than "swap the blockstate id", both verified against 26.2 rather than
 * remembered.
 *
 * <h2>1. The pools are finite, and the server must be able to say so</h2>
 *
 * <p>Projection borrows vanilla blockstates: a generated block is shown to a
 * Lane B client as some vanilla state that nothing else in the world is using
 * for its own purpose — the note-block variants are the classic pool because
 * they are numerous, visually inert and rarely placed. A pool is a fixed number
 * of states, and it runs out. When it does the server must <b>refuse by name,
 * with the count</b>: "0 full-block states left, this mod's block cannot be
 * shown to vanilla clients". A pool that silently wraps around is two different
 * blocks rendering as one, which is worse than a refusal and impossible to
 * diagnose from a screenshot.
 *
 * <p>Separate pools are needed per *shape class* — a full cube, a
 * transparent block, a block with collision unlike a cube — because a client
 * ray-traces and collides against the state it was given, not the one the server
 * has.
 *
 * <h2>2. Sections must be re-encoded at the client's own width, not remapped</h2>
 *
 * <p>This is the part that is easy to get wrong, and {@code PaletteGuard}'s
 * disassembly already settles it. A chunk section on the global palette writes
 * its data at {@code Strategy.globalPaletteBitsInMemory} — the <em>sender's</em>
 * in-memory width — and {@code PalettedContainer.read} sizes its long array from
 * the <em>receiver's</em> own width. So a projection that walks the packed longs
 * swapping ids leaves the section encoded at the server's width, and a vanilla
 * client whose {@code BLOCK_STATE_REGISTRY} is smaller decodes a long array of
 * the wrong length and dies on the spot.
 *
 * <p>The correct operation is a full re-encode: unpack at our width, map every
 * state through the borrowed-pool table, repack at the width the client's own
 * registry implies. Because every projected state <em>is</em> a vanilla state,
 * the result always lands inside vanilla's range, so the re-encode is always
 * possible — which is the one piece of good news here.
 *
 * <p>Both {@code ClientboundLevelChunkWithLightPacket} and
 * {@code ClientboundSectionBlocksUpdatePacket} need it, and
 * {@code ClientboundBlockUpdatePacket} needs only the id map.
 *
 * <h2>Until then</h2>
 *
 * <p>Blocks stay singleplayer/LAN, {@link #refusalFor} is the sentence, and
 * {@code ProjectedPackets} declares the three chunk packets as uncovered so the
 * gate keeps them visible rather than letting a future version quietly add a
 * fourth.
 */
public final class BlockProjectionSeam {

    private BlockProjectionSeam() {
    }

    /** What to tell a mod that registers a block while a vanilla client may connect. */
    public static String refusalFor(Identifier block) {
        return block + " will not be visible to players who are not running VibeMod. Lane B does "
                + "not project blocks in this version: showing one means borrowing a vanilla "
                + "blockstate from a finite pool AND re-encoding every chunk section at the "
                + "client's own palette width, because the network format carries the sender's "
                + "in-memory width. Until that exists, blocks are singleplayer and LAN only";
    }
}
