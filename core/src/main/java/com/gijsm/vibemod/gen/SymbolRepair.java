package com.gijsm.vibemod.gen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Logger;

import com.gijsm.vibemod.platform.ApiVocabulary;
import com.gijsm.vibemod.platform.ApiVocabulary.Known;

/**
 * The free self-heal round: a deterministic, offline pass that repairs
 * misspelled API constants against what the running server was <em>measured</em>
 * to declare, before a single token is spent.
 *
 * <h2>Why</h2>
 *
 * <p>VibeMod compiles generated code against the live server, so a wrong enum
 * constant costs a full repair round — the whole system prompt (~6k tokens), all
 * sources and the javac diagnostics, re-sent, for a spelling mistake. And the
 * spelling mistakes are systematic rather than random, because the API renamed
 * itself underneath the models' training data (docs/API-VOCABULARY.md):
 *
 * <ul>
 *   <li><strong>1.21.3</strong> drops every {@code GENERIC_}/{@code PLAYER_}/
 *       {@code ZOMBIE_}/{@code HORSE_} prefix from {@code Attribute}:
 *       {@code GENERIC_MAX_HEALTH} became {@code MAX_HEALTH}. Eight of the 31
 *       constants on 1.21.1 carry a prefix that is <em>not</em> {@code GENERIC_},
 *       so a {@code GENERIC_}-only rule misses a quarter of them.
 *   <li><strong>1.20.5</strong> renamed 67 constants across four types with no
 *       prefix pattern at all: {@code Enchantment.DURABILITY} became
 *       {@code UNBREAKING}, {@code DIG_SPEED} became {@code EFFICIENCY},
 *       {@code Particle} lost 38 names and {@code PotionEffectType} nine.
 * </ul>
 *
 * <p>The host now measures the real constant sets at boot. So this whole class of
 * error can be fixed locally, for free and instantly, and the repair rounds that
 * remain are about logic rather than spelling.
 *
 * <h2>The four rules</h2>
 *
 * <ol>
 *   <li><strong>Never act on {@code UNKNOWN}.</strong> Only a constant measured
 *       {@link Known#NO} on a type measured {@link Known#YES} is even a
 *       candidate. If the vocabulary never looked at the type, this pass does
 *       nothing. Reading "we didn't look" as "doesn't exist" would rewrite
 *       <em>working</em> code into broken code, which is strictly worse than
 *       every self-heal round it could ever save.
 *   <li><strong>Rewrite only when the answer is unambiguous.</strong> Candidates
 *       come from the measured rename table and from the prefix rules; every one
 *       is then checked against the measured set, and the rewrite happens
 *       <em>only if exactly one survives</em>. Two survivors is a report, never a
 *       coin flip.
 *   <li><strong>Never touch a string literal, a char literal, or a comment.</strong>
 *       {@code sendMessage("Attribute.GENERIC_MAX_HEALTH")} is not a bug and must
 *       survive byte-identical. That is why the scanner below is a real Java
 *       lexer state machine and not a regex.
 *   <li><strong>Enrich what it cannot fix.</strong> Zero candidates or several,
 *       and the pass emits a diagnostic naming the constants that actually exist
 *       — nearest first — which {@link com.gijsm.vibemod.llm.PromptLibrary} folds
 *       into the repair prompt. The model is told the truth instead of
 *       rediscovering it at 6k tokens a go. Those nearest-neighbour names are a
 *       <em>hint</em>: they never drive an automatic rewrite.
 * </ol>
 *
 * <p>And every rewrite is recorded in {@link Report#rewrites()}, logged, and
 * surfaced to the player. A host that silently edits generated code and then
 * misbehaves is far worse than one that visibly corrects it.
 *
 * <h2>Where the rename table comes from</h2>
 *
 * <p>{@link #MEASURED_ALIASES} was <em>derived by measurement</em>, not typed from
 * memory — see {@code core/src/test/java/repair/RenameDerivation.java}, which
 * reads all 21 cached {@code paper-api} jars and pairs constants on the vanilla
 * registry key their {@code <clinit>} loads:
 *
 * <pre>
 *   1.20.4:  ldc "unbreaking" ... putstatic DURABILITY
 *   1.20.5:  ldc "unbreaking" ... putstatic UNBREAKING
 * </pre>
 *
 * <p>Same key, two spellings, therefore the same concept — established from the
 * bytecode rather than from anyone's recollection. Field names alone could never
 * have found that pair: {@code DURABILITY} and {@code UNBREAKING} share no
 * substring. {@code SymbolRepairSelfTest} re-derives the table from the jars and
 * fails if this copy has drifted.
 *
 * <p>The groups are <em>undirected</em>. One table therefore repairs in both
 * directions — 1.21.1-era code running on 1.21.3 and 1.21.3-era code running on
 * 1.21.1 — because which name is "legacy" is a fact about the server, and the
 * server is what gets asked.
 */
