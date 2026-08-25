package com.gijsm.vibemod.fabric.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.minecraft.DetectedVersion;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;

import com.gijsm.vibemod.fabric.mixin.client.PackRepositoryAccessor;
import com.gijsm.vibemod.loader.content.ClientReloader;
import com.gijsm.vibemod.loader.content.ClientResourceSink;
import com.gijsm.vibemod.loader.content.LoaderModContent;
import com.gijsm.vibemod.store.ModResources;
import com.gijsm.vibemod.store.PixelGrid;
import com.gijsm.vibemod.util.Ids;

/**
 * One runtime resource pack, shared by every live mod (V3 Phase 2 §D).
 *
 * <p>Rooted at {@code <gamedir>/vibemod/respack/}, joined to the client's
 * {@code PackRepository} through {@link PackRepositoryAccessor}, and always
 * present: {@code required=true} plus a fixed {@code TOP} position means
 * vanilla's {@code rebuildSelected} re-adds it after every reload without
 * anybody having to remember it in {@code options.txt} (verified by
 * disassembly). An empty pack contributes nothing, so "always present" costs
 * nothing when no mod ships assets.
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
 */
public final class FabricClientPacks implements ClientResourceSink, ClientReloader, RepositorySource {

    private static final Logger LOG = Logger.getLogger("VibeMod.Respack");

    /** The pack id the player would see in the resource pack screen, and the gates assert on. */
    public static final String PACK_ID = "vibemod/respack";

    private final Path root;
    private final Path manifests;
    private final Set<String> mods = new LinkedHashSet<>();

    /**
     * File mutations that arrived while a reload was in flight, and the flag
     * that puts them there.
     *
     * <p>A resource reload reads the pack's files from worker threads over one
     * to three seconds. Deleting a file out from under that reload is a
     * {@code NoSuchFileException} in the middle of somebody's loading screen —
     * observed on the first run of the client gate, which disabled a mod while
     * the reload its own load had triggered was still running. The end state
     * self-corrects (the removal marks the pack dirty, so another reload
     * follows), but a stack trace nobody can act on is not an acceptable way to
     * get there. So mutations wait for the reload to finish, and the reload
     * that was already pending picks them up.
     *
     * <p>This closes the window VibeMod opens. It does not close the window a
     * player opens by changing their resource packs at exactly the wrong
     * moment — that reload is the game's, and we are not told about it.
     */
    private final java.util.ArrayDeque<Runnable> deferred = new java.util.ArrayDeque<>();
    private boolean reloading;

    public FabricClientPacks(Path dataFolder) {
        this.root = dataFolder.resolve("respack");
        // Beside the pack, never inside it: a stray directory next to `assets/`
        // is harmless to a PathPackResources, but "harmless" is a claim about
        // vanilla's directory walk that would be somebody else's problem to
        // re-check every version.
        this.manifests = dataFolder.resolve("respack-manifests");
    }

    /**
     * Wipes the pack and starts a fresh one.
     *
     * <p>Called from client init, which is the one moment in the process when
     * the correct contents are known without asking anything: no world is
     * loaded, so no mod is live, so the pack must be empty. Anything on disk is
     * residue from a crash, and this is the stale guard §D asks for in its
     * strongest form.
     */
    public void resetOnClientInit() {
        try {
            LoaderModContent.deleteRecursively(root);
            LoaderModContent.deleteRecursively(manifests);
            Files.createDirectories(root.resolve("assets"));
            Files.createDirectories(manifests);
            Files.writeString(root.resolve("pack.mcmeta"),
                    LoaderModContent.packMeta("VibeMod generated mods",
                            DetectedVersion.BUILT_IN.packVersion(PackType.CLIENT_RESOURCES)),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        mods.clear();
    }

    /**
     * Adds this source to the client's pack repository if it is not there yet.
     * Safe to call before {@code Minecraft} has built its repository — it simply
     * does nothing and the next call (from a reload) succeeds.
     */
    public boolean joinRepository() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return false;
        }
        PackRepository repo;
        try {
            repo = client.getResourcePackRepository();
        } catch (Throwable tooEarly) {
            return false;
        }
        if (repo == null) {
            return false;
        }
        PackRepositoryAccessor accessor = (PackRepositoryAccessor) repo;
        Set<RepositorySource> sources = accessor.getSources();
        if (sources != null && sources.contains(this)) {
            return true;
        }
        Set<RepositorySource> merged = new LinkedHashSet<>();
        if (sources != null) {
            merged.addAll(sources);
        }
        merged.add(this);
        accessor.setSources(merged);
        LOG.info("VibeMod's runtime resource pack (" + PACK_ID + ") joined the client repository");
        return true;
    }

