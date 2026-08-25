package com.gijsm.vibemod.compile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;

import com.gijsm.vibemod.platform.ClasspathProvider;
import com.gijsm.vibemod.platform.CompilerProvider;

/**
 * Compiles generated mod source in-process, capturing all resulting bytecode
 * (including inner/anonymous classes) directly into memory. Never throws on bad
 * input — nor on anything else: compilation failures are reported via
 * {@link CompileResult}.
 *
 * <p>Two injected seams (ARCHITECTURE-V2 §7): a {@link CompilerProvider} for
 * the backend (system javac, or a bundled ECJ where the runtime has no
 * compiler) and a {@link ClasspathProvider} for what generated code compiles
 * against. The classpath is always the running game's own jars, never a shipped
 * or pinned API jar (§0#7) — which is why assembling it is the host's job, not
 * this class's.
 */
public final class InMemoryCompiler {

    private final CompilerProvider provider;
    private final ClasspathProvider classpath;

    /**
     * Host constructor: the resolved backend plus the host's own classpath
     * provider.
     */
    public InMemoryCompiler(CompilerProvider provider, ClasspathProvider classpath) {
        this.provider = provider;
        this.classpath = classpath;
    }

    /**
     * Convenience for the self-tests and any caller happy with whatever
     * {@link CompilerProvider#resolve()} finds and this JVM's own
     * {@code java.class.path}. {@code provider} stays null when no backend
     * exists at all, which {@link #compile} reports as a failed result.
     */
    public InMemoryCompiler() {
        this(CompilerProvider.resolve().orElse(null), new JvmClasspathProvider());
    }

    /**
     * Legacy shape kept for callers that only wanted to append jars to the
     * detected classpath.
     */
    public InMemoryCompiler(Path... extraClasspath) {
        this(CompilerProvider.resolve().orElse(null),
                new JvmClasspathProvider(List.of(extraClasspath)));
    }

    /** true if some Java compiler backend is available (system javac, or a bundled ECJ). */
    public static boolean available() {
        return CompilerProvider.resolve().isPresent();
    }

    /** The backend actually in use, for the boot log. */
    public String backendName() {
        return provider == null ? "none" : provider.name();
    }

