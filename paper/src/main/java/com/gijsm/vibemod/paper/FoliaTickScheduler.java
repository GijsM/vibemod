package com.gijsm.vibemod.paper;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import com.gijsm.vibemod.platform.TickScheduler;

/**
 * {@link TickScheduler} over Folia's <em>global region</em> scheduler.
 *
 * <p>On Folia {@code Bukkit.getScheduler()} is not merely deprecated, it throws:
 * {@code CraftScheduler.handle} raises {@link UnsupportedOperationException}.
 * That is not a hypothetical — with nothing but {@code folia-supported: true}
 * added to {@code plugin.yml}, VibeMod enables cleanly on Folia 26.2 and then
 * dies in {@code applyStoredVersion} on its <em>own</em> async compile hop,
 * before a single generated mod has run. This class is what makes that path
 * exist at all.
 *
 * <h2>Global region only — and what that costs</h2>
 *
 * <p>Folia offers four schedulers: global-region, per-region, per-entity and
 * async. This class uses exactly two of them: the global region scheduler for
 * everything a mod would call "the main thread", and the async scheduler for
 * off-thread work. It deliberately does <strong>not</strong> use the region or
 * entity schedulers.
 *
 * <p>That is a real, deliberate loss. The global region is a single thread that
 * ticks the parts of the server not owned by any region; funnelling every
 * generated mod onto it forgoes Folia's per-region parallelism, <em>which is
 * most of the reason anyone runs Folia</em>. What it buys is the one property
 * the whole design rests on: a single ordering domain, so a generated mod that
 * was written for Paper's one-thread world behaves the same here. Per-region
 * scheduling would require every mod to know which region owns each entity and
 * block it touches, and to re-acquire that ownership after every hop — a
 * contract no LLM-generated mod is going to honour and no watchdog here can
 * check.
 *
 * <h2>Tick semantics</h2>
 *
 * <p>Two behavioural differences from Bukkit, both handled here rather than
 * pushed onto callers:
 *
 * <ul>
 *   <li>Bukkit accepts a delay or period of {@code 0}; Folia rejects anything
 *       below {@code 1} with {@link IllegalArgumentException}. Both platforms
 *       mean "next tick" by a zero delay, so {@link #atLeastOneTick} clamps.
 *   <li>Folia's callbacks take a {@link ScheduledTask} argument. Callers here
 *       pass a plain {@link Runnable}, so the task argument is dropped — the
 *       corpus cancels through the returned handle, never through the callback
 *       parameter.
 * </ul>
 *
 * @see FoliaTaskHandle for the {@code BukkitTask} adapter
 */
public final class FoliaTickScheduler implements BukkitTaskScheduler {

    /**
     * Synthetic ids for {@link BukkitTask#getTaskId()}. Folia's
     * {@link ScheduledTask} has no id of its own, so one is minted here.
     * Verified safe: across all 49 stored mods (569 sources), {@code getTaskId()}
     * is called exactly zero times — {@code cancel()} is the only
     * {@code BukkitTask} member the corpus ever consumes.
     */
    private static final AtomicInteger TASK_IDS = new AtomicInteger();

    private final Plugin plugin;

