package com.gijsm.vibemod.gen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.gijsm.vibemod.llm.PromptLibrary;
import com.gijsm.vibemod.platform.ApiVocabulary;
import com.gijsm.vibemod.store.ModStore;

import repair.RenameDerivation;
import symbols.ClassFileVocabulary;

/**
 * Standalone self-test (no test framework) for {@link SymbolRepair}: the
 * offline pre-compile pass that repairs API constant names against what the
 * running server was measured to declare.
 *
 * <p>Every vocabulary here except the two deliberately synthetic ones is
 * <strong>measured</strong> — read out of a real {@code paper-api} jar in
 * {@code paper/api-jars/} by {@link ClassFileVocabulary}. Testing a repair pass
 * against hand-written fakes would only prove the fake agrees with the code;
 * the whole point of this pass is that the API is not what anyone remembers.
 *
 * <p>The jar-backed half is skipped, not failed, when the cache is absent
 * (run {@code scripts/fetch-api-jars.sh}), so this is safe inside
 * {@code :core:selfTest}.
 */
public final class SymbolRepairSelfTest {

    private static int failures = 0;
    private static Path apiJars;

    public static void main(String[] args) throws Exception {
        apiJars = Path.of(System.getProperty("vibemod.apiJars", "paper/api-jars"));

        testScannerIgnoresLiteralsAndComments();
        testUnknownIsNeverTreatedAsAbsent();

        if (!Files.isDirectory(apiJars) || RenameDerivation.jarsIn(apiJars).isEmpty()) {
            System.out.println("SKIPPED: every jar-backed check (no api-jar cache at "
                    + apiJars.toAbsolutePath() + " — run scripts/fetch-api-jars.sh)");
        } else {
            testMeasuredTableMatchesTheJars();
            testAttributePrefixStrippedOnModern();
            testAttributePrefixRestoredOnLegacy();
            testNonGenericPrefixFamilies();
            testEnchantmentRenameOn1205();
            testStringsAndCommentsSurviveAgainstARealVocabulary();
            testCorrectCodeIsByteIdentical();
            testOpenWorldVocabularyOnlyRepairsWhatItMeasured();
            testSelfDeclaredTypesAreLeftAlone();
            testAmbiguityAudit();
            testNotesReachTheRepairPrompt();
            testTheStoredCorpus();
        }

        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
    }

    // ==================================================================
    // Rule 3: the scanner. No jar needed — this is pure lexing.
    // ==================================================================

    private static void testScannerIgnoresLiteralsAndComments() {
        String source = """
                package vibemod.demo;
                // Attribute.GENERIC_MAX_HEALTH in a line comment
                /* Attribute.GENERIC_MAX_HEALTH in a block comment
                   Attribute.GENERIC_MAX_HEALTH on a second line */
                /** {@code Attribute.GENERIC_MAX_HEALTH} in javadoc */
                public class Demo {
                    static final String DOC = "Attribute.GENERIC_MAX_HEALTH";
                    static final String ESC = "he said \\"Attribute.GENERIC_MAX_HEALTH\\" loudly";
                    static final char SLASH = '/';
                    static final String BLOCK = \"""
                            Attribute.GENERIC_MAX_HEALTH inside a text block
                            \""";
                    void go() {
                        use(Attribute.GENERIC_MAX_HEALTH);
                    }
                }
                """;

        List<SymbolRepair.Reference> refs = SymbolRepair.scan(source);
        List<String> found = new ArrayList<>();
        for (SymbolRepair.Reference r : refs) {
            found.add(r.type() + "." + r.constant());
        }
        check("scanner finds exactly one Attribute reference in code (found " + found + ")",
                found.equals(List.of("Attribute.GENERIC_MAX_HEALTH")));

        // The one it found is the one on the `use(...)` line, not any of the eight decoys.
        check("the reference the scanner found is the real code one",
                refs.size() == 1 && source.startsWith("use(Attribute.GENERIC_MAX_HEALTH)",
                        refs.get(0).typeStart() - "use(".length()));

        // Things that look like references but are not enum constants.
        String notConstants = """
                Class<?> c = Attribute.class;
                Attribute a = Attribute.valueOf(name);
                Particle.DustOptions o = new Particle.DustOptions(c, 1f);
                org.bukkit.Material m = org.bukkit.Material.STONE;
                """;
        List<String> kinds = new ArrayList<>();
        for (SymbolRepair.Reference r : SymbolRepair.scan(notConstants)) {
            kinds.add(r.type() + "." + r.constant());
        }
        check("SCREAMING_CASE filter keeps .class/.valueOf/nested types out (saw " + kinds + ")",
                kinds.equals(List.of("Material.STONE")));
    }

