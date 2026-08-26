package com.gijsm.vibemod.fabric.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;

/**
 * Every chunk a level currently has in memory, which vanilla 26.2 offers no
 * public way to enumerate (V4 Phase 1).
 *
 * <p>The palette crossing has to visit <em>all</em> of them, and the public
 * surface is a set of near misses:
 *
 * <pre>
 * public LevelChunk getChunkToSend(long);              // needs the keys we do not have
 * public void forEachBlockTickingChunk(Consumer&lt;LevelChunk&gt;);  // ticking only
 * Stream&lt;ChunkHolder&gt; allChunksWithAtLeastStatus(ChunkStatus);  // package-private
 * private volatile Long2ObjectLinkedOpenHashMap&lt;ChunkHolder&gt; visibleChunkMap;
 * private final    Long2ObjectLinkedOpenHashMap&lt;ChunkHolder&gt; updatingChunkMap;
 * private final    Long2ObjectLinkedOpenHashMap&lt;ChunkHolder&gt; pendingUnloads;
 * </pre>
 *
 * <p>{@code forEachBlockTickingChunk} is the tempting one and it is a hole: a
 * loaded-but-not-ticking border chunk skipped by the sweep keeps a container on
 * the old width, and the next block written into it throws from
 * {@code SimpleBitStorage.set}. So the sweep reads the maps.
 *
 * <p>{@code updatingChunkMap} rather than {@code visibleChunkMap} because it is
 * the live one — {@code promoteChunkMap} copies it into {@code visibleChunkMap},
 * so visible is a strictly older snapshot holding the very same
 * {@code ChunkHolder} objects. {@code pendingUnloads} is swept too: those
 * holders have left {@code updatingChunkMap} but their chunks are still in
 * memory and can still be written to before the unload completes.
 *
 * <p>{@code ServerChunkCache.chunkMap} is a public field, so getting here needs
 * no second accessor.
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {

    @Accessor("updatingChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> getUpdatingChunkMap();

    @Accessor("pendingUnloads")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> getPendingUnloads();
}
