package com.gijsm.vibemod.fabric;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import com.gijsm.vibemod.api.client.ClientContext;
import com.gijsm.vibemod.command.VibeRouter;
import com.gijsm.vibemod.compile.CompileResult;
import com.gijsm.vibemod.compile.InMemoryCompiler;
import com.gijsm.vibemod.compile.SymbolOracle;
import com.gijsm.vibemod.fabric.shim.CreativeTabs;
import com.gijsm.vibemod.fabric.shim.EventFanout;
import com.gijsm.vibemod.fabric.shim.RegistrySeam;
import com.gijsm.vibemod.fabric.shim.Shims;
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
import com.gijsm.vibemod.loader.content.ClientReloader;
import com.gijsm.vibemod.loader.content.ClientResourceSink;
import com.gijsm.vibemod.loader.content.LoaderModContent;
import com.gijsm.vibemod.loader.content.ReloadCoordinator;
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
import com.gijsm.vibemod.store.RegistryLedger;
import com.gijsm.vibemod.ui.InstallCard;
import com.gijsm.vibemod.ui.Progress;
import com.gijsm.vibemod.ui.Style;
import com.gijsm.vibemod.ui.chat.ChatRenderer;
import com.gijsm.vibemod.ui.screens.FormScreens;
import com.gijsm.vibemod.ui.screens.HubScreens;
import com.gijsm.vibemod.ui.screens.InfoScreens;
import com.gijsm.vibemod.ui.screens.SettingsScreens;

/**
 * VibeMod on Fabric: the {@code main} entrypoint, and nothing but a bootstrap.
 *
 * <p>The shape mirrors Paper's {@code VibeMod} plugin class exactly — probe what
 * the platform can do, build the SPI implementations, hand them to the
 * platform-free engine in {@code core}, pick a renderer — because that is the
 * whole point of the v2 architecture. Everything below the SPI is shared; the
 * only thing a host writes is the wiring.
 *
 * <p>One structural difference the loader forces: a Fabric mod initializes
 * before a server exists, and on a client no server may ever exist. So this
 * class registers exactly one thing at init — a {@code SERVER_STARTING} hook —
 * and everything else is built inside it, torn down at {@code SERVER_STOPPED},
 * and built again if the player loads another world in the same JVM.
 *
 * <p>{@link #services()} is how the client entrypoint reaches the live host
 * without either half depending on the other's classes.
 */
public final class VibeModFabric implements ModInitializer {

    private static final Logger LOG = Logger.getLogger("VibeMod");

    /** Set by the client entrypoint before a server exists; null on a dedicated server. */
    private static volatile ClientHooks clientHooks;
    /** The live services, or null between worlds. */
    private static volatile Services services;
    /**
     * The live bridges, or null between worlds.
     *
     * <p>Separate from {@link #services} because the process-lived subscriptions
     * in {@link #onInitialize()} need them, and because they exist slightly
     * earlier in {@code wire()} than the fully-built {@link Services} record
     * does.
     */
    private static volatile FabricEventBridge eventBridge;
    private static volatile FabricChatBridge chatBridge;
    private static volatile LoaderCommandBridge commandBridge;
    /**
     * The guarded-entry helper for the current server, or null between worlds.
     *
     * <p>Published for the same reason the bridges above are: the V3 event
     * fanout (§B) is PROCESS-lived — it owns permanent {@code Event.register}
     * calls that cannot be undone — while {@code ModDispatch} is per-server. So
     * the fanout resolves it at dispatch time and finds null between worlds,
     * exactly like every other process-lived subscription in this file.
     */
    private static volatile ModDispatch modDispatch;
    /** The process-lived event fanout (V3 Phase 0 §B); built once, in {@link #onInitialize()}. */
    private static volatile EventFanout eventFanout;
    /**
     * The process-lived registry seam (V3 Phase 3 §A); built once, in
     * {@link #onInitialize()}.
     *
     * <p>Process-lived for the same reason the fanout is, plus one of its own:
     * it holds the creative-tab listener, which is an {@code Event.register}
     * that cannot be undone.
     */
    private static volatile RegistrySeam registrySeam;

