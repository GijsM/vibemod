package com.gijsm.vibemod.compile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The extract-once, content-addressed classpath cache (ARCHITECTURE-V2 §7.2).
 *
 * <p>javac and ECJ read classpath entries as real files on a real filesystem.
 * Knot and ModLauncher do not have to: a Fabric mod's Jar-in-Jar dependencies
 * live <em>inside</em> another jar, reached through a nested {@code ZipFileSystem}
 * whose {@link Path}s are not {@code sun.nio.fs} paths and cannot be handed to a
 * compiler. NeoForge goes further and serves {@code union:} paths spanning
 * several roots. Both are perfectly good {@code Path}s and completely useless as
 * {@code -classpath} entries.
 *
 * <p>So every origin that is not already a plain readable {@code .jar} file on
 * the default filesystem is materialized once into
 * {@code <dataFolder>/cpcache/<first-16-hex-of-sha256>.jar} and reused from
 * there. Content addressing gives three things for free: the same nested jar
 * from two mods is stored once, a cached file survives restarts, and an upgraded
 * dependency simply lands under a new name instead of being silently stale.
 * Orphans — entries no live classpath still names — are pruned at the end of
 * each assembly.
 *
 * <p>Not on Paper: everything {@code PaperClasspathProvider} yields is already a
 * plain file. This is the loaders' concern, which is why it arrived in Phase D.
 */
public final class CpCache {

    private static final Logger LOG = Logger.getLogger(CpCache.class.getName());

    /** How many hex characters of the SHA-256 name a cached file. 16 = 64 bits. */
    private static final int NAME_HEX_CHARS = 16;

    /**
     * How long an orphaned {@code .partial} has to sit before {@link #prune} will
     * delete it.
     *
     * <p>A {@code .partial} is a temp file some thread is <em>writing right
     * now</em>, and {@link #cached} deletes its own in a {@code finally}. Two
     * mods restoring at boot materialize the classpath concurrently, so a prune
     * that deleted every {@code .partial} it did not recognise was deleting the
     * other thread's live temp file — after which its {@code Files.move} failed
     * with {@code NoSuchFileException}, the entry was dropped from the classpath
     * with a warning, and the mod failed to compile against a library that was
     * right there. The one-hour floor keeps the leak fixed (a JVM that dies
     * mid-write still gets cleaned up eventually) without racing anything alive.
     */
    private static final long PARTIAL_ORPHAN_AGE_MS = 60 * 60 * 1000L;

    private final Path cacheDir;

    public CpCache(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /**
     * Materializes {@code origins} into paths a compiler can open, in order and
     * without duplicates, then prunes anything else in the cache directory.
     *
     * <p>An origin that cannot be materialized is dropped with a warning rather
     * than failing the whole classpath: one unreadable nested jar should cost the
     * generated mod one library, not every library.
     */
    public List<Path> materialize(List<Path> origins) {
        // Keyed by the resolved target so two origins hashing the same content
        // (the same JiJ'd jar shipped by two mods) collapse to one entry.
        Map<Path, Path> resolved = new LinkedHashMap<>();
        Set<String> keep = new HashSet<>();
        for (Path origin : origins) {
            if (origin == null) {
                continue;
            }
            Path usable;
            try {
                usable = materializeOne(origin);
            } catch (Exception | LinkageError e) {
                LOG.log(Level.WARNING, "Skipping classpath entry that could not be cached: " + origin, e);
                continue;
            }
            if (usable == null) {
                continue;
            }
            if (usable.startsWith(cacheDir)) {
                keep.add(usable.getFileName().toString());
            }
            resolved.putIfAbsent(usable, origin);
        }
        prune(keep);
        return List.copyOf(new ArrayList<>(new LinkedHashSet<>(resolved.keySet())));
    }

    /**
     * One origin to something a compiler can read: itself when it already is a
     * plain readable jar, else a cached copy. Directories are zipped rather than
     * passed through, because a directory reached through a nested filesystem is
     * exactly as unreadable to javac as a nested jar is.
     */
    private Path materializeOne(Path origin) throws IOException {
        if (isUsableInPlace(origin)) {
            return origin;
        }
        if (Files.isDirectory(origin)) {
            return cached(hashOfDirectory(origin), target -> zipDirectory(origin, target));
        }
        if (!Files.isReadable(origin)) {
            LOG.fine("Classpath origin is not readable, skipping: " + origin);
            return null;
        }
        return cached(hashOfFile(origin), target -> {
            try (InputStream in = Files.newInputStream(origin)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        });
    }

    /**
     * Whether a compiler can be handed this path directly: a plain readable
     * {@code .jar} on the <em>default</em> filesystem. The filesystem check is
     * the load-bearing half — a nested jar's path also ends in {@code .jar} and
     * also reports as a regular file, and only its {@code FileSystem} gives it
     * away.
     */
    private static boolean isUsableInPlace(Path path) {
        if (path.getFileSystem() != java.nio.file.FileSystems.getDefault()) {
            return false;
        }
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        return name.endsWith(".jar") && Files.isRegularFile(path) && Files.isReadable(path);
    }

    /** Writes {@code producer}'s output to {@code <cacheDir>/<hash>.jar}, unless it is already there. */
    private Path cached(String hash, IoConsumer producer) throws IOException {
        Files.createDirectories(cacheDir);
        Path target = cacheDir.resolve(hash + ".jar");
        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            return target;
        }
        // Write beside the target and move into place: a half-written cache entry
        // that survived a crash would be indistinguishable from a good one.
        Path tmp = Files.createTempFile(cacheDir, hash, ".partial");
        try {
            producer.accept(tmp);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target;
    }

    /**
     * Deletes every {@code .jar} in the cache directory that {@code keep} does not
     * name, plus any {@code .partial} old enough to be a crash leftover rather
     * than another thread's work in progress ({@link #PARTIAL_ORPHAN_AGE_MS}).
     */
    private void prune(Set<String> keep) {
        if (!Files.isDirectory(cacheDir)) {
            return;
        }
        long now = System.currentTimeMillis();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.endsWith(".partial")) {
                    if (!isStalePartial(entry, now)) {
                        continue;
                    }
                } else if (!name.endsWith(".jar") || keep.contains(name)) {
                    continue;
                }
                try {
                    Files.deleteIfExists(entry);
                    LOG.fine("Pruned orphaned cpcache entry " + name);
                } catch (IOException ignored) {
                    // an entry still mapped by a running compile: it will go next boot
                }
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not prune the cpcache", e);
        }
    }

