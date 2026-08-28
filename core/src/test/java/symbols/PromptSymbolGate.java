package symbols;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.gijsm.vibemod.llm.PromptFacts;
import com.gijsm.vibemod.llm.PromptLibrary;
import com.gijsm.vibemod.llm.PromptRule;
import com.gijsm.vibemod.llm.PromptRules;
import com.gijsm.vibemod.platform.ApiVocabulary.Known;

/**
 * The B3 gate: for every supported Paper version, the system prompt VibeMod
 * would actually send that server is built offline and every factual claim in it
 * is checked against that version's own {@code paper-api} jar.
 *
 * <p>The prompt used to assert API facts in hand-written prose and got them
 * wrong on twelve supported versions — it taught {@code Attribute.GENERIC_MAX_HEALTH}
 * on four versions where only the short form compiles, and forbade
 * {@code ItemMeta#setEnchantmentGlintOverride} on eight versions that ship it
 * (docs/API-VOCABULARY.md). Both defects were introduced by a person writing a
 * true sentence about one version into a table serving thirteen. This is the
 * thing that would have gone red the day either was written.
 *
 * <h2>What it asserts, per version</h2>
 *
 * <ol>
 *   <li><strong>Named symbols are true.</strong> The RENDERED prompt text is
 *       parsed for {@code Type.CONSTANT} and {@code Type#method} references and
 *       each is looked up in the jar. Not the declared {@code requiresSymbols} —
 *       the rendered text, because that is the artifact that reaches the model
 *       and a sentence can name a symbol no list mentions. Whether a reference
 *       must be present or absent comes from the rule that emitted it (half the
 *       rules name a symbol precisely to ban it), and a reference no fired rule
 *       accounts for is itself a failure.
 *   <li><strong>Forbidden symbols are absent</strong>, and MEASURED absent: for
 *       every rule that fired, each {@code forbidsSymbols} entry must be
 *       {@link Known#NO} and each {@code requiresSymbols} entry {@link Known#YES}.
 *       {@link PromptRule#appliesTo} already refuses to emit a rule the
 *       vocabulary contradicts, so this adds the half that is not tautological:
 *       {@link Known#UNKNOWN} is not good enough. A forbids entry that is
 *       UNKNOWN — a typo'd type name, say — would suppress nothing and prove
 *       nothing, and would otherwise sit in the table unnoticed.
 *   <li><strong>The injected constant lists are the jar's real sets.</strong>
 *       The {@code Attribute}/{@code Enchantment}/{@code PotionEffectType} block
 *       is parsed back out of the rendered prompt and compared to
 *       {@link ClassFileVocabulary} exactly: no extras, nothing missing, and the
 *       count in the header right.
 *   <li><strong>The right rules fire.</strong> Six sibling pairs straddle the
 *       three measured vocabulary boundaries (1.20.5, 1.21, 1.21.3). Exactly one
 *       of each pair must fire on each version, and it must be the side the
 *       measurements say is true there.
 *   <li><strong>No dead rules.</strong> Every rule fires on at least one
 *       supported version — see {@link #checkNoDeadRules}, which catches the
 *       failure mode the render-time invariant creates: a rule that is silently
 *       suppressed everywhere because it names something that does not exist.
 * </ol>
 *
 * <p>The version-independent half — every symbol a rule's text names is declared
 * in that rule's own lists — is {@link RuleSymbolDrift}, run once here and also
 * from {@code LlmSelfTest} where it needs no jars.
 *
 * <h2>The tri-state, and why "not in this jar" is not "absent"</h2>
 *
 * <p>{@link ClassFileVocabulary} is closed-world over the Paper API: it indexed
 * every class in the jar, so for a type it lists, a constant it does not list is
 * genuinely {@link Known#NO}. That is what gives this gate teeth. But the jar is
 * not the server's whole classpath. Adventure ({@code Component}), the sdk
 * ({@code VibeContext}, {@code Mod}) and the JDK ({@code HashMap}, {@code UUID})
 * are all real at runtime and all absent from {@code paper-api}, so reading
 * "not in this jar" as "does not exist" would fail the gate on symbols that are
 * perfectly fine — permanently red, and then deleted, which is how gates die.
 *
 * <p>So the rule for the text scan is: <strong>a reference is only checked when
 * the jar knows its type.</strong> Unknown types are counted and reported, never
 * failed. Within a known type the world is closed and a missing constant is a
 * failure. Method references get one extra allowance, documented at
 * {@link #checkReference}: {@link ClassFileVocabulary} reads DECLARED members
 * only and cannot walk a supertype, so a method that exists somewhere in the jar
 * but not on the named type is reported as unproven rather than failed. The
 * strict half of check 2 keeps the symbols that actually gate a rule honest, and
 * a method that exists nowhere in the jar still fails — which is what catches a
 * fabricated name.
 *
 * <pre>
 *   ./gradlew :core:selfTestPromptSymbols
 *   ./gradlew :core:selfTestPromptSymbols -Pinventory=true   # every reference, with its verdict
 * </pre>
 */
