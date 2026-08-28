package com.gijsm.vibemod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.gijsm.vibemod.command.VibeCommand;
import com.gijsm.vibemod.command.VibeRouter;
import com.gijsm.vibemod.compile.CompileResult;
import com.gijsm.vibemod.compile.InMemoryCompiler;
import com.gijsm.vibemod.gen.ModGenerator;
import com.gijsm.vibemod.llm.ModelCatalog;
import com.gijsm.vibemod.llm.OpenRouterClient;
import com.gijsm.vibemod.llm.PlatformProfile;
import com.gijsm.vibemod.llm.PlatformProfiles;
import com.gijsm.vibemod.paper.PaperChatBridge;
import com.gijsm.vibemod.paper.PaperClasspathProvider;
import com.gijsm.vibemod.paper.PaperCommandBridge;
import com.gijsm.vibemod.paper.PaperEventBridge;
import com.gijsm.vibemod.paper.PaperMessenger;
import com.gijsm.vibemod.paper.PaperMetrics;
import com.gijsm.vibemod.paper.PaperModHost;
import com.gijsm.vibemod.paper.PaperPlatformInfo;
import com.gijsm.vibemod.paper.PaperSender;
import com.gijsm.vibemod.paper.PaperTickScheduler;
import com.gijsm.vibemod.platform.CompilerProvider;
import com.gijsm.vibemod.platform.ModFailure;
import com.gijsm.vibemod.platform.Sender;
import com.gijsm.vibemod.platform.ui.UiRenderer;
import com.gijsm.vibemod.runtime.ChatMode;
import com.gijsm.vibemod.runtime.DebugEcho;
import com.gijsm.vibemod.runtime.ModDispatch;
import com.gijsm.vibemod.runtime.ModErrors;
import com.gijsm.vibemod.runtime.ModLifecycle;
import com.gijsm.vibemod.runtime.ModLoadException;
import com.gijsm.vibemod.runtime.Watchdog;
import com.gijsm.vibemod.store.JarExporter;
import com.gijsm.vibemod.store.ModConfigs;
import com.gijsm.vibemod.store.ModStore;
import com.gijsm.vibemod.ui.InstallCard;
import com.gijsm.vibemod.ui.Progress;
import com.gijsm.vibemod.ui.Style;
import com.gijsm.vibemod.ui.chat.ChatRenderer;
import com.gijsm.vibemod.ui.screens.FormScreens;
import com.gijsm.vibemod.ui.screens.HubScreens;
import com.gijsm.vibemod.ui.screens.InfoScreens;
import com.gijsm.vibemod.ui.screens.SettingsScreens;

/**
 * VibeMod: vibe-code gameplay mods from in-game prompts. One plugin that asks
 * an LLM for Java, compiles it in-process, and hot-loads the result as a mod —
 * no restarts, no external services. Mods that error are marked degraded with
 * a one-click [fix].
 *
 * <p>v2 makes this class the Paper <em>bootstrap</em> and nothing else
 * (ARCHITECTURE-V2 §1.1): it probes what the server can do, builds the SPI
 * implementations, hands them to the platform-free engine in {@code core}, and
 * picks a renderer. Every line of behaviour it used to own moved somewhere
 * reusable.
 *
 * <p>Two things here are load-bearing and easy to break:
 * <ul>
 *   <li><b>The dialog renderer is loaded reflectively.</b> VibeMod compiles
 *       against 1.21.8 paper-api and ships a renderer referencing
 *       {@code io.papermc.paper.dialog}. On a 1.20.6 server those classes do not
 *       exist, so the JVM must never be asked to link that renderer — a plain
 *       {@code new} in a dead branch is not a guarantee, a {@code Class.forName}
 *       behind a capability probe is.</li>
 *   <li><b>The floor is 1.20</b> (shipped as 1.20.6; the sweep in
 *       ARCHITECTURE-V2 §10.6 found four more releases already working), and it
 *       is a declaration rather than a capability — {@code api-version: '1.20'}
 *       in {@code plugin.yml} is what makes Paper 1.19.4 and below refuse the
 *       plugin, before this class is ever constructed. Anything newer that this
 *       class or its collaborators touch is capability-gated through
 *       {@link PaperPlatformInfo}, never version-compared.</li>
 * </ul>
 */
