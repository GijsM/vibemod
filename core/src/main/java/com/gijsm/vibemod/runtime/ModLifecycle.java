package com.gijsm.vibemod.runtime;

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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import com.gijsm.vibemod.gen.GeneratedProject;
import com.gijsm.vibemod.platform.CommandBridge;
import com.gijsm.vibemod.platform.Messenger;
import com.gijsm.vibemod.platform.ModFailure;
import com.gijsm.vibemod.platform.Registration;
import com.gijsm.vibemod.platform.Sender;
import com.gijsm.vibemod.platform.TickScheduler;
import com.gijsm.vibemod.store.ModConfigs;
import com.gijsm.vibemod.ui.Style;

/**
 * Owns the lifecycle of every hot-loaded mod: bytecode in, a tracked set of
 * {@link com.gijsm.vibemod.platform.Registration}s out, and a clean teardown on
 * disable, unload, replace, watchdog trip or error storm.
 *
 * <p>This is v1's {@code ModRegistry} with the Bukkit half lifted out
 * (ARCHITECTURE-V2 §1.1). What stayed: the mod map, enabled/degraded states,
 * version activation, registration draining, the error-storm and watchdog-trip
 * policy, and the degrade announcement. What left: instantiating the mod and
 * building its context (now {@link ModHost}), registering listeners (now
 * {@code EventBridge}) and commands (now {@code CommandBridge}).
 *
 * <p>All public methods must be called from the main server thread.
 */
public final class ModLifecycle implements ModFailure {

    private final ModHost host;
    private final TickScheduler scheduler;
    private final Messenger messenger;
    private final Watchdog watchdog;
    private final ModConfigs configs;
    private final ModErrors errors;
    private final DebugEcho debug;
    private final ModDispatch dispatch;
    private final LinkedHashMap<String, LoadedMod> mods = new LinkedHashMap<>();
    /** V3 Phase 2 §B: the host's resource channel, or {@link ModContent#NONE}. */
    private volatile ModContent content = ModContent.NONE;
    /**
     * V3 Phase 3 §A: notified when a mod is unloaded — deleted from the store,
     * not merely disabled.
     *
     * <p>A separate hook rather than a branch inside {@link #unload} because the
     * one thing that cares is a loader-side registry ledger, and {@code core}
     * must not know what a registry is. The distinction it needs is exactly the
     * one {@link #unload} already draws: {@link #disable} is reversible and
     * {@code unload} is not, so only the latter is a tombstone.
     */
    private final List<java.util.function.Consumer<String>> unloadListeners = new ArrayList<>();

    public ModLifecycle(ModHost host, TickScheduler scheduler, Messenger messenger, Watchdog watchdog,
                        ModConfigs configs, ModErrors errors, DebugEcho debug) {
        this.host = host;
        this.scheduler = scheduler;
        this.messenger = messenger;
        this.watchdog = watchdog;
        this.configs = configs;
        this.errors = errors;
        this.debug = debug;
        this.dispatch = new ModDispatch(watchdog, this);
        this.watchdog.onTrip(modName -> autoDisable(modName, "was auto-disabled by the watchdog (too slow)"));
        // Default storm handler: mirrors the watchdog trip above. The host supersedes this with its
        // own onStorm registration (also flipping store.setEnabled(false)) once it owns errors.
        this.errors.onStorm(modName -> autoDisable(modName, "was auto-disabled after an error storm"));
    }

    /** The shared guarded-entry helper, for host bridges that dispatch into mod code. */
    public ModDispatch dispatch() {
        return dispatch;
    }

    /**
     * Installs the host's resource channel (V3 Phase 2 §B). Null clears it back
     * to {@link ModContent#NONE}; a host that has none simply never calls this.
     */
    public void setContent(ModContent content) {
        this.content = content == null ? ModContent.NONE : content;
    }

    /** Compile output -> live mod. Replaces (tears down) an existing mod of the same name. Main thread. */
    public ModHandle load(String name, int version, String description, String mainClassFqcn,
                          Map<String, byte[]> classes) throws ModLoadException {
        return load(name, version, description, mainClassFqcn, classes, List.of(), Map.of(), null);
    }

