package symbols;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.gijsm.vibemod.llm.PlatformProfile;
import com.gijsm.vibemod.llm.PlatformProfiles;
import com.gijsm.vibemod.llm.PromptFacts;
import com.gijsm.vibemod.llm.PromptLibrary;
import com.gijsm.vibemod.llm.PromptRules;
import com.gijsm.vibemod.platform.ApiVocabulary;
import com.gijsm.vibemod.platform.PlatformInfo;

/**
 * Builds the real system prompt for each cached {@code paper-api} version and
 * prints what the server-derived section actually says.
 *
 * <p>This is the proof that the rework works, as opposed to the claim that it
 * does. The old prompt could not be checked this way at all: it had exactly two
 * outputs, chosen by a version comparison, so "what does it say on 1.21.4"
 * had the same answer as "what does it say on 1.20" by construction — and that
 * answer was wrong on both. Here every version gets its own measured
 * vocabulary, and the guidance is whatever the jar says it should be.
 *
 * <p>Offline: reads jars as bytes through {@link ClassFileVocabulary} and loads
 * no Bukkit class, so it runs anywhere the jar cache exists. It is not wired
 * into {@code check} for the same reason the other jar-backed tools are not —
 * without {@code scripts/fetch-api-jars.sh} there is nothing to measure.
 *
 * <pre>
 *   ./gradlew :core:promptProof
 *   ./gradlew :core:promptProof -Pversions="1.20 1.21.3 26.2"
 * </pre>
 */
public final class PromptVocabularyProof {

    private PromptVocabularyProof() {
    }

    /** The versions the brief asks to see, spanning every measured boundary. */
    private static final List<String> DEFAULT_VERSIONS =
            List.of("1.20", "1.20.4", "1.20.5", "1.21.1", "1.21.3", "1.21.6", "1.21.8", "26.2");

    /** The lines worth showing: the ones the old prompt got wrong. */
    private static final List<String> INTERESTING = List.of(
            "AttributeModifier", "setEnchantmentGlintOverride", "setItemModel",
            "switch` over an `Attribute", "main server thread");

    public static void main(String[] args) throws Exception {
        Path jars = Path.of(args.length > 0 ? args[0] : "paper/api-jars");
        List<String> versions = args.length > 1 && !args[1].isBlank()
                ? List.of(args[1].trim().split("\\s+"))
                : DEFAULT_VERSIONS;

        if (!Files.isDirectory(jars)) {
            System.out.println("SKIPPED: no jar cache at " + jars.toAbsolutePath()
                    + " (run scripts/fetch-api-jars.sh)");
            return;
        }

        System.out.println("=== System prompt, built per version against the real paper-api jar ===");
        System.out.println();
        System.out.printf("%-9s %-14s %7s %7s  %s%n",
                "VERSION", "PROFILE", "CHARS", "~TOKENS", "RULES THAT FIRED");
        List<String[]> details = new ArrayList<>();

        for (String version : versions) {
            Path jar = jars.resolve("paper-api-" + version + ".jar");
            if (!Files.isRegularFile(jar)) {
                System.out.printf("%-9s %-14s %7s %7s  (no jar)%n", version, "-", "-", "-");
                continue;
            }
            ApiVocabulary vocabulary = ClassFileVocabulary.ofJar(jar);
            String profileId = PlatformProfiles.paperProfileIdFor(version);
            PlatformProfile profile = PlatformProfiles.byId(profileId);
            PromptFacts facts = new PromptFacts(profile, stubInfo(version, profileId, vocabulary), vocabulary);

            String prompt = PromptLibrary.systemPrompt(facts);
            List<String> ids = PromptRules.applicableIds(profile.rules(), facts);
            System.out.printf("%-9s %-14s %7d %7d  %s%n",
                    version, profileId, prompt.length(), prompt.length() / 4,
                    String.join(" ", shortIds(ids)));

            details.add(new String[]{version, differingLines(prompt)});
        }

        System.out.println();
        System.out.println("=== The lines that differ, per version ===");
        for (String[] d : details) {
            System.out.println();
            System.out.println("--- Paper " + d[0] + " ---");
            System.out.print(d[1]);
        }

        crossCheckReflection(jars);
    }

