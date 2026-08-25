package com.gijsm.vibemod.paper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.gijsm.vibemod.compile.JvmClasspathProvider;
import com.gijsm.vibemod.platform.ClasspathProvider;

/**
 * The compile classpath on Paper: the running server's own jars, nothing
 * shipped or pinned (ARCHITECTURE-V2 §0#7, §7.1).
 *
 * <p>Three sources, in order:
 * <ol>
 *   <li>whatever {@code java.class.path} names plus VibeMod's own code source
 *       (so generated code links against the sdk inside the running plugin jar)
 *       — delegated to {@link JvmClasspathProvider};</li>
 *   <li>the code source of {@code org.bukkit.Bukkit}, which pins the exact
 *       server API the generated mod will run against;</li>
 *   <li>every jar under {@code libraries/} and {@code versions/}.</li>
 * </ol>
 *
 * <p>That third walk is the one that matters and the reason this class exists at
 * all: Paper's bundler extracts the server and all its libraries (paper-api
 * included) to disk at startup, and inside the running server
 * {@code java.class.path} holds only the paperclip bootstrap. Without the walk,
 * javac sees essentially nothing and every generated mod fails to compile.
 *
 * <p>No {@code cpcache} (§7.2) here: every path this provider yields is already
 * a plain readable jar or a directory javac can open. The extract-once cache is
 * a loader concern — nested Jar-in-Jar entries and ModLauncher {@code union:}
 * URLs — and belongs to the Fabric/NeoForge providers in Phases D/E.
 */
public final class PaperClasspathProvider implements ClasspathProvider {

    /** The paperclip layout: extracted server jars and their libraries, relative to the server root. */
    private static final String[] BUNDLER_DIRS = {"libraries", "versions"};

    private final JvmClasspathProvider jvm = new JvmClasspathProvider();
    private volatile List<Path> cached;

    @Override
    public List<Path> compileClasspath() {
        List<Path> result = cached;
        if (result == null) {
            result = assemble();
            cached = result;
        }
        return result;
    }

    private List<Path> assemble() {
        Set<Path> entries = new LinkedHashSet<>(jvm.compileClasspath());
        Path bukkit = JvmClasspathProvider.codeSourceOf("org.bukkit.Bukkit");
        if (bukkit != null) {
            entries.add(bukkit);
        }
        entries.addAll(bundlerJars());
        return List.copyOf(entries);
    }

    /** Every {@code .jar} under the server's {@code libraries/} and {@code versions/} trees. */
    private static List<Path> bundlerJars() {
        List<Path> jars = new ArrayList<>();
        for (String dir : BUNDLER_DIRS) {
            Path root = Path.of(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var stream = Files.walk(root)) {
                stream.filter(p -> p.toString().endsWith(".jar")).forEach(jars::add);
            } catch (IOException ignored) {
                // best effort - the code-source detection above also contributes the API jar
            }
        }
        return jars;
    }
}
