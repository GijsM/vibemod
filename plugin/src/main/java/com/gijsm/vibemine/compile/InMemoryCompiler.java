package com.gijsm.vibemine.compile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compiles generated mod source in-process using the JDK's system Java compiler,
 * capturing all resulting bytecode (including inner/anonymous classes) directly
 * into memory. Never throws on bad input; compilation failures are reported via
 * {@link CompileResult}.
 */
public final class InMemoryCompiler {

    private final List<Path> extraClasspath;

    /** extraClasspath entries are appended after the auto-detected paper jar + VibeCore jar. */
    public InMemoryCompiler(Path... extraClasspath) {
        this.extraClasspath = List.of(extraClasspath);
    }

    /** true if a system java compiler is available (server started from a full JDK). */
    public static boolean available() {
        return ToolProvider.getSystemJavaCompiler() != null;
    }

    /** sources: fully-qualified class name -> source text. Never throws on bad source; returns failure result. */
    public CompileResult compile(Map<String, String> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new CompileResult(false, Map.of(),
                    "No system Java compiler available: VibeMine must run on a full JDK, not a JRE.");
        }

        List<JavaFileObject> compilationUnits = new ArrayList<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            compilationUnits.add(new SourceFileObject(entry.getKey(), entry.getValue()));
        }

        Map<String, ByteArrayOutputStream> outputs = new LinkedHashMap<>();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        StandardJavaFileManager standardFileManager =
                compiler.getStandardFileManager(diagnostics, null, java.nio.charset.StandardCharsets.UTF_8);

        try (InMemoryFileManager fileManager = new InMemoryFileManager(standardFileManager, outputs)) {
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
            return new CompileResult(false, Map.of(), "Failed to close compiler file manager: " + e.getMessage());
        }
    }

    private List<String> buildOptions() {
        List<String> options = new ArrayList<>();
        options.add("--release");
        options.add(String.valueOf(Runtime.version().feature()));
        options.add("-proc:none");
        options.add("-encoding");
        options.add("UTF-8");
        options.add("-classpath");
        options.add(buildClasspath());
        return options;
    }

    private String buildClasspath() {
        Set<String> entries = new LinkedHashSet<>();

        String existing = System.getProperty("java.class.path");
        if (existing != null && !existing.isBlank()) {
            for (String part : existing.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!part.isBlank()) {
                    entries.add(part);
                }
            }
        }

        addCodeSourceOf("org.bukkit.Bukkit", entries);

        String selfLocation = codeSourcePathOf(InMemoryCompiler.class.getProtectionDomain());
        if (selfLocation != null) {
            entries.add(selfLocation);
        }

        for (Path p : extraClasspath) {
            if (p != null) {
                entries.add(p.toString());
            }
        }

        return String.join(File.pathSeparator, entries);
    }

    private static void addCodeSourceOf(String className, Set<String> entries) {
        try {
            Class<?> clazz = Class.forName(className);
            String location = codeSourcePathOf(clazz.getProtectionDomain());
            if (location != null) {
                entries.add(location);
            }
        } catch (ClassNotFoundException e) {
            // Not running with Bukkit on the classpath (e.g. plain-JVM self-test) - skip silently.
        } catch (Throwable t) {
            // Any other reflective/security failure: skip this entry defensively.
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
        } catch (URISyntaxException | IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
            return null;
        }
    }

    private static String formatDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder sb = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            String sourceName = d.getSource() != null ? d.getSource().getName() : "<unknown>";
            sb.append('[').append(d.getKind()).append("] ")
                    .append(sourceName).append(':').append(d.getLineNumber())
                    .append(" - ").append(d.getMessage(null));
        }
        return sb.toString();
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

        ClassOutputObject(String binaryName, Map<String, ByteArrayOutputStream> outputs) {
            super(uriFor(binaryName), Kind.CLASS);
            this.binaryName = binaryName;
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

    /** Forwards to the standard file manager but redirects CLASS output into memory. */
    private static final class InMemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, ByteArrayOutputStream> outputs;

        InMemoryFileManager(StandardJavaFileManager fileManager, Map<String, ByteArrayOutputStream> outputs) {
            super(fileManager);
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
    }
}
