package com.gijsm.vibemod.fabric.dynamic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import com.gijsm.vibemod.loader.content.LoaderModContent;

/**
 * Reads the dynamic entries back off the datapack VibeMod already wrote, and
 * hands each one to {@link DynamicSeam} (V4 Phase 5).
 *
 * <p>Deliberately a <b>disk sweep</b> rather than a hook on the install path, and
 * that is the whole design in one choice. {@code LoaderModContent.materialize}
 * has already staged and renamed {@code <world>/datapacks/vibemod-<mod>/} by the
 * time anything here runs; reading it back means the only claim this feature
 * makes is <em>"we apply now what the datapack on disk already declares"</em>. A
 * hook on the in-memory resource map would apply something the file might not
 * say, and the next world load — where vanilla reads the file and this class does
 * not run at all — would quietly disagree.
 *
 * <p>Three properties fall out of that and are worth naming:
 *
 * <ul>
 *   <li><b>Idempotent.</b> {@link DynamicSeam#apply} answers
 *       {@code ALREADY_PRESENT} for an id the registry holds, so sweeping twice
 *       is free and sweeping after a restart is a no-op — by then vanilla loaded
 *       the same files itself, in its own deterministic order.</li>
 *   <li><b>Self-healing across the uninstall path.</b>
 *       {@code LoaderModContent.uninstall} deletes the folder, so a disabled
 *       mod's entries simply stop being swept. The ids stay in the live registry
 *       (there is no {@code MappedRegistry.remove}) and stop being re-applied at
 *       the next boot, which is the same shape as a tombstone.</li>
 *   <li><b>It needs nothing from {@code LoaderModContent}.</b> No edit to that
 *       class, no new interface, no ordering contract between two channels.</li>
 * </ul>
 *
 * <p>Directory matching is by longest prefix because the datapack directory for a
 * registry is {@code Registries.elementsDirPath}, which is the key's whole path —
 * {@code enchantment} for one, {@code worldgen/biome} for another. Sorting the
 * supported directories longest-first means {@code worldgen/biome} is never
 * mistaken for a hypothetical {@code worldgen}.
 */
public final class DatapackSweep {

    private static final Logger LOG = Logger.getLogger("VibeMod.Dynamic");

    /** Supported directories, longest first, so the longest prefix wins. */
    private static final List<String> DIRS_LONGEST_FIRST =
            DynamicCatalogue.directories().stream()
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .toList();

    private final DynamicSeam seam;

    public DatapackSweep(DynamicSeam seam) {
        this.seam = seam;
    }

    /**
     * Walks every {@code vibemod-*} datapack in the world and applies whatever it
     * finds that the live registries do not already hold.
     *
     * <p>Server thread only, and cheap when there is nothing to do: it is a
     * directory walk over folders VibeMod itself wrote, and every id already in
     * the registry short-circuits before a codec runs.
     *
     * @return every entry it touched, including the ones it refused, in walk order
     */
    public List<DynamicSeam.Result> sweep(MinecraftServer server) {
        List<DynamicSeam.Result> results = new ArrayList<>();
        if (server == null) {
            return results;
        }
        Path datapacks = server.getWorldPath(LevelResource.DATAPACK_DIR);
        if (!Files.isDirectory(datapacks)) {
            return results;
        }
        List<Path> packs;
        try (Stream<Path> children = Files.list(datapacks)) {
            packs = children
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(LoaderModContent.PACK_PREFIX))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not list " + datapacks + " for dynamic registry content", e);
            return results;
        }
        for (Path pack : packs) {
            String modName = pack.getFileName().toString()
                    .substring(LoaderModContent.PACK_PREFIX.length());
            sweepPack(server, pack, modName, results);
        }
        return results;
    }

    private void sweepPack(MinecraftServer server, Path pack, String modName,
                           List<DynamicSeam.Result> results) {
        Path data = pack.resolve("data");
        if (!Files.isDirectory(data)) {
            return;
        }
        List<Path> namespaces;
        try (Stream<Path> children = Files.list(data)) {
            namespaces = children.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read " + data, e);
            return;
        }
        for (Path namespaceDir : namespaces) {
            String namespace = namespaceDir.getFileName().toString();
            for (String dir : DIRS_LONGEST_FIRST) {
                DynamicCatalogue.Kind kind = DynamicCatalogue.byDir(dir);
                Path root = namespaceDir.resolve(dir.replace('/', java.io.File.separatorChar));
                if (!Files.isDirectory(root)) {
                    continue;
                }
                sweepDirectory(server, root, namespace, kind, modName, results);
            }
        }
    }

    private void sweepDirectory(MinecraftServer server, Path root, String namespace,
                                DynamicCatalogue.Kind kind, String modName,
                                List<DynamicSeam.Result> results) {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    // Sorted, because registration order is the order raw ids are
                    // minted in, and a sweep that walked the filesystem's order
                    // would mint them differently on two machines with the same
                    // datapack. Vanilla's own loader sorts too.
                    .sorted()
                    .toList();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not walk " + root, e);
            return;
        }
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
            String path = relative.substring(0, relative.length() - ".json".length());
            Identifier id;
            try {
                id = Identifier.fromNamespaceAndPath(namespace, path);
            } catch (RuntimeException e) {
                LOG.warning("Skipping " + file + ": " + namespace + ":" + path
                        + " is not a valid Identifier, so it names no registry entry");
                continue;
            }
            String json;
            try {
                json = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not read " + file, e);
                continue;
            }
            results.add(seam.apply(server, kind, id, json, modName));
        }
    }
}
