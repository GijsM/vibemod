package com.gijsm.vibemod.fabric.shim;

import java.util.logging.Logger;

import net.fabricmc.fabric.api.event.Event;

import net.minecraft.resources.Identifier;

/**
 * The static methods a generated mod's rewritten bytecode actually calls
 * (V3 Phase 0 §B).
 *
 * <p>Nothing here is called from Java. Every entry point exists because
 * {@code BytecodeSurgeon} turned an {@code invokevirtual Event.register} in a
 * mod's compiled code into an {@code invokestatic} pointing at it, with the
 * receiver prepended to the arguments — so the signatures below are a contract
 * with the seam table in {@code FabricSeams}, and changing one without the
 * other produces a mod that fails to link rather than a compile error.
 *
 * <p>Resolvable from a mod's class loader because it is not in the mod: the
 * mod's {@code BytesClassLoader} parents to the host's loader (Knot), so a
 * class in the VibeMod jar is found parent-first exactly like {@code Event}
 * itself.
 *
 * <p>Deliberately thin. The policy lives in {@link EventFanout}; this class is
 * only the address the bytecode knows.
 */
public final class Shims {

    private static final Logger LOG = Logger.getLogger(Shims.class.getName());

    /**
     * Installed once per process at mod init, never per server.
     *
     * <p>Process-lived for the same reason every other host subscription is
     * (§10.3): the fanout owns permanent {@code Event.register} calls that
     * cannot be undone, so a client that loads a second world must find the
     * same fanout, not a second one dispatching into a dead bridge.
     */
    private static volatile EventSeam seam;

    private Shims() {
    }

    /** Installs the process-lived seam. Called from {@code VibeModFabric.onInitialize()}. */
    public static void install(EventSeam seam) {
        Shims.seam = seam;
    }

    /** The installed seam, or null before mod init. */
    public static EventSeam seam() {
        return seam;
    }

    /**
     * {@code Event.register(T)} — the erased {@code (Ljava/lang/Object;)V}
     * call every Fabric event registration compiles down to.
     */
    public static void eventRegister(Event<?> event, Object listener) {
        require().register(event, listener);
    }

    /**
     * {@code Event.register(Identifier, T)} — the phased overload.
     *
     * <p>The phase is dropped on purpose, and it is not a silent drop: phases
     * only mean anything relative to an {@code addPhaseOrdering} call, which
     * the surgeon's policy forbids outright (a mod that reorders an event's
     * phases changes behaviour for every other mod and cannot be undone when it
     * is disabled). With no orderings in play, every phase runs in registration
     * order, which is exactly what the fanout does.
     */
    public static void eventRegister(Event<?> event, Identifier phase, Object listener) {
        LOG.fine(() -> "Ignoring event phase " + phase + " (phase ordering is not available to mods)");
        require().register(event, listener);
    }

    private static EventSeam require() {
        EventSeam live = seam;
        if (live == null) {
            throw new IllegalStateException(
                    "VibeMod's event seam is not installed — a rewritten mod class was loaded "
                            + "before the host initialised");
        }
        return live;
    }
}
