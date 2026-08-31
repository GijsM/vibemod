package com.gijsm.vibemod.platform;

import java.util.Map;

/**
 * The bytecode seam (ARCHITECTURE-V2 §7, V3 Phase 0): the host's one chance to
 * inspect and rewrite a generated mod's compiled classes between a successful
 * compile and {@code defineClass}.
 *
 * <p>This interface is the reason V3's thesis works at all. A generated mod is
 * an <em>ordinary loader mod</em> now — it implements the loader's own
 * entrypoint interface and registers to the loader's own events, with zero
 * VibeMod imports. Neither of those is revocable: a Fabric {@code Event} cannot
 * be unsubscribed, so a mod that really called {@code Event.register} could
 * never be torn down, and VibeMod's whole teardown model (§0#10) is that a
 * disabled mod leaves nothing behind. The fix is not to forbid the call but to
 * <em>redirect</em> it: the host rewrites the call site into a shim it owns,
 * and the shim keeps one permanent subscription with a revocable per-mod
 * registry behind it. The mod's source never knows.
 *
 * <p>The same pass is the security boundary. Generated code arrives from a
 * language model and is compiled against the running game's real classpath, so
 * "it compiled" says nothing about what it reaches for. The surgeon verifies
 * every type and member the bytecode actually references against an allowlist
 * of package roots plus a deny table, and reports violations as javac-shaped
 * diagnostic lines — which is what lets them ride the existing self-heal loop
 * instead of needing a second error channel.
 *
 * <p>Lives in {@code platform-api} rather than {@code core} because it must be
 * nameable from {@code core} (which compiles {@code --release 21} and bans
 * platform types) while the only implementation needs {@code java.lang.classfile}
 * and therefore Java 25. Paper passes no surgeon at all, and a null surgeon is
 * a pass-through.
 */
public interface ClassSurgeon {

    /**
     * The outcome of one pass over a mod's compiled classes.
     *
     * <p>Deliberately shaped like {@code CompileResult}: on success
     * {@code classes} is what should be defined (rewritten or not), on failure
     * {@code diagnostics} holds javac-style {@code <File>.java: error: …} lines
     * that go straight back to the model.
     */
    record Result(boolean ok, Map<String, byte[]> classes, String diagnostics) {

        public Result {
            classes = Map.copyOf(classes);
        }

        /** The classes passed policy; {@code classes} is what to define. */
        public static Result accepted(Map<String, byte[]> classes) {
            return new Result(true, classes, "");
        }

        /** The classes violated policy; {@code diagnostics} says how, in javac's shape. */
        public static Result rejected(String diagnostics) {
            return new Result(false, Map.of(), diagnostics);
        }
    }

    /**
     * Verifies and (where a seam matches) rewrites one mod's compiled classes.
     *
     * @param classes binary class name -&gt; bytecode, exactly as the compiler
     *                produced it, inner and synthetic classes included
     * @return {@link Result#accepted} carrying the classes to define, or
     *         {@link Result#rejected} carrying diagnostics; never null
     */
    Result operate(Map<String, byte[]> classes);
}
