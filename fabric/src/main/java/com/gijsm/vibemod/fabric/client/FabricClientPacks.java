package com.gijsm.vibemod.fabric.client;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

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
import com.gijsm.vibemod.loader.content.PackTree;

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
 * <p>V4 Phase 3 halved this class. Everything that was file I/O over a directory
 * of {@code assets/**} — the per-mod manifests, the {@code .png.grid} decode,
 * the empty-parent pruning — moved to {@link PackTree} in {@code loader-common},
 * because the pack server needs exactly the same tree written exactly the same
 * way and two writers is two manifest formats waiting to drift. What is left
 * here is what genuinely needs a client and could not move: joining a
 * {@code PackRepository} that has no public API for it, building a {@code Pack}
 * through {@code Pack.readMetaAndCreate}, and holding mutations back while a
 * reload is reading the files.
 */
public final class FabricClientPacks implements ClientResourceSink, ClientReloader, RepositorySource {

    private static final Logger LOG = Logger.getLogger("VibeMod.Respack");

    /** The pack id the player would see in the resource pack screen, and the gates assert on. */
    public static final String PACK_ID = "vibemod/respack";

    private final PackTree tree;

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
        this.tree = new PackTree(dataFolder, "VibeMod generated mods", PACK_ID);
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
        tree.reset();
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
        if (!tree.hasMeta()) {
            return;
        }
        Path root = tree.root();
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
        if (deferIfReloading(() -> tree.install(modName, assets))) {
            return true;
        }
        return tree.install(modName, assets);
    }

    @Override
    public boolean remove(String modName) {
        if (!tree.knows(modName)) {
            tree.forget(modName);
            return false;
        }
        if (deferIfReloading(() -> tree.remove(modName))) {
            tree.forget(modName);
            return true;
        }
        return tree.remove(modName);
    }

    @Override
    public String describeState() {
        return tree.describeState();
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
        return tree.filesOf(modName);
    }
}
