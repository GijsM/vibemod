package com.gijsm.vibemod.neoforge;

import java.io.IOException;
import java.lang.module.ResolvedModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.util.ClasspathResourceUtils;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.locating.IModFile;

import com.gijsm.vibemod.compile.CpCache;
import com.gijsm.vibemod.compile.JvmClasspathProvider;
import com.gijsm.vibemod.platform.ClasspathProvider;

/**
 * The compile classpath on NeoForge: the running game's own jars, nothing
 * shipped or pinned (ARCHITECTURE-V2 §0#7, §7.1).
 *
 * <p><b>The loader's classpath is not the JVM's</b>, and §10.3 told Phase E to
 * answer that question for ModLauncher the way Phase D had to answer it for
 * Knot. The answer is the same shape and the sources are different. On a real
 * NeoForge server {@code java.class.path} holds the bootstrap jar and nothing
 * else: FML builds a module layer for the game and its ~60 libraries itself, and
 * those are game libraries, not mods, so walking {@code ModList} does not reach
 * them either. Four sources are unioned, in the order below, because "VibeMod
 * cannot compile anything" is the worst failure this mod has and one authority
 * is not enough:
 *
 * <ol>
 *   <li><b>the game module layer</b> — {@code FMLLoader.getGameLayer()}, whose
 *       resolved modules' locations are exactly what ModLauncher loaded. This is
 *       the ModLauncher equivalent of Knot's {@code FabricLauncher#getClassPath()},
 *       and unlike that one it is public API;</li>
 *   <li><b>every mod file FML knows about</b> — the patched game jar, the
 *       NeoForge universal jar, VibeMod's own jar (which is where the sdk lives)
 *       and every other installed mod, via {@code JarContents#getContentRoots()}.
 *       Content roots rather than {@code getFilePath()} on purpose: a mod loaded
 *       from a directory in dev has several, and the file path is then a
 *       directory that names none of them;</li>
 *   <li><b>{@code ClasspathResourceUtils.getAllClasspathItems}</b>, FML's own
 *       enumeration of the loading class loader, which covers the dev launch
 *       where nothing is a mod file at all;</li>
 *   <li><b>a walk of {@code libraries/}</b> beside the game directory — the tree
 *       the NeoForge installer extracts — plus {@code java.class.path} and the
 *       code source of {@code MinecraftServer}, as the last belt.</li>
 * </ol>
 *
 * <p>Everything a loader hands back goes through {@link LoaderUris} first (§7.1:
 * {@code union:} and friends) and then through {@link CpCache} (§7.2), which is
 * the part that matters most here. VibeMod's own jar contains ECJ and Adventure
 * as Jar-in-Jar entries; FML mounts those through a nested filesystem, so their
 * {@link Path}s are perfectly usable by the loader and completely unusable by
 * javac. The cache materializes them once, content-addressed, and hands back
 * real files.
 */
public final class NeoForgeClasspathProvider implements ClasspathProvider {

    private static final Logger LOG = Logger.getLogger(NeoForgeClasspathProvider.class.getName());

    private final CpCache cache;
    private volatile List<Path> cached;

    public NeoForgeClasspathProvider(Path dataFolder) {
        this.cache = new CpCache(dataFolder.resolve("cpcache"));
    }

    @Override
    public List<Path> compileClasspath() {
        List<Path> result = cached;
        if (result == null) {
            result = cache.materialize(origins());
            cached = result;
            LOG.info("Compile classpath: " + result.size() + " entries");
        }
        return result;
    }

    /** Every path the loader can tell us about, before the cache gets to it. */
    private List<Path> origins() {
        Set<Path> entries = new LinkedHashSet<>();
        addGameLayer(entries);
        addModFiles(entries);
        addLoaderClasspath(entries);
        entries.addAll(new JvmClasspathProvider().compileClasspath());
        entries.addAll(libraryJars(gameDir()));

        Path game = JvmClasspathProvider.codeSourceOf("net.minecraft.server.MinecraftServer");
        if (game != null) {
            entries.add(game);
        }
        entries.remove(null);
        return new ArrayList<>(entries);
    }

    /**
     * The modules ModLauncher actually resolved for the game.
     *
     * <p>A module's location is a {@link java.net.URI} and may be any of the
     * jar-ish forms {@link LoaderUris} knows, which is the one place §7.1's
     * {@code union:} translation could still be needed on a loader that no
     * longer uses SecureJarHandler anywhere else.
     */
    private static void addGameLayer(Set<Path> into) {
        try {
            ModuleLayer layer = FMLLoader.getCurrent().getGameLayer();
            for (ResolvedModule module : layer.configuration().modules()) {
                module.reference().location()
                        .map(LoaderUris::toPath)
                        .ifPresent(into::add);
            }
            LOG.fine("Game module layer contributed " + into.size() + " entries");
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Could not read the game module layer; "
                    + "falling back to ModList and the libraries/ walk", t);
        }
    }

    /** Every mod file FML loaded, by its content roots. */
    private static void addModFiles(Set<Path> into) {
        try {
            for (IModFileInfo info : ModList.get().getModFiles()) {
                IModFile file = info == null ? null : info.getFile();
                if (file == null) {
                    continue;
                }
                try {
                    into.addAll(file.getContents().getContentRoots());
                } catch (Throwable t) {
                    // A mod file whose contents cannot be enumerated still has a
                    // path, and one entry is better than none.
                    Path path = file.getFilePath();
                    if (path != null) {
                        into.add(path);
                    }
                }
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Could not enumerate mod files", t);
        }
    }

    /** FML's own view of the class loader that loaded us. */
    private static void addLoaderClasspath(Set<Path> into) {
        try {
            into.addAll(ClasspathResourceUtils.getAllClasspathItems(
                    NeoForgeClasspathProvider.class.getClassLoader()));
        } catch (Throwable t) {
            LOG.log(Level.FINE, "Could not enumerate the loader classpath", t);
        }
    }

    private static Path gameDir() {
        try {
            return FMLLoader.getCurrent().getGameDir();
        } catch (Throwable t) {
            return Path.of(".");
        }
    }

    /**
     * Every {@code .jar} under {@code libraries/}, the tree the NeoForge
     * installer extracts beside the game directory. The belt to the module
     * layer's braces, and the same walk {@code PaperClasspathProvider} does for
     * paperclip and {@code FabricClasspathProvider} does for Fabric's launcher.
     */
    private static List<Path> libraryJars(Path gameDir) {
        Path root = gameDir == null ? Path.of("libraries") : gameDir.resolve("libraries");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> jars = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith(".jar"))
                    .forEach(jars::add);
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not walk " + root, e);
        }
        return jars;
    }
}
