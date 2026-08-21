package com.gijsm.vibemine.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import com.gijsm.vibemine.compile.CompileResult;
import com.gijsm.vibemine.compile.InMemoryCompiler;
import com.gijsm.vibemine.gen.GeneratedProject.ConfigKnob;

/**
 * Exports a stored mod as a standalone Paper plugin jar that can run on any
 * Paper server without VibeCore installed.
 *
 * The export compiles a small generated {@code JavaPlugin} wrapper alongside
 * the mod's own sources, embeds copies of the three {@code com.gijsm.vibemine.api}
 * classes so the jar is self-contained, and writes a {@code plugin.yml} plus the
 * full source tree next to the jar.
 *
 * When the mod declares config knobs, the wrapper's standalone {@code VibeContext}
 * reads them live from the exported plugin's own {@code config.yml} (each knob's
 * default baked into the generated source as the fallback argument), and the jar
 * embeds a {@code config.yml} seeded with the mod's current resolved values, one
 * {@code # <description>} comment line per key. A mod with no knobs exports
 * exactly as v1: no {@code config.yml} entry is written at all (an empty/comment-only
 * file would be harmless but adds nothing, so it is simply omitted).
 */
public final class JarExporter {

    private static final String[] API_CLASSES = {"VibeMod", "VibeContext", "ModCommandHandler"};

    private final InMemoryCompiler compiler;

    public JarExporter(InMemoryCompiler compiler) {
        this.compiler = compiler;
    }

    /**
     * Standalone Paper plugin jar. Embeds compiled mod classes, copies of the three
     * api classes, a generated {@code <Name>ExportPlugin} wrapper with a standalone
     * {@code VibeContext} impl, and a generated {@code plugin.yml}. Also writes the
     * source tree next to it as {@code <Name>-src/}. Returns the jar path.
     */
    public Path export(ModStore.StoredMod mod, Map<String, String> sources, Path outDir) throws Exception {
        String name = mod.name();
        String packageName = "vibemod." + name.toLowerCase(java.util.Locale.ROOT);
        String wrapperSimpleName = name + "ExportPlugin";
        String wrapperFqcn = packageName + "." + wrapperSimpleName;

        List<ConfigKnob> knobs = mod.config() == null ? List.of() : mod.config();
        String wrapperSource = buildWrapperSource(packageName, wrapperSimpleName, name, mod.mainClass(), knobs);

        Map<String, String> compileUnits = new LinkedHashMap<>(sources);
        compileUnits.put(wrapperFqcn, wrapperSource);

        CompileResult result = compiler.compile(compileUnits);
        if (!result.success()) {
            throw new IllegalStateException("Export compilation failed for mod '" + name + "':\n" + result.diagnostics());
        }

        Map<String, byte[]> apiClasses = loadApiClasses();

        Files.createDirectories(outDir);
        Path jarPath = outDir.resolve(name + "-" + mod.currentVersion() + ".jar");
        String pluginYml = buildPluginYml(name, mod, wrapperFqcn);
        String configYml = knobs.isEmpty() ? null : buildConfigYml(knobs, resolvedValues(mod));

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            writeEntry(jos, "plugin.yml", pluginYml.getBytes(StandardCharsets.UTF_8));
            if (configYml != null) {
                writeEntry(jos, "config.yml", configYml.getBytes(StandardCharsets.UTF_8));
            }
            for (Map.Entry<String, byte[]> e : result.classes().entrySet()) {
                writeEntry(jos, e.getKey().replace('.', '/') + ".class", e.getValue());
            }
            for (Map.Entry<String, byte[]> e : apiClasses.entrySet()) {
                writeEntry(jos, "com/gijsm/vibemine/api/" + e.getKey() + ".class", e.getValue());
            }
        }

        Path srcDir = outDir.resolve(name + "-src");
        writeSourceTree(srcDir, sources, wrapperFqcn, wrapperSource, mod);

