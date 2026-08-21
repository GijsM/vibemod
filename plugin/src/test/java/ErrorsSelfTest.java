package com.gijsm.vibemine.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone self-test (no test framework, no Bukkit on the classpath)
 * proving {@link ModErrors}: dedup by root-cause class + top frame, distinct
 * records for distinct frames, degrade-episode transitions, once-per-episode
 * storm tripping, rolling-window expiry, max-distinct cap eviction (oldest
 * {@code lastSeen} first), disk persistence round-trip, {@code report()}
 * formatting, and vibemod-frame-priority stack truncation.
 *
 * Declared in {@code com.gijsm.vibemine.runtime} (even though the file lives
 * under {@code src/test/java}, not a matching subdirectory) so it can reach
 * the package-private, Bukkit-free test constructor
 * {@code ModErrors(Path, Consumer<Runnable>, Consumer<Runnable>)}.
 */
public class ErrorsSelfTest {

    private static final String SCRATCH_ROOT =
            "/private/tmp/claude-501/-Users-gijsmulder-projects-vibemine/28576c55-1a45-4088-81bb-f47d2e6ed714/scratchpad/errors-selftest";

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of(SCRATCH_ROOT));

        testDedupSameClassAndFrameMerges();
        testDistinctRecordsForDifferentFrames();
        testEpisodeTransitions();
        testStormTripsOnceWithinWindow();
        testWindowExpiryResetsCounting();
        testCapEvictionOldestFirst();
        testPersistenceRoundTrip();
        testReportFormatSanity();
        testStackTruncationVibemodPriority();

        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- tests

    private static void testDedupSameClassAndFrameMerges() throws Exception {
        ModErrors errors = newErrors("dedup");

        Throwable t1 = withFrames(new IllegalStateException("boom 1"),
                frame("vibemod.thing.Widget", "tick", "Widget.java", 10));
        Throwable t2 = withFrames(new IllegalStateException("boom 2"),
                frame("vibemod.thing.Widget", "tick", "Widget.java", 10));

        errors.record("Dedup", t1, "task");
        check("first record: distinctCount == 1", errors.distinctCount("Dedup") == 1);
        ModErrors.ErrorRecord r1 = errors.recent("Dedup").get(0);
        check("first record: count == 1", r1.count() == 1);
        long firstSeen = r1.firstSeen();
        long lastSeen1 = r1.lastSeen();

        Thread.sleep(5);
        errors.record("Dedup", t2, "task");
        check("second (same class+frame) record: still distinctCount == 1", errors.distinctCount("Dedup") == 1);
        ModErrors.ErrorRecord r2 = errors.recent("Dedup").get(0);
        check("dedup merges: count incremented to 2", r2.count() == 2);
        check("dedup merges: firstSeen unchanged", r2.firstSeen() == firstSeen);
        check("dedup merges: lastSeen advanced", r2.lastSeen() >= lastSeen1);
        check("dedup merges: message updated to the latest occurrence", "boom 2".equals(r2.message()));

        System.out.println("PASS: dedup by root-cause class + top frame merges repeats");
    }

    private static void testDistinctRecordsForDifferentFrames() throws Exception {
        ModErrors errors = newErrors("distinct");

        Throwable t1 = withFrames(new IllegalStateException("a"),
                frame("vibemod.thing.Widget", "tick", "Widget.java", 10));
        Throwable t2 = withFrames(new IllegalStateException("b"),
                frame("vibemod.thing.Widget", "explode", "Widget.java", 42));

        errors.record("Distinct", t1, "task");
        errors.record("Distinct", t2, "task");
        check("different top frame, same exception class -> 2 distinct records", errors.distinctCount("Distinct") == 2);

        Throwable t3 = withFrames(new NullPointerException("a"),
                frame("vibemod.thing.Widget", "tick", "Widget.java", 10));
        errors.record("Distinct", t3, "task");
        check("different exception class, same frame -> a 3rd distinct record", errors.distinctCount("Distinct") == 3);

        System.out.println("PASS: distinct root-cause class or top frame yields distinct records");
    }

    private static void testEpisodeTransitions() throws Exception {
        ModErrors errors = newErrors("episodes");
        Throwable t = withFrames(new RuntimeException("x"), frame("vibemod.a.B", "m", "B.java", 1));

        ModErrors.Outcome o1 = errors.record("Episode", t, "task");
        check("first ever record is firstOfEpisode", o1.firstOfEpisode());

        ModErrors.Outcome o2 = errors.record("Episode", t, "task");
        check("second record in the same episode is NOT firstOfEpisode", !o2.firstOfEpisode());

        errors.clearEpisode("Episode");
        ModErrors.Outcome o3 = errors.record("Episode", t, "task");
        check("record after clearEpisode() is firstOfEpisode again", o3.firstOfEpisode());

        System.out.println("PASS: degrade-episode transitions (first/repeat/cleared)");
    }

    private static void testStormTripsOnceWithinWindow() throws Exception {
        ModErrors errors = newErrors("storm");
        errors.setLimits(3, 60L, 25, 10);
        java.util.concurrent.atomic.AtomicInteger stormCalls = new java.util.concurrent.atomic.AtomicInteger();
        errors.onStorm(mod -> stormCalls.incrementAndGet());

        Throwable t = withFrames(new RuntimeException("storm"), frame("vibemod.a.B", "m", "B.java", 1));
        ModErrors.Outcome o1 = errors.record("Storm", t, "task");
        ModErrors.Outcome o2 = errors.record("Storm", t, "task");
        ModErrors.Outcome o3 = errors.record("Storm", t, "task");
        ModErrors.Outcome o4 = errors.record("Storm", t, "task");

        check("call 1 does not trip (below threshold)", !o1.stormTripped());
        check("call 2 does not trip (below threshold)", !o2.stormTripped());
        check("call 3 trips at the threshold", o3.stormTripped());
        check("call 4 (same episode) does NOT re-trip", !o4.stormTripped());
        check("onStorm handler fired exactly once", stormCalls.get() == 1);

        System.out.println("PASS: storm trips exactly once per episode at the threshold");
    }

    private static void testWindowExpiryResetsCounting() throws Exception {
        ModErrors errors = newErrors("window-expiry");
        errors.setLimits(3, 1L, 25, 10); // threshold 3, 1-second window
        Throwable t = withFrames(new RuntimeException("w"), frame("vibemod.a.B", "m", "B.java", 1));

        errors.record("WindowExpiry", t, "task");
        errors.record("WindowExpiry", t, "task");
        // windowCount == 2, one more within this window would trip. Let the window expire instead.
        Thread.sleep(1100);

        ModErrors.Outcome afterExpiry = errors.record("WindowExpiry", t, "task");
        check("a record just after window expiry does not trip (count reset by the new window)",
                !afterExpiry.stormTripped());

        ModErrors.Outcome next = errors.record("WindowExpiry", t, "task");
        ModErrors.Outcome tripped = errors.record("WindowExpiry", t, "task");
        check("counting resumes from 1 in the new window: 2nd call after expiry doesn't trip", !next.stormTripped());
        check("counting resumes from 1 in the new window: 3rd call after expiry trips", tripped.stormTripped());

        System.out.println("PASS: rolling storm window expiry resets the count instead of accumulating forever");
    }

    private static void testCapEvictionOldestFirst() throws Exception {
        ModErrors errors = newErrors("cap-eviction");
        errors.setLimits(10, 60L, 25, 10); // maxDistinct defaults to 25 anyway; explicit for clarity

        for (int i = 0; i < 27; i++) {
            errors.note("Capped", "Ex" + i, "msg " + i, "where" + i);
        }
        check("27 distinct inserts capped down to 25", errors.distinctCount("Capped") == 25);

        List<ModErrors.ErrorRecord> recent = errors.recent("Capped");
        boolean hasEx0 = recent.stream().anyMatch(r -> r.exceptionClass().equals("Ex0"));
        boolean hasEx1 = recent.stream().anyMatch(r -> r.exceptionClass().equals("Ex1"));
        boolean hasEx2 = recent.stream().anyMatch(r -> r.exceptionClass().equals("Ex2"));
        boolean hasEx26 = recent.stream().anyMatch(r -> r.exceptionClass().equals("Ex26"));
        check("the two oldest-inserted records (Ex0, Ex1) were evicted", !hasEx0 && !hasEx1);
        check("newer records (Ex2..Ex26) survive", hasEx2 && hasEx26);

        System.out.println("PASS: max-distinct cap evicts the oldest (by lastSeen, ties broken by insertion order)");
    }

    private static void testPersistenceRoundTrip() throws Exception {
        Path dir = tempDir("persistence");
        ModErrors first = new ModErrors(dir, Runnable::run, Runnable::run);

        Throwable t1 = withFrames(new IllegalStateException("persisted 1"),
                frame("vibemod.p.Thing", "run", "Thing.java", 7));
        Throwable t2 = withFrames(new NullPointerException("persisted 2"),
                frame("vibemod.p.Other", "go", "Other.java", 3));
        first.record("Persisto", t1, "task");
        first.record("Persisto", t2, "action:zap");
        first.flush();

        check("errors.json was written to disk", Files.isRegularFile(dir.resolve("Persisto").resolve("errors.json")));

        ModErrors second = new ModErrors(dir, Runnable::run, Runnable::run);
        List<ModErrors.ErrorRecord> reloaded = second.recent("Persisto");
        check("a fresh instance reloads the same distinct count", reloaded.size() == 2);
        boolean hasFirst = reloaded.stream().anyMatch(r -> r.exceptionClass().equals("java.lang.IllegalStateException")
                && "persisted 1".equals(r.message()));
        boolean hasSecond = reloaded.stream().anyMatch(r -> r.exceptionClass().equals("java.lang.NullPointerException")
                && "persisted 2".equals(r.message()) && "action:zap".equals(r.where()));
        check("reloaded record 1 matches what was flushed", hasFirst);
        check("reloaded record 2 matches what was flushed", hasSecond);

        System.out.println("PASS: flush() -> new instance persistence round-trip via errors.json");
    }

    private static void testReportFormatSanity() throws Exception {
        ModErrors errors = newErrors("report");
        Throwable t = withFrames(new IllegalArgumentException("bad value"),
                frame("vibemod.r.Reporter", "check", "Reporter.java", 99));
        errors.record("Reporty", t, "action:check");
        errors.record("Reporty", t, "action:check");

        String report = errors.report("Reporty", 10);
        check("report has the mod header", report.contains("== Reporty errors =="));
        check("report shows the dedup count", report.contains("2× java.lang.IllegalArgumentException: bad value"));
        check("report shows the where + relative time", report.contains("(action:check, last "));
        check("report includes the top frame", report.contains("vibemod.r.Reporter.check(Reporter.java:99)"));

        System.out.println("PASS: report() format includes count/class/message/where/time/stack");
    }

    private static void testStackTruncationVibemodPriority() throws Exception {
        ModErrors errors = newErrors("truncation");
        errors.setLimits(10, 60L, 25, 6); // stackFrames = 6

        StackTraceElement[] frames = new StackTraceElement[15];
        for (int i = 0; i < 15; i++) {
            if (i == 5 || i == 9 || i == 13) {
                frames[i] = frame("vibemod.deep.Inner", "step" + i, "Inner.java", i);
            } else {
                frames[i] = frame("org.bukkit.Framework", "dispatch" + i, "Framework.java", i);
            }
        }
        Throwable t = new RuntimeException("deep");
        t.setStackTrace(frames);

        errors.record("Truncated", t, "listener:Test");
        List<String> stack = errors.recent("Truncated").get(0).stack();

        long vibemodLines = stack.stream().filter(l -> l.contains("vibemod.")).count();
        long skipMarkers = stack.stream().filter(l -> l.startsWith("… ") && l.endsWith(" skipped")).count();
        int totalSkipped = stack.stream()
                .filter(l -> l.startsWith("… ") && l.endsWith(" skipped"))
                .mapToInt(ErrorsSelfTest::skippedCount)
                .sum();

        check("all 3 vibemod frames are kept despite the 6-frame cap", vibemodLines == 3);
        check("gaps are marked with skip markers", skipMarkers >= 1);
        check("skipped counts add up to exactly the elided frames (15 - 6 = 9)", totalSkipped == 9);
        check("the kept vibemod frames stay in original relative order",
                indexOf(stack, "step5") < indexOf(stack, "step9")
                        && indexOf(stack, "step9") < indexOf(stack, "step13"));
        check("the earliest non-vibemod frames fill the remaining budget",
                stack.get(0).contains("dispatch0") && stack.get(1).contains("dispatch1"));
        check("topFrame is the first vibemod frame in original order",
                errors.recent("Truncated").get(0).topFrame().contains("step5"));

        System.out.println("PASS: stack truncation prioritizes vibemod.* frames and marks skipped gaps");
    }

    // ---------------------------------------------------------------- helpers

    private static final Pattern SKIP_PATTERN = Pattern.compile("… (\\d+) skipped");

    private static int skippedCount(String line) {
        Matcher m = SKIP_PATTERN.matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static int indexOf(List<String> list, String needle) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static StackTraceElement frame(String cls, String method, String file, int line) {
        return new StackTraceElement(cls, method, file, line);
    }

    private static Throwable withFrames(Throwable t, StackTraceElement... frames) {
        t.setStackTrace(frames);
        return t;
    }

    private static ModErrors newErrors(String label) throws Exception {
        return new ModErrors(tempDir(label), Runnable::run, Runnable::run);
    }

    private static Path tempDir(String prefix) throws Exception {
        return Files.createTempDirectory(Path.of(SCRATCH_ROOT), prefix + "-");
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            System.out.println("  ok: " + label);
        } else {
            System.out.println("  FAIL: " + label);
            failures++;
        }
    }
}
