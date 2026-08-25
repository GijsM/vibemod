package com.gijsm.vibemod.fabric.shim;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Logger;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.util.TriState;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionResult;

import com.gijsm.vibemod.loader.ModAttribution;
import com.gijsm.vibemod.platform.Registration;
import com.gijsm.vibemod.runtime.ModDispatch;
import com.gijsm.vibemod.runtime.ModHandle;

/**
 * One permanent subscription per loader event, with a revocable per-mod list
 * behind it (V3 Phase 0 §B).
 *
 * <p>This is the class that makes "a generated mod is just a Fabric mod"
 * survive {@code /vibe disable}. A Fabric {@code Event} cannot be unsubscribed
 * — that is by design, and it is the premise {@code LoaderEventBridge} was
 * already built on for the curated hooks. So when a mod's rewritten bytecode
 * asks to register, the fanout registers a {@link Proxy} of its own <em>once,
 * forever</em>, and puts the mod's listener in a list it can empty. Disabling
 * the mod removes the entry; the proxy stays subscribed and dispatches to
 * nobody. Counts go to zero, not away, which is exactly what a gate wants to
 * be able to assert.
 *
 * <p><b>Dispatch policy</b> is deliberately the same as
 * {@code LoaderEventBridge.every()}: every handler runs even after one has
 * voted to cancel, and a handler that throws casts no vote. Short-circuiting
 * would make a mod's behaviour depend on the order mods happened to load, which
 * is exactly the kind of irreproducible bug the error journal cannot explain.
 *
 * <p><b>Attribution</b> travels on the thread ({@link ModAttribution}): a
 * native call site carries no mod identity, so the host supplies it around the
 * entrypoint and around every dispatch. That second half is what makes a mod
 * registering another listener from inside its own tick handler attribute
 * correctly.
 *
 * <p><b>What is refused, loudly.</b> Phase 0 covers server events only, and the
 * house rule is that a registration VibeMod cannot honour must throw rather
 * than land in a list that never fires. Client callbacks and
 * registration-style events (commands) therefore throw
 * {@link UnsupportedOperationException} naming the phase that will support
 * them, and so does a callback whose return type the merge policy has no
 * honest answer for.
 */
public final class EventFanout implements EventSeam {

    private static final Logger LOG = Logger.getLogger("VibeMod.EventFanout");

    /**
     * Callbacks that describe a <em>registration pass</em> rather than a game
     * event: the loader fires them once, at a moment of its choosing, and
     * whatever you register during them lives outside VibeMod's teardown model.
     * Hot-loading into one is Phase 1's problem, and until then registering one
     * is an error rather than a subscription that silently never fires.
     */
    private static final Set<String> REGISTRATION_STYLE = Set.of(
            "net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback",
            "net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback",
            "net.fabricmc.fabric.api.resource.v1.ResourceLoader");

    /**
     * Lifecycle callbacks a mod loaded mid-session has already missed.
     *
     * <p>Without this, every generated mod that does its setup in
     * {@code SERVER_STARTING} would be dead on arrival: VibeMod hot-loads mods
     * long after the server started, so the event they are waiting for has
     * already fired and will not fire again until the next world.
     */
    private static final Set<String> REPLAY_WHEN_LATE = Set.of(
            "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents$ServerStarting",
            "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents$ServerStarted");

    private final Supplier<ModDispatch> dispatch;
    private final Supplier<MinecraftServer> server;

    /**
     * Keyed by {@code Event} identity — {@code Event} does not override
     * {@code equals}, which is what we want: two distinct events are two
     * distinct fanouts even if a mod made them itself with
     * {@code EventFactory}.
     */
    private final Map<Event<?>, Fan> fans = new ConcurrentHashMap<>();

    public EventFanout(Supplier<ModDispatch> dispatch, Supplier<MinecraftServer> server) {
        this.dispatch = dispatch;
        this.server = server;
    }

    // ------------------------------------------------------------------ register

    @Override
    public void register(Event<?> event, Object listener) {
        if (event == null || listener == null) {
            throw new IllegalArgumentException("Event.register(null) is not a registration");
        }
        ModHandle handle = ModAttribution.current();
        if (handle == null) {
            // Never a drop: a subscription VibeMod cannot attribute is one it
            // could never revoke, which is precisely the failure this whole
            // design exists to prevent.
            throw new IllegalStateException(
                    "Event.register reached VibeMod outside any mod's own code, so this "
                            + "subscription could never be revoked. Register from onInitialize "
                            + "or from inside one of your own callbacks.");
        }

        Class<?> callback = callbackTypeOf(listener);
        refuseIfUnsupported(callback);

        Fan fan = fans.computeIfAbsent(event, e -> new Fan(e, callback));
        if (!fan.callbackType.equals(callback)) {
            throw new IllegalArgumentException("Event " + fan.label + " was first registered with a "
                    + fan.callbackType.getName() + " listener; this one is a " + callback.getName());
        }

        Bound bound = new Bound(handle.name(), handle, listener);
        fan.listeners.add(bound);
        handle.track(ModHandle.Kind.NATIVE, Registration.of(() -> fan.listeners.remove(bound)));
        LOG.fine(() -> "Mod " + bound.modName + " subscribed to " + fan.label);

        replayIfLate(fan, bound);
    }

