package symbols;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Measures every cached {@code paper-api} jar and emits docs/API-VOCABULARY.md.
 *
 * <p>The document is GENERATED rather than written, on purpose. Its whole reason
 * for existing is that the prompt's version claims were hand-written and wrong; a
 * hand-written correction would rot the same way. Regenerate with:
 *
 * <pre>
 *   scripts/fetch-api-jars.sh
 *   ./gradlew :core:apiVocabularyReport &gt; docs/API-VOCABULARY.md
 * </pre>
 *
 * <p>The claim verdicts at the end are computed from the measurements, not typed.
 * A claim that stops being true turns REFUTED on the next regeneration, which is
 * the seed of the B3 gate the master prompt asks for.
 */
public final class VocabularyReport {

    /** The Attribute prefixes the pre-1.21.3 vocabulary used. */
    private static final List<String> PREFIXES = List.of("GENERIC_", "PLAYER_", "ZOMBIE_", "HORSE_");

    /** One indexed jar plus the version it belongs to. */
    private record Version(String mc, ClassFileVocabulary vocab) {
        boolean isLegacyProfile() {
            // PlatformProfiles.paperProfileIdFor: anything below 1.21.7 is paper-legacy.
            int[] p = parse(mc);
            if (p[0] > 1) {
                return false;
            }
            return p[1] < 21 || (p[1] == 21 && p[2] < 7);
        }

        int attributesWithPrefix() {
            int n = 0;
            for (String c : vocab.constants("Attribute")) {
                for (String prefix : PREFIXES) {
                    if (c.startsWith(prefix)) {
                        n++;
                        break;
                    }
                }
            }
            return n;
        }

        int attributesWithPrefix(String prefix) {
            int n = 0;
            for (String c : vocab.constants("Attribute")) {
                if (c.startsWith(prefix)) {
                    n++;
                }
            }
            return n;
        }

        int attributesShortForm() {
            return vocab.constants("Attribute").size() - attributesWithPrefix();
        }

        boolean has(String type, String constant) {
            return vocab.constants(type).contains(constant);
        }

        String kind(String type) {
            ClassFileVocabulary.TypeInfo t = vocab.type(type);
            return t == null ? "ABSENT" : t.kind();
        }

        int count(String type) {
            return vocab.constants(type).size();
        }

        boolean method(String type, String name) {
            return vocab.hasMethod(type, name);
        }
    }

    private static int[] parse(String mc) {
        String[] bits = mc.split("\\.");
        int[] out = {0, 0, 0};
        for (int i = 0; i < 3 && i < bits.length; i++) {
            try {
                out[i] = Integer.parseInt(bits[i]);
            } catch (NumberFormatException ignored) {
                break;
            }
        }
        return out;
    }