public final class VibeMod extends JavaPlugin {

    /** FQCN of the dialog renderer, loaded only when {@code hasDialogs()} says the server has the API. */
    private static final String DIALOG_RENDERER_CLASS = "com.gijsm.vibemod.ui.PaperDialogRenderer";

    private PaperPlatformInfo platform;
    private PaperTickScheduler scheduler;
    private PaperMessenger messenger;
    private PaperChatBridge chatBridge;
    private PaperCommandBridge commandBridge;
    private ChatMode chatMode;
    private ModLifecycle lifecycle;
    private ModStore store;
    private ModConfigs configs;
    private ModErrors modErrors;
    private DebugEcho debugEcho;
    private ModGenerator generator;
    private OpenRouterClient client;
    private ModelCatalog catalog;
    private InMemoryCompiler compiler;
    private Watchdog watchdog;
    private PlatformProfile profile;
    private UiRenderer ui;
    /** Non-null only when the chat renderer is the active UI: it owns the {@code /vibe ui} route. */
    private ChatRenderer chatRenderer;
    private FormScreens forms;
    private HubScreens hub;
    private InfoScreens info;
    private SettingsScreens settingsScreens;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        platform = new PaperPlatformInfo();
        profile = PlatformProfiles.forPlatform(platform);
        getLogger().info("Platform: " + platform.describe() + " → prompt profile " + profile.displayName());

        scheduler = new PaperTickScheduler(this);
        messenger = new PaperMessenger(this);

        Optional<CompilerProvider> compilerProvider = CompilerProvider.resolve();
        if (compilerProvider.isEmpty()) {
            getLogger().severe("No Java compiler available: start the server with a full JDK "
                    + "(not a JRE) or VibeMod cannot compile mods.");
        } else {
            getLogger().info("Compiler backend: " + compilerProvider.get().name()
                    + " (backend max --release " + compilerProvider.get().maxSupportedRelease()
                    + ", generated mods target java" + platform.maxTargetRelease() + ")");
        }
        compiler = new InMemoryCompiler(compilerProvider.orElse(null), new PaperClasspathProvider(),
                platform.maxTargetRelease());

        String apiKey = resolveApiKey();
        if (apiKey == null) {
            getLogger().warning("No OpenRouter API key found (config openrouter.api-key, "
                    + "$OPENROUTER_API_KEY, or ~/.config/vibemod/openrouter.key). "
                    + "/vibe make will fail until one is set.");
        }
        client = new OpenRouterClient(apiKey == null ? "" : apiKey,
                getConfig().getString("openrouter.model", "anthropic/claude-sonnet-5"),
                Duration.ofSeconds(getConfig().getLong("openrouter.timeout-seconds", 120)));
        client.setMaxTokens(getConfig().getInt("openrouter.max-tokens", 0));
        client.setReasoningEffort(reasoningEffortFromConfig());
        catalog = new ModelCatalog();
        catalog.refreshAsync();

        watchdog = new Watchdog(scheduler, "main", watchdogSingleMs(), perSecondBudgetMs());
        store = new ModStore(getDataFolder().toPath().resolve("mods"),
                platform.platformName(), platform.mcVersion());
        configs = new ModConfigs(store);
        modErrors = new ModErrors(scheduler, getDataFolder().toPath().resolve("mods"));
        applyErrorLimits();
        debugEcho = new DebugEcho(messenger, scheduler);
        debugEcho.setDefault(getConfig().getBoolean("debug.default-echo", false));
        // Every explicit toggle/set writes through to meta.json, so overrides survive restarts.
        debugEcho.onChange((mod, on) -> store.setDebugEcho(mod, on));