    /** Counts per event, for the acceptance gates: {@code "ServerTickEvents.EndTick=1 … total=1"}. */
    public String describeState() {
        List<String> parts = new ArrayList<>();
        int total = 0;
        for (Fan fan : fans.values()) {
            int n = fan.listeners.size();
            total += n;
            parts.add(fan.label + "=" + n);
        }
        parts.sort(String::compareTo);
        parts.add("total=" + total);
        return String.join(" ", parts);
    }

    // ------------------------------------------------------------------ policy

    /**
     * The interface a listener is offering.
     *
     * <p>A lambda implements exactly one interface, which covers essentially
     * every real registration; the hierarchy walk is for the mod that wrote an
     * explicit class. Ambiguity is an error rather than a guess: picking the
     * wrong interface would build a proxy the event cannot accept, and the
     * failure would surface as a {@code ClassCastException} inside Fabric
     * rather than as anything a repair round could act on.
     */
    private static Class<?> callbackTypeOf(Object listener) {
        Set<Class<?>> candidates = new LinkedHashSet<>();
        for (Class<?> c = listener.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            collectSams(c.getInterfaces(), candidates);
        }
        if (candidates.size() == 1) {
            return candidates.iterator().next();
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(listener.getClass().getName()
                    + " implements no single-method interface, so VibeMod cannot tell what "
                    + "callback it is meant to be");
        }
        List<String> names = new ArrayList<>();
        candidates.forEach(c -> names.add(c.getName()));
        throw new IllegalArgumentException(listener.getClass().getName()
                + " implements several single-method interfaces (" + String.join(", ", names)
                + "); use a lambda or an anonymous class so the callback type is unambiguous");
    }

    private static void collectSams(Class<?>[] interfaces, Set<Class<?>> into) {
        for (Class<?> candidate : interfaces) {
            if (samOf(candidate) != null) {
                into.add(candidate);
            }
            collectSams(candidate.getInterfaces(), into);
        }
    }

    /** The single abstract method of a functional interface, or null. */
    private static Method samOf(Class<?> type) {
        if (!type.isInterface()) {
            return null;
        }
        Method sam = null;
        for (Method method : type.getMethods()) {
            if (!Modifier.isAbstract(method.getModifiers()) || method.isDefault()) {
                continue;
            }
            if (isObjectMethod(method)) {
                continue;
            }
            if (sam != null) {
                return null;
            }
            sam = method;
        }
        return sam;
    }

    private static boolean isObjectMethod(Method method) {
        try {
            Object.class.getMethod(method.getName(), method.getParameterTypes());
            return true;
        } catch (NoSuchMethodException notObject) {
            return false;
        }
    }

    /** The three things Phase 0 will not pretend to support. */
    private static void refuseIfUnsupported(Class<?> callback) {
        String name = callback.getName();
        if (name.startsWith("net.fabricmc.fabric.api.client.")) {
            throw new UnsupportedOperationException("Client-side events (" + name
                    + ") are not available to generated mods yet — they arrive with the client "
                    + "entrypoint in Phase 1. Use a server event for now.");
        }
        if (REGISTRATION_STYLE.contains(name)) {
            throw new UnsupportedOperationException(name
                    + " is a registration pass, not a game event: whatever you register inside it "
                    + "would outlive your mod. Commands and registries arrive in Phase 1.");
        }
        Method sam = samOf(callback);
        if (sam != null && !Merge.supports(sam.getReturnType())) {
            throw new UnsupportedOperationException("Events returning " + sam.getReturnType().getName()
                    + " (" + name + ") are not supported yet: VibeMod has no honest way to merge "
                    + "several mods' answers into one. Use a void or boolean event.");
        }
    }

    /** Fires a lifecycle callback the mod missed because it was hot-loaded after the fact. */
    private void replayIfLate(Fan fan, Bound bound) {
        if (!REPLAY_WHEN_LATE.contains(fan.callbackType.getName())) {
            return;
        }
        MinecraftServer live = server.get();
        ModDispatch guarded = dispatch.get();
        if (live == null || guarded == null) {
            return;
        }
        Method sam = samOf(fan.callbackType);
        if (sam == null || sam.getParameterCount() != 1
                || !sam.getParameterTypes()[0].isAssignableFrom(MinecraftServer.class)) {
            return;
        }
        LOG.fine(() -> "Replaying " + fan.label + " for " + bound.modName + " (server already running)");
        ModAttribution.runAs(bound.handle, () -> guarded.run(bound.modName, null,
                "event:" + fan.label, () -> invoke(sam, bound.listener, new Object[] {live})));
    }

    // ------------------------------------------------------------------ dispatch

    /** One event's permanent proxy and the mods currently behind it. */
    private final class Fan {

