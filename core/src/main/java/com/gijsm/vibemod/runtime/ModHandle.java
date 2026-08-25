package com.gijsm.vibemod.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.gijsm.vibemod.platform.CommandBridge;
import com.gijsm.vibemod.platform.Registration;

/**
 * Live handle on one loaded mod. The read surface below is for UI callers;
 * the mutators and tracking collections are package-private and used only by
 * {@link ModLifecycle} to build up and tear down a mod's registrations.
 *
 * <p>v2 change (ARCHITECTURE-V2 §1.1, §0#10): the handle no longer holds
 * platform types. Listeners, tasks and commands are all just
 * {@link Registration}s now — one revocation model on every platform — each
 * tagged with a {@code kind} so the UI can still say "3 listeners, 2 tasks".
 */
public final class ModHandle {

    /**
     * What a tracked registration was for; drives the UI's introspected counts.
     *
     * <p>{@code NATIVE} is V3 Phase 0: a subscription a mod made to a
     * <em>loader</em> event through its own API, which the host intercepted at
     * a bytecode seam and turned into an entry in a host-owned fanout. It is a
     * separate kind from {@code LISTENER} because the two are revoked at
     * different layers — {@code LISTENER} unhooks from the curated
     * {@code ctx.on*} registry, {@code NATIVE} unhooks from the fanout standing
     * behind a real, permanently-registered loader event — and because "3
     * listeners" meaning two different things in the same UI line would be a
     * lie.
     */
    public enum Kind {
        LISTENER, TASK, COMMAND, CLIENT, NATIVE
    }

    private final String name;
    private final int version;
    private final String description;
    volatile boolean enabled;
    volatile boolean degraded;
    volatile int errorCount;

    final List<Tracked> registrations = new ArrayList<>();
    final List<String> commandNames = new ArrayList<>();
    final Map<String, CommandBridge.CommandHandler> actions = new LinkedHashMap<>();

    ModHandle(String name, int version, String description) {
        this.name = name;
        this.version = version;
        this.description = description;
    }

    public String name() {
        return name;
    }

    public int version() {
        return version;
    }

    public String description() {
        return description;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Whether this mod has an uncleared caught error (degrade episode still open). */
    public boolean degraded() {
        return degraded;
    }

    /** Total caught errors since load/enable, across all degrade episodes. */
    public int errorCount() {
        return errorCount;
    }

    public int listenerCount() {
        return countOf(Kind.LISTENER);
    }

    public int taskCount() {
        return countOf(Kind.TASK);
    }

    /** Loader-event subscriptions the bytecode seam routed into the host's fanout (V3 Phase 0). */
    public int nativeCount() {
        return countOf(Kind.NATIVE);
    }

    /** Registrations of every kind currently held on the mod's behalf. */
    public int registrationCount() {
        synchronized (registrations) {
            return registrations.size();
        }
    }

    public List<String> commandNames() {
        synchronized (commandNames) {
            return List.copyOf(commandNames);
        }
    }

    public List<String> actionNames() {
        synchronized (actions) {
            return List.copyOf(actions.keySet());
        }
    }

    // ---- lifecycle-internal tracking ----

    /**
     * Tracks one registration made for this mod. Called by the host's
     * {@code VibeContext} implementation and by the bridges; drained by
     * {@link ModLifecycle} on disable/unload.
     */
    public void track(Kind kind, Registration registration) {
        synchronized (registrations) {
            registrations.add(new Tracked(kind, registration));
        }
    }

    /**
     * Records that this mod owns the top-level command {@code /name}. Separate
     * from {@link #track} because the name is what the UI reports ("commands:
     * boom") while the {@link Registration} is what revokes it.
     */
    public void trackCommandName(String name) {
        synchronized (commandNames) {
            if (!commandNames.contains(name)) {
                commandNames.add(name);
            }
        }
    }

    /**
     * Registers a {@code /vibe do <mod> <name>} action. Keyed lowercased by the
     * caller; the last registration of a name wins, as in v1.
     */
    public void trackAction(String lowercasedName, CommandBridge.CommandHandler handler) {
        synchronized (actions) {
            actions.put(lowercasedName, handler);
        }
    }

    /** The handler for an action, or null. Main thread. */
    CommandBridge.CommandHandler action(String lowercasedName) {
        synchronized (actions) {
            return actions.get(lowercasedName);
        }
    }

    /** Closes every tracked registration and forgets it. Never throws. */
    void drain() {
        List<Tracked> snapshot;
        synchronized (registrations) {
            snapshot = List.copyOf(registrations);
            registrations.clear();
        }
        for (Tracked t : snapshot) {
            try {
                t.registration.close();
            } catch (Throwable ignored) {
                // best-effort: close() is contractually silent, but a rogue host impl must not stop teardown
            }
        }
        synchronized (commandNames) {
            commandNames.clear();
        }
        synchronized (actions) {
            actions.clear();
        }
    }

    private int countOf(Kind kind) {
        int n = 0;
        synchronized (registrations) {
            for (Tracked t : registrations) {
                if (t.kind == kind) {
                    n++;
                }
            }
        }
        return n;
    }

    private record Tracked(Kind kind, Registration registration) {
    }
}
