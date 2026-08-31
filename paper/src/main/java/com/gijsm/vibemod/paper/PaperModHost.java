package com.gijsm.vibemod.paper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Logger;

import org.bukkit.Server;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import com.gijsm.vibemod.api.Mod;
import com.gijsm.vibemod.api.ModCommandHandler;
import com.gijsm.vibemod.api.VibeContext;
import com.gijsm.vibemod.platform.CommandBridge;
import com.gijsm.vibemod.platform.EventBridge;
import com.gijsm.vibemod.platform.Registration;
import com.gijsm.vibemod.runtime.ModDispatch;
import com.gijsm.vibemod.runtime.ModHandle;
import com.gijsm.vibemod.runtime.ModHost;
import com.gijsm.vibemod.runtime.ModLoadException;
import com.gijsm.vibemod.store.ModConfigs;

/**
 * The Paper half of a mod's lifecycle (ARCHITECTURE-V2 §1.1): instantiate the
 * generated main class, hand it a Bukkit-typed {@link VibeContext}, and route
 * every registration it makes through the SPI bridges so
 * {@code ModLifecycle} can revoke it later.
 *
 * <p>Everything Bukkit-shaped about running a mod is here and nowhere else. The
 * {@code VibeContext} surface below is the frozen v3 contract that all 49 stored
 * mods (569 sources) compile against, so its signatures — {@code Plugin},
 * {@code Server}, {@code Listener}, {@code BukkitTask} — cannot change. That
 * frozenness is precisely why the lifecycle around it had to be expressed over
 * an opaque activation token instead: core cannot name any of these types.
 */
public final class PaperModHost implements ModHost {

    private final Plugin plugin;
    private final EventBridge events;
    private final CommandBridge commands;
    private final ModConfigs configs;
    private final ModDispatch dispatch;
    private final BukkitTaskScheduler scheduler;

    public PaperModHost(Plugin plugin, EventBridge events, CommandBridge commands, ModConfigs configs,
                        ModDispatch dispatch, BukkitTaskScheduler scheduler) {
        this.plugin = plugin;
        this.events = events;
        this.commands = commands;
        this.configs = configs;
        this.dispatch = dispatch;
        this.scheduler = scheduler;
    }

    @Override
    public Object activate(ModHandle handle, ClassLoader loader, String mainClassFqcn) throws ModLoadException {
        Mod instance;
        try {
            Class<?> mainClass = loader.loadClass(mainClassFqcn);
            Object obj = mainClass.getDeclaredConstructor().newInstance();
            if (!(obj instanceof Mod)) {
                throw new ModLoadException(
                        mainClassFqcn + " does not implement " + Mod.class.getName(), null);
            }
            instance = (Mod) obj;
        } catch (ModLoadException e) {
            throw e;
        } catch (Exception e) {
            throw new ModLoadException(
                    "Failed to instantiate " + mainClassFqcn + " for mod " + handle.name(), e);
        }

        Activation activation = new Activation(instance, new Context(handle));
        try {
            instance.onEnable(activation.context);
        } catch (Throwable t) {
            // The caller drains the handle's registrations and journals this as "onEnable".
            throw new ModLoadException("onEnable failed for mod " + handle.name(), t, "onEnable");
        }
        return activation;
    }

    @Override
    public void deactivate(ModHandle handle, Object activation) throws Exception {
        Activation live = (Activation) activation;
        live.instance.onDisable(live.context);
    }

    /** What {@link #activate} hands back: the mod instance plus the context it was given. */
    private record Activation(Mod instance, VibeContext context) {
    }

    /**
     * Per-mod {@link VibeContext}. Every registration goes through an SPI bridge
     * and is tracked on the mod's {@link ModHandle}, which the lifecycle drains
     * on disable — the v2 generalization of v1's HandlerList/task/command
     * teardown (§0#10).
     */
    private final class Context implements VibeContext {

        private final ModHandle handle;

        Context(ModHandle handle) {
            this.handle = handle;
        }

        @Override
        public Plugin plugin() {
            return plugin;
        }

        @Override
        public Server server() {
            return plugin.getServer();
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
            Path p = plugin.getDataFolder().toPath().resolve("moddata").resolve(handle.name());
            try {
                Files.createDirectories(p);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return p;
        }

        @Override
        public void listen(Listener listener) {
            assertMainThread();
            handle.track(ModHandle.Kind.LISTENER, events.listen(listener, handle.name()));
        }

        @Override
        public BukkitTask repeat(long delayTicks, long periodTicks, Runnable task) {
            assertMainThread();
            BukkitTaskHandle t = scheduler.repeat(delayTicks, periodTicks, wrapTask(task));
            handle.track(ModHandle.Kind.TASK, t);
            return t.task();
        }

        @Override
        public BukkitTask later(long delayTicks, Runnable task) {
            assertMainThread();
            BukkitTaskHandle t = scheduler.later(delayTicks, wrapTask(task));
            handle.track(ModHandle.Kind.TASK, t);
            return t.task();
        }

        private Runnable wrapTask(Runnable task) {
            return () -> dispatch.run(handle.name(), null, "task", task::run);
        }

        @Override
        public void command(String name, String description, ModCommandHandler handler) {
            assertMainThread();
            Registration registration = commands.register(name, description, handle.name(),
                    (sender, args) -> handler.run(PaperSender.unwrap(sender), args));
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
                    (sender, args) -> handler.run(PaperSender.unwrap(sender), args));
        }

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

        private void assertMainThread() {
            if (!scheduler.onMain()) {
                throw new IllegalStateException(
                        "VibeContext must only be used from the main server thread");
            }
        }
    }
}
