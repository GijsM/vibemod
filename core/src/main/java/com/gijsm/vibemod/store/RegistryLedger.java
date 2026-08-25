package com.gijsm.vibemod.store;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import com.google.gson.JsonSyntaxException;

/**
 * What VibeMod has put into the game's own registries, and what it must never
 * put back (V3 Phase 3 §A).
 *
 * <p>A registry entry is the one thing a generated mod acquires that cannot be
 * taken away again. {@code MappedRegistry} has no {@code remove}: the id, the
 * numeric id and the {@code Holder} behind it are wired into chunk sections,
 * item stacks and every network codec derived from the registry, and pulling
 * one out mid-session would corrupt exactly the saves the feature exists to
 * enrich. So the honest model is a ledger rather than a revocation:
 *
 * <ul>
 *   <li><b>live</b> — the mod is in the store and its ids are registered again,
 *       identically, on the next boot. Deterministic because the id is derived
 *       from the mod's own name and the path it asked for, so "the same mod"
 *       always means "the same ids".</li>
 *   <li><b>tombstone</b> — the mod was unloaded. Its ids are never registered
 *       again. Vanilla drops an unknown item id from a save on load, so the
 *       world heals itself rather than failing, and this file is the record of
 *       what happened to it.</li>
 * </ul>
 *
 * <p>Disabling a mod is deliberately NOT a tombstone. A disabled mod's item
 * object is still in the registry — it has to be, for the reason above — and it
 * simply has no behaviour left, because everything a mod's item could DO
 * (events, commands, its own {@code use} override reaching mod state) is
 * drained with the rest of its registrations.
 *
 * <p>Written next to the store rather than inside it: it is host state about a
 * whole installation, not part of any one mod's version history, and rolling a
 * mod back to v1 must not roll back the fact that an id exists.
 */
public final class RegistryLedger {

    private static final Logger LOG = Logger.getLogger("VibeMod.Registry");

    /** The file name, under the host's data folder. */
    public static final String FILE_NAME = "registry-ledger.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One id VibeMod put into one registry. */
    public record Entry(String registry, String id) {
    }

    /** Everything one mod ever registered, and whether it may be registered again. */
    public static final class ModEntry {
        private String state = "live";
        private int version;
        private List<Entry> entries = new ArrayList<>();

        public boolean tombstoned() {
            return "tombstone".equals(state);
        }

        public List<Entry> entries() {
            return List.copyOf(entries);
        }

        public int version() {
            return version;
        }
    }

    private final Path file;
    private final Map<String, ModEntry> mods = new LinkedHashMap<>();

    public RegistryLedger(Path file) {
        this.file = file;
        load();
    }

    /** The path this ledger persists to. */
    public Path file() {
        return file;
    }

    /**
     * Records that {@code modName} registered {@code id} into {@code registry}.
     * Re-recording the same pair is a no-op, which is what makes a boot restore
     * of a live mod idempotent.
     */
    public synchronized void record(String modName, int version, String registry, String id) {
        ModEntry entry = mods.computeIfAbsent(modName, ignored -> new ModEntry());
        entry.state = "live";
        entry.version = version;
        Entry candidate = new Entry(registry, id);
        if (!entry.entries.contains(candidate)) {
            entry.entries.add(candidate);
        }
        save();
    }

    /**
     * Marks {@code modName}'s ids as never-again. Called when the mod is
     * unloaded from the store, not when it is merely disabled — see the class
     * comment for why those are different.
     */
    public synchronized void tombstone(String modName) {
        ModEntry entry = mods.get(modName);
        if (entry == null || entry.tombstoned()) {
            return;
        }
        entry.state = "tombstone";
        LOG.info("Tombstoned " + entry.entries.size() + " registry id(s) for unloaded mod "
                + modName + "; they will not be registered again, and vanilla drops unknown ids "
                + "from world saves");
        save();
    }

    /** True when this mod's ids must not be registered again. */
    public synchronized boolean isTombstoned(String modName) {
        ModEntry entry = mods.get(modName);
        return entry != null && entry.tombstoned();
    }

    /** Everything {@code modName} has registered, live or tombstoned; empty when nothing. */
    public synchronized List<Entry> entriesOf(String modName) {
        ModEntry entry = mods.get(modName);
        return entry == null ? List.of() : entry.entries();
    }

    /** Mod names with at least one recorded id. */
    public synchronized List<String> modNames() {
        return List.copyOf(mods.keySet());
    }

    /** {@code "ledgerMods=2 ledgerIds=3 ledgerTombstones=1"} — for the gates and /vibe info. */
    public synchronized String describeState() {
        int ids = 0;
        int tombstones = 0;
        for (ModEntry entry : mods.values()) {
            ids += entry.entries.size();
            if (entry.tombstoned()) {
                tombstones++;
            }
        }
        return "ledgerMods=" + mods.size() + " ledgerIds=" + ids + " ledgerTombstones=" + tombstones;
    }

    // ------------------------------------------------------------------ io

    private static final class Document {
        Map<String, ModEntry> mods = new LinkedHashMap<>();
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Document document = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    Document.class);
            if (document != null && document.mods != null) {
                document.mods.forEach((name, entry) -> {
                    if (entry != null) {
                        if (entry.entries == null) {
                            entry.entries = new ArrayList<>();
                        }
                        if (entry.state == null) {
                            entry.state = "live";
                        }
                        mods.put(name, entry);
                    }
                });
            }
        } catch (IOException | JsonSyntaxException e) {
            // A corrupt ledger must not stop the host booting. Losing it means
            // losing tombstones, which is a re-registration of ids that are
            // already absent from every save — noisy, not destructive.
            LOG.log(Level.WARNING, "Could not read " + file + "; starting a fresh registry ledger", e);
        }
    }

    private void save() {
        Document document = new Document();
        document.mods.putAll(mods);
        try {
            Files.createDirectories(file.getParent());
            Path staged = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(staged, GSON.toJson(document), StandardCharsets.UTF_8);
            Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }
}