        // The bridges dispatch into mod code and must report failures to the lifecycle,
        // which does not exist yet (it needs the bridges, via the host). Reading the
        // field at call time breaks the cycle without a mutable setter.
        ModFailure failureSink = (mod, cause, where) -> {
            if (lifecycle != null) {
                lifecycle.markFailure(mod, cause, where);
            }
        };
        ModDispatch dispatch = new ModDispatch(watchdog, failureSink);
        commandBridge = new PaperCommandBridge(this, platform, dispatch,
                getConfig().getBoolean("commands.allow-top-level", true));
        PaperEventBridge eventBridge = new PaperEventBridge(this, dispatch);
        PaperModHost modHost = new PaperModHost(this, eventBridge, commandBridge, configs, dispatch, scheduler);
        lifecycle = new ModLifecycle(modHost, scheduler, messenger, watchdog, configs, modErrors, debugEcho);

        // Supersede the lifecycle defaults so the store flag follows disables.
        watchdog.onTrip(modName -> {
            lifecycle.disable(modName);
            store.setEnabled(modName, false);
            modErrors.note(modName, "WatchdogTrip", "exceeded time budget", "watchdog");
            messenger.broadcast(Style.warn(modName + " was auto-disabled by the watchdog (too slow)"));
            getLogger().warning(modName + " was auto-disabled by the watchdog (too slow)");
        });
        modErrors.onStorm(modName -> {
            lifecycle.disable(modName);
            store.setEnabled(modName, false);
            messenger.broadcast(Style.warn(modName + " was auto-disabled after an error storm")
                    .append(Component.text("  "))
                    .append(Style.button("fix", "/vibe fix " + modName,
                            "Send the errors to the model", NamedTextColor.GOLD)));
            getLogger().warning(modName + " was auto-disabled after an error storm");
        });

        chatBridge = new PaperChatBridge(this, scheduler);
        chatMode = new ChatMode(chatBridge, this::generateFromPrompt);
        ui = chooseRenderer();

        forms = new FormScreens(messenger, ui);
        hub = new HubScreens(lifecycle, store, modErrors, debugEcho, platform.platformName());
        info = new InfoScreens(ui);
        settingsScreens = new SettingsScreens(messenger, ui, this::settingsSnapshot, this::applySettings,
                this::openSettingsModelPicker, this::reloadVibeConfig);

        generator = new ModGenerator(scheduler, com.gijsm.vibemod.llm.PromptFacts.of(platform),
                client, compiler, store, lifecycle,
                () -> getConfig().getInt("generation.max-retries", 3),
                () -> getConfig().getBoolean("openrouter.streaming", true),
                getConfig().getInt("generation.concurrency", 4));
        JarExporter exporter = new JarExporter(compiler, profile);

        VibeRouter router = new VibeRouter(scheduler, messenger, platform, getDataFolder().toPath(),
                generator, lifecycle, store, configs, modErrors, debugEcho, catalog, exporter,
                chatMode, ui, chatRenderer, forms, hub, info, settingsScreens,
                this::senderFor,
                client::model, this::setModel, client::sessionCostUsd, this::applyStoredVersion,
                this::reloadVibeConfig);

        PluginCommand vibe = getCommand("vibe");
        if (vibe != null) {
            VibeCommand handler = new VibeCommand(router);
            vibe.setExecutor(handler);
            vibe.setTabCompleter(handler);
        }

        getServer().getPluginManager().registerEvents(new QuitCleanup(), this);

        restoreModsFromDisk();

        // Last, so the mod count it reports is the restored one and not zero.
        // Currently a no-op with a log line: PaperMetrics.SERVICE_ID is still -1
        // because nobody has registered VibeMod at bstats.org yet.
        PaperMetrics.start(this, platform,
                () -> chatRenderer != null ? "chat" : "dialogs",
                () -> store.all().size());

