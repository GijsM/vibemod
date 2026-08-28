package eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The stored-mod corpus, read as eval TASKS.
 *
 * <p>Every mod under {@code server/plugins/VibeMod/mods/<Name>/meta.json} was
 * generated from a real human request and then actually ran, so its
 * {@code description} is a request the model has already been asked once. That
 * makes the corpus the only prompt set here that is not invented by the person
 * grading the eval.
 */
public final class EvalCorpus {

    private EvalCorpus() {
    }

    /** One eval prompt: the mod's name (for reporting) and the request text. */
    public record Task(String name, String description) {
    }

    /** Every corpus mod with a non-blank description, sorted by name. */
    public static List<Task> load(Path modsDir) throws IOException {
        List<Task> tasks = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) {
            return tasks;
        }
        try (var dirs = Files.list(modsDir)) {
            for (Path dir : dirs.sorted().toList()) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                Path meta = dir.resolve("meta.json");
                if (!Files.isReadable(meta)) {
                    continue;
                }
                String text = Files.readString(meta, StandardCharsets.UTF_8);
                JsonObject obj;
                try {
                    obj = JsonParser.parseString(text).getAsJsonObject();
                } catch (RuntimeException e) {
                    System.out.println("  [corpus] skipping unparseable " + meta + ": " + e);
                    continue;
                }
                String description = obj.has("description") && !obj.get("description").isJsonNull()
                        ? obj.get("description").getAsString() : "";
                if (description.isBlank()) {
                    continue;
                }
                String name = obj.has("name") && !obj.get("name").isJsonNull()
                        ? obj.get("name").getAsString() : dir.getFileName().toString();
                tasks.add(new Task(name, description.trim()));
            }
        }
        tasks.sort(Comparator.comparing(Task::name));
        return tasks;
    }

    /**
     * A deterministic, evenly-spread subset. Sorting by name first and then
     * taking index {@code i*size/n} means the subset is reproducible from the
     * corpus alone and is not clustered at one end of the alphabet — which a
     * plain {@code subList} or a seeded shuffle of 49 items both risk.
     *
     * <p>{@code seed} rotates the starting offset, so a second run with a
     * different seed samples a genuinely different slice while staying
     * reproducible.
     */
    public static List<Task> select(List<Task> all, int n, long seed) {
        List<Task> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparing(Task::name));
        int size = sorted.size();
        if (size == 0 || n <= 0) {
            return List.of();
        }
        if (n >= size) {
            return List.copyOf(sorted);
        }
        // PREFIX-STABLE on n. The selection is a fixed pseudo-random permutation
        // of the name-sorted corpus, seeded only by `seed`, and `n` just takes a
        // prefix of it — so select(all, 25, s) is a strict superset of
        // select(all, 12, s).
        //
        // This matters because the eval is resumable and content-addressed: the
        // response cache is keyed on the prompt, so growing n must not change
        // WHICH tasks the smaller run picked, or every generation already paid
        // for is orphaned. The first version indexed as `i * size / n`, which
        // re-spaces the whole selection whenever n changes; raising n from 12 to
        // 25 discarded all 23 paid calls. Cost of learning that: about $0.09.
        List<Task> shuffled = new ArrayList<>(sorted);
        java.util.Collections.shuffle(shuffled, new java.util.Random(seed));
        List<Task> picked = new ArrayList<>(shuffled.subList(0, n));
        picked.sort(Comparator.comparing(Task::name));
        return picked;
    }

    // ------------------------------------------------------------------
    // Stored sources — for the offline self-check
    // ------------------------------------------------------------------

    private static final Pattern PACKAGE_DECL = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;",
            Pattern.MULTILINE);

    /** The highest {@code vN} directory of a stored mod, or null when there is none. */
    public static Path latestVersionDir(Path modDir) throws IOException {
        Path best = null;
        int bestN = -1;
        try (var dirs = Files.list(modDir)) {
            for (Path d : dirs.toList()) {
                String name = d.getFileName().toString();
                if (!Files.isDirectory(d) || !name.matches("v\\d+")) {
                    continue;
                }
                int n = Integer.parseInt(name.substring(1));
                if (n > bestN) {
                    bestN = n;
                    best = d;
                }
            }
        }
        return best;
    }

    /**
     * The stored sources of one mod, keyed by FQCN exactly the way
     * {@code ModGenerator.toFqcnSources} keys them.
     */
    public static Map<String, String> storedSources(Path modDir) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        Path v = latestVersionDir(modDir);
        if (v == null) {
            return out;
        }
        String modName = modDir.getFileName().toString();
        try (var files = Files.list(v)) {
            for (Path f : files.sorted().toList()) {
                String fileName = f.getFileName().toString();
                if (!fileName.endsWith(".java")) {
                    continue;
                }
                String content = Files.readString(f, StandardCharsets.UTF_8);
                out.put(fqcn(fileName, content, modName), content);
            }
        }
        return out;
    }

    /** {@code ModGenerator.toFqcnSources}, copied so the eval keys sources identically. */
    public static String fqcn(String fileName, String content, String projectName) {
        String simple = fileName.replaceAll("\\.java$", "");
        Matcher m = PACKAGE_DECL.matcher(content);
        String pkg = m.find() ? m.group(1) : "vibemod." + projectName.toLowerCase();
        return pkg + "." + simple;
    }
}
