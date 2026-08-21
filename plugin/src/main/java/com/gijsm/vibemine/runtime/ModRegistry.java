package com.gijsm.vibemine.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import com.gijsm.vibemine.api.ModCommandHandler;
import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;

/**
 * Owns the lifecycle of every hot-loaded mod: compiling bytecode in, wiring a
 * per-mod {@link VibeContext} that tracks every registration, and tearing
 * mods down cleanly on disable, unload, replace, or watchdog trip.
 *
 * All public methods must be called from the main server thread.
 */
public final class ModRegistry {

    private final Plugin plugin;
    private final DynamicCommands dynamicCommands;
    private final Watchdog watchdog;
    private final LinkedHashMap<String, LoadedMod> mods = new LinkedHashMap<>();

    public ModRegistry(Plugin plugin, DynamicCommands commands, Watchdog watchdog) {
        this.plugin = plugin;
        this.dynamicCommands = commands;
        this.watchdog = watchdog;
        this.watchdog.onTrip(modName -> {
            LoadedMod lm = mods.get(lower(modName));
            String displayName = lm != null ? lm.displayName : modName;
            boolean wasEnabled = lm != null && lm.handle.enabled;
            disableInternal(lm);
            if (wasEnabled) {
                plugin.getServer().broadcast(Component.text(
                        displayName + " was auto-disabled by the watchdog (too slow)", NamedTextColor.RED));
            }
        });
    }

    /** Compile output -> live mod. Replaces (tears down) an existing mod of the same name. Main thread. */
    public ModHandle load(String name, int version, String description, String mainClassFqcn,
                           Map<String, byte[]> classes) throws ModLoadException {
        assertMainThread();
        String key = lower(name);
        LoadedMod existing = mods.remove(key);
        if (existing != null) {
            disableInternal(existing);
        }
        ModHandle handle = new ModHandle(name, version, description);
        LoadedMod lm = new LoadedMod(name, version, description, mainClassFqcn, Map.copyOf(classes), handle);
        mods.put(key, lm);
        try {
            activate(lm);
        } catch (ModLoadException e) {
            mods.remove(key);
            throw e;
        }
        return handle;
    }

    /** Teardown registrations + close loader, keep bytes; false if absent/disabled. */
    public boolean disable(String name) {
        assertMainThread();
        LoadedMod lm = mods.get(lower(name));
        if (lm == null) {
            return false;
        }
        return disableInternal(lm);
    }

    /** Fresh loader from kept bytes. */
    public boolean enable(String name) throws ModLoadException {
        assertMainThread();
        LoadedMod lm = mods.get(lower(name));
        if (lm == null || lm.handle.enabled) {
            return false;
        }
        activate(lm);
        return true;
    }

    /** Disable + forget entirely. */
    public void unload(String name) {
        assertMainThread();
        LoadedMod lm = mods.remove(lower(name));
        if (lm != null) {
            disableInternal(lm);
        }
    }

    /** Disable ALL mods. */
    public void panic() {
        assertMainThread();
        for (LoadedMod lm : List.copyOf(mods.values())) {
            if (lm.handle.enabled) {
                if (disableInternal(lm)) {
                    plugin.getLogger().info("Panic: disabled mod " + lm.displayName);
                }
            }
        }
    }

    /** Null if unknown. */
    public ModHandle get(String name) {
        LoadedMod lm = mods.get(lower(name));
        return lm == null ? null : lm.handle;
    }