    public FoliaTickScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public FoliaTaskHandle repeat(long delayTicks, long periodTicks, Runnable task) {
        return new FoliaTaskHandle(
                plugin,
                Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                        plugin, ignored -> task.run(),
                        atLeastOneTick(delayTicks), atLeastOneTick(periodTicks)),
                true);
    }

    @Override
    public FoliaTaskHandle later(long delayTicks, Runnable task) {
        return new FoliaTaskHandle(
                plugin,
                Bukkit.getGlobalRegionScheduler().runDelayed(
                        plugin, ignored -> task.run(), atLeastOneTick(delayTicks)),
                true);
    }

    @Override
    public FoliaTaskHandle async(Runnable task) {
        return new FoliaTaskHandle(
                plugin,
                Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run()),
                false);
    }

    /**
     * Hops to the global region thread — or drops the work when there is no
     * longer one to hop to.
     *
     * <p>Mirrors {@link PaperTickScheduler#runOnMain} exactly, including the
     * shutdown-race swallow. An async compile that lands during shutdown has
     * nowhere to go and its result is a mod nobody will run; letting the refusal
     * propagate turns a normal shutdown into a console stack trace that reads
     * like a bug. Folia signals a disabled plugin with a different exception
     * type than Bukkit's {@code IllegalPluginAccessException}, so this catches
     * broadly rather than naming one.
     */
    @Override
    public void runOnMain(Runnable task) {
        if (onMain()) {
            task.run();
            return;
        }
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
        } catch (Throwable disabled) {
            plugin.getLogger().log(Level.FINE,
                    "Dropped a global-region hop: the plugin is no longer enabled", disabled);
        }
    }

    /**
     * True on the global region thread specifically — <em>not</em>
     * {@code Bukkit.isPrimaryThread()}.
     *
     * <p>This distinction is the whole point. On Folia
     * {@code isPrimaryThread()} answers "am I on <em>a</em> tick thread", which
     * is true on every one of the many region threads. Using it here would let
     * {@link #runOnMain} run its task inline on whichever region thread happened
     * to call, which is exactly the silent cross-region race this class exists
     * to prevent. {@code isGlobalTickThread()} answers the question we actually
     * mean: am I on the one thread this scheduler serialises everything onto.
     */
    @Override
    public boolean onMain() {
        return Bukkit.isGlobalTickThread();
    }

    /** Bukkit's "0 means next tick" expressed as Folia's minimum of 1. */
    private static long atLeastOneTick(long ticks) {
        return Math.max(1L, ticks);
    }

    /**
     * A Folia {@link ScheduledTask} presented as both a {@link BukkitTaskHandle}
     * and a {@link BukkitTask}.
     *
     * <p>{@link BukkitTask} is a five-method interface — {@code getTaskId},
     * {@code getOwner}, {@code isSync}, {@code isCancelled}, {@code cancel}
     * (verified with {@code javap} against paper-api 1.21.8) — and Folia ships
     * the full Bukkit API, so the interface itself is present and implementable.
     * Only {@code Bukkit.getScheduler()} is missing. Four of the five map onto
     * {@link ScheduledTask} directly or near enough; {@code getTaskId} has no
     * counterpart and is synthesised.
     */
    public static final class FoliaTaskHandle implements BukkitTaskHandle, BukkitTask {

        private final Plugin plugin;
        private final ScheduledTask task;
        private final boolean sync;
        private final int taskId = TASK_IDS.incrementAndGet();

        private FoliaTaskHandle(Plugin plugin, ScheduledTask task, boolean sync) {
            this.plugin = plugin;
            this.task = task;
            this.sync = sync;
        }

        /** This object is its own {@code BukkitTask}; no second allocation. */
        @Override
        public BukkitTask task() {
            return this;
        }

        // ---- BukkitTask ----

        /** Synthetic and monotonic. Nothing in the stored corpus reads it. */
        @Override
        public int getTaskId() {
            return taskId;
        }

        @Override
        public Plugin getOwner() {
            return plugin;
        }

        /**
         * True for global-region work, false for async — the same distinction
         * Bukkit draws, applied to the two Folia schedulers this class uses.
         */
        @Override
        public boolean isSync() {
            return sync;
        }

        @Override
        public boolean isCancelled() {
            return task.isCancelled();
        }

        /**
         * Folia's {@code cancel()} returns a {@code CancelledState} describing
         * what it managed to stop; {@link BukkitTask#cancel()} returns void. The
         * state is discarded, which is exactly Bukkit's own semantics: cancelling
         * an already-finished or already-cancelled task is a silent no-op there
         * too.
         */
        @Override
        public void cancel() {
            task.cancel();
        }

        // ---- TaskHandle ----

        @Override
        public void close() {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // close() never throws
            }
        }

        @Override
        public boolean active() {
            return !task.isCancelled();
        }
    }
}
