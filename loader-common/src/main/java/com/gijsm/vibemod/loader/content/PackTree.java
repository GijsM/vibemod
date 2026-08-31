package com.gijsm.vibemod.loader.content;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.minecraft.DetectedVersion;
import net.minecraft.server.packs.PackType;

import com.gijsm.vibemod.store.ModResources;
import com.gijsm.vibemod.store.PixelGrid;
import com.gijsm.vibemod.util.Ids;

/**
 * One resource-pack directory tree, shared by every live mod (V3 Phase 2 §D,
 * lifted out of {@code FabricClientPacks} by V4 Phase 3).
 *
 * <p>Everything here is file I/O plus two vanilla constants, and none of it was
 * ever about a client: writing {@code assets/**} under a root, remembering which
 * mod wrote which file, decoding {@code .png.grid} to a real PNG, and pruning
 * the directories a removal empties. V3 put it in the client class because the
 * client was the only consumer; the pack server is the second, and a second
 * copy of a tree writer is a second place for the manifest format to drift.
 * So the client keeps the half that genuinely needs a client — the
 * {@code RepositorySource}, {@code Pack.readMetaAndCreate}, the reload
 * deferral — and this holds the half that does not.
 *
 * <p>One tree with a per-mod manifest, rather than one pack per mod. A pack per
 * mod would mean N entries in the player's resource pack screen appearing and
 * vanishing as mods load; a manifest file listing exactly what each mod wrote
 * gives the same exact cleanup with one pack.
 *
 * <p>{@code .png.grid} files are decoded to real PNGs here — see
 * {@link PixelGrid}. The grid itself was validated at generation time, so a
 * broken one is a self-heal round rather than a texture the client rejects;
 * this end still refuses to write a file it cannot decode, and says which one.
 *
 * <p>{@link #archive()} is V4 Phase 3's addition and the one thing the client
 * never needed: the same tree as a **deterministic** zip, so that its SHA-1 is a
 * pure function of its contents and an unchanged pack is byte-identical on every
 * rebuild, on every host, in every timezone. That is what makes
 * {@code ClientboundResourcePackPushPacket}'s hash meaningful and what lets a
 * client that already has the pack skip the download.
 *
 * <p>Names no loader type and no client type, for the reason
 * {@link ClientReloader} states: this file is compiled into the dedicated-server
 * half of both loader jars.
 */
public final class PackTree {

    private static final Logger LOG = Logger.getLogger("VibeMod.Respack");

    /**
     * The timestamp every zip entry gets: 1980-01-01T00:00, the first instant
     * MS-DOS time can represent.
     *
     * <p>{@code setTimeLocal} rather than {@code setTime(long)} on purpose.
     * {@code setTime} converts epoch millis to DOS time <em>through the default
     * time zone</em>, so the same tree zipped in Amsterdam and in Auckland would
     * produce different bytes and therefore different hashes — which would make
     * the pack's identity a property of where the server is standing. Handing a
     * {@code LocalDateTime} straight to the DOS field removes the conversion.
     * The value is the bottom of the DOS range, so nothing is ever pushed out of
     * it into an extended-timestamp extra field (which would add bytes, but
     * deterministic ones — the range check is belt and braces).
     */
    private static final LocalDateTime ZIP_EPOCH = LocalDateTime.of(1980, 1, 1, 0, 0, 0);

    /**
     * The one directory a resource pack tree has, spelled without the trailing
     * separator {@code ModResources.ASSETS_ROOT} carries — that constant is a
     * path <em>prefix</em> for matching stored resource keys, and a
     * {@code Path} built from it is only accidentally the same thing.
     */
    private static final String ASSETS = "assets";

    private final Path root;
    private final Path manifests;
    private final String description;
    private final String label;
    private final Set<String> mods = new LinkedHashSet<>();

    /**
     * @param dataFolder  the host's own folder; the tree lands in
     *                    {@code respack/} under it and the manifests beside it
     * @param description the {@code pack.mcmeta} description a player sees
     * @param label       what to call this tree in a log line
     */
    public PackTree(Path dataFolder, String description, String label) {
        this.root = dataFolder.resolve("respack");
        // Beside the pack, never inside it: a stray directory next to `assets/`
        // is harmless to a PathPackResources, but "harmless" is a claim about
        // vanilla's directory walk that would be somebody else's problem to
        // re-check every version.
        this.manifests = dataFolder.resolve("respack-manifests");
        this.description = description;
        this.label = label;
    }

