package com.gijsm.vibemod.compile;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns "cannot find symbol" into "here are the members that DO exist"
 * (V3 Phase 0 §D).
 *
 * <p>The single most expensive failure mode in VibeMod is a model that invents
 * a method name. It costs a full self-heal round — a real API call, real money,
 * real seconds of a player watching a progress bar — and the model's second
 * guess is often no better than its first, because nothing in the diagnostic
 * tells it what the type actually offers. The running game IS the
 * documentation (§7.1: generated code compiles against the server's own jars),
 * so the host can simply look, and a repair prompt carrying
 * {@code float getHealth()} next to {@code getHealthh} converges in one round
 * instead of three.
 *
 * <p>Works off the formatted diagnostics string rather than the
 * {@link javax.tools.Diagnostic} objects on purpose. The two backends VibeMod
 * supports word this completely differently — javac emits a three-line
 * {@code cannot find symbol} / {@code symbol:} / {@code location:} block, ECJ
 * emits {@code The method x() is undefined for the type Y} — and neither
 * exposes the missing name as structured data. Both spellings are parsed here,
 * which also means the oracle works on a diagnostics string that has been
 * stored, logged or handed across a thread.
 *
 * <p>Resolution is injected as a {@link Function} because the classes to
 * inspect live on the <em>host's</em> class loader (Knot, or FML's), not this
 * one: {@code core} cannot even name {@code net.minecraft}. Hosts pass
 * {@code n -> Class.forName(n, false, VibeModX.class.getClassLoader())}, and
 * {@code false} matters — a hint must never run a static initialiser.
 */
public final class SymbolOracle {

    /** Only types whose API we are willing to teach; a hint about {@code java.util.List} helps nobody. */
    private static final List<String> INTERESTING_OWNERS = List.of(
            "net.minecraft.", "com.mojang.", "net.fabricmc.", "com.gijsm.vibemod.api.");

    private static final int MAX_PER_SYMBOL = 12;
    private static final int MAX_TOTAL_CHARS = 3000;
    private static final int MAX_EDIT_DISTANCE = 3;

    /** javac: {@code   symbol:   method setHelth(float)} — the kind then the name. */
    private static final Pattern JAVAC_SYMBOL =
            Pattern.compile("symbol:\\s+(\\w+)\\s+([A-Za-z_$][\\w$]*)");
    /** javac: {@code   location: class net.minecraft.…} or {@code variable p of type net.minecraft.…}. */
    private static final Pattern JAVAC_LOCATION = Pattern.compile(
            "location:\\s+(?:\\w+\\s+\\S+\\s+of\\s+type\\s+|class\\s+|interface\\s+|enum\\s+"
                    + "|record\\s+|annotation\\s+)?([\\w.$]+)");
    /** ECJ: {@code The method setHelth(float) is undefined for the type LivingEntity}. */
    private static final Pattern ECJ_METHOD = Pattern.compile(
            "The method ([A-Za-z_$][\\w$]*)\\([^)]*\\) is undefined for the type ([\\w.$]+)");
    /** ECJ: {@code helth cannot be resolved or is not a field} — no owner, still worth a look. */
    private static final Pattern ECJ_FIELD = Pattern.compile(
            "([A-Za-z_$][\\w$]*) cannot be resolved or is not a field");
    /** Every fully-qualified type name anywhere in the diagnostics, for resolving ECJ's simple names. */
    private static final Pattern QUALIFIED_TYPE = Pattern.compile(
            "\\b([a-z][\\w]*(?:\\.[a-z][\\w]*)+\\.[A-Z][\\w$]*)\\b");

    private final Function<String, Class<?>> resolver;

    /**
     * @param resolver fully-qualified binary name -&gt; class, or null when the
     *                 host's loader cannot see it. Must not initialise the
     *                 class.
     */
    public SymbolOracle(Function<String, Class<?>> resolver) {
        this.resolver = resolver;
    }

