package eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;

import com.gijsm.vibemod.compile.CompileResult;
import com.gijsm.vibemod.compile.InMemoryCompiler;
import com.gijsm.vibemod.gen.GeneratedProject;
import com.gijsm.vibemod.gen.SymbolRepair;
import com.gijsm.vibemod.llm.OpenRouterClient;
import com.gijsm.vibemod.llm.OpenRouterClient.ChatMessage;
import com.gijsm.vibemod.llm.PromptLibrary;
import com.gijsm.vibemod.platform.CompilerProvider;

import symbols.ClassFileVocabulary;

/**
 * B5: the scored offline eval harness.
 *
 * <p>The question it exists to answer is narrow and falsifiable: <em>does the
 * rebuilt, vocabulary-aware system prompt produce more first-try-compiling mods
 * than the one it replaced?</em> Everything else here is scaffolding for making
 * that measurable without either arm getting an unearned advantage.
 *
 * <h2>What is held constant</h2>
 *
 * <p>Only the system prompt differs between the {@code before} and
 * {@code after} arms. The user message, the response parser and the
 * full-project demand are byte-identical between {@code 46f452c^} and HEAD, so
 * both arms run through HEAD's {@link PromptLibrary#makePrompt} and
 * {@link PromptLibrary#parse}. The "before" system prompt is not retyped — it
 * is compiled out of git by {@link LegacyPrompt}.
 *
 * <h2>What is measured</h2>
 *
 * <ul>
 *   <li><b>First-try compile rate</b> — round 0 went green, per arm and version.
 *   <li><b>SymbolRepair on vs off</b> — scored TWICE from the same parsed
 *       project, so the offline repair pass costs no extra generations and the
 *       comparison is exactly paired.
 *   <li><b>Rounds to green</b> — how many self-heal rounds the survivors needed.
 * </ul>
 *
 * <h2>What it deliberately does not claim</h2>
 *
 * <p>At the sample sizes this is affordable at, almost nothing is significant.
 * The report prints the observed difference next to the spread you would expect
 * from coin flips, and says "within noise" unless the counts are extreme. A
 * harness that reported p-values off nine generations per cell would be worse
 * than no harness.
 *
 * <h2>Money</h2>
 *
 * <p>Every response is cached content-addressed ({@link RunCache}), so a
 * resumed run never re-pays. {@code -Dvibemod.eval.budgetUsd} is a hard stop
 * checked before every call, {@code -Dvibemod.eval.pilot=N} caps total calls,
 * and {@code -Dvibemod.eval.dryRun=true} makes no calls at all.
 *
 * <p><b>The API key is never printed, logged, or written anywhere</b> — not its
 * length, not a prefix, not a hash. It is read into a local, handed to
 * {@link OpenRouterClient}, and never touched again. Every {@code System.out}
 * and every file written below is key-free by construction; treat that as an
 * invariant when editing this class.
 */
public final class CompileRateEval {

    private CompileRateEval() {
    }

    // ------------------------------------------------------------------
    // Config
    // ------------------------------------------------------------------

    /** Everything the run was told to do. Contains no secret. */
    record Config(Path root, Path jarsDir, Path modsDir, Path outDir, String model, int n,
                  List<Cell> cells, int healRounds, double budgetUsd, boolean dryRun,
                  int pilotCalls, long seed, boolean selfCheck, Free free) {
    }

    /**
     * The free-tier run's extra knobs. All default to "behave exactly as before",
     * so a paid run that sets none of them is byte-for-byte the old harness.
     *
     * @param requireZeroCost abort the run the moment any response bills more
     *     than $0. The free-tier study must cost nothing, and the cheapest way
     *     to guarantee that is to stop rather than to trust a model id suffix.
     * @param maxRetries      attempts after the first on a retryable transport
     *     failure (429 from a shared upstream pool, 5xx, timeout).
     * @param retryBaseMs     first backoff; doubles per attempt.
     * @param throttleMs      minimum spacing between calls, to stay under the
     *     free tier's per-minute ceiling without discovering it the hard way.
     * @param taskMajor       iterate tasks outermost and cells innermost. With a
     *     hard daily request cap this is what keeps the arms paired: a cap that
     *     bites mid-run then truncates every cell at the same task rather than
     *     starving whichever cell happened to run last.
     */
    record Free(boolean requireZeroCost, int maxRetries, long retryBaseMs, long throttleMs,
                boolean taskMajor) {
    }

    record Cell(String condition, String version) {
        @Override
        public String toString() {
            return condition + ":" + version;
        }
    }

