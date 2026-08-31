package com.gijsm.vibemod.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * <h2>The order of record (V4 Phase 2)</h2>
 *
 * <p>Lane A — a dedicated server and a client that runs VibeMod — works only if
 * both ends build the same registries in the same order. Fabric's
 * {@code SyncConfigurationTask} renumbers <em>raw registry ids</em> for us and
 * we must not compete with it, but two things it does not touch make order
 * load-bearing anyway: {@code Block.BLOCK_STATE_REGISTRY} is an
 * {@code IdMapper} that appends at {@code nextId++} and is never remapped, and
 * a boot that mints ids in a different sequence than the last one changes what
 * every un-remapped consumer of those ids means.
 *
 * <p>So every entry carries a <b>monotonic per-installation sequence</b>,
 * assigned once, at first record. From then on this file — not the order the
 * store happens to list mods in, and not the order a directory listing comes
 * back in — is what "the order" means:
 *
 * <ul>
 *   <li>{@link #restoreOrder()} is the order a boot restores mods in;</li>
 *   <li>{@link #orderedEntries()} is the ordered id list a manifest is built
 *       from and {@link #orderHash} is hashed over;</li>
 *   <li>{@link #orderProblem} <b>refuses</b> a mod that registers its ids in a
 *       different order than this file records. That is the difference between
 *       "the raw ids drifted" and "somebody's saved inventory turned into a
 *       different item".</li>
 * </ul>
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
     * @param seq   the monotonic per-installation sequence, assigned once at
     *              first record and never rewritten. 1-based, because 0 is what
     *              a ledger written before V4 Phase 2 deserialises to and
     *              {@link #load()} uses that to tell "unnumbered" from
     *              "numbered first".
     */
    public record Entry(String registry, String id, BlockSchema block, int seq) {

        /** A non-block entry, which is every entry the item and entity paths make. */
        public Entry(String registry, String id) {
            this(registry, id, null, 0);
        }

        /** True when this id lives in {@code minecraft:block} and so can never be released. */
        public boolean isBlock() {
            return BLOCK_REGISTRY.equals(registry);
        }

        /**
         * {@code "minecraft:item|vibemod_x:ruby"} — the one string
         * {@link RegistryLedger#orderHash} hashes for this entry.
         *
         * <p>Registry and id both, because "the same ids in the same order" is
         * not the claim being checked: two registries can hold the same path,
         * and an ordered list that agreed on paths while disagreeing on which
         * registry they went into would hash the same and mean something else.
         */
        public String orderKey() {
            return RegistryLedger.orderKey(registry, id);
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
    /** The next sequence to hand out; 1-based, so 0 always means "not numbered yet". */
    private int nextSeq = 1;
    /** This installation's identity, minted on first ask and never rewritten. */
    private String installationId;
    /**
     * How far each mod has replayed its own recorded order in THIS process.
     *
     * <p>Deliberately not persisted: it is a fact about one boot's progress
     * through the file, not about the file. Rebuilt from zero every time a
     * server starts, which is exactly when a boot restore begins.
     */
    private final Map<String, Integer> replayCursor = new HashMap<>();

    public RegistryLedger(Path file) {
        this.file = file;
        load();
    }

    /** The path this ledger persists to. */
    public Path file() {
        return file;
    }

    /**
     * This installation's identity — a UUID minted the first time it is asked
     * for and written to the ledger beside the ids it belongs to.
     *
     * <p>It exists because a client can visit two VibeMod servers. Namespaces
     * are {@code vibemod_<modname>} and so are unique per <em>mod</em>, not per
     * server: two installations that both generated a mod called
     * {@code RubySword} mint the same id, and a client that has already taken
     * the first one cannot give it back — {@code MappedRegistry} has no
     * {@code remove}. This id is what lets the second server's join be refused
     * at the door with a message that names the collision, instead of the
     * client quietly holding somebody else's item under the same name.
     */
    public synchronized String installationId() {
        if (installationId == null || installationId.isBlank()) {
            installationId = UUID.randomUUID().toString();
            save();
        }
        return installationId;
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
                    // The sequence is carried over unchanged. It was assigned
                    // the first time this id was ever recorded and it is the
                    // one thing about the entry that must survive every later
                    // edit of it — a re-sequenced id is a reordered registry.
                    entry.entries.set(i, new Entry(registry, id, block, existing.seq()));
                    save();
                }
                return;
            }
        }
        entry.entries.add(new Entry(registry, id, block, nextSeq++));
        save();
    }

    // ------------------------------------------------------------------ the order of record

    /**
     * Every recorded id in the whole installation, in sequence order.
     *
     * <p>Across mods, not per mod, and that is the point: the numeric ids a
     * boot mints come out in one global sequence, so the order that has to be
     * reproduced is one global list. This is what a Lane A manifest is built
     * from and what {@link #orderHash} is hashed over.
     *
     * <p>Includes tombstoned and pinned mods' entries, because they are part of
     * the same sequence — a pinned block id really is registered again on every
     * boot, as a stub, before any live mod runs.
     */
    public synchronized List<Entry> orderedEntries() {
        List<Entry> out = new ArrayList<>();
        for (ModEntry mod : mods.values()) {
            out.addAll(mod.entries);
        }
        out.sort(Comparator.comparingInt(Entry::seq));
        return out;
    }

    /**
     * Mod names in the order a boot should restore them: earliest first
     * recorded id first.
     *
     * <p>Boot restore follows this rather than {@code ModStore.all()}, whose
     * order is a directory listing. A mod store that happens to hand back its
     * mods in a different order after a rename, a rollback or a filesystem that
     * does not sort is not a bug in the store — it is only a bug if something
     * downstream was quietly depending on it, and registry order is exactly
     * that something.
     *
     * <p>A mod with no recorded ids is not here at all: it registered nothing,
     * so its position in the sequence cannot matter.
     */
    public synchronized List<String> restoreOrder() {
        record Ranked(String name, int seq) {
        }
        List<Ranked> ranked = new ArrayList<>();
        mods.forEach((name, mod) -> mod.entries.stream()
                .mapToInt(Entry::seq)
                .min()
                .ifPresent(min -> ranked.add(new Ranked(name, min))));
        ranked.sort(Comparator.comparingInt(Ranked::seq));
        return ranked.stream().map(Ranked::name).toList();
    }

    /**
     * {@code modNames}, sorted into this file's order, with anything the file
     * has never seen appended in the order it was given.
     *
     * <p>The shape boot restore actually wants: a store hands back the mods it
     * has, and this says which order to load them in. Unknown mods go last
     * rather than first because they have registered nothing yet — whatever ids
     * they mint are new, so they belong at the end of the sequence, which is
     * where {@link #record} will put them anyway.
     */
    public synchronized List<String> inRestoreOrder(List<String> modNames) {
        List<String> known = restoreOrder();
        List<String> out = new ArrayList<>(modNames.size());
        for (String name : known) {
            if (modNames.contains(name)) {
                out.add(name);
            }
        }
        for (String name : modNames) {
            if (!out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * Why {@code modName} registering {@code id} into {@code registry} right
     * now disagrees with this file — or null when it does not.
     *
     * <p>Called on the near side of the registration, so a refusal costs a mod
     * load rather than a wrong id. The rule, and both halves of it are
     * deliberate:
     *
     * <ul>
     *   <li>An id this file has <b>never seen</b> is always fine. It is
     *       appended, it takes the next sequence, and there is nothing for it
     *       to disagree with.</li>
     *   <li>An id this file <b>has</b> seen must come no earlier than the
     *       cursor. Registering it out of turn is refused by name.</li>
     * </ul>
     *
     * <p>Skipping <em>forward</em> is allowed on purpose: an edited mod that no
     * longer registers one of its old ids is a normal thing to do, and the ids
     * it does register are still in recorded order. What is refused is the case
     * that cannot be recovered from — the same set of ids minted in a different
     * sequence, which silently renumbers everything downstream of the swap.
     */
    public synchronized String orderProblem(String modName, String registry, String id) {
        ModEntry mod = mods.get(modName);
        if (mod == null) {
            return null;
        }
        int cursor = replayCursor.getOrDefault(modName, 0);
        int found = -1;
        for (int i = 0; i < mod.entries.size(); i++) {
            Entry entry = mod.entries.get(i);
            if (entry.registry().equals(registry) && entry.id().equals(id)) {
                found = i;
                break;
            }
        }
        if (found < 0) {
            // A new id, appended after everything recorded. Nothing recorded
            // can follow it, so the cursor goes to the end.
            replayCursor.put(modName, mod.entries.size());
            return null;
        }
        if (found < cursor) {
            // The cursor can sit one past the end, when the last thing recorded
            // was a brand-new id; then there is no "expected next" to name and
            // the honest answer says so rather than reading off the end.
            String expected = cursor < mod.entries.size()
                    ? mod.entries.get(cursor).id() + " was expected next"
                    : "nothing was expected after it";
            return "mod " + modName + " registered " + id + " into " + registry
                    + " out of order: the registry ledger records it at position " + (found + 1)
                    + " and this boot has already registered up to position " + cursor + " ("
                    + expected + "). Registry order is the order of record "
                    + "because the numeric ids a boot mints follow it, and a mod that mints the "
                    + "same ids in a different sequence renumbers everything after the swap — no "
                    + "silent drops. Register your content from onInitialize() in a fixed order, "
                    + "or generate the mod under a new name";
        }
        replayCursor.put(modName, found + 1);
        return null;
    }

    /**
     * {@code "<registry>|<id>"} — the one string form both ends of a Lane A
     * connection hash, defined once, here, so the server and the client cannot
     * drift apart on the formatting of the thing they are comparing.
     */
    public static String orderKey(String registry, String id) {
        return registry + "|" + id;
    }

    /**
     * A short hash over an ordered list of {@link #orderKey} strings.
     *
     * <p>SHA-256, truncated to 16 hex characters, and order-sensitive by
     * construction: this is the value a joining client computes over what it
     * actually registered and sends back, so a server whose content the client
     * did not reproduce exactly can refuse the connection <em>at the door</em>
     * rather than kicking the player ten minutes later over a wire id nobody
     * can trace back to here.
     *
     * <p>Truncation is safe for what it is used for. This is a mismatch
     * detector between two ends that are trying to agree, not a signature: the
     * failure it defends against is drift, and the refusal path names the
     * differing ids anyway.
     */
    public static String orderHash(List<String> orderedKeys) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JRE ships SHA-256; this catch exists because the checked
            // exception does, not because the branch is reachable.
            throw new IllegalStateException("SHA-256 is missing from this JRE", e);
        }
        for (String key : orderedKeys) {
            digest.update(key.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        byte[] bytes = digest.digest();
        StringBuilder out = new StringBuilder(16);
        for (int i = 0; i < 8; i++) {
            out.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16));
            out.append(Character.forDigit(bytes[i] & 0xF, 16));
        }
        return out.toString();
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
        List<Entry> pinned = new ArrayList<>();
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
                pinned.add(entry);
            }
        });
        // Sequence order, not map order. Since V4 Phase 2 the sequence is what
        // "first-assigned" means; the map's iteration order is only the order
        // the file happened to list mods in, which is the same thing until the
        // day a mod is renamed and it stops being.
        pinned.sort(Comparator.comparingInt(Entry::seq));
        return pinned.stream().map(Entry::block).toList();
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

    /**
     * The version recorded for {@code modName}, or 0 when it has no entries.
     *
     * <p>Informational: a Lane A manifest carries it so a client-side log line
     * and a refusal message can say which version of which mod an id came from.
     * Nothing is registered from it.
     */
    public synchronized int versionOf(String modName) {
        ModEntry entry = mods.get(modName);
        return entry == null ? 0 : entry.version();
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
        String installationId;
        int nextSeq;
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
                installationId = document.installationId;
                nextSeq = Math.max(1, document.nextSeq);
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
                numberUnsequencedEntries();
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

    /**
     * Gives a sequence to every entry a pre-Phase-2 ledger left unnumbered.
     *
     * <p>File order is used, and it is the only defensible choice: it is the
     * order those ids were appended in, because the only writer that ever
     * appended them wrote them in registration order. Guessing anything else —
     * sorting by id, say — would invent an ordering for ids that already exist
     * in somebody's world, which is the exact failure this sequence exists to
     * prevent.
     */
    private void numberUnsequencedEntries() {
        boolean changed = false;
        for (ModEntry mod : mods.values()) {
            for (int i = 0; i < mod.entries.size(); i++) {
                Entry entry = mod.entries.get(i);
                if (entry.seq() > 0) {
                    nextSeq = Math.max(nextSeq, entry.seq() + 1);
                    continue;
                }
                mod.entries.set(i, new Entry(entry.registry(), entry.id(), entry.block(),
                        nextSeq++));
                changed = true;
            }
        }
        if (changed) {
            LOG.info("Numbered the registry ledger's existing entries in file order: it was "
                    + "written before the ledger became the order of record, and file order is "
                    + "the order they were appended in");
            save();
        }
    }

    private void save() {
        Document document = new Document();
        document.installationId = installationId;
        document.nextSeq = nextSeq;
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
