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
import com.gijsm.vibemine.gen.GeneratedProject;
import com.gijsm.vibemine.gen.ModGenerator;
import com.gijsm.vibemine.llm.ModelCatalog;
import com.gijsm.vibemine.llm.OpenRouterClient;
import com.gijsm.vibemine.runtime.DebugEcho;
import com.gijsm.vibemine.runtime.DynamicCommands;
import com.gijsm.vibemine.runtime.ModErrors;
import com.gijsm.vibemine.runtime.ModRegistry;
import com.gijsm.vibemine.runtime.Watchdog;
import com.gijsm.vibemine.store.JarExporter;
import com.gijsm.vibemine.store.ModConfigs;
import com.gijsm.vibemine.store.ModStore;
import com.gijsm.vibemine.ui.ChatMode;
import com.gijsm.vibemine.ui.Dialogs;
import com.gijsm.vibemine.ui.GuiCallbacks;
import com.gijsm.vibemine.ui.InfoDialogs;
import com.gijsm.vibemine.ui.InstallCard;
import com.gijsm.vibemine.ui.ModBrowserGui;
import com.gijsm.vibemine.ui.Progress;
import com.gijsm.vibemine.ui.SettingsDialog;
import com.gijsm.vibemine.ui.Style;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * VibeMod: vibe-code gameplay mods from in-game prompts. One plugin that asks
 * an LLM for Java, compiles it in-process, and hot-loads the result as a mod —
 * no restarts, no external services. v3: renamed from VibeCore; mods that
 * error are marked degraded with a one-click [fix]; native dialogs replace
 * book items for all typing and virtual books for all reading (console keeps
 * plain chat dumps).
 */
public final class VibeMod extends JavaPlugin {

    private ModRegistry registry;
    private ModStore store;
    private ModConfigs configs;
    private ModErrors modErrors;
    private DebugEcho debugEcho;
    private ModGenerator generator;
    private OpenRouterClient client;
    private ModelCatalog catalog;
    private InMemoryCompiler compiler;
    private Watchdog watchdog;
    private DynamicCommands dynamicCommands;
    private Dialogs dialogs;
    private InfoDialogs infoDialogs;
    private SettingsDialog settingsDialog;