public final class PromptSymbolGate {

    private PromptSymbolGate() {
    }

    // ------------------------------------------------------------------
    // The measured boundaries. Each pair is (rule below the boundary, rule at
    // or above it). Sources: docs/API-VOCABULARY.md, verified here against all
    // 21 jars — if Paper ever moves one, this table is what goes red.
    // ------------------------------------------------------------------

    private record Boundary(String subject, String floor, String below, String atOrAbove) {
    }

    private static final List<Boundary> BOUNDARIES = List.of(
            new Boundary("enchantment spellings", "1.20.5",
                    "paper.enchantment.legacy", "paper.enchantment.vanilla"),
            new Boundary("potion effect spellings", "1.20.5",
                    "paper.potion.legacy", "paper.potion.vanilla"),
            new Boundary("particle spellings", "1.20.5",
                    "paper.particle.legacy", "paper.particle.vanilla"),
            new Boundary("ItemMeta glint override", "1.20.5",
                    "paper.itemmeta.glint.no", "paper.itemmeta.glint.yes"),
            new Boundary("AttributeModifier constructor", "1.21",
                    "paper.attribute.modifier.uuid", "paper.attribute.modifier.key"),
            new Boundary("ItemMeta data-component setters", "1.21.3",
                    "paper.itemmeta.model.no", "paper.itemmeta.model.yes"));

    /** The types {@code VocabularyBlock} dumps in full. */
    private static final List<String> DUMPED = List.of("Attribute", "Enchantment", "PotionEffectType");

    // ------------------------------------------------------------------

    /** {@code `...`} — the prompt's convention for naming a symbol in prose. */
    private static final Pattern BACKTICKED = Pattern.compile("`([^`\n]+)`");

    /** {@code Type.CONSTANT}, {@code Type#method}, {@code Type.method(}. */
    private static final Pattern REFERENCE = Pattern.compile(
            "\\b([A-Z][A-Za-z0-9]*)([.#])([A-Za-z_][A-Za-z0-9_]*)\\s*(\\()?");

    /** The heading {@code PromptLibrary} puts in front of the server-derived half. */
    private static final String SERVER_SECTION = "================ THIS SERVER ================";

    /** The heading after it: everything from here on is fixed text again. */
    private static final String EXAMPLES_SECTION = "================ WORKED EXAMPLES ================";

    /** {@code Every `Attribute` constant on this server (40, exhaustive ...):} */
    private static final Pattern VOCABULARY_HEADER = Pattern.compile(
            "Every `([A-Za-z]+)` constant on this server \\((\\d+), exhaustive[^)]*\\):");

    /**
     * Placeholders the prompt writes in symbol position on purpose. Exactly one,
     * and it is spelled so a reader cannot mistake it for a real constant.
     */
    private static final Set<String> PLACEHOLDERS = Set.of("EntityType.X");

