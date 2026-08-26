package com.gijsm.vibemod.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * The swap half of the palette repack (V4 Phase 1).
 *
 * <p>Re-encoding a section that is already on the global palette is
 * {@code PalettedContainer.pack(strategy)} → {@code unpack(strategy, packed)},
 * both public, and both honest about the width:
 * {@code Configuration$Global.alwaysRepack()} always returns true, so
 * {@code unpack} genuinely rebuilds the bit storage at the strategy's
 * <em>current</em> width instead of reusing the old longs. What is not public is
 * putting the result back:
 *
 * <pre>
 * public class net.minecraft.world.level.chunk.LevelChunkSection {
 *   private final PalettedContainer&lt;BlockState&gt; states;   // no setter
 *   public PalettedContainer&lt;BlockState&gt; getStates();
 * }
 * </pre>
 *
 * <p>Hence a {@code @Mutable} accessor rather than a replacement section.
 * Building a new {@code LevelChunkSection} would also compile, and would be
 * wrong in three ways that each cost a bug: the section's identity is held by
 * the chunk's section array <em>and</em> by the light engine, the
 * {@code biomes} container would have to be carried across by hand, and the
 * four block-count shorts ({@code nonEmptyBlockCount}, {@code fluidCount},
 * {@code tickingBlockCount}, {@code tickingFluidCount}) are rebuilt only by
 * {@code recalcBlockCounts}. Swapping just the container leaves every one of
 * those untouched, which is correct precisely because the contents are
 * identical — only the number of bits they are packed into changed.
 */
@Mixin(LevelChunkSection.class)
public interface LevelChunkSectionAccessor {

    @Mutable
    @Accessor("states")
    void setStates(PalettedContainer<BlockState> states);
}