    @Override
    public void onEnable() {
        migrateLegacyDataFolder();
        saveDefaultConfig();

        if (!InMemoryCompiler.available()) {
            getLogger().severe("No system Java compiler: start the server with a full JDK "
                    + "(not a JRE) or VibeMod cannot compile mods.");
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
        client.setMaxTokens(getConfig().getInt("openrouter.max-tokens", 0));
        client.setReasoningEffort(reasoningEffortFromConfig());
        catalog = new ModelCatalog();
        catalog.refreshAsync();

        watchdog = new Watchdog(this, watchdogSingleMs(), perSecondBudgetMs());
        dynamicCommands = new DynamicCommands(this, getConfig().getBoolean("commands.allow-top-level", true));
        store = new ModStore(getDataFolder().toPath().resolve("mods"));
        configs = new ModConfigs(store);
        modErrors = new ModErrors(this, getDataFolder().toPath().resolve("mods"));
        applyErrorLimits();
        debugEcho = new DebugEcho(this);
        debugEcho.setDefault(getConfig().getBoolean("debug.default-echo", false));
        registry = new ModRegistry(this, dynamicCommands, watchdog, configs, modErrors, debugEcho);

        // Supersede the registry defaults so the store flag follows disables.
        watchdog.onTrip(modName -> {
            registry.disable(modName);
            store.setEnabled(modName, false);
            modErrors.note(modName, "WatchdogTrip", "exceeded time budget", "watchdog");
            getServer().broadcast(Style.warn(modName + " was auto-disabled by the watchdog (too slow)"));
            getLogger().warning(modName + " was auto-disabled by the watchdog (too slow)");
        });
        modErrors.onStorm(modName -> {
            registry.disable(modName);
            store.setEnabled(modName, false);
            getServer().broadcast(Style.warn(modName + " was auto-disabled after an error storm")
                    .append(Component.text("  "))
                    .append(Style.button("fix", "/vibe fix " + modName, "Send the errors to the model", NamedTextColor.GOLD)));
            getLogger().warning(modName + " was auto-disabled after an error storm");
        });

        generator = new ModGenerator(this, client, compiler, store, registry,
                () -> getConfig().getInt("generation.max-retries", 3),
                () -> getConfig().getBoolean("openrouter.streaming", true),
                getConfig().getInt("generation.concurrency", 4));
        JarExporter exporter = new JarExporter(compiler);

        ChatMode chatMode = new ChatMode(this, this::generateFromPrompt);
        dialogs = new Dialogs(this,
                this::generateFromPrompt,
                (player, mod, changes) -> editFromPrompt(player, mod, changes),
                this::applyConfigValues);
        infoDialogs = new InfoDialogs(this);
        settingsDialog = new SettingsDialog(this, this::settingsSnapshot, this::applySettings,
                this::openSettingsModelPicker, this::reloadVibeConfig);
        ModBrowserGui gui = new ModBrowserGui(this, registry, store, configs, modErrors, debugEcho,
                new GuiCallbacks(
                        (player, mod) -> exportMod(player, mod, exporter),
                        this::applyStoredVersion,
                        (player, mod) -> dialogs.openConfig(player, mod, knobsFor(mod)),
                        (player, mod) -> dialogs.openEdit(player, mod, manualSummary(mod)),
                        (player, mod) -> dialogs.openFixConfirm(player, mod, lastErrorLine(mod)),
                        (player, mod) -> openManual(player, mod),
                        (player, mod) -> openSource(player, mod),
                        (player, mod) -> infoDialogs.openErrors(player, mod, modErrors.recent(mod)),
                        this::openHistory,
                        this::reloadVibeConfig,
                        client::model,
                        this::setModel,
                        player -> dialogs.openModelPicker(player, catalog.featured(client.model()), client.model(),
                                client.sessionCostUsd(), model -> setModelAndNotify(player, model)),
                        settingsDialog::open));

        PluginCommand vibe = getCommand("vibe");
        if (vibe != null) {
            VibeCommand handler = new VibeCommand(this, generator, registry, store, configs,
                    modErrors, debugEcho, catalog, exporter, gui, chatMode, dialogs, settingsDialog,
                    client::model, this::setModel, client::sessionCostUsd, this::applyStoredVersion,
                    this::reloadVibeConfig);
            vibe.setExecutor(handler);
            vibe.setTabCompleter(handler);
        }

        restoreModsFromDisk();
        getLogger().info("VibeMod ready — /vibe make \"something wonderful\"");
    }

    @Override
    public void onDisable() {
        if (generator != null) {
            generator.shutdown();
        }
        if (registry != null) {
            registry.panic();
        }
        if (modErrors != null) {
            modErrors.flush();
        }
    }

    /** One-time move of the pre-v3 data folder (plugins/VibeCore) to plugins/VibeMod. */
    private void migrateLegacyDataFolder() {
        Path legacy = getDataFolder().toPath().resolveSibling("VibeCore");
        Path current = getDataFolder().toPath();
        if (Files.isDirectory(legacy) && !Files.exists(current)) {
            try {
                Files.move(legacy, current);
                getLogger().info("Migrated data folder plugins/VibeCore -> plugins/VibeMod "
                        + "(mods, config and exports preserved)");
            } catch (IOException e) {
                getLogger().severe("Could not migrate plugins/VibeCore to plugins/VibeMod: " + e
                        + " — move it manually and restart.");
            }
        }
    }

    /** Re-read config.yml and push the reloadable knobs into live components. */
    private void reloadVibeConfig() {
        reloadConfig();
        applyLiveConfig();
        catalog.refreshAsync();
        getLogger().info("Config reloaded (model=" + client.model()
                + ", watchdog=" + watchdogSingleMs() + "ms/" + perSecondBudgetMs()
                + "ms, retries=" + getConfig().getInt("generation.max-retries", 3) + ")");
    }

    /**
     * Pushes every reloadable config value into the live components. Shared by
     * {@link #reloadVibeConfig} and the settings-dialog save path so the two can
     * never drift; generation retries/streaming need no push (the generator reads
     * them through live suppliers) and concurrency is deliberately absent (the
     * pool is sized at construction - a reload/restart applies it).
     */
    private void applyLiveConfig() {
        watchdog.setBudgets(watchdogSingleMs(), perSecondBudgetMs());
        dynamicCommands.setAllowTopLevel(getConfig().getBoolean("commands.allow-top-level", true));
        client.setModel(getConfig().getString("openrouter.model", "anthropic/claude-sonnet-5"));
        client.setTimeout(Duration.ofSeconds(getConfig().getLong("openrouter.timeout-seconds", 120)));
        client.setMaxTokens(getConfig().getInt("openrouter.max-tokens", 0));
        client.setReasoningEffort(reasoningEffortFromConfig());
        applyErrorLimits();
        debugEcho.setDefault(getConfig().getBoolean("debug.default-echo", false));
    }

    private void applyErrorLimits() {
        modErrors.setLimits(getConfig().getInt("errors.storm-threshold", 10),
                getConfig().getLong("errors.storm-window-seconds", 60),
                getConfig().getInt("errors.max-distinct", 25),
                getConfig().getInt("errors.stack-frames", 10));
    }

    private long watchdogSingleMs() {
        return getConfig().getBoolean("watchdog.enabled", true)
                ? getConfig().getLong("watchdog.single-invocation-ms", 250) : 0;
    }

    private long perSecondBudgetMs() {
        return getConfig().getLong("watchdog.per-second-budget-ms", 500);
    }

    /** Recompile a mod's current stored version and hot-load it. Used by rollback/enable/reload/boot. */
    private void applyStoredVersion(CommandSender feedback, String modName) {
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            feedback.sendMessage(Style.err("Unknown mod: " + modName));
            return;
        }
        Map<String, String> sources = store.sources(mod.name(), mod.currentVersion());
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            CompileResult compiled = compiler.compile(sources);
            Bukkit.getScheduler().runTask(this, () -> {
                if (!compiled.success()) {
                    feedback.sendMessage(Style.err("Stored version failed to compile: "
                            + firstLine(compiled.diagnostics())));
                    return;
                }
                try {
                    registry.load(mod.name(), mod.currentVersion(), mod.description(),
                            mod.mainClass(), compiled.classes(),
                            mod.config(), store.resolvedConfigValues(mod.name()));
                    store.setEnabled(mod.name(), true);
                    feedback.sendMessage(Style.ok(mod.name() + " v" + mod.currentVersion() + " is live"));
                } catch (ModRegistry.ModLoadException e) {
                    feedback.sendMessage(Style.err("Failed to start " + mod.name() + ": " + e.getMessage()));
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

    // ---- generation entry points shared by chat mode and dialogs ----

    private void generateFromPrompt(Player player, String prompt) {
        Progress progress = new Progress(this, player, "vibe: " + shorten(prompt));
        generator.make(prompt, player.getName(), listenerFor(progress))
                .thenAccept(result -> finish(player, progress, result));
    }

    private void editFromPrompt(Player player, String modName, String changes) {
        Progress progress = new Progress(this, player, "vibe: edit " + modName);
        generator.edit(modName, changes, player.getName(), listenerFor(progress))
                .thenAccept(result -> finish(player, progress, result));
    }

    // ---- dialog/config plumbing ----

    /** Schema + current values -> the knob list the config dialog renders. */
    private List<Dialogs.Knob> knobsFor(String modName) {
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            return List.of();
        }
        Map<String, String> values = store.resolvedConfigValues(mod.name());
        List<Dialogs.Knob> knobs = new ArrayList<>();
        for (GeneratedProject.ConfigKnob k : mod.config()) {
            knobs.add(new Dialogs.Knob(k.key(), k.type(), k.description(),
                    values.getOrDefault(k.key(), k.def()), k.min(), k.max(), k.step(), k.choices()));
        }
        return knobs;
    }

    /** Config-dialog submission: apply every value, returning per-key errors. */
    private List<String> applyConfigValues(Player player, String modName, Map<String, String> values) {
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            try {
                configs.set(modName, e.getKey(), e.getValue());
            } catch (IllegalArgumentException bad) {
                errors.add(e.getKey() + ": " + bad.getMessage());
            }
        }
        return errors;
    }

    private String manualSummary(String modName) {
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            return "";
        }
        String manual = mod.manual() == null || mod.manual().isBlank() ? mod.description() : mod.manual();
        return manual.length() <= 200 ? manual : manual.substring(0, 197) + "…";
    }

    private String lastErrorLine(String modName) {
        List<ModErrors.ErrorRecord> recent = modErrors.recent(modName);
        if (recent.isEmpty()) {
            return "no recorded errors";
        }
        ModErrors.ErrorRecord r = recent.get(0);
        return r.count() + "× " + r.exceptionClass() + (r.message() != null ? ": " + r.message() : "");
    }

    private void openManual(Player player, String modName) {
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            player.sendMessage(Style.err("Unknown mod: " + modName));
            return;
        }
        infoDialogs.openManual(player, mod, registry.get(mod.name()), store.resolvedConfigValues(mod.name()));
    }