        return jarPath;
    }

    // -- jar assembly --

    private static void writeEntry(JarOutputStream jos, String entryName, byte[] data) throws IOException {
        JarEntry entry = new JarEntry(entryName);
        jos.putNextEntry(entry);
        jos.write(data);
        jos.closeEntry();
    }

    private static String buildPluginYml(String name, ModStore.StoredMod mod, String wrapperFqcn) {
        StringBuilder sb = new StringBuilder();
        sb.append("name: ").append(name).append('\n');
        sb.append("version: ").append(mod.currentVersion()).append(".0\n");
        sb.append("main: ").append(wrapperFqcn).append('\n');
        sb.append("api-version: '1.21'\n");
        sb.append("description: ").append(yamlQuote(mod.description())).append('\n');
        sb.append("author: ").append(yamlQuote(mod.creator())).append('\n');
        return sb.toString();
    }

    private static String yamlQuote(String value) {
        String v = value == null ? "" : value;
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Schema defaults overlaid with the mod's currently stored config values. */
    private static Map<String, String> resolvedValues(ModStore.StoredMod mod) {
        Map<String, String> result = new LinkedHashMap<>();
        List<ConfigKnob> config = mod.config() == null ? List.of() : mod.config();
        for (ConfigKnob k : config) {
            result.put(k.key(), k.def() == null ? "" : k.def());
        }
        Map<String, String> stored = mod.configValues() == null ? Map.of() : mod.configValues();
        result.putAll(stored);
        return result;
    }

    /** Builds a {@code config.yml} body: one {@code # <description>} line then {@code key: value} per knob. */
    private static String buildConfigYml(List<ConfigKnob> knobs, Map<String, String> resolved) {
        StringBuilder sb = new StringBuilder();
        for (ConfigKnob k : knobs) {
            String description = k.description() == null ? "" : k.description();
            if (!description.isBlank()) {
                for (String line : description.split("\n", -1)) {
                    sb.append("# ").append(line).append('\n');
                }
            }
            String value = resolved.getOrDefault(k.key(), k.def() == null ? "" : k.def());
            sb.append(k.key()).append(": ").append(yamlValue(k, value)).append('\n');
        }
        return sb.toString();
    }

    private static String yamlValue(ConfigKnob knob, String value) {
        String type = knob.type() == null ? "text" : knob.type().toLowerCase(Locale.ROOT);
        switch (type) {
            case "boolean":
                return (value != null && value.trim().equalsIgnoreCase("true")) ? "true" : "false";
            case "integer":
                try {
                    return Long.toString(Long.parseLong(value.trim()));
                } catch (Exception e) {
                    return "0";
                }
            case "decimal":
                try {
                    return Double.toString(Double.parseDouble(value.trim()));
                } catch (Exception e) {
                    return "0.0";
                }
            case "text":
            case "choice":
            default:
                return yamlQuote(value);
        }
    }

    // -- api class byte loading --

    /**
     * Loads the three api interface classes as bytecode so they can be embedded in
     * the export jar. Prefers reading them as classloader resources (works whether
     * VibeCore itself is running from a jar or, as in tests, from a directory of
     * .class files); falls back to reading them directly out of VibeCore's own code
     * source (jar file or classes directory) if the resource lookup fails.
     */
    private Map<String, byte[]> loadApiClasses() throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        ClassLoader cl = JarExporter.class.getClassLoader();
        for (String simple : API_CLASSES) {
            byte[] bytes = readResource(cl, "com/gijsm/vibemine/api/" + simple + ".class");
            if (bytes == null) {
                bytes = readFromCodeSource(simple);
            }
            if (bytes == null) {
                throw new IOException("Could not locate compiled bytes for api class " + simple);
            }
            result.put(simple, bytes);

            // Defensive scan for nested/anonymous class variants (none of the three
            // interfaces currently declare any, but export must not silently drop them
            // if that ever changes).
            for (int i = 1; i <= 4; i++) {
                byte[] nested = readResource(cl, "com/gijsm/vibemine/api/" + simple + "$" + i + ".class");
                if (nested != null) {
                    result.put(simple + "$" + i, nested);
                }
            }
        }
        return result;
    }

    private static byte[] readResource(ClassLoader cl, String resourcePath) {
        try (InputStream in = cl.getResourceAsStream(resourcePath)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] readFromCodeSource(String simpleName) {
        try {
            var domain = JarExporter.class.getProtectionDomain();
            var codeSource = domain == null ? null : domain.getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return null;
            }
            Path location = Paths.get(codeSource.getLocation().toURI());
            String entryPath = "com/gijsm/vibemine/api/" + simpleName + ".class";
            if (Files.isDirectory(location)) {
                Path classFile = location.resolve(entryPath);
                return Files.exists(classFile) ? Files.readAllBytes(classFile) : null;
            }
            if (Files.isRegularFile(location)) {
                try (JarFile jarFile = new JarFile(location.toFile())) {
                    JarEntry entry = jarFile.getJarEntry(entryPath);
                    if (entry == null) {
                        return null;
                    }
                    try (InputStream in = jarFile.getInputStream(entry)) {
                        return in.readAllBytes();
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    // -- source tree --

    private static void writeSourceTree(Path srcDir, Map<String, String> modSources, String wrapperFqcn,
                                         String wrapperSource, ModStore.StoredMod mod) throws IOException {
        Files.createDirectories(srcDir);
        Map<String, String> all = new LinkedHashMap<>(modSources);
        all.put(wrapperFqcn, wrapperSource);
        for (Map.Entry<String, String> e : all.entrySet()) {
            Path target = srcDir.resolve(e.getKey().replace('.', '/') + ".java");
            Files.createDirectories(target.getParent());
            Files.writeString(target, e.getValue(), StandardCharsets.UTF_8);
        }

        String readme = "VibeMine export: " + mod.name() + " (version " + mod.currentVersion() + ")\n"
                + "Generated " + Instant.now() + " by com.gijsm.vibemine.store.JarExporter.\n\n"
                + "This directory holds the full Java source for the exported mod, including the\n"
                + "generated standalone plugin wrapper (" + wrapperFqcn + ") that hosts it outside\n"
                + "VibeCore. It is a plain source tree rooted at the package directory, e.g.\n"
                + "javac-compilable against paper-api plus the three com.gijsm.vibemine.api classes\n"
                + "that are embedded in the sibling jar.\n";
        Files.writeString(srcDir.resolve("README.txt"), readme, StandardCharsets.UTF_8);
    }

    // -- wrapper source generation --

    private static String buildWrapperSource(String packageName, String wrapperSimpleName, String modName,
                                              String mainClassSimpleName, List<ConfigKnob> knobs) {
        String prefix = modName.toLowerCase(java.util.Locale.ROOT);
        return "package " + packageName + ";\n\n"
                + "import com.gijsm.vibemine.api.ModCommandHandler;\n"
                + "import com.gijsm.vibemine.api.VibeContext;\n"
                + "import com.gijsm.vibemine.api.VibeMod;\n"
                + "import org.bukkit.Bukkit;\n"
                + "import org.bukkit.Server;\n"
                + "import org.bukkit.command.Command;\n"
                + "import org.bukkit.command.CommandSender;\n"
                + "import org.bukkit.event.HandlerList;\n"
                + "import org.bukkit.event.Listener;\n"
                + "import org.bukkit.plugin.Plugin;\n"
                + "import org.bukkit.plugin.java.JavaPlugin;\n"
                + "import org.bukkit.scheduler.BukkitTask;\n\n"
                + "import java.nio.file.Path;\n"
                + "import java.util.ArrayList;\n"
                + "import java.util.List;\n"
                + "import java.util.logging.Logger;\n\n"
                + "/**\n"
                + " * Standalone export wrapper for VibeMine mod \"" + modName + "\": a real Paper plugin\n"
                + " * hosting a single mod outside VibeCore, with a minimal VibeContext implementation\n"
                + " * (no watchdog, plain Bukkit registration calls).\n"
                + " */\n"
                + "public final class " + wrapperSimpleName + " extends JavaPlugin {\n\n"
                + "    private final List<Listener> listeners = new ArrayList<>();\n"
                + "    private final List<BukkitTask> tasks = new ArrayList<>();\n"
                + "    private final List<Command> commands = new ArrayList<>();\n"
                + "    private VibeMod mod;\n"
                + "    private StandaloneContext ctx;\n\n"
                + "    @Override\n"
                + "    public void onEnable() {\n"
                + "        saveDefaultConfig();\n"
                + "        try {\n"
                + "            this.ctx = new StandaloneContext(this);\n"
                + "            this.mod = new " + mainClassSimpleName + "();\n"
                + "            this.mod.onEnable(ctx);\n"
                + "        } catch (Exception e) {\n"
                + "            getLogger().severe(\"Failed to enable mod " + modName + ": \" + e);\n"
                + "            getServer().getPluginManager().disablePlugin(this);\n"
                + "        }\n"
                + "    }\n\n"
                + "    @Override\n"
                + "    public void onDisable() {\n"
                + "        if (mod != null && ctx != null) {\n"
                + "            try {\n"
                + "                mod.onDisable(ctx);\n"
                + "            } catch (Exception e) {\n"
                + "                getLogger().warning(\"Error during mod disable: \" + e);\n"
                + "            }\n"
                + "        }\n"
                + "        for (Listener l : listeners) {\n"
                + "            HandlerList.unregisterAll(l);\n"
                + "        }\n"
                + "        listeners.clear();\n"
                + "        for (BukkitTask t : tasks) {\n"
                + "            try {\n"
                + "                t.cancel();\n"
                + "            } catch (Throwable ignored) {\n"
                + "            }\n"
                + "        }\n"
                + "        tasks.clear();\n"
                + "        for (Command c : commands) {\n"
                + "            try {\n"
                + "                c.unregister(Bukkit.getCommandMap());\n"
                + "            } catch (Throwable ignored) {\n"
                + "            }\n"
                + "        }\n"
                + "        commands.clear();\n"
                + "    }\n\n"
                + "    /** Standalone VibeContext: no watchdog, direct Bukkit registration calls. */\n"
                + "    private static final class StandaloneContext implements VibeContext {\n"
                + "        private final " + wrapperSimpleName + " plugin;\n\n"
                + "        StandaloneContext(" + wrapperSimpleName + " plugin) {\n"
                + "            this.plugin = plugin;\n"
                + "        }\n\n"
                + "        @Override\n"
                + "        public Plugin plugin() {\n"
                + "            return plugin;\n"
                + "        }\n\n"
                + "        @Override\n"
                + "        public Server server() {\n"
                + "            return plugin.getServer();\n"
                + "        }\n\n"
                + "        @Override\n"
                + "        public String modName() {\n"
                + "            return \"" + modName + "\";\n"
                + "        }\n\n"
                + "        @Override\n"
                + "        public Logger log() {\n"
                + "            return plugin.getLogger();\n"
                + "        }\n\n"
                + "        @Override\n"
                + "        public Path dataFolder() {\n"
                + "            plugin.getDataFolder().mkdirs();\n"
                + "            return plugin.getDataFolder().toPath();\n"
                + "        }\n\n"
                + "        @Override\n"
                + "        public void listen(Listener listener) {\n"
                + "            plugin.getServer().getPluginManager().registerEvents(listener, plugin);\n"
                + "            plugin.listeners.add(listener);\n"
                + "        }\n\n"
                + "        @Override\n"
                + "        public BukkitTask repeat(long delayTicks, long periodTicks, Runnable task) {\n"
                + "            BukkitTask t = plugin.getServer().getScheduler()\n"
                + "                    .runTaskTimer(plugin, task, delayTicks, periodTicks);\n"
                + "            plugin.tasks.add(t);\n"
                + "            return t;\n"
                + "        }\n\n"
                + "        @Override\n"
                + "        public BukkitTask later(long delayTicks, Runnable task) {\n"
                + "            BukkitTask t = plugin.getServer().getScheduler().runTaskLater(plugin, task, delayTicks);\n"
                + "            plugin.tasks.add(t);\n"
                + "            return t;\n"
                + "        }\n\n"
                + "        @Override\n"
                + "        public void command(String name, String description, ModCommandHandler handler) {\n"
                + "            try {\n"
                + "                Command cmd = new Command(name, description, \"/\" + name, java.util.List.of()) {\n"
                + "                    @Override\n"
                + "                    public boolean execute(CommandSender sender, String label, String[] args) {\n"
                + "                        try {\n"
                + "                            handler.run(sender, args);\n"
                + "                        } catch (Exception e) {\n"
                + "                            sender.sendMessage(\"Error: \" + e.getMessage());\n"
                + "                        }\n"
                + "                        return true;\n"
                + "                    }\n"
                + "                };\n"
                + "                Bukkit.getCommandMap().register(\"" + prefix + "\", cmd);\n"
                + "                plugin.commands.add(cmd);\n"
                + "            } catch (Throwable t) {\n"
                + "                plugin.getLogger().warning(\"Failed to register command \" + name + \": \" + t);\n"
                + "            }\n"
                + "        }\n\n"
                + "        @Override\n"
                + "        public void action(String name, ModCommandHandler handler) {\n"
                + "            plugin.getLogger().warning(\"Action '\" + name\n"
                + "                    + \"' requires VibeCore and is not available in a standalone export.\");\n"
                + "        }\n\n"
                + buildConfigAccessorMethod("boolean", "configBool", "getBoolean", knobs, "boolean", null, "false")
                + buildConfigAccessorMethod("long", "configInt", "getLong", knobs, "integer", null, "0L")
                + buildConfigAccessorMethod("double", "configDouble", "getDouble", knobs, "decimal", null, "0.0")
                + buildConfigAccessorMethod("String", "configString", "getString", knobs, "text", "choice", "\"\"")
                + "    }\n"
                + "}\n";
    }

    /**
     * Generates one of the four {@code VibeContext} config accessor overrides as a
     * {@code switch} over the knob keys of the matching type(s), each case baking that
     * knob's schema default in as the {@code getXxx(key, default)} fallback argument. A
     * key not present in the schema (or a mod with no knobs at all) falls through to the
     * type's zero value, mirroring {@code ModConfigs}' unknown-key behavior.
     */
    private static String buildConfigAccessorMethod(String returnType, String methodName, String getter,
                                                      List<ConfigKnob> knobs, String type1, String type2,
                                                      String zeroLiteral) {
        List<ConfigKnob> matching = new ArrayList<>();
        for (ConfigKnob k : knobs) {
            String t = k.type() == null ? "text" : k.type().toLowerCase(Locale.ROOT);
            if (t.equals(type1) || (type2 != null && t.equals(type2))) {
                matching.add(k);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("        @Override\n");
        sb.append("        public ").append(returnType).append(' ').append(methodName).append("(String key) {\n");
        if (matching.isEmpty()) {
            sb.append("            return plugin.getConfig().").append(getter)
                    .append("(key, ").append(zeroLiteral).append(");\n");
        } else {
            sb.append("            switch (key) {\n");
            for (ConfigKnob k : matching) {
                sb.append("                case \"").append(escapeJava(k.key())).append("\": return plugin.getConfig().")
                        .append(getter).append("(key, ").append(defaultLiteral(k, type1, zeroLiteral)).append(");\n");
            }
            sb.append("                default: return plugin.getConfig().").append(getter)
                    .append("(key, ").append(zeroLiteral).append(");\n");
            sb.append("            }\n");
        }
        sb.append("        }\n\n");
        return sb.toString();
    }

    /** Renders a knob's schema default as a Java literal matching {@code kind}'s accessor. */
    private static String defaultLiteral(ConfigKnob k, String kind, String zeroLiteral) {
        String def = k.def();
        switch (kind) {
            case "boolean":
                return (def != null && def.trim().equalsIgnoreCase("true")) ? "true" : "false";
            case "integer":
                try {
                    return Long.parseLong(def.trim()) + "L";
                } catch (Exception e) {
                    return zeroLiteral;
                }
            case "decimal":
                try {
                    return Double.toString(Double.parseDouble(def.trim()));
                } catch (Exception e) {
                    return zeroLiteral;
                }
            case "text":
            default:
                return "\"" + escapeJava(def == null ? "" : def) + "\"";
        }
    }

    private static String escapeJava(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
