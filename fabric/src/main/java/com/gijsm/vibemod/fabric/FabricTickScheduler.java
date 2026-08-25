package com.gijsm.vibemod.fabric;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.minecraft.server.MinecraftServer;

import com.gijsm.vibemod.platform.TaskHandle;
import com.gijsm.vibemod.platform.TickScheduler;

/**
 * {@link TickScheduler} over the server tick.
 *
 * <p>Fabric has no scheduler, so this is one: a list of tasks drained on
 * {@code ServerTickEvents.END_SERVER_TICK} (the host registers that once and
 * calls {@link #tick()}), plus a small pool for the off-thread work — LLM calls
 * and compilation — that {@code ModGenerator} runs.
 *
 * <p>Two details are load-bearing. Ticks are counted here rather than read from
 * the server, so delays stay correct across a world change. And the drain
 * iterates a snapshot: a task that schedules another task — which the generation
 * pipeline does constantly — would otherwise mutate the list mid-iteration.
 */
public final class FabricTickScheduler implements TickScheduler {

    private static final Logger LOG = Logger.getLogger(FabricTickScheduler.class.getName());

    private final MinecraftServer server;
    private final ExecutorService async;
    private final List<Scheduled> tasks = new ArrayList<>();
    private volatile long tickCount;

    public FabricTickScheduler(MinecraftServer server) {
        this.server = server;
        this.async = Executors.newCachedThreadPool(runnable -> {
            Thread t = new Thread(runnable, "VibeMod-async");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * One server tick: advance the clock and run everything due. Called by the
     * host's single {@code END_SERVER_TICK} subscription. Never throws — a task
     * that fails must not take the tick with it.
     */
    public void tick() {
        long now = ++tickCount;
        List<Scheduled> due = null;
        synchronized (tasks) {
            for (int i = tasks.size() - 1; i >= 0; i--) {
                Scheduled task = tasks.get(i);
                if (!task.active()) {
                    tasks.remove(i);
                    continue;
                }
                if (now >= task.nextRun) {
                    if (due == null) {
                        due = new ArrayList<>();
                    }
                    due.add(task);
                    if (task.period > 0) {
                        task.nextRun = now + task.period;
                    } else {
                        task.close();
                        tasks.remove(i);
                    }
                }
            }
        }
        if (due == null) {
            return;
        }
        for (Scheduled task : due) {
            try {
                task.body.run();
            } catch (Throwable t) {
                // Mod code is already wrapped by ModDispatch; this catches the
                // host's own scheduled work so one bad task cannot stop the tick.
                LOG.log(Level.WARNING, "A scheduled VibeMod task threw", t);
            }
        }
    }

    @Override
    public TaskHandle repeat(long delayTicks, long periodTicks, Runnable task) {
        return schedule(Math.max(0, delayTicks), Math.max(1, periodTicks), task);
    }

    @Override
    public TaskHandle later(long delayTicks, Runnable task) {
        return schedule(Math.max(0, delayTicks), 0, task);
    }

    @Override
    public TaskHandle async(Runnable task) {
        Future<?> future = async.submit(task);
        return new TaskHandle() {
            @Override
            public void close() {
                future.cancel(false);
            }

            @Override
            public boolean active() {
                return !future.isDone();
            }
        };
    }

    /**
     * Hops to the server thread — or drops the work when there is no longer a
     * server thread to hop to.
     *
     * <p>{@code executeIfPossible} rather than {@code execute}: an async compile
     * that lands during shutdown legitimately has nowhere to go, and its result
     * is a mod nobody is going to run. {@code execute} on a stopped server throws
     * or silently queues forever; this drops it, which is the honest outcome.
     */
    @Override
    public void runOnMain(Runnable task) {
        if (onMain()) {
            task.run();
            return;
        }
        try {
            server.executeIfPossible(task);
        } catch (Throwable stopping) {
            LOG.log(Level.FINE, "Dropped a main-thread hop: the server is shutting down", stopping);
        }
    }

    @Override
    public boolean onMain() {
        return server.isSameThread();
    }

    /** Cancels every scheduled task and stops the async pool. Called at server stop. */
    public void shutdown() {
        synchronized (tasks) {
            for (Scheduled task : tasks) {
                task.close();
            }
            tasks.clear();
        }
        async.shutdownNow();
    }

    private TaskHandle schedule(long delayTicks, long periodTicks, Runnable body) {
        Scheduled task = new Scheduled(tickCount + delayTicks, periodTicks, body);
        synchronized (tasks) {
            tasks.add(task);
        }
        return task;
    }

    /** One scheduled task; {@code period == 0} means one-shot. */
    private static final class Scheduled implements TaskHandle {

        private final AtomicBoolean open = new AtomicBoolean(true);
        private final long period;
        private final Runnable body;
        private long nextRun;

        Scheduled(long nextRun, long period, Runnable body) {
            this.nextRun = nextRun;
            this.period = period;
            this.body = body;
        }

        @Override
        public void close() {
            open.set(false);
        }

        @Override
        public boolean active() {
            return open.get();
        }
    }
}