    /**
     * The only symbols the FIXED skeleton is allowed to name — everything else
     * must come from a {@link PromptRule}, which is what gives a claim a
     * polarity and a version range.
     *
     * <p>This list is short on purpose and is the gate's forcing function. A new
     * sentence in {@code PromptLibrary} or in a profile that names an API symbol
     * fails as "unattributed" until someone either moves it into a rule with
     * {@code requiresSymbols}/{@code forbidsSymbols} — where the jars check it
     * per version — or adds it here and thereby asserts it is true on every
     * supported version. That is precisely the decision the old cheat sheet let
     * people skip.
     *
     * <p>What is here today: the three {@code Bukkit} statics the hard rules
     * forbid a mod from calling directly (host plumbing, present on all 21
     * versions, and that sentence is about registration going through
     * {@code VibeContext} rather than about the API existing), and Adventure's
     * {@code Component#text}, which is not a paper-api symbol at all — Paper
     * supplies Adventure as a separate artifact, so no jar here can vouch for it.
     */
    private static final Set<String> SKELETON = Set.of(
            "Bukkit#getPluginManager", "Bukkit#getScheduler", "Bukkit#getCommandMap",
            "Component#text");

    private static int assertions;

    /** Undeclared symbols, found once; the per-version loop tallies where they exist. */
    private static final List<RuleSymbolDrift.Violation> DRIFT = RuleSymbolDrift.findings();
    private static final Map<String, List<String>> DRIFT_PRESENT = new LinkedHashMap<>();

    private static final Set<String> EVER_FIRED = new TreeSet<>();
    private static final List<String> FAILURES = new ArrayList<>();
    private static final Set<String> UNCHECKED_TYPES = new TreeSet<>();
    private static final Set<String> UNPROVEN_METHODS = new TreeSet<>();

    public static void main(String[] args) throws Exception {
        long started = System.nanoTime();
        Path jars = Path.of(args.length > 0 && !args[0].isBlank()
                ? args[0]
                : System.getProperty("vibemod.apiJars", "paper/api-jars"));
        boolean inventory = args.length > 1 && Boolean.parseBoolean(args[1]);

        List<String> versions = Files.isDirectory(jars) ? JarFacts.versions(jars) : List.of();
        if (versions.isEmpty()) {
            // Loud, and green. A developer without the cache must not get a red
            // build; CI fetches the jars so that this line never appears there.
            System.out.println("SKIPPED: no paper-api jars at " + jars.toAbsolutePath()
                    + " - run scripts/fetch-api-jars.sh to make this gate real");
            System.out.println("         (checking the version-independent half anyway)");
            driftWithTally(0);
            report(started, 0);
            return;
        }

        System.out.println("=== The prompt's factual claims, checked against " + versions.size()
                + " paper-api jars ===");
        System.out.println("    " + jars.toAbsolutePath());
        System.out.println();
        System.out.printf("%-9s %-14s %7s  %5s %5s %5s  %s%n",
                "VERSION", "PROFILE", "PROMPT", "SYMS", "RULES", "CONST", "VERDICT");

        for (String version : versions) {
            checkVersion(jars, version, inventory);
        }
        driftWithTally(versions.size());
        checkNoDeadRules(versions.size());
        report(started, versions.size());
    }

    // ------------------------------------------------------------------

    private static void checkVersion(Path jars, String version, boolean inventory) throws Exception {
        ClassFileVocabulary vocabulary = JarFacts.vocabulary(jars, version);
        PromptFacts facts = JarFacts.factsFor(version, vocabulary);
        String prompt = PromptLibrary.systemPrompt(facts);
        List<PromptRule> rules = facts.profile().rules();
        List<String> fired = PromptRules.applicableIds(rules, facts);
        EVER_FIRED.addAll(fired);
        for (RuleSymbolDrift.Violation v : DRIFT) {
            if (v.symbol() != null && measure(v.symbol(), vocabulary) == Known.YES) {
                DRIFT_PRESENT.computeIfAbsent(v.symbol(), k -> new ArrayList<>()).add(version);
            }
        }
        int before = FAILURES.size();

        int symbols = checkNamedSymbols(version, prompt, rules, fired, vocabulary, inventory);
        checkFiredRuleClaims(version, rules, facts, fired);
        int constants = checkVocabularyBlock(version, prompt, vocabulary);
        checkBoundaries(version, fired);

        int failed = FAILURES.size() - before;
        System.out.printf("%-9s %-14s %7d  %5d %5d %5d  %s%n",
                version, facts.profile().id(), prompt.length(), symbols, fired.size(), constants,
                failed == 0 ? "ok" : failed + " FAILED");
    }