    /**
     * Compile output -> live mod, with the config schema and current values to register for it,
     * plus the mod's persisted debug-echo override to seed ({@code null} = no override, follow
     * the config default). Replaces (tears down) an existing mod of the same name. Main thread.
     */
    public ModHandle load(String name, int version, String description, String mainClassFqcn,
                          Map<String, byte[]> classes,
                          List<GeneratedProject.ConfigKnob> schema, Map<String, String> values,
                          Boolean debugEcho)
            throws ModLoadException {
        assertMainThread();
        String key = lower(name);
        LoadedMod existing = mods.remove(key);
        if (existing != null) {
            disableInternal(existing);
        }
        ModHandle handle = new ModHandle(name, version, description);
        LoadedMod lm = new LoadedMod(name, mainClassFqcn, Map.copyOf(classes), handle);
        mods.put(key, lm);
        configs.register(name, schema, values);
        try {
            activate(lm);
        } catch (ModLoadException e) {
            mods.remove(key);
            configs.forget(name);
            throw e;
        }
        debug.track(name);
        debug.seed(name, debugEcho);
        errors.clearEpisode(name);
        return handle;
    }

    /** Teardown registrations + drop the activation, keep bytes; false if absent/disabled. */
    public boolean disable(String name) {
        assertMainThread();
        LoadedMod lm = mods.get(lower(name));
        if (lm == null) {
            return false;
        }
        return disableInternal(lm);
    }

    /** Fresh activation from kept bytes. */
    public boolean enable(String name) throws ModLoadException {
        assertMainThread();
        LoadedMod lm = mods.get(lower(name));
        if (lm == null || lm.handle.enabled) {
            return false;
        }
        activate(lm);
        lm.handle.degraded = false;
        lm.handle.errorCount = 0;
        debug.track(name);
        errors.clearEpisode(name);
        return true;
    }

    /**
     * Registers a listener for {@link #unload} (V3 Phase 3 §A). Never called
     * for a plain {@link #disable}, which is the whole point of the hook.
     */
    public void onUnload(java.util.function.Consumer<String> listener) {
        unloadListeners.add(listener);
    }

    /** Disable + forget entirely. */
    public void unload(String name) {
        assertMainThread();
        LoadedMod lm = mods.remove(lower(name));
        if (lm != null) {
            disableInternal(lm);
        }
        configs.forget(name);
        errors.forget(name);
        debug.forget(name);
        for (java.util.function.Consumer<String> listener : unloadListeners) {
            try {
                listener.accept(name);
            } catch (Throwable t) {
                Logger.getLogger(ModLifecycle.class.getName())
                        .log(Level.WARNING, "An unload listener threw for " + name, t);
            }
        }
    }

