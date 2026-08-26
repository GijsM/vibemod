package com.gijsm.vibemod.fabric.mixin;

import java.util.Iterator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import com.gijsm.vibemod.fabric.dimension.LevelTickGuard;

/**
 * The one injection V4 Phase 6 needs, and the reason a dimension can be created
 * or removed while the server is mid-tick.
 *
 * <p>{@code tickChildren} walks the level map with a plain iterator. From the
 * 26.2 disassembly, at offset 118:
 *
 * <pre>
 * 117: aload_0
 * 118: invokevirtual  getAllLevels:()Ljava/lang/Iterable;
 * 121: invokeinterface java/lang/Iterable.iterator:()Ljava/util/Iterator;
 * ...
 * 172: invokevirtual  net/minecraft/server/level/ServerLevel.tick:(…)V
 * </pre>
 *
 * <p>{@code getAllLevels()} returns {@code levels.values()}, a live view of the
 * {@code HashMap} {@code MinecraftServer.levels}. A generated mod that opens or
 * closes a dimension from inside any {@code ServerLevel.tick} — a block tick, an
 * entity tick, a scheduled task, which is most of when generated code runs — is
 * therefore writing to the map that the loop above is walking, and the next
 * {@code hasNext()} throws {@code ConcurrentModificationException} out of vanilla
 * with a stack trace naming nothing of ours.
 *
 * <p>Redirecting the {@code iterator()} call to a snapshot fixes it in the only
 * place it can be fixed: a level added mid-tick simply starts ticking next tick,
 * and a level removed mid-tick gets one last harmless tick against an object
 * nothing else references any more. This is the same fix, at the same call site,
 * that NucleoidMC's Fantasy has shipped for years.
 *
 * <p>Note the two iterator calls in this method are distinguishable by owner:
 * this one is {@code Iterable.iterator}, and the {@code tickables} walk further
 * down is {@code List.iterator}. The target descriptor picks the first and only
 * the first, so no ordinal is needed and none is given — an ordinal would be a
 * silent liability the day a version reorders the method.
 *
 * <p><b>{@code require = 0}, deliberately.</b> This is a redirect into a hot
 * vanilla method whose shape is not ours to guarantee; a version that inlines the
 * walk into an enhanced-for over an array, or hands it to {@code forEach}, moves
 * the call site without warning. A startup crash for the whole host is the wrong
 * response to that, so the failure is made visible instead of fatal:
 * {@link LevelTickGuard} counts invocations, reports {@code levelTickGuard=armed}
 * once it has actually run, and {@code DimensionSeam} refuses to open a dimension
 * while it still says {@code unproven}. Fantasy uses {@code require = 0} here for
 * the same reason and then simply hopes; this one refuses instead. No silent
 * drops.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerTickLevelsMixin {

    @Redirect(
            method = "tickChildren(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/Iterable;iterator()Ljava/util/Iterator;"),
            require = 0)
    private Iterator<ServerLevel> vibemod$snapshotLevels(Iterable<ServerLevel> levels) {
        return LevelTickGuard.snapshot(levels);
    }
}
