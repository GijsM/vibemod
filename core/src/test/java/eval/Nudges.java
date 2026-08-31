package eval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import symbols.ClassFileVocabulary;

/**
 * Candidate prompt rules, appended to the shipping {@code after} prompt so each
 * can be measured before anyone commits it.
 *
 * <p>These exist because the free-model run produced a failure taxonomy, not a
 * single number: the generations that failed did so for a small number of very
 * legible reasons, and each reason suggests one sentence that might kill it.
 * Guessing which sentence is worth its tokens is exactly the drift the eval was
 * built to prevent ("prompt work that cannot be measured tends to drift toward
 * longer prompts rather than better ones"), so every rule here is a condition in
 * the harness — {@code after+imports}, {@code after+particle}, and so on — scored
 * on the same paired tasks as {@code before} and {@code after}.
 *
 * <p><b>This is eval-side text.</b> Nothing here edits {@code PromptRules} or
 * {@code PromptFacts}. A rule earns its way into the product by moving a number
 * here first; until then it is a hypothesis with a name.
 *
 * <h2>Why these four</h2>
 *
 * <ul>
 *   <li>{@code imports} — the largest observed class. Weak models write {@code UUID},
 *       {@code World}, {@code BlockFace} and never emit the import line, and it is
 *       worst on multi-file mods where every file needs its own. Nothing in the
 *       prompt currently says a file must stand alone.
 *   <li>{@code nms} — models reach for {@code BlockPos} and {@code BlockBox}, which
 *       are Minecraft/NMS/Fabric names with no Bukkit existence. A ban the model
 *       does not apply is not a ban; this states the redirect positively, naming
 *       the Bukkit type to use instead.
 *   <li>{@code particle} — {@code Particle} is the one vocabulary type deliberately
 *       EXCLUDED from the injected constant lists, on the theory that ~110 names
 *       cost tokens and invite exotic choices. The observed vocabulary errors are
 *       concentrated in exactly that type ({@code BLOCK_CRACK}, a real rename;
 *       {@code LIGHTNING}, which never existed). This injects the list and lets the
 *       theory be wrong.
 *   <li>{@code intlong} — {@code possible lossy conversion from long to int} recurred
 *       across unrelated generations. One sentence about tick arithmetic and counts
 *       is the cheapest thing on this list.
 * </ul>
 */
final class Nudges {

    private Nudges() {
    }

    static final String IMPORTS = "imports";
    static final String NMS = "nms";
    static final String PARTICLE = "particle";
    static final String INTLONG = "intlong";

    static List<String> names() {
        return List.of(IMPORTS, NMS, PARTICLE, INTLONG);
    }

    /** The nudge tags in {@code after+a+b}, in order; empty for any other condition. */
    static List<String> tagsOf(String condition) {
        if (condition == null || !condition.startsWith("after+")) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String t : condition.substring("after+".length()).split("\\+")) {
            String s = t.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** Fail fast on a typo in a cell spec rather than silently measuring nothing. */
    static void validate(String condition) {
        Set<String> known = new LinkedHashSet<>(names());
        known.add("all");
        for (String t : tagsOf(condition)) {
            if (!known.contains(t)) {
                throw new IllegalArgumentException("unknown nudge '" + t + "' in condition '"
                        + condition + "'; known: " + String.join(", ", known));
            }
        }
    }

    /**
     * The {@code after} prompt plus the requested rules.
     *
     * <p>Appended at the end rather than spliced into the relevant section, and
     * that is a deliberate limitation to state out loud: it keeps the diff to one
     * block so the measurement is unambiguous, but it also means a positive result
     * is a lower bound — the same sentence placed next to the rule it amends could
     * do better. A negative result is the informative one either way.
     */
    static String apply(String afterPrompt, String condition, ClassFileVocabulary vocab) {
        List<String> tags = tagsOf(condition);
        if (tags.isEmpty()) {
            return afterPrompt;
        }
        if (tags.contains("all")) {
            tags = names();
        }
        StringBuilder sb = new StringBuilder(afterPrompt);
        sb.append("\n\n## Additional hard rules\n");
        for (String tag : tags) {
            sb.append('\n').append(text(tag, vocab)).append('\n');
        }
        return sb.toString();
    }

    private static String text(String tag, ClassFileVocabulary vocab) {
        return switch (tag) {
            case IMPORTS -> """
                    EVERY FILE MUST COMPILE ON ITS OWN. Each entry in "files" is a separate
                    compilation unit. Immediately after the package line of every file, write an
                    import for every single type that file names — including types you used in
                    another file of the same mod, and including java.util types such as List, Map,
                    UUID, Random, Set, ArrayList and HashMap. Before you emit a file, re-read it and
                    check that every capitalised type name in it either is declared in that same
                    file, is imported by name at the top of it, or is in java.lang. A missing import
                    is the single most common reason a generated mod fails to compile.""";
            case NMS -> """
                    BUKKIT TYPES ONLY. Types from Minecraft internals, NMS, Fabric, Forge or
                    Mojang mappings do not exist on this server and will not compile. In particular
                    there is no BlockPos, no BlockBox, no BoundingBox from those APIs, no Vec3d, no
                    ServerLevel, no ItemLike, no ResourceLocation. Use the Bukkit equivalents:
                      - a block position   -> org.bukkit.Location, or org.bukkit.block.Block
                      - a world            -> org.bukkit.World
                      - a 3D vector        -> org.bukkit.util.Vector
                      - a region/box       -> org.bukkit.util.BoundingBox
                      - a namespaced id    -> org.bukkit.NamespacedKey
                    If you cannot name the Bukkit type for something, do not invent one: solve the
                    problem with the Bukkit types you are sure of.""";
            case PARTICLE -> particleText(vocab);
            case INTLONG -> """
                    INT VERSUS LONG. Bukkit takes int for particle counts, item amounts, durations
                    in ticks passed to PotionEffect, and the delay/period arguments of the
                    scheduler's run-task methods are long. System.currentTimeMillis(), World.getTime()
                    and World.getFullTime() return long. Never pass a long where an int is required
                    and never assign one to an int without an explicit (int) cast — "possible lossy
                    conversion from long to int" is a compile error, not a warning. Prefer int for
                    tick counters you declare yourself.""";
            default -> throw new IllegalArgumentException("unknown nudge " + tag);
        };
    }

    /**
     * The real {@code Particle} constant list for the running version, read from
     * the same class files the rest of the eval measures against, so this nudge
     * cannot itself teach a name that does not exist.
     */
    private static String particleText(ClassFileVocabulary vocab) {
        Set<String> constants = vocab.constants("Particle");
        if (constants == null || constants.isEmpty()) {
            return """
                    PARTICLES. Only use org.bukkit.Particle constants you are certain exist on this
                    server version. If unsure, prefer the long-standing ones and do not guess.""";
        }
        List<String> sorted = new ArrayList<>(constants);
        java.util.Collections.sort(sorted);
        return "PARTICLES. These are the ONLY org.bukkit.Particle constants that exist on this\n"
                + "server. Any other name will not compile. Do not guess a particle name; if what\n"
                + "you want is not in this list, pick the closest one that is.\n\n"
                + String.join(", ", sorted);
    }
}