    /** Disable ALL mods. */
    public void panic() {
        assertMainThread();
        for (LoadedMod lm : List.copyOf(mods.values())) {
            if (lm.handle.enabled && disableInternal(lm)) {
                Logger.getLogger(ModLifecycle.class.getName()).info("Panic: disabled mod " + lm.displayName);
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

    /** Runs a {@code /vibe do <mod> <action>} handler; false when the mod or action is unknown. */
    public boolean runAction(String mod, String action, Sender sender, String[] args) {
        assertMainThread();
        LoadedMod lm = mods.get(lower(mod));
        if (lm == null || !lm.handle.enabled) {
            return false;
        }
        CommandBridge.CommandHandler handler = lm.handle.action(lower(action));
        if (handler == null) {
            return false;
        }
        dispatch.run(lm.displayName, sender, "action:" + action, () -> handler.run(sender, args));
        return true;
    }

    // ---------------------------------------------------------------- ModFailure

    /** Marks a mod degraded, records the error, and announces the degrade episode once (per episode). */
    @Override
    public void markFailure(String modName, Throwable cause, String where) {
        if (modName == null || cause == null) {
            return;
        }
        LoadedMod lm = mods.get(lower(modName));
        if (lm != null) {
            lm.handle.degraded = true;
            lm.handle.errorCount++;
        }
        ModErrors.Outcome outcome = errors.record(modName, cause, where);
        if (outcome.firstOfEpisode()) {
            announceDegraded(modName, cause);
        }
    }

    // ---------------------------------------------------------------- internals

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private void assertMainThread() {
        if (!scheduler.onMain()) {
            throw new IllegalStateException("ModLifecycle must only be used from the main server thread");
        }
    }

    /** Hand the bytes to the host, which instantiates and enables. Rolls back on any failure. */
    private void activate(LoadedMod lm) throws ModLoadException {
        // The parent is the HOST's class loader, not this class's, and the
        // difference is load-bearing.
        //
        // The host is the thing that does `instanceof Mod` on what comes back,
        // so the `Mod` the generated class links against has to be the host's
        // `Mod`. Parenting to `ModLifecycle.class.getClassLoader()` only happens
        // to give the same answer when `core` and the sdk share a loader — which
        // they do in every shipped jar, and do NOT in a loader's dev run, where
        // `core` arrives as a plain classpath jar and the sdk arrives inside the
        // mod file. There the two diverge, and a generated mod either fails to
        // find `Mod` at all or finds a second copy and is rejected by an
        // `instanceof` that looks like it should have passed.
        BytesClassLoader loader = new BytesClassLoader(host.getClass().getClassLoader(), lm.classes);
        try {
            lm.activation = host.activate(lm.handle, loader, lm.mainClassFqcn);
        } catch (ModLoadException e) {
            lm.handle.drain();
            lm.activation = null;
            lm.handle.enabled = false;
            if (e.where() != null && e.getCause() != null) {
                errors.note(lm.displayName, e.getCause(), e.where());
            }
            throw e;
        }
        lm.handle.enabled = true;

        // V3 Phase 2 §B, and it happens AFTER the entrypoint on purpose: a mod's
        // own code has to be running before its recipes appear, not the other
        // way round, and the CONTENT registration then lands last in the
        // handle's list, which is the order ModHandle.drain() guarantees anyway.
        //
        // A content failure degrades the mod rather than failing the load. The
        // Java half is already live and working; refusing the whole mod because
        // one recipe file could not be written would be a worse answer than a
        // journalled error and a mod that runs.
        try {
            Registration installed = content.install(lm.handle);
            if (installed != null) {
                lm.handle.track(ModHandle.Kind.CONTENT, installed);
            }
        } catch (Throwable t) {
            Logger.getLogger("VibeMod." + lm.displayName)
                    .log(Level.WARNING, "Could not install resources for " + lm.displayName, t);
            errors.note(lm.displayName, t, "resources");
        }
    }

    /** Full teardown: onDisable, then revoke everything. Never throws. */
    private boolean disableInternal(LoadedMod lm) {
        if (lm == null || !lm.handle.enabled) {
            return false;
        }
        Object activation = lm.activation;
        if (activation != null) {
            try {
                host.deactivate(lm.handle, activation);
            } catch (Throwable t) {
                Logger.getLogger("VibeMod." + lm.displayName)
                        .log(Level.WARNING, "onDisable failed for mod " + lm.displayName, t);
                errors.note(lm.displayName, t, "onDisable");
            }
        }
        lm.handle.drain();
        lm.activation = null;
        lm.handle.enabled = false;
        watchdog.reset(lm.displayName);
        return true;
    }

    /** Shared body of the watchdog-trip and error-storm defaults: disable, then say so once. */
    private void autoDisable(String modName, String reason) {
        LoadedMod lm = mods.get(lower(modName));
        String displayName = lm != null ? lm.displayName : modName;
        boolean wasEnabled = lm != null && lm.handle.enabled;
        disableInternal(lm);
        if (wasEnabled) {
            messenger.broadcast(Component.text(displayName + " " + reason, NamedTextColor.RED));
        }
    }

    /** One clickable ops-chat announcement per degrade episode, with inline [fix]/[errors] buttons. */
    private void announceDegraded(String modName, Throwable cause) {
        String cls = rootCauseSimpleName(cause);
        Component message = Component.text(modName + " hit an error (" + cls + ") — ", NamedTextColor.GOLD)
                .append(Style.button("fix", "/vibe fix " + modName,
                        "Send this mod's errors to the model for a fix", NamedTextColor.GOLD))
                .append(Component.text(" "))
                .append(Style.button("errors", "/vibe errors " + modName,
                        "View this mod's error log", NamedTextColor.GRAY));
        messenger.broadcast(message, "vibe.admin");
    }

    private static String rootCauseSimpleName(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName();
    }

    /** Everything the lifecycle tracks for one mod, enabled or not. */
    private static final class LoadedMod {
        final String displayName;
        final String mainClassFqcn;
        final Map<String, byte[]> classes;
        final ModHandle handle;

        /** The host's opaque activation token while enabled, else null. */
        volatile Object activation;

        LoadedMod(String displayName, String mainClassFqcn, Map<String, byte[]> classes, ModHandle handle) {
            this.displayName = displayName;
            this.mainClassFqcn = mainClassFqcn;
            this.classes = classes;
            this.handle = handle;
        }
    }

    /** In-memory classloader for one mod's compiled bytecode. Nothing to close on disable, just drop it. */
    public static final class BytesClassLoader extends ClassLoader {

        static {
            registerAsParallelCapable();
        }

        private final Map<String, byte[]> classes;

        public BytesClassLoader(ClassLoader parent, Map<String, byte[]> classes) {
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
}
