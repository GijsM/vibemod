package com.gijsm.vibemod.fabric;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import com.gijsm.vibemod.fabric.dimension.DimensionContent;
import com.gijsm.vibemod.fabric.dynamic.DynamicContent;
import com.gijsm.vibemod.fabric.dynamic.ProxyGate;
import com.gijsm.vibemod.fabric.dynamic.ReconfigureBouncer;
import com.gijsm.vibemod.fabric.net.ContentSync;
import com.gijsm.vibemod.fabric.pack.FabricPackServer;
import com.gijsm.vibemod.fabric.project.VanillaLane;
import com.gijsm.vibemod.fabric.shim.CreativeTabs;
import com.gijsm.vibemod.fabric.shim.EventFanout;
import com.gijsm.vibemod.fabric.shim.PaletteGuard;
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
     * The process-lived block-palette guard (V4 Phase 1); built once, in
     * {@link #onInitialize()}.
     *
     * <p>Process-lived like the seam beside it, and for a sharper reason of its
     * own: a crossing mutates {@code Strategy.globalPaletteBitsInMemory} on
     * strategies that outlive nothing in particular, and the repack count it
     * keeps is a property of the JVM rather than of a world. It reads the live
     * server through a supplier and finds null between worlds, at which point it
     * refuses a crossing rather than guessing.
     */
    private static volatile PaletteGuard paletteGuard;

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
     *
     * <p>{@code dynamic} (V4 Phase 5) is per-server for the same reason
     * {@code reloads} is: the sweep reads the world's materialised datapacks and
     * the bouncer counts ticks on a live server, and neither means anything
     * between worlds. Its one un-undoable subscription lives elsewhere — see
     * {@code DynamicContent.installProcessListeners} in {@link #onInitialize()}.
     */
    public record Services(MinecraftServer server, FabricPlatformInfo platform, ModLifecycle lifecycle,
                           ModStore store, ModErrors errors, LoaderMessenger messenger,
                           LoaderTickScheduler scheduler, UiRenderer ui, VibeRouter router,
                           ChatRenderer chatRenderer, ReloadCoordinator reloads,
                           DynamicContent dynamic, DimensionContent dimensions) {
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

    /**
     * The process-lived block-palette guard (V4 Phase 1), for the acceptance
     * gates and for {@code /vibe info}'s palette line.
     *
     * <p>Never null after {@link #onInitialize()}.
     */
    public static PaletteGuard paletteGuard() {
        return paletteGuard;
    }

    /**
     * The dynamic-registry facade for the current server (V4 Phase 5), or null
     * between worlds.
     *
     * <p>Reached through {@link Services} rather than a static of its own,
     * because unlike the three above it does NOT outlive a world. The accessor
     * exists so the gates can reach it without holding the whole record.
     */
    public static DynamicContent dynamicContent() {
        Services live = services;
        return live == null ? null : live.dynamic();
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

        // V4 Phase 1. Built here rather than per-server because the thing it
        // guards — the global blockstate id space — is per-JVM: it is appended
        // to from the registration window and never reclaimed, world or no
        // world.
        //
        // The seam calls admit() before appending a block's states, and lets the
        // refusal propagate. Handing the guard over here rather than constructing
        // it inside the seam keeps it per-JVM while the seam is rebuilt per
        // server: measuring the budget against a registry that outlives the world
        // is the whole point.
        paletteGuard = new PaletteGuard(() -> {
            Services live = services;
            return live == null ? null : live.server();
        });
        registrySeam.setPaletteGuard(paletteGuard);

        // V4 Phase 2 (Lane A), here and nowhere else: every one of the six
        // things this call makes — four payload-type registrations, a phase
        // ordering on BEFORE_CONFIGURE, two connection listeners and two global
        // receivers — is permanent. A per-server install would leave a dead
        // manifest sender behind for every world ever loaded, and
        // PayloadTypeRegistry would throw on the second one anyway.
        //
        // It also hands the seam two things it cannot build for itself: the
        // dedicated-server policy the seam consults before admitting content,
        // and a subscription to Fabric's raw-id remap, which is what keeps a
        // reconnecting client's ids agreeing with the ones it was told about.
        ContentSync.install(registrySeam);
        // V4 Phase 4, Lane B, and it must come AFTER the line above: this
        // listener registers into ContentSync.PHASE, whose ordering relative to
        // fabric-api's own BEFORE_CONFIGURE listener is established there.
        //
        // What it installs is the half that makes a vanilla client possible at
        // all: a redirect that hides VibeMod's entries from fabric-api's sync
        // map for a connection that cannot receive our manifest. Without it that
        // client is disconnected during configuration by fabric-api itself, with
        // a message naming neither VibeMod nor the item. If the redirect fails
        // to apply, VanillaLane refuses the connection itself, with a message
        // that does name us — an honest refusal beating an anonymous kick.
        VanillaLane.install(registrySeam, () -> {
            Services live = services;
            return live == null ? null : live.server();
        });

        // V4 Phase 5, same rule, one subscription: the JOIN listener the bounce
        // debounce needs. The facade behind it is PER-SERVER (it reads the
        // world's datapacks), so what is registered here is the supplier, not
        // the instance — null between worlds, exactly like the fanout above.
        DynamicContent.installProcessListeners(() -> {
            Services live = services;
            return live == null ? null : live.dynamic();
        });
        // V4 Phase 6, and the same supplier-not-instance reason: it snapshots at
        // BEFORE_CONFIGURE which dimension_type ids each connecting client
        // actually holds, which is the roster a teleport is later refused
        // against. DimensionType.STREAM_CODEC is a bare registry index with no
        // inline-value branch, so a client one entry short does not render a
        // wrong dimension — it fails the decode and the connection ends.
        DimensionContent.installProcessListeners(() -> {
            Services live = services;
            return live == null ? null : live.dimensions();
        });

        // The note* calls are what make the fanout's immediate-replay honest
        // (V3 Phase 1 §A): a mod hot-loaded after an event has fired needs it
        // replayed, and a mod loaded BEFORE it fires must not get it twice. Only
        // the host can tell the fanout which of those it is, because at the
        // moment these fire nothing has subscribed through it yet.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            eventFanout.noteServerStarting();
            start(server);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            eventFanout.noteServerStarted();
            // One line, once per server: the real blockstate count and the real
            // headroom on whatever version is actually running, which is the
            // honest replacement for a figure computed from a data dump.
            paletteGuard.probe();
            // V4 Phase 5. One sweep at STARTED, and it is not redundant with the
            // one applyStoredVersion runs per mod: a world whose datapacks were
            // edited on disk while the server was down has content no load event
            // will ever announce. The sweep is idempotent, so on a world where
            // nothing changed this costs a directory walk and says nothing.
            Services live = services;
            if (live != null) {
                live.dynamic().apply("server started");
                // AFTER the sweep above, and the order is the point: a recorded
                // dimension may name a dimension_type that only that sweep has
                // just put back. Re-opening it first would refuse itself.
                live.dimensions().serverStarted(server);
            }
        });
        // V4 Phase 6. STOPPING, not STOPPED, and the difference is load-bearing:
        // at STOPPING the levels are still open and the session still holds the
        // directory lock, which is exactly what a temporary dimension needs in
        // order to close its chunk source and delete its own folder. By STOPPED
        // both are gone and the folder would be left behind.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            Services live = services;
            if (live != null) {
                live.dimensions().serverStopping(server);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            eventFanout.noteServerStopped();
            stop();
            // Process-lived state, so it does NOT belong in stop() with the
            // per-server teardown: the in-flight reconfigure marks are keyed by
            // player and read by the process-lived DISCONNECT listener below,
            // which is still armed after the last world closes. Clearing them
            // here is what stops world B from inheriting world A's marks.
            DynamicContent.serverStopped();
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
                // V4 Phase 5: the bounce debounce and its rate limits are
                // counted in ticks, so they ride the subscription that already
                // exists and inherit its per-server lifetime — a bounce armed by
                // world A must not fire into world B.
                live.dynamic().tick();
                // V4 Phase 6: finishes an armed drain. A closing dimension is
                // not removed until it is genuinely idle — no players, no loaded
                // chunks, no pending chunk work — so the close is a request and
                // this is what completes it.
                live.dimensions().tick();
            }
            // V4 Phase 1: the post-crossing straggler watch rides it for the
            // same reason. Outside the `live != null` guard because the guard
            // outlives any one server (it is per-JVM, like the id space it
            // watches), and disarmed it is one volatile read — which is every
            // tick of a server that never crosses the palette boundary.
            PaletteGuard palette = paletteGuard;
            if (palette != null) {
                palette.tick();
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

        // V4 Phase 5 put a guard in front of the forget, and the guard is a BELT
        // rather than a contract. On 26.2 with fabric-api 0.158.0 this event does
        // not fire for a bounce at all: the play→configuration swap goes through
        // a setListener injection that only calls endSession, so the disconnect
        // half never runs. The guard costs one map lookup and covers the case
        // where that split changes, because the split it relies on is two
        // @Inject sites in someone else's mixin.
        //
        // Skipping is safe in the other direction too: a bounce that FAILS is a
        // real disconnect, and its mark expires on a timer rather than on a
        // packet, so the forget happens a moment late instead of never.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.player.getUUID();
            if (DynamicContent.isReconfiguring(id)) {
                return;
            }
            forget(id);
        });
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
        // V4 Phase 3. The pack server is per-server despite holding a socket:
        // the tree it publishes is this world's mods, and a port left listening
        // after the world closed would serve a stale zip to whoever asked next —
        // and refuse to bind when world B starts. The Fabric event half of Phase
        // 3 (FabricPackPush) is NOT torn down here; it is installed once per JVM
        // from startIfEnabled and finds a null pack server between worlds.
        FabricPackServer.stopCurrent();
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
                InstallCard.setRegisteredBlocks(ledger::blockIdsOf);
                // V4 Phase 1. A deleted mod's BLOCK ids are pinned rather than
                // tombstoned, and this is the half that makes the pin mean
                // something: every pinned id comes back as an inert stub before
                // a single live mod is restored, so a saved chunk that names one
                // still decodes. Without this line the id is simply absent, the
                // section palette's ListCodec drops it, and every entry after it
                // shifts — which rewrites that chunk's terrain and says almost
                // nothing in the log. It runs here, not later, because
                // restoreModsFromDisk() below must find the ids already claimed.
                registrySeam.replayPinnedBlocks(ledger);
            }

            // V3 Phase 2 §B/§C. Both halves are loader-neutral: the datapack
            // channel is pure vanilla API (vanilla's own folder RepositorySource
            // already scans <world>/datapacks/, so nothing is injected on the
            // server side), and the client half is reached through two
            // interfaces that name no client type, so this whole block is
            // identical on NeoForge.
            // V4 Phase 3 adds the third delivery route beside those two: a
            // dedicated server has no FabricClientPacks to write into, so it
            // publishes assets/** over HTTP and pushes the URL at configuration
            // time. Null on a physical client and when packserver.mode=off, and
            // both sinks below treat null as "this host does not do that",
            // exactly as they already treat a null client sink on a dedicated
            // server. FabricPackPush.installOnce() happens inside
            // startIfEnabled, so the un-undoable Fabric subscription it makes is
            // made at most once per JVM however many worlds this one loads.
            FabricPackServer packServer =
                    FabricPackServer.startIfEnabled(dataFolder, config, platform.isDedicatedServer());
            reloads = new ReloadCoordinator(server, hooks == null ? null : hooks.reloader(), packServer);

            // V4 Phase 5. The gate is asked, before a bounce, whether this
            // server is one where kicking a player back to configuration is safe
            // — a proxy in front of it turns a bounce into a disconnect — and it
            // answers from the game and config directories rather than from a
            // setting, because the setting is the thing most likely to be wrong.
            // Built per-server because `auto` reads this world's connections.
            ProxyGate proxies = new ProxyGate(
                    proxyMode(config.getString("dynamic.proxy", "auto")),
                    FabricLoader.getInstance().getGameDir(),
                    FabricLoader.getInstance().getConfigDir(),
                    platform.isDedicatedServer());
            // The announcer is the one thing the dynamic package cannot build for
            // itself: a bounce is visible to players, and telling them is the
            // host's job because the messenger is per-server. Two methods rather
            // than one because "everyone is being reconnected" and "you are being
            // reconnected" are different sentences to be in the middle of.
            DynamicContent dynamic = new DynamicContent(() -> server, proxies,
                    new ReconfigureBouncer.Announcer() {
                        @Override public void all(String m) { messenger.broadcast(Style.info(m)); }
                        @Override public void player(UUID id, String m) {
                            messenger.player(id).sendMessage(Style.info(m));
                        }
                    });

            // V4 Phase 6. Per-server, because a dimension is a thing inside one
            // world: the roster, the ledger and the open levels all belong to it.
            DimensionContent dimensions = new DimensionContent(() -> server);

            lifecycle.setContent(new LoaderModContent(server, store, reloads,
                    hooks == null ? null : hooks.resources(), packServer));

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
                    scheduler, ui, router, chatRenderer, reloads, dynamic, dimensions);

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
                        // Logged as well as sent, and that is not double-reporting.
                        // This runs on a worker and answers whoever asked — but over
                        // RCON that connection was answered and closed long ago, so
                        // the diagnostic goes nowhere at all: no reply, nothing in
                        // the log, a mod that simply never appears. Every scripted
                        // admin path has that shape, and the palette gate lost an
                        // afternoon to it.
                        LOG.warning("Stored version of " + mod.name() + " failed to compile: "
                                + firstLine(compiled.diagnostics()));
                        feedback.audience().sendMessage(Style.err("Stored version failed to compile: "
                                + firstLine(compiled.diagnostics())));
                        return;
                    }
                    try {
                        lifecycle.load(mod.name(), mod.currentVersion(), mod.description(),
                                mod.mainClass(), compiled.classes(),
                                mod.config(), store.resolvedConfigValues(mod.name()), mod.debugEcho());
                        // V4 Phase 5, and THIS is the point where the files
                        // exist: lifecycle.load runs LoaderModContent.install →
                        // materialize, so the mod's data/** is on disk by the
                        // time this line runs and a sweep can see it. Every route
                        // that makes a stored version live goes through here —
                        // rollback, enable, /vibe reload, boot restore — so one
                        // call covers all of them, and the sweep is idempotent,
                        // so a mod that declares nothing dynamic costs a walk.
                        services.dynamic().apply(mod.name() + " loaded");
                        store.setEnabled(mod.name(), true);
                        feedback.audience().sendMessage(
                                Style.ok(mod.name() + " v" + mod.currentVersion() + " is live"));
                        if (onLive != null) {
                            onLive.run();
                        }
                    } catch (ModLoadException e) {
                        // Same reason as the compile failure above.
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
         *
         * <p>V4 Phase 2 changed the ORDER this walks in, from whatever the store
         * happens to list to the ledger's recorded restore order. It matters
         * because raw registry ids are assigned in registration order, and a
         * client that reconnects into the same epoch should find the ids where it
         * left them.
         *
         * <p>Best-effort, and deliberately not claimed as more: {@link
         * #applyStoredVersion} compiles on a worker and calls {@code
         * lifecycle.load} from {@code runOnMain}, so when two compiles race the
         * order mods actually REGISTER in is the order their compiles finished,
         * not the order of this loop. That is benign — Fabric remaps raw ids on
         * join and the store keeps string ids — and the ledger's order cursor is
         * per mod, so racing mods cannot trip each other on it. But "boot restore
         * follows the ledger" is an intention until this restore is made
         * sequential, not a guarantee.
         */
        private void restoreModsFromDisk() {
            Map<String, ModStore.StoredMod> byName = new LinkedHashMap<>();
            for (ModStore.StoredMod mod : store.all()) {
                byName.put(mod.name(), mod);
            }
            // Null ledger is the client-with-no-seam case; then the store's own
            // order is the only order there is.
            RegistryLedger book = registrySeam == null ? null : registrySeam.ledger();
            List<String> names = book == null
                    ? List.copyOf(byName.keySet())
                    : book.inRestoreOrder(List.copyOf(byName.keySet()));
            for (String name : names) {
                ModStore.StoredMod mod = byName.get(name);
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

        /**
         * {@code dynamic.proxy}, tolerantly.
         *
         * <p>A typo here used to throw {@code IllegalArgumentException} out of
         * {@code valueOf} from inside {@code wire()}, which aborts world start —
         * a mistyped config value taking the whole server down is a worse
         * outcome than the thing the setting guards. Falling back to
         * {@code AUTO} is also the safe direction: auto detects proxies and
         * closes the gate, so a typo cannot silently enable mid-play
         * reconfiguration behind a Velocity.
         */
        private static ProxyGate.Mode proxyMode(String configured) {
            String name = configured.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            try {
                return ProxyGate.Mode.valueOf(name);
            } catch (IllegalArgumentException e) {
                LOG.warning("dynamic.proxy is \"" + configured + "\", which is not one of "
                        + java.util.Arrays.toString(ProxyGate.Mode.values())
                        + "; falling back to AUTO, which detects proxies and disables mid-play "
                        + "reconfiguration when it finds one");
                return ProxyGate.Mode.AUTO;
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
