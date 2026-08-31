package com.gijsm.vibemod.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.chunk.Strategy;

/**
 * One {@code int}, and it is the entire mechanism by which VibeMod can register
 * a block once the global block palette has to grow a bit (V4 Phase 1).
 *
 * <p>Read off the 26.2 jar with {@code javap}:
 *
 * <pre>
 * public abstract class net.minecraft.world.level.chunk.Strategy&lt;T&gt; {
 *   private final net.minecraft.core.IdMap&lt;T&gt; globalMap;      // LIVE reference
 *   protected final int globalPaletteBitsInMemory;             // computed ONCE, in &lt;init&gt;
 *   protected abstract Configuration getConfigurationForBitCount(int);
 * }
 * private static int minimumBitsRequiredForDistinctValues(int n) { return Mth.ceillog2(n); }
 * </pre>
 *
 * <p>The {@code globalMap} reference is live, so an append to
 * {@code Block.BLOCK_STATE_REGISTRY} is seen immediately. The width is not: it
 * is {@code Mth.ceillog2(idMap.size())} frozen at construction, and
 * {@code getConfigurationForBitCount(n)} hands it to
 * {@code new Configuration$Global(globalPaletteBitsInMemory, n)} for every
 * {@code n > 8}. A container built under a 15-bit strategy therefore cannot
 * represent id 32768 — {@code SimpleBitStorage.set} opens with
 * {@code Validate.inclusiveBetween(0L, mask, value)} and throws
 * {@code IllegalArgumentException}. Loud, which is the one mercy here.
 *
 * <p><b>Why widen this field rather than swap {@code Level.palettedContainerFactory}.</b>
 * Every {@code PalettedContainer} holds its own {@code private final Strategy}
 * and consults <em>that</em>, never the Level's factory. A factory swap would
 * fix only containers created afterwards and leave every existing one stale —
 * and a local-palette container would become a latent failure, because the
 * moment it promotes past 8 bits it asks its <em>old</em> strategy and builds a
 * 15-bit global storage. Writing this one field fixes every container that
 * already holds this strategy, including the {@code ProtoChunk}s a worldgen
 * worker is building right now, with a single {@code int} store.
 *
 * <p>The interface goes on the abstract superclass rather than on the concrete
 * {@code Strategy$1}/{@code Strategy$2} the two factory methods actually
 * instantiate, so both inherit it and a cast on any strategy instance works.
 *
 * <p>Common, not client-only: a crossing widens the server's strategies and the
 * client's, and the class that drives it is loaded on a dedicated server.
 */
@Mixin(Strategy.class)
public interface StrategyAccessor {

    @Accessor("globalPaletteBitsInMemory")
    int getGlobalPaletteBitsInMemory();

    @Mutable
    @Accessor("globalPaletteBitsInMemory")
    void setGlobalPaletteBitsInMemory(int bits);
}