        getLogger().info("VibeMod ready — /vibe make \"something wonderful\"");
    }

    @Override
    public void onDisable() {
        if (generator != null) {
            generator.shutdown();
        }
        if (lifecycle != null) {
            lifecycle.panic();
        }
        if (modErrors != null) {
            modErrors.flush();
        }
    }

    /**
     * Dialogs where the server has them, chat everywhere else.
     *
     * <p>{@code ui.force-chat} forces the chat renderer on a server that could
     * do dialogs — QA needs to exercise the fallback on the machine it has, not
     * only on a 1.20.6 box. The capability probe itself stays honest: it answers
     * what the server can do, not what we chose.
     */
    private UiRenderer chooseRenderer() {
        boolean forceChat = getConfig().getBoolean("ui.force-chat", false);
        if (platform.hasDialogs() && !forceChat) {
            UiRenderer dialogs = loadDialogRenderer();
            if (dialogs != null) {
                getLogger().info("UI: native dialogs");
                return dialogs;
            }
            getLogger().warning("UI: dialog API probe passed but the renderer would not load; using chat");
        } else {
            getLogger().info("UI: chat" + (forceChat ? " (forced by ui.force-chat)"
                    : " (this server has no dialog API)"));
        }
        chatRenderer = new ChatRenderer(messenger, chatBridge, scheduler);
        return chatRenderer;
    }

    /**
     * Reflection, not {@code new}: the dialog renderer names classes a 1.20.6
     * server does not have, and resolving them must be impossible unless the
     * probe already said yes.
     */
    private UiRenderer loadDialogRenderer() {
        try {
            Class<?> cls = Class.forName(DIALOG_RENDERER_CLASS, true, getClass().getClassLoader());
            return (UiRenderer) cls
                    .getConstructor(org.bukkit.plugin.Plugin.class, com.gijsm.vibemod.platform.PlatformInfo.class)
                    .newInstance(this, platform);
        } catch (ReflectiveOperationException | LinkageError e) {
            getLogger().log(Level.WARNING, "Could not load the dialog renderer", e);
            return null;
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
     * {@link #reloadVibeConfig} and the settings-screen save path so the two can
     * never drift; generation retries/streaming need no push (the generator reads
     * them through live suppliers) and concurrency is deliberately absent (the
     * pool is sized at construction - a reload/restart applies it). {@code
     * ui.force-chat} is also absent: swapping renderers under live screens would
     * strand every open callback, so it applies on the next boot.
     */
    private void applyLiveConfig() {
        watchdog.setBudgets(watchdogSingleMs(), perSecondBudgetMs());
        commandBridge.setAllowTopLevel(getConfig().getBoolean("commands.allow-top-level", true));
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

    /**
     * Recompile a mod's current stored version and hot-load it. Used by
     * rollback/enable/reload/boot. {@code onLive} (nullable) runs on the main
     * thread after the version is actually live — the compile is async, so this
     * is the only correct place for follow-ups like reopening the mod hub.
     */
    private void applyStoredVersion(Sender feedback, String modName, Runnable onLive) {
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            feedback.audience().sendMessage(Style.err("Unknown mod: " + modName));
            return;
        }
        Map<String, String> sources = store.sources(mod.name(), mod.currentVersion());
        scheduler.async(() -> {
            CompileResult compiled = compiler.compile(sources);
            scheduler.runOnMain(() -> {
                if (!compiled.success()) {
                    feedback.audience().sendMessage(Style.err("Stored version failed to compile: "
                            + firstLine(compiled.diagnostics())));
                    return;
                }
                try {
                    lifecycle.load(mod.name(), mod.currentVersion(), mod.description(),
                            mod.mainClass(), compiled.classes(),
                            mod.config(), store.resolvedConfigValues(mod.name()), mod.debugEcho());
                    store.setEnabled(mod.name(), true);
                    feedback.audience().sendMessage(
                            Style.ok(mod.name() + " v" + mod.currentVersion() + " is live"));
                    if (onLive != null) {
                        onLive.run();
                    }
                } catch (ModLoadException e) {
                    feedback.audience().sendMessage(
                            Style.err("Failed to start " + mod.name() + ": " + e.getMessage()));
                }
            });
        });
    }

    /**
     * Boot restore: recompile every mod that was enabled when the server last ran.
     *
     * <p>Mods stamped for another platform (meta.json v3, §5) are skipped with a
     * log line rather than attempted: their sources compile against a different
     * sdk flavor, so the only thing trying would produce is a wall of compile
     * diagnostics for a mod the admin never asked this server to run.
     */
    private void restoreModsFromDisk() {
        for (ModStore.StoredMod mod : store.all()) {
            if (!mod.enabled()) {
                continue;
            }
            if (!mod.platform().equalsIgnoreCase(platform.platformName())) {
                getLogger().info("Skipping mod " + mod.name() + ": generated for " + mod.platform()
                        + ", this server is " + platform.platformName());
                continue;
            }
            getLogger().info("Restoring mod " + mod.name() + " v" + mod.currentVersion());
            applyStoredVersion(PaperSender.of(getServer().getConsoleSender()), mod.name(), null);
        }
    }

    /** The {@link Sender} for an online player, or null — the router's screen-callback bridge. */
    private Sender senderFor(UUID playerId) {
        Player player = playerId == null ? null : getServer().getPlayer(playerId);
        return player == null ? null : PaperSender.of(player);
    }

    // ---- generation entry points shared by chat mode and the screens ----

    private void generateFromPrompt(UUID playerId, String prompt) {
        Progress progress = progressFor(playerId, "vibe: " + shorten(prompt));
        generator.make(prompt, nameOf(playerId), ModGenerator.listenerFor(progress))
                .thenAccept(result -> finish(playerId, progress, result));
    }

    private Progress progressFor(UUID playerId, String title) {
        return new Progress(scheduler, messenger, messenger.player(playerId), playerId, title);
    }

    private String nameOf(UUID playerId) {
        Player player = getServer().getPlayer(playerId);
        return player != null ? player.getName() : "unknown";
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
    private void setModelAndNotify(UUID playerId, String model) {
        setModel(model);
        String price = catalog.find(model).map(ModelCatalog.ModelInfo::priceLabel).orElse("price unknown");
        messenger.player(playerId).sendMessage(Style.ok("Model set to " + model + " (" + price + ")"));
    }

    // ---- settings plumbing ----

    /** Current config values as the settings screen's prefill snapshot. */
    private SettingsScreens.Values settingsSnapshot() {
        return new SettingsScreens.Values(
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
     * Settings-screen save: persist every submitted value to config.yml, then push the
     * reloadable ones into the live components via {@link #applyLiveConfig} (concurrency
     * intentionally not live-applied - the generator pool is sized at construction, as
     * the screen's label says).
     */
    private void applySettings(UUID playerId, SettingsScreens.Values v) {
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
        messenger.player(playerId).sendMessage(
                Style.ok("Settings saved and applied (concurrency applies on next reload)."));
    }

    /**
     * The settings screen's [Model…] button: the same picker {@code /vibe model} opens,
     * except its onPick also reopens the settings screen (fresh snapshot) so the admin
     * lands back on the form they came from.
     */
    private void openSettingsModelPicker(UUID playerId) {
        ui.show(playerId, forms.modelPicker(catalog.featured(client.model()), client.model(),
                client.sessionCostUsd(), model -> {
                    setModelAndNotify(playerId, model);
                    ui.show(playerId, settingsScreens.settings());
                }));
    }

    /** Completion handling for chat/screen generation: progress verdict + install card. */
    private void finish(UUID playerId, Progress progress, ModGenerator.Result result) {
        scheduler.runOnMain(() -> {
            Audience viewer = messenger.player(playerId);
            if (result.success()) {
                String costSuffix = result.costUsd() <= 0 ? "" : " · " + Style.fmtCost(result.costUsd());
                progress.succeed("✔ " + result.modName() + " v" + result.version() + " installed"
                        + (result.retries() > 0 ? " (self-healed ×" + result.retries() + ")" : "") + costSuffix);
                ModStore.StoredMod mod = store.get(result.modName());
                if (mod != null) {
                    viewer.sendMessage(InstallCard.build(mod, lifecycle.get(mod.name())));
                }
            } else {
                String costNote = result.costUsd() > 0 ? " (spent " + Style.fmtCost(result.costUsd()) + ")" : "";
                progress.fail("✘ " + firstLine(result.message()) + costNote);
            }
        });
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
        Path keyFile = Path.of(System.getProperty("user.home"), ".config", "vibemod", "openrouter.key");
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

    /**
     * Drops a leaving player's chat-renderer state. The chat bridge already ends
     * their capture; this also frees the pending form values and click tokens,
     * which would otherwise sit out their five-minute TTL.
     */
    private final class QuitCleanup implements Listener {

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            if (chatRenderer != null) {
                chatRenderer.forget(event.getPlayer().getUniqueId());
            }
        }
    }
}
