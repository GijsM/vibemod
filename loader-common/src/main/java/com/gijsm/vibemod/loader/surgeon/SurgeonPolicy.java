package com.gijsm.vibemod.loader.surgeon;

import java.util.ArrayList;
import java.util.List;

/**
 * What a generated mod's bytecode is allowed to reach for (V3 Phase 0).
 *
 * <p>Two lists, checked in this order for every type and member the bytecode
 * actually references:
 *
 * <ol>
 *   <li>the {@linkplain #denials deny table} — specific things that are inside
 *       an allowed root but must not be used anyway (reflection, threads,
 *       sockets, mixins, loader internals). Checked first because it produces
 *       the better message: "reflection is not available" beats "java/lang/
 *       reflect/Method is not an allowed package".</li>
 *   <li>the {@linkplain #allowedRoots allowlist} of package roots — everything
 *       else is refused by default.</li>
 * </ol>
 *
 * <p>The allowlist is the load-bearing half and it is deliberately an
 * allowlist: a model that reaches for something we never thought of gets a
 * diagnostic and one self-heal round, rather than reaching it. The deny table
 * exists because {@code java/} has to be allowed wholesale (it is the standard
 * library) and a handful of corners of it are not compatible with a mod the
 * host must be able to unload at any moment.
 *
 * <p>A host builds its own instance at wiring time (V3 Phase 0 §A): the surgeon
 * hardcodes no loader knowledge, so NeoForge can add one denial and turn every
 * {@code net/fabricmc/} reference into a clear "not available here yet"
 * diagnostic without a second surgeon.
 *
 * @param allowedRoots internal-name prefixes (slash form, trailing slash) that
 *                     may be referenced at all
 * @param denials      carve-outs inside those roots, checked first
 */
public record SurgeonPolicy(List<String> allowedRoots, List<Denial> denials) {

    public SurgeonPolicy {
        allowedRoots = List.copyOf(allowedRoots);
        denials = List.copyOf(denials);
    }

    /**
     * One forbidden thing.
     *
     * @param ownerPrefix internal-name prefix of the owning type, e.g.
     *                    {@code "java/lang/reflect/"} or {@code "java/lang/Thread"}
     * @param member      null to forbid the type outright, an exact member name
     *                    ({@code "start"}, {@code "<init>"}) to forbid one
     *                    member, or {@code "*Suffix"} to forbid every member
     *                    whose name ends that way
     * @param unless      internal-name prefixes exempted from this denial
     * @param detail      what goes after {@code "forbidden API: "} in the diagnostic
     */
    public record Denial(String ownerPrefix, String member, List<String> unless, String detail) {

        public Denial {
            unless = List.copyOf(unless);
        }

        public Denial(String ownerPrefix, String member, String detail) {
            this(ownerPrefix, member, List.of(), detail);
        }

        /** Whether this denial covers a reference to {@code owner}'s {@code memberName} (null = a bare type reference). */
        boolean matches(String owner, String memberName) {
            if (!owner.startsWith(ownerPrefix)) {
                return false;
            }
            for (String exempt : unless) {
                if (owner.startsWith(exempt)) {
                    return false;
                }
            }
            if (member == null) {
                return true;
            }
            if (memberName == null) {
                return false;
            }
            return member.startsWith("*")
                    ? memberName.endsWith(member.substring(1))
                    : member.equals(memberName);
        }
    }

    /**
     * The package roots a generated loader mod may reference.
     *
     * <p>{@code com/gijsm/vibemod/} is on the list on purpose and it is not a
     * self-serving exception: the legacy {@code Mod}/{@code VibeContext} corpus
     * is still supported and still recompiled on every boot (§5 restore), so
     * banning the sdk here would fail every stored mod written before V3.
     * {@code vibemod/} is the generated code's own package.
     */
    public static final List<String> DEFAULT_ROOTS = List.of(
            "java/",
            "net/minecraft/",
            "com/mojang/",
            "org/joml/",
            "it/unimi/",
            "net/fabricmc/api/",
            "net/fabricmc/fabric/api/",
            "com/gijsm/vibemod/",
            "vibemod/");

