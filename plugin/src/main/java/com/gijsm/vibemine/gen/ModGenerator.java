package com.gijsm.vibemine.gen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import com.gijsm.vibemine.compile.CompileResult;
import com.gijsm.vibemine.compile.InMemoryCompiler;
import com.gijsm.vibemine.llm.OpenRouterClient;
import com.gijsm.vibemine.llm.PromptLibrary;
import com.gijsm.vibemine.runtime.ModRegistry;
import com.gijsm.vibemine.store.ModStore;

/**
 * The heart of VibeMine: turns a natural-language prompt into a live mod.
 *
 * Pipeline (all off the main thread except the final load): ask the LLM for a
 * project, compile it in-process, and on javac errors or a failing enable feed
 * the diagnostics back to the model for a self-healing retry. Edit and repair
 * rounds may answer with surgical search/replace {@code edits} instead of full
 * files; blocks that fail to apply cleanly trigger a full-project retry. On
 * success the sources are persisted to the {@link ModStore} and the compiled
 * classes are hot-loaded on the main thread via the {@link ModRegistry},
 * carrying the mod's config schema and current values.
 */
public final class ModGenerator {

    /** Receives human-readable pipeline progress; called from background threads. */
    public interface ProgressListener {
        void phase(String label);

        void detail(String line);

        // ---- streaming callbacks (defaults so existing bridges keep compiling) ----
        // May be invoked from HTTP threads; implementations own any main-thread hop.

        /** The model declared its plan manifest: mod name + files it will emit, in order. */
        default void planReady(String name, List<String> files) {
        }

        /** A planned (or edit-touched) file started streaming. {@code total} <= 0 = unknown. */
        default void fileStarted(String path, int index, int total) {
        }

        /** Rolling stream volume; throttled by the caller. */
        default void streamStats(int chars, int approxTokens) {
        }

        /**
         * Fired synchronously at submit time, only when every generator thread is
         * already busy: this run waits at {@code position} (1 = next up) behind
         * {@code running} in-flight generations.
         */
        default void queued(int position, int running) {
        }
    }

    /** Outcome of one generation run. {@code costUsd} sums the real OpenRouter cost of every
     * round in this run, including rounds that failed or were thrown away by a retry -
     * burned money counts even when the run ultimately fails. */
    public record Result(boolean success, String modName, int version, int retries, String message,
                         double costUsd) {
    }

    private static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    private final Plugin plugin;
    private final OpenRouterClient client;
    private final InMemoryCompiler compiler;
    private final ModStore store;
    private final ModRegistry registry;
    private final IntSupplier maxRetries;
    private final java.util.function.BooleanSupplier streamingEnabled;
    private final ExecutorService executor;
    private final int poolSize;
    /** Submitted-but-unfinished runs; anything past {@link #poolSize} is waiting in the pool's queue. */
    private final java.util.concurrent.atomic.AtomicInteger unfinished =
            new java.util.concurrent.atomic.AtomicInteger();

    /** {@code concurrency} is the number of generations that may run at once (pool sized at construction — a config reload does not resize it). */
    public ModGenerator(Plugin plugin, OpenRouterClient client, InMemoryCompiler compiler,
                        ModStore store, ModRegistry registry, IntSupplier maxRetries,
                        java.util.function.BooleanSupplier streamingEnabled, int concurrency) {
        this.poolSize = Math.max(1, concurrency);
        this.executor = Executors.newFixedThreadPool(this.poolSize, r -> {
            Thread t = new Thread(r, "VibeMine-generator");
            t.setDaemon(true);
            return t;
        });
        this.plugin = plugin;
        this.client = client;
        this.compiler = compiler;
        this.store = store;
        this.registry = registry;
        this.maxRetries = maxRetries;
        this.streamingEnabled = streamingEnabled;
    }

    /** Create a brand-new mod from a prompt. */
    public CompletableFuture<Result> make(String prompt, String creator, ProgressListener l) {
        return run(l, () -> pipeline(prompt, creator, "create", null, null,
                PromptLibrary.makePrompt(prompt, creator), l));
    }