    /**
     * Check 1: every symbol the rendered prompt NAMES is true here — present
     * when the prompt offers it, absent when the prompt forbids it.
     *
     * <p>Scans backticked spans only. That is not a shortcut, it is the prompt's
     * own convention for asserting a symbol: the un-backticked occurrences are
     * inside the verbatim sdk sources and the worked examples, which are Java
     * and JSON rather than claims about Paper, and whose types
     * ({@code VibeContext}, {@code Component}) the jar has no opinion on anyway.
     *
     * <h3>Polarity, and why it is read off the rules rather than the English</h3>
     *
     * <p>Half the rules name a symbol in order to BAN it — "{@code ItemMeta#setItemModel(...)}
     * does not exist on this server" — so a scan that reads every named symbol
     * as an existence claim reports the truest sentences in the prompt as
     * failures. (It did, on the first run: 23 of them.) The polarity of a claim
     * therefore comes from the rule that emitted it: a symbol in a fired rule's
     * {@code forbidsSymbols} must be measured absent, one in its
     * {@code requiresSymbols} measured present.
     *
     * <p>A reference belonging to NEITHER list of any fired rule is itself a
     * failure — see {@link #SKELETON}. Guessing polarity from the surrounding
     * words is the one thing this must not do: a gate that parses English
     * negation can be fooled by a rewrite, and the premise of the whole rework
     * is that prose about the API is not checkable. Making the prose declare its
     * own polarity is what makes it checkable.
     *
     * @return how many references were checked against the jar
     */
    private static int checkNamedSymbols(String version, String prompt, List<PromptRule> rules,
                                         List<String> fired, ClassFileVocabulary vocabulary,
                                         boolean inventory) {
        Map<String, String> requiredBy = new LinkedHashMap<>();
        Map<String, String> forbiddenBy = new LinkedHashMap<>();
        for (PromptRule rule : rules) {
            if (!fired.contains(rule.id())) {
                continue;
            }
            for (String sym : rule.requiresSymbols()) {
                requiredBy.putIfAbsent(sym, rule.id());
            }
            for (String sym : rule.forbidsSymbols()) {
                forbiddenBy.putIfAbsent(sym, rule.id());
            }
        }

        // The two halves of the prompt are checked against different rules,
        // because they are written under different ones. Everything between the
        // THIS SERVER heading and the worked examples was produced by the rule
        // table for THIS version; everything else is fixed text that every
        // version gets, and a fixed sentence naming a versioned symbol is the
        // original defect in its purest form.
        int serverAt = prompt.indexOf(SERVER_SECTION);
        int examplesAt = prompt.indexOf(EXAMPLES_SECTION);
        boolean split = serverAt >= 0 && examplesAt > serverAt;
        String serverHalf = split ? prompt.substring(serverAt, examplesAt) : "";
        String fixedHalf = split
                ? prompt.substring(0, serverAt) + prompt.substring(examplesAt)
                : prompt;

        int checked = 0;
        for (String reference : references(fixedHalf)) {
            if (PLACEHOLDERS.contains(reference)) {
                continue;
            }
            String verdict;
            if (SKELETON.contains(reference)) {
                verdict = expectPresent(version, "fixed skeleton", reference, vocabulary);
            } else {
                verdict = fail(version, "fixed skeleton", reference, "not named outside a rule",
                        "a sentence every version gets names this symbol, so nothing checks it"
                                + " per version (paper-api " + version + " answers "
                                + measure(reference, vocabulary) + ") - move the sentence into a"
                                + " PromptRule with requiresSymbols/forbidsSymbols, or add the"
                                + " symbol to PromptSymbolGate.SKELETON if it is true on all of them");
            }
            assertions++;
            if (verdict == null) {
                checked++;
                verdict = "ok";
            }
            if (inventory) {
                System.out.printf("    %-9s %-46s %s%n", version, reference, verdict);
            }
        }

        for (String reference : references(serverHalf)) {
            if (PLACEHOLDERS.contains(reference)) {
                continue;
            }
            String verdict;
            if (forbiddenBy.containsKey(reference)) {
                verdict = expectAbsent(version, forbiddenBy.get(reference), reference, vocabulary);
            } else if (requiredBy.containsKey(reference)) {
                verdict = expectPresent(version, requiredBy.get(reference), reference, vocabulary);
            } else {
                verdict = fail(version, "unattributed", reference, "declared by a rule",
                        "the server section names it but no rule that fired lists it in"
                                + " requiresSymbols or forbidsSymbols, so nothing says whether it"
                                + " is supposed to exist (paper-api " + version + " answers "
                                + measure(reference, vocabulary) + ")");
            }
            assertions++;
            if (verdict == null) {
                checked++;
                verdict = "ok";
            }
            if (inventory) {
                System.out.printf("    %-9s %-46s %s%n", version, reference, verdict);
            }
        }
        return checked;
    }

