package com.gijsm.vibemod.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.gijsm.vibemod.platform.TickScheduler;

/**
 * Per-mod error journal. Deduplicates repeated exceptions by root-cause class
 * plus top offending frame, tracks "degrade episodes" (has this mod had a
 * fresh, un-cleared failure since it was last loaded/fixed?), trips a
 * once-per-episode storm alarm when errors arrive too fast in a rolling
 * window (the same pattern as {@link Watchdog}'s window), and persists a
 * small rolling history per mod to {@code <modsDir>/<Name>/errors.json}
 * (Gson, pretty, lazily loaded, dirty-flag + debounced async flush).
 *
 * All public methods are safe to call from any thread. {@link #onStorm}
 * callbacks are always delivered via the injected main-thread hop so callers
 * can safely touch Bukkit state (disabling the mod, broadcasting) from them.
 */
public final class ModErrors {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String NO_STACK = "(no stack)";
    private static final String SYNTHETIC_FRAME = "(synthetic)";

    private final Path modsDir;
    private final Consumer<Runnable> mainThread;
    private final Consumer<Runnable> async;
    private final ConcurrentHashMap<String, ModErrorState> states = new ConcurrentHashMap<>();

    private volatile int stormThreshold = 10;
    private volatile long stormWindowSeconds = 60;
    private volatile int maxDistinct = 25;
    private volatile int stackFrames = 10;
    private volatile Consumer<String> onStorm;

    /** Wires the host's main-thread hop and a ~100-tick-debounced async flush. */
    public ModErrors(TickScheduler scheduler, Path modsDir) {
        this(modsDir,
                scheduler::runOnMain,
                r -> scheduler.later(100L, () -> scheduler.async(r)));
    }

    /** Test-only: no scheduler involved; both hooks run their {@link Runnable} inline. */
    ModErrors(Path modsDir, Consumer<Runnable> mainThread, Consumer<Runnable> async) {
        this.modsDir = modsDir;
        this.mainThread = mainThread;
        this.async = async;
    }

    /** One deduplicated error: {@code count} occurrences of the same root cause since {@code firstSeen}. */
    public record ErrorRecord(String exceptionClass, String message, String topFrame, String where,
                               List<String> stack, int count, long firstSeen, long lastSeen) {
    }

    /** Result of {@link #record}: whether this call started a fresh degrade episode, and whether it tripped a storm. */
    public record Outcome(boolean firstOfEpisode, boolean stormTripped) {
    }

    /**
     * Replaces the storm/cap/truncation limits used by subsequent calls.
     * Same plain-reassignment semantics as {@link Watchdog#setBudgets}.
     */
    public void setLimits(int stormThreshold, long stormWindowSeconds, int maxDistinct, int stackFrames) {
        this.stormThreshold = stormThreshold;
        this.stormWindowSeconds = stormWindowSeconds;
        this.maxDistinct = maxDistinct;
        this.stackFrames = stackFrames;
    }

    /** Registers the handler fired (once per episode, on the main thread, next tick) when a mod's errors storm. */
    public void onStorm(Consumer<String> handler) {
        this.onStorm = handler;
    }

    /**
     * Records a caught exception for {@code mod}, deduplicating by root-cause
     * class + top frame, and advances the mod's degrade episode and rolling
     * storm window.
     */
    public Outcome record(String mod, Throwable t, String where) {
        Objects.requireNonNull(t, "t");
        upsertFromThrowable(mod, t, where);
        return advanceEpisode(mod);
    }

    /** Records a caught exception without affecting episode/storm state (lifecycle notes, watchdog trips). */
    public void note(String mod, Throwable t, String where) {
        Objects.requireNonNull(t, "t");
        upsertFromThrowable(mod, t, where);
    }

    /** Records a synthetic (no {@link Throwable}) note without affecting episode/storm state. */
    public void note(String mod, String exceptionClass, String message, String where) {
        upsert(mod, exceptionClass, message == null ? "" : message, SYNTHETIC_FRAME, List.of(), where);
    }

    /** Ends the current degrade episode: the next {@link #record} call will report {@code firstOfEpisode}. */
    public void clearEpisode(String mod) {
        ModErrorState state = stateFor(mod);
        synchronized (state) {
            state.episodeActive = false;
            state.stormFired = false;
            state.windowCount = 0;
            state.windowStartNanos = System.nanoTime();
        }
    }

    /** This mod's distinct error records, most recently seen first. */
    public List<ErrorRecord> recent(String mod) {
        ModErrorState state = stateFor(mod);
        synchronized (state) {
            List<ErrorRecord> list = new ArrayList<>(state.records.values());
            list.sort(Comparator.comparingLong(ErrorRecord::lastSeen).reversed());
            return List.copyOf(list);
        }
    }

