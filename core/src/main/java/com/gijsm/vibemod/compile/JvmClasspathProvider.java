package com.gijsm.vibemod.compile;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.gijsm.vibemod.platform.ClasspathProvider;

/**
 * The platform-free half of "the live game is the compile classpath"
 * (ARCHITECTURE-V2 §0#7, §7.1): whatever {@code java.class.path} names, plus
 * the code source VibeMod's own classes came from (so generated code can see
 * the sdk that lives inside the host jar).
 *
 * <p>This is the fallback and the self-tests' provider. A real host wraps or
 * replaces it with one that knows where its game jars actually are — on Paper
 * that is {@code PaperClasspathProvider}, whose paperclip {@code libraries/}
 * and {@code versions/} walk is the part that cannot live here.
 */
public final class JvmClasspathProvider implements ClasspathProvider {

    private final List<Path> extra;
    private volatile List<Path> cached;

    public JvmClasspathProvider(List<Path> extra) {
        this.extra = List.copyOf(extra);
    }

    public JvmClasspathProvider() {
        this(List.of());
    }

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
        Set<String> entries = new LinkedHashSet<>();
        String existing = System.getProperty("java.class.path");
        if (existing != null && !existing.isBlank()) {
            for (String part : existing.split(Pattern.quote(File.pathSeparator))) {
                if (!part.isBlank()) {
                    entries.add(part);
                }
            }
        }
        String self = codeSourcePathOf(JvmClasspathProvider.class.getProtectionDomain());
        if (self != null) {
            entries.add(self);
        }
        List<Path> paths = new ArrayList<>(entries.size() + extra.size());
        for (String entry : entries) {
            paths.add(Path.of(entry));
        }
        for (Path p : extra) {
            if (p != null && !paths.contains(p)) {
                paths.add(p);
            }
        }
        return List.copyOf(paths);
    }

    /**
     * Where {@code className}'s defining jar/directory lives, or {@code null}
     * when the class is absent or its location is not a real file. Hosts use it
     * to pin the running game's own API jar.
     */
    public static Path codeSourceOf(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            String location = codeSourcePathOf(clazz.getProtectionDomain());
            return location == null ? null : Path.of(location);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Throwable t) {
            // any other reflective/security failure: skip this entry defensively
            return null;
        }
    }

    private static String codeSourcePathOf(ProtectionDomain domain) {
        if (domain == null) {
            return null;
        }
        CodeSource codeSource = domain.getCodeSource();
        if (codeSource == null) {
            return null;
        }
        URL location = codeSource.getLocation();
        if (location == null) {
            return null;
        }
        try {
            return Paths.get(location.toURI()).toString();
        } catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException e) {
            return null;
        }
    }
}