    /**
     * A symbol the prompt offers. Returns {@code null} when it was checked and
     * holds, otherwise the reason it was skipped, or the failure line.
     *
     * <p>The method allowance: {@link ClassFileVocabulary} indexes DECLARED
     * members, so {@code Player#sendMessage} — declared on {@code CommandSender}
     * — reads as absent on {@code Player}. Failing that would be wrong. So a
     * method missing from its named type but declared on SOME type in the jar is
     * reported as unproven, while a method declared nowhere in the jar fails:
     * inheritance can move a real method between types, it cannot conjure one
     * that does not exist.
     */
    private static String expectPresent(String version, String source, String reference,
                                        ClassFileVocabulary vocabulary) {
        String type = typeOf(reference);
        String member = memberOf(reference);
        if (vocabulary.knows(type) != Known.YES) {
            UNCHECKED_TYPES.add(type);
            return "unchecked (" + type + " is not a paper-api type)";
        }
        if (reference.indexOf('#') > 0) {
            if (vocabulary.declaresMethod(type, member) == Known.YES) {
                return null;
            }
            if (declaredSomewhere(vocabulary, member)) {
                UNPROVEN_METHODS.add(reference);
                return "unproven (inherited? " + member + " is declared elsewhere in the jar)";
            }
            return fail(version, source, reference, "present",
                    "the prompt offers it but no type in paper-api " + version
                            + " declares a method named " + member);
        }
        if (vocabulary.declaresConstant(type, member) == Known.YES) {
            return null;
        }
        return fail(version, source, reference, "present",
                "the prompt offers it but " + type + " on paper-api " + version
                        + " declares no constant " + member
                        + " (that type has " + vocabulary.constants(type).size() + " constants here)");
    }

    /**
     * A symbol the prompt bans. UNKNOWN is not good enough: telling a model that
     * a method does not exist, on a classpath nobody measured, is the same class
     * of guess this rework removed.
     */
    private static String expectAbsent(String version, String source, String reference,
                                       ClassFileVocabulary vocabulary) {
        String type = typeOf(reference);
        String member = memberOf(reference);
        Known known = reference.indexOf('#') > 0
                ? vocabulary.declaresMethod(type, member)
                : vocabulary.declaresConstant(type, member);
        if (known == Known.NO) {
            return null;
        }
        return fail(version, source, reference, "absent",
                "the prompt tells the model this does not exist here, but paper-api " + version
                        + " answers " + known);
    }

    /** Every distinct {@code Type.CONSTANT} / {@code Type#method} inside backticks, in order. */
    private static Set<String> references(String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher spans = BACKTICKED.matcher(text);
        while (spans.find()) {
            Matcher m = REFERENCE.matcher(spans.group(1));
            while (m.find()) {
                boolean method = "#".equals(m.group(2)) || m.group(4) != null;
                found.add(m.group(1) + (method ? "#" : ".") + m.group(3));
            }
        }
        return found;
    }

    /** What the jar says about a reference, for a failure message. */
    private static Known measure(String reference, ClassFileVocabulary vocabulary) {
        String type = typeOf(reference);
        return reference.indexOf('#') > 0
                ? vocabulary.declaresMethod(type, memberOf(reference))
                : vocabulary.declaresConstant(type, memberOf(reference));
    }

