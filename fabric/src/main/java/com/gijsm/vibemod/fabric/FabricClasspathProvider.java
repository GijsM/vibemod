package com.gijsm.vibemod.fabric;

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
 *   <li>whatever {@code java.class.path} names, for the dev environment, where
 *       Loom runs the game straight off the Gradle classpath and the containers
 *       above point at build directories.</li>
 * </ol>
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

        Path game = JvmClasspathProvider.codeSourceOf("net.minecraft.server.MinecraftServer");
        if (game != null) {
            entries.add(game);
        }
        return new ArrayList<>(entries);
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