    private void openSource(Player player, String modName) {
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            player.sendMessage(Style.err("Unknown mod: " + modName));
            return;
        }
        infoDialogs.openSource(player, mod.name(), mod.currentVersion(),
                store.sources(mod.name(), mod.currentVersion()));
    }

    private void openHistory(Player player, String modName) {
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            player.sendMessage(Style.err("Unknown mod: " + modName));
            return;
        }
        infoDialogs.openHistory(player, mod, store.versionsOnDisk(mod.name()));
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
                Bukkit.getScheduler().runTask(this, () -> player.sendMessage(
                        Style.ok("Exported standalone plugin: " + jar)));
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(this, () -> player.sendMessage(
                        Style.err("Export failed: " + e.getMessage())));
            }
        });
    }

    private void setModel(String model) {
        client.setModel(model);
        getConfig().set("openrouter.model", model);
        saveConfig();
    }

    /**
     * Reads {@code openrouter.reasoning-effort}, tolerating YAML's bare {@code off}
     * parsing as boolean false (Bukkit stringifies it to "false"; the client treats
     * anything but low/medium/high as off).
     */
    private String reasoningEffortFromConfig() {
        String raw = getConfig().getString("openrouter.reasoning-effort", "off");
        return raw == null ? "off" : raw;
    }

    /** {@link #setModel}'s counterpart for the reasoning effort: apply live, persist to config.yml. */
    private void setReasoningEffort(String effort) {
        client.setReasoningEffort(effort);
        getConfig().set("openrouter.reasoning-effort", client.reasoningEffort());
        saveConfig();
    }

    /** {@link #setModel} plus a chat confirmation naming the new model and its live price. */
    private void setModelAndNotify(Player player, String model) {
        setModel(model);
        String price = catalog.find(model).map(ModelCatalog.ModelInfo::priceLabel).orElse("price unknown");
        player.sendMessage(Style.ok("Model set to " + model + " (" + price + ")"));
    }

    // ---- settings dialog plumbing ----

    /** Current config values as the settings dialog's prefill snapshot. */
    private SettingsDialog.Values settingsSnapshot() {
        return new SettingsDialog.Values(
                client.model(),
                catalog.find(client.model()).map(ModelCatalog.ModelInfo::priceLabel).orElse("price unknown"),
                client.sessionCostUsd(),
                client.reasoningEffort(),
                getConfig().getBoolean("openrouter.streaming", true),
                getConfig().getLong("openrouter.timeout-seconds", 120),
                getConfig().getInt("openrouter.max-tokens", 0),
                getConfig().getInt("generation.max-retries", 3),
                getConfig().getInt("generation.concurrency", 4),
                getConfig().getBoolean("watchdog.enabled", true),
                getConfig().getLong("watchdog.single-invocation-ms", 250),
                getConfig().getLong("watchdog.per-second-budget-ms", 500),
                getConfig().getBoolean("debug.default-echo", false));
    }

    /**
     * Settings-dialog save: persist every submitted value to config.yml, then push the
     * reloadable ones into the live components via {@link #applyLiveConfig} (concurrency
     * intentionally not live-applied - the generator pool is sized at construction, as
     * the dialog's label says).
     */
    private void applySettings(Player player, SettingsDialog.Values v) {
        setReasoningEffort(v.effort());
        getConfig().set("openrouter.streaming", v.streaming());
        getConfig().set("openrouter.timeout-seconds", v.timeoutSeconds());
        getConfig().set("openrouter.max-tokens", v.maxTokens());
        getConfig().set("generation.max-retries", v.maxRetries());
        getConfig().set("generation.concurrency", v.concurrency());
        getConfig().set("watchdog.enabled", v.watchdogEnabled());
        getConfig().set("watchdog.single-invocation-ms", v.watchdogSingleMs());
        getConfig().set("watchdog.per-second-budget-ms", v.watchdogBudgetMs());
        getConfig().set("debug.default-echo", v.debugEcho());
        saveConfig();
        applyLiveConfig();
        player.sendMessage(Style.ok("Settings saved and applied (concurrency applies on next reload)."));
    }

    /**
     * The settings dialog's [Model…] button: the same picker {@code /vibe model} opens,
     * except its onPick also reopens the settings dialog (fresh snapshot) so the admin
     * lands back on the form they came from.
     */
    private void openSettingsModelPicker(Player player) {
        dialogs.openModelPicker(player, catalog.featured(client.model()), client.model(),
                client.sessionCostUsd(), model -> {
                    setModelAndNotify(player, model);
                    settingsDialog.open(player);
                });
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

            @Override
            public void planReady(String name, java.util.List<String> files) {
                progress.planReady(name, files);
            }

            @Override
            public void fileStarted(String path, int index, int total) {
                progress.fileStarted(path, index, total);
            }

            @Override
            public void streamStats(int chars, int approxTokens) {
                progress.streamStats(chars, approxTokens);
            }
        };
    }

    /** Completion handling for chat/dialog generation: progress verdict + install card. */
    private void finish(CommandSender viewer, Progress progress, ModGenerator.Result result) {
        Bukkit.getScheduler().runTask(this, () -> {
            if (result.success()) {
                String costSuffix = result.costUsd() <= 0 ? "" : " · " + Style.fmtCost(result.costUsd());
                progress.succeed("✔ " + result.modName() + " v" + result.version() + " installed"
                        + (result.retries() > 0 ? " (self-healed ×" + result.retries() + ")" : "") + costSuffix);
                ModStore.StoredMod mod = store.get(result.modName());
                if (mod != null) {
                    viewer.sendMessage(InstallCard.build(mod, registry.get(mod.name())));
                }
            } else {
                String costNote = result.costUsd() > 0 ? " (spent " + Style.fmtCost(result.costUsd()) + ")" : "";
                progress.fail("✘ " + firstLine(result.message()) + costNote);
            }
        });
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
