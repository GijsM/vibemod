package eval;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import symbols.ClassFileVocabulary;

/**
 * Tells a <em>missing import</em> apart from a <em>hallucinated type</em>, and
 * prototypes the deterministic repair for the first of the two.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The compile-rate eval keeps finding that free and weak models do not fail at
 * Bukkit — they fail at Java. The single largest failure class in the corpus is a
 * body that uses {@code UUID}, {@code World}, {@code Block}, {@code BlockFace},
 * {@code Entity}, {@code EntityType}, {@code Material} or {@code BlockData} and
 * never emits the {@code import} line for it. Every one of those is a symbol the
 * model <em>knew</em>: the call sites are correct, the type is real, the file
 * simply does not name it at the top. That is not a knowledge failure and it is
 * not worth a repair round (a full system prompt, all sources and all diagnostics
 * re-sent) — it is a mechanical edit that the harness can do for free and
 * offline, exactly like {@link com.gijsm.vibemod.gen.SymbolRepair} does for
 * misspelled constants.
 *
 * <p>Underneath that class sits a second one that looks identical in the
 * diagnostics and must never be treated the same way: {@code BlockPos} and
 * {@code BlockBox} are Minecraft/NMS and Fabric names. No import can fix them,
 * because the type does not exist on a Bukkit compile classpath at all. Inventing
 * an import for a type that is not there turns one error into two. So the
 * separator is not a heuristic about the name — it is a lookup against the real
 * compile classpath: the {@code paper-api} jar for the version under test plus
 * the JDK. Resolves means missing import; does not resolve means the model made
 * it up, and the eval should score it as a knowledge failure.
 *
 * <h2>Why it is here and not in {@code SymbolRepair}</h2>
 *
 * <p>Deliberately a test-tree prototype. The point of the eval is to measure
 * whether an import pass would move the compile rate <em>before</em> anything
 * ships; putting the experiment inside the shipping repair pass would mean the
 * measurement and the thing being measured could never disagree. {@code
 * SymbolRepair} also owns a strictly different question — {@code symbol: variable
 * X} — and this class ignores that shape entirely so the two can be scored apart.
 *
 * <h2>Why ambiguity is answered with null</h2>
 *
 * <p>{@link #resolve} returns null rather than a best guess whenever two
 * fully-qualified names claim one simple name. A missing import costs one
 * compile error; a <em>wrong</em> import costs that same error plus a new one,
 * and it can silently bind a call to the wrong type. Leaving it alone is strictly
 * cheaper than guessing, and the eval would rather under-repair and measure the
 * true ceiling than over-repair and flatter itself.
 *
 * <p>One exception, and it is not a guess: when exactly one of the claimants sits
 * under {@code org.bukkit}, that one wins. A generated Bukkit mod that writes
 * {@code World} cannot mean {@code com.destroystokyo.…$World}; there is only one
 * name it could have meant, and the package layout says which. Two bukkit
 * claimants, or none, and the name stays ambiguous.
 *
 * <h2>main()</h2>
 *
 * <pre>
 *   java eval.ImportDoctor paper/api-jars 1.21.4
 *   java eval.ImportDoctor paper/api-jars 1.21.4 UUID BlockPos
 * </pre>
 */
public final class ImportDoctor {

    /**
     * Where a bare simple name is looked for in the JDK, in order, first hit
     * wins. Fixed and short on purpose: this is the set of packages a Minecraft
     * mod actually reaches for, and an unbounded search would resolve
     * {@code Element} or {@code List} to something from a corner of the platform
     * no generated mod has ever meant. {@code java.lang} is first because it is
     * what javac itself would have found.
     */
    private static final List<String> JDK_PACKAGES = List.of(
            "java.lang",
            "java.util",
            "java.util.function",
            "java.util.concurrent",
            "java.util.concurrent.atomic",
            "java.util.stream",
            "java.io",
            "java.nio.file",
            "java.time",
            "java.text",
            "java.math");