    /** The directory the pack itself lives in — {@code <dataFolder>/respack/}. */
    public Path root() {
        return root;
    }

    /** True once {@link #reset()} has written a manifest the game would accept. */
    public boolean hasMeta() {
        return Files.isRegularFile(root.resolve("pack.mcmeta"));
    }

    /**
     * Wipes the tree and starts a fresh one.
     *
     * <p>Called from the one moment in the process when the correct contents are
     * known without asking anything: no mod is live yet, so the pack must be
     * empty. Anything on disk is residue from a crash, and this is the stale
     * guard §D asks for in its strongest form.
     */
    public void reset() {
        try {
            LoaderModContent.deleteRecursively(root);
            LoaderModContent.deleteRecursively(manifests);
            Files.createDirectories(root.resolve(ASSETS));
            Files.createDirectories(manifests);
            Files.writeString(root.resolve("pack.mcmeta"),
                    LoaderModContent.packMeta(description,
                            DetectedVersion.BUILT_IN.packVersion(PackType.CLIENT_RESOURCES)),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        mods.clear();
    }

    /**
     * Writes {@code assets} (pack-relative path -> text, {@code .png.grid}
     * entries still in grid form) into the tree under {@code modName}, replacing
     * anything that mod wrote before. True when the tree actually changed.
     */
    public boolean install(String modName, Map<String, String> assets) {
        remove(modName);
        List<String> written = new ArrayList<>();
        try {
            Files.createDirectories(root.resolve(ASSETS));
            for (Map.Entry<String, String> entry : assets.entrySet()) {
                String relative = entry.getKey();
                Path target;
                if (ModResources.isGridPath(relative)) {
                    relative = relative.substring(0,
                            relative.length() - ModResources.GRID_SUFFIX.length()) + ".png";
                    target = root.resolve(relative);
                    Files.createDirectories(target.getParent());
                    Files.write(target, PixelGrid.parse(entry.getValue()).toPng());
                } else {
                    target = root.resolve(relative);
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
                }
                written.add(relative);
            }
            if (written.isEmpty()) {
                return false;
            }
            Files.createDirectories(manifests);
            Files.writeString(manifests.resolve(manifestName(modName)),
                    String.join("\n", written) + "\n", StandardCharsets.UTF_8);
        } catch (IllegalArgumentException badGrid) {
            LOG.warning("Could not decode a texture for " + modName + ": " + badGrid.getMessage());
            return !written.isEmpty();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not write resources for " + modName, e);
            return !written.isEmpty();
        }
        mods.add(modName);
        LOG.info(modName + " contributed " + written.size() + " file(s) to " + label);
        return true;
    }

    /** Removes everything {@code modName} put in the tree. True when something went. */
    public boolean remove(String modName) {
        Path manifest = manifests.resolve(manifestName(modName));
        if (!Files.isRegularFile(manifest)) {
            mods.remove(modName);
            return false;
        }
        try {
            for (String relative : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                if (relative.isBlank()) {
                    continue;
                }
                Path file = root.resolve(relative);
                Files.deleteIfExists(file);
                pruneEmptyParents(file.getParent());
            }
            Files.deleteIfExists(manifest);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not remove resources for " + modName, e);
        }
        mods.remove(modName);
        LOG.info(modName + " removed its files from " + label);
        return true;
    }

    /** True when a mod currently owns files in the tree. */
    public boolean knows(String modName) {
        return Files.isRegularFile(manifests.resolve(manifestName(modName)));
    }

    /** Forgets a mod without touching disk — the removal was deferred elsewhere. */
    public void forget(String modName) {
        mods.remove(modName);
    }

    /** The pack-relative paths one mod currently owns, for gates and diagnostics. */
    public List<String> filesOf(String modName) {
        Path manifest = manifests.resolve(manifestName(modName));
        if (!Files.isRegularFile(manifest)) {
            return List.of();
        }
        try {
            return Files.readAllLines(manifest, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank()).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** One line for logs and gates, e.g. {@code "respackMods=2 respackFiles=7"}. */
    public String describeState() {
        return "respackMods=" + mods.size() + " respackFiles=" + countFiles();
    }

    /** How many real files the tree holds under {@code assets/}. */
    public int countFiles() {
        Path assets = root.resolve(ASSETS);
        if (!Files.isDirectory(assets)) {
            return 0;
        }
        try (var walk = Files.walk(assets)) {
            return (int) walk.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------ the archive

    /**
     * The tree as a zip whose bytes depend on nothing but its contents.
     *
     * <p>Three sources of nondeterminism are closed, because all three would
     * otherwise change the SHA-1 without changing a single texture: entries are
     * sorted by their pack-relative name (a directory walk's order is the file
     * system's business, not ours), every timestamp is {@link #ZIP_EPOCH}, and
     * the deflate level is pinned. Directory entries are not written at all —
     * a pack is read by name through {@code ZipFile.getEntry}, which never
     * consults them, and an unwritten entry is one fewer thing to order.
     *
     * <p>The UUID is derived from the digest rather than generated, which is
     * what makes {@code ClientboundResourcePackPopPacket} usable: the id a
     * client is holding for our pack is a pure function of the pack, so an
     * unchanged pack is the same id and the push is a no-op the client answers
     * from its own cache.
     *
     * @return null when the tree holds no asset files at all — an empty pack is
     *         not worth a download, a push, or a line in the player's pack list
     */
    public Archive archive() {
        List<Path> files;
        try (var walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read " + label + " to archive it", e);
            return null;
        }
        int assetFiles = countFiles();
        if (assetFiles == 0) {
            return null;
        }
        List<String> names = new ArrayList<>(files.size());
        for (Path file : files) {
            names.add(relativeName(file));
        }
        // The walk is sorted by Path, whose ordering is segment-wise; the zip is
        // sorted by the STRING we actually write, so the two cannot disagree.
        List<Integer> order = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> names.get(a).compareTo(names.get(b)));

        ByteArrayOutputStream sink = new ByteArrayOutputStream(1 << 16);
        try (ZipOutputStream zip = new ZipOutputStream(sink, StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            for (int index : order) {
                ZipEntry entry = new ZipEntry(names.get(index));
                entry.setMethod(ZipEntry.DEFLATED);
                entry.setTimeLocal(ZIP_EPOCH);
                zip.putNextEntry(entry);
                zip.write(Files.readAllBytes(files.get(index)));
                zip.closeEntry();
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not archive " + label, e);
            return null;
        }
        byte[] bytes = sink.toByteArray();
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-1").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            // Every conformant JRE ships SHA-1; this is the checked exception,
            // not a real branch.
            throw new IllegalStateException("this JRE has no SHA-1", impossible);
        }
        return new Archive(bytes, hex(digest), UUID.nameUUIDFromBytes(digest), assetFiles);
    }

    /**
     * A built pack: its bytes, its SHA-1 (the hash the push packet carries), the
     * UUID derived from that hash, and how many asset files went into it.
     */
    public record Archive(byte[] bytes, String sha1, UUID uuid, int files) {

        /** The path this pack is served at — content-addressed, so it never collides. */
        public String fileName() {
            return sha1 + ".zip";
        }
    }

    // ------------------------------------------------------------ internals

    private String relativeName(Path file) {
        StringBuilder sb = new StringBuilder();
        for (Path segment : root.relativize(file)) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(segment.toString());
        }
        return sb.toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String manifestName(String modName) {
        return Ids.sanitize(modName, "mod") + ".files";
    }

    /**
     * Removes now-empty directories up to (but never including) {@code assets/}.
     *
     * <p>{@code assets/} itself stays: a {@code PathPackResources} whose
     * {@code assets/} directory has vanished is a pack that has to be
     * special-cased, and keeping one empty directory is cheaper than that.
     */
    private void pruneEmptyParents(Path from) {
        Path stopAt = root.resolve(ASSETS);
        Path current = from;
        while (current != null && current.startsWith(stopAt) && !current.equals(stopAt)) {
            try (var entries = Files.list(current)) {
                if (entries.findAny().isPresent()) {
                    return;
                }
            } catch (IOException e) {
                return;
            }
            try {
                Files.deleteIfExists(current);
            } catch (IOException e) {
                return;
            }
            current = current.getParent();
        }
    }
}