    private static Config resolveConfig() {
        Path root = Path.of(prop("vibemod.eval.root", detectRoot().toString())).toAbsolutePath();
        Path jarsDir = Path.of(prop("vibemod.apiJars", root.resolve("paper/api-jars").toString()));
        Path modsDir = Path.of(prop("vibemod.mods.dir",
                root.resolve("server/plugins/VibeMod/mods").toString()));
        Path outDir = Path.of(prop("vibemod.eval.out", root.resolve("eval-out").toString()));
        String model = prop("vibemod.eval.model", "openai/gpt-5.6-luna");
        int n = Integer.parseInt(prop("vibemod.eval.n", "3"));
        String cellSpec = prop("vibemod.eval.cells", "before:1.21.8,after:1.21.8,before:1.21.1,after:1.21.1");
        List<Cell> cells = new ArrayList<>();
        for (String raw : cellSpec.split(",")) {
            String s = raw.trim();
            if (s.isEmpty()) {
                continue;
            }
            int colon = s.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("bad cell '" + s + "', want condition:version");
            }
            String condition = s.substring(0, colon).trim();
            if (!condition.equals("before") && !condition.equals("after")
                    && !condition.equals("after-novocab")
                    && !condition.startsWith("after+")) {
                throw new IllegalArgumentException("bad condition '" + condition
                        + "', want before, after, after-novocab, or after+<nudge>[+<nudge>...] "
                        + "(nudges: " + String.join(", ", Nudges.names()) + ")");
            }
            Nudges.validate(condition);
            cells.add(new Cell(condition, s.substring(colon + 1).trim()));
        }
        int healRounds = Integer.parseInt(prop("vibemod.eval.healRounds", "1"));
        double budget = Double.parseDouble(prop("vibemod.eval.budgetUsd", "5.0"));
        boolean dryRun = Boolean.parseBoolean(prop("vibemod.eval.dryRun", "false"));
        int pilot = Integer.parseInt(prop("vibemod.eval.pilot", "-1"));
        long seed = Long.parseLong(prop("vibemod.eval.seed", "0"));
        boolean selfCheck = Boolean.parseBoolean(prop("vibemod.eval.selfCheck", "false"));
        Free free = new Free(
                Boolean.parseBoolean(prop("vibemod.eval.requireZeroCost", "false")),
                Integer.parseInt(prop("vibemod.eval.maxRetries", "0")),
                Long.parseLong(prop("vibemod.eval.retryBaseMs", "4000")),
                Long.parseLong(prop("vibemod.eval.throttleMs", "0")),
                Boolean.parseBoolean(prop("vibemod.eval.taskMajor", "false")));
        return new Config(root, jarsDir, modsDir, outDir, model, n, cells, healRounds, budget,
                dryRun, pilot, seed, selfCheck, free);
    }

    private static String prop(String key, String def) {
        String v = System.getProperty(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    /** Walk up from the working directory to the repository root. */
    private static Path detectRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path c = p; c != null; c = c.getParent()) {
            if (Files.isRegularFile(c.resolve("settings.gradle.kts"))
                    || Files.isDirectory(c.resolve("paper/api-jars"))) {
                return c;
            }
        }
        return p;
    }

    /**
     * The key, resolved from (in order) a system property, the environment, and
     * the server config. NEVER printed, logged, hashed or written. The caller
     * holds it in one local and hands it straight to the client.
     */
    private static String resolveApiKey(Path root) {
        // -Dvibemod.eval.apiKeyFile wins over everything and, crucially, DISABLES
        // every fallback below it. A study that must run on a specific key (the
        // free-tier one) must not be able to silently spend a different key
        // sitting in the server config; naming a file is how the caller says
        // "this key or none". The path is a filename, so unlike -Dvibemod.eval.apiKey
        // it is safe to pass on a command line that `ps` can read.
        String keyFile = System.getProperty("vibemod.eval.apiKeyFile");
        if (keyFile != null && !keyFile.isBlank()) {
            try {
                String v = Files.readString(Path.of(keyFile.trim()), StandardCharsets.UTF_8).trim();
                if (v.isBlank()) {
                    throw new IllegalStateException("apiKeyFile is empty: " + keyFile);
                }
                return v;
            } catch (IOException e) {
                // Says nothing about the contents, only that it could not be read.
                throw new IllegalStateException("could not read apiKeyFile " + keyFile + ": "
                        + e.getClass().getSimpleName());
            }
        }
        String p = System.getProperty("vibemod.eval.apiKey");
        if (p != null && !p.isBlank()) {
            return p.trim();
        }
        String env = System.getenv("OPENROUTER_API_KEY");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        Path cfg = root.resolve("server/plugins/VibeMod/config.yml");
        if (Files.isReadable(cfg)) {
            try {
                for (String line : Files.readAllLines(cfg, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.startsWith("api-key:")) {
                        String v = t.substring("api-key:".length()).trim();
                        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"")
                                || v.startsWith("'") && v.endsWith("'"))) {
                            v = v.substring(1, v.length() - 1);
                        }
                        if (!v.isBlank()) {
                            return v;
                        }
                    }
                }
            } catch (IOException e) {
                // Deliberately says nothing about the file's contents.
                System.out.println("  (could not read the server config for a key: " + e.getClass().getSimpleName() + ")");
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------

    /** One generation: one (condition, version, task) triple, all its rounds. */
    static final class Gen {
        final String condition;
        final String version;
        final String task;
        String outcome = "PENDING";
        boolean firstTryRepairOff;
        boolean firstTryRepairOn;
        boolean everGreen;
        int roundsToGreen = -1;
        boolean repairFlippedFirstTry;
        final List<String> rewrites = new ArrayList<>();
        /** Round-0 failure classification (repair ON). See classifyFailure. */
        String failureKind = "";
        /**
         * Round-0 JSON adherence, independent of whether the code compiled.
         * One of PARSED / NO_JSON / UNBALANCED / INVALID_JSON / SCHEMA / (empty
         * when no response was obtained). See {@link #classifyParse}.
         */
        String parseKind = "";
        /** Round-0 response was bare JSON: first char '{', last char '}'. */
        boolean strictJson;
        /** Round-0 response needed the fence/prose tolerance to parse at all. */
        boolean neededTolerance;
        /** Round-0 raw response length in characters. */
        int rawChars;
        /** Round-0 went green once ImportDoctor's prototype import pass was applied too. */
        boolean importRepairGreen;
        /** Imports the prototype pass inserted, as "owner -> java.util.UUID". */
        final List<String> importsAdded = new ArrayList<>();
        /** Missing type names it could NOT resolve: the hallucinated-type candidates. */
        final List<String> importsUnresolved = new ArrayList<>();
        final List<String> missingSymbols = new ArrayList<>();
        double usd;
        int apiCalls;
        int cacheHits;
        String note = "";

        Gen(String condition, String version, String task) {
            this.condition = condition;
            this.version = version;
            this.task = task;
        }
    }

    /** Cumulative spend and call budget, checked before every request. */
    static final class Ledger {
        final double budgetUsd;
        final int pilotCalls;
        double spentUsd;
        int apiCalls;
        int cacheHits;
        boolean aborted;
        String abortReason = "";

        Ledger(double budgetUsd, int pilotCalls) {
            this.budgetUsd = budgetUsd;
            this.pilotCalls = pilotCalls;
        }

        /** True when another call is allowed. Sets the abort reason when not. */
        boolean mayCall() {
            if (aborted) {
                return false;
            }
            if (spentUsd >= budgetUsd) {
                abort(String.format(Locale.ROOT,
                        "BUDGET REACHED: $%.4f spent >= $%.4f budget", spentUsd, budgetUsd));
                return false;
            }
            if (pilotCalls >= 0 && apiCalls >= pilotCalls) {
                abort("PILOT CAP REACHED: " + apiCalls + " API calls >= " + pilotCalls);
                return false;
            }
            return true;
        }

        void abort(String reason) {
            if (!aborted) {
                aborted = true;
                abortReason = reason;
                System.out.println();
                System.out.println("################################################################");
                System.out.println("##  RUN ABORTED - " + reason);
                System.out.println("##  Reporting on what completed. Nothing further will be bought.");
                System.out.println("################################################################");
                System.out.println();
            }
        }
    }

    // ------------------------------------------------------------------
    // Main
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        Config cfg = resolveConfig();
        Files.createDirectories(cfg.outDir());
        printConfig(cfg);

        if (cfg.selfCheck()) {
            selfCheck(cfg);
            return;
        }

        LegacyPrompt.configure(cfg.root(), cfg.outDir());

        // --- per-version fixtures -------------------------------------
        Set<String> versions = new LinkedHashSet<>();
        cfg.cells().forEach(c -> versions.add(c.version()));
        Map<String, ClassFileVocabulary> vocabs = new LinkedHashMap<>();
        Map<String, InMemoryCompiler> compilers = new LinkedHashMap<>();
        CompilerProvider provider = CompilerProvider.resolve()
                .orElseThrow(() -> new IllegalStateException("no java compiler on this JVM"));
        for (String v : versions) {
            vocabs.put(v, EvalFacts.vocabulary(cfg.jarsDir(), v));
            compilers.put(v, new InMemoryCompiler(provider, new VersionClasspath(cfg.jarsDir(), v)));
        }

        // --- the four (or however many) system prompts ----------------
        Map<Cell, String> systemPrompts = new LinkedHashMap<>();
        for (Cell c : cfg.cells()) {
            systemPrompts.put(c, systemPromptFor(c, vocabs.get(c.version())));
        }
        System.out.println("=== System prompts ===");
        for (Map.Entry<Cell, String> e : systemPrompts.entrySet()) {
            Cell c = e.getKey();
            String profile = "before".equals(c.condition())
                    ? LegacyPrompt.profileIdFor(c.version()) : EvalFacts.profileIdFor(c.version());
            System.out.printf(Locale.ROOT, "  %-8s %-8s profile=%-13s %,7d chars%n",
                    c.condition(), c.version(), profile, e.getValue().length());
            Files.writeString(cfg.outDir().resolve("system-prompt-" + c.condition() + "-" + c.version() + ".txt"),
                    e.getValue(), StandardCharsets.UTF_8);
        }
        System.out.println();

        // --- corpus ---------------------------------------------------
        List<EvalCorpus.Task> all = EvalCorpus.load(cfg.modsDir());
        List<EvalCorpus.Task> subset = EvalCorpus.select(all, cfg.n(), cfg.seed());
        System.out.println("=== Corpus ===");
        System.out.println("  " + all.size() + " mods with a usable description at " + cfg.modsDir());
        System.out.println("  " + subset.size() + " selected (n=" + cfg.n() + ", seed=" + cfg.seed() + "):");
        for (EvalCorpus.Task t : subset) {
            System.out.println("    - " + t.name() + ": " + oneLine(t.description(), 90));
        }
        System.out.println();
        if (subset.isEmpty()) {
            System.out.println("No tasks. Nothing to do.");
            return;
        }

        // --- run ------------------------------------------------------
        RunCache cache = new RunCache(cfg.outDir());
        String fixtureMod = prop("vibemod.eval.seedCacheFrom", "");
        if (!fixtureMod.isEmpty()) {
            seedCacheFixture(cfg, systemPrompts, subset, cache, fixtureMod);
        }
        Ledger ledger = new Ledger(cfg.budgetUsd(), cfg.pilotCalls());
        Path usageLog = cfg.outDir().resolve("usage.jsonl");
        OpenRouterClient client = null;
        if (!cfg.dryRun()) {
            String apiKey = resolveApiKey(cfg.root());   // never printed, never stored
            if (apiKey == null) {
                System.out.println("No API key found (vibemod.eval.apiKey / OPENROUTER_API_KEY / config.yml).");
                System.out.println("Falling back to CACHE-ONLY: misses will be recorded as SKIPPED.");
            } else {
                client = new OpenRouterClient(apiKey, cfg.model(), Duration.ofSeconds(300));
            }
        }

        // Cell-major by default (unchanged). Task-major when a hard request cap
        // might truncate the run: see Free#taskMajor.
        List<Object[]> plan = new ArrayList<>();
        if (cfg.free().taskMajor()) {
            for (EvalCorpus.Task task : subset) {
                for (Cell cell : cfg.cells()) {
                    plan.add(new Object[] {cell, task});
                }
            }
        } else {
            for (Cell cell : cfg.cells()) {
                for (EvalCorpus.Task task : subset) {
                    plan.add(new Object[] {cell, task});
                }
            }
        }

        Path resultsLog = cfg.outDir().resolve("results.jsonl");
        Files.deleteIfExists(resultsLog);
        List<Gen> results = new ArrayList<>();
        for (Object[] step : plan) {
            Cell cell = (Cell) step[0];
            EvalCorpus.Task task = (EvalCorpus.Task) step[1];
            String system = systemPrompts.get(cell);
            ClassFileVocabulary vocab = vocabs.get(cell.version());
            InMemoryCompiler compiler = compilers.get(cell.version());
            Gen gen = runOne(cfg, cell, task, system, vocab, compiler, cache, client, ledger, usageLog);
            results.add(gen);
            logResult(resultsLog, cfg.model(), gen);
            // Circuit breaker. A model whose provider is saturated returns 429 to
            // every request, and without this the run cheerfully spends the day's
            // entire request allowance discovering that forty times over. Measured:
            // one such model burned 40 requests for 0 generations.
            consecutiveTransportFailures = "API_FAILURE".equals(gen.outcome)
                    || "API_TIMEOUT".equals(gen.outcome) ? consecutiveTransportFailures + 1 : 0;
            if (breakerLimit > 0 && consecutiveTransportFailures >= breakerLimit) {
                ledger.abort("CIRCUIT BREAKER: " + consecutiveTransportFailures
                        + " consecutive transport failures for model " + cfg.model()
                        + " — treating it as unavailable rather than spending more requests on it.");
            }
            System.out.printf(Locale.ROOT,
                    "  [%s/%s] %-24s %-22s parse=%-12s firstTry off=%s on=%s rounds=%s  $%.4f%n",
                    cell.condition(), cell.version(), task.name(), gen.outcome, gen.parseKind,
                    gen.firstTryRepairOff ? "Y" : "n", gen.firstTryRepairOn ? "Y" : "n",
                    gen.roundsToGreen < 0 ? "-" : String.valueOf(gen.roundsToGreen), gen.usd);
        }

        String report = buildReport(cfg, all.size(), subset, systemPrompts, results, ledger, cache);
        System.out.println();
        System.out.println(report);
        Files.writeString(cfg.outDir().resolve("report.md"), report, StandardCharsets.UTF_8);
        System.out.println("Report written to " + cfg.outDir().resolve("report.md"));
    }

    private static void printConfig(Config cfg) {
        System.out.println("=== CompileRateEval (B5) ===");
        System.out.println("  root        " + cfg.root());
        System.out.println("  apiJars     " + cfg.jarsDir());
        System.out.println("  modsDir     " + cfg.modsDir());
        System.out.println("  outDir      " + cfg.outDir());
        System.out.println("  model       " + cfg.model());
        System.out.println("  n per cell  " + cfg.n());
        System.out.println("  cells       " + cfg.cells());
        System.out.println("  healRounds  " + cfg.healRounds());
        System.out.println("  budgetUsd   " + String.format(Locale.ROOT, "%.2f", cfg.budgetUsd()));
        System.out.println("  dryRun      " + cfg.dryRun());
        System.out.println("  pilot       " + (cfg.pilotCalls() < 0 ? "(off)" : cfg.pilotCalls() + " calls"));
        System.out.println("  seed        " + cfg.seed());
        System.out.println("  selfCheck   " + cfg.selfCheck());
        System.out.println("  zeroCost    " + (cfg.free().requireZeroCost()
                ? "REQUIRED (abort on any billed response)" : "not enforced"));
        System.out.println("  retries     " + cfg.free().maxRetries()
                + " (base " + cfg.free().retryBaseMs() + "ms, doubling)");
        System.out.println("  throttle    " + cfg.free().throttleMs() + "ms between calls");
        System.out.println("  order       " + (cfg.free().taskMajor() ? "task-major" : "cell-major"));
        System.out.println("  api key     (resolved at call time; never printed)");
        System.out.println();
    }

    private static String systemPromptFor(Cell cell, ClassFileVocabulary vocab) {
        if ("before".equals(cell.condition())) {
            return LegacyPrompt.forVersion(cell.version());
        }
        String after = PromptLibrary.systemPrompt(EvalFacts.factsFor(cell.version(), vocab));
        if ("after-novocab".equals(cell.condition())) {
            return stripVocabularyDump(after);
        }
        // after+<nudge>: the shipping prompt with one or more extra rules appended.
        // These are eval-side text, NOT a change to PromptRules — the point is to
        // measure whether a rule is worth adding before anyone adds it.
        return Nudges.apply(after, cell.condition(), vocab);
    }

    /**
     * The {@code after} prompt with the injected constant lists removed, and
     * nothing else changed.
     *
     * <p>This is the ablation that separates the two things the rebuild did at
     * once. The rebuilt prompt both (a) replaced hand-written era prose with
     * capability-predicated rules and (b) appended the server's real
     * Attribute/Enchantment/PotionEffectType constant lists, measured off the
     * jar. Those cost about 850 prompt tokens together, and comparing
     * {@code after} against {@code before} cannot say which half did the work —
     * or which half caused the extra malformed responses.
     *
     * <p>Removing the dump is a pure text operation on the rendered prompt,
     * deliberately: the assembly in {@code PromptLibrary}/{@code PromptRules} is
     * never touched, so this arm cannot perturb the shipping prompt or the B3
     * symbol gate.
     */
    static String stripVocabularyDump(String prompt) {
        String[] lines = prompt.split("\n", -1);
        StringBuilder out = new StringBuilder(prompt.length());
        int removed = 0;
        for (int i = 0; i < lines.length; i++) {
            if (VOCAB_HEADER.matcher(lines[i]).matches()) {
                // Drop the header, the single comma-separated constant line that
                // follows it, and the blank line after that.
                i++;
                if (i + 1 < lines.length && lines[i + 1].isBlank()) {
                    i++;
                }
                removed++;
                continue;
            }
            out.append(lines[i]);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        if (removed == 0) {
            throw new IllegalStateException("after-novocab: found no vocabulary block to strip - "
                    + "the injected-constants header must have changed shape");
        }
        return out.toString();
    }

    /** {@code Every `Attribute` constant on this server (32, exhaustive - ...):} */
    private static final Pattern VOCAB_HEADER = Pattern.compile(
            "^Every `\\w+` constant on this server \\(\\d+, exhaustive[^)]*\\):$");

    // ------------------------------------------------------------------
    // One generation
    // ------------------------------------------------------------------

    private static Gen runOne(Config cfg, Cell cell, EvalCorpus.Task task, String system,
                              ClassFileVocabulary vocab, InMemoryCompiler compiler, RunCache cache,
                              OpenRouterClient client, Ledger ledger, Path usageLog) {
        Gen gen = new Gen(cell.condition(), cell.version(), task.name());
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("user", PromptLibrary.makePrompt(task.description(), "eval")));

        String lastDiagnostics = "";
        List<String> lastNotes = List.of();
        // The project state a heal round's edits apply against, exactly as
        // ModGenerator.pipeline keeps it. Without this an edit-shaped repair
        // response parses to a project with zero files and javac is handed
        // nothing at all ("error: no source files"), which scores as a spurious
        // NEVER_GREEN. Measured: that is what the pilot's LightningTyrant round 1
        // did before this was added.
        GeneratedProject current = null;

        for (int round = 0; round <= cfg.healRounds(); round++) {
            Call call = call(cfg, cell, task, round, system, messages, cache, client, ledger, usageLog);
            gen.usd += call.costUsd();
            if (call.cacheHit()) {
                gen.cacheHits++;
            } else if (call.made()) {
                gen.apiCalls++;
            }
            if (!call.ok()) {
                if (gen.outcome.equals("PENDING")) {
                    gen.outcome = call.outcome();
                    gen.note = call.note();
                }
                return gen;
            }

            if (round == 0) {
                classifyParse(gen, call.content());
            }

            GeneratedProject project;
            try {
                GeneratedProject parsed = PromptLibrary.parse(call.content());
                project = parsed.isEditResponse() ? applyEdits(current, parsed) : merge(current, parsed);
            } catch (IllegalArgumentException e) {
                if (round == 0) {
                    gen.outcome = "UNPARSEABLE";
                    gen.note = oneLine(e.getMessage(), 120);
                    return gen;
                }
                gen.outcome = gen.everGreen ? gen.outcome : "NEVER_GREEN(unparseable heal)";
                return gen;
            }

            Map<String, String> plain = toFqcnSources(project);

            // repairOff — the sources exactly as the model wrote them.
            CompileResult off = compiler.compile(plain);

            // repairOn — the same parse, put through the offline repair pass.
            SymbolRepair.Report repairReport = SymbolRepair.repair(project, vocab);
            GeneratedProject repaired = repairReport.changed()
                    ? SymbolRepair.applyTo(project, repairReport) : project;
            CompileResult on = compiler.compile(toFqcnSources(repaired));

            if (round == 0) {
                gen.firstTryRepairOff = off.success();
                gen.firstTryRepairOn = on.success();
                gen.repairFlippedFirstTry = on.success() && !off.success();
                repairReport.rewrites().forEach(r -> gen.rewrites.add(r.toString()));
                if (!on.success()) {
                    classifyFailure(gen, on.diagnostics());
                    // The FULL diagnostics, kept on disk. `gen.note` holds one
                    // line, which is enough to eyeball a run but not enough to
                    // ask why a whole population of generations failed. The
                    // taxonomy work (missing imports vs hallucinated types vs
                    // lossy conversions) is scored offline from these dumps, so
                    // re-classifying never costs another generation.
                    dump(cfg, cell, task, "diagnostics.txt", on.diagnostics());
                    dump(cfg, cell, task, "sources.java",
                            renderSources(toFqcnSources(repaired)));
                    // ImportDoctor is a PROTOTYPE of a deterministic import-repair
                    // pass, scored here as a third arm alongside SymbolRepair
                    // on/off. It is deliberately not wired into the shipping
                    // pipeline: measure first, then decide whether it earns a place.
                    scoreImportRepair(cfg, cell, gen, compiler, toFqcnSources(repaired),
                            on.diagnostics());
                }
            }

            if (on.success()) {
                gen.everGreen = true;
                gen.roundsToGreen = round;
                gen.outcome = round == 0 ? "GREEN_FIRST_TRY" : "GREEN_AFTER_HEAL(" + round + ")";
                return gen;
            }

            lastDiagnostics = on.diagnostics();
            lastNotes = repairReport.notesFor(lastDiagnostics);
            // Repairs apply against THIS round's repaired sources, as in ModGenerator.
            current = repaired;
            if (round < cfg.healRounds()) {
                messages.add(new ChatMessage("assistant", call.content()));
                messages.add(new ChatMessage("user", PromptLibrary.repairPrompt(lastDiagnostics, lastNotes)));
            }
        }

        gen.outcome = "NEVER_GREEN";
        gen.note = oneLine(firstDiagnosticLine(lastDiagnostics), 140);
        return gen;
    }

    /** The outcome of one attempted model call: bought, cached, skipped or failed. */
    record Call(boolean ok, boolean made, boolean cacheHit, String content, double costUsd,
                String outcome, String note) {
    }

    private static Call call(Config cfg, Cell cell, EvalCorpus.Task task, int round, String system,
                             List<ChatMessage> messages, RunCache cache, OpenRouterClient client,
                             Ledger ledger, Path usageLog) {
        String key = RunCache.key(cfg.model(), system, messages);
        Optional<RunCache.Entry> hit = cache.lookup(key);
        if (hit.isPresent()) {
            ledger.cacheHits++;
            logUsage(usageLog, cell, task, round, cfg.model(), hit.get().costUsd(), true,
                    hit.get().usageJson(), key, "hit");
            // A cache hit re-reports the ORIGINAL cost for the record but adds
            // nothing to this run's spend: the money was paid on an earlier run.
            return new Call(true, false, true, hit.get().content(), 0.0, "CACHED", "");
        }

        if (cfg.dryRun() || client == null) {
            return new Call(false, false, false, null, 0.0, "SKIPPED(no cached response)",
                    cfg.dryRun() ? "dry run" : "no api key");
        }
        if (!ledger.mayCall()) {
            return new Call(false, false, false, null, 0.0, "ABORTED(" + ledger.abortReason + ")", "");
        }

        Free free = cfg.free();
        Call last = null;
        for (int attempt = 0; attempt <= free.maxRetries(); attempt++) {
            if (attempt > 0) {
                long backoff = free.retryBaseMs() << (attempt - 1);
                System.out.printf(Locale.ROOT, "      retry %d/%d in %,dms (%s)%n",
                        attempt, free.maxRetries(), backoff, last == null ? "" : last.note());
                if (!sleepMs(backoff)) {
                    return new Call(false, true, false, null, 0.0, "INTERRUPTED", "");
                }
            } else {
                throttle(free.throttleMs());
            }
            if (!ledger.mayCall()) {
                return new Call(false, false, false, null, 0.0, "ABORTED(" + ledger.abortReason + ")", "");
            }
            ledger.apiCalls++;
            try {
                OpenRouterClient.Completion completion =
                        client.complete(system, List.copyOf(messages)).get(300, TimeUnit.SECONDS);
                ledger.spentUsd += completion.costUsd();
                // A run declared free must be free. Trusting the ":free" suffix is
                // not good enough — OpenRouter can route a free id to a paid
                // provider — so the invariant is enforced against the billed
                // number on every single response, and a violation stops the run
                // instead of quietly accumulating charges.
                if (free.requireZeroCost() && completion.costUsd() > 0.0) {
                    ledger.abort(String.format(Locale.ROOT,
                            "NON-ZERO COST on a free-tier run: $%.6f for model %s. "
                            + "Stopping before anything else is billed.",
                            completion.costUsd(), cfg.model()));
                    logUsage(usageLog, cell, task, round, cfg.model(), completion.costUsd(), false,
                            usageJson(completion), key, "nonzero-cost");
                    return new Call(false, true, false, null, completion.costUsd(),
                            "ABORTED(non-zero cost)", "");
                }
                String usageJson = usageJson(completion);
                cache.store(key, cfg.model(), completion.content(), completion.costUsd(), usageJson);
                logUsage(usageLog, cell, task, round, cfg.model(), completion.costUsd(), false, usageJson,
                        key, "miss");
                return new Call(true, true, false, completion.content(), completion.costUsd(), "OK", "");
            } catch (ExecutionException e) {
                double cost = costOf(e.getCause());
                ledger.spentUsd += cost;
                String detail = String.valueOf(e.getCause());
                logUsage(usageLog, cell, task, round, cfg.model(), cost, false, null, key, "error");
                last = new Call(false, true, false, null, cost, "API_FAILURE", oneLine(detail, 200));
                if (isDailyCap(detail)) {
                    ledger.abort("FREE-TIER DAILY REQUEST CAP hit after " + ledger.apiCalls
                            + " request(s) this run: " + oneLine(detail, 200));
                    return new Call(false, true, false, null, cost, "ABORTED(daily cap)",
                            oneLine(detail, 200));
                }
                if (!isRetryable(detail)) {
                    return last;
                }
            } catch (TimeoutException e) {
                logUsage(usageLog, cell, task, round, cfg.model(), 0.0, false, null, key, "timeout");
                last = new Call(false, true, false, null, 0.0, "API_TIMEOUT", "300s");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Call(false, true, false, null, 0.0, "INTERRUPTED", "");
            }
        }
        return last;
    }

    /**
     * True for a failure worth trying again: a 429 from a shared upstream pool,
     * any 5xx, or a transport-level stall. Deliberately narrow — a 400 (bad model
     * id, prompt too long for the context window) is a fact about the request and
     * retrying it only burns the day's request budget.
     */
    private static boolean isRetryable(String detail) {
        String d = detail == null ? "" : detail;
        return d.contains("status=429") || d.contains("status=500") || d.contains("status=502")
                || d.contains("status=503") || d.contains("status=504")
                || d.contains("HttpTimeoutException") || d.contains("IOException: timeout");
    }

    /**
     * True when a 429 is the account's own free-model daily allowance rather than
     * a provider's shared pool. The two are worth telling apart: the shared pool
     * clears in seconds, the daily allowance does not clear until UTC midnight,
     * so retrying it is pure waste and the run should stop and report the count
     * it reached — which is the empirical measurement of the cap.
     */
    private static boolean isDailyCap(String detail) {
        String d = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
        if (!d.contains("status=429")) {
            return false;
        }
        return d.contains("per-day") || d.contains("per day") || d.contains("daily")
                || d.contains("free-models-per-day") || d.contains("add 10 credits")
                || d.contains("free_tier") && d.contains("limit");
    }

    /** Consecutive generations lost to the transport, for the circuit breaker. */
    private static int consecutiveTransportFailures;
    /** Trip the breaker after this many; 0 disables it. */
    private static int breakerLimit =
            Integer.parseInt(prop("vibemod.eval.breakerLimit", "0"));

    private static long lastCallAt;

    /** Space calls at least {@code minGapMs} apart, to stay under a per-minute ceiling. */
    private static void throttle(long minGapMs) {
        if (minGapMs <= 0) {
            return;
        }
        long wait = minGapMs - (System.currentTimeMillis() - lastCallAt);
        if (wait > 0) {
            sleepMs(wait);
        }
        lastCallAt = System.currentTimeMillis();
    }

    /** Sleep, returning false if the thread was interrupted. */
    private static boolean sleepMs(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The round's token accounting, as JSON for {@code usage.jsonl}.
     *
     * <p>Reads only the public {@code Completion.usage()} record. If that
     * accessor ever goes away, delete this method and pass {@code null} — the
     * log degrades to cost-only and nothing else breaks.
     */
    private static String usageJson(OpenRouterClient.Completion completion) {
        OpenRouterClient.Usage u = completion.usage();
        if (u == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("promptTokens", u.promptTokens());
        o.addProperty("completionTokens", u.completionTokens());
        o.addProperty("cachedPromptTokens", u.cachedPromptTokens());
        o.addProperty("cacheWriteTokens", u.cacheWriteTokens());
        return o.toString();
    }

    private static double costOf(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof OpenRouterClient.CostAwareException cae) {
                return cae.costUsd();
            }
        }
        return 0.0;
    }

    private static void logUsage(Path usageLog, Cell cell, EvalCorpus.Task task, int round, String model,
                                 double costUsd, boolean cacheHit, String usageJson, String key,
                                 String status) {
        JsonObject o = new JsonObject();
        o.addProperty("ts", Instant.now().toString());
        o.addProperty("condition", cell.condition());
        o.addProperty("version", cell.version());
        o.addProperty("task", task.name());
        o.addProperty("round", round);
        o.addProperty("model", model);
        o.addProperty("costUsd", costUsd);
        o.addProperty("cache", cacheHit ? "hit" : "miss");
        o.addProperty("status", status);
        o.addProperty("requestKey", key);
        if (usageJson != null) {
            try {
                o.add("usage", com.google.gson.JsonParser.parseString(usageJson));
            } catch (RuntimeException e) {
                o.addProperty("usage", usageJson);
            }
        } else {
            o.addProperty("tokens", "unavailable for this round");
        }
        try {
            Files.writeString(usageLog, o + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("  (could not append to usage.jsonl: " + e + ")");
        }
    }

    /**
     * One line of machine-readable scoring per generation, written as it happens.
     * The markdown report covers a single model; comparing models (or a run that
     * a request cap cut short) needs the raw rows, and writing them incrementally
     * means an aborted run still leaves everything it managed to score.
     */
    private static void logResult(Path resultsLog, String model, Gen gen) {
        JsonObject o = new JsonObject();
        o.addProperty("ts", Instant.now().toString());
        o.addProperty("model", model);
        o.addProperty("condition", gen.condition);
        o.addProperty("version", gen.version);
        o.addProperty("task", gen.task);
        o.addProperty("outcome", gen.outcome);
        o.addProperty("parseKind", gen.parseKind);
        o.addProperty("strictJson", gen.strictJson);
        o.addProperty("neededTolerance", gen.neededTolerance);
        o.addProperty("rawChars", gen.rawChars);
        o.addProperty("firstTryRepairOff", gen.firstTryRepairOff);
        o.addProperty("firstTryRepairOn", gen.firstTryRepairOn);
        o.addProperty("repairFlippedFirstTry", gen.repairFlippedFirstTry);
        o.addProperty("everGreen", gen.everGreen);
        o.addProperty("roundsToGreen", gen.roundsToGreen);
        o.addProperty("failureKind", gen.failureKind);
        o.addProperty("rewrites", gen.rewrites.size());
        o.addProperty("importRepairGreen", gen.importRepairGreen);
        o.addProperty("importsAdded", String.join(";", gen.importsAdded));
        o.addProperty("importsUnresolved", String.join(";", gen.importsUnresolved));
        o.addProperty("missingSymbols", String.join(",", gen.missingSymbols));
        o.addProperty("usd", gen.usd);
        o.addProperty("apiCalls", gen.apiCalls);
        o.addProperty("cacheHits", gen.cacheHits);
        o.addProperty("note", gen.note);
        try {
            Files.writeString(resultsLog, o + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("  (could not append to results.jsonl: " + e + ")");
        }
    }

    private static final Pattern PACKAGE_DECL = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;",
            Pattern.MULTILINE);

    /**
     * Apply an edit-shaped response to the current project state, mirroring
     * {@code ModGenerator.applyEdits}. Only the file set matters here — the eval
     * compiles, it never stores or loads — so the descriptive fields are carried
     * over from {@code current} rather than merged field by field.
     *
     * <p>The strictness is deliberate and copied: an edit whose {@code find} text
     * is missing, or matches twice, is an {@link IllegalArgumentException}, which
     * the caller scores the same way the real pipeline treats an unusable
     * response. Silently applying an ambiguous edit would let the eval report a
     * green the shipping code would never have reached.
     */
    private static GeneratedProject applyEdits(GeneratedProject current, GeneratedProject editResponse) {
        if (current == null) {
            throw new IllegalArgumentException(
                    "an edits response is not allowed here - there are no current sources to edit");
        }
        Map<String, String> byPath = new LinkedHashMap<>();
        for (GeneratedProject.GeneratedFile f : current.files()) {
            byPath.put(baseName(f.path()), f.content());
        }
        for (GeneratedProject.EditBlock edit : editResponse.edits()) {
            String path = baseName(edit.path());
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
        }
        List<GeneratedProject.GeneratedFile> files = byPath.entrySet().stream()
                .map(e -> new GeneratedProject.GeneratedFile(e.getKey(), e.getValue()))
                .toList();
        return new GeneratedProject(current.name(), current.description(), current.usage(),
                current.manual(), current.changelog(), current.icon(), current.mainClass(),
                files, current.config(), List.of());
    }

    /** Full-project heal response: keep it, but never let it drop to zero files. */
    private static GeneratedProject merge(GeneratedProject previous, GeneratedProject full) {
        if (previous == null) {
            return full;
        }
        return (full.files() == null || full.files().isEmpty()) ? previous : full;
    }

    /** The part of a path after the last separator. */
    private static String baseName(String path) {
        if (path == null) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * The types whose constant vocabulary this whole body of work is about
     * (docs/API-VOCABULARY.md). A "cannot find symbol" naming one of these is
     * the failure class the prompt rebuild and SymbolRepair were built to kill.
     */
    private static final java.util.Set<String> VOCABULARY_TYPES = java.util.Set.of(
            "Attribute", "Enchantment", "PotionEffectType", "Particle", "Sound",
            "Material", "EntityType");

    /** {@code symbol: variable GENERIC_MAX_HEALTH} / {@code location: class org.bukkit.attribute.Attribute} */
    private static final Pattern SYMBOL_LINE =
            Pattern.compile("symbol:\\s+(?:variable|method|class)\\s+([A-Za-z0-9_]+)");
    private static final Pattern LOCATION_LINE =
            Pattern.compile("location:\\s+(?:class|interface|enum)\\s+([\\w.]+)");

    /**
     * Split a round-0 compile failure into the class this work targets and
     * everything else.
     *
     * <p>The headline first-try rate is a noisy aggregate: a generation can fail
     * for a nonexistent enum constant, or because the model wrote a genuine
     * logic or API-shape error that no prompt would have prevented. Only the
     * first kind is what a truthful vocabulary can fix, so it is counted
     * separately. Three buckets:
     *
     * <ul>
     *   <li>{@code VOCAB_SYMBOL} — "cannot find symbol" on a constant of a type
     *       in {@link #VOCABULARY_TYPES}. The targeted class: the exact defect
     *       the old prompt caused on 1.21.3-1.21.6 by prescribing
     *       {@code Attribute.GENERIC_MAX_HEALTH} where only the short name exists.
     *   <li>{@code OTHER_SYMBOL} — "cannot find symbol" on anything else: a
     *       method that does not exist, a misremembered class. Still a
     *       vocabulary-shaped error, still plausibly promptable, but not the
     *       measured defect.
     *   <li>{@code NON_SYMBOL} — everything else: incompatible types, wrong
     *       arity, unreported exceptions. Ordinary bad code.
     * </ul>
     */
    private static void classifyFailure(Gen gen, String diagnostics) {
        String diag = diagnostics == null ? "" : diagnostics;
        if (!diag.contains("cannot find symbol")) {
            gen.failureKind = "NON_SYMBOL";
            return;
        }
        Matcher sym = SYMBOL_LINE.matcher(diag);
        Matcher loc = LOCATION_LINE.matcher(diag);
        boolean vocab = false;
        while (sym.find()) {
            String name = sym.group(1);
            String owner = loc.find() ? loc.group(1) : "";
            String simple = owner.contains(".") ? owner.substring(owner.lastIndexOf('.') + 1) : owner;
            if (VOCABULARY_TYPES.contains(simple)) {
                vocab = true;
                if (!gen.missingSymbols.contains(simple + "." + name)) {
                    gen.missingSymbols.add(simple + "." + name);
                }
            } else if (gen.missingSymbols.size() < 6
                    && !gen.missingSymbols.contains(simple + "." + name)) {
                gen.missingSymbols.add((simple.isEmpty() ? "?" : simple) + "." + name);
            }
        }
        gen.failureKind = vocab ? "VOCAB_SYMBOL" : "OTHER_SYMBOL";
    }

    /**
     * Score a round-0 response for JSON adherence, separately from whether the
     * Java inside it compiles. On a weak model these are different questions and
     * conflating them hides the one that decides usability: a model that cannot
     * emit the response envelope never reaches the compiler at all, and no amount
     * of platform grounding in the system prompt can rescue it.
     *
     * <p>Buckets:
     * <ul>
     *   <li>{@code PARSED} — {@link PromptLibrary#parse} accepted it.
     *   <li>{@code NO_JSON} — no {@code '{'} anywhere. Prose, a refusal, or
     *       reasoning where the answer should have been.
     *   <li>{@code UNBALANCED} — a {@code '{'} with no matching close. In practice
     *       this is truncation: the model ran out of output budget mid-object.
     *   <li>{@code INVALID_JSON} — balanced braces, but Gson rejected it.
     *   <li>{@code SCHEMA} — valid JSON, wrong shape (no non-empty {@code files}
     *       or {@code edits}, a bad field type, ...). The model understood
     *       "JSON" but not the contract.
     * </ul>
     *
     * <p>Also recorded: whether the response was bare JSON ({@code strictJson}),
     * and whether it only parsed <em>because</em> {@code PromptLibrary} tolerates
     * markdown fences and leading/trailing prose ({@code neededTolerance}). That
     * second flag is the measurement of how much the existing tolerance is
     * already earning — it is easy to assume a strict parser and then attribute
     * to the model a failure the parser would have absorbed.
     */
    private static void classifyParse(Gen gen, String content) {
        String raw = content == null ? "" : content;
        gen.rawChars = raw.length();
        String trimmed = raw.strip();
        gen.strictJson = trimmed.startsWith("{") && trimmed.endsWith("}");

        boolean parsed;
        try {
            PromptLibrary.parse(raw);
            parsed = true;
        } catch (IllegalArgumentException e) {
            parsed = false;
        }
        if (parsed) {
            gen.parseKind = "PARSED";
            gen.neededTolerance = !gen.strictJson;
            return;
        }

        gen.neededTolerance = false;
        if (trimmed.indexOf('{') < 0) {
            gen.parseKind = "NO_JSON";
            return;
        }
        String candidate = balancedFrom(trimmed);
        if (candidate == null) {
            gen.parseKind = "UNBALANCED";
            return;
        }
        try {
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(candidate);
            gen.parseKind = el.isJsonObject() ? "SCHEMA" : "INVALID_JSON";
        } catch (RuntimeException e) {
            gen.parseKind = "INVALID_JSON";
        }
    }

    /**
     * The first balanced {@code {...}} in {@code text}, or null when the braces
     * never close. Mirrors {@code PromptLibrary.extractBalancedJsonObject} (which
     * is private) purely so a failure can be told apart from a truncation; it is
     * never used to feed the pipeline.
     */
    private static String balancedFrom(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        boolean inString = false;
        boolean escape = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return text.substring(start, i + 1);
            }
        }
        return null;
    }

    /** {@code ModGenerator.toFqcnSources}, copied verbatim so the eval compiles what the host would. */
    static Map<String, String> toFqcnSources(GeneratedProject project) {
        Map<String, String> out = new LinkedHashMap<>();
        for (GeneratedProject.GeneratedFile f : project.files()) {
            String simple = fileName(f.path()).replaceAll("\\.java$", "");
            Matcher m = PACKAGE_DECL.matcher(f.content());
            String pkg = m.find() ? m.group(1) : "vibemod." + project.name().toLowerCase();
            out.put(pkg + "." + simple, f.content());
        }
        return out;
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * TEST FIXTURE, not part of a real run: fabricates the round-0 cache entry
     * for every cell from one stored mod's real sources, so the scoring half of
     * the harness — parse, SymbolRepair, both compiles, rounds-to-green, the
     * whole report — can be exercised end to end without spending a cent.
     *
     * <p>Point it at a scratch {@code -Dvibemod.eval.out} so it never
     * contaminates the cache of a run you paid for. It shouts on every entry it
     * writes and marks each one {@code synthetic} in its usage field.
     */
    private static void seedCacheFixture(Config cfg, Map<Cell, String> systemPrompts,
                                         List<EvalCorpus.Task> subset, RunCache cache,
                                         String modName) throws IOException {
        Path modDir = cfg.modsDir().resolve(modName);
        Map<String, String> sources = EvalCorpus.storedSources(modDir);
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("no stored sources for fixture mod " + modName);
        }
        System.out.println("!!! SEEDING SYNTHETIC CACHE ENTRIES from " + modDir + " !!!");
        System.out.println("!!! This is a harness self-test fixture. Never do this into a real cache. !!!");

        JsonObject response = new JsonObject();
        response.addProperty("name", modName);
        response.addProperty("description", "synthetic fixture built from the stored " + modName);
        response.addProperty("mainClass", sources.keySet().iterator().next());
        response.addProperty("usage", "n/a");
        response.addProperty("changelog", "synthetic");
        com.google.gson.JsonArray files = new com.google.gson.JsonArray();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            JsonObject f = new JsonObject();
            String simple = e.getKey().substring(e.getKey().lastIndexOf('.') + 1);
            f.addProperty("path", simple + ".java");
            f.addProperty("content", e.getValue());
            files.add(f);
        }
        response.add("files", files);
        String body = response.toString();

        for (Cell cell : cfg.cells()) {
            for (EvalCorpus.Task task : subset) {
                List<ChatMessage> messages = List.of(
                        new ChatMessage("user", PromptLibrary.makePrompt(task.description(), "eval")));
                String key = RunCache.key(cfg.model(), systemPrompts.get(cell), messages);
                cache.store(key, cfg.model(), body, 0.0, "{\"synthetic\":true}");
                System.out.println("    seeded " + cell + " / " + task.name() + " -> " + key);
            }
        }
        System.out.println();
    }

    // ------------------------------------------------------------------
    // Self-check: prove the compile path works with no API at all
    // ------------------------------------------------------------------

    /**
     * Takes three real stored mods and compiles them through
     * {@link VersionClasspath} against every version named in the cells. On the
     * version the corpus was generated for this must be green; on an older API
     * some mods legitimately will not compile, and that is a measurement, not a
     * bug.
     */
    private static void selfCheck(Config cfg) throws IOException {
        System.out.println("=== SELF-CHECK: the compile path, with no API ===");
        List<String> versions = new ArrayList<>(new LinkedHashSet<>(
                cfg.cells().stream().map(Cell::version).toList()));
        if (versions.isEmpty()) {
            versions = List.of("1.21.8");
        }

        List<Path> mods = new ArrayList<>();
        try (var dirs = Files.list(cfg.modsDir())) {
            for (Path d : dirs.sorted().toList()) {
                if (Files.isDirectory(d) && Files.isReadable(d.resolve("meta.json"))) {
                    mods.add(d);
                }
            }
        }
        int wanted = Integer.parseInt(prop("vibemod.eval.selfCheckN", "3"));
        List<Path> picked = new ArrayList<>();
        for (int i = 0; i < wanted && !mods.isEmpty(); i++) {
            picked.add(mods.get(i * mods.size() / wanted));
        }

        Map<String, String> probe = EvalCorpus.storedSources(picked.get(0));
        VersionClasspath.reportComposition(cfg.jarsDir(), versions.get(0), probe);

        CompilerProvider provider = CompilerProvider.resolve()
                .orElseThrow(() -> new IllegalStateException("no java compiler on this JVM"));
        StringBuilder md = new StringBuilder("# Self-check\n\n");
        for (String version : versions) {
            System.out.println("--- " + version + " ---");
            md.append("## ").append(version).append("\n\n");
            InMemoryCompiler compiler =
                    new InMemoryCompiler(provider, new VersionClasspath(cfg.jarsDir(), version));
            for (Path mod : picked) {
                Map<String, String> sources = EvalCorpus.storedSources(mod);
                CompileResult r = compiler.compile(sources);
                System.out.printf(Locale.ROOT, "  %-24s %2d file(s)  %s%n",
                        mod.getFileName(), sources.size(), r.success() ? "GREEN" : "RED");
                md.append("- `").append(mod.getFileName()).append("` (")
                        .append(sources.size()).append(" files): ")
                        .append(r.success() ? "GREEN" : "RED").append("\n");
                if (!r.success()) {
                    for (String line : VersionClasspath.firstLines(r.diagnostics(), 6)) {
                        System.out.println("      " + line);
                        md.append("  - `").append(line.replace("`", "'")).append("`\n");
                    }
                }
            }
            md.append("\n");
        }
        Files.writeString(cfg.outDir().resolve("self-check.md"), md.toString(), StandardCharsets.UTF_8);
        System.out.println();
        System.out.println("Self-check written to " + cfg.outDir().resolve("self-check.md"));
    }

    // ------------------------------------------------------------------
    // Report
    // ------------------------------------------------------------------

    private static String buildReport(Config cfg, int corpusSize, List<EvalCorpus.Task> subset,
                                      Map<Cell, String> systemPrompts, List<Gen> results,
                                      Ledger ledger, RunCache cache) {
        StringBuilder md = new StringBuilder();
        md.append("# VibeMod compile-rate eval (B5)\n\n");
        md.append("Generated ").append(Instant.now()).append("\n\n");

        md.append("## Configuration\n\n");
        md.append("| setting | value |\n|---|---|\n");
        md.append("| model | `").append(cfg.model()).append("` |\n");
        md.append("| n per cell | ").append(cfg.n()).append(" |\n");
        md.append("| cells | ").append(cfg.cells()).append(" |\n");
        md.append("| heal rounds | ").append(cfg.healRounds()).append(" |\n");
        md.append("| budget | $").append(String.format(Locale.ROOT, "%.2f", cfg.budgetUsd())).append(" |\n");
        md.append("| dry run | ").append(cfg.dryRun()).append(" |\n");
        md.append("| pilot cap | ").append(cfg.pilotCalls() < 0 ? "off" : cfg.pilotCalls()).append(" |\n");
        md.append("| seed | ").append(cfg.seed()).append(" |\n");
        md.append("| api jars | `").append(cfg.jarsDir()).append("` |\n");
        md.append("| corpus | `").append(cfg.modsDir()).append("` (").append(corpusSize)
                .append(" usable mods) |\n");
        md.append("\nThe API key is never printed, logged or written by this harness.\n\n");

        md.append("## System prompts\n\n");
        md.append("| condition | version | profile | chars |\n|---|---|---|---:|\n");
        for (Map.Entry<Cell, String> e : systemPrompts.entrySet()) {
            Cell c = e.getKey();
            String profile = "before".equals(c.condition())
                    ? LegacyPrompt.profileIdFor(c.version()) : EvalFacts.profileIdFor(c.version());
            md.append("| ").append(c.condition()).append(" | ").append(c.version()).append(" | ")
                    .append(profile).append(" | ").append(e.getValue().length()).append(" |\n");
        }
        md.append("\nThe `before` prompts are compiled out of git at `").append(LegacyPrompt.REBUILD_COMMIT)
                .append("^`, never retyped. Only the system prompt differs between arms: ")
                .append("`makePrompt`, `parse` and `demandFullProject` are byte-identical across the rebuild.\n\n");

        md.append("## Tasks\n\n");
        for (EvalCorpus.Task t : subset) {
            md.append("- **").append(t.name()).append("** — ").append(oneLine(t.description(), 160)).append("\n");
        }
        md.append("\n");

        // --- main table ------------------------------------------------
        md.append("## First-try compile rate (SymbolRepair ON — the shipping pipeline)\n\n");
        md.append("| condition | version | first-try k/n | mean rounds-to-green | never green | skipped "
                + "| USD bought this run |\n");
        md.append("|---|---|---|---|---|---|---:|\n");
        for (Cell c : cfg.cells()) {
            List<Gen> cell = cellOf(results, c);
            int n = cell.size();
            long first = cell.stream().filter(g -> g.firstTryRepairOn).count();
            List<Gen> green = cell.stream().filter(g -> g.everGreen).toList();
            String mean = green.isEmpty() ? "—"
                    : String.format(Locale.ROOT, "%.2f",
                            green.stream().mapToInt(g -> g.roundsToGreen).average().orElse(0));
            long never = cell.stream().filter(g -> !g.everGreen && !g.outcome.startsWith("SKIPPED")).count();
            long skipped = cell.stream().filter(g -> g.outcome.startsWith("SKIPPED")).count();
            double usd = cell.stream().mapToDouble(g -> g.usd).sum();
            md.append("| ").append(c.condition()).append(" | ").append(c.version()).append(" | ")
                    .append(first).append("/").append(n).append(" | ").append(mean).append(" | ")
                    .append(never).append(" | ").append(skipped).append(" | ")
                    .append(String.format(Locale.ROOT, "%.4f", usd)).append(" |\n");
        }
        md.append("\n");

        // --- SymbolRepair table ----------------------------------------
        // --- targeted-failure breakdown ------------------------------------
        // The headline rate mixes together failures a truthful vocabulary can
        // fix and failures no prompt would have prevented. This is the
        // mechanism, and it should move more cleanly than the headline.
        md.append("## Round-0 failures by kind (repair ON)\n\n");
        md.append("`VOCAB_SYMBOL` is the targeted class: `cannot find symbol` on a constant of ");
        md.append("Attribute / Enchantment / PotionEffectType / Particle / Sound / Material / EntityType. ");
        md.append("`OTHER_SYMBOL` is any other missing symbol; `NON_SYMBOL` is ordinary bad code.\n\n");
        md.append("| condition | version | scored | green | VOCAB_SYMBOL | OTHER_SYMBOL | NON_SYMBOL |\n");
        md.append("|---|---|---:|---:|---:|---:|---:|\n");
        for (Cell cell : cfg.cells()) {
            List<Gen> gens = cellOf(results, cell).stream()
                    .filter(g -> !g.outcome.startsWith("SKIPPED") && !g.outcome.startsWith("ABORTED"))
                    .toList();
            long green = gens.stream().filter(g -> g.firstTryRepairOn).count();
            long vocab = gens.stream().filter(g -> "VOCAB_SYMBOL".equals(g.failureKind)).count();
            long other = gens.stream().filter(g -> "OTHER_SYMBOL".equals(g.failureKind)).count();
            long plain = gens.stream().filter(g -> "NON_SYMBOL".equals(g.failureKind)).count();
            md.append(String.format(Locale.ROOT, "| %s | %s | %d | %d | %d | %d | %d |%n",
                    cell.condition(), cell.version(), gens.size(), green, vocab, other, plain));
        }
        md.append("\n");

        // --- JSON adherence -------------------------------------------------
        // Upstream of everything else. A response that does not parse never
        // reaches javac, so on a weak model this can be the whole story and the
        // compile table below is scored on a self-selected survivor set.
        md.append("## Round-0 JSON adherence (upstream of any compile)\n\n");
        md.append("`bare` = the response was exactly one JSON object, no fence and no prose. ");
        md.append("`tolerated` = it parsed only because `PromptLibrary.parse` strips ``` fence lines ");
        md.append("and extracts the first balanced `{...}`. `NO_JSON` / `UNBALANCED` (truncated) / ");
        md.append("`INVALID_JSON` / `SCHEMA` (valid JSON, wrong shape) are the ways it failed.\n\n");
        md.append("| condition | version | responses | parsed | bare | tolerated | NO_JSON | UNBALANCED "
                + "| INVALID_JSON | SCHEMA | no response |\n");
        md.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Cell cell : cfg.cells()) {
            List<Gen> gens = cellOf(results, cell);
            long got = gens.stream().filter(g -> !g.parseKind.isEmpty()).count();
            long none = gens.size() - got;
            md.append(String.format(Locale.ROOT,
                    "| %s | %s | %d | %d | %d | %d | %d | %d | %d | %d | %d |%n",
                    cell.condition(), cell.version(), got,
                    countParse(gens, "PARSED"),
                    gens.stream().filter(g -> "PARSED".equals(g.parseKind) && g.strictJson).count(),
                    gens.stream().filter(g -> g.neededTolerance).count(),
                    countParse(gens, "NO_JSON"), countParse(gens, "UNBALANCED"),
                    countParse(gens, "INVALID_JSON"), countParse(gens, "SCHEMA"), none));
        }
        md.append("\n`no response` counts generations with nothing to score: skipped, aborted, ");
        md.append("timed out, or failed at the transport (which includes a provider-side truncation, ");
        md.append("reported by `OpenRouterClient` as `finish_reason=length` rather than as content).\n\n");

        List<String> missing = new ArrayList<>();
        for (Cell cell : cfg.cells()) {
            for (Gen g : cellOf(results, cell)) {
                if ("VOCAB_SYMBOL".equals(g.failureKind)) {
                    missing.add(cell.condition() + " " + cell.version() + " " + g.task + ": "
                            + String.join(", ", g.missingSymbols));
                }
            }
        }
        if (missing.isEmpty()) {
            md.append("No round-0 failure named a missing vocabulary constant.\n\n");
        } else {
            md.append("Vocabulary constants the model named that do not exist on the target version:\n\n");
            for (String line : missing) {
                md.append("- `").append(line).append("`\n");
            }
            md.append("\n");
        }

        md.append("## SymbolRepair: on vs off (same parses, scored twice)\n\n");
        md.append("| condition | version | repair OFF k/n | repair ON k/n | flipped fail→pass |\n");
        md.append("|---|---|---|---|---|\n");
        List<Gen> flips = new ArrayList<>();
        for (Cell c : cfg.cells()) {
            List<Gen> cell = cellOf(results, c);
            long off = cell.stream().filter(g -> g.firstTryRepairOff).count();
            long on = cell.stream().filter(g -> g.firstTryRepairOn).count();
            long flipped = cell.stream().filter(g -> g.repairFlippedFirstTry).count();
            cell.stream().filter(g -> g.repairFlippedFirstTry).forEach(flips::add);
            md.append("| ").append(c.condition()).append(" | ").append(c.version()).append(" | ")
                    .append(off).append("/").append(cell.size()).append(" | ")
                    .append(on).append("/").append(cell.size()).append(" | ")
                    .append(flipped).append(" |\n");
        }
        md.append("\n");
        if (flips.isEmpty()) {
            md.append("No generation was flipped from fail to pass by SymbolRepair in this run.\n\n");
        } else {
            md.append("Generations SymbolRepair rescued, and what it rewrote:\n\n");
            for (Gen g : flips) {
                md.append("- `").append(g.condition).append(":").append(g.version).append("` **")
                        .append(g.task).append("**\n");
                for (String r : g.rewrites) {
                    md.append("  - ").append(r).append("\n");
                }
            }
            md.append("\n");
        }

        // --- rewrites everywhere ---------------------------------------
        long totalRewrites = results.stream().mapToLong(g -> g.rewrites.size()).sum();
        md.append("SymbolRepair made **").append(totalRewrites)
                .append("** rewrite(s) across all round-0 generations.\n\n");

        // --- per-generation --------------------------------------------
        md.append("## Every generation\n\n");
        md.append("| condition | version | task | outcome | off | on | rounds | USD | note |\n");
        md.append("|---|---|---|---|---|---|---|---:|---|\n");
        for (Gen g : results) {
            md.append("| ").append(g.condition).append(" | ").append(g.version).append(" | ")
                    .append(g.task).append(" | ").append(g.outcome).append(" | ")
                    .append(g.firstTryRepairOff ? "Y" : "n").append(" | ")
                    .append(g.firstTryRepairOn ? "Y" : "n").append(" | ")
                    .append(g.roundsToGreen < 0 ? "—" : String.valueOf(g.roundsToGreen)).append(" | ")
                    .append(String.format(Locale.ROOT, "%.4f", g.usd)).append(" | ")
                    .append(g.note.isBlank() ? "" : "`" + g.note.replace("|", "/").replace("`", "'") + "`")
                    .append(" |\n");
        }
        md.append("\n");

        // --- money -------------------------------------------------------
        md.append("## Spend\n\n");
        md.append("- Total this run: **$").append(String.format(Locale.ROOT, "%.4f", ledger.spentUsd))
                .append("** of a $").append(String.format(Locale.ROOT, "%.2f", cfg.budgetUsd()))
                .append(" budget\n");
        md.append("- API calls made: ").append(ledger.apiCalls).append("\n");
        md.append("- Cache hits: ").append(cache.hits()).append(" (misses ").append(cache.misses()).append(")\n");
        if (ledger.aborted) {
            md.append("- **RUN ABORTED**: ").append(ledger.abortReason).append("\n");
        }
        if (cfg.pilotCalls() >= 0 && ledger.apiCalls > 0) {
            double per = ledger.spentUsd / ledger.apiCalls;
            int fullCalls = cfg.cells().size() * subset.size() * (1 + cfg.healRounds());
            md.append("- Measured cost per API call: $").append(String.format(Locale.ROOT, "%.4f", per))
                    .append("\n");
            md.append("- Projected worst case for the full grid (")
                    .append(cfg.cells().size()).append(" cells x ").append(subset.size())
                    .append(" tasks x ").append(1 + cfg.healRounds()).append(" rounds = ")
                    .append(fullCalls).append(" calls): **$")
                    .append(String.format(Locale.ROOT, "%.2f", per * fullCalls)).append("**\n");
        }
        md.append("- Token counts are in `usage.jsonl` per call (prompt, completion, cached-prompt and\n");
        md.append("  cache-write), read from `OpenRouterClient.Completion.usage()`. Cache-hit rounds log\n");
        md.append("  the usage stored when the response was originally bought.\n\n");

        // --- noise -------------------------------------------------------
        md.append("## Read this before believing any difference above\n\n");
        int n = subset.size();
        md.append("**SAMPLE SIZE IS ").append(n).append(" PER CELL.** With ").append(n)
                .append(" trials, the 95% normal-approximation half-width on a single rate near 50% is ")
                .append(String.format(Locale.ROOT, "%.0f", 100 * 1.96 * Math.sqrt(0.25 / Math.max(n, 1))))
                .append(" percentage points, i.e. about ")
                .append(String.format(Locale.ROOT, "%.1f", 1.96 * Math.sqrt(0.25 * n)))
                .append(" successes out of ").append(n)
                .append(". Differences smaller than that are within noise and mean nothing.\n\n");

        md.append("| version | before k/n | after k/n | observed diff | 95% interval on the diff | verdict |\n");
        md.append("|---|---|---|---|---|---|\n");
        Set<String> versions = new LinkedHashSet<>(cfg.cells().stream().map(Cell::version).toList());
        for (String v : versions) {
            List<Gen> before = cellOf(results, new Cell("before", v));
            List<Gen> after = cellOf(results, new Cell("after", v));
            if (before.isEmpty() || after.isEmpty()) {
                continue;
            }
            long kb = before.stream().filter(g -> g.firstTryRepairOn).count();
            long ka = after.stream().filter(g -> g.firstTryRepairOn).count();
            double pb = kb / (double) before.size();
            double pa = ka / (double) after.size();
            double diff = pa - pb;
            double se = Math.sqrt(pb * (1 - pb) / before.size() + pa * (1 - pa) / after.size());
            double half = 1.96 * se;
            // "Extreme" means a clean sweep in one arm and a shut-out in the
            // other, on at least four trials — the only shape at these counts
            // that is not comfortably explained by luck. Everything else, the
            // degenerate se==0 cases included, reads as noise.
            boolean sweep = before.size() >= 4 && after.size() >= 4
                    && (kb == 0 && ka == after.size() || ka == 0 && kb == before.size());
            boolean excludesZero = se > 0 && Math.abs(diff) > half;
            String verdict;
            if (sweep) {
                verdict = "every trial flipped — extreme, but still only "
                        + before.size() + " per cell; run more before believing it";
            } else if (excludesZero) {
                verdict = "outside the naive interval — still only " + before.size()
                        + " per cell; treat as a hint, not a result";
            } else {
                verdict = "**within noise**";
            }
            md.append("| ").append(v).append(" | ").append(kb).append("/").append(before.size())
                    .append(" | ").append(ka).append("/").append(after.size()).append(" | ")
                    .append(String.format(Locale.ROOT, "%+.0f pp", 100 * diff)).append(" | ")
                    .append(String.format(Locale.ROOT, "%+.0f pp ± %.0f", 100 * diff, 100 * half))
                    .append(" | ").append(verdict).append(" |\n");
        }
        md.append("\nNo significance is claimed. The interval above is a two-proportion normal ")
                .append("approximation, which is itself unreliable at these counts; it is printed to show ")
                .append("how wide the uncertainty is, not to pass a test.\n");

        return md.toString();
    }

    /**
     * Write one artefact of a round-0 failure under {@code outDir/gens/}. Named
     * by cell and task so a whole run's failures can be swept offline.
     */
    private static void dump(Config cfg, Cell cell, EvalCorpus.Task task, String file, String body) {
        try {
            Path dir = cfg.outDir().resolve("gens")
                    .resolve(cell.condition() + "__" + cell.version() + "__" + task.name());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(file), body == null ? "" : body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("  (could not dump " + file + ": " + e + ")");
        }
    }

    /** Every source in one text, each preceded by a {@code // === fqcn ===} banner. */
    private static String renderSources(Map<String, String> sources) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            sb.append("// === ").append(e.getKey()).append(" ===\n").append(e.getValue()).append("\n\n");
        }
        return sb.toString();
    }

    /** Per-version ImportDoctor indexes, built once and reused across generations. */
    private static final Map<String, ImportDoctor> DOCTORS = new LinkedHashMap<>();

    /**
     * Score the deterministic import-repair prototype on a round-0 failure: add
     * the imports javac says are missing, recompile, and record whether that
     * alone would have turned the generation green.
     *
     * <p>Scored, never shipped. A missing {@code import java.util.UUID;} is a
     * mechanical defect the host can fix for free, exactly as {@code SymbolRepair}
     * already fixes renamed constants — but "could fix" and "should fix" are
     * different claims, and this is what tells them apart. It never mutates the
     * generation's real outcome; {@code firstTryRepairOn} stays the shipping number.
     */
    private static void scoreImportRepair(Config cfg, Cell cell, Gen gen, InMemoryCompiler compiler,
                                          Map<String, String> sources, String diagnostics) {
        try {
            ImportDoctor doctor = DOCTORS.computeIfAbsent(cell.version(), v -> {
                try {
                    return ImportDoctor.forVersion(cfg.jarsDir(), v);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            ImportDoctor.Result r = doctor.repairImports(sources, diagnostics);
            gen.importsAdded.addAll(r.added());
            gen.importsUnresolved.addAll(r.unresolved());
            if (!r.added().isEmpty()) {
                gen.importRepairGreen = compiler.compile(r.sources()).success();
            }
        } catch (RuntimeException e) {
            System.out.println("  (import-repair probe failed: " + e.getClass().getSimpleName() + ")");
        }
    }

    private static long countParse(List<Gen> gens, String kind) {
        return gens.stream().filter(g -> kind.equals(g.parseKind)).count();
    }

    private static List<Gen> cellOf(List<Gen> results, Cell c) {
        return results.stream()
                .filter(g -> g.condition.equals(c.condition()) && g.version.equals(c.version()))
                .toList();
    }

    private static String firstDiagnosticLine(String diagnostics) {
        if (diagnostics == null) {
            return "";
        }
        for (String line : diagnostics.split("\n")) {
            if (!line.isBlank()) {
                return line.trim();
            }
        }
        return "";
    }

    private static String oneLine(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }
}
