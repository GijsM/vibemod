package com.gijsm.vibemod.llm;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.gijsm.vibemod.platform.ApiVocabulary;

/**
 * An {@link ApiVocabulary} read out of the classes the host is actually running,
 * by reflection.
 *
 * <p>This is the runtime half of the answer to docs/API-VOCABULARY.md: VibeMod
 * already compiles generated code against the live server classpath, so at boot
 * it can simply <em>look</em> at what the server declares instead of asserting
 * it from a version string. The offline half — {@code symbols.ClassFileVocabulary},
 * which parses a {@code paper-api} jar as bytes — measures the same facts for a
 * version that is not running, and the two agree by construction because both
 * count exactly {@code public static final} declared fields and declared method
 * names.
 *
 * <h2>Why this class lives in {@code core}</h2>
 *
 * <p>{@code core} must never depend on {@code paper-api} (ARCHITECTURE-V2 §1),
 * so the engine is parameterised: the host supplies a {@link ClassLoader} and a
 * map from the SIMPLE name {@link ApiVocabulary} is keyed by to the fully
 * qualified name to resolve. Not one platform package name appears below. The
 * Bukkit map lives in the {@code paper} module; a loader host can hand in its
 * own without this class changing.
 *
 * <h2>Open world — the one place this deviates from {@link ApiVocabulary}'s defaults</h2>
 *
 * <p>{@link ApiVocabulary}'s default {@link ApiVocabulary#knows} treats a
 * non-empty vocabulary as a CLOSED world: a type it does not list is absent.
 * That is right for a jar index, which enumerated every class there is. It is
 * <strong>wrong here</strong>, and dangerously so: this vocabulary resolves a
 * hand-supplied list of types, so the default would answer {@code NO} for the
 * ~5,000 Bukkit types nobody put in the map and mean {@code UNKNOWN} — exactly
 * the confusion that interface's javadoc warns will let a repair pass rewrite
 * working code. So {@link #knows} is overridden into three honest cases:
 *
 * <ul>
 *   <li>the type was asked for and resolved → {@link Known#YES};
 *   <li>the type was asked for and the classloader said it does not exist →
 *       {@link Known#NO} (a real, positive measurement — this is what makes
 *       {@code knows("Dialog") == NO} on Paper 1.21.6 evidence rather than a shrug);
 *   <li>anything else — not in the map, or resolved but unreadable →
 *       {@link Known#UNKNOWN}.
 * </ul>
 *
 * <h2>Constants and methods are measured independently</h2>
 *
 * <p>A type can resolve and still refuse to yield its method table: walking
 * {@code getDeclaredMethods} resolves every parameter and return type, so on a
 * classpath missing an optional dependency it throws {@code NoClassDefFoundError}
 * where reading the field table succeeded. The first version of this class
 * indexed both together and dropped the whole type when either failed — which,
 * measured against a bare {@code paper-api} jar, silently lost all 2,032
 * {@code Material} constants because {@code ItemMeta}-shaped signatures could
 * not be resolved. So the two are tracked separately, and
 * {@link #declaresConstant} and {@link #declaresMethod} are overridden to answer
 * from their own measurement: constants known and methods unknown is a real,
 * useful state, and collapsing it to "nothing known" throws away exactly the
 * facts the prompt most wants.
 *
 * <h2>Cost</h2>
 *
 * <p>Everything is indexed once, eagerly, in {@link #of}. Reflecting
 * {@code Material} alone walks 1,900+ fields, which is nothing once and silly
 * per generation, so hosts build this at boot and cache it.
 */
public final class ReflectiveVocabulary implements ApiVocabulary {

    private final Set<String> resolved;
    private final Set<String> absent;
    /** Types whose field table was successfully read; the key set of {@link #constants}. */
    private final Map<String, Set<String>> constants;
    /** Types whose method table was successfully read; the key set of {@link #methods}. */
    private final Map<String, Set<String>> methods;
    private final String source;

    private ReflectiveVocabulary(Set<String> resolved, Set<String> absent,
                                 Map<String, Set<String>> constants,
                                 Map<String, Set<String>> methods,
                                 String source) {
        this.resolved = Collections.unmodifiableSet(resolved);
        this.absent = Collections.unmodifiableSet(absent);
        this.constants = Collections.unmodifiableMap(constants);
        this.methods = Collections.unmodifiableMap(methods);
        this.source = source;
    }

