package com.gijsm.vibemod.neoforge;

import java.io.File;
import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Turns the URIs a mod loader hands out into ordinary file paths.
 *
 * <p>ARCHITECTURE-V2 §7.1 asks Phase E for one of these specifically: translate
 * {@code union:} URLs by stripping the scheme and the {@code %23n!/} suffix. It
 * is here, unit-tested (see {@code UriSelfTest}) — and generalized, because
 * {@code union:} turned out to be the least likely of the three forms to
 * actually show up.
 *
 * <p>{@code union:} was SecureJarHandler's own scheme, and FML 11 (the loader
 * that ships with NeoForge 26.x) no longer uses SecureJarHandler at all: it has
 * {@code JarContents}, whose {@code getContentRoots()} returns real
 * {@link Path}s. A full-tree string search of {@code fml-loader-11.0.16.jar}
 * for "union" comes back empty. The translation is kept anyway because it costs
 * nine lines, because module locations are the one place a loader can still
 * hand out something exotic, and because the doc asked for it: a classpath
 * provider that silently drops an entry it cannot parse is the failure mode
 * this whole class exists to prevent.
 *
 * <p>Three forms are handled:
 * <ul>
 *   <li>{@code file:/a/b/c.jar} — the ordinary case;</li>
 *   <li>{@code jar:file:/a/b/c.jar!/} — a jar URL, with the entry part dropped
 *       (the outer jar is what a compiler wants);</li>
 *   <li>{@code union:/a/b/c.jar%23142!/} — SecureJarHandler's, whose
 *       {@code %23<n>} is a URL-escaped {@code #} plus a package-set index and
 *       whose {@code !/} is the usual entry separator. Both are noise here.</li>
 * </ul>
 */
public final class LoaderUris {

    private LoaderUris() {
    }

    /**
     * @return the file the URI names, or null when it names nothing on disk
     */
    public static Path toPath(URI uri) {
        return uri == null ? null : toPath(uri.toString());
    }

    /**
     * @param raw a URI in string form (loaders sometimes hand out strings that
     *            are not legal {@link URI}s — {@code union:} with an unescaped
     *            {@code #} is exactly that)
     * @return the file the URI names, or null when it names nothing on disk
     */
    public static Path toPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();

        // jar:file:/x.jar!/entry -> file:/x.jar
        if (s.startsWith("jar:")) {
            s = s.substring("jar:".length());
        }
        // union:/x.jar%23142!/ -> /x.jar
        if (s.startsWith("union:")) {
            s = s.substring("union:".length());
        }
        // Drop the entry part of any jar-ish URI.
        int bang = s.indexOf("!/");
        if (bang >= 0) {
            s = s.substring(0, bang);
        }
        // Drop SecureJarHandler's package-set index, escaped or not.
        int hash = s.indexOf("%23");
        if (hash >= 0) {
            s = s.substring(0, hash);
        }
        hash = s.indexOf('#');
        if (hash >= 0) {
            s = s.substring(0, hash);
        }

        if (s.startsWith("file:")) {
            try {
                return Path.of(new File(URI.create(s)).toURI());
            } catch (IllegalArgumentException e) {
                // Fall through to the plain-path reading below: a "file:" URI
                // with a Windows drive letter or a space is common enough that
                // giving up here would be worse than guessing.
                s = s.substring("file:".length());
                while (s.startsWith("//")) {
                    s = s.substring(1);
                }
            }
        }
        try {
            return Path.of(s);
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