    /**
     * The diagnostic shape this parser targets is the one
     * {@link com.gijsm.vibemod.compile.InMemoryCompiler#formatDiagnostics} emits
     * into {@link com.gijsm.vibemod.compile.CompileResult#diagnostics()}, which
     * is what the eval actually has in hand:
     *
     * <pre>
     *   [ERROR] /com/example/Mod.java:12 - cannot find symbol
     *     symbol:   class UUID
     *     location: class com.example.Mod
     * </pre>
     *
     * <p>The header line is built by that formatter ({@code '[' + kind + "] " +
     * sourceName + ':' + line + " - " + message}); the two indented lines are not
     * ours at all — they are part of javac's own rendering of the message, which
     * is why they arrive glued to the first line of a single diagnostic entry.
     * The line number is greedy-matched off the end so a source name containing a
     * colon cannot eat it.
     */
    private static final Pattern FORMATTED_HEADER =
            Pattern.compile("^\\[[A-Z]+\\]\\s+(.+):(-?\\d+)\\s+-\\s+(.*)$");

    /**
     * javac's own console shape, {@code path/File.java:12: error: cannot find
     * symbol}. The harness does not produce this today, but a run captured from a
     * command-line javac or from a different backend does, and accepting it costs
     * one regex.
     */
    private static final Pattern RAW_HEADER =
            Pattern.compile("^(.+\\.java):(\\d+):\\s*(?:error|warning):\\s*(.*)$");