    // ==================================================================
    // Rule 1: UNKNOWN is never absent.
    // ==================================================================

    private static void testUnknownIsNeverTreatedAsAbsent() {
        String source = "class X { void f() { use(Attribute.GENERIC_MAX_HEALTH, Enchantment.DURABILITY); } }\n";
        Map<String, String> in = Map.of("X.java", source);

        SymbolRepair.Report report = SymbolRepair.repair(in, ApiVocabulary.empty());
        check("an empty vocabulary rewrites nothing", report.rewrites().isEmpty());
        check("an empty vocabulary reports nothing unresolved", report.unresolved().isEmpty());
        check("an empty vocabulary returns the source byte-identical",
                source.equals(report.sources().get("X.java")));

        SymbolRepair.Report nullVocab = SymbolRepair.repair(in, null);
        check("a null vocabulary is treated as empty, not as a crash",
                !nullVocab.changed() && source.equals(nullVocab.sources().get("X.java")));
    }

    // ==================================================================
    // The measured table
    // ==================================================================

    private static void testMeasuredTableMatchesTheJars() throws Exception {
        Map<String, List<Set<String>>> derived = RenameDerivation.derive(apiJars);
        Map<String, List<Set<String>>> embedded = SymbolRepair.measuredGroups();

        Set<String> derivedRows = rows(derived);
        Set<String> embeddedRows = rows(embedded);

        Set<String> missing = new TreeSet<>(derivedRows);
        missing.removeAll(embeddedRows);
        Set<String> extra = new TreeSet<>(embeddedRows);
        extra.removeAll(derivedRows);

        check("the embedded rename table still matches the jars — nothing missing"
                        + (missing.isEmpty() ? "" : " (missing " + missing + ")"),
                missing.isEmpty());
        check("the embedded rename table still matches the jars — nothing invented"
                        + (extra.isEmpty() ? "" : " (extra " + extra + ")"),
                extra.isEmpty());
        System.out.println("  (table: " + embeddedRows.size() + " alias groups across "
                + embedded.size() + " types, re-derived from "
                + RenameDerivation.jarsIn(apiJars).size() + " jars)");

        // The pairing is not a prefix rule in disguise: prove the hard cases are in there.
        check("DURABILITY and UNBREAKING were paired by measurement, not by spelling",
                SymbolRepair.aliasesOf("Enchantment", "DURABILITY").contains("UNBREAKING"));
        check("DIG_SPEED and EFFICIENCY were paired by measurement",
                SymbolRepair.aliasesOf("Enchantment", "DIG_SPEED").contains("EFFICIENCY"));
        check("the three-way jump-strength group is intact",
                SymbolRepair.aliasesOf("Attribute", "HORSE_JUMP_STRENGTH")
                        .equals(new TreeSet<>(List.of("GENERIC_JUMP_STRENGTH", "JUMP_STRENGTH"))));
        check("no pairing exists for Material (measured: it never renamed a constant)",
                !SymbolRepair.measuredGroups().containsKey("Material"));
    }

    private static Set<String> rows(Map<String, List<Set<String>>> groups) {
        Set<String> out = new TreeSet<>();
        for (Map.Entry<String, List<Set<String>>> e : groups.entrySet()) {
            for (Set<String> g : e.getValue()) {
                out.add(e.getKey() + "|" + String.join(",", new TreeSet<>(g)));
            }
        }
        return out;
    }

    // ==================================================================
    // Rule 2: repairs, measured per version
    // ==================================================================

    private static void testAttributePrefixStrippedOnModern() throws Exception {
        for (String version : List.of("1.21.3", "1.21.8", "26.2")) {
            ApiVocabulary vocab = vocabulary(version);
            check(version + ": Attribute.GENERIC_MAX_HEALTH -> MAX_HEALTH",
                    repairsTo(vocab, "Attribute.GENERIC_MAX_HEALTH", "Attribute.MAX_HEALTH"));
        }
    }

