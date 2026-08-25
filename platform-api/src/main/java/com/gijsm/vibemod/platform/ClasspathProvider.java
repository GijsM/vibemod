package com.gijsm.vibemod.platform;

import java.nio.file.Path;
import java.util.List;

/**
 * The compile classpath for generated code: the running game's own jars —
 * never shipped or pinned API jars (ARCHITECTURE-V2 §0#7, §7.1).
 *
 * <p>Every returned path must be a plain readable file javac/ECJ can open.
 * Implementations own the extract-once content-addressed cache
 * ({@code <dataFolder>/cpcache/<sha256-16>.jar}) for origins that are not
 * plain files: nested Jar-in-Jar entries, directories, ModLauncher
 * {@code union:} paths (§7.2).
 *
 * <p>Called from the compile thread (off-main); results should be computed
 * once and cached.
 */
public interface ClasspathProvider {

    List<Path> compileClasspath();
}
