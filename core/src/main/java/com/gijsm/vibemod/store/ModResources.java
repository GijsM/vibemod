package com.gijsm.vibemod.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.gijsm.vibemod.util.Ids;

/**
 * The rules for a generated mod's non-Java files (V3 Phase 2 §A).
 *
 * <p>A generated mod may ship the same {@code data/**}/{@code assets/**} tree a
 * real mod jar would. Two rules make that safe, and both live here so the
 * store, the prompt parser and the gates cannot disagree about them.
 *
 * <p><b>Shape.</b> A resource path is {@code data/<ns>/…} or
 * {@code assets/<ns>/…}, at least three segments deep, with no {@code ..}, no
 * backslashes, no empty segments and nothing outside {@code [a-z0-9_.-]} in any
 * segment except the namespace (which is sanitized rather than rejected,
 * because it is about to be replaced anyway). Anything else is an
 * {@link IllegalArgumentException} — which, on the generation path, is a
 * self-heal round rather than a broken pack on disk.
 *
 * <p><b>Namespace.</b> Whatever namespace the model chose, the stored files use
 * {@code vibemod_<modname lowercased and sanitized>} — in the path AND in every
 * {@code "<ns>:…"} id inside a text body. Two mods therefore cannot collide on
 * a recipe id no matter what they were told to call themselves, and the
 * few-shot is free to use a natural-looking namespace.
 *
 * <p>The rewrite deliberately does NOT touch dotted forms
 * ({@code item.myns.ruby} in a lang file). Those are translation keys, a mod's
 * own Java may name one, and Java sources are not rewritten — so rewriting the
 * lang file alone would break exactly the pairing it was meant to protect. The
 * prompt states the canonical namespace instead; this is the safety net for
 * when the model ignores it.
 */
public final class ModResources {

    /** Prefix every generated mod's namespace carries, so a namespace is never anyone else's. */
    public static final String NAMESPACE_PREFIX = "vibemod_";

    /** The two roots a generated resource may live under, in the order a pack lists them. */
    public static final String DATA_ROOT = "data/";
    public static final String ASSETS_ROOT = "assets/";

    /** A texture written as a pixel grid rather than binary PNG (§D). */
    public static final String GRID_SUFFIX = ".png.grid";

    /** What every path segment below the namespace must look like. */
    private static final Pattern SEGMENT = Pattern.compile("[a-z0-9_.-]+");

    private ModResources() {
    }

    /** True for a path the store keeps as a resource rather than handing to the compiler. */
    public static boolean isResourcePath(String path) {
        return path != null && (path.startsWith(DATA_ROOT) || path.startsWith(ASSETS_ROOT));
    }

    /** True for a {@code .png.grid} pixel-grid texture source (§D). */
    public static boolean isGridPath(String path) {
        return path != null && path.endsWith(GRID_SUFFIX);
    }

    /**
     * The namespace every one of this mod's resources ends up under. Derived
     * from the mod's own name, so it is stable across versions and unique per
     * mod without any registry of claimed namespaces.
     */
    public static String canonicalNamespace(String modName) {
        return NAMESPACE_PREFIX + Ids.sanitize(modName, "mod");
    }

    /**
     * Validates one resource path, returning it unchanged. Throws
     * {@link IllegalArgumentException} naming the path and the rule it broke —
     * the message is shown to the model, so it says what to do instead.
     */
    public static String validate(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("A file \"path\" must not be blank");
        }
        if (!isResourcePath(path)) {
            throw new IllegalArgumentException("File path must end with .java, or start with "
                    + "\"data/\" or \"assets/\" for a resource file, got: " + path);
        }
        if (path.indexOf('\\') >= 0 || path.startsWith("/") || path.endsWith("/")
                || path.contains("//")) {
            throw new IllegalArgumentException("Resource path must be a clean forward-slash path, got: "
                    + path);
        }
        String[] segments = path.split("/");
        if (segments.length < 3) {
            throw new IllegalArgumentException("Resource path must be <root>/<namespace>/<file>, got: "
                    + path);
        }
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Resource path segment '" + segment
                        + "' is not allowed, got: " + path);
            }
            // The namespace segment (index 1) is exempt: it is replaced by the
            // canonical one below, so refusing "MyMod" there would be refusing
            // something we were about to fix anyway.
            if (i != 1 && !SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("Resource path segments must be lowercase "
                        + "[a-z0-9_.-], got '" + segment + "' in: " + path);
            }
        }
        return path;
    }

    /** The namespace segment of an already-validated resource path. */
    public static String namespaceOf(String path) {
        int first = path.indexOf('/');
        int second = path.indexOf('/', first + 1);
        return path.substring(first + 1, second);
    }

    /**
     * Every {@code data/**}/{@code assets/**} entry of {@code files}, rewritten
     * onto {@code modName}'s canonical namespace — paths and bodies both.
     *
     * <p>The input is path -> content in the model's own namespace; the output
     * is path -> content in ours, in the same iteration order. Paths are
     * validated on the way through.
     */
    public static Map<String, String> canonicalize(String modName, Map<String, String> files) {
        String canonical = canonicalNamespace(modName);
        Set<String> foreign = new LinkedHashSet<>();
        for (String path : files.keySet()) {
            validate(path);
            String ns = namespaceOf(path);
            if (!ns.equals(canonical) && !ns.equals("minecraft")) {
                foreign.add(ns);
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String path = entry.getKey();
            String ns = namespaceOf(path);
            String newPath = ns.equals("minecraft") ? path : replaceNamespace(path, canonical);
            out.put(newPath, rewriteBody(entry.getValue(), foreign, canonical));
        }
        return out;
    }

    /** Namespaces a mod's stored resource tree still mentions, for logs and tests. */
    public static List<String> namespacesOf(Map<String, String> files) {
        List<String> out = new ArrayList<>();
        for (String path : files.keySet()) {
            String ns = namespaceOf(path);
            if (!out.contains(ns)) {
                out.add(ns);
            }
        }
        return out;
    }

    private static String replaceNamespace(String path, String canonical) {
        int first = path.indexOf('/');
        int second = path.indexOf('/', first + 1);
        return path.substring(0, first + 1) + canonical + path.substring(second);
    }

    /**
     * Rewrites {@code "<foreign>:} to {@code "<canonical>:} in a text body.
     *
     * <p>Anchored on the opening quote on purpose: that is what makes it an id
     * in a JSON document rather than a substring of some other word, and it is
     * why {@code "minecraft:stone"} is never touched (its namespace is not in
     * the foreign set) while {@code "myns:ruby"} always is.
     */
    private static String rewriteBody(String content, Set<String> foreign, String canonical) {
        if (content == null || foreign.isEmpty()) {
            return content == null ? "" : content;
        }
        String out = content;
        for (String ns : foreign) {
            out = out.replace("\"" + ns + ":", "\"" + canonical + ":");
        }
        return out;
    }
}