public final class SymbolRepair {

    private static final Logger LOG = Logger.getLogger(SymbolRepair.class.getName());

    /**
     * The prefixes 1.21.3 stripped from {@code Attribute}. Ordering is irrelevant:
     * every candidate they produce is measured before it is used.
     */
    private static final List<String> PREFIXES = List.of("GENERIC_", "PLAYER_", "ZOMBIE_", "HORSE_");

    /** How many real names an unresolved diagnostic offers the model. */
    private static final int MAX_HINTS = 4;

    private SymbolRepair() {
    }

    // ==================================================================
    // Results
    // ==================================================================

    /** One applied rewrite. Logged, reported, and shown to the player. */
    public record Rewrite(String file, int line, String type, String from, String to) {
        @Override
        public String toString() {
            return file + ":" + line + " " + type + "." + from + " -> " + type + "." + to;
        }
    }

    /**
     * A constant measured absent that this pass would not guess at: either
     * nothing plausible exists ({@code candidates} empty, {@code hints} holds the
     * nearest real names) or several do ({@code candidates} holds them all).
     */
    public record Unresolved(String file, int line, String type, String constant,
                             List<String> candidates, List<String> hints) {

        /** True when the type has more than one real name this could have meant. */
        public boolean ambiguous() {
            return candidates.size() > 1;
        }

        /** The line handed to the model in the repair prompt. */
        public String describe() {
            if (ambiguous()) {
                return type + "." + constant + " does not exist on this server, and more than one real "
                        + "constant could be meant (" + String.join(", ", candidates)
                        + ") — the host refused to guess. Pick the right one.";
            }
            if (hints.isEmpty()) {
                return type + "." + constant + " does not exist on this server.";
            }
            return type + "." + constant + " does not exist on this server. The constants it actually "
                    + "declares that are closest: " + String.join(", ", hints) + ".";
        }
    }

    /** What the pass did, and to what. {@link #sources()} is what must be compiled and stored. */
    public record Report(Map<String, String> sources, List<Rewrite> rewrites, List<Unresolved> unresolved) {

        public Report {
            sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
            rewrites = List.copyOf(rewrites);
            unresolved = List.copyOf(unresolved);
        }

        /** Whether any source actually changed. */
        public boolean changed() {
            return !rewrites.isEmpty();
        }

        /** Whether there is anything at all worth telling the model. */
        public boolean hasNotes() {
            return !rewrites.isEmpty() || !unresolved.isEmpty();
        }

        /**
         * Every note this pass could make. Useful for logging and tests;
         * {@link #notesFor} is what should reach a prompt.
         */
        public List<String> notes() {
            return collect(true, null);
        }

        /**
         * The measured facts worth sending to the model, with javac as the
         * tie-breaker.
         *
         * <p>Applied rewrites are always included: a later round re-sends the
         * <em>repaired</em> sources, and the model must not "correct" them back
         * to the names it remembers, which would loop the round forever.
         *
         * <p>Unresolved symbols are included only when {@code javacDiagnostics}
         * mentions them, and that filter is load-bearing rather than tidy.
         * {@link ApiVocabulary} is keyed by SIMPLE name, so it can answer about
         * the wrong type: on a jar index of Paper 1.21.8, {@code SpawnReason}
         * resolves to {@code ExperienceOrb.SpawnReason} rather than
         * {@code CreatureSpawnEvent.SpawnReason}, and the pass duly concludes
         * that the perfectly real {@code SpawnReason.NATURAL} does not exist. It
         * never rewrites on that (no candidate survives), but left unfiltered it
         * would put a confident falsehood into a repair prompt.
         *
         * <p>The compiler has already answered the same question against the real
         * classpath with real imports, so it outranks us. If javac did not
         * complain about the symbol, the symbol is fine and we say nothing about
         * it. Passing {@code null} — the enable-crash path, where the code
         * compiled — drops every unresolved note for the same reason.
         */
        public List<String> notesFor(String javacDiagnostics) {
            return collect(false, javacDiagnostics);
        }