    /** Whether a {@code .partial} is old enough that no live materialize can still own it. */
    private static boolean isStalePartial(Path partial, long now) {
        try {
            return now - Files.getLastModifiedTime(partial).toMillis() > PARTIAL_ORPHAN_AGE_MS;
        } catch (IOException gone) {
            // Already deleted by whoever created it. Nothing to prune.
            return false;
        }
    }

    // ---- hashing ----

    private static String hashOfFile(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream digesting = new DigestInputStream(in, digest)) {
            byte[] buffer = new byte[64 * 1024];
            while (digesting.read(buffer) >= 0) {
                // digesting as a side effect
            }
        }
        return hex(digest.digest());
    }

    /**
     * A directory's identity: every entry's relative path, size and modification
     * time, in sorted order. Deliberately not the file contents — a game's
     * exploded classes directory can be hundreds of megabytes, and this runs on
     * every boot.
     */
    private static String hashOfDirectory(Path dir) throws IOException {
        MessageDigest digest = sha256();
        List<String> lines = new ArrayList<>();
        try (var walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                long size;
                long modified;
                try {
                    size = Files.size(p);
                    modified = Files.getLastModifiedTime(p).toMillis();
                } catch (IOException skip) {
                    continue;
                }
                lines.add(dir.relativize(p).toString() + " " + size + " " + modified);
            }
        }
        lines.sort(null);
        for (String line : lines) {
            digest.update(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return hex(digest.digest());
    }

    private static void zipDirectory(Path dir, Path target) throws IOException {
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            List<Path> files = new ArrayList<>();
            try (var walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile).forEach(files::add);
            }
            files.sort(null);
            Set<String> written = new HashSet<>();
            for (Path file : files) {
                // '/' explicitly: a zip entry name is not a platform path, and on
                // Windows the relativized separator would be a backslash.
                String name = String.join("/", relativeSegments(dir, file));
                if (!written.add(name)) {
                    continue;
                }
                zip.putNextEntry(new ZipEntry(name));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    private static List<String> relativeSegments(Path root, Path file) {
        List<String> segments = new ArrayList<>();
        for (Path part : root.relativize(file)) {
            segments.add(part.toString());
        }
        return segments;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(NAME_HEX_CHARS);
        for (int i = 0; i < bytes.length && sb.length() < NAME_HEX_CHARS; i++) {
            sb.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16));
            if (sb.length() < NAME_HEX_CHARS) {
                sb.append(Character.forDigit(bytes[i] & 0xF, 16));
            }
        }
        return sb.toString();
    }

    /** {@link java.util.function.Consumer} that may throw {@link IOException}. */
    @FunctionalInterface
    private interface IoConsumer {
        void accept(Path target) throws IOException;
    }
}