    private static void testAttributePrefixRestoredOnLegacy() throws Exception {
        for (String version : List.of("1.21.1", "1.21", "1.20.6")) {
            ApiVocabulary vocab = vocabulary(version);
            check(version + ": Attribute.MAX_HEALTH -> GENERIC_MAX_HEALTH (the repair runs both ways)",
                    repairsTo(vocab, "Attribute.MAX_HEALTH", "Attribute.GENERIC_MAX_HEALTH"));
        }
    }

    /**
     * The quarter of the attribute surface a {@code GENERIC_}-only rule misses:
     * eight of 1.21.1's 31 constants carry {@code PLAYER_}, {@code ZOMBIE_} or
     * {@code HORSE_} instead.
     */
    private static void testNonGenericPrefixFamilies() throws Exception {
        ApiVocabulary modern = vocabulary("1.21.3");
        check("1.21.3: Attribute.PLAYER_BLOCK_BREAK_SPEED -> BLOCK_BREAK_SPEED",
                repairsTo(modern, "Attribute.PLAYER_BLOCK_BREAK_SPEED", "Attribute.BLOCK_BREAK_SPEED"));
        check("1.21.3: Attribute.ZOMBIE_SPAWN_REINFORCEMENTS -> SPAWN_REINFORCEMENTS",
                repairsTo(modern, "Attribute.ZOMBIE_SPAWN_REINFORCEMENTS", "Attribute.SPAWN_REINFORCEMENTS"));
        check("1.21.3: Attribute.HORSE_JUMP_STRENGTH -> JUMP_STRENGTH (two hops from the 1.20.4 name)",
                repairsTo(modern, "Attribute.HORSE_JUMP_STRENGTH", "Attribute.JUMP_STRENGTH"));

        ApiVocabulary legacy = vocabulary("1.21.1");
        check("1.21.1: Attribute.BLOCK_BREAK_SPEED -> PLAYER_BLOCK_BREAK_SPEED",
                repairsTo(legacy, "Attribute.BLOCK_BREAK_SPEED", "Attribute.PLAYER_BLOCK_BREAK_SPEED"));
        check("1.21.1: Attribute.HORSE_JUMP_STRENGTH -> GENERIC_JUMP_STRENGTH "
                        + "(strip HORSE_, add GENERIC_ — no single prefix rule does this)",
                repairsTo(legacy, "Attribute.HORSE_JUMP_STRENGTH", "Attribute.GENERIC_JUMP_STRENGTH"));
    }

    private static void testEnchantmentRenameOn1205() throws Exception {
        for (String version : List.of("1.20.5", "1.20.6", "1.21.8")) {
            ApiVocabulary vocab = vocabulary(version);
            check(version + ": Enchantment.DURABILITY -> UNBREAKING",
                    repairsTo(vocab, "Enchantment.DURABILITY", "Enchantment.UNBREAKING"));
            check(version + ": Enchantment.DIG_SPEED -> EFFICIENCY",
                    repairsTo(vocab, "Enchantment.DIG_SPEED", "Enchantment.EFFICIENCY"));
            check(version + ": Particle.REDSTONE -> DUST",
                    repairsTo(vocab, "Particle.REDSTONE", "Particle.DUST"));
            check(version + ": PotionEffectType.SLOW -> SLOWNESS",
                    repairsTo(vocab, "PotionEffectType.SLOW", "PotionEffectType.SLOWNESS"));
        }
        ApiVocabulary legacy = vocabulary("1.20.4");
        check("1.20.4: Enchantment.UNBREAKING -> DURABILITY (the other direction, same table)",
                repairsTo(legacy, "Enchantment.UNBREAKING", "Enchantment.DURABILITY"));
    }

    // ==================================================================
    // Rule 3, against a real vocabulary end to end
    // ==================================================================