        private List<String> collect(boolean everything, String javacDiagnostics) {
            List<String> out = new ArrayList<>();
            for (Rewrite r : rewrites) {
                out.add(r.type() + "." + r.from() + " does not exist on this server; the host already "
                        + "rewrote it to " + r.type() + "." + r.to() + " in the sources above. Keep it.");
            }
            for (Unresolved u : unresolved) {
                if (everything
                        || (javacDiagnostics != null && javacDiagnostics.contains(u.constant()))) {
                    out.add(u.describe());
                }
            }
            // Same fact repeated across files or lines is one fact to the model.
            return new ArrayList<>(new LinkedHashSet<>(out));
        }

        /** One line for the log and the progress bar. */
        public String summary() {
            return rewrites.size() + " symbol(s) repaired locally, " + unresolved.size() + " unresolved";
        }
    }

    // ==================================================================
    // Entry points
    // ==================================================================

    /**
     * Repairs a {@code path -> source} map against a measured vocabulary.
     *
     * <p>With {@link ApiVocabulary#empty()} — or any vocabulary that never saw
     * the types in question — this returns the sources byte-identical and an
     * empty report. That is the intended degradation, not a failure.
     */
    public static Report repair(Map<String, String> sources, ApiVocabulary vocabulary) {
        if (sources == null || sources.isEmpty()) {
            return new Report(Map.of(), List.of(), List.of());
        }
        ApiVocabulary vocab = vocabulary == null ? ApiVocabulary.empty() : vocabulary;

        // A type the generated project declares itself shadows any API type of
        // the same simple name, and its constants are none of our business.
        Set<String> selfDeclared = declaredTypes(sources.values());

        Map<String, String> out = new LinkedHashMap<>();
        List<Rewrite> rewrites = new ArrayList<>();
        List<Unresolved> unresolved = new ArrayList<>();
        for (Map.Entry<String, String> file : sources.entrySet()) {
            out.put(file.getKey(),
                    repairOne(file.getKey(), file.getValue(), vocab, selfDeclared, rewrites, unresolved));
        }
        return new Report(out, rewrites, unresolved);
    }