    /**
     * Does the runtime engine agree with the offline ground truth?
     *
     * <p>{@code ReflectiveVocabulary} is what a live server will use, and it can
     * only be exercised for real on a booted server. It can, however, be pointed
     * at a {@code paper-api} jar through a {@link java.net.URLClassLoader} right
     * here, and its answers compared against the same jar read as bytes by
     * {@link ClassFileVocabulary}. If the two disagree about which constants a
     * type declares, one of them is wrong and it matters which.
     *
     * <p>Expect method-surface types ({@code ItemMeta}) to come back UNKNOWN in
     * this harness: enumerating their methods resolves parameter types that live
     * in Adventure and Brigadier, which are not on this classpath. That is the
     * designed fail-safe rather than a defect — {@code UNKNOWN} never suppresses
     * a rule and never triggers a repair — and on a real server those
     * dependencies are present, so it does not arise there.
     */
    private static void crossCheckReflection(Path jars) throws Exception {
        Path jar = jars.resolve("paper-api-1.21.8.jar");
        if (!Files.isRegularFile(jar)) {
            return;
        }
        java.util.Map<String, String> types = new java.util.LinkedHashMap<>();
        types.put("Attribute", "org.bukkit.attribute.Attribute");
        types.put("Enchantment", "org.bukkit.enchantments.Enchantment");
        types.put("PotionEffectType", "org.bukkit.potion.PotionEffectType");
        types.put("Material", "org.bukkit.Material");
        types.put("ItemMeta", "org.bukkit.inventory.meta.ItemMeta");
        types.put("Dialog", "io.papermc.paper.dialog.Dialog");
        types.put("NotAThing", "org.bukkit.NotAThing");

        System.out.println();
        System.out.println("=== ReflectiveVocabulary (runtime engine) vs ClassFileVocabulary (bytes), 1.21.8 ===");
        ClassFileVocabulary bytes = ClassFileVocabulary.ofJar(jar);
        // Parent = this harness's loader, so Adventure (on the test classpath)
        // is visible. Bukkit types are not on it, so they still come from the
        // jar. With a null parent nothing links at all: org.bukkit.Attribute
        // implements net.kyori.adventure.translation.Translatable, and loading a
        // class loads its superinterfaces.
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[]{jar.toUri().toURL()},
                PromptVocabularyProof.class.getClassLoader())) {
            ApiVocabulary reflected = com.gijsm.vibemod.llm.ReflectiveVocabulary.of(loader, types, "proof");
            System.out.printf("%-18s %-10s %-10s %-9s %s%n",
                    "TYPE", "REFLECTED", "BYTES", "KNOWS", "VERDICT");
            for (String type : types.keySet()) {
                java.util.Set<String> r = reflected.constants(type);
                java.util.Set<String> b = bytes.constants(type);
                ApiVocabulary.Known k = reflected.knows(type);
                String verdict = k == ApiVocabulary.Known.UNKNOWN
                        ? "unknown (safe: never treated as absent)"
                        : r.equals(b) ? "agree" : "DISAGREE " + disagreement(r, b);
                System.out.printf("%-18s %-10d %-10d %-9s %s%n", type, r.size(), b.size(), k, verdict);
            }

            // The method probes the rule table actually keys off.
            System.out.println();
            System.out.printf("%-46s %-10s %-10s %s%n", "METHOD PROBE", "REFLECTED", "BYTES", "VERDICT");
            for (String[] probe : new String[][]{
                    {"ItemMeta", "setEnchantmentGlintOverride"},
                    {"ItemMeta", "setItemModel"},
                    {"ItemMeta", "setTooltipStyle"},
                    {"ItemMeta", "addEnchant"},
                    {"ItemMeta", "noSuchMethodAnywhere"}}) {
                ApiVocabulary.Known r = reflected.declaresMethod(probe[0], probe[1]);
                ApiVocabulary.Known b = bytes.declaresMethod(probe[0], probe[1]);
                System.out.printf("%-46s %-10s %-10s %s%n", probe[0] + "#" + probe[1], r, b,
                        r == b ? "agree" : r == ApiVocabulary.Known.UNKNOWN ? "unknown (safe)" : "DISAGREE");
            }
        }
    }

    private static String disagreement(java.util.Set<String> reflected, java.util.Set<String> bytes) {
        java.util.Set<String> onlyReflected = new java.util.TreeSet<>(reflected);
        onlyReflected.removeAll(bytes);
        java.util.Set<String> onlyBytes = new java.util.TreeSet<>(bytes);
        onlyBytes.removeAll(reflected);
        return "+" + onlyReflected + " -" + onlyBytes;
    }

    /** Rule ids minus the {@code paper.} prefix, so the table fits a terminal. */
    private static List<String> shortIds(List<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            out.add(id.startsWith("paper.") ? id.substring(6) : id);
        }
        return out;
    }

    /**
     * The server-derived lines a reader needs to check the fix: the version
     * statement, the guidance that used to be wrong, and the first line of each
     * injected constant list.
     */
    private static String differingLines(String prompt) {
        StringBuilder sb = new StringBuilder();
        String[] lines = prompt.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("This server reports Minecraft")) {
                sb.append("  ").append(line).append('\n');
                continue;
            }
            if (line.startsWith("Every `")) {
                // The list itself is long; show its head so the spelling era is visible.
                String body = i + 1 < lines.length ? lines[i + 1] : "";
                sb.append("  ").append(line).append('\n');
                sb.append("      ").append(body.length() > 88 ? body.substring(0, 88) + " ..." : body)
                        .append('\n');
                continue;
            }
            for (String needle : INTERESTING) {
                if (line.contains(needle)) {
                    sb.append("  ").append(line.trim()).append('\n');
                    break;
                }
            }
        }
        return sb.toString();
    }

    /** Just enough {@link PlatformInfo} for the prompt: the version and the vocabulary. */
    private static PlatformInfo stubInfo(String version, String profileId, ApiVocabulary vocabulary) {
        return new PlatformInfo() {
            @Override
            public String platformName() {
                return "paper";
            }

            @Override
            public String mcVersion() {
                return version;
            }

            @Override
            public boolean hasDialogs() {
                return vocabulary.hasType("Dialog");
            }

            @Override
            public boolean hasSystemCompiler() {
                return true;
            }

            @Override
            public boolean hasClient() {
                return false;
            }

            @Override
            public boolean hasNativeCommandMap() {
                return true;
            }

            @Override
            public boolean isDedicatedServer() {
                return true;
            }

            @Override
            public ApiVocabulary vocabulary() {
                return vocabulary;
            }

            @Override
            public String profileId() {
                return profileId;
            }
        };
    }
}
