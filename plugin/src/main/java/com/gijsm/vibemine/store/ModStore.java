package com.gijsm.vibemine.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.gijsm.vibemine.gen.GeneratedProject;

/**
 * Disk-backed store of generated mods and their version history.
 *
 * Layout: {@code <modsDir>/<Name>/meta.json} (Gson, pretty-printed, mirroring
 * {@link StoredMod} verbatim) plus {@code <modsDir>/<Name>/v<N>/<File>.java} for
 * each version's sources. Mod name lookups are case-insensitive against the
 * directory names on disk. All public methods are synchronized: this store is
 * called both from the async generation pipeline and from the main thread.
 */
public final class ModStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z_][a-zA-Z0-9_.]*)\\s*;");

    private final Path modsDir;

    /** One stored version's generation metadata. */
    public record StoredVersion(int version, String prompt, String model, long createdAt) {
    }

    /** A stored mod: its identity, current pointer, and full version history. */
    public record StoredMod(String name, String description, String mainClass, int currentVersion,
                             boolean enabled, String creator, List<StoredVersion> versions) {
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
     * '..', must end in {@code .java}.
     */
    public synchronized StoredMod saveNewVersion(String name, String description, String mainClass, String creator,
                                                  String prompt, String model, GeneratedProject project) {
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
        versions.add(new StoredVersion(nextVersion, prompt, model, System.currentTimeMillis()));

        String effectiveCreator = existing == null ? creator : existing.creator();
        StoredMod updated = new StoredMod(name, description, mainClass, nextVersion, true, effectiveCreator, versions);
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
        StoredMod updated = new StoredMod(mod.name(), mod.description(), mod.mainClass(), version,
                mod.enabled(), mod.creator(), mod.versions());
        writeMeta(dir, updated);
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
        StoredMod updated = new StoredMod(mod.name(), mod.description(), mod.mainClass(), mod.currentVersion(),
                enabled, mod.creator(), mod.versions());
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
        StoredMod updated = new StoredMod(mod.name(), mod.description(), mod.mainClass(), mod.currentVersion() - 1,
                mod.enabled(), mod.creator(), mod.versions());
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

    // -- internals --

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
            return GSON.fromJson(json, StoredMod.class);
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