    /**
     * Only {@code class}. {@code symbol: variable FOO} is a missing or renamed
     * constant and belongs to {@code SymbolRepair}; {@code symbol: method foo()}
     * is a genuine API error no import can fix. Whitespace after the colon varies
     * with javac's column alignment, hence {@code \s*}.
     */
    private static final Pattern SYMBOL_CLASS =
            Pattern.compile("^\\s*symbol:\\s*class\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    /** The enclosing declaration javac blames, used to attribute the error to one source. */
    private static final Pattern LOCATION =
            Pattern.compile("^\\s*location:\\s*(?:class|interface|enum|record|annotation type|@interface)"
                    + "\\s+([A-Za-z_$][A-Za-z0-9_$.]*)");

    private static final Pattern PACKAGE_LINE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern IMPORT_LINE = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.$*]+)\\s*;");

    /** Sentinel for "probed the JDK for this name and it is not there", so a miss is memoised too. */
    private static final String ABSENT = "";

    private final String version;
    private final String jarSource;
    private final Map<String, String> byJar;
    private final Set<String> ambiguous;
    private final Map<String, String> jdkCache = new ConcurrentHashMap<>();

    private ImportDoctor(String version, String jarSource, Map<String, String> byJar, Set<String> ambiguous) {
        this.version = version;
        this.jarSource = jarSource;
        this.byJar = byJar;
        this.ambiguous = ambiguous;
    }

    // ------------------------------------------------------------------
    // Building the index
    // ------------------------------------------------------------------

    /** Build the index for one Paper version from its cached paper-api jar. */
    public static ImportDoctor forVersion(Path jarsDir, String version) throws IOException {
        ClassFileVocabulary vocab = EvalFacts.vocabulary(jarsDir, version);

        // Collect every claimant first and decide afterwards. Deciding as we go
        // would make the outcome depend on zip enumeration order, and an index
        // that reorders itself between runs cannot be used to score anything.
        Map<String, Set<String>> claimants = new TreeMap<>();
        for (String binary : vocab.binaryNames()) {
            // ClassFileVocabulary hands back dotted names (its parser already did
            // replace('/', '.')), but accept the slashed form too rather than
            // depend on that: the cost is one extra lastIndexOf.
            String simple = simpleNameOf(binary);
            if (simple.isEmpty() || isNumeric(simple)) {
                // Anonymous classes: Foo$1. They have no simple name a human
                // could write, so they are not importable and not candidates.
                continue;
            }
            // '$' is the binary separator; the importable name spells nesting
            // with a dot, so Bed$Part must be imported as ...type.Bed.Part.
            claimants.computeIfAbsent(simple, k -> new TreeSet<>()).add(binary.replace('/', '.').replace('$', '.'));
        }

        Map<String, String> resolved = new TreeMap<>();
        Set<String> ambiguous = new TreeSet<>();
        for (Map.Entry<String, Set<String>> e : claimants.entrySet()) {
            Set<String> names = e.getValue();
            if (names.size() == 1) {
                resolved.put(e.getKey(), names.iterator().next());
                continue;
            }
            List<String> bukkit = new ArrayList<>();
            for (String n : names) {
                if (n.startsWith("org.bukkit.")) {
                    bukkit.add(n);
                }
            }
            if (bukkit.size() == 1) {
                // See the class javadoc: this is the only name a Bukkit mod could
                // have meant, so picking it is reading the package layout, not
                // guessing.
                resolved.put(e.getKey(), bukkit.get(0));
            } else {
                ambiguous.add(e.getKey());
            }
        }
        return new ImportDoctor(version, vocab.source(), resolved, ambiguous);
    }

    private static String simpleNameOf(String binaryName) {
        int cut = -1;
        for (int i = binaryName.length() - 1; i >= 0; i--) {
            char c = binaryName.charAt(i);
            if (c == '/' || c == '.' || c == '$') {
                cut = i;
                break;
            }
        }
        return binaryName.substring(cut + 1);
    }

    private static boolean isNumeric(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    /** Unique fully-qualified name for a simple type name, or null when unknown or ambiguous. */
    public String resolve(String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) {
            return null;
        }
        if (ambiguous.contains(simpleName)) {
            // Terminal, and deliberately not retried against the JDK: the jar is
            // the compile classpath the generated mod is aimed at, so if the API
            // itself is of two minds about the name, no answer is defensible.
            return null;
        }
        String fromJar = byJar.get(simpleName);
        if (fromJar != null) {
            // The jar wins over the JDK on a shared simple name, because the jar
            // is the API the source was written against.
            return fromJar;
        }
        String cached = jdkCache.get(simpleName);
        if (cached != null) {
            return cached.equals(ABSENT) ? null : cached;
        }
        String found = probeJdk(simpleName);
        jdkCache.put(simpleName, found == null ? ABSENT : found);
        return found;
    }

    /**
     * Lazily ask the classloader, because enumerating the JDK is not something
     * that can be done cheaply or completely (jrt module walking, split packages,
     * the difference between what is exported and what merely exists). One
     * {@code Class.forName} per candidate package, memoised, is both exact and
     * bounded: the miss for a hallucinated name costs eleven failed lookups once.
     * Initialisation is suppressed — this asks whether the type exists, and has
     * no business running anyone's static initialiser to find out.
     */
    private static String probeJdk(String simpleName) {
        for (String pkg : JDK_PACKAGES) {
            String candidate = pkg + "." + simpleName;
            try {
                Class.forName(candidate, false, ImportDoctor.class.getClassLoader());
                return candidate;
            } catch (ClassNotFoundException | LinkageError notThere) {
                // Expected for ten of the eleven packages on every hit.
            }
        }
        return null;
    }

    /** True when resolve() would return a name: the type really exists on the compile classpath. */
    public boolean isKnownType(String simpleName) {
        return resolve(simpleName) != null;
    }

    /** Simple names the jar index holds an unambiguous answer for. */
    public int indexSize() {
        return byJar.size();
    }

    /** Simple names two or more jar classes claim and the bukkit rule could not settle. */
    public int ambiguousCount() {
        return ambiguous.size();
    }

    @Override
    public String toString() {
        return "ImportDoctor[" + version + ", " + jarSource + ", " + byJar.size()
                + " names, " + ambiguous.size() + " ambiguous]";
    }

    // ------------------------------------------------------------------
    // Repair
    // ------------------------------------------------------------------

    /** Result of a repair attempt. */
    public record Result(Map<String, String> sources, List<String> added, List<String> unresolved) {
        public Result {
            sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
            added = List.copyOf(added);
            unresolved = List.copyOf(unresolved);
        }
    }

    /** One {@code cannot find symbol} for a type, with whatever file it could be pinned to. */
    private static final class Missing {
        final String simpleName;
        String ownerFqcn;

        Missing(String simpleName) {
            this.simpleName = simpleName;
        }
    }

    /**
     * Insert the imports a failed compile says are missing.
     *
     * @param fqcnSources  fully-qualified-class-name -&gt; source text, exactly the map the eval compiles
     * @param diagnostics  javac output from compiling that map
     */
    public Result repairImports(Map<String, String> fqcnSources, String diagnostics) {
        Map<String, String> out = new LinkedHashMap<>(fqcnSources);
        List<String> added = new ArrayList<>();
        Set<String> unresolved = new TreeSet<>();
        if (diagnostics == null || diagnostics.isBlank() || fqcnSources.isEmpty()) {
            return new Result(out, added, new ArrayList<>(unresolved));
        }

        // file key (or null when unattributable) -> the simple names it is missing.
        // Sorted so a file that needs three imports gets them in a stable order;
        // an eval whose output depends on HashMap iteration is not a measurement.
        Map<String, Set<String>> wanted = new LinkedHashMap<>();
        Set<String> floating = new TreeSet<>();

        for (Missing missing : parseMissingTypes(diagnostics, fqcnSources.keySet())) {
            String fqcn = resolve(missing.simpleName);
            if (fqcn == null) {
                unresolved.add(missing.simpleName);
                continue;
            }
            if (missing.ownerFqcn == null) {
                floating.add(missing.simpleName);
            } else {
                wanted.computeIfAbsent(missing.ownerFqcn, k -> new TreeSet<>()).add(missing.simpleName);
            }
        }

        // A name javac could not be pinned to one file goes into every source that
        // uses it as a word and does not already import it. Over-applying here is
        // safe in a way that guessing an FQCN is not: the import is correct, it is
        // only its necessity that is in doubt, and an unused import is legal Java.
        for (String name : floating) {
            Pattern word = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
            for (String key : fqcnSources.keySet()) {
                if (word.matcher(out.get(key)).find()) {
                    wanted.computeIfAbsent(key, k -> new TreeSet<>()).add(name);
                }
            }
        }

        for (Map.Entry<String, Set<String>> entry : wanted.entrySet()) {
            String key = entry.getKey();
            String source = out.get(key);
            if (source == null) {
                continue;
            }
            for (String name : entry.getValue()) {
                String fqcn = resolve(name);
                if (fqcn == null) {
                    continue;
                }
                // javac imports java.lang for you. Emitting the line would be
                // legal but is noise in a generated file and, worse, would make
                // the eval report a repair where nothing was repaired.
                if ("java.lang".equals(packageOf(fqcn))) {
                    continue;
                }
                String updated = withImport(source, fqcn);
                if (updated != null) {
                    source = updated;
                    added.add(key + " -> " + fqcn);
                }
            }
            out.put(key, source);
        }
        return new Result(out, added, new ArrayList<>(unresolved));
    }

    /**
     * Walks the diagnostic text as a line-oriented state machine rather than one
     * multi-line regex, because the three lines of a {@code cannot find symbol}
     * are not guaranteed adjacent: the header carries the file, the {@code
     * symbol:} line carries the name, and the {@code location:} line that
     * attributes it arrives afterwards. Tracking the last header and the last
     * symbol separately survives interleaved diagnostics and truncated output.
     */
    private static List<Missing> parseMissingTypes(String diagnostics, Set<String> sourceKeys) {
        List<Missing> found = new ArrayList<>();
        String headerFile = null;
        Missing pending = null;

        for (String line : diagnostics.split("\r?\n", -1)) {
            Matcher header = FORMATTED_HEADER.matcher(line);
            if (!header.matches()) {
                header = RAW_HEADER.matcher(line);
                if (!header.matches()) {
                    header = null;
                }
            }
            if (header != null) {
                headerFile = attributeByPath(header.group(1), sourceKeys);
                pending = null;
                continue;
            }
            Matcher symbol = SYMBOL_CLASS.matcher(line);
            if (symbol.find()) {
                pending = new Missing(symbol.group(1));
                pending.ownerFqcn = headerFile;
                found.add(pending);
                continue;
            }
            Matcher location = LOCATION.matcher(line);
            if (location.find() && pending != null) {
                // Prefer the location over the file path: it is the declaration
                // javac was inside, so for a nested class it still trims back to
                // the top-level key, whereas a staged temp path may not match at
                // all.
                String owner = attributeByDeclaration(location.group(1), sourceKeys);
                if (owner != null) {
                    pending.ownerFqcn = owner;
                }
                pending = null;
            }
        }
        return found;
    }

    /**
     * {@code /com/example/Mod.java} (the in-memory file manager's URI path) or an
     * absolute staging path ending in the same suffix, back to the map key.
     */
    private static String attributeByPath(String sourceName, Set<String> sourceKeys) {
        if (sourceName == null) {
            return null;
        }
        String dotted = sourceName.replace('\\', '/');
        if (dotted.endsWith(".java")) {
            dotted = dotted.substring(0, dotted.length() - ".java".length());
        }
        dotted = dotted.replace('/', '.');
        for (String key : sourceKeys) {
            if (dotted.equals(key) || dotted.endsWith("." + key)) {
                return key;
            }
        }
        return null;
    }

    /** {@code com.example.Mod.Inner} back to the top-level key {@code com.example.Mod}. */
    private static String attributeByDeclaration(String declared, Set<String> sourceKeys) {
        String candidate = declared;
        while (candidate != null && !candidate.isEmpty()) {
            if (sourceKeys.contains(candidate)) {
                return candidate;
            }
            int dot = candidate.lastIndexOf('.');
            candidate = dot < 0 ? null : candidate.substring(0, dot);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Source editing
    // ------------------------------------------------------------------

    /**
     * Returns the source with {@code import fqcn;} inserted, or null when the
     * file already covers that type and nothing should change. Returning null
     * rather than the unchanged text is what keeps {@code added} honest: it
     * lists edits that happened, not edits that were attempted.
     */
    private static String withImport(String source, String fqcn) {
        String pkg = packageOf(fqcn);
        List<String> lines = new ArrayList<>(List.of(source.split("\n", -1)));

        int lastImport = -1;
        int packageLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher pkgMatch = PACKAGE_LINE.matcher(line);
            if (packageLine < 0 && pkgMatch.find()) {
                if (pkgMatch.group(1).equals(pkg)) {
                    // Same package as the file: the type is already in scope and
                    // an import would be pure noise.
                    return null;
                }
                packageLine = i;
                continue;
            }
            Matcher importMatch = IMPORT_LINE.matcher(line);
            if (importMatch.find()) {
                String imported = importMatch.group(1);
                if (imported.equals(fqcn) || imported.equals(pkg + ".*")) {
                    return null;
                }
                lastImport = i;
            }
        }

        String statement = "import " + fqcn + ";";
        if (lastImport >= 0) {
            lines.add(lastImport + 1, statement);
        } else if (packageLine >= 0) {
            // A blank line after the package declaration is the shape every Java
            // file in this repo has; preserve it by inserting below it and adding
            // the separator the file has not got yet.
            int at = packageLine + 1;
            if (at < lines.size() && !lines.get(at).isBlank()) {
                lines.add(at, "");
            }
            lines.add(at, statement);
            lines.add(at, "");
        } else {
            lines.add(0, "");
            lines.add(0, statement);
        }
        return String.join("\n", lines);
    }

    private static String packageOf(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? "" : fqcn.substring(0, dot);
    }

    // ------------------------------------------------------------------
    // main(): print the index so it can be checked by hand
    // ------------------------------------------------------------------

    /**
     * The names below are the eval's two hypotheses side by side. The first eight
     * are the observed missing-import population and must all resolve; {@code
     * BlockPos} and {@code BlockBox} are the observed hallucinations and must
     * both come back null, because they belong to Minecraft and Fabric rather
     * than Bukkit. If that ever inverts, the separator this class is built on has
     * stopped working and the eval's numbers mean nothing.
     */
    private static final List<String> INTERESTING = List.of(
            "UUID", "World", "Block", "BlockFace", "Entity", "EntityType",
            "Material", "BlockData", "BlockPos", "BlockBox", "Location", "Particle");

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: ImportDoctor <jarsDir> <version> [simpleName ...]");
            System.exit(2);
        }
        Path jarsDir = Path.of(args[0]);
        String version = args[1];

        long started = System.nanoTime();
        ImportDoctor doctor = forVersion(jarsDir, version);
        long ms = (System.nanoTime() - started) / 1_000_000;

        List<String> names = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            names.add(args[i]);
        }

        System.out.println("=== ImportDoctor " + version + " (" + doctor.jarSource + ") ===");
        System.out.println("simple names indexed : " + doctor.indexSize() + "  (" + ms + " ms)");
        System.out.println("ambiguous, unresolvable : " + doctor.ambiguousCount());
        System.out.println();

        Set<String> print = new LinkedHashSet<>(names.isEmpty() ? INTERESTING : names);
        System.out.printf("%-14s %-10s %s%n", "SIMPLE", "VERDICT", "RESOLVES TO");
        for (String name : print) {
            String fqcn = doctor.resolve(name);
            String verdict = fqcn != null ? "known"
                    : doctor.ambiguous.contains(name) ? "AMBIGUOUS" : "ABSENT";
            System.out.printf("%-14s %-10s %s%n", name, verdict, fqcn == null ? "-" : fqcn);
        }
        System.out.println();
        System.out.println("known -> a missing import this pass would insert");
        System.out.println("ABSENT -> a hallucinated type; no import can fix it");
    }
}