    private static String typeOf(String reference) {
        int cut = reference.indexOf('#');
        return reference.substring(0, cut > 0 ? cut : reference.indexOf('.'));
    }

    private static String memberOf(String reference) {
        int cut = reference.indexOf('#');
        return reference.substring((cut > 0 ? cut : reference.indexOf('.')) + 1);
    }

    private static boolean declaredSomewhere(ClassFileVocabulary vocabulary, String method) {
        for (String type : vocabulary.knownTypes()) {
            if (vocabulary.methods(type).contains(method)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check 2: for every rule that fired, its required symbols are MEASURED
     * present and its forbidden ones MEASURED absent — YES and NO, never UNKNOWN.
     */
    private static void checkFiredRuleClaims(String version, List<PromptRule> rules,
                                             PromptFacts facts, List<String> fired) {
        for (PromptRule rule : rules) {
            if (!fired.contains(rule.id())) {
                continue;
            }
            for (String required : rule.requiresSymbols()) {
                assertions++;
                Known known = facts.symbol(required);
                if (known != Known.YES) {
                    fail(version, rule.id(), required, "present",
                            "the rule fired but the jar answers " + known);
                }
            }
            for (String forbidden : rule.forbidsSymbols()) {
                assertions++;
                Known known = facts.symbol(forbidden);
                if (known != Known.NO) {
                    fail(version, rule.id(), forbidden, "absent",
                            "the rule tells the model this does not exist, but the jar answers " + known);
                }
            }
        }
    }

    /**
     * Check 3: the injected constant lists ARE the jar's sets. Parsed back out of
     * the rendered prompt, so what is compared is what the model is shown.
     *
     * @return how many constants were compared
     */
    private static int checkVocabularyBlock(String version, String prompt,
                                            ClassFileVocabulary vocabulary) {
        int compared = 0;
        String[] lines = prompt.split("\n", -1);
        Set<String> found = new LinkedHashSet<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher m = VOCABULARY_HEADER.matcher(lines[i]);
            if (!m.find()) {
                continue;
            }
            String type = m.group(1);
            found.add(type);
            Set<String> claimed = new TreeSet<>();
            if (i + 1 < lines.length && !lines[i + 1].isBlank()) {
                for (String name : lines[i + 1].split(",")) {
                    claimed.add(name.trim());
                }
            }
            Set<String> real = new TreeSet<>(vocabulary.constants(type));

            assertions++;
            if (Integer.parseInt(m.group(2)) != claimed.size()) {
                fail(version, "vocabulary block", type, "count matches list",
                        "header says " + m.group(2) + " but the list has " + claimed.size());
            }

            Set<String> extra = new TreeSet<>(claimed);
            extra.removeAll(real);
            Set<String> missing = new TreeSet<>(real);
            missing.removeAll(claimed);
            assertions++;
            if (!extra.isEmpty() || !missing.isEmpty()) {
                fail(version, "vocabulary block", type, "exactly the jar's set",
                        (extra.isEmpty() ? "" : "prompt invents " + extra + " ")
                                + (missing.isEmpty() ? "" : "prompt omits " + missing));
            }
            compared += claimed.size();
        }

        for (String type : DUMPED) {
            assertions++;
            if (!found.contains(type) && !vocabulary.constants(type).isEmpty()) {
                fail(version, "vocabulary block", type, "present in the prompt",
                        "the jar declares " + vocabulary.constants(type).size()
                                + " constants but the prompt lists none");
            }
        }
        return compared;
    }

    /**
     * Check 4: exactly one side of each boundary pair fires, and it is the side
     * the measurements say is true on this version.
     */
    private static void checkBoundaries(String version, List<String> fired) {
        for (Boundary b : BOUNDARIES) {
            boolean modern = JarFacts.atLeast(version, b.floor());
            String expected = modern ? b.atOrAbove() : b.below();
            String other = modern ? b.below() : b.atOrAbove();

            assertions++;
            if (!fired.contains(expected)) {
                fail(version, expected, b.subject(), "the rule fires",
                        version + " is " + (modern ? "at or above " : "below ") + b.floor()
                                + " so this is the true side, but it did not fire (fired: " + fired + ")");
            }
            assertions++;
            if (fired.contains(other)) {
                fail(version, other, b.subject(), "the rule does NOT fire",
                        "it contradicts " + expected + ", which is the true side on " + version);
            }
        }
    }

    /**
     * Check 5: no dead rules. Every rule in the Paper table must fire on at
     * least one supported version.
     *
     * <p>This is the other end of {@link PromptRule#appliesTo}'s safety net. That
     * invariant means a rule whose {@code requiresSymbols} are honest can never
     * emit a false claim — it is simply suppressed where its symbols are absent
     * — which is exactly right for the model and exactly wrong for the author,
     * who gets no signal at all that the rule they just wrote is silently
     * suppressed everywhere. A rule naming {@code Attribute.GENERIC_NOT_A_THING}
     * would sit in the table forever, teaching nobody, breaking nothing. This is
     * what notices.
     */
    private static void checkNoDeadRules(int versions) {
        for (PromptRule rule : PromptRules.PAPER) {
            assertions++;
            if (!EVER_FIRED.contains(rule.id())) {
                fail("all", rule.id(), "(the whole rule)", "fires somewhere",
                        "it fired on none of the " + versions + " supported versions, so it teaches"
                                + " nothing - its predicate or its requires/forbids list names"
                                + " something no supported Paper version has");
            }
        }
    }

    /**
     * The version-independent half — see {@link RuleSymbolDrift} — reported with
     * the one thing the jars can add to it: how wrong the sentence actually is.
     *
     * <p>An undeclared symbol is a defect on its own (nothing checks it), but
     * "{@code Enchantment.DURABILITY} exists on 5 of the 21 supported versions"
     * is the line that ends the argument. It is also the exact shape of the
     * defect this whole exercise exists to prevent: a sentence that was true on
     * the author's server and false on most of the others.
     */
    private static void driftWithTally(int versions) {
        // Counted even when the tables are clean, so the skip path reports one
        // assertion rather than zero: the version-independent half DID run.
        assertions++;
        for (RuleSymbolDrift.Violation v : DRIFT) {
            assertions++;
            List<String> present = DRIFT_PRESENT.getOrDefault(v.symbol(), List.of());
            String tally = v.symbol() == null
                    ? "no type given, so no jar can be asked"
                    : present.isEmpty()
                            ? "and no supported Paper version has it at all"
                            : present.size() == versions
                                    ? "it does exist on all " + versions + " supported versions"
                                    : "it exists on only " + present.size() + " of " + versions
                                            + " supported versions (" + String.join(", ", present) + ")";
            fail("all", v.ruleId(), v.symbol() == null ? "(bare constant)" : v.symbol(),
                    "declared in requires/forbids", v.message() + " - " + tally);
        }
    }

    // ------------------------------------------------------------------

    private static String fail(String version, String rule, String symbol,
                               String expected, String detail) {
        String line = String.format(Locale.ROOT,
                "FAIL  paper %-8s  rule=%-32s symbol=%-44s expected=%-22s %s",
                version, rule, symbol, expected, detail);
        FAILURES.add(line);
        return line;
    }

    private static void report(long started, int versions) {
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.println();
        if (!UNCHECKED_TYPES.isEmpty()) {
            System.out.println("Types the paper-api jars have no opinion on, so not checked ("
                    + UNCHECKED_TYPES.size() + "): " + UNCHECKED_TYPES);
        }
        if (!UNPROVEN_METHODS.isEmpty()) {
            System.out.println("Methods declared elsewhere in the jar - inherited, not proven ("
                    + UNPROVEN_METHODS.size() + "): " + UNPROVEN_METHODS);
        }
        if (!FAILURES.isEmpty()) {
            System.out.println();
            for (String failure : FAILURES) {
                System.out.println(failure);
            }
            System.out.println();
            System.out.println(FAILURES.size() + " of " + assertions + " ASSERTIONS FAILED across "
                    + versions + " versions in " + ms + "ms");
            System.exit(1);
        }
        System.out.println("ALL CHECKS PASSED (" + assertions + " assertions across "
                + versions + " versions, " + ms + "ms)");
    }
}