        private final Class<?> callbackType;
        private final String label;
        private final List<Bound> listeners = new CopyOnWriteArrayList<>();
        /** One log line per event, not one per tick, when a callback arrives off-thread. */
        private final AtomicBoolean warnedOffThread = new AtomicBoolean();

        Fan(Event<?> event, Class<?> callbackType) {
            this.callbackType = callbackType;
            this.label = labelOf(callbackType);
            Object proxy = Proxy.newProxyInstance(callbackType.getClassLoader(),
                    new Class<?>[] {callbackType}, new Handler(this));
            // The one and only subscription for this event, for the life of the
            // process. NOT rewritten by the surgeon: the surgeon runs over
            // generated mod bytecode, never over the host's own.
            registerRaw(event, proxy);
            LOG.info("Fanning out " + label + " (one permanent subscription)");
        }

        Object dispatch(Method method, Object[] args) {
            Class<?> returnType = method.getReturnType();
            if (listeners.isEmpty()) {
                return Merge.defaultFor(returnType);
            }
            MinecraftServer live = server.get();
            if (live != null && !live.isSameThread()) {
                if (warnedOffThread.compareAndSet(false, true)) {
                    LOG.warning(label + " fired off the main server thread; VibeMod is skipping mod "
                            + "dispatch for it (mod code must not run off-thread). "
                            + "This is logged once per event.");
                }
                return Merge.defaultFor(returnType);
            }
            ModDispatch guarded = dispatch.get();
            if (guarded == null) {
                // Between worlds: the host is torn down but the proxy is still
                // subscribed, exactly as designed.
                return Merge.defaultFor(returnType);
            }
            Merge merge = new Merge(returnType);
            String where = "event:" + label;
            for (Bound bound : listeners) {
                ModAttribution.runAs(bound.handle, () -> guarded.run(bound.modName, null, where,
                        () -> merge.accept(invoke(method, bound.listener, args))));
            }
            return merge.result();
        }
    }

    /** One mod's listener for one event. */
    private record Bound(String modName, ModHandle handle, Object listener) {
    }

    /** The proxy's back end; Object methods answered here so they never reach mod code. */
    private record Handler(Fan fan) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "VibeMod fanout for " + fan.label;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            return fan.dispatch(method, args);
        }
    }

    /**
     * Merges several mods' answers into the one the game expects.
     *
     * <p>Only the shapes with an unambiguous answer are supported, and
     * {@link #supports} is checked at <em>registration</em> time rather than at
     * dispatch time so a mod finds out it cannot use an event while it is
     * loading, not silently three ticks later.
     */
    private static final class Merge {

        private final Class<?> returnType;
        private boolean allow = true;
        private Object first;

        Merge(Class<?> returnType) {
            this.returnType = returnType;
        }

        static boolean supports(Class<?> returnType) {
            return !returnType.isPrimitive() || returnType == void.class || returnType == boolean.class;
        }

        void accept(Object value) {
            if (returnType == boolean.class || returnType == Boolean.class) {
                // AND of votes, no short-circuit: a thrower never reaches here
                // and therefore casts no vote.
                allow &= !(value instanceof Boolean b) || b;
                return;
            }
            if (first != null || value == null) {
                return;
            }
            if (value instanceof InteractionResult.Pass || value == TriState.DEFAULT) {
                return;
            }
            first = value;
        }

        Object result() {
            if (returnType == void.class) {
                return null;
            }
            if (returnType == boolean.class || returnType == Boolean.class) {
                return allow;
            }
            return first != null ? first : defaultFor(returnType);
        }

        static Object defaultFor(Class<?> returnType) {
            if (returnType == void.class) {
                return null;
            }
            if (returnType == boolean.class || returnType == Boolean.class) {
                return Boolean.TRUE;
            }
            if (returnType == InteractionResult.class) {
                return InteractionResult.PASS;
            }
            if (returnType == TriState.class) {
                return TriState.DEFAULT;
            }
            if (returnType.isPrimitive()) {
                // Unreachable: refuseIfUnsupported rejects these at registration.
                return 0;
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * {@code Event<T>.register(T)} against an {@code Object}. The unchecked cast
     * is safe because the proxy implements exactly the interface the event's own
     * listeners implement — that is what {@link #callbackTypeOf} established.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerRaw(Event event, Object proxy) {
        event.register(proxy);
    }

    /**
     * Calls one mod's listener, unwrapping the reflection layer so
     * {@code ModDispatch} journals the mod's real exception rather than an
     * {@code InvocationTargetException} wrapper.
     */
    private static Object invoke(Method method, Object listener, Object[] args) throws Exception {
        try {
            return method.invoke(listener, args);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception e) {
                throw e;
            }
            if (cause instanceof Error e) {
                throw e;
            }
            throw wrapped;
        }
    }

    /** {@code …ServerTickEvents$EndTick} -&gt; {@code ServerTickEvents.EndTick}. */
    private static String labelOf(Class<?> callbackType) {
        String name = callbackType.getName();
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(dot + 1)).replace('$', '.');
    }
}