    /** Number of distinct (deduplicated) error records currently held for this mod. */
    public int distinctCount(String mod) {
        ModErrorState state = stateFor(mod);
        synchronized (state) {
            return state.records.size();
        }
    }

    /**
     * Human-readable report of the {@code maxDistinct} most recently seen
     * records, for chat/book display or as the basis of a fix prompt.
     */
    public String report(String mod, int maxDistinct) {
        List<ErrorRecord> list = recent(mod);
        if (list.size() > maxDistinct) {
            list = list.subList(0, maxDistinct);
        }
        StringBuilder sb = new StringBuilder("== ").append(mod).append(" errors ==\n");
        for (ErrorRecord r : list) {
            sb.append(r.count()).append("× ").append(r.exceptionClass());
            if (!r.message().isEmpty()) {
                sb.append(": ").append(r.message());
            }
            sb.append('\n');
            sb.append("  at ").append(r.topFrame()).append(" (").append(r.where())
                    .append(", last ").append(relativeTime(r.lastSeen())).append(")\n");
            for (String line : r.stack()) {
                sb.append("    ").append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** Drops all in-memory state for a mod, flushing first if dirty. Does not delete {@code errors.json}. */
    public void forget(String mod) {
        ModErrorState state = states.remove(lower(mod));
        if (state != null) {
            synchronized (state) {
                if (state.dirty) {
                    writeToDisk(state);
                    state.dirty = false;
                }
            }
        }
    }

    /** Synchronously writes every mod's pending (dirty) records to disk. */
    public void flush() {
        for (ModErrorState state : states.values()) {
            synchronized (state) {
                if (state.dirty) {
                    writeToDisk(state);
                    state.dirty = false;
                }
            }
        }
    }

    // ---------------------------------------------------------------- internals

    private void upsertFromThrowable(String mod, Throwable t, String where) {
        Throwable root = rootCause(t);
        String exceptionClass = root.getClass().getName();
        String message = root.getMessage() == null ? "" : root.getMessage();
        StackTraceElement[] frames = root.getStackTrace();
        String topFrame = computeTopFrame(frames);
        List<String> stack = truncateStack(frames, stackFrames);
        upsert(mod, exceptionClass, message, topFrame, stack, where);
    }

    private void upsert(String mod, String exceptionClass, String message, String topFrame,
                         List<String> stack, String where) {
        ModErrorState state = stateFor(mod);
        String key = exceptionClass + "\u0001" + topFrame;
        long now = System.currentTimeMillis();
        synchronized (state) {
            ErrorRecord existing = state.records.get(key);
            ErrorRecord updated;
            if (existing == null) {
                updated = new ErrorRecord(exceptionClass, message, topFrame, where, stack, 1, now, now);
                state.records.put(key, updated);
                evictIfNeeded(state);
            } else {
                updated = new ErrorRecord(exceptionClass, message, topFrame, where, stack,
                        existing.count() + 1, existing.firstSeen(), now);
                state.records.put(key, updated);
            }
            markDirty(state);
        }
    }

    private Outcome advanceEpisode(String mod) {
        ModErrorState state = stateFor(mod);
        boolean firstOfEpisode;
        boolean justTripped;
        synchronized (state) {
            firstOfEpisode = !state.episodeActive;
            state.episodeActive = true;

            long now = System.nanoTime();
            long windowNanos = Math.max(1L, stormWindowSeconds) * 1_000_000_000L;
            if (now - state.windowStartNanos > windowNanos) {
                state.windowStartNanos = now;
                state.windowCount = 0;
            }
            state.windowCount++;

            justTripped = false;
            if (!state.stormFired && state.windowCount >= stormThreshold) {
                state.stormFired = true;
                justTripped = true;
            }
        }
        if (justTripped) {
            Consumer<String> handler = onStorm;
            if (handler != null) {
                mainThread.accept(() -> handler.accept(mod));
            }
        }
        return new Outcome(firstOfEpisode, justTripped);
    }

    /** Evicts the record with the oldest {@code lastSeen} (ties broken by earliest insertion) if over the cap. */
    private void evictIfNeeded(ModErrorState state) {
        if (state.records.size() <= maxDistinct) {
            return;
        }
        String oldestKey = null;
        long oldestSeen = Long.MAX_VALUE;
        for (Map.Entry<String, ErrorRecord> e : state.records.entrySet()) {
            if (e.getValue().lastSeen() < oldestSeen) {
                oldestSeen = e.getValue().lastSeen();
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null) {
            state.records.remove(oldestKey);
        }
    }

    /** Marks a mod's state dirty and, unless a flush is already pending, schedules one on the async scheduler. */
    private void markDirty(ModErrorState state) {
        state.dirty = true;
        if (state.flushScheduled.compareAndSet(false, true)) {
            async.accept(() -> {
                state.flushScheduled.set(false);
                synchronized (state) {
                    if (state.dirty) {
                        writeToDisk(state);
                        state.dirty = false;
                    }
                }
            });
        }
    }

    private ModErrorState stateFor(String mod) {
        return states.computeIfAbsent(lower(mod), k -> loadFromDisk(mod));
    }

    private ModErrorState loadFromDisk(String mod) {
        ModErrorState state = new ModErrorState(mod);
        Path file = modsDir.resolve(mod).resolve("errors.json");
        if (Files.isRegularFile(file)) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                Persisted persisted = GSON.fromJson(json, Persisted.class);
                if (persisted != null && persisted.records != null) {
                    for (ErrorRecord r : persisted.records) {
                        if (r == null) {
                            continue;
                        }
                        state.records.put(r.exceptionClass() + "\u0001" + r.topFrame(), r);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return state;
    }

    private void writeToDisk(ModErrorState state) {
        try {
            Path dir = modsDir.resolve(state.mod);
            Files.createDirectories(dir);
            Persisted persisted = new Persisted(new ArrayList<>(state.records.values()));
            Files.writeString(dir.resolve("errors.json"), GSON.toJson(persisted), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }

    private static String computeTopFrame(StackTraceElement[] frames) {
        if (frames == null || frames.length == 0) {
            return NO_STACK;
        }
        for (StackTraceElement f : frames) {
            if (f.getClassName().startsWith("vibemod.")) {
                return formatFrame(f);
            }
        }
        return formatFrame(frames[0]);
    }

    /** Truncates to at most {@code limit} frames, keeping every {@code vibemod.*} frame first, marking gaps. */
    private static List<String> truncateStack(StackTraceElement[] frames, int limit) {
        if (frames == null || frames.length == 0) {
            return List.of();
        }
        int cap = Math.max(1, limit);
        if (frames.length <= cap) {
            List<String> out = new ArrayList<>(frames.length);
            for (StackTraceElement f : frames) {
                out.add(formatFrame(f));
            }
            return out;
        }
        TreeSet<Integer> keep = new TreeSet<>();
        for (int i = 0; i < frames.length && keep.size() < cap; i++) {
            if (frames[i].getClassName().startsWith("vibemod.")) {
                keep.add(i);
            }
        }
        for (int i = 0; i < frames.length && keep.size() < cap; i++) {
            keep.add(i);
        }
        List<String> out = new ArrayList<>();
        int prev = -1;
        for (int idx : keep) {
            if (idx > prev + 1) {
                out.add("… " + (idx - prev - 1) + " skipped");
            }
            out.add(formatFrame(frames[idx]));
            prev = idx;
        }
        if (prev < frames.length - 1) {
            out.add("… " + (frames.length - 1 - prev) + " skipped");
        }
        return out;
    }

    private static String formatFrame(StackTraceElement f) {
        String file = f.getFileName() == null ? "Unknown" : f.getFileName();
        return f.getClassName() + "." + f.getMethodName() + "(" + file + ":" + f.getLineNumber() + ")";
    }

    /** Relative-time label ("just now" / "5m ago" / ...) for a {@code lastSeen}-style epoch-millis timestamp. */
    static String relativeTime(long epochMillis) {
        long deltaMs = Math.max(0, System.currentTimeMillis() - epochMillis);
        long seconds = deltaMs / 1000L;
        if (seconds < 5) {
            return "just now";
        }
        if (seconds < 60) {
            return seconds + "s ago";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h ago";
        }
        long days = hours / 24;
        return days + "d ago";
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /** Disk shape: {@code {"records": [...]}}. */
    private record Persisted(List<ErrorRecord> records) {
    }

    /** Per-mod in-memory state: the deduplicated record map plus episode/storm/flush bookkeeping. */
    private static final class ModErrorState {
        final String mod;
        final LinkedHashMap<String, ErrorRecord> records = new LinkedHashMap<>();
        final AtomicBoolean flushScheduled = new AtomicBoolean(false);
        boolean episodeActive;
        boolean stormFired;
        long windowStartNanos = System.nanoTime();
        long windowCount;
        volatile boolean dirty;

        ModErrorState(String mod) {
            this.mod = mod;
        }
    }
}
