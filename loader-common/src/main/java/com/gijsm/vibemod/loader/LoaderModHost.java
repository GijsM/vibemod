package com.gijsm.vibemod.loader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import com.gijsm.vibemod.api.Mod;
import com.gijsm.vibemod.api.ModCommandHandler;
import com.gijsm.vibemod.api.TaskHandle;
import com.gijsm.vibemod.api.VibeContext;
import com.gijsm.vibemod.api.client.ClientContext;
import com.gijsm.vibemod.platform.Registration;
import com.gijsm.vibemod.runtime.ModDispatch;
import com.gijsm.vibemod.runtime.ModHandle;
import com.gijsm.vibemod.runtime.ModHost;
import com.gijsm.vibemod.runtime.ModLoadException;
import com.gijsm.vibemod.store.ModConfigs;

/**
 * A loader host's half of a mod's lifecycle (ARCHITECTURE-V2 §1.1): instantiate the
 * generated main class, hand it a Mojang-typed {@link VibeContext}, and route
 * every registration it makes through the SPI bridges so {@code ModLifecycle}
 * can revoke it later.
 *
 * <p>Structurally identical to {@code PaperModHost} — same activation token,
 * same {@link ModHandle} tracking, same main-thread assertion — and it has to
 * be, because {@code ModLifecycle} in core drives both through the same
 * {@link ModHost} seam. What differs is only what the context's methods are
 * typed in, which is the whole point of the two sdk flavors (§4.1).
 *
 * <p>The one genuinely new thing is {@code client(...)}: on a physical client it
 * hands the mod a {@link ClientContext} whose registrations are tracked as
 * {@link ModHandle.Kind#CLIENT}, so disabling a mod drains its HUD elements,
 * key leases and client commands exactly like its listeners. On a dedicated
 * server it does nothing at all, which is what lets one generated mod run on
 * both.
 *
 * <p>V3 Phase 0 (§C) adds a second kind of mod entirely: a <em>native</em> one,
 * whose main class implements the loader's own entrypoint interface and which
 * gets no {@link VibeContext} at all. {@link #activate} asks the injected
 * {@link EntrypointAdapter} first and falls through to the {@code Mod} path
 * unchanged when it says no, so the two flavors coexist with no meta.json
 * change — detection is by interface, which is a fact about the bytes rather
 * than a claim in a file that could disagree with them.
 */
public final class LoaderModHost implements ModHost {

    private final MinecraftServer server;
    private final Path dataFolder;
    private final LoaderEventBridge events;
    private final LoaderCommandBridge commands;
    private final ModConfigs configs;
    private final ModDispatch dispatch;
    private final LoaderTickScheduler scheduler;
    /**
     * Builds a mod's {@link ClientContext}, or null on a dedicated server.
     *
     * <p>A {@link Function} rather than the client bridge itself, so that this
     * class — which runs on dedicated servers — never names a client-only type.
     * The client entrypoint supplies it; on a server it stays null and
     * {@code ctx.client(...)} is a no-op, which is what lets one generated mod
     * run on both sides.
     */
    private final Function<ModHandle, ClientContext> clientContexts;
    /**
     * Recognises a native loader entrypoint (V3 Phase 0 §C), injected because
     * this class may not name {@code net.fabricmc.*} (§10.4). Never null;
     * {@link EntrypointAdapter#NONE} is the "this loader has none" value.
     */
    private final EntrypointAdapter entrypoints;

    public LoaderModHost(MinecraftServer server, Path dataFolder, LoaderEventBridge events,
                         LoaderCommandBridge commands, ModConfigs configs, ModDispatch dispatch,
                         LoaderTickScheduler scheduler, Function<ModHandle, ClientContext> clientContexts,
                         EntrypointAdapter entrypoints) {
        this.server = server;
        this.dataFolder = dataFolder;
        this.events = events;
        this.commands = commands;
        this.configs = configs;
        this.dispatch = dispatch;
        this.scheduler = scheduler;
        this.clientContexts = clientContexts;
        this.entrypoints = entrypoints == null ? EntrypointAdapter.NONE : entrypoints;
    }

