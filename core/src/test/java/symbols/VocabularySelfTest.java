package symbols;

import java.nio.file.Files;
import java.nio.file.Path;

import com.gijsm.vibemod.platform.ApiVocabulary;
import com.gijsm.vibemod.platform.ApiVocabulary.Known;

/**
 * Self-test for {@link ApiVocabulary}'s unknown-vs-absent contract and for the
 * class-file parser behind {@link ClassFileVocabulary}.
 *
 * <p>The contract half runs everywhere. The measurement half needs the jar cache
 * ({@code scripts/fetch-api-jars.sh}) and SKIPS without it rather than failing, so
 * a fresh clone still goes green — same convention as {@code StoreSelfTest}'s
 * stored-corpus check.
 *
 * <p>This is the seed of the B3 gate the master prompt asks for: the assertions
 * below are exactly the facts {@code PlatformProfiles} asserts in prose, so if
 * Paper moves one of them, this goes red before a user pays for a self-heal round.
 */
public final class VocabularySelfTest {

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        emptyIsAlwaysUnknown();
        closedWorldSaysNo();

        String dir = System.getProperty("vibemod.apiJars", "");
        if (dir.isEmpty() || !Files.isDirectory(Path.of(dir))) {
            System.out.println("SKIPPED: jar-backed checks (no api-jar cache at '" + dir
                    + "'; run scripts/fetch-api-jars.sh)");
        } else {
            measuredFacts(Path.of(dir));
        }

