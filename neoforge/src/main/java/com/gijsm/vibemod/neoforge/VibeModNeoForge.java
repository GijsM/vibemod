package com.gijsm.vibemod.neoforge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.CustomClickActionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.Permissions;

import com.gijsm.vibemod.api.client.ClientContext;
import com.gijsm.vibemod.command.VibeRouter;
import com.gijsm.vibemod.compile.CompileResult;
import com.gijsm.vibemod.compile.InMemoryCompiler;
import com.gijsm.vibemod.gen.ModGenerator;
import com.gijsm.vibemod.llm.ModelCatalog;
import com.gijsm.vibemod.llm.OpenRouterClient;
import com.gijsm.vibemod.llm.PlatformProfile;
import com.gijsm.vibemod.llm.PlatformProfiles;
import com.gijsm.vibemod.loader.DialogClicks;
import com.gijsm.vibemod.loader.LoaderCommandBridge;
import com.gijsm.vibemod.loader.LoaderConfig;
import com.gijsm.vibemod.loader.LoaderDialogRenderer;
import com.gijsm.vibemod.loader.LoaderMessenger;
import com.gijsm.vibemod.loader.LoaderModHost;
import com.gijsm.vibemod.loader.LoaderSender;
import com.gijsm.vibemod.loader.LoaderTickScheduler;
import com.gijsm.vibemod.platform.ClientEventBridge;
import com.gijsm.vibemod.platform.CompilerProvider;
import com.gijsm.vibemod.platform.ModFailure;
import com.gijsm.vibemod.platform.Sender;
import com.gijsm.vibemod.platform.ui.UiRenderer;
import com.gijsm.vibemod.runtime.ChatMode;
import com.gijsm.vibemod.runtime.DebugEcho;
import com.gijsm.vibemod.runtime.ModDispatch;
import com.gijsm.vibemod.runtime.ModErrors;
import com.gijsm.vibemod.runtime.ModHandle;
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
 * VibeMod on NeoForge: the {@code @Mod} entrypoint, and nothing but a bootstrap.
 *
 * <p>The shape mirrors {@code VibeModFabric} — and Paper's plugin class before
 * it — because that is the whole point of the v2 architecture: probe what the
 * platform can do, build the SPI implementations, hand them to the
 * platform-free engine in {@code core}, pick a renderer. Everything below the
 * SPI is shared, and on the two loaders most of the SPI implementations are
 * shared too (§10.4, {@code loader-common}). The only thing a host writes is the
 * wiring.
 *
 * <p>One structural rule the loader forces, and it is the §10.3 trap restated:
 * <b>a mod initialises once per process, but a VibeMod host lives with a
 * server</b> — and on a client that is one host per world loaded. So the
 * constructor registers exactly one thing per surface, permanently, on
 * {@code NeoForge.EVENT_BUS}; each of those resolves the live per-server object
 * when it fires (null between worlds). NeoForge's bus can unregister, unlike
 * Fabric's, and it still would not help: the asymmetry is about lifetimes, not
 * about the bus.
 *
 * <p>{@link #services()} is how the client half reaches the live host without
 * either half depending on the other's classes.
 */
@Mod("vibemod")
public final class VibeModNeoForge {

    private static final Logger LOG = Logger.getLogger("VibeMod");

    /** Set by the client entrypoint before a server exists; null on a dedicated server. */
    private static volatile ClientHooks clientHooks;
    /** The live services, or null between worlds. */
    private static volatile Services services;
    /**
     * The live bridges, or null between worlds.
     *
     * <p>Separate from {@link #services} because the process-lived subscriptions
     * in the constructor need them, and because they exist slightly earlier in
     * {@code wire()} than the fully-built {@link Services} record does.
     */
    private static volatile NeoForgeEventBridge eventBridge;
    private static volatile NeoForgeChatBridge chatBridge;
    private static volatile LoaderCommandBridge commandBridge;

    /**
     * What the client entrypoint contributes: the bridge, its watchdog wiring,
     * and the factory for a mod's {@code ClientContext}.
     *
     * <p>A record rather than a direct reference so that this class — which runs
     * on dedicated servers — never names a client-only type. Only
     * {@link ClientEventBridge} (platform-api) and {@link ClientContext}
     * (sdk-client, pure JDK) appear here, and both exist on every side.
     */
    public record ClientHooks(ClientEventBridge bridge,
                              Function<ModHandle, ClientContext> contexts,
                              Watchdog renderWatchdog) {
    }

    /**
     * Everything the host built for the currently running server.
     *
     * <p>{@code chatRenderer} is null when native dialogs are in use — then there
     * is no per-player form state to forget.
     */
    public record Services(MinecraftServer server, NeoForgePlatformInfo platform, ModLifecycle lifecycle,
                           ModStore store, ModErrors errors, LoaderMessenger messenger,
                           LoaderTickScheduler scheduler, UiRenderer ui, VibeRouter router,
                           ChatRenderer chatRenderer) {
    }

    /** The live services, or null when no server is running. */
    public static Services services() {
        return services;
    }

    /** Called by the client entrypoint at its own construction, before a server exists. */
    public static void setClientHooks(ClientHooks hooks) {
        clientHooks = hooks;
    }

    // ------------------------------------------------------- permission nodes

    /**
     * The two permission nodes VibeMod models, registered with NeoForge's
     * {@code PermissionAPI} so an installed permission manager (LuckPerms and
     * friends) can grant them by name.
     *
     * <p>Their default resolvers answer by op level, which is the same contract
     * fabric-permission-api gives Fabric: node first, op level when nothing
     * answers. That is why {@code LoaderSender} takes an oracle rather than
     * branching — the question is identical on both loaders, only the plumbing
     * differs (§10.4).
     */
    private static final PermissionNode<Boolean> USE = new PermissionNode<>(
            LoaderSender.USE_NODE, PermissionTypes.BOOLEAN, (player, uuid, context) -> true);
    private static final PermissionNode<Boolean> ADMIN = new PermissionNode<>(
            LoaderSender.ADMIN_NODE, PermissionTypes.BOOLEAN,
            (player, uuid, context) -> player != null && atLeast(player, PermissionLevel.GAMEMASTERS));

    public VibeModNeoForge() {
        LOG.info("VibeMod loading — waiting for a server");

        LoaderSender.setPermissionOracle(VibeModNeoForge::checkPermission);

        NeoForge.EVENT_BUS.addListener(PermissionGatherEvent.Nodes.class,
                event -> event.addNodes(USE, ADMIN));

        NeoForge.EVENT_BUS.addListener(ServerStartingEvent.class, event -> start(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> stop());

        NeoForgeEventBridge.installDispatchers(NeoForge.EVENT_BUS, () -> eventBridge);
        NeoForgeChatBridge.installDispatcher(NeoForge.EVENT_BUS, () -> chatBridge);

        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
            Services live = services;
            if (live != null) {
                live.scheduler().tick();
            }
        });

        // Fires at startup AND after every /reload, each time with a fresh
        // dispatcher — which would silently drop every generated-mod command if
        // the bridge did not re-add them.
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event -> {
            LoaderCommandBridge live = commandBridge;
            if (live != null) {
                live.reinstallInto(event.getDispatcher());
            }
        });

        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                forget(player.getUUID());
            }
        });

        // ARCHITECTURE-V2 §8.5 asked whether the loader exposes an event for
        // custom click actions, so the Fabric mixin could be skipped. NeoForge
        // does: it patches ServerCommonPacketListenerImpl to pass the PLAYER
        // through to MinecraftServer#handleCustomClickAction, where CommonHooks
        // posts this event — after ensureRunningOnSameThread, so already on the
        // server thread. Fabric needs a mixin for exactly the information this
        // event hands over for free. HIGHEST so a dialog VibeMod opened is
        // answered before any other mod's click handling sees it.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, CustomClickActionEvent.class, event -> {
            if (DialogClicks.handle(event.getPlayer(), event.getIdentifier(),
                    Optional.ofNullable(event.getPayload()))) {
                // It was ours: vanilla's debug no-op has nothing to add.
                event.setCanceled(true);
            }
        });
    }

    /**
     * The {@code LoaderSender} oracle: node first, op level when nothing answers.
     *
     * <p>Only the two nodes above are registered, because {@code PermissionAPI}
     * requires registration at gather time and a mod can ask about any string.
     * Anything else falls straight through to the op-level check, which is what
     * the Fabric side's unregistered-node path does too.
     */
    private static boolean checkPermission(CommandSourceStack source, Identifier node,
                                           PermissionLevel fallback) {
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            PermissionNode<Boolean> known = node.equals(LoaderSender.USE_NODE) ? USE
                    : node.equals(LoaderSender.ADMIN_NODE) ? ADMIN : null;
            if (known != null) {
                try {
                    return Boolean.TRUE.equals(PermissionAPI.getPermission(player, known));
                } catch (Throwable notInitialised) {
                    // Before the permission handler exists (very early, or in a
                    // test harness) the op level is the honest answer.
                }
            }
            return atLeast(player, fallback);
        }
        // The console and RCON are the server itself: everything, always.
        return true;
    }

    /** Vanilla 26.x has no {@code hasPermission(int)}; op levels are typed Permissions now. */
    private static boolean atLeast(ServerPlayer player, PermissionLevel level) {
        return switch (level) {
            case ALL -> true;
            case MODERATORS -> player.permissions().hasPermission(Permissions.COMMANDS_MODERATOR);
            case GAMEMASTERS -> player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
            case ADMINS -> player.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
            case OWNERS -> player.permissions().hasPermission(Permissions.COMMANDS_OWNER);
        };
    }

    /** Drops everything a leaving player left behind: click tokens, form state, their audience. */
    private static void forget(UUID playerId) {
        DialogClicks.forget(playerId);
        Services live = services;
        if (live == null) {
            return;
        }
        live.messenger().forget(playerId);
        if (live.chatRenderer() != null) {
            live.chatRenderer().forget(playerId);
        }
    }

    // ------------------------------------------------------------------ boot

    private static void start(MinecraftServer server) {
        Path dataFolder = FMLPaths.GAMEDIR.get().resolve("vibemod");
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException e) {
            LOG.severe("Could not create " + dataFolder + ": " + e);
            return;
        }
        LoaderConfig config = new LoaderConfig(FMLPaths.CONFIGDIR.get().resolve("vibemod.json"));

        NeoForgePlatformInfo platform = new NeoForgePlatformInfo(server.isDedicatedServer());
        PlatformProfile profile = PlatformProfiles.forPlatform(platform);
        LOG.info("Platform: " + platform.describe() + " → prompt profile " + profile.displayName());
        LOG.info("Hot-load class-file ceiling: " + ClassFileCeiling.describe());

        LoaderTickScheduler scheduler = new LoaderTickScheduler(server);
        LoaderMessenger messenger = new LoaderMessenger(server);

        Optional<CompilerProvider> compilerProvider = CompilerProvider.resolve();
        if (compilerProvider.isEmpty()) {
            LOG.severe("No Java compiler available — VibeMod cannot compile mods. "
                    + "This should not happen: ECJ is bundled inside the mod jar.");
        } else {
            LOG.info("Compiler backend: " + compilerProvider.get().name()
                    + " (backend max --release " + compilerProvider.get().maxSupportedRelease()
                    + ", generated mods target java" + platform.maxTargetRelease() + ")");
        }
        InMemoryCompiler compiler = new InMemoryCompiler(compilerProvider.orElse(null),
                new NeoForgeClasspathProvider(dataFolder), platform.maxTargetRelease());

        String apiKey = resolveApiKey(config);
        if (apiKey == null) {
            LOG.warning("No OpenRouter API key found (config openrouter.api-key, "
                    + "$OPENROUTER_API_KEY, or ~/.config/vibemod/openrouter.key). "
                    + "/vibe make will fail until one is set.");
        }
        OpenRouterClient client = new OpenRouterClient(apiKey == null ? "" : apiKey,
                config.getString("openrouter.model", "anthropic/claude-sonnet-5"),
                Duration.ofSeconds(config.getLong("openrouter.timeout-seconds", 120)));
        client.setMaxTokens(config.getInt("openrouter.max-tokens", 0));
        client.setReasoningEffort(config.getString("openrouter.reasoning-effort", "off"));
        ModelCatalog catalog = new ModelCatalog();
        catalog.refreshAsync();

        Watchdog watchdog = new Watchdog(scheduler, "main",
                watchdogSingleMs(config), config.getLong("watchdog.per-second-budget-ms", 500));
        ModStore store = new ModStore(dataFolder.resolve("mods"),
                platform.platformName(), platform.mcVersion());
        ModConfigs configs = new ModConfigs(store);
        ModErrors modErrors = new ModErrors(scheduler, dataFolder.resolve("mods"));
        DebugEcho debugEcho = new DebugEcho(messenger, scheduler);
        debugEcho.setDefault(config.getBoolean("debug.default-echo", false));
        debugEcho.onChange((mod, on) -> store.setDebugEcho(mod, on));

        Boot boot = new Boot(server, dataFolder, config, platform, profile, scheduler, messenger,
                compiler, client, catalog, watchdog, store, configs, modErrors, debugEcho);
        boot.wire();
        services = boot.services;
    }

    private static void stop() {
        Services live = services;
        // Cleared BEFORE teardown: the process-lived subscriptions are still
        // armed, and a tick or a chat line landing mid-shutdown must find
        // nothing rather than a half-torn-down host.
        services = null;
        eventBridge = null;
        chatBridge = null;
        commandBridge = null;
        if (live == null) {
            return;
        }
        try {
            live.lifecycle.panic();
        } catch (Throwable ignored) {
            // shutdown is best-effort
        }
        live.errors.flush();
        live.scheduler.shutdown();
        DialogClicks.clear();
        LOG.info("VibeMod stopped");
    }

    private static long watchdogSingleMs(LoaderConfig config) {
        return config.getBoolean("watchdog.enabled", true)
                ? config.getLong("watchdog.single-invocation-ms", 250) : 0;
    }

    /**
     * The key lookup, in the same order as Paper's and Fabric's: config, then
     * environment, then {@code ~/.config/vibemod/openrouter.key}. Never
     * hardcoded anywhere.
     */
    private static String resolveApiKey(LoaderConfig config) {
        String fromConfig = config.getString("openrouter.api-key", "");
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

    /**
     * The wiring, in one object because the pieces are mutually referential: the
     * bridges need a failure sink that is the lifecycle, the lifecycle needs a
     * host that needs the bridges, and the router needs all of them.
     */
    private static final class Boot {

        private final MinecraftServer server;
        private final Path dataFolder;
        private final LoaderConfig config;
        private final NeoForgePlatformInfo platform;
        private final PlatformProfile profile;
        private final LoaderTickScheduler scheduler;
        private final LoaderMessenger messenger;
        private final InMemoryCompiler compiler;
        private final OpenRouterClient client;
        private final ModelCatalog catalog;
        private final Watchdog watchdog;
        private final ModStore store;
        private final ModConfigs configs;
        private final ModErrors modErrors;
        private final DebugEcho debugEcho;

        private ModLifecycle lifecycle;
        private ChatRenderer chatRenderer;
        private UiRenderer ui;
        private ModGenerator generator;
        private FormScreens forms;
        private HubScreens hub;
        private InfoScreens info;
        private SettingsScreens settingsScreens;
        private Services services;

        Boot(MinecraftServer server, Path dataFolder, LoaderConfig config, NeoForgePlatformInfo platform,
             PlatformProfile profile, LoaderTickScheduler scheduler, LoaderMessenger messenger,
             InMemoryCompiler compiler, OpenRouterClient client, ModelCatalog catalog, Watchdog watchdog,
             ModStore store, ModConfigs configs, ModErrors modErrors, DebugEcho debugEcho) {
            this.server = server;
            this.dataFolder = dataFolder;
            this.config = config;
            this.platform = platform;
            this.profile = profile;
            this.scheduler = scheduler;
            this.messenger = messenger;
            this.compiler = compiler;
            this.client = client;
            this.catalog = catalog;
            this.watchdog = watchdog;
            this.store = store;
            this.configs = configs;
            this.modErrors = modErrors;
            this.debugEcho = debugEcho;
        }

        void wire() {
            applyErrorLimits();

            // The bridges dispatch into mod code and must report failures to the
            // lifecycle, which does not exist yet (it needs the bridges, via the
            // host). Reading the field at call time breaks the cycle.
            ModFailure failureSink = (mod, cause, where) -> {
                if (lifecycle != null) {
                    lifecycle.markFailure(mod, cause, where);
                }
            };
            ModDispatch dispatch = new ModDispatch(watchdog, failureSink);

            // Published to the statics the process-lived subscriptions read; the
            // subscriptions themselves were made once, in the constructor.
            commandBridge = new LoaderCommandBridge(server, messenger, dispatch,
                    config.getBoolean("commands.allow-top-level", true));
            eventBridge = new NeoForgeEventBridge(dispatch);
            chatBridge = new NeoForgeChatBridge(scheduler);

            ClientHooks hooks = clientHooks;
            LoaderModHost modHost = new LoaderModHost(server, dataFolder, eventBridge, commandBridge,
                    configs, dispatch, scheduler, hooks == null ? null : hooks.contexts());
            lifecycle = new ModLifecycle(modHost, scheduler, messenger, watchdog, configs, modErrors, debugEcho);

            // The render-thread watchdog reports to the same lifecycle and shares
            // its budgets: watchdogging a HUD renderer differs from watchdogging a
            // listener only in which thread is being measured (§8.4).
            if (hooks != null) {
                hooks.renderWatchdog().setBudgets(watchdogSingleMs(config),
                        config.getLong("watchdog.per-second-budget-ms", 500));
                hooks.renderWatchdog().onTrip(this::autoDisable);
            }

            watchdog.onTrip(this::autoDisable);
            modErrors.onStorm(modName -> {
                lifecycle.disable(modName);
                store.setEnabled(modName, false);
                messenger.broadcast(Style.warn(modName + " was auto-disabled after an error storm")
                        .append(Component.text("  "))
                        .append(Style.button("fix", "/vibe fix " + modName,
                                "Send the errors to the model", NamedTextColor.GOLD)));
                LOG.warning(modName + " was auto-disabled after an error storm");
            });

            ui = chooseRenderer();

            forms = new FormScreens(messenger, ui);
            hub = new HubScreens(lifecycle, store, modErrors, debugEcho, platform.platformName());
            info = new InfoScreens(ui);
            settingsScreens = new SettingsScreens(messenger, ui, this::settingsSnapshot, this::applySettings,
                    this::openSettingsModelPicker, this::reloadConfig);

            generator = new ModGenerator(scheduler, com.gijsm.vibemod.llm.PromptFacts.of(platform),
                    client, compiler, store, lifecycle,
                    () -> config.getInt("generation.max-retries", 3),
                    () -> config.getBoolean("openrouter.streaming", true),
                    config.getInt("generation.concurrency", 4));
            JarExporter exporter = new JarExporter(compiler, profile);

            ChatMode chatMode = new ChatMode(chatBridge, this::generateFromPrompt);
            VibeRouter router = new VibeRouter(scheduler, messenger, platform, dataFolder,
                    generator, lifecycle, store, configs, modErrors, debugEcho, catalog, exporter,
                    chatMode, ui, chatRenderer, forms, hub, info, settingsScreens,
                    this::senderFor,
                    client::model, this::setModel, client::sessionCostUsd, this::applyStoredVersion,
                    this::reloadConfig);
            commandBridge.setRouter(router);
            // RegisterCommandsEvent already fired for THIS server's dispatcher,
            // before the bridge existed, so install into it directly. The
            // process-lived listener covers every later /reload.
            commandBridge.reinstallInto(server.getCommands().getDispatcher());

            services = new Services(server, platform, lifecycle, store, modErrors, messenger,
                    scheduler, ui, router, chatRenderer);

            restoreModsFromDisk();
            LOG.info("VibeMod ready — /vibe make \"something wonderful\"");
        }

        private void autoDisable(String modName) {
            lifecycle.disable(modName);
            store.setEnabled(modName, false);
            modErrors.note(modName, "WatchdogTrip", "exceeded time budget", "watchdog");
            messenger.broadcast(Style.warn(modName + " was auto-disabled by the watchdog (too slow)"));
            LOG.warning(modName + " was auto-disabled by the watchdog (too slow)");
        }

        /**
         * Dialogs where the game has them — which on the MC 26.1+ floor is
         * always — and chat when QA asks for it. The probe stays honest: it
         * answers what the game can do, not what was chosen.
         */
        private UiRenderer chooseRenderer() {
            boolean forceChat = config.getBoolean("ui.force-chat", false);
            if (platform.hasDialogs() && !forceChat) {
                LOG.info("UI: native dialogs");
                return new LoaderDialogRenderer(server, scheduler, messenger);
            }
            LOG.info("UI: chat" + (forceChat ? " (forced by ui.force-chat)"
                    : " (this game has no dialog API)"));
            chatRenderer = new ChatRenderer(messenger, chatBridge, scheduler);
            return chatRenderer;
        }

        private Sender senderFor(UUID playerId) {
            var player = playerId == null ? null : server.getPlayerList().getPlayer(playerId);
            return player == null ? null : LoaderSender.of(player.createCommandSourceStack(), messenger);
        }

        private Sender console() {
            return LoaderSender.of(server.createCommandSourceStack(), messenger);
        }

        // ---- config plumbing ----

        private void reloadConfig() {
            config.reload();
            applyLiveConfig();
            catalog.refreshAsync();
            LOG.info("Config reloaded (model=" + client.model()
                    + ", watchdog=" + watchdogSingleMs(config) + "ms/"
                    + config.getLong("watchdog.per-second-budget-ms", 500)
                    + "ms, retries=" + config.getInt("generation.max-retries", 3) + ")");
        }

        private void applyLiveConfig() {
            long single = watchdogSingleMs(config);
            long budget = config.getLong("watchdog.per-second-budget-ms", 500);
            watchdog.setBudgets(single, budget);
            ClientHooks hooks = clientHooks;
            if (hooks != null) {
                hooks.renderWatchdog().setBudgets(single, budget);
            }
            commandBridge.setAllowTopLevel(config.getBoolean("commands.allow-top-level", true));
            client.setModel(config.getString("openrouter.model", "anthropic/claude-sonnet-5"));
            client.setTimeout(Duration.ofSeconds(config.getLong("openrouter.timeout-seconds", 120)));
            client.setMaxTokens(config.getInt("openrouter.max-tokens", 0));
            client.setReasoningEffort(config.getString("openrouter.reasoning-effort", "off"));
            applyErrorLimits();
            debugEcho.setDefault(config.getBoolean("debug.default-echo", false));
        }

        private void applyErrorLimits() {
            modErrors.setLimits(config.getInt("errors.storm-threshold", 10),
                    config.getLong("errors.storm-window-seconds", 60),
                    config.getInt("errors.max-distinct", 25),
                    config.getInt("errors.stack-frames", 10));
        }

        private void setModel(String model) {
            client.setModel(model);
            config.set("openrouter.model", model);
            config.save();
        }

        private SettingsScreens.Values settingsSnapshot() {
            return new SettingsScreens.Values(
                    client.model(),
                    catalog.find(client.model()).map(ModelCatalog.ModelInfo::priceLabel).orElse("price unknown"),
                    client.sessionCostUsd(),
                    client.reasoningEffort(),
                    config.getBoolean("openrouter.streaming", true),
                    config.getLong("openrouter.timeout-seconds", 120),
                    config.getInt("openrouter.max-tokens", 0),
                    config.getInt("generation.max-retries", 3),
                    config.getInt("generation.concurrency", 4),
                    config.getBoolean("watchdog.enabled", true),
                    config.getLong("watchdog.single-invocation-ms", 250),
                    config.getLong("watchdog.per-second-budget-ms", 500),
                    config.getBoolean("debug.default-echo", false));
        }

        private void applySettings(UUID playerId, SettingsScreens.Values v) {
            client.setReasoningEffort(v.effort());
            config.set("openrouter.reasoning-effort", client.reasoningEffort());
            config.set("openrouter.streaming", v.streaming());
            config.set("openrouter.timeout-seconds", v.timeoutSeconds());
            config.set("openrouter.max-tokens", v.maxTokens());
            config.set("generation.max-retries", v.maxRetries());
            config.set("generation.concurrency", v.concurrency());
            config.set("watchdog.enabled", v.watchdogEnabled());
            config.set("watchdog.single-invocation-ms", v.watchdogSingleMs());
            config.set("watchdog.per-second-budget-ms", v.watchdogBudgetMs());
            config.set("debug.default-echo", v.debugEcho());
            config.save();
            applyLiveConfig();
            messenger.player(playerId).sendMessage(
                    Style.ok("Settings saved and applied (concurrency applies on next reload)."));
        }

        private void openSettingsModelPicker(UUID playerId) {
            ui.show(playerId, forms.modelPicker(catalog.featured(client.model()), client.model(),
                    client.sessionCostUsd(), model -> {
                        setModel(model);
                        String price = catalog.find(model)
                                .map(ModelCatalog.ModelInfo::priceLabel).orElse("price unknown");
                        messenger.player(playerId).sendMessage(
                                Style.ok("Model set to " + model + " (" + price + ")"));
                        ui.show(playerId, settingsScreens.settings());
                    }));
        }

        // ---- generation + restore ----

        private void generateFromPrompt(UUID playerId, String prompt) {
            Progress progress = new Progress(scheduler, messenger, messenger.player(playerId), playerId,
                    "vibe: " + (prompt.length() <= 40 ? prompt : prompt.substring(0, 37) + "…"));
            var player = server.getPlayerList().getPlayer(playerId);
            generator.make(prompt, player == null ? "unknown" : player.getName().getString(),
                            ModGenerator.listenerFor(progress))
                    .thenAccept(result -> scheduler.runOnMain(() -> {
                        if (result.success()) {
                            String costSuffix = result.costUsd() <= 0 ? ""
                                    : " · " + Style.fmtCost(result.costUsd());
                            progress.succeed("✔ " + result.modName() + " v" + result.version() + " installed"
                                    + (result.retries() > 0 ? " (self-healed ×" + result.retries() + ")" : "")
                                    + costSuffix);
                            ModStore.StoredMod mod = store.get(result.modName());
                            if (mod != null) {
                                messenger.player(playerId)
                                        .sendMessage(InstallCard.build(mod, lifecycle.get(mod.name())));
                            }
                        } else {
                            progress.fail("✘ " + firstLine(result.message()));
                        }
                    }));
        }

        /**
         * Recompile a mod's current stored version and hot-load it. Used by
         * rollback/enable/reload/boot. {@code onLive} (nullable) runs on the main
         * thread after the version is actually live — the compile is async, so
         * this is the only correct place for follow-ups like reopening the hub.
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
                        // Logged as well as sent. The sender is whoever asked —
                        // and on restore-on-boot that is the console, whose
                        // Adventure audience is not always a place a human is
                        // looking. A mod that failed to compile at boot must
                        // leave a trace in the log whatever the audience does
                        // with it; the Phase E client gate spent two runs on a
                        // compile failure that reported only to a channel
                        // nothing was reading.
                        LOG.warning(mod.name() + " v" + mod.currentVersion()
                                + " failed to compile: " + firstLine(compiled.diagnostics()));
                        feedback.audience().sendMessage(Style.err("Stored version failed to compile: "
                                + firstLine(compiled.diagnostics())));
                        return;
                    }
                    try {
                        lifecycle.load(mod.name(), mod.currentVersion(), mod.description(),
                                mod.mainClass(), compiled.classes(),
                                mod.config(), store.resolvedConfigValues(mod.name()), mod.debugEcho());
                        store.setEnabled(mod.name(), true);
                        LOG.info(mod.name() + " v" + mod.currentVersion() + " is live");
                        feedback.audience().sendMessage(
                                Style.ok(mod.name() + " v" + mod.currentVersion() + " is live"));
                        if (onLive != null) {
                            onLive.run();
                        }
                    } catch (ModLoadException e) {
                        LOG.warning("Failed to start " + mod.name() + ": " + e.getMessage());
                        feedback.audience().sendMessage(
                                Style.err("Failed to start " + mod.name() + ": " + e.getMessage()));
                    }
                });
            });
        }

        /**
         * Boot restore: recompile every mod that was enabled when this world last
         * ran. Mods stamped for another platform (meta.json v3, §5) are skipped
         * with a log line rather than attempted — their sources compile against a
         * different sdk flavor.
         */
        private void restoreModsFromDisk() {
            for (ModStore.StoredMod mod : store.all()) {
                if (!mod.enabled()) {
                    continue;
                }
                if (!mod.platform().equalsIgnoreCase(platform.platformName())) {
                    LOG.info("Skipping mod " + mod.name() + ": generated for " + mod.platform()
                            + ", this server is " + platform.platformName());
                    continue;
                }
                LOG.info("Restoring mod " + mod.name() + " v" + mod.currentVersion());
                applyStoredVersion(console(), mod.name(), null);
            }
        }

        private static String firstLine(String s) {
            if (s == null) {
                return "unknown error";
            }
            int nl = s.indexOf('\n');
            return nl < 0 ? s : s.substring(0, nl);
        }
    }
}