    @Override
    public Object activate(ModHandle handle, ClassLoader loader, String mainClassFqcn) throws ModLoadException {
        Object obj;
        Runnable nativeInit;
        try {
            Class<?> mainClass = loader.loadClass(mainClassFqcn);
            obj = mainClass.getDeclaredConstructor().newInstance();
            nativeInit = entrypoints.adapt(handle, loader, obj);
            if (nativeInit == null && !(obj instanceof Mod)) {
                throw new ModLoadException(mainClassFqcn + " implements neither "
                        + Mod.class.getName() + " nor " + entrypoints.describe(), null);
            }
        } catch (ModLoadException e) {
            throw e;
        } catch (Exception e) {
            throw new ModLoadException(
                    "Failed to instantiate " + mainClassFqcn + " for mod " + handle.name(), e);
        }

        if (nativeInit != null) {
            return activateNative(handle, nativeInit);
        }

        Mod instance = (Mod) obj;
        Activation activation = new Activation(instance, new Context(handle));
        try {
            instance.onEnable(activation.context);
        } catch (Throwable t) {
            // The caller drains the handle's registrations and journals this as "onEnable".
            throw new ModLoadException("onEnable failed for mod " + handle.name(), t, "onEnable");
        }
        return activation;
    }

    /**
     * The V3 path (§C): a plain loader mod's {@code onInitialize()}.
     *
     * <p>{@link ModAttribution#runAs} is what makes the rest of V3 work.
     * Everything the mod registers inside this call reaches a host shim with no
     * mod identity of its own (§B), and the thread-local is where that identity
     * comes from — so every {@code Event.register} the entrypoint makes lands
     * on <em>this</em> handle and is drained with it.
     *
     * <p>Deliberately NOT routed through {@link ModDispatch}, unlike every later
     * dispatch into this mod: the failure has to come back as a
     * {@link ModLoadException} so the caller rolls the activation back and the
     * generator gets its repair round, and {@code ModDispatch} swallows by
     * contract. This mirrors the {@code onEnable} path directly above it, which
     * is journalled the same way for the same reason.
     */
    private Object activateNative(ModHandle handle, Runnable init) throws ModLoadException {
        Throwable[] failure = new Throwable[1];
        ModAttribution.runAs(handle, () -> {
            try {
                init.run();
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        if (failure[0] != null) {
            throw new ModLoadException("onInitialize failed for mod " + handle.name(),
                    failure[0], "onInitialize");
        }
        return new Activation(null, null);
    }

    @Override
    public void deactivate(ModHandle handle, Object activation) throws Exception {
        Activation live = (Activation) activation;
        // A native mod has no onDisable: a Fabric mod's contract is that it is
        // initialised once and never torn down, so there is nothing to call.
        // Draining the handle IS the teardown, and the caller does that next.
        if (live.instance != null) {
            live.instance.onDisable(live.context);
        }
    }

    /**
     * What {@link #activate} hands back: the mod instance plus the context it
     * was given, or a pair of nulls for a native mod (§C) that has neither.
     */
    private record Activation(Mod instance, VibeContext context) {
    }

    /**
     * Per-mod {@link VibeContext}. Every registration goes through an SPI bridge
     * and is tracked on the mod's {@link ModHandle}, which the lifecycle drains
     * on disable — the v2 generalization of v1's teardown (§0#10).
     */
    private final class Context implements VibeContext {

        private final ModHandle handle;

        Context(ModHandle handle) {
            this.handle = handle;
        }

        @Override
        public MinecraftServer server() {
            return server;
        }

        @Override
        public String modName() {
            return handle.name();
        }

        @Override
        public Logger log() {
            return Logger.getLogger("VibeMod." + handle.name());
        }

        @Override
        public Path dataFolder() {
            Path p = dataFolder.resolve("moddata").resolve(handle.name());
            try {
                Files.createDirectories(p);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return p;
        }

        // ---- scheduling ----

        @Override
        public TaskHandle repeat(long delayTicks, long periodTicks, Runnable task) {
            assertMainThread();
            return track(scheduler.repeat(delayTicks, periodTicks, wrapTask(task)));
        }

        @Override
        public TaskHandle later(long delayTicks, Runnable task) {
            assertMainThread();
            return track(scheduler.later(delayTicks, wrapTask(task)));
        }

        private TaskHandle track(com.gijsm.vibemod.platform.TaskHandle platform) {
            handle.track(ModHandle.Kind.TASK, platform);
            return new TaskHandle() {
                @Override
                public void cancel() {
                    platform.close();
                }

                @Override
                public boolean active() {
                    return platform.active();
                }
            };
        }

        private Runnable wrapTask(Runnable task) {
            return () -> dispatch.run(handle.name(), null, "task", task::run);
        }

        // ---- commands ----

        @Override
        public void command(String name, String description, ModCommandHandler handler) {
            assertMainThread();
            Registration registration = commands.register(name, description, handle.name(),
                    (sender, args) -> handler.run(LoaderSender.unwrap(sender), args));
            if (registration.active()) {
                handle.track(ModHandle.Kind.COMMAND, registration);
                handle.trackCommandName(name);
            } else {
                log().info("Top-level command /" + name + " unavailable, falling back to action");
                action(name, handler);
            }
        }

        @Override
        public void action(String name, ModCommandHandler handler) {
            assertMainThread();
            handle.trackAction(name.toLowerCase(Locale.ROOT),
                    (sender, args) -> handler.run(LoaderSender.unwrap(sender), args));
        }

        // ---- live config ----

        @Override
        public boolean configBool(String key) {
            return configs.bool(handle.name(), key);
        }

        @Override
        public long configInt(String key) {
            return configs.integer(handle.name(), key);
        }

        @Override
        public double configDouble(String key) {
            return configs.decimal(handle.name(), key);
        }

        @Override
        public String configString(String key) {
            return configs.text(handle.name(), key);
        }

        // ---- client ----

        @Override
        public boolean hasClient() {
            return clientContexts != null;
        }

        @Override
        public void client(Consumer<ClientContext> setup) {
            assertMainThread();
            if (clientContexts == null) {
                return;
            }
            setup.accept(clientContexts.apply(handle));
        }

        // ---- the curated server hooks (§4.1) ----

        @Override
        public void onPlayerJoin(Consumer<ServerPlayer> handler) {
            hook(() -> events.onPlayerJoin(handle.name(), handler));
        }

        @Override
        public void onPlayerQuit(Consumer<ServerPlayer> handler) {
            hook(() -> events.onPlayerQuit(handle.name(), handler));
        }

        @Override
        public void onServerTick(Consumer<MinecraftServer> handler) {
            hook(() -> events.onServerTick(handle.name(), handler));
        }

        @Override
        public void onChat(ChatHandler handler) {
            hook(() -> events.onChat(handle.name(), handler::handle));
        }

        @Override
        public void onBlockBreak(BlockHandler handler) {
            hook(() -> events.onBlockBreak(handle.name(), handler::handle));
        }

        @Override
        public void onUseBlock(UseHandler handler) {
            hook(() -> events.onUseBlock(handle.name(), handler::handle));
        }

        @Override
        public void onUseItem(UseHandler handler) {
            hook(() -> events.onUseItem(handle.name(), handler::handle));
        }

        @Override
        public void onEntityDeath(BiConsumer<LivingEntity, DamageSource> handler) {
            hook(() -> events.onEntityDeath(handle.name(), handler));
        }

        @Override
        public void onPlayerDeath(Consumer<ServerPlayer> handler) {
            hook(() -> events.onPlayerDeath(handle.name(), handler));
        }

        @Override
        public void onRespawn(Consumer<ServerPlayer> handler) {
            hook(() -> events.onRespawn(handle.name(), handler));
        }

        /** Asserts BEFORE registering: an off-thread hook must not half-register. */
        private void hook(java.util.function.Supplier<Registration> register) {
            assertMainThread();
            handle.track(ModHandle.Kind.LISTENER, register.get());
        }

        private void assertMainThread() {
            if (!scheduler.onMain()) {
                throw new IllegalStateException(
                        "VibeContext must only be used from the main server thread");
            }
        }
    }
}
