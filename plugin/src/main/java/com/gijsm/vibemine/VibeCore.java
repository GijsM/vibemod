package com.gijsm.vibemine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.gijsm.vibemine.command.VibeCommand;
import com.gijsm.vibemine.compile.CompileResult;
import com.gijsm.vibemine.compile.InMemoryCompiler;
import com.gijsm.vibemine.gen.ModGenerator;
import com.gijsm.vibemine.llm.OpenRouterClient;
import com.gijsm.vibemine.runtime.DynamicCommands;
import com.gijsm.vibemine.runtime.ModRegistry;
import com.gijsm.vibemine.runtime.Watchdog;
import com.gijsm.vibemine.store.JarExporter;
import com.gijsm.vibemine.store.ModStore;
import com.gijsm.vibemine.ui.ChatMode;
import com.gijsm.vibemine.ui.ModBrowserGui;
import com.gijsm.vibemine.ui.Progress;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * VibeCore: vibe-code gameplay mods from in-game prompts. One plugin that asks
 * an LLM for Java, compiles it in-process, and hot-loads the result as a mod —
 * no restarts, no external services.
 */
public final class VibeCore extends JavaPlugin {

    private ModRegistry registry;
    private ModStore store;
    private ModGenerator generator;
    private OpenRouterClient client;
    private InMemoryCompiler compiler;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!InMemoryCompiler.available()) {
            getLogger().severe("No system Java compiler: start the server with a full JDK "
                    + "(not a JRE) or VibeMine cannot compile mods.");
        }
        String apiKey = resolveApiKey();
        if (apiKey == null) {
            getLogger().warning("No OpenRouter API key found (config openrouter.api-key, "
                    + "$OPENROUTER_API_KEY, or ~/.config/vibemine/openrouter.key). "
                    + "/vibe make will fail until one is set.");
        }

        compiler = new InMemoryCompiler(serverLibraryJars());
        client = new OpenRouterClient(apiKey == null ? "" : apiKey,
                getConfig().getString("openrouter.model", "anthropic/claude-sonnet-5"),
                Duration.ofSeconds(getConfig().getLong("openrouter.timeout-seconds", 120)));

        boolean watchdogOn = getConfig().getBoolean("watchdog.enabled", true);
        Watchdog watchdog = new Watchdog(this,
                watchdogOn ? getConfig().getLong("watchdog.single-invocation-ms", 250) : 0,
                getConfig().getLong("watchdog.per-second-budget-ms", 500));
        DynamicCommands dynamicCommands = new DynamicCommands(this,
                getConfig().getBoolean("commands.allow-top-level", true));
        registry = new ModRegistry(this, dynamicCommands, watchdog);
        store = new ModStore(getDataFolder().toPath().resolve("mods"));
        // Supersede the registry's default trip handler so the store flag follows:
        // a tripped mod must not come back on its own at the next boot.
        watchdog.onTrip(modName -> {
            registry.disable(modName);
            store.setEnabled(modName, false);
            getServer().broadcast(net.kyori.adventure.text.Component.text(
                    modName + " was auto-disabled by the watchdog (too slow)",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            getLogger().warning(modName + " was auto-disabled by the watchdog (too slow)");
        });
        generator = new ModGenerator(this, client, compiler, store, registry,
                getConfig().getInt("generation.max-retries", 3));
        JarExporter exporter = new JarExporter(compiler);

        ChatMode chatMode = new ChatMode(this, (player, prompt) -> generateFromChat(player, prompt));
        ModBrowserGui gui = new ModBrowserGui(this, registry, store,
                (player, mod) -> exportMod(player, mod, exporter),
                (player, mod) -> applyStoredVersion(player, mod));

        PluginCommand vibe = getCommand("vibe");
        if (vibe != null) {
            VibeCommand handler = new VibeCommand(this, generator, registry, store, exporter, gui,
                    chatMode, client::model, this::setModel, this::applyStoredVersion);
            vibe.setExecutor(handler);
            vibe.setTabCompleter(handler);
        }

        restoreModsFromDisk();
        getLogger().info("VibeCore ready — /vibe make \"something wonderful\"");
    }

    @Override
    public void onDisable() {
        if (generator != null) {
            generator.shutdown();
        }
        if (registry != null) {
            registry.panic();
        }
    }

    /** Recompile a mod's current stored version and hot-load it. Used by rollback/enable/boot. */
    private void applyStoredVersion(CommandSender feedback, String modName) {
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            feedback.sendMessage(Component.text("Unknown mod: " + modName, NamedTextColor.RED));
            return;
        }
        Map<String, String> sources = store.sources(mod.name(), mod.currentVersion());
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            CompileResult compiled = compiler.compile(sources);
            Bukkit.getScheduler().runTask(this, () -> {
                if (!compiled.success()) {
                    feedback.sendMessage(Component.text("Stored version failed to compile: "
                            + firstLine(compiled.diagnostics()), NamedTextColor.RED));
                    return;
                }
                try {
                    registry.load(mod.name(), mod.currentVersion(), mod.description(),
                            mod.mainClass(), compiled.classes());
                    store.setEnabled(mod.name(), true);
                    feedback.sendMessage(Component.text(mod.name() + " v" + mod.currentVersion()
                            + " is live", NamedTextColor.GREEN));
                } catch (ModRegistry.ModLoadException e) {
                    feedback.sendMessage(Component.text("Failed to start " + mod.name() + ": "
                            + e.getMessage(), NamedTextColor.RED));
                }
            });
        });
    }

    /** Boot restore: recompile every mod that was enabled when the server last ran. */
    private void restoreModsFromDisk() {
        for (ModStore.StoredMod mod : store.all()) {
            if (mod.enabled()) {
                getLogger().info("Restoring mod " + mod.name() + " v" + mod.currentVersion());
                applyStoredVersion(Bukkit.getConsoleSender(), mod.name());
            }
        }
    }

    private void generateFromChat(Player player, String prompt) {
        Progress progress = new Progress(this, player, "vibe: " + shorten(prompt));
        generator.make(prompt, player.getName(), listenerFor(progress))
                .thenAccept(result -> finish(progress, result));
    }

    private void exportMod(Player player, String modName, JarExporter exporter) {
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            return;
        }
        Map<String, String> sources = store.sources(mod.name(), mod.currentVersion());
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Path jar = exporter.export(mod, sources, getDataFolder().toPath().resolve("exports"));
                Bukkit.getScheduler().runTask(this, () -> player.sendMessage(Component.text(
                        "Exported standalone plugin: " + jar, NamedTextColor.GREEN)));
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(this, () -> player.sendMessage(Component.text(
                        "Export failed: " + e.getMessage(), NamedTextColor.RED)));
            }
        });
    }

    private void setModel(String model) {
        client.setModel(model);
        getConfig().set("openrouter.model", model);
        saveConfig();
    }

    /** Bridge a ModGenerator progress stream into a Progress UI. */
    public static ModGenerator.ProgressListener listenerFor(Progress progress) {
        return new ModGenerator.ProgressListener() {
            @Override
            public void phase(String label) {
                progress.phase(label);
            }

            @Override
            public void detail(String line) {
                progress.detail(line);
            }
        };
    }

    /** Standard completion handling shared by chat mode and /vibe. */
    public static void finish(Progress progress, ModGenerator.Result result) {
        if (result.success()) {
            progress.succeed("✔ " + result.modName() + " v" + result.version() + " installed"
                    + (result.retries() > 0 ? " (self-healed ×" + result.retries() + ")" : ""));
        } else {
            progress.fail("✘ " + firstLine(result.message()));
        }
    }

    /**
     * Paper's bundler extracts the server + all its libraries (paper-api included)
     * to disk; inside the running server {@code java.class.path} only holds the
     * paperclip bootstrap, so hand every extracted jar to the compiler explicitly.
     */
    private static Path[] serverLibraryJars() {
        List<Path> jars = new ArrayList<>();
        for (String dir : new String[] {"libraries", "versions"}) {
            Path root = Path.of(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var stream = Files.walk(root)) {
                stream.filter(p -> p.toString().endsWith(".jar")).forEach(jars::add);
            } catch (IOException ignored) {
                // best effort - the compiler also tries code-source detection
            }
        }
        return jars.toArray(Path[]::new);
    }

    private String resolveApiKey() {
        String fromConfig = getConfig().getString("openrouter.api-key", "");
        if (fromConfig != null && !fromConfig.isBlank()) {
            return fromConfig.trim();
        }
        String fromEnv = System.getenv("OPENROUTER_API_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        Path keyFile = Path.of(System.getProperty("user.home"), ".config", "vibemine", "openrouter.key");
        if (Files.isReadable(keyFile)) {
            try {
                String key = Files.readString(keyFile).trim();
                return key.isEmpty() ? null : key;
            } catch (IOException ignored) {
                // fall through
            }
        }
        return null;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "unknown error";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static String shorten(String s) {
        return s.length() <= 40 ? s : s.substring(0, 37) + "…";
    }
}