    /** sources: fully-qualified class name -> source text. Never throws on bad source; returns failure result. */
    public CompileResult compile(Map<String, String> sources) {
        if (provider == null) {
            return new CompileResult(false, Map.of(),
                    "No Java compiler available: VibeMod must run on a full JDK (not a JRE), "
                            + "or with a bundled ECJ on its classpath.");
        }
        JavaCompiler compiler = provider.compiler();

        Map<String, ByteArrayOutputStream> outputs = new LinkedHashMap<>();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        StandardJavaFileManager standardFileManager =
                compiler.getStandardFileManager(diagnostics, null, java.nio.charset.StandardCharsets.UTF_8);

        Path staging = null;
        try (InMemoryFileManager fileManager = new InMemoryFileManager(standardFileManager, outputs)) {
            List<JavaFileObject> compilationUnits;
            if (provider.acceptsInMemorySources()) {
                compilationUnits = new ArrayList<>();
                for (Map.Entry<String, String> entry : sources.entrySet()) {
                    compilationUnits.add(new SourceFileObject(entry.getKey(), entry.getValue()));
                }
            } else {
                staging = stageSources(sources);
                compilationUnits = realFileUnits(standardFileManager, staging, sources.keySet());
            }

            List<String> options = buildOptions();

            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fileManager, diagnostics, options, null, compilationUnits);

            boolean success = task.call();

            if (!success) {
                return new CompileResult(false, Map.of(), formatDiagnostics(diagnostics));
            }

            Map<String, byte[]> classes = new LinkedHashMap<>();
            for (Map.Entry<String, ByteArrayOutputStream> entry : outputs.entrySet()) {
                classes.put(entry.getKey(), entry.getValue().toByteArray());
            }
            return new CompileResult(true, classes, formatDiagnostics(diagnostics));
        } catch (IOException e) {
            return new CompileResult(false, Map.of(), "Compiler file manager failed: " + e.getMessage());
        } catch (RuntimeException | Error hostile) {
            // "compile() never throws" is a contract the self-heal loop depends on:
            // a backend that blows up mid-task is a failed round, not a crashed server.
            return new CompileResult(false, Map.of(),
                    "Compiler backend " + provider.name() + " failed: " + hostile);
        } finally {
            deleteRecursively(staging);
        }
    }

    /**
     * Writes the sources into a throwaway directory tree so a backend that
     * insists on real files can read them ({@link CompilerProvider#acceptsInMemorySources()}).
     * Only the <em>input</em> touches disk: class output still goes to memory
     * through {@link InMemoryFileManager}, and the directory is deleted before
     * {@link #compile} returns.
     */
    private static Path stageSources(Map<String, String> sources) throws IOException {
        Path root = java.nio.file.Files.createTempDirectory("vibemod-src");
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Path target = root.resolve(entry.getKey().replace('.', '/') + ".java");
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.writeString(target, entry.getValue(), java.nio.charset.StandardCharsets.UTF_8);
        }
        return root;
    }

    private static List<JavaFileObject> realFileUnits(StandardJavaFileManager manager, Path root,
                                                      Iterable<String> fqcns) {
        List<java.io.File> files = new ArrayList<>();
        for (String fqcn : fqcns) {
            files.add(root.resolve(fqcn.replace('.', '/') + ".java").toFile());
        }
        List<JavaFileObject> units = new ArrayList<>();
        for (JavaFileObject unit : manager.getJavaFileObjectsFromFiles(files)) {
            units.add(unit);
        }
        return units;
    }

    /** Best-effort cleanup of a staging directory; a leftover temp dir must never fail a compile. */
    private static void deleteRecursively(Path root) {
        if (root == null) {
            return;
        }
        try (var walk = java.nio.file.Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    java.nio.file.Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /**
     * {@code --release} is clamped to {@code min(runtime, backend ceiling)}
     * (ARCHITECTURE-V2 §7.3): targeting a release the running JVM cannot load
     * is pointless, and asking a backend for a release it never heard of is a
     * hard failure rather than a diagnostic.
     */
    private List<String> buildOptions() {
        int release = Math.min(Runtime.version().feature(), provider.maxSupportedRelease());
        List<String> options = new ArrayList<>();
        options.add("--release");
        options.add(String.valueOf(release));
        options.add("-proc:none");
        options.add("-encoding");
        options.add("UTF-8");
        options.add("-classpath");
        options.add(buildClasspath());
        return options;
    }

    private String buildClasspath() {
        Set<String> entries = new LinkedHashSet<>();
        for (Path p : classpath.compileClasspath()) {
            if (p != null) {
                entries.add(p.toString());
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    /**
     * Renders the collected diagnostics.
     *
     * <p>The {@link List#copyOf} is load-bearing, not tidiness:
     * {@link DiagnosticCollector#getDiagnostics()} hands back a live view of the
     * collector's own list, and {@link Diagnostic#getMessage} can drive javac
     * far enough to append to it (deferred diagnostics, mandatory-warning
     * aggregation) — iterating the view while formatting it can therefore throw
     * {@link java.util.ConcurrentModificationException} straight out of
     * {@link #compile}, which is documented never to throw
     * (ARCHITECTURE-V2 §10.1). Snapshot first, format second. Formatting is
     * additionally fail-safe: a backend whose diagnostics cannot be rendered
     * must not lose us the compile result.
     */
    public static String formatDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        List<Diagnostic<? extends JavaFileObject>> snapshot;
        try {
            snapshot = List.copyOf(diagnostics.getDiagnostics());
        } catch (RuntimeException e) {
            return "(diagnostics unavailable: " + e + ")";
        }
        return formatDiagnostics(snapshot);
    }

    /** {@link #formatDiagnostics(DiagnosticCollector)} over an already-snapshotted list. */
    public static String formatDiagnostics(List<? extends Diagnostic<? extends JavaFileObject>> snapshot) {
        StringBuilder sb = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> d : snapshot) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            String sourceName;
            try {
                sourceName = d.getSource() != null ? d.getSource().getName() : "<unknown>";
            } catch (RuntimeException e) {
                sourceName = "<unknown>";
            }
            String message;
            try {
                message = d.getMessage(null);
            } catch (RuntimeException e) {
                message = "(unrenderable diagnostic: " + e + ")";
            }
            sb.append('[').append(d.getKind()).append("] ")
                    .append(sourceName).append(':').append(d.getLineNumber())
                    .append(" - ").append(message);
        }
        return sb.toString();
    }

    /** The backend a bare {@link #InMemoryCompiler()} would pick, for logging before one is built. */
    public static Optional<CompilerProvider> defaultProvider() {
        return CompilerProvider.resolve();
    }

    /** In-memory source file backed by a String, named per the FQCN. */
    private static final class SourceFileObject extends SimpleJavaFileObject {
        private final String content;

        SourceFileObject(String fqcn, String content) {
            super(uriFor(fqcn), Kind.SOURCE);
            this.content = content;
        }

        private static URI uriFor(String fqcn) {
            String path = "/" + fqcn.replace('.', '/') + Kind.SOURCE.extension;
            return URI.create("string://" + path);
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }

    /** In-memory class output backed by a ByteArrayOutputStream, keyed by binary class name. */
    private static final class ClassOutputObject extends SimpleJavaFileObject {
        private final String binaryName;
        private final Map<String, ByteArrayOutputStream> outputs;

        ClassOutputObject(String className, Map<String, ByteArrayOutputStream> outputs) {
            super(uriFor(className), Kind.CLASS);
            // javac hands us a dotted binary name; ECJ hands us "p/A". The class
            // loader wants the dotted form, and the difference is invisible until a
            // mod fails to load with a ClassNotFoundException for a class we
            // definitely compiled.
            this.binaryName = className.replace('/', '.');
            this.outputs = outputs;
        }

        private static URI uriFor(String binaryName) {
            String path = "/" + binaryName.replace('.', '/') + Kind.CLASS.extension;
            return URI.create("string://" + path);
        }

        @Override
        public OutputStream openOutputStream() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            outputs.put(binaryName, baos);
            return baos;
        }
    }

    /**
     * Forwards to the standard file manager but redirects CLASS output into memory.
     *
     * <p>It implements {@link StandardJavaFileManager} rather than just
     * {@link JavaFileManager}, and that is not cosmetic: ECJ inspects the file
     * manager it is handed and, when it is not a {@code StandardJavaFileManager},
     * falls back to treating every compilation unit's {@code getName()} as a path
     * on disk — which for our {@code string://} source objects fails with
     * "File /vibemod/.../Foo.java is missing" before compilation even starts.
     * That is exactly the "ECJ's file manager vs our InMemoryFileManager" risk
     * ARCHITECTURE-V2 §7.3 flagged; declaring the fuller interface and delegating
     * is the whole fix, and it costs javac nothing.
     */
    private static final class InMemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager>
            implements StandardJavaFileManager {

        private final StandardJavaFileManager delegate;
        private final Map<String, ByteArrayOutputStream> outputs;

        InMemoryFileManager(StandardJavaFileManager fileManager, Map<String, ByteArrayOutputStream> outputs) {
            super(fileManager);
            this.delegate = fileManager;
            this.outputs = outputs;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(JavaFileManager.Location location, String className,
                                                    JavaFileObject.Kind kind, javax.tools.FileObject sibling) {
            if (kind == JavaFileObject.Kind.CLASS) {
                return new ClassOutputObject(className, outputs);
            }
            try {
                return super.getJavaFileForOutput(location, className, kind, sibling);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        // ---- StandardJavaFileManager: straight delegation ----

        @Override
        public Iterable<? extends JavaFileObject> getJavaFileObjectsFromFiles(
                Iterable<? extends java.io.File> files) {
            return delegate.getJavaFileObjectsFromFiles(files);
        }

        @Override
        public Iterable<? extends JavaFileObject> getJavaFileObjects(java.io.File... files) {
            return delegate.getJavaFileObjects(files);
        }

        @Override
        public Iterable<? extends JavaFileObject> getJavaFileObjectsFromStrings(Iterable<String> names) {
            return delegate.getJavaFileObjectsFromStrings(names);
        }

        @Override
        public Iterable<? extends JavaFileObject> getJavaFileObjects(String... names) {
            return delegate.getJavaFileObjects(names);
        }

        @Override
        public Iterable<? extends JavaFileObject> getJavaFileObjectsFromPaths(
                Iterable<? extends java.nio.file.Path> paths) {
            return delegate.getJavaFileObjectsFromPaths(paths);
        }

        @Override
        public Iterable<? extends JavaFileObject> getJavaFileObjects(java.nio.file.Path... paths) {
            return delegate.getJavaFileObjects(paths);
        }

        @Override
        public void setLocation(JavaFileManager.Location location, Iterable<? extends java.io.File> files)
                throws IOException {
            delegate.setLocation(location, files);
        }

        @Override
        public void setLocationFromPaths(JavaFileManager.Location location,
                                         Collection<? extends java.nio.file.Path> paths) throws IOException {
            delegate.setLocationFromPaths(location, paths);
        }

        @Override
        public Iterable<? extends java.io.File> getLocation(JavaFileManager.Location location) {
            return delegate.getLocation(location);
        }

        @Override
        public Iterable<? extends java.nio.file.Path> getLocationAsPaths(JavaFileManager.Location location) {
            return delegate.getLocationAsPaths(location);
        }

        @Override
        public java.nio.file.Path asPath(javax.tools.FileObject file) {
            return delegate.asPath(file);
        }
    }
}
