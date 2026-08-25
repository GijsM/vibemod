package com.gijsm.vibemod.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.gijsm.vibemod.gen.GeneratedProject;
import com.gijsm.vibemod.gen.GeneratedProject.ConfigKnob;

/**
 * Disk-backed store of generated mods and their version history.
 *
 * Layout: {@code <modsDir>/<Name>/meta.json} (Gson, pretty-printed, mirroring
 * {@link StoredMod} verbatim) plus {@code <modsDir>/<Name>/v<N>/<File>.java} for
 * each version's sources. Mod name lookups are case-insensitive against the
 * directory names on disk. All public methods are synchronized: this store is
 * called both from the async generation pipeline and from the main thread.
 *
 * v1-shaped {@code meta.json} files (written before {@code usage}/{@code manual}/
 * {@code config}/{@code configValues} existed, or before {@code versions[]}
 * entries carried {@code changelog}/{@code kind}/{@code costUsd}/{@code requester})
 * deserialize with those fields null (Gson defaults a missing double to 0.0);
 * every read of a {@link StoredMod} out of this class is normalized so
 * callers never see a null where an empty string/list/map belongs.
 */
public final class ModStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z_][a-zA-Z0-9_.]*)\\s*;");

    private final Path modsDir;

    /**
     * One stored version's generation metadata. {@code changelog} is one player-facing
     * line describing what this version changed, {@code kind} the change type
     * (create/edit/fix/again), {@code costUsd} the real generation cost of this version,
     * and {@code requester} who asked for it — all four normalized to {@code ""}/0.0 for
     * entries written before they existed.
     */
    public record StoredVersion(int version, String prompt, String model, long createdAt,
                                 String changelog, String kind, double costUsd, String requester) {
    }

    /**
     * A stored mod: its identity, current pointer, full version history, and its
     * config schema/values. {@code usage}/{@code manual}/{@code icon}/{@code config}/
     * {@code configValues} are optional and normalized to ""/""/List.of()/Map.of()
     * by every accessor on this class. {@code debugEcho} is deliberately tri-state
     * and exempt from that no-nulls rule: {@code null} means "no explicit override,
     * follow the config default" (what every pre-existing meta.json reads as),
     * {@code true}/{@code false} a persisted per-mod override.
     */
    public record StoredMod(String name, String description, String usage, String manual, String icon,
                             String mainClass, int currentVersion, boolean enabled, String creator,
                             List<StoredVersion> versions, List<ConfigKnob> config,
                             Map<String, String> configValues, Boolean debugEcho) {
    }

    public ModStore(Path modsDir) {
        this.modsDir = modsDir;
        try {
            Files.createDirectories(modsDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** All stored mods, sorted by name. */
    public synchronized List<StoredMod> all() {
        List<StoredMod> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
            for (Path dir : stream) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                StoredMod mod = readMeta(dir);
                if (mod != null) {
                    result.add(mod);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        result.sort(Comparator.comparing(StoredMod::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** The stored mod by name (case-insensitive), or null if unknown. */
    public synchronized StoredMod get(String name) {
        Path dir = resolveDir(name);
        return dir == null ? null : readMeta(dir);
    }

    /**
     * Persists a new version of a mod's sources and refreshes its metadata.
     * The next version number is the current max existing version + 1, or 1
     * for a brand-new mod. File names are sanitized: no path separators, no
     * '..', must end in {@code .java}. {@code usage}/{@code manual}/{@code config}
     * are taken from {@code project}; any previously stored config values whose
     * key still appears in the new schema are carried forward, others dropped.
     * {@code changelog}/{@code kind}/{@code costUsd}/{@code requester} are recorded
     * on the new version entry (null strings become {@code ""}).
     */
    public synchronized StoredMod saveNewVersion(String name, String description, String mainClass, String creator,
                                                  String prompt, String model, String changelog, String kind,
                                                  double costUsd, String requester, GeneratedProject project) {
        Path dir = resolveDir(name);
        StoredMod existing = dir == null ? null : readMeta(dir);
        if (dir == null) {
            dir = modsDir.resolve(name);
        }

        int nextVersion = existing == null ? 1 : maxVersion(existing) + 1;

        Path versionDir = dir.resolve("v" + nextVersion);
        try {
            Files.createDirectories(versionDir);
            for (GeneratedProject.GeneratedFile file : project.files()) {
                String fileName = sanitizeFileName(file.path());
                Files.writeString(versionDir.resolve(fileName), file.content(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<StoredVersion> versions = existing == null ? new ArrayList<>() : new ArrayList<>(existing.versions());
        versions.add(new StoredVersion(nextVersion, prompt, model, System.currentTimeMillis(),
                nullToEmpty(changelog), nullToEmpty(kind), costUsd, nullToEmpty(requester)));

        String effectiveCreator = existing == null ? nullToEmpty(creator) : existing.creator();

        String usage = nullToEmpty(project.usage());
        String manual = nullToEmpty(project.manual());
        String icon = nullToEmpty(project.icon());
        List<ConfigKnob> newConfig = project.config() == null ? List.of() : project.config();

        Map<String, String> oldValues = existing == null ? Map.of() : existing.configValues();
        Set<String> newKeys = new HashSet<>();
        for (ConfigKnob k : newConfig) {
            if (k != null && k.key() != null) {
                newKeys.add(k.key().toLowerCase(Locale.ROOT));
            }
        }
        Map<String, String> preservedValues = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : oldValues.entrySet()) {
            if (e.getKey() != null && newKeys.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                preservedValues.put(e.getKey(), e.getValue());
            }
        }

        StoredMod updated = new StoredMod(name, description, usage, manual, icon, mainClass, nextVersion, true,
                effectiveCreator, versions, newConfig, preservedValues,
                existing == null ? null : existing.debugEcho());
        writeMeta(dir, updated);
        return updated;
    }

    /** Sources for one version of a mod: FQCN -> source text. */
    public synchronized Map<String, String> sources(String name, int version) {
        Path dir = resolveDir(name);
        if (dir == null) {
            return Map.of();
        }
        Path versionDir = dir.resolve("v" + version);
        Map<String, String> result = new LinkedHashMap<>();
        if (!Files.isDirectory(versionDir)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(versionDir, "*.java")) {
            for (Path file : stream) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                // Mods stored before the vibemine -> vibemod rename still import
                // com.gijsm.vibemine.api; normalize so they recompile against the
                // renamed API on load.
                content = content.replace("com.gijsm.vibemine.api", "com.gijsm.vibemod.api");
                String fileName = file.getFileName().toString();
                String simpleName = fileName.substring(0, fileName.length() - ".java".length());
                String fqcn = deriveFqcn(content, simpleName);
                result.put(fqcn, content);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    /** Points the mod at an already-stored version. */
    public synchronized void setCurrentVersion(String name, int version) {
        Path dir = resolveDir(name);
        if (dir == null) {
            return;
        }
        StoredMod mod = readMeta(dir);
        if (mod == null) {
            return;
        }
        StoredMod updated = new StoredMod(mod.name(), mod.description(), mod.usage(), mod.manual(), mod.icon(),
                mod.mainClass(), version, mod.enabled(), mod.creator(), mod.versions(), mod.config(),
                mod.configValues(), mod.debugEcho());
        writeMeta(dir, updated);
    }

    /**
     * Version numbers whose {@code v<N>/} sources directory still exists on disk.
     * Guards activating a version whose sources were pruned or lost.
     */
    public synchronized Set<Integer> versionsOnDisk(String name) {
        Set<Integer> result = new HashSet<>();
        Path dir = resolveDir(name);
        StoredMod mod = dir == null ? null : readMeta(dir);
        if (mod == null) {
            return result;
        }
        for (StoredVersion v : mod.versions()) {
            if (Files.isDirectory(dir.resolve("v" + v.version()))) {
                result.add(v.version());
            }
        }
        return result;
    }

    /** Enables or disables a mod without touching its version history. */
    public synchronized void setEnabled(String name, boolean enabled) {
        Path dir = resolveDir(name);
        if (dir == null) {
            return;
        }
        StoredMod mod = readMeta(dir);
        if (mod == null) {
            return;
        }
        StoredMod updated = new StoredMod(mod.name(), mod.description(), mod.usage(), mod.manual(), mod.icon(),
                mod.mainClass(), mod.currentVersion(), enabled, mod.creator(), mod.versions(), mod.config(),
                mod.configValues(), mod.debugEcho());
        writeMeta(dir, updated);
    }

    /**
     * Sets (or, with {@code null}, clears) a mod's persisted per-mod debug echo
     * override without touching anything else. Tri-state on purpose: {@code null}
     * means "follow the config default" — see {@link StoredMod#debugEcho()}.
     */
    public synchronized void setDebugEcho(String name, Boolean on) {
        Path dir = resolveDir(name);
        if (dir == null) {
            return;
        }
        StoredMod mod = readMeta(dir);
        if (mod == null) {
            return;
        }
        StoredMod updated = new StoredMod(mod.name(), mod.description(), mod.usage(), mod.manual(), mod.icon(),
                mod.mainClass(), mod.currentVersion(), mod.enabled(), mod.creator(), mod.versions(), mod.config(),
                mod.configValues(), on);
        writeMeta(dir, updated);
    }

    /** Moves the current version back by one. False if already at v1 or the mod is unknown. */
    public synchronized boolean rollback(String name) {
        Path dir = resolveDir(name);
        if (dir == null) {
            return false;
        }
        StoredMod mod = readMeta(dir);
        if (mod == null || mod.currentVersion() <= 1) {
            return false;
        }
        StoredMod updated = new StoredMod(mod.name(), mod.description(), mod.usage(), mod.manual(), mod.icon(),
                mod.mainClass(), mod.currentVersion() - 1, mod.enabled(), mod.creator(), mod.versions(),
                mod.config(), mod.configValues(), mod.debugEcho());
        writeMeta(dir, updated);
        return true;
    }

    /** Deletes a mod entirely, including all stored versions. */
    public synchronized void delete(String name) {
        Path dir = resolveDir(name);
        if (dir == null) {
            return;
        }
        try {
            deleteRecursively(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Validates {@code rawValue} against {@code key}'s schema (type parse,
     * min/max range for numerics, membership for choice) and persists the
     * normalized value. Throws {@link IllegalArgumentException} with a
     * human-readable reason on any validation failure or unknown mod/key.
     */
    public synchronized void setConfigValue(String name, String key, String rawValue) {
        Path dir = resolveDir(name);
        if (dir == null) {
            throw new IllegalArgumentException("Unknown mod: " + name);
        }
        StoredMod mod = readMeta(dir);
        if (mod == null) {
            throw new IllegalArgumentException("Unknown mod: " + name);
        }
        ConfigKnob knob = findKnob(mod.config(), key);
        if (knob == null) {
            throw new IllegalArgumentException("Mod '" + mod.name() + "' has no config key '" + key + "'");
        }
        String normalizedValue = validateKnobValue(knob, rawValue);

        Map<String, String> values = new LinkedHashMap<>(mod.configValues());
        values.put(knob.key(), normalizedValue);

        StoredMod updated = new StoredMod(mod.name(), mod.description(), mod.usage(), mod.manual(), mod.icon(),
                mod.mainClass(), mod.currentVersion(), mod.enabled(), mod.creator(), mod.versions(), mod.config(),
                values, mod.debugEcho());
        writeMeta(dir, updated);
    }

    /** Schema defaults overlaid with stored values (stored values win). Empty map for unknown mods. */
    public synchronized Map<String, String> resolvedConfigValues(String name) {
        StoredMod mod = get(name);
        if (mod == null) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (ConfigKnob k : mod.config()) {
            result.put(k.key(), nullToEmpty(k.def()));
        }
        result.putAll(mod.configValues());
        return result;
    }

    /**
     * Validates a raw string value against a knob's schema and returns the
     * normalized (canonical) string form to persist. Shared by {@link #setConfigValue}
     * and {@link ModConfigs#set} so both paths reject the same bad input with the
     * same message.
     *
     * <ul>
     *   <li>{@code boolean}: only {@code true}/{@code false} (case-insensitive); nothing else.</li>
     *   <li>{@code integer}: strict {@code long} parse, then rejected (not clamped) if outside
     *       {@code min}/{@code max}.</li>
     *   <li>{@code decimal}: strict finite {@code double} parse, then rejected if outside
     *       {@code min}/{@code max}.</li>
     *   <li>{@code choice}: matched case-insensitively against {@code choices}; the canonical
     *       (schema-cased) member is returned.</li>
     *   <li>{@code text} (or any unrecognized type): accepted as-is.</li>
     * </ul>
     */
    public static String validateKnobValue(ConfigKnob knob, String rawValue) {
        if (rawValue == null) {
            throw new IllegalArgumentException("Value for '" + knob.key() + "' must not be null");
        }
        String type = knob.type() == null ? "text" : knob.type().toLowerCase(Locale.ROOT);
        String trimmed = rawValue.trim();
        switch (type) {
            case "boolean": {
                if (trimmed.equalsIgnoreCase("true")) {
                    return "true";
                }
                if (trimmed.equalsIgnoreCase("false")) {
                    return "false";
                }
                throw new IllegalArgumentException(
                        "'" + rawValue + "' is not a valid boolean for '" + knob.key() + "' (expected true/false)");
            }
            case "integer": {
                long value;
                try {
                    value = Long.parseLong(trimmed);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "'" + rawValue + "' is not a valid integer for '" + knob.key() + "'");
                }
                if (knob.min() != null && value < knob.min()) {
                    throw new IllegalArgumentException(value + " is below the minimum of "
                            + formatNumber(knob.min()) + " for '" + knob.key() + "'");
                }
                if (knob.max() != null && value > knob.max()) {
                    throw new IllegalArgumentException(value + " is above the maximum of "
                            + formatNumber(knob.max()) + " for '" + knob.key() + "'");
                }
                return Long.toString(value);
            }
            case "decimal": {
                double value;
                try {
                    value = Double.parseDouble(trimmed);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "'" + rawValue + "' is not a valid decimal number for '" + knob.key() + "'");
                }
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException(
                            "'" + rawValue + "' is not a finite number for '" + knob.key() + "'");
                }
                if (knob.min() != null && value < knob.min()) {
                    throw new IllegalArgumentException(formatNumber(value) + " is below the minimum of "
                            + formatNumber(knob.min()) + " for '" + knob.key() + "'");
                }
                if (knob.max() != null && value > knob.max()) {
                    throw new IllegalArgumentException(formatNumber(value) + " is above the maximum of "
                            + formatNumber(knob.max()) + " for '" + knob.key() + "'");
                }
                return Double.toString(value);
            }
            case "choice": {
                List<String> choices = knob.choices() == null ? List.of() : knob.choices();
                for (String c : choices) {
                    if (c != null && c.equalsIgnoreCase(trimmed)) {
                        return c;
                    }
                }
                throw new IllegalArgumentException("'" + rawValue + "' is not one of ["
                        + String.join(", ", choices) + "] for '" + knob.key() + "'");
            }
            case "text":
            default:
                return rawValue;
        }
    }

    private static String formatNumber(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    // -- internals --

    private static ConfigKnob findKnob(List<ConfigKnob> config, String key) {
        if (config == null || key == null) {
            return null;
        }
        for (ConfigKnob k : config) {
            if (k != null && k.key() != null && k.key().equalsIgnoreCase(key)) {
                return k;
            }
        }
        return null;
    }

    private static int maxVersion(StoredMod mod) {
        int max = 0;
        for (StoredVersion v : mod.versions()) {
            max = Math.max(max, v.version());
        }
        return max;
    }

    private static String sanitizeFileName(String path) {
        if (path == null || path.contains("/") || path.contains("\\") || path.contains("..") || !path.endsWith(".java")) {
            throw new IllegalArgumentException("Invalid generated file name: " + path);
        }
        return path;
    }

    private static String deriveFqcn(String source, String simpleName) {
        Matcher m = PACKAGE_PATTERN.matcher(source);
        if (m.find()) {
            return m.group(1) + "." + simpleName;
        }
        return simpleName;
    }

    /** Resolves a mod name to its directory, matching case-insensitively. Null if not found. */
    private Path resolveDir(String name) {
        Path direct = modsDir.resolve(name);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
            for (Path dir : stream) {
                if (Files.isDirectory(dir) && dir.getFileName().toString().equalsIgnoreCase(name)) {
                    return dir;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return null;
    }

    private static StoredMod readMeta(Path dir) {
        Path meta = dir.resolve("meta.json");
        if (!Files.isRegularFile(meta)) {
            return null;
        }
        try {
            String json = Files.readString(meta, StandardCharsets.UTF_8);
            return normalize(GSON.fromJson(json, StoredMod.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeMeta(Path dir, StoredMod mod) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("meta.json"), GSON.toJson(mod), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Normalizes a {@link StoredMod} straight off disk: null {@code usage}/{@code manual}/
     * {@code icon}/{@code creator} become {@code ""}, null {@code versions}/{@code config}
     * become {@code List.of()}, a null {@code configValues} becomes {@code Map.of()}, and
     * version entries written before {@code changelog}/{@code kind}/{@code requester}
     * existed get those normalized to {@code ""} (Gson already defaults a missing
     * {@code costUsd} to 0.0). This is the single point where old-shaped {@code meta.json}
     * files (which predate these fields) are made safe for every caller.
     * {@code debugEcho} passes through UNCHANGED — its null is meaningful
     * ("no override"), see {@link StoredMod#debugEcho()}.
     */
    private static StoredMod normalize(StoredMod mod) {
        if (mod == null) {
            return null;
        }
        return new StoredMod(
                mod.name(),
                nullToEmpty(mod.description()),
                nullToEmpty(mod.usage()),
                nullToEmpty(mod.manual()),
                nullToEmpty(mod.icon()),
                mod.mainClass(),
                mod.currentVersion(),
                mod.enabled(),
                nullToEmpty(mod.creator()),
                normalizeVersions(mod.versions()),
                mod.config() == null ? List.of() : mod.config(),
                mod.configValues() == null ? Map.of() : mod.configValues(),
                mod.debugEcho());
    }

    /**
     * Rebuilds version entries whose post-v1 String fields ({@code changelog}/{@code kind}/
     * {@code requester}) are null so they read as {@code ""}; the list is only reallocated
     * when something actually needed fixing.
     */
    private static List<StoredVersion> normalizeVersions(List<StoredVersion> versions) {
        if (versions == null) {
            return List.of();
        }
        boolean needsFix = false;
        for (StoredVersion v : versions) {
            if (v.changelog() == null || v.kind() == null || v.requester() == null) {
                needsFix = true;
                break;
            }
        }
        if (!needsFix) {
            return versions;
        }
        List<StoredVersion> fixed = new ArrayList<>(versions.size());
        for (StoredVersion v : versions) {
            fixed.add(new StoredVersion(v.version(), v.prompt(), v.model(), v.createdAt(),
                    nullToEmpty(v.changelog()), nullToEmpty(v.kind()), v.costUsd(),
                    nullToEmpty(v.requester())));
        }
        return fixed;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