    /**
     * What the client entrypoint contributes: the bridge, its watchdog wiring,
     * and the factory for a mod's {@code ClientContext}.
     *
     * <p>A record rather than a direct reference so that this class — which runs
     * on dedicated servers — never names a client-only type. Only
     * {@link ClientEventBridge} (platform-api), {@link ClientContext}
     * (sdk-client, pure JDK) and V3 Phase 2's {@link ClientResourceSink} /
     * {@link ClientReloader} (loader-common, deliberately free of client types
     * for exactly this reason) appear here, and all four exist on every side.
     */
    public record ClientHooks(ClientEventBridge bridge,
                              Function<ModHandle, ClientContext> contexts,
                              Watchdog renderWatchdog,
                              ClientResourceSink resources,
                              ClientReloader reloader) {
    }

    /**
     * Everything the host built for the currently running server.
     *
     * <p>{@code chatRenderer} is null when native dialogs are in use — then there
     * is no per-player form state to forget.
     */
    public record Services(MinecraftServer server, FabricPlatformInfo platform, ModLifecycle lifecycle,
                           ModStore store, ModErrors errors, LoaderMessenger messenger,
                           LoaderTickScheduler scheduler, UiRenderer ui, VibeRouter router,
                           ChatRenderer chatRenderer, ReloadCoordinator reloads) {
    }

    /** The live services, or null when no server is running. */
    public static Services services() {
        return services;
    }

    /**
     * The process-lived event fanout (V3 Phase 0 §B), for the acceptance gates.
     *
     * <p>Never null after {@link #onInitialize()}, and deliberately not part of
     * {@link Services}: it outlives every server in this JVM.
     */
    public static EventFanout eventFanout() {
        return eventFanout;
    }

    /**
     * The process-lived registry seam (V3 Phase 3 §A), for the acceptance gates
     * and for {@code /vibe info}'s "registered content" line.
     */
    public static RegistrySeam registrySeam() {
        return registrySeam;
    }

    /** Called by the client entrypoint at its own init, before any server starts. */
    public static void setClientHooks(ClientHooks hooks) {
        clientHooks = hooks;
    }

