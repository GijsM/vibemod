package com.gijsm.vibemod.fabric;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import com.gijsm.vibemod.compile.CpCache;
import com.gijsm.vibemod.compile.JvmClasspathProvider;
import com.gijsm.vibemod.platform.ClasspathProvider;

/**
 * The compile classpath on Fabric: the running game's own jars, nothing shipped
 * or pinned (ARCHITECTURE-V2 §0#7, §7.1).
 *
 * <p>Sources, in order:
 * <ol>
 *   <li>the {@code minecraft} mod container — the game jar generated code is
 *       validated against, which on 26.1+ carries Mojang names directly;</li>
 *   <li>{@code fabric-loader} and every {@code fabric-*} module container, so a
 *       generated mod could at least <em>see</em> the loader API (it may not use
 *       it — the prompt bans {@code net.fabricmc.*} — but a missing class on the
 *       classpath produces a confusing diagnostic rather than an honest one);</li>
 *   <li>VibeMod's own container, which is where the sdk lives;</li>
 *   <li><b>the loader's own classpath</b>, which is the entry that actually
 *       matters on a real installation and the one this class originally
 *       missed;</li>
 *   <li>whatever {@code java.class.path} names, for the dev environment, where
 *       Loom runs the game straight off the Gradle classpath and the containers
 *       above point at build directories.</li>
 * </ol>
 *
 * <p><b>The loader's classpath is not the JVM's.</b> On a real Fabric server
 * {@code java.class.path} holds one thing — the launcher jar — because Knot
 * loads the game and all ~50 of its libraries itself. The mod containers above
 * do not cover them either: Brigadier, DataFixerUpper and the rest are game
 * libraries, not mods. The first version of this class had exactly that gap, and
 * it was invisible in the dev environment (where Gradle puts everything on
 * {@code java.class.path}) right up until the acceptance gate compiled a mod
 * calling {@code src.sendSystemMessage(...)} on a real server and got
 * {@code cannot access com.mojang.brigadier.Message}. Two independent answers
 * are used, because "VibeMod cannot compile anything" is the worst failure this
 * mod has: {@code FabricLauncher#getClassPath()}, which is the exact list Knot
 * loaded, and a walk of the {@code libraries/} tree the server launcher
 * extracts beside the game directory.
 *
 * <p>And then all of it goes through {@link CpCache}, which is the part that
 * matters here and the reason §7.2 exists. VibeMod's own jar contains Adventure
 * and ECJ as Jar-in-Jar entries; the loader mounts those through a nested
 * {@code ZipFileSystem}, so their {@link Path}s are perfectly usable by the
 * loader and completely unusable by javac. The cache materializes them once,
 * content-addressed, and hands back real files.
 *
 * <p>Fallback: if a container lookup fails entirely, the code source of
 * {@code net.minecraft.server.MinecraftServer} still pins the game jar. A
 * VibeMod that cannot find the game jar cannot compile anything, so it is worth
 * two ways of asking.
 */
public final class FabricClasspathProvider implements ClasspathProvider {

    private static final Logger LOG = Logger.getLogger(FabricClasspathProvider.class.getName());

    private final CpCache cache;
    private volatile List<Path> cached;

    public FabricClasspathProvider(Path dataFolder) {
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
        FabricLoader loader = FabricLoader.getInstance();

        addContainer(entries, loader, "minecraft");
        addContainer(entries, loader, "fabricloader");
        addContainer(entries, loader, "vibemod");
        entries.addAll(launcherClassPath());
        try {
            for (ModContainer container : loader.getAllMods()) {
                String id = container.getMetadata().getId();
                if (id.startsWith("fabric")) {
                    entries.addAll(container.getRootPaths());
                }
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Could not enumerate fabric-api modules", t);
        }

        entries.addAll(new JvmClasspathProvider().compileClasspath());
        entries.addAll(libraryJars(loader.getGameDir()));

        Path game = JvmClasspathProvider.codeSourceOf("net.minecraft.server.MinecraftServer");
        if (game != null) {
            entries.add(game);
        }
        return new ArrayList<>(entries);
    }

    /**
     * Exactly what Knot put on the game's classpath.
     *
     * <p>Reflective because {@code FabricLauncher} is loader-internal
     * ({@code net.fabricmc.loader.impl}) — there is no public API for this, and
     * the alternative is guessing which libraries a generated mod might reach.
     * A loader that renames it costs us the {@code libraries/} walk instead, not
     * a broken compiler.
     */
    private static List<Path> launcherClassPath() {
        try {
            Class<?> base = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase");
            Object launcher = base.getMethod("getLauncher").invoke(null);
            if (launcher == null) {
                return List.of();
            }
            Object paths = launcher.getClass().getMethod("getClassPath").invoke(launcher);
            if (paths instanceof List<?> list) {
                List<Path> out = new ArrayList<>(list.size());
                for (Object o : list) {
                    if (o instanceof Path path) {
                        out.add(path);
                    }
                }
                LOG.fine("Loader classpath contributed " + out.size() + " entries");
                return out;
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "Could not read the loader's classpath (" + t
                    + "); falling back to the libraries/ walk", t);
        }
        return List.of();
    }

    /**
     * Every {@code .jar} under {@code libraries/}, the tree the Fabric server
     * launcher extracts beside the game directory. The belt to
     * {@link #launcherClassPath()}'s braces, and the same walk
     * {@code PaperClasspathProvider} does for paperclip.
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

    private static void addContainer(Set<Path> into, FabricLoader loader, String modId) {
        try {
            loader.getModContainer(modId).ifPresentOrElse(
                    container -> into.addAll(container.getRootPaths()),
                    () -> LOG.warning("No mod container for '" + modId + "'"));
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Could not read the '" + modId + "' mod container", t);
        }
    }
}