    /** The oracle every host installs: resolve against the loader that owns the game's classes. */
    public static SymbolOracle forLoader(ClassLoader loader) {
        return new SymbolOracle(name -> {
            try {
                return Class.forName(name, false, loader);
            } catch (Throwable notThere) {
                return null;
            }
        });
    }

    /**
     * Builds the {@code API HINTS} block for a set of compile diagnostics, or
     * {@code ""} when there is nothing useful to say.
     *
     * <p>Never throws: a hint is a nicety and must never cost a repair round.
     */
    public String hints(String diagnostics) {
        if (diagnostics == null || diagnostics.isBlank() || resolver == null) {
            return "";
        }
        try {
            return build(diagnostics);
        } catch (Throwable defensive) {
            return "";
        }
    }

    private String build(String diagnostics) {
        Map<String, String> simpleNames = qualifiedNamesBySimpleName(diagnostics);
        // Keyed "owner#symbol" so the same miss repeated on ten lines is one hint.
        Map<String, Miss> misses = new LinkedHashMap<>();
        for (Miss miss : parse(diagnostics)) {
            misses.putIfAbsent(miss.owner() + "#" + miss.symbol(), miss);
        }

        StringBuilder out = new StringBuilder();
        for (Miss miss : misses.values()) {
            Class<?> owner = resolve(miss.owner(), simpleNames);
            if (owner == null || !interesting(owner.getName())) {
                continue;
            }
            List<String> members = closestMembers(owner, miss.symbol());
            if (members.isEmpty()) {
                continue;
            }
            StringBuilder block = new StringBuilder();
            block.append(owner.getName()).append(" has no `").append(miss.symbol())
                    .append("`; its real public members closest to that name are:\n");
            for (String member : members) {
                block.append("  - ").append(member).append('\n');
            }
            if (out.length() + block.length() > MAX_TOTAL_CHARS) {
                break;
            }
            out.append(block);
        }
        if (out.length() == 0) {
            return "";
        }
        return "API HINTS (read off the running game's own jars — these names exist, "
                + "the one you used does not):\n" + out;
    }

    // ------------------------------------------------------------------ parsing

    /** One missing member and the type it was looked for on. */
    private record Miss(String owner, String symbol) {
    }

