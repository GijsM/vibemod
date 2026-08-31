package com.gijsm.vibemod.loader.content;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.minecraft.DetectedVersion;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.world.level.storage.LevelResource;

import com.gijsm.vibemod.platform.Registration;
import com.gijsm.vibemod.runtime.ModContent;
import com.gijsm.vibemod.runtime.ModHandle;
import com.gijsm.vibemod.store.ModResources;
import com.gijsm.vibemod.store.ModStore;
import com.gijsm.vibemod.util.Ids;

/**
 * A generated mod's {@code data/**} become a real world datapack, and its
 * {@code assets/**} become part of the client's runtime resource pack
 * (V3 Phase 2 §B).
 *
 * <p>Deliberately loader-neutral: everything here is vanilla API
 * ({@code MinecraftServer#getWorldPath}, {@code LevelResource.DATAPACK_DIR},
 * vanilla's own folder {@code RepositorySource}) plus the JDK, so Fabric and
 * NeoForge share it byte for byte and neither needed a repository injection on
 * the server side. The one thing that is not loader-neutral — putting a pack
 * into the <em>client's</em> repository — is reached through
 * {@link ClientResourceSink}, which is null on a dedicated server.
 *
 * <p>V4 Phase 3 gave that null a second answer. Until it, a dedicated server
 * stored a mod's {@code assets/**} and logged that they were inert, because
 * serving them needs a URL and a hash and V3 had neither. It has both now:
 * {@link ServerResourceSink} writes the same tree the client sink writes and
 * publishes it as a content-addressed zip. So the branch below is no longer
 * client-or-nothing — it is client, else pack server, else (a host with
 * neither) the honest old line.
 *
 * <p>Materialization is staged and renamed rather than written in place: a
 * half-written datapack directory that vanilla's folder source discovers
 * mid-write is a pack that fails to load, and the reload it fails during is the
 * one the player is watching.
 */
public final class LoaderModContent implements ModContent {

    private static final Logger LOG = Logger.getLogger("VibeMod.Content");

    /** Every generated pack directory starts with this, so ours are recognisable on disk. */
    public static final String PACK_PREFIX = "vibemod-";

    /**
     * The {@code data/<ns>/<type>/} directories {@code /reload} actually re-reads.
     *
     * <p>Everything else in a datapack — enchantments, dialogs, damage types,
     * jukebox songs, painting variants, worldgen — is loaded into the registry
     * layer when the world loads and is NOT re-read by a reload. A mod shipping
     * one is not a failure and the file is kept; it just does not take effect
     * until the world is loaded again, and saying so beats letting somebody
     * conclude the whole channel is broken.
     */
    private static final Set<String> RELOADABLE = Set.of(
            "recipe", "advancement", "function", "tags", "loot_table", "predicate", "item_modifier");

    private final MinecraftServer server;
    private final ModStore store;
    private final ReloadCoordinator coordinator;
    /** Null on a dedicated server, where {@link #serverSink} answers instead. */
    private final ClientResourceSink clientSink;
    /**
     * The pack server (V4 Phase 3), or null when there is none — a physical
     * client, or a dedicated server whose operator has {@code packserver.mode}
     * set to {@code off}. Never non-null at the same time as
     * {@link #clientSink}: a client already has the tree mounted locally and
     * hosting it to itself would be a second copy of the same files.
     */
    private final ServerResourceSink serverSink;

    public LoaderModContent(MinecraftServer server, ModStore store, ReloadCoordinator coordinator,
                            ClientResourceSink clientSink) {
        this(server, store, coordinator, clientSink, null);
    }

    public LoaderModContent(MinecraftServer server, ModStore store, ReloadCoordinator coordinator,
                            ClientResourceSink clientSink, ServerResourceSink serverSink) {
        this.server = server;
        this.store = store;
        this.coordinator = coordinator;
        this.clientSink = clientSink;
        this.serverSink = serverSink;
    }

    /** The world's {@code datapacks/} directory — vanilla's own folder source scans it. */
    public Path datapacksDir() {
        return server.getWorldPath(LevelResource.DATAPACK_DIR);
    }

    /** The datapack folder name for a mod, and therefore (as {@code file/<name>}) its pack id. */
    public static String packFolder(String modName) {
        return PACK_PREFIX + Ids.sanitize(modName, "mod");
    }

    @Override
    public Registration install(ModHandle handle) {
        Map<String, String> resources = store.resources(handle.name(), handle.version());
        if (resources.isEmpty()) {
            return null;
        }

        Map<String, String> data = new LinkedHashMap<>();
        Map<String, String> assets = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : resources.entrySet()) {
            if (entry.getKey().startsWith(ModResources.DATA_ROOT)) {
                data.put(entry.getKey(), entry.getValue());
            } else {
                assets.put(entry.getKey(), entry.getValue());
            }
        }

