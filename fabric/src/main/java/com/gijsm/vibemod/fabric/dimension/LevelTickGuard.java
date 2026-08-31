package com.gijsm.vibemod.fabric.dimension;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.server.level.ServerLevel;

/**
 * The body of {@code MinecraftServerTickLevelsMixin}'s redirect, kept out of the
 * mixin so that it can hold state and be read from ordinary code (V4 Phase 6).
 *
 * <p>Two jobs, and the second is the interesting one.
 *
 * <p><b>Snapshot.</b> {@code MinecraftServer.tickChildren} iterates
 * {@code getAllLevels()}, which is a live view of the {@code levels} map. Copying
 * it before the walk is what makes {@link DimensionSeam} legal to call from
 * inside a level tick — which is where generated code almost always runs.
 *
 * <p><b>Proof of application.</b> The redirect is {@code require = 0}, so a
 * version bump that moves the call site disables it silently. That is exactly the
 * failure mode §10's "a mixin that quietly stops applying" entry exists to
 * refuse, so this class counts: until {@link #snapshot} has actually run at least
 * once, {@link #isArmed()} is false and {@code DimensionSeam.open} refuses,
 * naming the mixin. One tick of a running server is enough to arm it, so on a
 * healthy build the refusal is unreachable — it fires only on the version where
 * the redirect really has stopped applying, which is precisely when a
 * {@code ConcurrentModificationException} would otherwise be waiting.
 *
 * <p><b>Cost.</b> One {@code ArrayList} of a handful of references per server
 * tick, on the server thread, allocated next to the several thousand objects a
 * single chunk tick makes. Vanilla itself copies more than this in
 * {@code tickChildren}'s own {@code getPlayers().forEach}. Sizing the copy from
 * {@link java.util.Collection#size()} when the {@code Iterable} is one — it
 * always is; {@code levels.values()} — keeps it to a single array.
 *
 * <p>Process-lived statics rather than per-server state, because the mixin has
 * nowhere to reach an instance from and because "did this redirect apply" is a
 * property of the JVM's loaded classes, not of any one world.
 */
public final class LevelTickGuard {

    /** Flipped by the first redirected tick. Never flipped back. */
    private static volatile boolean armed;
    private static volatile long snapshots;

    private LevelTickGuard() {
    }

    /**
     * Called from the redirect, once per server tick.
     *
     * @param levels the live {@code levels.values()} view vanilla was about to walk
     * @return an iterator over a copy of it
     */
    public static Iterator<ServerLevel> snapshot(Iterable<ServerLevel> levels) {
        armed = true;
        snapshots++;
        if (levels instanceof java.util.Collection<ServerLevel> collection) {
            return new ArrayList<>(collection).iterator();
        }
        List<ServerLevel> copy = new ArrayList<>();
        for (ServerLevel level : levels) {
            copy.add(level);
        }
        return copy.iterator();
    }

    /**
     * True once the redirect has demonstrably run.
     *
     * <p>False means one of two things and they are worth telling apart: the
     * server has not ticked yet, or the redirect did not apply. Both are equally
     * unsafe to open a dimension under, so {@link DimensionSeam} treats them the
     * same and says so.
     */
    public static boolean isArmed() {
        return armed;
    }

    /** For the gates: {@code "levelTickGuard=armed snapshots=1200"}. */
    public static String describeState() {
        return "levelTickGuard=" + (armed ? "armed" : "unproven") + " levelSnapshots=" + snapshots;
    }
}