    /** The carve-outs inside {@link #DEFAULT_ROOTS}. */
    public static final List<Denial> DEFAULT_DENIALS = List.of(
            new Denial("java/lang/reflect/", null,
                    "reflection (a mod the host can unload must not reach around its own class loader)"),
            new Denial("java/lang/invoke/MethodHandles", null,
                    "method handles (same reason as reflection)"),
            // `new Thread(...)` shows up as invokespecial Thread.<init>, so the
            // constructor denial catches construction and `start` catches the
            // case where a Thread arrives from somewhere else.
            // "java/lang/Thread" is a PREFIX, so these two also caught
            // ThreadLocal and ThreadGroup — and told a model it was "creating
            // threads" when a ThreadLocal is thread *confinement*, the opposite
            // of the hazard the rule exists for. The palette gate's canary hit
            // it. A wrong diagnostic is worse than a missing one: it sends the
            // repair round somewhere there is nothing to fix.
            new Denial("java/lang/Thread", "<init>", List.of("java/lang/ThreadLocal"),
                    "creating threads (mod code runs on the server thread)"),
            new Denial("java/lang/Thread", "start", List.of("java/lang/ThreadLocal"),
                    "starting threads (mod code runs on the server thread)"),
            new Denial("java/util/concurrent/Executors", null, "thread pools"),
            new Denial("java/util/concurrent/ForkJoinPool", null, "thread pools"),
            new Denial("java/util/concurrent/CompletableFuture", "*Async", "off-thread work"),
            // Same prefix trap as the Thread denials above, and a worse one:
            // "java/lang/Runtime" also matches RuntimeException and
            // RuntimePermission, so a mod that merely CATCHES RuntimeException —
            // about as ordinary as Java gets — was refused and told it had used
            // "the process runtime". The smoke gate found it on a real server.
            new Denial("java/lang/Runtime", null,
                    List.of("java/lang/RuntimeException", "java/lang/RuntimePermission"),
                    "the process runtime"),
            new Denial("java/lang/ProcessBuilder", null, "starting processes"),
            new Denial("java/lang/System", "exit", "shutting the JVM down"),
            // URI is pure text and shows up in perfectly innocent code; the
            // rest of java.net is a socket one way or another.
            new Denial("java/net/", null, List.of("java/net/URI"), "networking"),
            new Denial("org/spongepowered/", null, "mixins"),
            new Denial("net/fabricmc/loader/", null, "loader internals"),
            new Denial("net/fabricmc/fabric/impl/", null, "Fabric API internals"),
            new Denial("net/fabricmc/fabric/mixin/", null, "Fabric API internals"),
            // Phase ordering is global and permanent: a mod that reorders an
            // event's phases changes behaviour for every other mod and cannot
            // be undone when it is disabled.
            new Denial("net/fabricmc/fabric/api/event/Event", "addPhaseOrdering",
                    "Event.addPhaseOrdering (phase order is global and cannot be undone on disable)"),
            // V4 Phase 1. Same objection as HudElementRegistry.removeElement:
            // BlockColors is built once per client, from every registration
            // made before the build, and has no unregister — so a tint a mod
            // adds outlives the mod, for the rest of the session, with no way
            // to take it back. A coloured texture is the same picture and the
            // host can drop it with the rest of the pack.
            new Denial("net/fabricmc/fabric/api/client/rendering/v1/BlockColorRegistry", null,
                    "BlockColorRegistry (per-block tint colours are built once per client and "
                            + "cannot be unregistered; ship a coloured texture instead)"));

    /** The stock policy: {@link #DEFAULT_ROOTS} and {@link #DEFAULT_DENIALS}. */
    public static SurgeonPolicy defaults() {
        return new SurgeonPolicy(DEFAULT_ROOTS, DEFAULT_DENIALS);
    }

    /** The stock policy plus host-specific denials (NeoForge adds one for {@code net/fabricmc/}). */
    public static SurgeonPolicy defaultsPlus(Denial... extra) {
        List<Denial> all = new ArrayList<>(DEFAULT_DENIALS);
        all.addAll(List.of(extra));
        return new SurgeonPolicy(DEFAULT_ROOTS, all);
    }

    /** The denial covering {@code owner}'s {@code memberName}, or null. */
    Denial denialFor(String owner, String memberName) {
        for (Denial denial : denials) {
            if (denial.matches(owner, memberName)) {
                return denial;
            }
        }
        return null;
    }

    /** Whether {@code owner} lives under an allowed package root. */
    boolean allows(String owner) {
        for (String root : allowedRoots) {
            if (owner.startsWith(root)) {
                return true;
            }
        }
        return false;
    }
}