    /**
     * The {@link GeneratedProject} flavour: same pass, and
     * {@link Report#sources()} is keyed by file path so the caller can rebuild
     * the project. Use {@link #applyTo} for that.
     */
    public static Report repair(GeneratedProject project, ApiVocabulary vocabulary) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (GeneratedProject.GeneratedFile f : project.files()) {
            sources.put(f.path(), f.content());
        }
        return repair(sources, vocabulary);
    }

    /**
     * The project with this report's repaired sources substituted in, or the
     * original instance when nothing changed.
     *
     * <p>Callers must compile <em>and store</em> this one. The stored corpus is
     * VibeMod's regression suite; persisting the original after compiling the
     * repair would poison it with code the server cannot build.
     */
    public static GeneratedProject applyTo(GeneratedProject project, Report report) {
        if (!report.changed()) {
            return project;
        }
        List<GeneratedProject.GeneratedFile> files = new ArrayList<>();
        for (GeneratedProject.GeneratedFile f : project.files()) {
            String repaired = report.sources().get(f.path());
            files.add(repaired == null || repaired.equals(f.content())
                    ? f
                    : new GeneratedProject.GeneratedFile(f.path(), repaired));
        }
        return new GeneratedProject(project.name(), project.description(), project.usage(),
                project.manual(), project.changelog(), project.icon(), project.mainClass(), files,
                project.config(), project.edits());
    }

    /**
     * Logs every rewrite individually and at {@code INFO} — a host that silently
     * edits generated code and then misbehaves is far worse than one that
     * visibly corrects it, so this is not optional and not summarised.
     *
     * <p>Unresolved symbols log at {@code FINE}. They are a suspicion, not a
     * finding: the vocabulary is keyed by simple name and can be answering about
     * a different type of that name (see {@link Report#notesFor}), so warning
     * about them would cry wolf on code that compiles perfectly well. Where they
     * matter — a compile actually failed and javac named the same symbol — they
     * reach the model instead.
     */
    public static void log(Report report) {
        for (Rewrite r : report.rewrites()) {
            LOG.info("Symbol repair: " + r);
        }
        for (Unresolved u : report.unresolved()) {
            LOG.fine("Symbol repair suspects " + u.file() + ":" + u.line() + " — " + u.describe());
        }
    }

    // ==================================================================
    // The pass
    // ==================================================================

    private static String repairOne(String path, String source, ApiVocabulary vocab,
                                    Set<String> selfDeclared,
                                    List<Rewrite> rewrites, List<Unresolved> unresolved) {
        List<Reference> refs = scan(source);
        if (refs.isEmpty()) {
            return source;
        }
        StringBuilder out = null;
        int copied = 0;
        for (Reference ref : refs) {
            if (selfDeclared.contains(ref.type)) {
                continue;
            }
            if (vocab.knows(ref.type) != Known.YES) {
                continue; // rule 1: UNKNOWN and NO are both "not our business"
            }
            if (vocab.constants(ref.type).isEmpty()) {
                continue; // measured the type but not its field table: no evidence either way
            }
            if (vocab.declaresConstant(ref.type, ref.constant) != Known.NO) {
                continue; // present, or unmeasured — either way, leave it alone
            }

            List<String> candidates = survivingCandidates(ref.type, ref.constant, vocab);
            int line = lineOf(source, ref.constantStart);
            if (candidates.size() == 1) {
                String to = candidates.get(0);
                if (out == null) {
                    out = new StringBuilder(source.length());
                }
                out.append(source, copied, ref.constantStart).append(to);
                copied = ref.constantEnd;
                rewrites.add(new Rewrite(path, line, ref.type, ref.constant, to));
            } else {
                unresolved.add(new Unresolved(path, line, ref.type, ref.constant, candidates,
                        candidates.isEmpty() ? nearest(ref.constant, vocab.constants(ref.type)) : List.of()));
            }
        }
        if (out == null) {
            return source;
        }
        return out.append(source, copied, source.length()).toString();
    }

    /**
     * Every real constant this one could have meant, measured present. Rule 2's
     * input: one survivor is a repair, anything else is a report.
     */
    static List<String> survivingCandidates(String type, String constant, ApiVocabulary vocab) {
        Set<String> proposed = new LinkedHashSet<>();

        // 1. the measured rename table, in both directions
        proposed.addAll(aliasesOf(type, constant));

        // 2. prefix strip, 3. prefix add, and the strip-then-add combination
        //    (HORSE_JUMP_STRENGTH -> GENERIC_JUMP_STRENGTH is both at once)
        String bare = constant;
        for (String prefix : PREFIXES) {
            if (constant.startsWith(prefix) && constant.length() > prefix.length()) {
                bare = constant.substring(prefix.length());
                break;
            }
        }
        proposed.add(bare);
        for (String prefix : PREFIXES) {
            proposed.add(prefix + bare);
        }
        proposed.remove(constant);

        List<String> surviving = new ArrayList<>();
        for (String candidate : proposed) {
            if (vocab.declaresConstant(type, candidate) == Known.YES) {
                surviving.add(candidate);
            }
        }
        Collections.sort(surviving);
        return surviving;
    }

    /** The nearest real names by edit distance — a hint for the model, never a rewrite. */
    static List<String> nearest(String constant, Set<String> real) {
        List<String> sorted = new ArrayList<>(real);
        sorted.sort(Comparator.<String>comparingInt(c -> distance(constant, c)).thenComparing(c -> c));
        List<String> out = new ArrayList<>();
        for (String c : sorted) {
            // Beyond half the name's length the "nearest" name is noise, not a hint.
            if (distance(constant, c) > Math.max(3, constant.length() / 2)) {
                break;
            }
            out.add(c);
            if (out.size() == MAX_HINTS) {
                break;
            }
        }
        return out;
    }

    /** Levenshtein, two rows. */
    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    // ==================================================================
    // The scanner — rule 3 lives here
    // ==================================================================

    /** A {@code Type.CONSTANT} occurrence found in real code, with its offsets. */
    record Reference(String type, String constant, int typeStart, int constantStart, int constantEnd) {
    }

    /**
     * Finds every {@code Type.CONSTANT} in <em>code</em>: not in a string literal,
     * not in a text block, not in a char literal, not in a {@code //} or
     * {@code /* *}{@code /} comment.
     *
     * <p>A regex cannot do this. {@code player.sendMessage("use
     * Attribute.GENERIC_MAX_HEALTH")} is a correct program that a regex would
     * silently corrupt, and so is a javadoc paragraph explaining the rename. So
     * this is a small Java lexer: it walks the file once, tracks which lexical
     * context it is in, and only ever emits references seen in {@code CODE}.
     *
     * <p>{@code CONSTANT} must be {@code SCREAMING_SNAKE_CASE} of at least two
     * characters. That is what an enum constant looks like, and the restriction is
     * what keeps {@code Attribute.class}, {@code Material.valueOf(x)} and
     * {@code Particle.DustOptions} out of the pass entirely.
     */
    static List<Reference> scan(String source) {
        List<Reference> out = new ArrayList<>();
        int n = source.length();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);

            // --- comments ---
            if (c == '/' && i + 1 < n) {
                char next = source.charAt(i + 1);
                if (next == '/') {
                    int end = source.indexOf('\n', i);
                    i = end < 0 ? n : end + 1;
                    continue;
                }
                if (next == '*') {
                    int end = source.indexOf("*/", i + 2);
                    i = end < 0 ? n : end + 2;
                    continue;
                }
            }

            // --- text blocks (""" ... """) ---
            if (c == '"' && i + 2 < n && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                i = endOfTextBlock(source, i + 3);
                continue;
            }

            // --- string and char literals ---
            if (c == '"' || c == '\'') {
                i = endOfQuoted(source, i + 1, c);
                continue;
            }

            // --- identifiers ---
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < n && Character.isJavaIdentifierPart(source.charAt(i))) {
                    i++;
                }
                String first = source.substring(start, i);
                if (i < n && source.charAt(i) == '.' && i + 1 < n
                        && Character.isJavaIdentifierStart(source.charAt(i + 1))) {
                    int secondStart = i + 1;
                    int j = secondStart;
                    while (j < n && Character.isJavaIdentifierPart(source.charAt(j))) {
                        j++;
                    }
                    String second = source.substring(secondStart, j);
                    if (isTypeName(first) && isConstantName(second)) {
                        out.add(new Reference(first, second, start, secondStart, j));
                    }
                    // Continue from the dot, not from j: a chain like
                    // A.B.CONST must still see B.CONST.
                    continue;
                }
                continue;
            }

            i++;
        }
        return out;
    }

    /** Position just past the closing {@code """}. */
    private static int endOfTextBlock(String source, int from) {
        int i = from;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"' && i + 2 < source.length()
                    && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                return i + 3;
            }
            i++;
        }
        return source.length();
    }

    /**
     * Position just past the closing quote. An unterminated literal ends at the
     * newline, which is what javac would say too — and stops one broken quote
     * from swallowing the rest of the file into "string context".
     */
    private static int endOfQuoted(String source, int from, char quote) {
        int i = from;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            if (c == '\n') {
                return i + 1;
            }
            i++;
        }
        return source.length();
    }

    /** Upper-camel: what an API type is called. */
    private static boolean isTypeName(String s) {
        return !s.isEmpty() && Character.isUpperCase(s.charAt(0)) && !isConstantName(s);
    }

    /** {@code SCREAMING_SNAKE_CASE}, at least two characters. */
    private static boolean isConstantName(String s) {
        if (s.length() < 2) {
            return false;
        }
        boolean letter = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                letter = true;
            } else if (c != '_' && !Character.isDigit(c)) {
                return false;
            }
        }
        return letter;
    }

    private static int lineOf(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Simple names of types the generated project declares itself. */
    static Set<String> declaredTypes(Iterable<String> sources) {
        Set<String> out = new TreeSet<>();
        for (String source : sources) {
            java.util.regex.Matcher m = DECLARATION.matcher(source);
            while (m.find()) {
                out.add(m.group(2));
            }
        }
        return out;
    }

    private static final java.util.regex.Pattern DECLARATION = java.util.regex.Pattern.compile(
            "\\b(class|interface|enum|record|@interface)\\s+([A-Za-z_$][\\w$]*)");

    // ==================================================================
    // The measured rename table
    // ==================================================================

    /**
     * Simple type name -&gt; constant -&gt; every other name measured to mean the
     * same thing. Built in a holder rather than a {@code static} block on this
     * class, because the block would otherwise run before
     * {@link #MEASURED_ALIASES} — declared below it for readability — has a value.
     */
    private static final class Aliases {
        static final Map<String, Map<String, Set<String>>> INDEX = index();

        private static Map<String, Map<String, Set<String>>> index() {
            Map<String, Map<String, Set<String>>> out = new TreeMap<>();
            for (Map.Entry<String, List<Set<String>>> type : measuredGroups().entrySet()) {
                Map<String, Set<String>> forType = out.computeIfAbsent(type.getKey(), k -> new TreeMap<>());
                for (Set<String> group : type.getValue()) {
                    for (String name : group) {
                        Set<String> others = forType.computeIfAbsent(name, k -> new TreeSet<>());
                        others.addAll(group);
                        others.remove(name);
                    }
                }
            }
            return out;
        }
    }

    static Set<String> aliasesOf(String type, String constant) {
        Map<String, Set<String>> forType = Aliases.INDEX.get(type);
        if (forType == null) {
            return Set.of();
        }
        Set<String> aliases = forType.get(constant);
        return aliases == null ? Set.of() : aliases;
    }

    /** The measured groups, in the form {@code RenameDerivation} prints. Exposed so a test can diff them. */
    public static Map<String, List<Set<String>>> measuredGroups() {
        Map<String, List<Set<String>>> out = new TreeMap<>();
        for (String row : MEASURED_ALIASES) {
            int bar = row.indexOf('|');
            String type = row.substring(0, bar);
            Set<String> names = new TreeSet<>(List.of(row.substring(bar + 1).split(",")));
            out.computeIfAbsent(type, k -> new ArrayList<>()).add(names);
        }
        return out;
    }

    /**
     * DERIVED, NOT TYPED. Every row is {@code Type|NAME,NAME[,NAME]}: field names
     * measured to carry the same vanilla registry key in
     * {@code paper/api-jars/paper-api-*.jar}. Regenerate with
     * {@code ./gradlew :core:deriveRenames}; {@code SymbolRepairSelfTest} fails if
     * this copy and the jars disagree.
     *
     * <p>Three-name groups are real: {@code HORSE_JUMP_STRENGTH} (to 1.20.4) became
     * {@code GENERIC_JUMP_STRENGTH} (1.20.5–1.21.1) became {@code JUMP_STRENGTH}
     * (1.21.3+), all three carrying {@code jump_strength}.
     *
     * <p>{@code Material} and {@code Sound} appear nowhere here and that too is a
     * measurement: both were indexed in full (1,922 and 371 constants on 1.20.4)
     * and neither renamed a constant anywhere in the supported range.
     */
    static final String[] MEASURED_ALIASES = {
        "Attribute|ARMOR,GENERIC_ARMOR",
        "Attribute|ARMOR_TOUGHNESS,GENERIC_ARMOR_TOUGHNESS",
        "Attribute|ATTACK_DAMAGE,GENERIC_ATTACK_DAMAGE",
        "Attribute|ATTACK_KNOCKBACK,GENERIC_ATTACK_KNOCKBACK",
        "Attribute|ATTACK_SPEED,GENERIC_ATTACK_SPEED",
        "Attribute|BLOCK_BREAK_SPEED,PLAYER_BLOCK_BREAK_SPEED",
        "Attribute|BLOCK_INTERACTION_RANGE,PLAYER_BLOCK_INTERACTION_RANGE",
        "Attribute|BURNING_TIME,GENERIC_BURNING_TIME",
        "Attribute|ENTITY_INTERACTION_RANGE,PLAYER_ENTITY_INTERACTION_RANGE",
        "Attribute|EXPLOSION_KNOCKBACK_RESISTANCE,GENERIC_EXPLOSION_KNOCKBACK_RESISTANCE",
        "Attribute|FALL_DAMAGE_MULTIPLIER,GENERIC_FALL_DAMAGE_MULTIPLIER",
        "Attribute|FLYING_SPEED,GENERIC_FLYING_SPEED",
        "Attribute|FOLLOW_RANGE,GENERIC_FOLLOW_RANGE",
        "Attribute|GENERIC_GRAVITY,GRAVITY",
        "Attribute|GENERIC_JUMP_STRENGTH,HORSE_JUMP_STRENGTH,JUMP_STRENGTH",
        "Attribute|GENERIC_KNOCKBACK_RESISTANCE,KNOCKBACK_RESISTANCE",
        "Attribute|GENERIC_LUCK,LUCK",
        "Attribute|GENERIC_MAX_ABSORPTION,MAX_ABSORPTION",
        "Attribute|GENERIC_MAX_HEALTH,MAX_HEALTH",
        "Attribute|GENERIC_MOVEMENT_EFFICIENCY,MOVEMENT_EFFICIENCY",
        "Attribute|GENERIC_MOVEMENT_SPEED,MOVEMENT_SPEED",
        "Attribute|GENERIC_OXYGEN_BONUS,OXYGEN_BONUS",
        "Attribute|GENERIC_SAFE_FALL_DISTANCE,SAFE_FALL_DISTANCE",
        "Attribute|GENERIC_SCALE,SCALE",
        "Attribute|GENERIC_STEP_HEIGHT,STEP_HEIGHT",
        "Attribute|GENERIC_WATER_MOVEMENT_EFFICIENCY,WATER_MOVEMENT_EFFICIENCY",
        "Attribute|MINING_EFFICIENCY,PLAYER_MINING_EFFICIENCY",
        "Attribute|PLAYER_SNEAKING_SPEED,SNEAKING_SPEED",
        "Attribute|PLAYER_SUBMERGED_MINING_SPEED,SUBMERGED_MINING_SPEED",
        "Attribute|PLAYER_SWEEPING_DAMAGE_RATIO,SWEEPING_DAMAGE_RATIO",
        "Attribute|SPAWN_REINFORCEMENTS,ZOMBIE_SPAWN_REINFORCEMENTS",
        "Enchantment|AQUA_AFFINITY,WATER_WORKER",
        "Enchantment|ARROW_DAMAGE,POWER",
        "Enchantment|ARROW_FIRE,FLAME",
        "Enchantment|ARROW_INFINITE,INFINITY",
        "Enchantment|ARROW_KNOCKBACK,PUNCH",
        "Enchantment|BANE_OF_ARTHROPODS,DAMAGE_ARTHROPODS",
        "Enchantment|BLAST_PROTECTION,PROTECTION_EXPLOSIONS",
        "Enchantment|DAMAGE_ALL,SHARPNESS",
        "Enchantment|DAMAGE_UNDEAD,SMITE",
        "Enchantment|DIG_SPEED,EFFICIENCY",
        "Enchantment|DURABILITY,UNBREAKING",
        "Enchantment|FEATHER_FALLING,PROTECTION_FALL",
        "Enchantment|FIRE_PROTECTION,PROTECTION_FIRE",
        "Enchantment|FORTUNE,LOOT_BONUS_BLOCKS",
        "Enchantment|LOOTING,LOOT_BONUS_MOBS",
        "Enchantment|LUCK,LUCK_OF_THE_SEA",
        "Enchantment|OXYGEN,RESPIRATION",
        "Enchantment|PROJECTILE_PROTECTION,PROTECTION_PROJECTILE",
        "Enchantment|PROTECTION,PROTECTION_ENVIRONMENTAL",
        "EntityType|CHEST_MINECART,MINECART_CHEST",
        "EntityType|COMMAND_BLOCK_MINECART,MINECART_COMMAND",
        "EntityType|DROPPED_ITEM,ITEM",
        "EntityType|ENDER_CRYSTAL,END_CRYSTAL",
        "EntityType|ENDER_SIGNAL,EYE_OF_ENDER",
        "EntityType|EXPERIENCE_BOTTLE,THROWN_EXP_BOTTLE",
        "EntityType|FIREWORK,FIREWORK_ROCKET",
        "EntityType|FISHING_BOBBER,FISHING_HOOK",
        "EntityType|FURNACE_MINECART,MINECART_FURNACE",
        "EntityType|HOPPER_MINECART,MINECART_HOPPER",
        "EntityType|LEASH_HITCH,LEASH_KNOT",
        "EntityType|LIGHTNING,LIGHTNING_BOLT",
        "EntityType|MINECART_MOB_SPAWNER,SPAWNER_MINECART",
        "EntityType|MINECART_TNT,TNT_MINECART",
        "EntityType|MOOSHROOM,MUSHROOM_COW",
        "EntityType|POTION,SPLASH_POTION",
        "EntityType|PRIMED_TNT,TNT",
        "EntityType|SNOWMAN,SNOW_GOLEM",
        "LootTables|RIAL_CHAMBER_CONSUMABLES,TRIAL_CHAMBER_CONSUMABLES",
        "MusicInstrument|ADMIRE,ADMIRE_GOAT_HORN",
        "MusicInstrument|CALL,CALL_GOAT_HORN",
        "MusicInstrument|DREAM,DREAM_GOAT_HORN",
        "MusicInstrument|FEEL,FEEL_GOAT_HORN",
        "MusicInstrument|PONDER,PONDER_GOAT_HORN",
        "MusicInstrument|SEEK,SEEK_GOAT_HORN",
        "MusicInstrument|SING,SING_GOAT_HORN",
        "MusicInstrument|YEARN,YEARN_GOAT_HORN",
        "Particle|ANGRY_VILLAGER,VILLAGER_ANGRY",
        "Particle|BUBBLE,WATER_BUBBLE",
        "Particle|CRIT_MAGIC,ENCHANTED_HIT",
        "Particle|DRIPPING_LAVA,DRIP_LAVA",
        "Particle|DRIPPING_WATER,DRIP_WATER",
        "Particle|DUST,REDSTONE",
        "Particle|EFFECT,SPELL",
        "Particle|ELDER_GUARDIAN,MOB_APPEARANCE",
        "Particle|ENCHANT,ENCHANTMENT_TABLE",
        "Particle|ENTITY_EFFECT,SPELL_MOB",
        "Particle|EXPLOSION,EXPLOSION_LARGE",
        "Particle|EXPLOSION_EMITTER,EXPLOSION_HUGE",
        "Particle|EXPLOSION_NORMAL,POOF",
        "Particle|FIREWORK,FIREWORKS_SPARK",
        "Particle|FISHING,WATER_WAKE",
        "Particle|HAPPY_VILLAGER,VILLAGER_HAPPY",
        "Particle|INSTANT_EFFECT,SPELL_INSTANT",
        "Particle|ITEM,ITEM_CRACK",
        "Particle|ITEM_SLIME,SLIME",
        "Particle|LARGE_SMOKE,SMOKE_LARGE",
        "Particle|MYCELIUM,TOWN_AURA",
        "Particle|RAIN,WATER_DROP",
        "Particle|SMOKE,SMOKE_NORMAL",
        "Particle|SPELL_WITCH,WITCH",
        "Particle|SPLASH,WATER_SPLASH",
        "Particle|TOTEM,TOTEM_OF_UNDYING",
        "PatternType|CIRCLE,CIRCLE_MIDDLE",
        "PatternType|DIAGONAL_LEFT_MIRROR,DIAGONAL_UP_LEFT",
        "PatternType|DIAGONAL_RIGHT,DIAGONAL_RIGHT_MIRROR",
        "PatternType|DIAGONAL_RIGHT,DIAGONAL_UP_RIGHT",
        "PatternType|HALF_HORIZONTAL_BOTTOM,HALF_HORIZONTAL_MIRROR",
        "PatternType|HALF_VERTICAL_MIRROR,HALF_VERTICAL_RIGHT",
        "PatternType|RHOMBUS,RHOMBUS_MIDDLE",
        "PatternType|SMALL_STRIPES,STRIPE_SMALL",
        "PotionEffectType|CONFUSION,NAUSEA",
        "PotionEffectType|DAMAGE_RESISTANCE,RESISTANCE",
        "PotionEffectType|FAST_DIGGING,HASTE",
        "PotionEffectType|HARM,INSTANT_DAMAGE",
        "PotionEffectType|HEAL,INSTANT_HEALTH",
        "PotionEffectType|INCREASE_DAMAGE,STRENGTH",
        "PotionEffectType|JUMP,JUMP_BOOST",
        "PotionEffectType|MINING_FATIGUE,SLOW_DIGGING",
        "PotionEffectType|SLOW,SLOWNESS",
        "PotionType|HARMING,INSTANT_DAMAGE",
        "PotionType|HEALING,INSTANT_HEAL",
        "PotionType|JUMP,LEAPING",
        "PotionType|REGEN,REGENERATION",
        "PotionType|SPEED,SWIFTNESS",
        "Tag|CLUSTER_MAX_HARVESTABLES,ITEMS_CLUSTER_MAX_HARVESTABLES",
        "Tag|DRIPSTONE_REPLACEABLE,DRIPSTONE_REPLACEABLE_BLOCKS",
        "Tag|FOX_FOOD,ITEMS_FOX_FOOD",
        "Tag|FREEZE_IMMUNE_WEARABLES,ITEMS_FREEZE_IMMUNE_WEARABLES",
        "Tag|IGNORED_BY_PIGLIN_BABIES,ITEMS_IGNORED_BY_PIGLIN_BABIES",
        "Tag|ITEMS_NOTEBLOCK_TOP_INSTRUMENTS,ITEMS_NOTE_BLOCK_TOP_INSTRUMENTS",
        "Tag|ITEMS_PIGLIN_FOOD,PIGLIN_FOOD",
    };
}