    private static void testStringsAndCommentsSurviveAgainstARealVocabulary() throws Exception {
        ApiVocabulary modern = vocabulary("1.21.8");
        String source = """
                package vibemod.demo;
                // legacy name: Attribute.GENERIC_MAX_HEALTH
                /* also legacy: Enchantment.DURABILITY */
                public class Demo {
                    void go(Player p) {
                        p.sendMessage("Attribute.GENERIC_MAX_HEALTH");
                        p.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(40);
                    }
                }
                """;
        SymbolRepair.Report report = SymbolRepair.repair(Map.of("Demo.java", source), modern);
        String out = report.sources().get("Demo.java");

        check("exactly one rewrite (the code one), not four", report.rewrites().size() == 1);
        check("the line comment survives untouched",
                out.contains("// legacy name: Attribute.GENERIC_MAX_HEALTH"));
        check("the block comment survives untouched",
                out.contains("/* also legacy: Enchantment.DURABILITY */"));
        check("the string literal survives untouched",
                out.contains("p.sendMessage(\"Attribute.GENERIC_MAX_HEALTH\");"));
        check("the real call site was repaired",
                out.contains("p.getAttribute(Attribute.MAX_HEALTH).setBaseValue(40);"));
        check("nothing else in the file moved",
                out.replace("Attribute.MAX_HEALTH).setBaseValue", "Attribute.GENERIC_MAX_HEALTH).setBaseValue")
                        .equals(source));
    }

    private static void testCorrectCodeIsByteIdentical() throws Exception {
        ApiVocabulary modern = vocabulary("1.21.8");
        String source = """
                package vibemod.demo;
                public class Demo {
                    void go(Player p) {
                        p.getAttribute(Attribute.MAX_HEALTH).setBaseValue(40);
                        p.getWorld().spawnParticle(Particle.DUST, p.getLocation(), 10);
                        p.getInventory().getItemInMainHand()
                                .addEnchantment(Enchantment.UNBREAKING, 3);
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20, 1));
                        p.getWorld().spawnEntity(p.getLocation(), EntityType.ZOMBIE);
                        p.getInventory().addItem(new ItemStack(Material.DIAMOND_SWORD));
                    }
                }
                """;
        SymbolRepair.Report report = SymbolRepair.repair(Map.of("Demo.java", source), modern);
        check("already-correct code is returned byte-identical",
                source.equals(report.sources().get("Demo.java")));
        check("already-correct code produces no rewrites", report.rewrites().isEmpty());
        check("already-correct code produces no diagnostics"
                        + (report.unresolved().isEmpty() ? "" : " (got " + report.unresolved() + ")"),
                report.unresolved().isEmpty());
    }

    // ==================================================================
    // Rule 1 again, with the open-world shape a live host actually has
    // ==================================================================

    /**
     * {@code ReflectiveVocabulary} resolves a hand-supplied list of types, so a
     * type outside that list is {@code UNKNOWN} rather than absent. This models
     * exactly that: a host that measured {@code Enchantment} and never looked at
     * {@code Attribute}. It must repair the first and not touch the second — and
     * it must not emit a diagnostic about the second either, because it has no
     * evidence there is anything wrong with it.
     */
    private static void testOpenWorldVocabularyOnlyRepairsWhatItMeasured() throws Exception {
        ClassFileVocabulary jar = ClassFileVocabulary.ofJar(jarFor("1.21.8"));
        ApiVocabulary partial = openWorld(jar, Set.of("Enchantment"));

        String source = """
                class Demo {
                    void go(ItemStack i, LivingEntity e) {
                        i.addEnchantment(Enchantment.DURABILITY, 3);
                        e.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(40);
                    }
                }
                """;
        SymbolRepair.Report report = SymbolRepair.repair(Map.of("Demo.java", source), partial);
        String out = report.sources().get("Demo.java");
        check("the measured type is repaired", out.contains("Enchantment.UNBREAKING"));
        check("the UNMEASURED type is left exactly alone",
                out.contains("Attribute.GENERIC_MAX_HEALTH"));
        check("the unmeasured type produces no diagnostic either",
                report.unresolved().isEmpty());
        check("exactly one rewrite total", report.rewrites().size() == 1);
    }

    /**
     * A project type that shadows an API name is none of the pass's business,
     * even when the vocabulary happens to know a Bukkit type by that name.
     */
    private static void testSelfDeclaredTypesAreLeftAlone() throws Exception {
        ApiVocabulary modern = vocabulary("1.21.8");
        String source = """
                class Demo {
                    enum Particle { REDSTONE, GLOW }
                    void go() { pick(Particle.REDSTONE); }
                }
                """;
        SymbolRepair.Report report = SymbolRepair.repair(Map.of("Demo.java", source), modern);
        check("a project's own type shadows the API type of the same name",
                source.equals(report.sources().get("Demo.java")) && report.rewrites().isEmpty());
    }

    // ==================================================================
    // Rule 2's other half: refuse to guess
    // ==================================================================