    // ------------------------------------------------------------ RepositorySource

    @Override
    public void loadPacks(Consumer<Pack> consumer) {
        if (!Files.isRegularFile(root.resolve("pack.mcmeta"))) {
            return;
        }
        PackLocationInfo location = new PackLocationInfo(PACK_ID,
                Component.literal("VibeMod mods"), PackSource.BUILT_IN, Optional.empty());
        Pack.ResourcesSupplier supplier = new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(PackLocationInfo info) {
                return new PathPackResources(info, root);
            }

            @Override
            public PackResources openFull(PackLocationInfo info, Pack.Metadata metadata) {
                return new PathPackResources(info, root);
            }
        };
        Pack pack = Pack.readMetaAndCreate(location, supplier, PackType.CLIENT_RESOURCES,
                new PackSelectionConfig(true, Pack.Position.TOP, true));
        if (pack == null) {
            LOG.warning("VibeMod's runtime resource pack has unreadable metadata; skipping it");
            return;
        }
        consumer.accept(pack);
    }

    // ------------------------------------------------------------ ClientResourceSink

    @Override
    public boolean install(String modName, Map<String, String> assets) {
        if (deferIfReloading(() -> doInstall(modName, assets))) {
            return true;
        }
        return doInstall(modName, assets);
    }

    private boolean doInstall(String modName, Map<String, String> assets) {
        doRemove(modName);
        List<String> written = new ArrayList<>();
        try {
            Files.createDirectories(root.resolve("assets"));
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
            LOG.log(Level.WARNING, "Could not write client resources for " + modName, e);
            return !written.isEmpty();
        }
        mods.add(modName);
        LOG.info(modName + " contributed " + written.size() + " file(s) to " + PACK_ID);
        return true;
    }

    @Override
    public boolean remove(String modName) {
        if (!Files.isRegularFile(manifests.resolve(manifestName(modName)))) {
            mods.remove(modName);
            return false;
        }
        if (deferIfReloading(() -> doRemove(modName))) {
            mods.remove(modName);
            return true;
        }
        return doRemove(modName);
    }

    private boolean doRemove(String modName) {
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
            LOG.log(Level.WARNING, "Could not remove client resources for " + modName, e);
        }
        mods.remove(modName);
        LOG.info(modName + " removed its files from " + PACK_ID);
        return true;
    }

    @Override
    public String describeState() {
        return "respackMods=" + mods.size() + " respackFiles=" + countFiles();
    }

    // ------------------------------------------------------------ ClientReloader

    @Override
    public void reload(Runnable done) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            done.run();
            return;
        }
        beginReload();
        client.execute(() -> {
            try {
                joinRepository();
                client.reloadResourcePacks().whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        LOG.log(Level.WARNING, "Client resource reload failed", failure);
                    }
                    endReload();
                    done.run();
                });
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "Client resource reload threw", t);
                endReload();
                done.run();
            }
        });
    }

    private synchronized void beginReload() {
        reloading = true;
    }

    /** Ends the in-flight reload and applies everything that waited for it. */
    private void endReload() {
        List<Runnable> pending;
        synchronized (this) {
            reloading = false;
            pending = List.copyOf(deferred);
            deferred.clear();
        }
        for (Runnable op : pending) {
            try {
                op.run();
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "A deferred resource-pack change failed", t);
            }
        }
        if (!pending.isEmpty()) {
            LOG.info("Applied " + pending.size() + " resource-pack change(s) held back during a reload");
        }
    }

    private synchronized boolean deferIfReloading(Runnable op) {
        if (!reloading) {
            return false;
        }
        deferred.add(op);
        return true;
    }

    // ------------------------------------------------------------ internals

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

    private static String manifestName(String modName) {
        return Ids.sanitize(modName, "mod") + ".files";
    }

    private int countFiles() {
        Path assets = root.resolve("assets");
        if (!Files.isDirectory(assets)) {
            return 0;
        }
        try (var walk = Files.walk(assets)) {
            return (int) walk.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Removes now-empty directories up to (but never including) {@code assets/}.
     *
     * <p>{@code assets/} itself stays: a {@code PathPackResources} whose
     * {@code assets/} directory has vanished is a pack that has to be
     * special-cased, and keeping one empty directory is cheaper than that.
     */
    private void pruneEmptyParents(Path from) {
        Path stopAt = root.resolve("assets");
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