    private static List<Miss> parse(String diagnostics) {
        List<Miss> misses = new ArrayList<>();
        String[] lines = diagnostics.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            Matcher symbol = JAVAC_SYMBOL.matcher(lines[i]);
            if (symbol.find()) {
                String kind = symbol.group(1);
                // "symbol: class Foo" is a missing TYPE, not a missing member:
                // listing the members of its enclosing scope would be noise.
                if ("class".equals(kind) || "interface".equals(kind) || "enum".equals(kind)) {
                    continue;
                }
                String owner = locationNear(lines, i);
                if (owner != null) {
                    misses.add(new Miss(owner, symbol.group(2)));
                }
                continue;
            }
            Matcher ecjMethod = ECJ_METHOD.matcher(lines[i]);
            if (ecjMethod.find()) {
                misses.add(new Miss(ecjMethod.group(2), ecjMethod.group(1)));
                continue;
            }
            Matcher ecjField = ECJ_FIELD.matcher(lines[i]);
            if (ecjField.find()) {
                // No owner in the message; the enclosing line often names one.
                String owner = qualifiedOn(lines[i]);
                if (owner != null) {
                    misses.add(new Miss(owner, ecjField.group(1)));
                }
            }
        }
        return misses;
    }

    /** javac puts {@code location:} on the line after {@code symbol:}; tolerate a couple either way. */
    private static String locationNear(String[] lines, int symbolLine) {
        for (int i = symbolLine; i < Math.min(lines.length, symbolLine + 3); i++) {
            Matcher location = JAVAC_LOCATION.matcher(lines[i]);
            if (location.find()) {
                return location.group(1);
            }
        }
        return null;
    }

    private static String qualifiedOn(String line) {
        Matcher matcher = QUALIFIED_TYPE.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Simple name -&gt; fully-qualified name, harvested from the diagnostics
     * themselves.
     *
     * <p>ECJ names types by their simple name ("undefined for the type
     * LivingEntity"), which is unresolvable on its own. But the same
     * diagnostics almost always spell the type out somewhere else — in another
     * error, in a parameter list, in an import complaint — so the text is its
     * own symbol table. Cheap, and it fails closed: an unresolvable owner just
     * produces no hint.
     */
    private static Map<String, String> qualifiedNamesBySimpleName(String diagnostics) {
        Map<String, String> bySimple = new LinkedHashMap<>();
        Matcher matcher = QUALIFIED_TYPE.matcher(diagnostics);
        while (matcher.find()) {
            String qualified = matcher.group(1);
            String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
            bySimple.putIfAbsent(simple, qualified);
        }
        return bySimple;
    }

    private Class<?> resolve(String owner, Map<String, String> simpleNames) {
        if (owner == null || owner.isBlank()) {
            return null;
        }
        Class<?> direct = apply(owner);
        if (direct != null) {
            return direct;
        }
        if (owner.indexOf('.') < 0) {
            String qualified = simpleNames.get(owner);
            if (qualified != null) {
                return apply(qualified);
            }
        }
        return null;
    }

    private Class<?> apply(String name) {
        try {
            return resolver.apply(name);
        } catch (Throwable notThere) {
            return null;
        }
    }

    private static boolean interesting(String className) {
        for (String prefix : INTERESTING_OWNERS) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ matching

    /**
     * Public members whose names are near {@code missing}: containment first
     * (a typo that added or dropped a word), then edit distance.
     */
    private List<String> closestMembers(Class<?> owner, String missing) {
        String needle = missing.toLowerCase(Locale.ROOT);
        List<Scored> scored = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        try {
            for (Method method : owner.getMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                    continue;
                }
                score(scored, seen, method.getName(), needle, render(method));
            }
            for (Field field : owner.getFields()) {
                if (!Modifier.isPublic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                score(scored, seen, field.getName(), needle, render(field));
            }
        } catch (Throwable unreadable) {
            return List.of();
        }

        scored.sort(Comparator.<Scored>comparingInt(s -> s.score).thenComparing(s -> s.rendered));
        List<String> out = new ArrayList<>();
        for (Scored s : scored) {
            if (out.size() >= MAX_PER_SYMBOL) {
                break;
            }
            out.add(s.rendered);
        }
        return out;
    }

    private static void score(List<Scored> into, Set<String> seen, String name, String needle,
                              String rendered) {
        if (!seen.add(rendered)) {
            return;
        }
        String candidate = name.toLowerCase(Locale.ROOT);
        if (candidate.contains(needle) || needle.contains(candidate)) {
            into.add(new Scored(0, rendered));
            return;
        }
        int distance = levenshtein(candidate, needle, MAX_EDIT_DISTANCE);
        if (distance <= MAX_EDIT_DISTANCE) {
            into.add(new Scored(distance, rendered));
        }
    }

    private record Scored(int score, String rendered) {
    }

    private static String render(Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append(simple(method.getReturnType())).append(' ').append(method.getName()).append('(');
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(simple(parameters[i]));
        }
        return sb.append(')').toString();
    }

    private static String render(Field field) {
        return simple(field.getType()) + " " + field.getName()
                + (Modifier.isStatic(field.getModifiers()) ? " (static field)" : " (field)");
    }

    private static String simple(Class<?> type) {
        if (type.isArray()) {
            return simple(type.getComponentType()) + "[]";
        }
        String name = type.getName();
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(dot + 1)).replace('$', '.');
    }

    /** Bounded Levenshtein: bails out as soon as every cell in a row exceeds {@code limit}. */
    private static int levenshtein(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) {
            return limit + 1;
        }
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            int rowMin = current[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
                rowMin = Math.min(rowMin, current[j]);
            }
            if (rowMin > limit) {
                return limit + 1;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