    /**
     * Sweeps every cached version against every name in the measured table and
     * reports any absent constant with more than one surviving candidate. Those
     * are the cases the pass must refuse; the sweep is here so the claim "we
     * checked" is a measurement rather than a hope.
     */
    private static void testAmbiguityAudit() throws Exception {
        List<String> ambiguous = new ArrayList<>();
        int probed = 0;
        for (Path jar : RenameDerivation.jarsIn(apiJars)) {
            ClassFileVocabulary vocab = ClassFileVocabulary.ofJar(jar);
            String version = versionOf(jar);
            for (Map.Entry<String, List<Set<String>>> type : SymbolRepair.measuredGroups().entrySet()) {
                Set<String> names = new TreeSet<>();
                type.getValue().forEach(names::addAll);
                for (String name : names) {
                    if (vocab.declaresConstant(type.getKey(), name) != ApiVocabulary.Known.NO) {
                        continue;
                    }
                    probed++;
                    List<String> surviving =
                            SymbolRepair.survivingCandidates(type.getKey(), name, vocab);
                    if (surviving.size() > 1) {
                        ambiguous.add(version + " " + type.getKey() + "." + name + " -> " + surviving);
                    }
                }
            }
        }
        System.out.println("  (ambiguity audit: " + probed + " absent constants probed across "
                + RenameDerivation.jarsIn(apiJars).size() + " versions, "
                + ambiguous.size() + " ambiguous)");
        for (String a : ambiguous) {
            System.out.println("    ambiguous: " + a);
        }

        // Whatever the sweep found, the refusal itself has to be provable. A
        // vocabulary declaring BOTH spellings is the case that must not be
        // guessed at, and it is synthetic on purpose: no shipped Paper version
        // declares both, which is exactly why it needs constructing to test.
        ClassFileVocabulary legacy = ClassFileVocabulary.ofJar(jarFor("1.21.1"));
        ClassFileVocabulary modern = ClassFileVocabulary.ofJar(jarFor("1.21.3"));
        Set<String> both = new TreeSet<>(legacy.constants("Attribute"));
        both.addAll(modern.constants("Attribute"));
        ApiVocabulary bilingual = fixedType("Attribute", both);

        String source = "class Demo { void go() { use(Attribute.HORSE_JUMP_STRENGTH); } }\n";
        SymbolRepair.Report report = SymbolRepair.repair(Map.of("Demo.java", source), bilingual);
        check("a genuinely ambiguous constant is NOT rewritten",
                source.equals(report.sources().get("Demo.java")) && report.rewrites().isEmpty());
        check("a genuinely ambiguous constant IS reported", report.unresolved().size() == 1);
        SymbolRepair.Unresolved u = report.unresolved().get(0);
        check("the report names both real candidates instead of picking one (" + u.candidates() + ")",
                u.ambiguous()
                        && u.candidates().equals(List.of("GENERIC_JUMP_STRENGTH", "JUMP_STRENGTH")));
        check("the ambiguous diagnostic says the host refused to guess",
                u.describe().contains("refused to guess"));

        // Zero candidates: a name nothing explains. Report, with real neighbours.
        String typo = "class Demo { void go() { use(Attribute.MAX_HEALTHH); } }\n";
        SymbolRepair.Report typoReport = SymbolRepair.repair(Map.of("Demo.java", typo), vocabulary("1.21.8"));
        check("a plain typo is not rewritten", typo.equals(typoReport.sources().get("Demo.java")));
        check("a plain typo is reported with the nearest real names",
                typoReport.unresolved().size() == 1
                        && typoReport.unresolved().get(0).hints().contains("MAX_HEALTH"));
        System.out.println("    typo hint: " + typoReport.unresolved().get(0).describe());
    }

    // ==================================================================
    // Rule 4: what cannot be fixed reaches the model
    // ==================================================================