        System.out.println();
        if (failures > 0) {
            System.out.println(failures + " of " + checks + " CHECKS FAILED");
            System.exit(1);
        }
        System.out.println("ALL CHECKS PASSED (" + checks + ")");
    }

    // ------------------------------------------------------------------

    /**
     * The rule that protects generated code: a vocabulary that measured nothing
     * must never answer NO. A repair pass reading NO here would rewrite correct
     * code on any platform we have no index for.
     */
    private static void emptyIsAlwaysUnknown() {
        ApiVocabulary empty = ApiVocabulary.empty();
        check("empty(): knows() is UNKNOWN, never NO",
                empty.knows("Attribute") == Known.UNKNOWN);
        check("empty(): declaresConstant() is UNKNOWN",
                empty.declaresConstant("Attribute", "GENERIC_MAX_HEALTH") == Known.UNKNOWN);
        check("empty(): declaresMethod() is UNKNOWN",
                empty.declaresMethod("ItemMeta", "setItemModel") == Known.UNKNOWN);
        check("empty(): the boolean conveniences collapse UNKNOWN to false",
                !empty.hasType("Attribute") && !empty.hasMethod("ItemMeta", "setItemModel"));
        check("empty(): knownTypes() is empty", empty.knownTypes().isEmpty());
        check("empty(): constants() is empty", empty.constants("Attribute").isEmpty());
    }

    /** A vocabulary that DID enumerate a surface may answer NO — that is the point. */
    private static void closedWorldSaysNo() {
        ApiVocabulary one = new ApiVocabulary() {
            @Override
            public java.util.Set<String> knownTypes() {
                return java.util.Set.of("Attribute");
            }

            @Override
            public java.util.Set<String> constants(String t) {
                return "Attribute".equals(t) ? java.util.Set.of("MAX_HEALTH")
                        : java.util.Set.of();
            }

            @Override
            public java.util.Set<String> methods(String t) {
                return java.util.Set.of();
            }
        };
        check("closed world: a known present constant is YES",
                one.declaresConstant("Attribute", "MAX_HEALTH") == Known.YES);
        check("closed world: a known absent constant is NO",
                one.declaresConstant("Attribute", "GENERIC_MAX_HEALTH") == Known.NO);
        check("closed world: an unlisted type is NO, not UNKNOWN",
                one.knows("Dialog") == Known.NO);
    }

    // ------------------------------------------------------------------

    /**
     * The measured facts docs/API-VOCABULARY.md rests on. Each one is a sentence
     * the prompt currently asserts (or should).
     */
    private static void measuredFacts(Path dir) {
        ClassFileVocabulary v1211 = index(dir, "1.21.1");
        ClassFileVocabulary v1213 = index(dir, "1.21.3");
        ClassFileVocabulary v1216 = index(dir, "1.21.6");
        ClassFileVocabulary v1217 = index(dir, "1.21.7");
        ClassFileVocabulary v1204 = index(dir, "1.20.4");
        ClassFileVocabulary v1205 = index(dir, "1.20.5");
        if (v1211 == null || v1213 == null || v1216 == null || v1217 == null
                || v1204 == null || v1205 == null) {
            System.out.println("SKIPPED: jar-backed checks (cache is incomplete)");
            return;
        }

        // The attribute rename, both directions.
        long generic = v1211.constants("Attribute").stream()
                .filter(c -> c.startsWith("GENERIC_")).count();
        check("1.21.1 has 23 GENERIC_ attribute constants (measured " + generic + ")",
                generic == 23);
        check("1.21.1 Attribute is an enum", v1211.type("Attribute").isEnum());
        check("1.21.3 has zero prefixed attribute constants",
                v1213.constants("Attribute").stream().noneMatch(
                        c -> c.startsWith("GENERIC_") || c.startsWith("PLAYER_")
                                || c.startsWith("ZOMBIE_") || c.startsWith("HORSE_")));
        check("1.21.3 Attribute is an interface, not an enum",
                v1213.type("Attribute").isInterface() && !v1213.type("Attribute").isEnum());
        check("1.21.3 REFUTES the legacy sheet: GENERIC_MAX_HEALTH is absent",
                v1213.declaresConstant("Attribute", "GENERIC_MAX_HEALTH") == Known.NO);
        check("1.21.1 REFUTES the modern sheet: MAX_HEALTH is absent",
                v1211.declaresConstant("Attribute", "MAX_HEALTH") == Known.NO);

        // The dialog boundary.
        check("Dialog is absent on 1.21.6",
                v1216.knows("io.papermc.paper.dialog.Dialog") == Known.NO);
        check("Dialog is present on 1.21.7",
                v1217.knows("io.papermc.paper.dialog.Dialog") == Known.YES);

        // The glint-override boundary.
        check("setEnchantmentGlintOverride is absent on 1.20.4",
                v1204.declaresMethod("ItemMeta", "setEnchantmentGlintOverride") == Known.NO);
        check("setEnchantmentGlintOverride is present on 1.20.5",
                v1205.declaresMethod("ItemMeta", "setEnchantmentGlintOverride") == Known.YES);

        // The second boundary the prompt says nothing about.
        check("1.20.5 drops Enchantment.DURABILITY for UNBREAKING",
                v1204.declaresConstant("Enchantment", "DURABILITY") == Known.YES
                        && v1205.declaresConstant("Enchantment", "DURABILITY") == Known.NO
                        && v1205.declaresConstant("Enchantment", "UNBREAKING") == Known.YES);

        // The ambiguity the interface exists to prevent.
        check("ItemMeta is KNOWN yet has zero constants - empty is not absent",
                v1213.knows("ItemMeta") == Known.YES && v1213.constants("ItemMeta").isEmpty()
                        && v1213.declaresMethod("ItemMeta", "setItemModel") == Known.YES);

        // The parser itself.
        check("the index found a plausible number of classes",
                v1213.binaryNames().size() > 1500);
        check("no private/synthetic field leaked in as a constant",
                !v1211.constants("Attribute").contains("$VALUES"));
    }

    private static ClassFileVocabulary index(Path dir, String mc) {
        Path jar = dir.resolve("paper-api-" + mc + ".jar");
        if (!Files.isRegularFile(jar)) {
            return null;
        }
        try {
            return ClassFileVocabulary.ofJar(jar);
        } catch (Exception e) {
            System.out.println("  FAILED to index " + jar + ": " + e);
            failures++;
            return null;
        }
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (ok) {
            System.out.println("  ok: " + what);
        } else {
            System.out.println("  FAIL: " + what);
            failures++;
        }
    }

    private VocabularySelfTest() {
    }
}
