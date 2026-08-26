package com.gijsm.vibemod.fabric.dimension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.resources.Identifier;

/**
 * The record of which runtime dimensions are meant to come back, and the reason
 * VibeMod keeps its own rather than extending {@code RegistryLedger}
 * (V4 Phase 6).
 *
 * <h2>Why not the registry ledger</h2>
 *
 * <p>{@code RegistryLedger}'s three states — {@code live}, {@code tombstone},
 * {@code pinned} — all answer one question: <em>may this registry id ever be
 * reused?</em> That question exists because item, entity-type and block ids are
 * baked into saved data and into wire order, so releasing one is a decision with
 * consequences measured in years (Decision 7, Decision 15).
 *
 * <p>A dimension asks a different question, and giving it the same answer would
 * be a category error. A {@code LEVEL_STEM} id is not baked into anything: it is
 * not synced, it is not positional in chunk data, and the registry that holds it
 * is rebuilt from datapack files at every world load. What persists about a
 * dimension is a <b>directory</b> and a <b>recipe</b>, and what this file records
 * is the recipe, so that the directory has something to be re-opened by.
 *
 * <p>It also lives here for a duller reason that is no less real: the ledger is
 * in {@code core/}, which is platform-free, and a {@code LevelStem} is about as
 * platform-bound as a value gets.
 *
 * <h2>What is recorded, and why it is the encoded stem</h2>
 *
 * <p>Each entry stores the {@code LevelStem} as JSON, produced by vanilla's own
 * {@code LevelStem.CODEC} over a {@code RegistryOps} on the live
 * {@code registryAccess()}. That is deliberate rather than convenient: the
 * generator inside a stem is an open polymorphic type — noise, flat, debug, or a
 * generated mod's own — and its codec is the only thing in the game that knows
 * how to write it down. Recording "which generator" as a string and rebuilding it
 * by hand would be a second, worse serializer that drifts from the first.
 *
 * <p>The consequence, said plainly: <b>this file is the same shape as a
 * {@code data/&lt;ns&gt;/dimension/&lt;name&gt;.json} datapack file</b>, because
 * it is written by the same codec. That is the end state to aim at — a
 * materialised datapack file, which vanilla loads at boot and turns into a level
 * with no VibeMod involvement whatsoever, exactly the "files first, registry
 * second" reframe §5 uses for every other dynamic registry. Until the content
 * pipeline writes those files, this ledger bridges the gap, and the bridge is
 * idempotent: re-opening a dimension the registry already holds is a no-op, so
 * the day the datapack files appear this class quietly stops doing anything.
 *
 * <p><b>A temporary dimension is never recorded.</b> That is the entire
 * difference between the two kinds, and it is the honest one: a dimension whose
 * directory is deleted on close has nothing to come back to.
 *
 * <h2>Writing</h2>
 *
 * <p>Write-to-temp-then-{@code ATOMIC_MOVE}, because this file is rewritten
 * whenever a dimension opens or closes and a server can be killed at any of those
 * moments. A half-written entry would mean a dimension whose directory exists and
 * whose recipe does not, which is the one state with no good recovery.
 */
public final class DimensionLedger {

    private static final Logger LOG = Logger.getLogger("VibeMod.Dimension");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Bumped only if the shape below changes incompatibly; read, never assumed. */
    private static final int FORMAT = 1;

    /** One persistent dimension, exactly as the file records it. */
    public record Entry(Identifier id, String modName, JsonElement stem) {
    }

    private final Path file;
    private final Map<Identifier, Entry> entries = new LinkedHashMap<>();

    /**
     * @param worldRoot the world save directory, i.e. {@code server.getWorldPath(LevelResource.ROOT)}
     */
    public DimensionLedger(Path worldRoot) {
        this.file = worldRoot.resolve("vibemod-dimensions.json");
        load();
    }

    /** Everything the file declares, in the order it declares it. */
    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    /** Records a persistent dimension, replacing any earlier recipe for the same id. */
    public void record(Identifier id, String modName, JsonElement stem) {
        entries.put(id, new Entry(id, modName, stem));
        save();
    }

    /** Drops a dimension from the record. Called when a persistent one is deliberately closed. */
    public void forget(Identifier id) {
        if (entries.remove(id) != null) {
            save();
        }
    }

    /** True when the file claims this dimension should be re-opened at boot. */
    public boolean isRecorded(Identifier id) {
        return entries.containsKey(id);
    }

    // ------------------------------------------------------------------ disk

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            int format = root.has("format") ? root.get("format").getAsInt() : 0;
            if (format != FORMAT) {
                // Loud and non-destructive: the file is left exactly as it is.
                // Rewriting it in a shape this build understands would throw away
                // the only record of somebody's world.
                LOG.severe("Not reading " + file + ": it declares format " + format + " and this"
                        + " build writes format " + FORMAT + ". Every dimension it records stays"
                        + " unopened and its directory stays on disk, untouched.");
                return;
            }
            JsonArray array = root.getAsJsonArray("dimensions");
            for (JsonElement element : array) {
                JsonObject object = element.getAsJsonObject();
                Identifier id = Identifier.tryParse(object.get("id").getAsString());
                if (id == null) {
                    LOG.warning("Skipping a dimension in " + file + " whose id does not parse: "
                            + object.get("id"));
                    continue;
                }
                String modName = object.has("mod") ? object.get("mod").getAsString() : "unknown";
                entries.put(id, new Entry(id, modName, object.get("stem")));
            }
            LOG.info("Read " + entries.size() + " persistent dimension(s) from " + file);
        } catch (RuntimeException | IOException e) {
            LOG.log(Level.SEVERE, "Could not read " + file + "; no dimension will be re-opened from"
                    + " it this session, and it is left on disk unmodified", e);
            entries.clear();
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        JsonArray array = new JsonArray();
        for (Entry entry : entries.values()) {
            JsonObject object = new JsonObject();
            object.addProperty("id", entry.id().toString());
            object.addProperty("mod", entry.modName());
            object.add("stem", entry.stem());
            array.add(object);
        }
        root.add("dimensions", array);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Some network filesystems refuse it. Say so rather than
                // pretending the write was atomic when it was not.
                LOG.warning("Atomic move not supported for " + file + "; falling back to a plain"
                        + " replace, which is not crash-safe on this filesystem");
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Could not write " + file + ". Dimensions opened this session will"
                    + " not come back after a restart; their directories are still on disk and"
                    + " nothing has been deleted", e);
        }
    }

    /** For the gates: {@code "dimRecorded=1"}. */
    public String describeState() {
        return "dimRecorded=" + entries.size();
    }

    /** Every recorded id, for a refusal message. */
    public List<Identifier> recordedIds() {
        return new ArrayList<>(entries.keySet());
    }
}