    private static void testNotesReachTheRepairPrompt() throws Exception {
        ApiVocabulary modern = vocabulary("1.21.8");
        String source = """
                class Demo {
                    void go(LivingEntity e) {
                        e.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                        e.getAttribute(Attribute.MAX_HEALTHH);
                    }
                }
                """;
        SymbolRepair.Report report = SymbolRepair.repair(Map.of("Demo.java", source), modern);
        List<String> notes = report.notes();
        check("the report produces notes for both the fixed and the unfixed symbol", notes.size() == 2);

        String prompt = PromptLibrary.repairPrompt("Demo.java:4: error: cannot find symbol", notes);
        check("the repair prompt carries the applied rewrite so the model does not undo it",
                prompt.contains("the host already rewrote it to Attribute.MAX_HEALTH"));
        check("the repair prompt carries the unresolved constant's real neighbours",
                prompt.contains("MAX_HEALTHH does not exist on this server"));
        check("the repair prompt tells the model the measurement outranks its training",
                prompt.contains("measured directly at boot"));
        check("the javac diagnostics are still there",
                prompt.contains("cannot find symbol"));
        check("repairPrompt with no notes is unchanged from the old one-argument form",
                PromptLibrary.repairPrompt("boom").equals(PromptLibrary.repairPrompt("boom", List.of())));

        // javac is the tie-breaker. ApiVocabulary is keyed by SIMPLE name, so it
        // can answer about the wrong type; if the compiler did not complain about
        // a symbol, that symbol is fine whatever we suspect.
        List<String> filtered = report.notesFor("Demo.java:3: error: cannot find symbol GENERIC_MAX_HEALTH");
        check("a suspicion javac did not raise is dropped from the prompt", filtered.size() == 1);
        check("the applied rewrite survives the filter anyway",
                filtered.get(0).contains("the host already rewrote it"));
        List<String> agreeing = report.notesFor("Demo.java:4: error: cannot find symbol MAX_HEALTHH");
        check("a suspicion javac DID raise reaches the prompt", agreeing.size() == 2);
        check("with no diagnostics at all (the enable-crash path) only rewrites are restated",
                report.notesFor(null).size() == 1);
    }

    // ==================================================================
    // The real corpus
    // ==================================================================

    /**
     * Runs the pass over every stored mod source there is and reports what it
     * would do. The corpus already compiles ({@code :core:selfTestStore} proves
     * it), so against the vocabulary of the version it was generated for the
     * honest expectation is ZERO rewrites — anything else is the pass wanting to
     * change working code and is a finding, not a pass.
     */
    private static void testTheStoredCorpus() throws Exception {
        ApiVocabulary buildVocab = vocabulary("1.21.8");
        int corpora = 0;
        for (String property : List.of("vibemod.fixture.mods.dir", "vibemod.mods.dir")) {
            String configured = System.getProperty(property, "");
            if (configured.isBlank() || !Files.isDirectory(Path.of(configured))) {
                System.out.println("  (corpus " + property + ": absent, skipped)");
                continue;
            }
            corpora++;
            scanCorpus(Path.of(configured), property, buildVocab);
        }
        if (corpora == 0) {
            System.out.println("SKIPPED: corpus scan (no corpus directory configured)");
        }
    }