        boolean installedData = !data.isEmpty() && materialize(handle.name(), data);
        boolean installedAssets = false;
        if (!assets.isEmpty()) {
            if (clientSink != null) {
                installedAssets = clientSink.install(handle.name(), assets);
            } else if (serverSink != null) {
                installedAssets = serverSink.install(handle.name(), assets);
                if (installedAssets) {
                    // The count is already in the sink's own line; this one
                    // exists for the half V3 could not say — where they go and
                    // what URL a player will fetch them from.
                    LOG.info(handle.name() + "'s assets/ are in " + serverSink.describeDelivery());
                }
            } else {
                LOG.info(handle.name() + " ships " + assets.size() + " assets/ file(s); this host has no "
                        + "client resource pack and no pack server (packserver.mode=off), so they are "
                        + "stored but inert here (models, textures and lang need one or the other)");
            }
        }

        if (installedData) {
            coordinator.ownPack(packFolder(handle.name()));
            coordinator.markServerDirty(handle.name() + " loaded");
        }
        if (installedAssets) {
            coordinator.markClientDirty(handle.name() + " loaded");
        }
        if (!installedData && !installedAssets) {
            return null;
        }

        String modName = handle.name();
        boolean removeData = installedData;
        boolean removeAssets = installedAssets;
        return Registration.of(() -> uninstall(modName, removeData, removeAssets));
    }

    /**
     * The teardown half. Marks reloads pending and returns immediately — it runs
     * inside {@code ModHandle.drain()}, which the watchdog is timing, and a
     * reload takes up to two seconds.
     *
     * <p>The datapack directory is deleted even when nothing else happens,
     * because {@code level.dat} remembers selected pack ids: a folder left
     * behind for a mod that no longer exists is a "Missing data pack" warning on
     * every subsequent world load, and the reload the coordinator runs a moment
     * later is what writes the shortened selection back.
     */
    private void uninstall(String modName, boolean data, boolean assets) {
        if (data) {
            Path dir = datapacksDir().resolve(packFolder(modName));
            try {
                deleteRecursively(dir);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not remove the datapack for " + modName, e);
            }
            coordinator.disownPack(packFolder(modName));
            coordinator.markServerDirty(modName + " unloaded");
        }
        if (!assets) {
            return;
        }
        boolean removed = clientSink != null ? clientSink.remove(modName)
                : serverSink != null && serverSink.remove(modName);
        if (removed) {
            coordinator.markClientDirty(modName + " unloaded");
        }
    }

    /**
     * Writes {@code <world>/datapacks/vibemod-<mod>/} from scratch: a staging
     * directory next to it, then one atomic rename over whatever was there.
     */
    private boolean materialize(String modName, Map<String, String> data) {
        Path root = datapacksDir();
        Path target = root.resolve(packFolder(modName));
        Path staging = root.resolve(packFolder(modName) + ".staging");
        try {
            Files.createDirectories(root);
            deleteRecursively(staging);
            Files.createDirectories(staging);
            Files.writeString(staging.resolve("pack.mcmeta"), packMeta(modName), StandardCharsets.UTF_8);
            for (Map.Entry<String, String> entry : data.entrySet()) {
                Path file = staging.resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
            }
            deleteRecursively(target);
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(staging, target);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not write the datapack for " + modName, e);
            return false;
        }
        reportDeferred(modName, data);
        LOG.info("Datapack " + packFolder(modName) + " materialized with " + data.size() + " file(s)");
        return true;
    }

    /** One line per mod naming the files a {@code /reload} cannot bring in. */
    private static void reportDeferred(String modName, Map<String, String> data) {
        Set<String> deferred = new LinkedHashSet<>();
        for (String path : data.keySet()) {
            String[] parts = path.split("/");
            if (parts.length >= 3 && !RELOADABLE.contains(parts[2])) {
                deferred.add(parts[2]);
            }
        }
        if (!deferred.isEmpty()) {
            LOG.info(modName + " ships registry-layer data (" + String.join(", ", deferred)
                    + "); those files are installed but apply on next world load, not on this reload");
        }
    }

    /**
     * The pack manifest, in the format the running game actually speaks.
     *
     * <p>The numbers are read off {@code DetectedVersion.BUILT_IN} at runtime
     * rather than compiled in from {@code SharedConstants.DATA_PACK_FORMAT_MAJOR}
     * (which javac would inline as a constant), so a host jar that outlives a
     * game update still writes a manifest that game will accept. The two-element
     * {@code [major, minor]} form is what vanilla's own bundled datapacks use;
     * {@code PackFormat}'s codec is a {@code compactListCodec}, so a bare int
     * would work too.
     */
    public static String packMeta(String modName) {
        PackFormat format = DetectedVersion.BUILT_IN.packVersion(PackType.SERVER_DATA);
        return packMeta("VibeMod: " + modName, format);
    }

    /** Shared by the datapack above and the client's runtime resource pack (§D). */
    public static String packMeta(String description, PackFormat format) {
        return "{\n"
                + "  \"pack\": {\n"
                + "    \"description\": " + quote(description) + ",\n"
                + "    \"min_format\": [" + format.major() + ", " + format.minor() + "],\n"
                + "    \"max_format\": [" + format.major() + ", " + format.minor() + "]\n"
                + "  }\n"
                + "}\n";
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Deletes a directory tree if it exists. Never throws on a missing path. */
    public static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