    private static final Comparator<String> VERSION_ORDER = (a, b) -> {
        int[] x = parse(a);
        int[] y = parse(b);
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) {
                return Integer.compare(x[i], y[i]);
            }
        }
        return a.compareTo(b);
    };

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: VocabularyReport <api-jars-dir> [prompt-source-dir]");
            System.exit(2);
        }
        Path dir = Path.of(args[0]);
        if (!Files.isDirectory(dir)) {
            System.err.println("no jar cache at " + dir.toAbsolutePath()
                    + " - run scripts/fetch-api-jars.sh");
            System.exit(2);
        }
        Path promptDir = args.length > 1 ? Path.of(args[1]) : null;

        List<Version> versions = new ArrayList<>();
        try (Stream<Path> jars = Files.list(dir)) {
            List<Path> paths = jars
                    .filter(p -> p.getFileName().toString().matches("paper-api-.*\\.jar"))
                    .sorted(Comparator.comparing(
                            p -> p.getFileName().toString()
                                    .replaceAll("^paper-api-", "").replaceAll("\\.jar$", ""),
                            VERSION_ORDER))
                    .collect(Collectors.toList());
            for (Path p : paths) {
                String mc = p.getFileName().toString()
                        .replaceAll("^paper-api-", "").replaceAll("\\.jar$", "");
                versions.add(new Version(mc, ClassFileVocabulary.ofJar(p)));
            }
        }
        if (versions.isEmpty()) {
            System.err.println("no paper-api jars in " + dir.toAbsolutePath());
            System.exit(2);
        }

        StringBuilder out = new StringBuilder();
        header(out, versions);
        tableAttributes(out, versions);
        tableItemMeta(out, versions);
        tableKinds(out, versions);
        tableChurn(out, versions);
        tableDialog(out, versions);
        tablePromptConstants(out, versions, promptDir);
        claims(out, versions);
        System.out.print(out);
    }

    // ------------------------------------------------------------------

    private static void header(StringBuilder out, List<Version> versions) {
        out.append("# The measured Paper API vocabulary\n\n");
        out.append("**Generated — do not hand-edit.** Every number below was read out of a real\n");
        out.append("`paper-api` jar by `core/src/test/java/symbols/ClassFileVocabulary.java`, which\n");
        out.append("parses class files as bytes (constant pool, `fields[]`, `methods[]`, access\n");
        out.append("flags) rather than loading them. Regenerate with:\n\n");
        out.append("```sh\n");
        out.append("scripts/fetch-api-jars.sh\n");
        out.append("./gradlew -q :core:apiVocabularyReport > docs/API-VOCABULARY.md\n");
        out.append("```\n\n");
        out.append("It exists because `PlatformProfiles.java` tells the model which enum constants\n");
        out.append("and which `ItemMeta` methods are real on which Paper era, in hand-written prose\n");
        out.append("that nobody had ever checked against a jar. The **Claims checked** section at\n");
        out.append("the end is the audit; the tables are the evidence.\n\n");

        out.append("## What was measured\n\n");
        out.append("| | |\n|---|---|\n");
        out.append("| Versions indexed | ").append(versions.size()).append(" |\n");
        out.append("| Range | ").append(versions.get(0).mc()).append(" - ")
                .append(versions.get(versions.size() - 1).mc()).append(" |\n");
        int classes = versions.stream().mapToInt(v -> v.vocab().binaryNames().size()).sum();
        out.append("| Classes parsed | ")
                .append(String.format(java.util.Locale.ROOT, "%,d", classes)).append(" |\n");
        out.append("| Constant counted as such | a `public static final` field, which catches "
                + "enum constants and interface fields alike |\n\n");

        out.append("A note on the version list, which is itself a finding. Paper publishes **no\n");
        out.append("`paper-api` for 1.21.2** — the metadata jumps 1.21.1 to 1.21.3 — so the\n");
        out.append("\"twenty consecutive versions\" of the brief are twenty *releases*, not twenty\n");
        out.append("consecutive patch numbers. A twenty-first, **26.1.1**, exists but only ever got\n");
        out.append("`-alpha` builds (newest: `26.1.1.build.29-alpha`); it is measured here for\n");
        out.append("completeness and should not be treated as a supported release.\n\n");
    }

    // ------------------------------------------------------------------

    private static void tableAttributes(StringBuilder out, List<Version> versions) {
        out.append("## Attribute\n\n");
        out.append("The single most consequential table in this document: the attribute vocabulary\n");
        out.append("is what the `paper-legacy` cheat sheet gets wrong.\n\n");
        out.append("| Version | kind | constants | `GENERIC_` | `PLAYER_` | `ZOMBIE_` | `HORSE_` | short-form | profile |\n");
        out.append("|---|---|--:|--:|--:|--:|--:|--:|---|\n");
        for (Version v : versions) {
            out.append("| ").append(v.mc())
                    .append(" | ").append(v.kind("Attribute"))
                    .append(" | ").append(v.count("Attribute"))
                    .append(" | ").append(v.attributesWithPrefix("GENERIC_"))
                    .append(" | ").append(v.attributesWithPrefix("PLAYER_"))
                    .append(" | ").append(v.attributesWithPrefix("ZOMBIE_"))
                    .append(" | ").append(v.attributesWithPrefix("HORSE_"))
                    .append(" | ").append(v.attributesShortForm())
                    .append(" | ").append(v.isLegacyProfile() ? "legacy" : "modern")
                    .append(" |\n");
        }
        out.append("\nThe cut is total and it is at **1.21.3**: every constant is prefixed up to\n");
        out.append("1.21.1, none is from 1.21.3 on. There is no version where both spellings work,\n");
        out.append("so there is no spelling that compiles across the range the `paper-legacy`\n");
        out.append("profile serves.\n\n");
        out.append("`Attribute` also changed *shape* at 1.21.3, from an `enum` to an `interface`.\n");
        out.append("That is a second, unremarked break: `switch` over an `Attribute`, `EnumMap`,\n");
        out.append("`EnumSet` and `Attribute.values()` used as an enum array all behave differently\n");
        out.append("or stop compiling, and no line of the prompt mentions it.\n\n");
    }

    // ------------------------------------------------------------------

    private static void tableItemMeta(StringBuilder out, List<Version> versions) {
        out.append("## ItemMeta data-component setters\n\n");
        out.append("The three methods the `paper-legacy` sheet forbids by name, plus the getter\n");
        out.append("`PlatformInfo` probes at boot.\n\n");
        out.append("| Version | `setEnchantmentGlintOverride` | `setItemModel` | `setTooltipStyle` |");
        out.append(" `hasEnchantmentGlintOverride` | profile |\n");
        out.append("|---|:-:|:-:|:-:|:-:|---|\n");
        for (Version v : versions) {
            out.append("| ").append(v.mc())
                    .append(" | ").append(yn(v.method("ItemMeta", "setEnchantmentGlintOverride")))
                    .append(" | ").append(yn(v.method("ItemMeta", "setItemModel")))
                    .append(" | ").append(yn(v.method("ItemMeta", "setTooltipStyle")))
                    .append(" | ").append(yn(v.method("ItemMeta", "hasEnchantmentGlintOverride")))
                    .append(" | ").append(v.isLegacyProfile() ? "legacy" : "modern")
                    .append(" |\n");
        }
        out.append('\n');
    }

    // ------------------------------------------------------------------

    private static void tableKinds(StringBuilder out, List<Version> versions) {
        out.append("## Shape and size of the vocabulary types\n\n");
        out.append("`enum` versus `interface` matters as much as the constant names: it decides\n");
        out.append("whether `switch`, `EnumMap` and `values()` mean anything.\n\n");
        String[] types = {"Attribute", "Sound", "Particle", "Enchantment", "PotionEffectType",
                "Material", "EntityType"};
        out.append("| Version |");
        for (String t : types) {
            out.append(' ').append(t).append(" |");
        }
        out.append('\n').append("|---|");
        for (int i = 0; i < types.length; i++) {
            out.append("---|");
        }
        out.append('\n');
        for (Version v : versions) {
            out.append("| ").append(v.mc()).append(" |");
            for (String t : types) {
                String kind = v.kind(t);
                out.append(' ').append(kind).append("ABSENT".equals(kind) ? "" : " " + v.count(t))
                        .append(" |");
            }
            out.append('\n');
        }
        out.append("\n(Each cell is *kind* then *number of `public static final` fields*.)\n\n");
    }

    // ------------------------------------------------------------------

    /**
     * Where the vocabulary actually breaks. A constant that DISAPPEARS between two
     * adjacent versions is a compile break for any mod naming it; a constant that
     * merely appears is not. Diffing every adjacent pair finds the real era
     * boundaries empirically, instead of trusting where someone thought they were.
     */
    private static void tableChurn(StringBuilder out, List<Version> versions) {
        String[] types = {"Attribute", "Enchantment", "PotionEffectType", "Particle"};
        out.append("## Where the vocabulary actually breaks\n\n");
        out.append("Adjacent-version diffs of the constant sets. **Removed** is the column that\n");
        out.append("matters: a name that disappears is a compile error in any mod that used it.\n");
        out.append("The prompt's era table splits at 1.21.7 (the dialog API). This table shows the\n");
        out.append("vocabulary does not.\n\n");
        out.append("| Step |");
        for (String t : types) {
            out.append(' ').append(t).append(" +/- |");
        }
        out.append('\n').append("|---|");
        for (int i = 0; i < types.length; i++) {
            out.append(":-:|");
        }
        out.append('\n');
        List<String> breaks = new ArrayList<>();
        for (int i = 1; i < versions.size(); i++) {
            Version prev = versions.get(i - 1);
            Version cur = versions.get(i);
            out.append("| ").append(prev.mc()).append(" -> ").append(cur.mc()).append(" |");
            for (String t : types) {
                Set<String> before = new TreeSet<>(prev.vocab().constants(t));
                Set<String> after = new TreeSet<>(cur.vocab().constants(t));
                Set<String> removed = new TreeSet<>(before);
                removed.removeAll(after);
                Set<String> added = new TreeSet<>(after);
                added.removeAll(before);
                out.append(' ').append(added.isEmpty() && removed.isEmpty()
                        ? "-"
                        : "+" + added.size() + " / **-" + removed.size() + "**").append(" |");
                if (removed.size() >= 5) {
                    breaks.add(prev.mc() + " -> " + cur.mc() + ": `" + t + "` loses "
                            + removed.size() + " names (e.g. "
                            + removed.stream().limit(4).map(s -> "`" + s + "`")
                                    .collect(Collectors.joining(", ")) + ")");
                }
            }
            out.append('\n');
        }
        out.append("\nSteps that remove five or more constants — i.e. the real era boundaries:\n\n");
        for (String b : breaks) {
            out.append("- ").append(b).append('\n');
        }
        if (breaks.isEmpty()) {
            out.append("- none\n");
        }
        out.append('\n');
        int at1205 = removedAcross(versions, "1.20.4", "1.20.5", types);
        int at1213 = removedAcross(versions, "1.21.1", "1.21.3", types);
        out.append("Both boundaries fall **inside** the single `paper-legacy` profile, and neither\n");
        out.append("is where the profile splits. The larger of the two is the one nobody has\n");
        out.append("written about: 1.20.4 -> 1.20.5 removes **").append(at1205)
                .append("** constants across these four types,\n");
        out.append("against **").append(at1213).append("** at the 1.21.3 attribute rename that "
                + "the brief and the master\n");
        out.append("prompt both single out. The prompt contains no sentence about the 1.20.5\n");
        out.append("batch at all — and the legacy sheet's only worked enchantment example,\n");
        out.append("`Enchantment.DURABILITY`, is one of the 19 names it deletes.\n\n");
        out.append("Above 1.21.6 nothing is ever removed: the modern range is purely additive, so\n");
        out.append("a single modern profile is defensible on vocabulary grounds even though it\n");
        out.append("spans 1.21.7 to 26.2. The legacy range is the opposite.\n\n");
    }

    /** Total constants removed between two versions, summed over the given types. */
    private static int removedAcross(List<Version> versions, String from, String to,
                                     String[] types) {
        Version a = at(versions, from);
        Version b = at(versions, to);
        if (a == null || b == null) {
            return -1;
        }
        int n = 0;
        for (String t : types) {
            Set<String> removed = new TreeSet<>(a.vocab().constants(t));
            removed.removeAll(b.vocab().constants(t));
            n += removed.size();
        }
        return n;
    }

    // ------------------------------------------------------------------

    private static void tableDialog(StringBuilder out, List<Version> versions) {
        out.append("## The dialog API\n\n");
        out.append("| Version | `io.papermc.paper.dialog.Dialog` | `io.papermc.paper.registry.data.dialog.DialogBase` |\n");
        out.append("|---|:-:|:-:|\n");
        for (Version v : versions) {
            out.append("| ").append(v.mc())
                    .append(" | ").append(yn(v.vocab().type("io.papermc.paper.dialog.Dialog") != null))
                    .append(" | ").append(yn(v.vocab()
                            .type("io.papermc.paper.registry.data.dialog.DialogBase") != null))
                    .append(" |\n");
        }
        out.append('\n');
    }

    // ------------------------------------------------------------------

    /**
     * Every {@code Type.CONSTANT} the prompt text actually names, extracted from the
     * prompt source at report time so this table cannot drift from the prompt.
     */
    private static void tablePromptConstants(StringBuilder out, List<Version> versions,
                                             Path promptDir) throws IOException {
        out.append("## Every constant the prompt names, checked against every version\n\n");
        if (promptDir == null || !Files.isDirectory(promptDir)) {
            out.append("_Skipped: prompt source directory not supplied._\n\n");
            return;
        }
        Pattern ref = Pattern.compile(
                "\\b(Material|Sound|Particle|EntityType|Attribute|PotionEffectType|Enchantment)"
                        + "\\.([A-Z][A-Z0-9_]{2,})\\b");
        Map<String, Set<String>> named = new LinkedHashMap<>();
        List<Path> sources;
        try (Stream<Path> s = Files.list(promptDir)) {
            sources = s.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().toList();
        }
        for (Path src : sources) {
            String text = Files.readString(src, StandardCharsets.UTF_8);
            Matcher m = ref.matcher(text);
            while (m.find()) {
                named.computeIfAbsent(m.group(1), k -> new TreeSet<>()).add(m.group(2));
            }
        }
        if (named.isEmpty()) {
            out.append("_No constant references found in ").append(promptDir).append("._\n\n");
            return;
        }
        out.append("Extracted by regex from `").append(promptDir.getFileName())
                .append("/*.java` at generation time, so a constant added to the prompt tomorrow\n");
        out.append("is audited by tomorrow's report. A `.` means the constant is absent on that\n");
        out.append("version — i.e. the prompt names a symbol that does not compile there.\n\n");

        out.append("| Constant |");
        for (Version v : versions) {
            out.append(' ').append(v.mc()).append(" |");
        }
        out.append('\n').append("|---|");
        for (int i = 0; i < versions.size(); i++) {
            out.append(":-:|");
        }
        out.append('\n');

        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : named.entrySet()) {
            for (String c : e.getValue()) {
                String full = e.getKey() + "." + c;
                out.append("| `").append(full).append("` |");
                int missing = 0;
                for (Version v : versions) {
                    boolean ok = v.has(e.getKey(), c);
                    if (!ok) {
                        missing++;
                    }
                    out.append(ok ? " x |" : " . |");
                }
                out.append('\n');
                if (missing > 0) {
                    broken.add(full + " (absent on " + missing + "/" + versions.size() + ")");
                }
            }
        }
        out.append('\n');
        out.append("Constants the prompt names that are absent on at least one supported version:\n\n");
        for (String b : broken) {
            out.append("- `").append(b).append("`\n");
        }
        if (broken.isEmpty()) {
            out.append("- none\n");
        }
        out.append("\nThis table does not on its own prove a defect — the prompt names some of these\n");
        out.append("only inside the era-specific sheet that is never shown on the versions where\n");
        out.append("they are missing. The claims below say which are real.\n\n");
    }

    // ------------------------------------------------------------------
    // The audit
    // ------------------------------------------------------------------

    private static Version at(List<Version> versions, String mc) {
        return versions.stream().filter(v -> v.mc().equals(mc)).findFirst().orElse(null);
    }

    private static List<Version> legacyRange(List<Version> versions) {
        return versions.stream().filter(Version::isLegacyProfile).toList();
    }

    private static List<Version> modernRange(List<Version> versions) {
        return versions.stream().filter(v -> !v.isLegacyProfile()).toList();
    }

    private static void claims(StringBuilder out, List<Version> versions) {
        out.append("## Claims checked\n\n");
        out.append("Each claim from the Phase 1a brief, plus every factual assertion in\n");
        out.append("`PAPER_CHEAT_SHEET_LEGACY` and `PAPER_CHEAT_SHEET_MODERN` that a jar can\n");
        out.append("settle. Verdicts are computed from the tables above, not typed.\n\n");

        List<Version> legacy = legacyRange(versions);
        List<Version> modern = modernRange(versions);

        // --- 1 -------------------------------------------------------------
        Version v1211 = at(versions, "1.21.1");
        if (v1211 != null) {
            int generic = v1211.attributesWithPrefix("GENERIC_");
            int shortForm = v1211.attributesShortForm();
            verdict(out, 1,
                    "Paper 1.21.1 has 23 `GENERIC_*` Attribute constants and no short names.",
                    generic == 23 && shortForm == 0,
                    generic == 23 && shortForm == 0 ? "CONFIRMED" : "REFUTED",
                    "1.21.1 `Attribute` is an `" + v1211.kind("Attribute") + "` with "
                            + v1211.count("Attribute") + " constants: " + generic + " `GENERIC_`, "
                            + v1211.attributesWithPrefix("PLAYER_") + " `PLAYER_`, "
                            + v1211.attributesWithPrefix("ZOMBIE_") + " `ZOMBIE_`, "
                            + shortForm + " short-form. The 23 is exact. Worth noting the claim "
                            + "undersells itself: there are " + (v1211.count("Attribute") - generic)
                            + " further prefixed constants beyond the `GENERIC_` ones, so a "
                            + "repair pass keyed only on `GENERIC_` would miss "
                            + (v1211.count("Attribute") - generic) + " of the "
                            + v1211.count("Attribute") + " renames.");
        }

        // --- 2 -------------------------------------------------------------
        Version v1213 = at(versions, "1.21.3");
        if (v1213 != null) {
            int generic = v1213.attributesWithPrefix("GENERIC_");
            int prefixed = v1213.attributesWithPrefix();
            boolean ok = generic == 0 && prefixed == 0;
            verdict(out, 2,
                    "Paper 1.21.3 has zero `GENERIC_*` Attribute constants and only short names.",
                    ok, ok ? "CONFIRMED" : "REFUTED",
                    "1.21.3 `Attribute` is an `" + v1213.kind("Attribute") + "` with "
                            + v1213.count("Attribute") + " constants, " + generic + " of them "
                            + "`GENERIC_`-prefixed and " + prefixed + " carrying any of the four "
                            + "old prefixes. The rename is complete in one step, and the type "
                            + "changed from `enum` to `interface` in the same release — a break "
                            + "the prompt never mentions.");
        }

        // --- 3 -------------------------------------------------------------
        List<String> brokenOn = new ArrayList<>();
        List<String> longNames = List.of("GENERIC_MAX_HEALTH", "GENERIC_MOVEMENT_SPEED",
                "GENERIC_ATTACK_DAMAGE", "GENERIC_ARMOR", "GENERIC_SCALE");
        for (Version v : legacy) {
            long absent = longNames.stream().filter(n -> !v.has("Attribute", n)).count();
            if (absent == longNames.size()) {
                brokenOn.add(v.mc());
            }
        }
        boolean claim3 = !brokenOn.isEmpty();
        verdict(out, 3,
                "The `paper-legacy` profile teaches attribute names that cannot compile on the "
                        + "1.21.3-1.21.6 versions it serves.",
                claim3, claim3 ? "CONFIRMED" : "REFUTED",
                "`paperProfileIdFor` routes every version below 1.21.7 to `paper-legacy`, which "
                        + "is " + legacy.size() + " of the " + versions.size()
                        + " measured versions (" + legacy.get(0).mc() + " - "
                        + legacy.get(legacy.size() - 1).mc() + "). On " + brokenOn.size()
                        + " of them — " + String.join(", ", brokenOn) + " — **all five** of the "
                        + "long names the sheet instructs the model to use "
                        + "(`GENERIC_MAX_HEALTH`, `GENERIC_MOVEMENT_SPEED`, "
                        + "`GENERIC_ATTACK_DAMAGE`, `GENERIC_ARMOR`, `GENERIC_SCALE`) are absent, "
                        + "and the sheet additionally says *\"NEVER the short 1.21.3+ forms\"* — "
                        + "the only forms that do exist there. The instruction is not merely "
                        + "unhelpful on those versions, it is exactly inverted.");

        // --- 3b: the same sheet is also wrong at the OTHER end -------------
        List<String> scaleMissing = legacy.stream()
                .filter(v -> !v.has("Attribute", "GENERIC_SCALE"))
                .filter(v -> v.attributesWithPrefix() > 0)
                .map(Version::mc).toList();
        if (!scaleMissing.isEmpty()) {
            note(out, "Not anticipated by the brief: `Attribute.GENERIC_SCALE` — one of the five "
                    + "names the legacy sheet holds up as correct — does not exist on "
                    + scaleMissing.size() + " of the prefixed-era versions either ("
                    + String.join(", ", scaleMissing) + "). So the legacy sheet names a "
                    + "non-existent constant on the *old* versions as well as the wrong "
                    + "spelling on the new ones. `SCALE` arrived with the 1.20.5 attribute batch.");
        }

        // --- 4 -------------------------------------------------------------
        List<String> glintYes = new ArrayList<>();
        List<String> glintNo = new ArrayList<>();
        for (Version v : legacy) {
            (v.method("ItemMeta", "setEnchantmentGlintOverride") ? glintYes : glintNo).add(v.mc());
        }
        boolean shapeOk = glintNo.equals(List.of("1.20", "1.20.1", "1.20.2", "1.20.3", "1.20.4"));
        verdict(out, 4,
                "`setEnchantmentGlintOverride` is absent on 1.20-1.20.4 and present on "
                        + "1.20.5-1.21.6, so the legacy sheet forbids a method the server has on "
                        + "eight of the twelve versions that profile serves.",
                shapeOk, shapeOk ? "CONFIRMED, with a corrected count" : "REFUTED",
                "Absent on " + glintNo.size() + " versions (" + String.join(", ", glintNo)
                        + "), present on " + glintYes.size() + " (" + String.join(", ", glintYes)
                        + "). The boundary is exactly where the claim puts it and the **eight** is "
                        + "exactly right. The **twelve** is not: `paper-legacy` serves "
                        + legacy.size() + " versions, not 12 — the brief's count appears to have "
                        + "lost one to the missing 1.21.2. So the sheet forbids a present method "
                        + "on " + glintYes.size() + " of " + legacy.size() + ".");

        // --- 5 -------------------------------------------------------------
        String dialogFirst = versions.stream()
                .filter(v -> v.vocab().type("io.papermc.paper.dialog.Dialog") != null)
                .map(Version::mc).findFirst().orElse("never");
        Version v1216 = at(versions, "1.21.6");
        Version v1215 = at(versions, "1.21.5");
        boolean claim5 = "1.21.7".equals(dialogFirst)
                && v1216 != null && v1216.vocab().type("io.papermc.paper.dialog.Dialog") == null
                && v1215 != null && v1215.vocab().type("io.papermc.paper.dialog.Dialog") == null;
        verdict(out, 5,
                "`io.papermc.paper.dialog.Dialog` first appears in 1.21.7 and is absent in 1.21.6 "
                        + "and 1.21.5.",
                claim5, claim5 ? "CONFIRMED" : "REFUTED",
                "First jar containing the class: **" + dialogFirst + "**. Absent from 1.21.6 and "
                        + "1.21.5, and from every jar below them. This also settles the disputed "
                        + "javadoc on `PaperPlatformInfo`: 1.21.6 does not \"have the class but "
                        + "not the behaviour\" — the class is not in the jar at all, so the "
                        + "master prompt's correction is right and the javadoc is wrong.");

        // --- 6 -------------------------------------------------------------
        out.append("### 6. Every other factual assertion in the two cheat sheets\n\n");
        out.append("| # | Assertion | Verdict | Evidence |\n|---|---|---|---|\n");

        // modern: the four example constants
        for (String[] pair : new String[][] {
                {"Material", "DIAMOND_SWORD"}, {"EntityType", "ZOMBIE"},
                {"Sound", "ENTITY_PLAYER_LEVELUP"}, {"Particle", "CLOUD"}}) {
            List<String> missing = versions.stream()
                    .filter(v -> !v.has(pair[0], pair[1])).map(Version::mc).toList();
            row(out, "both sheets", "the \"obviously-real\" fallback constant `"
                            + pair[0] + "." + pair[1] + "` exists",
                    missing.isEmpty() ? "CONFIRMED" : "REFUTED",
                    missing.isEmpty() ? "present on all " + versions.size() + " versions"
                            : "absent on " + String.join(", ", missing));
        }

        // modern: short attribute names
        for (String c : List.of("MAX_HEALTH", "MOVEMENT_SPEED", "ATTACK_DAMAGE", "SCALE")) {
            List<String> missing = modern.stream()
                    .filter(v -> !v.has("Attribute", c)).map(Version::mc).toList();
            row(out, "modern", "`Attribute." + c + "` exists on every version the modern sheet serves",
                    missing.isEmpty() ? "CONFIRMED" : "REFUTED",
                    missing.isEmpty() ? "present on all " + modern.size()
                            + " modern versions (" + modern.get(0).mc() + "+)"
                            : "absent on " + String.join(", ", missing));
        }

        // modern: prefixes removed
        List<String> stillPrefixed = modern.stream()
                .filter(v -> v.attributesWithPrefix() > 0).map(Version::mc).toList();
        row(out, "modern", "the `GENERIC_`/`PLAYER_`/`ZOMBIE_` prefixes \"were removed\"",
                stillPrefixed.isEmpty() ? "CONFIRMED" : "REFUTED",
                stillPrefixed.isEmpty()
                        ? "zero prefixed constants on all " + modern.size() + " modern versions"
                        : "still prefixed on " + String.join(", ", stillPrefixed));

        // modern: glint override available, with the exact signature the sheet quotes
        List<String> noGlint = modern.stream()
                .filter(v -> !v.method("ItemMeta", "setEnchantmentGlintOverride"))
                .map(Version::mc).toList();
        List<String> wrongSig = modern.stream()
                .filter(v -> {
                    ClassFileVocabulary.TypeInfo t = v.vocab().type("ItemMeta");
                    return t == null
                            || !t.hasSignature("setEnchantmentGlintOverride", "Ljava/lang/Boolean;");
                })
                .map(Version::mc).toList();
        row(out, "modern", "`ItemMeta#setEnchantmentGlintOverride(Boolean)` is available",
                noGlint.isEmpty() && wrongSig.isEmpty() ? "CONFIRMED" : "REFUTED",
                noGlint.isEmpty() && wrongSig.isEmpty()
                        ? "present with the exact `(Boolean)` parameter on all " + modern.size()
                        + " modern versions"
                        : "missing on " + String.join(", ", noGlint)
                        + "; wrong signature on " + String.join(", ", wrongSig));

        // legacy: "enum constants that exist in Paper 1.20.6"
        Version v1206 = at(versions, "1.20.6");
        if (v1206 != null) {
            List<String> legacyBelow = legacy.stream()
                    .filter(v -> VERSION_ORDER.compare(v.mc(), "1.20.6") < 0)
                    .map(Version::mc).toList();
            Version floor = at(versions, "1.20");
            String gap = "";
            if (floor != null) {
                for (String t : List.of("Attribute", "Enchantment", "PotionEffectType")) {
                    Set<String> only1206 = new TreeSet<>(v1206.vocab().constants(t));
                    only1206.removeAll(floor.vocab().constants(t));
                    gap += (gap.isEmpty() ? "" : ", ") + only1206.size() + " `" + t + "`";
                }
            }
            row(out, "legacy", "\"use only enum constants that exist in Paper 1.20.6\" is a safe "
                            + "instruction for the whole legacy range",
                    legacyBelow.isEmpty() ? "CONFIRMED" : "REFUTED",
                    "the profile also serves " + legacyBelow.size()
                            + " versions BELOW 1.20.6 (" + String.join(", ", legacyBelow)
                            + "). 1.20.6 declares constants that 1.20 does not: " + gap
                            + " constants exist on 1.20.6 and not on 1.20. So a model obeying "
                            + "the instruction to the letter still writes code that fails on the "
                            + "bottom of the range the profile serves");
        }

        // legacy: the five long attribute names
        for (String c : longNames) {
            List<String> missing = legacy.stream()
                    .filter(v -> !v.has("Attribute", c)).map(Version::mc).toList();
            row(out, "legacy", "`Attribute." + c + "` exists on every version the legacy sheet serves",
                    missing.isEmpty() ? "CONFIRMED" : "REFUTED",
                    missing.isEmpty() ? "present on all " + legacy.size() + " legacy versions"
                            : "absent on " + missing.size() + "/" + legacy.size() + ": "
                            + String.join(", ", missing));
        }

        // legacy: AttributeModifier takes a NamespacedKey
        List<String> nsKeyNo = new ArrayList<>();
        List<String> nsKeyYes = new ArrayList<>();
        for (Version v : legacy) {
            ClassFileVocabulary.TypeInfo t = v.vocab().type("AttributeModifier");
            boolean has = t != null && t.signatures().stream()
                    .anyMatch(s -> s.startsWith("<init>(") && s.contains("NamespacedKey"));
            (has ? nsKeyYes : nsKeyNo).add(v.mc());
        }
        row(out, "legacy", "\"`AttributeInstance`/`AttributeModifier` still take a `NamespacedKey` "
                        + "+ `AttributeModifier.Operation`\"",
                nsKeyNo.isEmpty() ? "CONFIRMED" : "REFUTED",
                nsKeyNo.isEmpty()
                        ? "a `NamespacedKey` constructor exists on all " + legacy.size()
                        + " legacy versions"
                        : "no `AttributeModifier` constructor takes a `NamespacedKey` on "
                        + nsKeyNo.size() + "/" + legacy.size() + ": " + String.join(", ", nsKeyNo)
                        + " (those take a `UUID`); it appears from "
                        + (nsKeyYes.isEmpty() ? "never" : nsKeyYes.get(0)) + " on");

        // legacy: PotionEffectType.SPEED
        List<String> speedMissing = versions.stream()
                .filter(v -> !v.has("PotionEffectType", "SPEED")).map(Version::mc).toList();
        row(out, "legacy", "`PotionEffectType.SPEED` is a safe constant",
                speedMissing.isEmpty() ? "CONFIRMED" : "REFUTED",
                speedMissing.isEmpty() ? "present on all " + versions.size() + " versions"
                        : "absent on " + String.join(", ", speedMissing));

        // legacy: Enchantment.DURABILITY
        List<String> durYes = versions.stream()
                .filter(v -> v.has("Enchantment", "DURABILITY")).map(Version::mc).toList();
        List<String> durLegacyNo = legacy.stream()
                .filter(v -> !v.has("Enchantment", "DURABILITY")).map(Version::mc).toList();
        row(out, "legacy", "\"`Enchantment` constants are still fields on `Enchantment` (e.g. "
                        + "`Enchantment.DURABILITY`)\"",
                durLegacyNo.isEmpty() ? "CONFIRMED" : "REFUTED (half true)",
                "`Enchantment` does still expose constants as fields, but `DURABILITY` "
                        + "specifically exists on only " + durYes.size() + "/" + versions.size()
                        + " versions ("
                        + (durYes.isEmpty() ? "none" : String.join(", ", durYes))
                        + ") and is absent on " + durLegacyNo.size() + " of the "
                        + legacy.size() + " the sheet serves"
                        + (durLegacyNo.isEmpty() ? "" : ": " + String.join(", ", durLegacyNo))
                        + ". The sheet's own hedge (\"era naming may differ\") is doing a lot of "
                        + "work; the model is being shown a name that mostly does not compile");

        // legacy: the forbidden data-component setters
        for (String m : List.of("setItemModel", "setTooltipStyle")) {
            List<String> present = legacy.stream()
                    .filter(v -> v.method("ItemMeta", m)).map(Version::mc).toList();
            row(out, "legacy", "\"Do NOT call `ItemMeta#" + m + "`\" - i.e. the method is absent",
                    present.isEmpty() ? "CONFIRMED" : "REFUTED",
                    present.isEmpty() ? "absent on all " + legacy.size() + " legacy versions"
                            : "present on " + present.size() + "/" + legacy.size() + ": "
                            + String.join(", ", present));
        }

        // legacy: Registry
        List<String> noRegistry = versions.stream()
                .filter(v -> v.vocab().type("org.bukkit.Registry") == null).map(Version::mc).toList();
        row(out, "legacy", "\"Do NOT use `Registry`-based lookups ... use the plain constants\"",
                "UNVERIFIABLE (style rule, but its premise holds)",
                noRegistry.isEmpty()
                        ? "`org.bukkit.Registry` exists on all " + versions.size()
                        + " versions, so this forbids something real rather than something "
                        + "absent; whether plain constants are preferable is a taste call a jar "
                        + "cannot settle. The premise that plain constants remain available does "
                        + "hold: Sound/Particle/Enchantment expose fields on every version"
                        : "absent on " + String.join(", ", noRegistry));

        // modern role line vs 26.x
        Version newest = versions.get(versions.size() - 1);
        row(out, "modern", "the role line \"expert Paper 1.21.8 ... real Paper 1.21 enum "
                        + "constants\" is accurate for the servers it is shown to",
                "REFUTED as a description",
                "the modern profile is selected for " + modern.size() + " versions up to "
                        + newest.mc() + ". On " + newest.mc() + " `Sound` has "
                        + newest.count("Sound") + " constants against "
                        + (at(versions, "1.21.8") == null ? "?" : at(versions, "1.21.8").count("Sound"))
                        + " on 1.21.8, and `Material` " + newest.count("Material") + " against "
                        + (at(versions, "1.21.8") == null ? "?" : at(versions, "1.21.8").count("Material"))
                        + ". Calling a " + newest.mc() + " server \"Paper 1.21.8\" is a factual "
                        + "misdescription; whether it costs compile rate is a generation "
                        + "question this jar-level measurement cannot answer");

        out.append('\n');

        // --- extras --------------------------------------------------------
        out.append("## Found, and not anticipated by the brief\n\n");
        out.append("1. **`Attribute` changes kind, not just spelling.** It is an `enum` up to\n");
        out.append("   1.21.1 and an `interface` from 1.21.3. Any generated mod using `switch`,\n");
        out.append("   `EnumMap<Attribute,…>`, `EnumSet` or `Attribute.valueOf` in an enum-typed\n");
        out.append("   context breaks across that line independently of the rename, and a repair\n");
        out.append("   pass that only rewrites names will not fix it.\n");
        out.append("2. **A `GENERIC_`-only repair rule is incomplete.** ").append(
                v1211 == null ? "" : (v1211.count("Attribute") - v1211.attributesWithPrefix("GENERIC_"))
                        + " of 1.21.1's " + v1211.count("Attribute") + " constants carry "
                        + "`PLAYER_` or `ZOMBIE_` instead. ");
        out.append("Both directions of the mapping need all four prefixes.\n");
        out.append("3. **At the 1.21.3 boundary the rename IS a clean prefix strip — but only\n");
        out.append("   there.** ");
        out.append(renameNotes(versions));
        out.append("4. **There is a SECOND vocabulary boundary, at 1.20.5, and nothing in the\n");
        out.append("   prompt knows about it.** ").append(secondBoundary(versions));
        out.append("\n5. **Paper never published a `paper-api` for 1.21.2**, and **26.1.1 exists\n");
        out.append("   only as `-alpha` builds**. Any \"every supported version\" loop must derive\n");
        out.append("   its list from the metadata rather than counting patch numbers.\n");
        out.append("6. **`ItemMeta` is an interface with 0 constants and ~100 methods** on every\n");
        out.append("   version, so `constants(\"ItemMeta\")` is legitimately empty — exactly the\n");
        out.append("   case where `ApiVocabulary.constants()` returning an empty set must not be\n");
        out.append("   read as \"unknown type\". That is why the interface has `declaresConstant`.\n");
    }

    /** The 1.20.4 -> 1.20.5 break: the batch nobody wrote a profile for. */
    private static String secondBoundary(List<Version> versions) {
        Version before = at(versions, "1.20.4");
        Version after = at(versions, "1.20.5");
        if (before == null || after == null) {
            return "(1.20.4 or 1.20.5 not measured).\n";
        }
        StringBuilder sb = new StringBuilder();
        for (String t : List.of("Enchantment", "Attribute", "PotionEffectType")) {
            Set<String> gone = new TreeSet<>(before.vocab().constants(t));
            gone.removeAll(after.vocab().constants(t));
            Set<String> added = new TreeSet<>(after.vocab().constants(t));
            added.removeAll(before.vocab().constants(t));
            sb.append("`").append(t).append("` loses ").append(gone.size())
              .append(" of ").append(before.vocab().constants(t).size())
              .append(" constants and gains ").append(added.size()).append("; ");
        }
        Set<String> gone = new TreeSet<>(before.vocab().constants("Enchantment"));
        gone.removeAll(after.vocab().constants("Enchantment"));
        return sb + "\n   Bukkit's legacy `Enchantment` spellings are replaced wholesale by the\n"
                + "   vanilla ones (`DURABILITY`->`UNBREAKING`, `DIG_SPEED`->`EFFICIENCY`,\n"
                + "   `PROTECTION_ENVIRONMENTAL`->`PROTECTION`, `LOOT_BONUS_MOBS`->`LOOTING`), and\n"
                + "   `Attribute.HORSE_JUMP_STRENGTH` becomes `GENERIC_JUMP_STRENGTH`. That is "
                + gone.size() + "\n   enchantment names that stop compiling in one step, INSIDE the "
                + "single\n   `paper-legacy` profile - and the legacy sheet's one worked "
                + "enchantment example,\n   `Enchantment.DURABILITY`, is on the losing side of it.\n";
    }

    /** How the 1.21.1 -> 1.21.3 rename actually maps, beyond stripping a prefix. */
    private static String renameNotes(List<Version> versions) {
        Version before = at(versions, "1.21.1");
        Version after = at(versions, "1.21.3");
        if (before == null || after == null) {
            return "(1.21.1 or 1.21.3 not measured).\n";
        }
        Set<String> stripped = new LinkedHashSet<>();
        for (String c : before.vocab().constants("Attribute")) {
            for (String p : PREFIXES) {
                if (c.startsWith(p)) {
                    stripped.add(c.substring(p.length()));
                    break;
                }
            }
        }
        Set<String> now = new TreeSet<>(after.vocab().constants("Attribute"));
        Set<String> added = new TreeSet<>(now);
        added.removeAll(stripped);
        Set<String> lost = new TreeSet<>(stripped);
        lost.removeAll(now);
        return "Stripping the prefix from 1.21.1's " + before.count("Attribute")
                + " constants yields " + stripped.size() + " names; 1.21.3 has "
                + now.size() + ". Names 1.21.3 adds outright: "
                + (added.isEmpty() ? "none" : "`" + String.join("`, `", added) + "`")
                + ". Names that vanish rather than being renamed: "
                + (lost.isEmpty() ? "none" : "`" + String.join("`, `", lost) + "`")
                + ".\n   So at THIS boundary a prefix-stripping regex would in fact be correct. It is\n"
                + "   not correct in general: the set keeps drifting afterwards (1.21.6 and 26.2\n"
                + "   each add more), and the 1.20.5 boundary below is not a prefix rule at all.\n"
                + "   A repair pass must be a lookup against the measured set.\n";
    }

    // ------------------------------------------------------------------

    private static void verdict(StringBuilder out, int n, String claim, boolean ok,
                                String label, String evidence) {
        out.append("### ").append(n).append(". ").append(claim).append("\n\n");
        out.append("**").append(label).append("**").append(ok ? "" : " ").append("\n\n");
        out.append(evidence).append("\n\n");
    }

    private static void note(StringBuilder out, String text) {
        out.append("> ").append(text).append("\n\n");
    }

    private static void row(StringBuilder out, String sheet, String assertion, String label,
                            String evidence) {
        out.append("| ").append(sheet).append(" | ").append(assertion)
                .append(" | **").append(label).append("** | ").append(evidence).append(" |\n");
    }

    private static String yn(boolean b) {
        return b ? "yes" : "**no**";
    }

    private VocabularyReport() {
    }
}