    /** Evolve an existing mod: the model sees the current sources, knobs and the change request. */
    public CompletableFuture<Result> edit(String modName, String prompt, String creator, ProgressListener l) {
        return run(l, () -> {
            ModStore.StoredMod existing = store.get(modName);
            if (existing == null) {
                return new Result(false, modName, 0, 0, "No mod named '" + modName + "'.", 0.0);
            }
            Map<String, String> sources = store.sources(existing.name(), existing.currentVersion());
            return pipeline(prompt, creator, "edit", existing.name(), baseProject(existing, sources),
                    PromptLibrary.editPrompt(prompt, sources, existing.config(),
                            store.resolvedConfigValues(existing.name())), l);
        });
    }

    /**
     * Repair a degraded mod: the model sees the current sources, knobs and the
     * recent runtime errors, and returns a new version fixing the root cause.
     */
    public CompletableFuture<Result> fix(String modName, String errorReport, String creator, ProgressListener l) {
        return run(l, () -> {
            ModStore.StoredMod existing = store.get(modName);
            if (existing == null) {
                return new Result(false, modName, 0, 0, "No mod named '" + modName + "'.", 0.0);
            }
            Map<String, String> sources = store.sources(existing.name(), existing.currentVersion());
            return pipeline("fix: " + errorHeadline(errorReport), creator, "fix", existing.name(),
                    baseProject(existing, sources),
                    PromptLibrary.fixPrompt(errorReport, sources, existing.config(),
                            store.resolvedConfigValues(existing.name())), l);
        });
    }

