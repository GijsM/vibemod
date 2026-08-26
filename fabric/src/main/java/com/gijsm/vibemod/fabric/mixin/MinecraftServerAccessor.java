package com.gijsm.vibemod.fabric.mixin;

import java.util.Map;
import java.util.concurrent.Executor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * The three private things a runtime dimension needs and vanilla does not hand
 * out (V4 Phase 6).
 *
 * <p>Read off the 26.2 jar, and each is here because the public surface stops
 * exactly short of it:
 *
 * <pre>
 * public abstract class net.minecraft.server.MinecraftServer … {
 *   private final Map&lt;ResourceKey&lt;Level&gt;, ServerLevel&gt; levels;
 *   private final Executor executor;
 *   protected final LevelStorageSource$LevelStorageAccess storageSource;
 *   public ServerLevel getLevel(ResourceKey&lt;Level&gt;);      // reads levels
 *   public Iterable&lt;ServerLevel&gt; getAllLevels();           // reads levels
 * }
 * </pre>
 *
 * <p><b>{@code levels}</b> is the map {@code createLevels()} writes and
 * {@code getLevel}/{@code getAllLevels} read. It is {@code final}, but the map
 * <em>object</em> is mutable, so a plain getter is the whole mechanism — no
 * {@code @Mutable} and no field reassignment. Vanilla's own non-overworld branch
 * does exactly {@code levels.put(levelKey, new ServerLevel(…))}, which is the
 * line {@code DimensionSeam} reproduces.
 *
 * <p><b>{@code executor}</b> and <b>{@code storageSource}</b> are two of the ten
 * arguments to the public {@code ServerLevel} constructor, and nothing public
 * returns either. Passing anything else would be a guess: the executor is the one
 * every other level's {@code ServerChunkCache} already schedules worldgen onto,
 * and the storage access is the one holding this world's directory lock.
 *
 * <p>Common, not client-only: the integrated server is a {@code MinecraftServer}
 * too, and the client gate runs one.
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {

    /** The live level map — mutable, and the only door into it. */
    @Accessor("levels")
    Map<ResourceKey<Level>, ServerLevel> getLevels();

    /** The executor every {@code ServerChunkCache} in this server already uses. */
    @Accessor("executor")
    Executor getExecutor();

    /** This world's directory handle, and the holder of its lock. */
    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess getStorageSource();
}