    private static void scanCorpus(Path modsDir, String label, ApiVocabulary vocab) {
        ModStore store = new ModStore(modsDir);
        Map<String, String> all = new LinkedHashMap<>();
        int versions = 0;
        for (ModStore.StoredMod mod : store.all()) {
            for (int v : store.versionsOnDisk(mod.name())) {
                versions++;
                for (Map.Entry<String, String> src : store.sources(mod.name(), v).entrySet()) {
                    all.put(mod.name() + "/v" + v + "/" + src.getKey(), src.getValue());
                }
            }
        }
        SymbolRepair.Report report = SymbolRepair.repair(all, vocab);
        Set<String> touched = new TreeSet<>();
        for (SymbolRepair.Rewrite r : report.rewrites()) {
            touched.add(r.file());
        }
        System.out.println("  (corpus " + label + ": " + store.all().size() + " mods, " + versions
                + " versions, " + all.size() + " files; " + report.rewrites().size()
                + " rewrite(s) in " + touched.size() + " file(s), "
                + report.unresolved().size() + " unresolved)");
        for (SymbolRepair.Rewrite r : report.rewrites()) {
            System.out.println("    would rewrite: " + r);
        }
        for (SymbolRepair.Unresolved u : report.unresolved()) {
            System.out.println("    suspicion (dropped unless javac agrees): "
                    + u.file() + ":" + u.line() + " " + u.describe());
        }
        check(label + ": the pass leaves the already-compiling corpus alone (" + touched.size()
                + " file(s) would change)", report.rewrites().isEmpty());
        // The corpus compiles, so javac names none of these symbols and every
        // suspicion is correctly filtered out before it can reach a prompt.
        check(label + ": no suspicion about already-compiling code reaches the model",
                report.notesFor("").isEmpty());

        // The counterfactual, and the actual case for this pass existing: the
        // corpus was written for a 1.21.8 server. Move it to an earlier one and
        // every one of these is a compile error the host now fixes for free.
        for (String era : List.of("1.21.1", "1.20.4")) {
            try {
                SymbolRepair.Report moved = SymbolRepair.repair(all, vocabulary(era));
                Set<String> movedFiles = new TreeSet<>();
                Set<String> kinds = new TreeSet<>();
                for (SymbolRepair.Rewrite r : moved.rewrites()) {
                    movedFiles.add(r.file());
                    kinds.add(r.type() + "." + r.from() + " -> " + r.to());
                }
                System.out.println("    on a " + era + " server this corpus would need "
                        + moved.rewrites().size() + " repair(s) in " + movedFiles.size()
                        + " file(s): " + kinds);

                // Repairing the repaired sources must be a no-op, or a later
                // round could walk a constant back and forth forever.
                SymbolRepair.Report again = SymbolRepair.repair(moved.sources(), vocabulary(era));
                check(label + " @" + era + ": the pass is idempotent", !again.changed());
            } catch (Exception e) {
                System.out.println("    (no " + era + " jar: " + e + ")");
            }
        }
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    /** True when the pass turns {@code from} into {@code to} and touches nothing else. */
    private static boolean repairsTo(ApiVocabulary vocab, String from, String to) {
        String source = "class Demo { void go() { use(" + from + "); } }\n";
        String want = "class Demo { void go() { use(" + to + "); } }\n";
        SymbolRepair.Report report = SymbolRepair.repair(Map.of("Demo.java", source), vocab);
        boolean ok = want.equals(report.sources().get("Demo.java")) && report.rewrites().size() == 1;
        if (!ok) {
            System.out.println("      got: " + report.sources().get("Demo.java").trim()
                    + " | unresolved=" + report.unresolved());
        }
        return ok;
    }

    private static final Map<String, ApiVocabulary> CACHE = new TreeMap<>();

    private static ApiVocabulary vocabulary(String version) throws Exception {
        ApiVocabulary cached = CACHE.get(version);
        if (cached == null) {
            cached = ClassFileVocabulary.ofJar(jarFor(version));
            CACHE.put(version, cached);
        }
        return cached;
    }

    private static Path jarFor(String version) {
        return apiJars.resolve("paper-api-" + version + ".jar");
    }

    private static String versionOf(Path jar) {
        String n = jar.getFileName().toString();
        return n.replace("paper-api-", "").replace(".jar", "");
    }

    /**
     * A vocabulary shaped like {@code ReflectiveVocabulary}: it measured exactly
     * {@code measured} and answers {@code UNKNOWN} — never {@code NO} — for
     * everything else.
     */
    private static ApiVocabulary openWorld(ClassFileVocabulary backing, Set<String> measured) {
        return new ApiVocabulary() {
            @Override
            public Set<String> knownTypes() {
                return measured;
            }

            @Override
            public Set<String> constants(String type) {
                return measured.contains(type) ? backing.constants(type) : Set.of();
            }

            @Override
            public Set<String> methods(String type) {
                return measured.contains(type) ? backing.methods(type) : Set.of();
            }

            @Override
            public Known knows(String type) {
                return measured.contains(type) ? Known.YES : Known.UNKNOWN;
            }

            @Override
            public Known declaresConstant(String type, String constant) {
                if (!measured.contains(type)) {
                    return Known.UNKNOWN;
                }
                return backing.constants(type).contains(constant) ? Known.YES : Known.NO;
            }
        };
    }

    /** A closed-world vocabulary over exactly one type with exactly this constant set. */
    private static ApiVocabulary fixedType(String type, Set<String> constants) {
        return new ApiVocabulary() {
            @Override
            public Set<String> knownTypes() {
                return Set.of(type);
            }

            @Override
            public Set<String> constants(String t) {
                return type.equals(t) ? constants : Set.of();
            }

            @Override
            public Set<String> methods(String t) {
                return Set.of();
            }

            @Override
            public Known knows(String t) {
                return type.equals(t) ? Known.YES : Known.UNKNOWN;
            }

            @Override
            public Known declaresConstant(String t, String constant) {
                if (!type.equals(t)) {
                    return Known.UNKNOWN;
                }
                return constants.contains(constant) ? Known.YES : Known.NO;
            }
        };
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