    /** First meaningful line of an error report (skipping the "== ... ==" header). */
    private static String errorHeadline(String report) {
        for (String line : report.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("==")) {
                return t.length() > 80 ? t.substring(0, 77) + "…" : t;
            }
        }
        return "runtime errors";
    }

    /** "Again": regenerate a fresh take on the mod's most recent prompt. */
    public CompletableFuture<Result> remake(String modName, String creator, ProgressListener l) {
        return run(l, () -> {
            ModStore.StoredMod existing = store.get(modName);
            if (existing == null || existing.versions().isEmpty()) {
                return new Result(false, modName, 0, 0, "No mod named '" + modName + "'.", 0.0);
            }
            String lastPrompt = existing.versions().get(existing.versions().size() - 1).prompt();
            return pipeline(lastPrompt, creator, "again", existing.name(), null,
                    PromptLibrary.makePrompt(lastPrompt, creator), l);
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private CompletableFuture<Result> run(ProgressListener l, java.util.concurrent.Callable<Result> body) {
        CompletableFuture<Result> out = new CompletableFuture<>();
        // The unfinished counter (not the pool's own gauges, which lag the hand-off)
        // decides synchronously whether this run must wait behind a full pool.
        int position = unfinished.incrementAndGet() - poolSize;
        if (position > 0) {
            l.queued(position, poolSize);
        }
        executor.submit(() -> {
            try {
                Result result = body.call();
                if (result.success()) {
                    plugin.getLogger().info("Generated " + result.modName() + " v" + result.version()
                            + (result.retries() > 0 ? " after " + result.retries() + " repair round(s)" : ""));
                } else {
                    plugin.getLogger().warning("Generation failed: " + result.message());
                }
                out.complete(result);
            } catch (Throwable t) {
                plugin.getLogger().warning("Generation failed: " + t);
                out.complete(new Result(false, null, 0, 0, brief(t), 0.0));
            } finally {
                unfinished.decrementAndGet();
            }
        });
        return out;
    }

    /**
     * The generate -> compile -> load loop with self-healing retries.
     * {@code base} is the current project state edits apply against (non-null
     * for /vibe edit); {@code kind} is the change type recorded on the saved
     * version (create/edit/fix/again); {@code forcedName} keeps edits/remakes
     * attached to the existing mod.
     */
    private Result pipeline(String originalPrompt, String creator, String kind, String forcedName,
                            GeneratedProject base, String firstUserMessage,
                            ProgressListener l) throws Exception {
        List<OpenRouterClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new OpenRouterClient.ChatMessage("user", firstUserMessage));
        GeneratedProject current = base;
        int budget = Math.max(0, maxRetries.getAsInt());
        double costUsd = 0.0; // burned money counts across every round, even failed ones
        double costAtLastSave = 0.0; // an enable-crash can save twice in one run: each save stores its delta

        int attempt = 0;
        while (true) {
            l.phase(attempt == 0 ? "Thinking" : "Thinking (repair " + attempt + ")");
            String response;
            try {
                OpenRouterClient.Completion completion = streamingEnabled.getAsBoolean()
                        ? client.completeStreaming(PromptLibrary.systemPrompt(), messages,
                                new StreamProgressAdapter(l)).get(600, TimeUnit.SECONDS)
                        : client.complete(PromptLibrary.systemPrompt(), messages)
                                .get(300, TimeUnit.SECONDS);
                response = completion.content();
                costUsd += completion.costUsd();
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException apiFail) {
                // Truncated/empty/flaky API responses are retryable rounds, not run-killers.
                Throwable cause = apiFail.getCause() != null ? apiFail.getCause() : apiFail;
                if (cause instanceof OpenRouterClient.CostAwareException costAware) {
                    costUsd += costAware.costUsd(); // the call was still billed even though it failed
                }
                String reason = brief(cause);
                plugin.getLogger().warning("LLM round failed (" + reason + "), retrying");
                if (attempt++ >= budget) {
                    return new Result(false, forcedName, 0, attempt - 1,
                            "The model's response failed " + attempt + " time(s): " + reason, costUsd);
                }
                l.detail("Model response failed (" + firstLineOf(reason) + "), retrying…");
                // No assistant turn to append (nothing usable came back); steer the retry:
                messages.add(new OpenRouterClient.ChatMessage("user",
                        "Your previous response was truncated or empty (" + reason + "). "
                        + "Respond again and BE CONCISE: keep the manual short, prefer fewer and "
                        + "smaller files, and stay well within the token limit. Same strict JSON."));
                continue;
            }

            l.phase("Writing");
            GeneratedProject project;
            try {
                GeneratedProject parsed = PromptLibrary.parse(response);
                project = parsed.isEditResponse() ? applyEdits(current, parsed) : merge(current, parsed);
            } catch (IllegalArgumentException bad) {
                plugin.getLogger().warning("Unusable model response: " + bad.getMessage());
                if (attempt++ >= budget) {
                    return new Result(false, forcedName, 0, attempt - 1,
                            "Model returned an unusable project: " + bad.getMessage(), costUsd);
                }
                l.detail("Bad response (" + bad.getMessage() + "), retrying…");
                messages.add(new OpenRouterClient.ChatMessage("assistant", response));
                messages.add(new OpenRouterClient.ChatMessage("user",
                        PromptLibrary.demandFullProject(bad.getMessage())));
                continue;
            }
            String name = forcedName != null ? forcedName : project.name();

            l.phase("Compiling");
            Map<String, String> sources = toFqcnSources(project);
            CompileResult compiled = compiler.compile(sources);
            if (!compiled.success()) {
                plugin.getLogger().warning("Mod compile round failed:\n" + compiled.diagnostics());
                if (attempt++ >= budget) {
                    return new Result(false, name, 0, attempt - 1,
                            "Compile failed after " + attempt + " attempt(s):\n" + compiled.diagnostics(), costUsd);
                }
                l.detail("javac errors, asking the model to fix them…");
                messages.add(new OpenRouterClient.ChatMessage("assistant", response));
                messages.add(new OpenRouterClient.ChatMessage("user",
                        PromptLibrary.repairPrompt(compiled.diagnostics())));
                current = project; // repairs now apply against this round's sources
                continue;
            }

            String mainFqcn = resolveMainFqcn(project, sources);
            double versionCost = costUsd - costAtLastSave;
            costAtLastSave = costUsd;
            ModStore.StoredMod saved = store.saveNewVersion(name, project.description(), mainFqcn,
                    creator, originalPrompt, client.model(),
                    firstNonBlank(project.changelog(), deriveChangelog(kind, originalPrompt)),
                    kind, versionCost, creator, project);

            l.phase("Loading");
            try {
                onMainThread(() -> registry.load(saved.name(), saved.currentVersion(),
                        saved.description(), mainFqcn, compiled.classes(),
                        saved.config(), store.resolvedConfigValues(saved.name()), saved.debugEcho()));
                return new Result(true, saved.name(), saved.currentVersion(), attempt,
                        saved.name() + " v" + saved.currentVersion() + " is live", costUsd);
            } catch (Exception enableFail) {
                // The code compiled but blew up on enable — worth one repair round too.
                store.setEnabled(saved.name(), false);
                if (attempt++ >= budget) {
                    return new Result(false, name, saved.currentVersion(), attempt - 1,
                            "Mod failed to start: " + brief(enableFail), costUsd);
                }
                l.detail("Mod crashed on enable, asking the model to fix it…");
                messages.add(new OpenRouterClient.ChatMessage("assistant", response));
                messages.add(new OpenRouterClient.ChatMessage("user", PromptLibrary.repairPrompt(
                        "The project compiled but threw on enable: " + stackTop(enableFail))));
                current = project;
            }
        }
    }

    /** Apply an edit-shaped response to the current project state. */
    private GeneratedProject applyEdits(GeneratedProject current, GeneratedProject editResponse) {
        if (current == null) {
            throw new IllegalArgumentException(
                    "an edits response is not allowed here - there are no current sources to edit");
        }
        Map<String, String> byPath = new LinkedHashMap<>();
        for (GeneratedProject.GeneratedFile f : current.files()) {
            byPath.put(fileName(f.path()), f.content());
        }
        int applied = 0;
        for (GeneratedProject.EditBlock edit : editResponse.edits()) {
            String path = fileName(edit.path());
            String content = byPath.get(path);
            if (content == null) {
                throw new IllegalArgumentException("edit targets unknown file '" + edit.path() + "'");
            }
            int first = content.indexOf(edit.find());
            if (first < 0) {
                throw new IllegalArgumentException("edit 'find' text not found in " + path);
            }
            if (content.indexOf(edit.find(), first + 1) >= 0) {
                throw new IllegalArgumentException("edit 'find' text matches more than once in " + path);
            }
            byPath.put(path, content.substring(0, first) + edit.replace()
                    + content.substring(first + edit.find().length()));
            applied++;
        }
        plugin.getLogger().info("Applied " + applied + " edit block(s) from an edit-shaped response");
        List<GeneratedProject.GeneratedFile> files = byPath.entrySet().stream()
                .map(e -> new GeneratedProject.GeneratedFile(e.getKey(), e.getValue()))
                .toList();
        return new GeneratedProject(current.name(),
                firstNonBlank(editResponse.description(), current.description()),
                firstNonBlank(editResponse.usage(), current.usage()),
                firstNonBlank(editResponse.manual(), current.manual()),
                // Reversed preference: the run's first changelog wins so a later repair
                // round's "fixed the compile error" line never overwrites it.
                firstNonBlank(current.changelog(), editResponse.changelog()),
                firstNonBlank(editResponse.icon(), current.icon()),
                current.mainClass(), files,
                editResponse.config() != null && !editResponse.config().isEmpty()
                        ? editResponse.config() : current.config(),
                List.of());
    }

    /** Full-project response: fill any omitted descriptive fields from the previous state. */
    private static GeneratedProject merge(GeneratedProject previous, GeneratedProject full) {
        if (previous == null) {
            return full;
        }
        return new GeneratedProject(full.name(),
                firstNonBlank(full.description(), previous.description()),
                firstNonBlank(full.usage(), previous.usage()),
                firstNonBlank(full.manual(), previous.manual()),
                // Reversed preference: the run's first changelog wins (see applyEdits).
                firstNonBlank(previous.changelog(), full.changelog()),
                firstNonBlank(full.icon(), previous.icon()),
                full.mainClass(), full.files(),
                full.config() != null && !full.config().isEmpty() ? full.config() : previous.config(),
                List.of());
    }

    /** Reconstruct a project-state view of a stored mod so edits can apply against it. */
    private static GeneratedProject baseProject(ModStore.StoredMod mod, Map<String, String> sources) {
        List<GeneratedProject.GeneratedFile> files = sources.entrySet().stream()
                .map(e -> new GeneratedProject.GeneratedFile(simpleName(e.getKey()) + ".java", e.getValue()))
                .toList();
        // changelog is null on purpose: a stored mod's old state must never seed a
        // fresh run's changelog — the first response of the run supplies it instead.
        return new GeneratedProject(mod.name(), mod.description(), mod.usage(), mod.manual(),
                null, mod.icon(), simpleName(mod.mainClass()), files, mod.config(), List.of());
    }

    /** Derive FQCN -> source, trusting each file's own package declaration. */
    private static Map<String, String> toFqcnSources(GeneratedProject project) {
        Map<String, String> out = new LinkedHashMap<>();
        for (GeneratedProject.GeneratedFile f : project.files()) {
            String simple = fileName(f.path()).replaceAll("\\.java$", "");
            Matcher m = PACKAGE_DECL.matcher(f.content());
            String pkg = m.find() ? m.group(1) : "vibemod." + project.name().toLowerCase();
            out.put(pkg + "." + simple, f.content());
        }
        return out;
    }

    private static String resolveMainFqcn(GeneratedProject project, Map<String, String> sources) {
        String main = project.mainClass();
        return sources.keySet().stream()
                .filter(fqcn -> fqcn.endsWith("." + main) || fqcn.equals(main))
                .findFirst().orElse(main);
    }

    /** Run a throwing action on the main server thread and wait for it here. */
    private void onMainThread(ThrowingRunnable action) throws Exception {
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                action.run();
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            done.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Bridges raw SSE deltas into ProgressListener events: incremental scanner
     * for plan/file markers, throttled volume stats. Runs on the HTTP thread -
     * listener implementations own any main-thread hop.
     */
    private static final class StreamProgressAdapter implements OpenRouterClient.StreamObserver {

        private static final long STATS_THROTTLE_NANOS = 250_000_000L;

        private final ProgressListener listener;
        private final com.gijsm.vibemine.llm.StreamScanner scanner = new com.gijsm.vibemine.llm.StreamScanner();
        private boolean firstDelta = true;
        private int plannedTotal = -1;
        private long lastStatsNanos = 0;

        private StreamProgressAdapter(ProgressListener listener) {
            this.listener = listener;
        }

        @Override
        public void onDelta(String delta, int totalChars) {
            try {
                if (firstDelta) {
                    firstDelta = false;
                    listener.phase("Writing");
                }
                boolean eventFired = false;
                for (com.gijsm.vibemine.llm.StreamScanner.Event event : scanner.feed(delta)) {
                    eventFired = true;
                    if (event instanceof com.gijsm.vibemine.llm.StreamScanner.PlanReady plan) {
                        plannedTotal = plan.files().size();
                        listener.planReady(plan.name(), plan.files());
                    } else if (event instanceof com.gijsm.vibemine.llm.StreamScanner.FileStarted file) {
                        listener.fileStarted(file.path(), file.index(), plannedTotal);
                    }
                }
                long now = System.nanoTime();
                if (eventFired || now - lastStatsNanos >= STATS_THROTTLE_NANOS) {
                    lastStatsNanos = now;
                    listener.streamStats(totalChars, totalChars / 4);
                }
            } catch (Throwable t) {
                // Progress narration must never break a generation.
            }
        }
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String simpleName(String fqcnOrSimple) {
        return fqcnOrSimple.substring(fqcnOrSimple.lastIndexOf('.') + 1);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    /**
     * Fallback changelog when the model omitted one: the run's prompt, flattened and
     * truncated to one short line. {@code kind} needs no prefix of its own — it is stored
     * separately, and fix prompts already read {@code "fix: <headline>"}.
     */
    private static String deriveChangelog(String kind, String prompt) {
        String line = prompt == null ? "" : prompt.replace('\n', ' ').trim();
        return line.length() <= 100 ? line : line.substring(0, 97) + "…";
    }

    private static String firstLineOf(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static String brief(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }

    private static String stackTop(Throwable t) {
        StringBuilder sb = new StringBuilder(t.toString());
        StackTraceElement[] st = (t.getCause() != null ? t.getCause() : t).getStackTrace();
        for (int i = 0; i < Math.min(6, st.length); i++) {
            sb.append("\n  at ").append(st[i]);
        }
        return sb.toString();
    }
}