    /**
     * Every Fabric subscription VibeMod makes on the server side, made exactly
     * once, here.
     *
     * <p>This is not tidiness — it is the same rule §8.1 states for the client,
     * and it applies just as hard to the host. A Fabric {@code Event} cannot be
     * unregistered. A client can load world A, quit to the menu, and load world
     * B in the same process, and each of those starts and stops a whole VibeMod
     * host. Registering from that per-server bootstrap therefore leaves one
     * subscription per world ever loaded, all but the last dispatching into a
     * dead scheduler and a dead bridge. So the subscriptions live for the
     * process and resolve the live per-server objects when they fire, which is
     * null between worlds.
     */
    @Override
    public void onInitialize() {
        LOG.info("VibeMod loading — waiting for a server");

        // V3 Phase 0 §B, and it belongs in this method for exactly the reason
        // stated above: the fanout registers one permanent listener per event a
        // mod ever subscribes to, and those cannot be undone. A per-server
        // fanout would leave one dead proxy per world behind. It reads the live
        // dispatch and server through suppliers and finds null between worlds.
        eventFanout = new EventFanout(() -> modDispatch, () -> {
            Services live = services;
            return live == null ? null : live.server();
        });
        Shims.install(eventFanout);

        // V3 Phase 3 §A, in this method for the same reason and one more: the
        // creative-tab listener below is an Event.register that cannot be
        // undone, so it must be made exactly once per process.
        registrySeam = new RegistrySeam(() -> {
            Services live = services;
            return live == null ? null : live.server();
        }, () -> {
            Services live = services;
            return live == null ? null : live.reloads();
        });
        Shims.installRegistries(registrySeam);
        CreativeTabs.install(registrySeam);

        // The note* calls are what make the fanout's immediate-replay honest
        // (V3 Phase 1 §A): a mod hot-loaded after an event has fired needs it
        // replayed, and a mod loaded BEFORE it fires must not get it twice. Only
        // the host can tell the fanout which of those it is, because at the
        // moment these fire nothing has subscribed through it yet.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            eventFanout.noteServerStarting();
            start(server);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> eventFanout.noteServerStarted());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            eventFanout.noteServerStopped();
            stop();
        });

        // ORDER MATTERS, and it is the one line in this file that is a decision
        // rather than a sequence. Both of these register an ALLOW_CHAT_MESSAGE
        // listener; Fabric runs them in registration order and the first `false`
        // wins. The chat bridge is the form-capture path — when a player is
        // filling in a text field on a chat-rendered screen, that line is form
        // input, not chat, and no generated mod has any business seeing it.
        // Registering the capture FIRST means a captured line is swallowed
        // before the event bridge offers it to anybody's onChat hook, which is
        // exactly what NeoForge does with EventPriority.HIGHEST. Phase E called
        // the divergence out (§10.4 deviation 7) and named NeoForge's order the
        // better one; this is Phase F taking it.
        FabricChatBridge.installDispatcher(() -> chatBridge);
        FabricEventBridge.installDispatchers(() -> eventBridge);

        // fabric-permission-api-v1 injects checkPermission onto CommandSourceStack;
        // NeoForge answers the same question through PermissionAPI, so which one
        // LoaderSender asks is installed here rather than branched on (§10.4).
        LoaderSender.setPermissionOracle(CommandSourceStack::checkPermission);

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Services live = services;
            if (live != null) {
                live.scheduler().tick();
                // V3 Phase 2 §C: the reload debounce rides the subscription that
                // already exists rather than making a second one, and inherits
                // its "null between worlds" lifetime for free.
                live.reloads().tick();
            }
        });

        // Fires at startup AND after every /reload, each time with a fresh
        // dispatcher — which would silently drop every generated-mod command if
        // the bridge did not re-add them.
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            LoaderCommandBridge live = commandBridge;
            if (live != null) {
                live.reinstallInto(dispatcher);
            }
            // V3 Phase 1 §A, and the ORDER is the policy: the host reinstalls
            // /vibe and its own dynamic commands first, so a generated mod that
            // names one of them loses the collision and is told so, rather than
            // silently taking over the command that disables it.
            eventFanout.commands().hostCallbackFired(dispatcher, registry, environment);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> forget(handler.player.getUUID()));
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
        Path dataFolder = FabricLoader.getInstance().getGameDir().resolve("vibemod");
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException e) {
            LOG.severe("Could not create " + dataFolder + ": " + e);
            return;
        }
        LoaderConfig config = new LoaderConfig(
                FabricLoader.getInstance().getConfigDir().resolve("vibemod.json"));

        FabricPlatformInfo platform = new FabricPlatformInfo(server.isDedicatedServer());
        PlatformProfile profile = PlatformProfiles.forPlatform(platform);
        LOG.info("Platform: " + platform.describe() + " → prompt profile " + profile.displayName());

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
                new FabricClasspathProvider(dataFolder), platform.maxTargetRelease());
        // V3 Phase 0 §A. Installed on the COMPILER rather than on any one code
        // path, because every route from source to live classes goes through
        // it: generation, repair rounds, /vibe edit, rollback, restore-on-boot.
        compiler.setSurgeon(FabricSeams.surgeon());
        LOG.info("Bytecode seams: " + FabricSeams.table().size()
                + " call site(s) rewritten, policy enforced before defineClass");

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
        // The fanout itself survives (its subscriptions are permanent); what it
        // must stop finding is a dead dispatch.
        modDispatch = null;
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
     * The key lookup, in the same order as Paper's: config, then environment,
     * then {@code ~/.config/vibemod/openrouter.key}. Never hardcoded anywhere.
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
        private final FabricPlatformInfo platform;
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
        // NO `commandBridge`/`chatBridge` fields here, and their absence is
        // load-bearing rather than tidiness. Both used to be declared, which
        // silently shadowed the statics of the same name above — so `wire()`
        // assigned the SHADOWS, the process-lived subscriptions in
        // onInitialize() kept reading a null static, and two things quietly did
        // not work: `/vibe` and every generated command vanished on the first
        // `/reload` (CommandRegistrationCallback found no bridge to reinstall
        // into), and `ctx.onChat` never fired at all (the chat dispatcher's
        // supplier answered null forever). NeoForge's identical Boot never
        // declared them and never had the bug. Found by the V3 Phase 1 gate's
        // new `/reload` assertion.
        private ChatRenderer chatRenderer;
        private UiRenderer ui;
        private ModGenerator generator;
        private ReloadCoordinator reloads;
        private FormScreens forms;
        private HubScreens hub;
        private InfoScreens info;
        private SettingsScreens settingsScreens;
        private Services services;

        Boot(MinecraftServer server, Path dataFolder, LoaderConfig config, FabricPlatformInfo platform,
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
            // subscriptions themselves were made once, in onInitialize().
            commandBridge = new LoaderCommandBridge(server, messenger, dispatch,
                    config.getBoolean("commands.allow-top-level", true));
            eventBridge = new FabricEventBridge(dispatch);
            chatBridge = new FabricChatBridge(scheduler);
            modDispatch = dispatch;

            ClientHooks hooks = clientHooks;
            LoaderModHost modHost = new LoaderModHost(server, dataFolder, eventBridge, commandBridge,
                    configs, dispatch, scheduler, hooks == null ? null : hooks.contexts(),
                    new FabricEntrypointAdapter());
            lifecycle = new ModLifecycle(modHost, scheduler, messenger, watchdog, configs, modErrors, debugEcho);

            // V3 Phase 3 §A. The ledger is per installation, not per mod
            // version, so it lives next to the store rather than inside it —
            // rolling a mod back to v1 must not roll back the fact that an id
            // exists. Deleting a mod tombstones its ids; disabling one does not.
            if (registrySeam != null) {
                RegistryLedger ledger = new RegistryLedger(
                        dataFolder.resolve(RegistryLedger.FILE_NAME));
                registrySeam.setLedger(ledger);
                lifecycle.onUnload(registrySeam::tombstone);
                InstallCard.setRegisteredContent(name -> ledger.entriesOf(name).stream()
                        .map(RegistryLedger.Entry::id).toList());
            }

            // V3 Phase 2 §B/§C. Both halves are loader-neutral: the datapack
            // channel is pure vanilla API (vanilla's own folder RepositorySource
            // already scans <world>/datapacks/, so nothing is injected on the
            // server side), and the client half is reached through two
            // interfaces that name no client type, so this whole block is
            // identical on NeoForge.
            reloads = new ReloadCoordinator(server, hooks == null ? null : hooks.reloader());
            lifecycle.setContent(new LoaderModContent(server, store, reloads,
                    hooks == null ? null : hooks.resources()));

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

            generator = new ModGenerator(scheduler, profile, client, compiler, store, lifecycle,
                    () -> config.getInt("generation.max-retries", 3),
                    () -> config.getBoolean("openrouter.streaming", true),
                    config.getInt("generation.concurrency", 4));
            // V3 Phase 0 §D: the repair round gets to look up what the type
            // REALLY offers. The host's own loader is the right one to ask —
            // it is the one that has the game and the Fabric API on it.
            generator.setSymbolOracle(SymbolOracle.forLoader(VibeModFabric.class.getClassLoader()));
            // V3 Phase 4 §C: which SIDE this process is. The Fabric profile is
            // the only one whose rules branch on it — assets, the client
            // entrypoint and the registry seam all behave differently on a
            // dedicated server — and until the live demo it stated both branches
            // and let the model pick. It picked the registry, was refused, and
            // paid for a repair round to learn what this line says for free.
            generator.setHostFacts(PlatformProfiles.fabricHostFacts(platform.isDedicatedServer()));
            JarExporter exporter = new JarExporter(compiler, profile);

            ChatMode chatMode = new ChatMode(chatBridge, this::generateFromPrompt);
            VibeRouter router = new VibeRouter(scheduler, messenger, platform, dataFolder,
                    generator, lifecycle, store, configs, modErrors, debugEcho, catalog, exporter,
                    chatMode, ui, chatRenderer, forms, hub, info, settingsScreens,
                    this::senderFor,
                    client::model, this::setModel, client::sessionCostUsd, this::applyStoredVersion,
                    this::reloadConfig);
            commandBridge.setRouter(router);
            // CommandRegistrationCallback already fired for THIS server's
            // dispatcher, before the bridge existed, so install into it directly.
            // The process-lived callback covers every later /reload.
            commandBridge.reinstallInto(server.getCommands().getDispatcher());

            services = new Services(server, platform, lifecycle, store, modErrors, messenger,
                    scheduler, ui, router, chatRenderer, reloads);

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
