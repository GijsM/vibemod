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
    }

    /** Outcome of one generation run. */
    public record Result(boolean success, String modName, int version, int retries, String message) {
    }

    private static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    private final Plugin plugin;
    private final OpenRouterClient client;
    private final InMemoryCompiler compiler;
    private final ModStore store;
    private final ModRegistry registry;
    private final IntSupplier maxRetries;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "VibeMine-generator");
        t.setDaemon(true);
        return t;
    });

    public ModGenerator(Plugin plugin, OpenRouterClient client, InMemoryCompiler compiler,
                        ModStore store, ModRegistry registry, IntSupplier maxRetries) {
        this.plugin = plugin;
        this.client = client;
        this.compiler = compiler;
        this.store = store;
        this.registry = registry;
        this.maxRetries = maxRetries;
    }

    /** Create a brand-new mod from a prompt. */
    public CompletableFuture<Result> make(String prompt, String creator, ProgressListener l) {
        return run(l, () -> pipeline(prompt, creator, null, null,
                PromptLibrary.makePrompt(prompt, creator), l));
    }

    /** Evolve an existing mod: the model sees the current sources, knobs and the change request. */
    public CompletableFuture<Result> edit(String modName, String prompt, String creator, ProgressListener l) {
        return run(l, () -> {
            ModStore.StoredMod existing = store.get(modName);
            if (existing == null) {
                return new Result(false, modName, 0, 0, "No mod named '" + modName + "'.");
            }
            Map<String, String> sources = store.sources(existing.name(), existing.currentVersion());
            return pipeline(prompt, creator, existing.name(), baseProject(existing, sources),
                    PromptLibrary.editPrompt(prompt, sources, existing.config(),
                            store.resolvedConfigValues(existing.name())), l);
        });
    }

    /** "Again": regenerate a fresh take on the mod's most recent prompt. */
    public CompletableFuture<Result> remake(String modName, String creator, ProgressListener l) {
        return run(l, () -> {
            ModStore.StoredMod existing = store.get(modName);
            if (existing == null || existing.versions().isEmpty()) {
                return new Result(false, modName, 0, 0, "No mod named '" + modName + "'.");
            }
            String lastPrompt = existing.versions().get(existing.versions().size() - 1).prompt();
            return pipeline(lastPrompt, creator, existing.name(), null,
                    PromptLibrary.makePrompt(lastPrompt, creator), l);
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private CompletableFuture<Result> run(ProgressListener l, java.util.concurrent.Callable<Result> body) {
        CompletableFuture<Result> out = new CompletableFuture<>();
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
                out.complete(new Result(false, null, 0, 0, brief(t)));
            }
        });
        return out;
    }

    /**
     * The generate -> compile -> load loop with self-healing retries.
     * {@code base} is the current project state edits apply against (non-null
     * for /vibe edit); {@code forcedName} keeps edits/remakes attached to the
     * existing mod.
     */
    private Result pipeline(String originalPrompt, String creator, String forcedName,
                            GeneratedProject base, String firstUserMessage,
                            ProgressListener l) throws Exception {
        List<OpenRouterClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new OpenRouterClient.ChatMessage("user", firstUserMessage));
        GeneratedProject current = base;
        int budget = Math.max(0, maxRetries.getAsInt());

        int attempt = 0;
        while (true) {
            l.phase(attempt == 0 ? "Thinking" : "Thinking (repair " + attempt + ")");
            String response = client.complete(PromptLibrary.systemPrompt(), messages)
                    .get(300, TimeUnit.SECONDS);

            l.phase("Writing");
            GeneratedProject project;
            try {
                GeneratedProject parsed = PromptLibrary.parse(response);
                project = parsed.isEditResponse() ? applyEdits(current, parsed) : merge(current, parsed);
            } catch (IllegalArgumentException bad) {
                plugin.getLogger().warning("Unusable model response: " + bad.getMessage());
                if (attempt++ >= budget) {
                    return new Result(false, forcedName, 0, attempt - 1,
                            "Model returned an unusable project: " + bad.getMessage());
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
                            "Compile failed after " + attempt + " attempt(s):\n" + compiled.diagnostics());
                }
                l.detail("javac errors, asking the model to fix them…");
                messages.add(new OpenRouterClient.ChatMessage("assistant", response));
                messages.add(new OpenRouterClient.ChatMessage("user",
                        PromptLibrary.repairPrompt(compiled.diagnostics())));
                current = project; // repairs now apply against this round's sources
                continue;
            }

            String mainFqcn = resolveMainFqcn(project, sources);
            ModStore.StoredMod saved = store.saveNewVersion(name, project.description(), mainFqcn,
                    creator, originalPrompt, client.model(), project);

            l.phase("Loading");
            try {
                onMainThread(() -> registry.load(saved.name(), saved.currentVersion(),
                        saved.description(), mainFqcn, compiled.classes(),
                        saved.config(), store.resolvedConfigValues(saved.name())));
                return new Result(true, saved.name(), saved.currentVersion(), attempt,
                        saved.name() + " v" + saved.currentVersion() + " is live");
            } catch (Exception enableFail) {
                // The code compiled but blew up on enable — worth one repair round too.
                store.setEnabled(saved.name(), false);
                if (attempt++ >= budget) {
                    return new Result(false, name, saved.currentVersion(), attempt - 1,
                            "Mod failed to start: " + brief(enableFail));
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
        return new GeneratedProject(mod.name(), mod.description(), mod.usage(), mod.manual(),
                mod.icon(), simpleName(mod.mainClass()), files, mod.config(), List.of());
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

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String simpleName(String fqcnOrSimple) {
        return fqcnOrSimple.substring(fqcnOrSimple.lastIndexOf('.') + 1);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
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