    /** Stable order by name. */
    public Collection<ModHandle> mods() {
        return mods.values().stream()
                .map(lm -> lm.handle)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ModHandle::name, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toUnmodifiableList());
    }

    public boolean runAction(String mod, String action, CommandSender sender, String[] args) {
        assertMainThread();
        LoadedMod lm = mods.get(lower(mod));
        if (lm == null || !lm.handle.enabled) {
            return false;
        }
        ModCommandHandler handler = lm.handle.actions.get(lower(action));
        if (handler == null) {
            return false;
        }
        runWrapped(lm, sender, handler, args);
        return true;
    }

    // ---------------------------------------------------------------- internals

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private void assertMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("ModRegistry must only be used from the main server thread");
        }
    }

    /** Every non-bridge, non-synthetic {@code @EventHandler} method on a class, walking up superclasses. */
    private static List<Method> eventHandlerMethods(Class<?> cls) {
        List<Method> result = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                if (m.isAnnotationPresent(EventHandler.class)) {
                    result.add(m);
                }
            }
        }
        return result;
    }

    /** Instantiate the mod, build its context, and call onEnable. Rolls back on any failure. */
    private void activate(LoadedMod lm) throws ModLoadException {
        BytesClassLoader loader = new BytesClassLoader(ModRegistry.class.getClassLoader(), lm.classes);
        VibeMod instance;
        try {
            Class<?> mainClass = loader.loadClass(lm.mainClassFqcn);
            Object obj = mainClass.getDeclaredConstructor().newInstance();
            if (!(obj instanceof VibeMod)) {
                throw new ModLoadException(
                        lm.mainClassFqcn + " does not implement " + VibeMod.class.getName(), null);
            }
            instance = (VibeMod) obj;
        } catch (ModLoadException e) {
            throw e;
        } catch (Exception e) {
            throw new ModLoadException("Failed to instantiate " + lm.mainClassFqcn + " for mod " + lm.displayName, e);
        }

        lm.loader = loader;
        lm.instance = instance;
        VibeContextImpl ctx = new VibeContextImpl(lm);
        lm.context = ctx;
        try {
            instance.onEnable(ctx);
        } catch (Throwable t) {
            teardownRegistrations(lm);
            lm.instance = null;
            lm.loader = null;
            lm.context = null;
            lm.handle.enabled = false;
            throw new ModLoadException("onEnable failed for mod " + lm.displayName, t);
        }
        lm.handle.enabled = true;
    }

    /** Full teardown: onDisable, then unregister everything. Never throws. */
    private boolean disableInternal(LoadedMod lm) {
        if (lm == null || !lm.handle.enabled) {
            return false;
        }
        if (lm.instance != null && lm.context != null) {
            try {
                lm.instance.onDisable(lm.context);
            } catch (Throwable t) {
                Logger.getLogger("VibeCore." + lm.displayName)
                        .log(Level.WARNING, "onDisable failed for mod " + lm.displayName, t);
            }
        }
        teardownRegistrations(lm);
        lm.instance = null;
        lm.loader = null;
        lm.context = null;
        lm.handle.enabled = false;
        return true;
    }

    /** Undo every tracked registration. Never throws. */
    private void teardownRegistrations(LoadedMod lm) {
        for (Listener l : lm.handle.listeners) {
            try {
                HandlerList.unregisterAll(l);
            } catch (Throwable ignored) {
                // best-effort
            }
        }
        lm.handle.listeners.clear();
        for (BukkitTask t : lm.handle.tasks) {
            try {
                if (!t.isCancelled()) {
                    t.cancel();
                }
            } catch (Throwable ignored) {
                // best-effort
            }
        }
        lm.handle.tasks.clear();
        for (String cmdName : lm.handle.commandNames) {
            try {
                dynamicCommands.unregister(cmdName);
            } catch (Throwable ignored) {
                // best-effort, DynamicCommands never throws anyway
            }
        }
        lm.handle.commandNames.clear();
        lm.handle.actions.clear();
        watchdog.reset(lm.displayName);
    }

    /** Run a mod handler through the watchdog, catching any failure and reporting it. */
    private void runWrapped(LoadedMod lm, CommandSender sender, ModCommandHandler handler, String[] args) {
        try {
            watchdog.time(lm.displayName, () -> {
                try {
                    handler.run(sender, args);
                } catch (Exception e) {
                    throw new HandlerFailure(e);
                }
            });
        } catch (HandlerFailure f) {
            reportFailure(lm, sender, f.getCause());
        } catch (Throwable t) {
            reportFailure(lm, sender, t);
        }
    }

    private void reportFailure(LoadedMod lm, CommandSender sender, Throwable cause) {
        Logger.getLogger("VibeCore." + lm.displayName)
                .log(Level.WARNING, "Handler in mod " + lm.displayName + " threw", cause);
        if (sender != null) {
            String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            sender.sendMessage(Component.text("Error in mod command: " + msg, NamedTextColor.RED));
        }
    }

    /** Internal unchecked carrier so a checked ModCommandHandler exception can cross a Runnable boundary. */
    private static final class HandlerFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        HandlerFailure(Throwable cause) {
            super(cause);
        }
    }

    /** Everything the registry tracks for one mod, enabled or not. */
    private static final class LoadedMod {
        final String displayName;
        final int version;
        final String description;
        final String mainClassFqcn;
        final Map<String, byte[]> classes;
        final ModHandle handle;

        volatile VibeMod instance;
        volatile ClassLoader loader;
        volatile VibeContextImpl context;

        LoadedMod(String displayName, int version, String description, String mainClassFqcn,
                  Map<String, byte[]> classes, ModHandle handle) {
            this.displayName = displayName;
            this.version = version;
            this.description = description;
            this.mainClassFqcn = mainClassFqcn;
            this.classes = classes;
            this.handle = handle;
        }
    }

    /** Per-mod {@link VibeContext}: every registration goes through here and is tracked in the mod's handle. */
    private final class VibeContextImpl implements VibeContext {

        private final LoadedMod lm;

        VibeContextImpl(LoadedMod lm) {
            this.lm = lm;
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
            return lm.displayName;
        }

        @Override
        public Logger log() {
            return Logger.getLogger("VibeCore." + lm.displayName);
        }

        @Override
        public Path dataFolder() {
            Path p = plugin.getDataFolder().toPath().resolve("moddata").resolve(lm.displayName);
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
            if (!lm.handle.listeners.add(listener)) {
                return;
            }
            for (Method method : eventHandlerMethods(listener.getClass())) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || !Event.class.isAssignableFrom(params[0])) {
                    continue;
                }
                Class<? extends Event> eventClass = params[0].asSubclass(Event.class);
                EventHandler ann = method.getAnnotation(EventHandler.class);
                method.setAccessible(true);
                EventExecutor executor = (l, event) -> {
                    if (!eventClass.isInstance(event)) {
                        return;
                    }
                    try {
                        watchdog.time(lm.displayName, () -> {
                            try {
                                method.invoke(listener, event);
                            } catch (InvocationTargetException ite) {
                                throw new HandlerFailure(ite.getCause() != null ? ite.getCause() : ite);
                            } catch (IllegalAccessException iae) {
                                throw new HandlerFailure(iae);
                            }
                        });
                    } catch (HandlerFailure f) {
                        Logger.getLogger("VibeCore." + lm.displayName).log(Level.WARNING,
                                "Listener " + listener.getClass().getName() + "#" + method.getName()
                                        + " in mod " + lm.displayName + " threw", f.getCause());
                    } catch (Throwable t) {
                        Logger.getLogger("VibeCore." + lm.displayName).log(Level.WARNING,
                                "Listener " + listener.getClass().getName() + "#" + method.getName()
                                        + " in mod " + lm.displayName + " threw", t);
                    }
                };
                plugin.getServer().getPluginManager()
                        .registerEvent(eventClass, listener, ann.priority(), executor, plugin, ann.ignoreCancelled());
            }
        }

        @Override
        public BukkitTask repeat(long delayTicks, long periodTicks, Runnable task) {
            assertMainThread();
            BukkitTask t = plugin.getServer().getScheduler()
                    .runTaskTimer(plugin, wrapTask(task), delayTicks, periodTicks);
            lm.handle.tasks.add(t);
            return t;
        }

        @Override
        public BukkitTask later(long delayTicks, Runnable task) {
            assertMainThread();
            BukkitTask t = plugin.getServer().getScheduler().runTaskLater(plugin, wrapTask(task), delayTicks);
            lm.handle.tasks.add(t);
            return t;
        }

        private Runnable wrapTask(Runnable task) {
            return () -> {
                try {
                    watchdog.time(lm.displayName, task);
                } catch (Throwable t) {
                    Logger.getLogger("VibeCore." + lm.displayName)
                            .log(Level.WARNING, "Task in mod " + lm.displayName + " threw", t);
                }
            };
        }

        @Override
        public void command(String name, String description, ModCommandHandler handler) {
            assertMainThread();
            DynamicCommands.CommandExecutorLike wrapped =
                    (sender, label, args) -> runWrapped(lm, sender, handler, args);
            boolean ok = dynamicCommands.register(name, description, null, wrapped);
            if (ok) {
                lm.handle.commandNames.add(name);
            } else {
                log().info("Top-level command /" + name + " unavailable, falling back to action");
                action(name, handler);
            }
        }

        @Override
        public void action(String name, ModCommandHandler handler) {
            assertMainThread();
            lm.handle.actions.put(lower(name), handler);
        }
    }

    /** In-memory classloader for one mod's compiled bytecode. Nothing to close on disable, just drop it. */
    static final class BytesClassLoader extends ClassLoader {

        static {
            registerAsParallelCapable();
        }

        private final Map<String, byte[]> classes;

        BytesClassLoader(ClassLoader parent, Map<String, byte[]> classes) {
            super(parent);
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** Thrown when a mod fails to load or (re)enable. */
    public static final class ModLoadException extends Exception {
        private static final long serialVersionUID = 1L;

        public ModLoadException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