    /**
     * Indexes every type in {@code types} against {@code loader}.
     *
     * @param loader the classloader that can see the platform API — on a server
     *               host, the one that loaded the platform's own classes
     * @param types  SIMPLE name (the {@link ApiVocabulary} key) to fully
     *               qualified name, e.g.
     *               {@code "ItemMeta" -> "org.bukkit.inventory.meta.ItemMeta"}.
     *               Iteration order is preserved for {@link #knownTypes()}.
     * @param source a short label for {@link #toString()} and the boot log
     */
    public static ApiVocabulary of(ClassLoader loader, Map<String, String> types, String source) {
        if (loader == null || types == null || types.isEmpty()) {
            return ApiVocabulary.empty();
        }
        Set<String> resolved = new LinkedHashSet<>();
        Set<String> absent = new LinkedHashSet<>();
        Map<String, Set<String>> constants = new LinkedHashMap<>();
        Map<String, Set<String>> methods = new LinkedHashMap<>();

        for (Map.Entry<String, String> e : types.entrySet()) {
            String simple = e.getKey();
            String fqcn = e.getValue();
            Class<?> type;
            try {
                // initialize=false: reading members must not run a static
                // initialiser that may want a fully started server.
                type = Class.forName(fqcn, false, loader);
            } catch (ClassNotFoundException notThere) {
                absent.add(simple);
                continue;
            } catch (Throwable unreadable) {
                // LinkageError and friends: the class may well exist, we simply
                // could not look. UNKNOWN, never NO.
                continue;
            }
            // Two independent measurements: either can fail without costing the
            // other. Anything not recorded stays UNKNOWN, never NO.
            try {
                constants.put(simple, publicStaticFinalFields(type));
                resolved.add(simple);
            } catch (Throwable unreadableFields) {
                // Field types could not be resolved; we know nothing about this
                // type's constants.
                constants.remove(simple);
            }
            try {
                methods.put(simple, declaredMethodNames(type));
                resolved.add(simple);
            } catch (Throwable unreadableMethods) {
                // Typically a signature naming a class this classpath lacks.
                methods.remove(simple);
            }
        }
        if (resolved.isEmpty() && absent.isEmpty()) {
            return ApiVocabulary.empty();
        }
        return new ReflectiveVocabulary(resolved, absent, constants, methods, source);
    }

    /**
     * {@code public static final} declared fields — the same definition
     * {@code ClassFileVocabulary} reads out of a class file's {@code fields[]},
     * and the reason enum constants and interface constants both count without
     * this code caring which shape the type happens to be this version.
     *
     * <p>Declared, not inherited: a jar index sees a class's own field table, so
     * matching that keeps the runtime and offline vocabularies comparable.
     */
    private static Set<String> publicStaticFinalFields(Class<?> type) {
        Set<String> out = new TreeSet<>();
        for (Field f : type.getDeclaredFields()) {
            int m = f.getModifiers();
            if (Modifier.isPublic(m) && Modifier.isStatic(m) && Modifier.isFinal(m)) {
                out.add(f.getName());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /** Declared method names, overloads collapsed — again matching the jar index. */
    private static Set<String> declaredMethodNames(Class<?> type) {
        Set<String> out = new TreeSet<>();
        for (Method m : type.getDeclaredMethods()) {
            out.add(m.getName());
        }
        return Collections.unmodifiableSet(out);
    }

    @Override
    public Set<String> knownTypes() {
        return resolved;
    }

    /** Types that were probed and definitively are not on this classpath. */
    public Set<String> absentTypes() {
        return absent;
    }

    @Override
    public Set<String> constants(String simpleTypeName) {
        return constants.getOrDefault(simpleTypeName, Collections.emptySet());
    }

    @Override
    public Set<String> methods(String simpleTypeName) {
        return methods.getOrDefault(simpleTypeName, Collections.emptySet());
    }

    /** Open-world override; see the class javadoc for why the default is wrong here. */
    @Override
    public Known knows(String simpleTypeName) {
        if (resolved.contains(simpleTypeName)) {
            return Known.YES;
        }
        if (absent.contains(simpleTypeName)) {
            return Known.NO;
        }
        return Known.UNKNOWN;
    }

    /**
     * Answers from the field measurement alone: {@code UNKNOWN} when this type's
     * field table could not be read, even if its methods could. See the class
     * javadoc — collapsing that to {@code NO} would let a repair pass delete a
     * constant that is really there.
     */
    @Override
    public Known declaresConstant(String simpleTypeName, String constantName) {
        if (absent.contains(simpleTypeName)) {
            return Known.NO;
        }
        Set<String> known = constants.get(simpleTypeName);
        if (known == null) {
            return Known.UNKNOWN;
        }
        return known.contains(constantName) ? Known.YES : Known.NO;
    }

    /** Symmetric with {@link #declaresConstant}, over the method measurement. */
    @Override
    public Known declaresMethod(String simpleTypeName, String methodName) {
        if (absent.contains(simpleTypeName)) {
            return Known.NO;
        }
        Set<String> known = methods.get(simpleTypeName);
        if (known == null) {
            return Known.UNKNOWN;
        }
        return known.contains(methodName) ? Known.YES : Known.NO;
    }

    /** One line for the boot log: what we managed to measure. */
    public String describe() {
        return source + ": " + resolved.size() + " types measured, "
                + absent.size() + " absent" + (absent.isEmpty() ? "" : " " + absent);
    }

    @Override
    public String toString() {
        return "ReflectiveVocabulary[" + describe() + "]";
    }
}
