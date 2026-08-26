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
 *   <li><b>pinned</b> — the mod was unloaded and it had registered at least one
 *       {@code minecraft:block}. Its ids are never <em>reused</em> either, but
 *       they are never released: on every subsequent boot the host registers an
 *       inert stub under each one. See below for why the tombstone's premise
 *       collapses here.</li>
 * </ul>
 *
 * <h2>Why a block id can never be tombstoned (V4 Phase 1, finding 3c)</h2>
 *
 * <p>The tombstone's whole premise is "vanilla drops an unknown id from a save
 * on load, so the world heals itself". Disassembly of 26.2 says that is true for
 * items and <b>false for blockstates</b>, and the failure is silent.
 *
 * <p>{@code SerializableChunkData.parse} calls
 * {@code .promotePartial(…).getOrThrow(…)} (verified). A section's palette is a
 * {@code ListCodec}, which <em>drops</em> the elements that fail to decode and
 * hands back the <b>shortened</b> list as its partial value — which
 * {@code promotePartial} then accepts. Packed chunk data indexes that palette
 * <b>by position</b>. So dropping one entry renumbers every entry after it:
 * stone becomes dirt, dirt becomes gravel, for that entire 16³ section, with a
 * single recoverable-error line in the log to show for it.
 *
 * <p>That is why {@link #tombstone} <b>structurally refuses</b> to write
 * {@code tombstone} for a mod whose entries include a block. It writes
 * {@code pinned} instead, and {@link Entry#block()} carries the state schema —
 * property names, value strings, state count — recorded at registration time,
 * so the replay can rebuild a stub whose schema is byte-for-byte the original's.
 * {@link #isTombstoned} stays true for both states, because the
 * re-registration guard's question ("may this mod's ids be handed back to a mod
 * of the same name?") has the same answer either way.
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

    /** The registry whose ids can never be released; see the class comment. */
    public static final String BLOCK_REGISTRY = "minecraft:block";

    private static final String LIVE = "live";
    private static final String TOMBSTONE = "tombstone";
    private static final String PINNED = "pinned";

    /**
     * One id VibeMod put into one registry.
     *
     * @param block the state schema, for a {@code minecraft:block} entry, and
     *              null for everything else. Recorded at registration time
     *              because that is the only moment the live
     *              {@code StateDefinition} exists to read it off — by the time
     *              the mod is deleted the block object is the only copy of its
     *              own schema, and it is about to stop being constructed.
     */
    public record Entry(String registry, String id, BlockSchema block) {

        /** A non-block entry, which is every entry the item and entity paths make. */
        public Entry(String registry, String id) {
            this(registry, id, null);
        }

        /** True when this id lives in {@code minecraft:block} and so can never be released. */
        public boolean isBlock() {
            return BLOCK_REGISTRY.equals(registry);
        }
    }

    /** Everything one mod ever registered, and whether it may be registered again. */
    public static final class ModEntry {
        private String state = LIVE;
        private int version;
        private List<Entry> entries = new ArrayList<>();

        /**
         * True when this mod's ids must not be handed back to a mod of the same
         * name — which {@code pinned} and {@code tombstone} both mean, for
         * different reasons. {@link #pinned()} is the one that says whether the
         * ids also have to be re-registered on the way in.
         */
        public boolean tombstoned() {
            return TOMBSTONE.equals(state) || PINNED.equals(state);
        }

        /** True when this mod's block ids come back as stubs on every boot. */
        public boolean pinned() {
            return PINNED.equals(state);
        }

        /** True when at least one recorded id lives in {@code minecraft:block}. */
        public boolean hasBlock() {
            return entries.stream().anyMatch(Entry::isBlock);
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
        record(modName, version, registry, id, null);
    }

    /**
     * The block overload: the same recording, plus the state schema a pinned id
     * needs in order to come back as a correct stub.
     *
     * <p>Re-recording an id whose schema has changed <em>replaces</em> the
     * schema rather than adding a second entry. That is the right way round: the
     * mod is live, its block is being constructed right now, and the live
     * definition is by definition the true one. A schema only becomes
     * load-bearing at the moment the mod stops existing.
     */
    public synchronized void record(String modName, int version, String registry, String id,
                                    BlockSchema block) {
        ModEntry entry = mods.computeIfAbsent(modName, ignored -> new ModEntry());
        entry.state = LIVE;
        entry.version = version;
        // By (registry, id) rather than by the whole record: the schema is a
        // payload of the entry, not part of its identity, and comparing it would
        // turn a re-registration with a changed property set into a duplicate id.
        for (int i = 0; i < entry.entries.size(); i++) {
            Entry existing = entry.entries.get(i);
            if (existing.registry().equals(registry) && existing.id().equals(id)) {
                if (block != null && !block.equals(existing.block())) {
                    entry.entries.set(i, new Entry(registry, id, block));
                    save();
                }
                return;
            }
        }
        entry.entries.add(new Entry(registry, id, block));
        save();
    }

    /**
     * Marks {@code modName}'s ids as never-again. Called when the mod is
     * unloaded from the store, not when it is merely disabled — see the class
     * comment for why those are different.
     *
     * <p>Structurally refuses to write {@code tombstone} for a mod that
     * registered a block: that mod is <b>pinned</b> instead. A tombstone means
     * "the id is simply absent next boot", and for a blockstate that is not a
     * healed world, it is a palette whose entries all shift down by one — every
     * block after the missing one in that section's palette becomes the wrong
     * block, silently. There is no flag for this and no override: the two states
     * are chosen by what the mod registered, not by the caller.
     */
    public synchronized void tombstone(String modName) {
        ModEntry entry = mods.get(modName);
        if (entry == null || entry.tombstoned()) {
            return;
        }
        if (entry.hasBlock()) {
            entry.state = PINNED;
            long blocks = entry.entries.stream().filter(Entry::isBlock).count();
            LOG.warning("Pinned rather than tombstoned " + entry.entries.size()
                    + " registry id(s) for unloaded mod " + modName + ": " + blocks
                    + " of them are minecraft:block ids, and a block id can never be released. "
                    + "A section palette is a ListCodec, which DROPS an entry that fails to decode "
                    + "and hands the shortened list to promotePartial; packed chunk data indexes "
                    + "that palette by position, so one missing block renumbers every entry after "
                    + "it and silently rewrites the terrain of that whole 16x16x16 section. These "
                    + "ids come back as inert stubs on every boot instead — no silent drops");
        } else {
            entry.state = TOMBSTONE;
            LOG.info("Tombstoned " + entry.entries.size() + " registry id(s) for unloaded mod "
                    + modName + "; they will not be registered again, and vanilla drops unknown ids "
                    + "from world saves");
        }
        save();
    }

    /**
     * True when this mod's ids must not be registered again <em>as this mod's
     * ids</em> — true for a tombstone and for a pin alike, because the
     * re-registration guard's question is the same one either way.
     */
    public synchronized boolean isTombstoned(String modName) {
        ModEntry entry = mods.get(modName);
        return entry != null && entry.tombstoned();
    }

    /** True when this mod's block ids are pinned, so a stub is replayed for each on boot. */
    public synchronized boolean isPinned(String modName) {
        ModEntry entry = mods.get(modName);
        return entry != null && entry.pinned();
    }

    /**
     * Every pinned block schema, in the order the ids were first assigned.
     *
     * <p>Order is load-bearing and is the ledger's rather than the disk's: the
     * replay registers these before any live mod is restored, so the numeric ids
     * a boot mints come out in first-assigned order, which is the only ordering
     * that a later client-side registry sync can agree with.
     *
     * <p>A pinned block entry with no recorded schema is skipped and named. It
     * can only come from a ledger written before schemas were recorded, and
     * there is nothing honest to build from it — a guessed schema is exactly the
     * wrong stub this whole mechanism exists to avoid.
     */
    public synchronized List<BlockSchema> pinnedBlockSchemas() {
        List<BlockSchema> out = new ArrayList<>();
        mods.forEach((name, mod) -> {
            if (!mod.pinned()) {
                return;
            }
            for (Entry entry : mod.entries) {
                if (!entry.isBlock()) {
                    continue;
                }
                if (entry.block() == null) {
                    LOG.warning("Pinned block " + entry.id() + " (from deleted mod " + name
                            + ") has no recorded state schema, so no stub can be built for it. "
                            + "Chunks holding it will lose it from their palette on load");
                    continue;
                }
                out.add(entry.block());
            }
        });
        return out;
    }

    /** The block ids {@code modName} registered; empty when it registered none. */
    public synchronized List<String> blockIdsOf(String modName) {
        ModEntry entry = mods.get(modName);
        if (entry == null) {
            return List.of();
        }
        return entry.entries.stream().filter(Entry::isBlock).map(Entry::id).toList();
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

    /**
     * {@code "ledgerMods=2 ledgerIds=3 ledgerTombstones=1 ledgerPinned=1"} — for
     * the gates and /vibe info.
     *
     * <p>{@code name=value} throughout and the names never change, because the
     * gates match full prefixes of this string rather than parsing it — which is
     * also why {@code ledgerPinned} is a new counter appended at the end rather
     * than a share of {@code ledgerTombstones}. The two are not the same fact:
     * a tombstoned mod's ids are gone, a pinned mod's ids are registered again
     * on the next boot.
     */
    public synchronized String describeState() {
        int ids = 0;
        int tombstones = 0;
        int pinned = 0;
        for (ModEntry entry : mods.values()) {
            ids += entry.entries.size();
            if (entry.pinned()) {
                pinned++;
            } else if (entry.tombstoned()) {
                tombstones++;
            }
        }
        return "ledgerMods=" + mods.size() + " ledgerIds=" + ids + " ledgerTombstones=" + tombstones
                + " ledgerPinned=" + pinned;
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
                        entry.entries.removeIf(one -> one == null || one.registry() == null
                                || one.id() == null);
                        if (entry.state == null) {
                            entry.state = LIVE;
                        }
                        mods.put(name, entry);
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            // A corrupt ledger must not stop the host booting. Losing a
            // tombstone is a re-registration of ids that are already absent
            // from every save — noisy, not destructive. Losing a PIN is worse,
            // and is worth saying plainly: the stubs are not replayed, and the
            // chunks holding those blocks lose them from their palette on load.
            // Still not a reason to refuse to boot — a server that will not
            // start cannot repair anything either — but the warning is the only
            // notice anybody gets.
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
