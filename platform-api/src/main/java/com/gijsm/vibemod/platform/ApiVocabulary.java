package com.gijsm.vibemod.platform;

import java.util.Collections;
import java.util.Set;

/**
 * What the API of the running platform actually contains: which types exist,
 * which public constants they declare, which methods they declare.
 *
 * <p>This exists because VibeMod's prompt used to <em>assert</em> those facts in
 * hand-written prose and got them wrong on twelve supported versions
 * (docs/API-VOCABULARY.md has the measurements). A vocabulary is the measured
 * replacement: a host produces one from the classpath it is actually running
 * on, and the prompt builder and the pre-compile repair pass consume it instead
 * of guessing from a version string.
 *
 * <h2>The one rule: unknown is not absent</h2>
 *
 * <p>This is the load-bearing distinction and it is why the interface is shaped
 * the way it is. A later phase repairs generated code against a vocabulary. If
 * that pass reads "we never looked at this type" as "this type does not exist",
 * it will rewrite <em>working</em> code into broken code — silently, on a
 * platform we happen to have no index for. Losing a repair opportunity costs one
 * self-heal round; corrupting a correct program costs trust.
 *
 * <p>So the queries come in two flavours and the difference is visible at the
 * call site:
 *
 * <ul>
 *   <li>{@link Known}-returning queries — {@link #knows}, {@link #declaresConstant},
 *       {@link #declaresMethod} — are the honest ones. They answer
 *       {@link Known#YES}, {@link Known#NO} or {@link Known#UNKNOWN}, and
 *       <strong>anything that may delete or rewrite code must use these and must
 *       treat {@code UNKNOWN} as "leave it alone"</strong>.
 *   <li>The {@code boolean} conveniences — {@link #hasType}, {@link #hasMethod} —
 *       collapse {@code UNKNOWN} to {@code false}. That is the right reading for
 *       "may I offer this to the model?" (don't teach what you cannot vouch for)
 *       and the wrong reading for "must I repair this?". Their names say
 *       <em>has</em>, not <em>lacks</em>, so the safe direction is the one they
 *       express.
 * </ul>
 *
 * <p>The tri-state logic lives here as {@code default} methods derived from three
 * primitives, so an implementation cannot get it subtly wrong in its own way: to
 * write a vocabulary you supply {@link #knownTypes()}, {@link #constants} and
 * {@link #methods}, and every unknown-vs-absent decision is already made.
 *
 * <h2>Closed world</h2>
 *
 * <p>A non-empty vocabulary is a <em>closed world</em>: it was produced by
 * enumerating a whole platform surface (a jar index, a classpath scan), so a type
 * it does not list is genuinely absent, not merely unexamined. That is what lets
 * {@code knows("Dialog") == NO} be real evidence on Paper 1.21.6 rather than a
 * shrug. An {@link #empty()} vocabulary enumerated nothing, so it answers
 * {@code UNKNOWN} to everything — never {@code NO}. Do not build a vocabulary
 * that indexes only a handful of hand-picked types: it would report {@code NO}
 * for the rest of the API and mean {@code UNKNOWN}.
 */
public interface ApiVocabulary {

    /** A three-valued answer. {@link #UNKNOWN} is never a synonym for {@link #NO}. */
    enum Known {
        /** Measured, and present. */
        YES,
        /** Measured, and absent. Safe to act on. */
        NO,
        /** Not measured. Never act on this as if it were {@link #NO}. */
        UNKNOWN
    }

    // ------------------------------------------------------------------
    // Primitives — the three things an implementation supplies.
    // ------------------------------------------------------------------

    /**
     * The types this vocabulary actually knows about, by SIMPLE name
     * ({@code "Attribute"}, {@code "ItemMeta"}, {@code "Dialog"}).
     *
     * <p>Empty means this vocabulary enumerated nothing at all, and every query
     * on it answers {@link Known#UNKNOWN}. Callers must treat a type outside a
     * non-empty vocabulary as absent and a type outside an empty one as
     * unexamined — which is exactly what {@link #knows} does for them.
     */
    Set<String> knownTypes();

    /**
     * Public static constant names declared on an API type, keyed by SIMPLE name
     * ({@code "Attribute" -> {"MAX_HEALTH", ...}}).
     *
     * <p>Returns an empty set both for a type this vocabulary never saw and for a
     * known type that genuinely declares no constants. That ambiguity is
     * deliberate — it keeps the common "give me the vocabulary to show the model"
     * call trivial — but it means <strong>this method must not be used to
     * conclude that a constant is missing</strong>. Use {@link #declaresConstant}
     * for that; it distinguishes the two cases.
     */
    Set<String> constants(String simpleTypeName);

    /**
     * Method names declared on an API type, keyed by SIMPLE name. Names only:
     * overloads collapse to one entry, and nothing here says anything about
     * signatures.
     *
     * <p>Same ambiguity as {@link #constants}: empty may mean "unknown type". Use
     * {@link #declaresMethod} when the difference matters.
     */
    Set<String> methods(String simpleTypeName);

    // ------------------------------------------------------------------
    // Derived — written once, so no implementation can disagree.
    // ------------------------------------------------------------------

    /** Whether the type itself is present on this platform (e.g. {@code "Dialog"}). */
    default Known knows(String simpleTypeName) {
        if (knownTypes().isEmpty()) {
            return Known.UNKNOWN;
        }
        return knownTypes().contains(simpleTypeName) ? Known.YES : Known.NO;
    }

    /**
     * Whether a type declares a constant. {@link Known#UNKNOWN} when the type
     * itself was never examined — which is the answer that must stop a repair
     * pass from rewriting {@code Attribute.GENERIC_MAX_HEALTH} on a platform we
     * have no index for.
     */
    default Known declaresConstant(String simpleTypeName, String constantName) {
        Known type = knows(simpleTypeName);
        if (type != Known.YES) {
            return type == Known.NO ? Known.NO : Known.UNKNOWN;
        }
        return constants(simpleTypeName).contains(constantName) ? Known.YES : Known.NO;
    }

    /** Whether a type declares a method with this name. See {@link #declaresConstant}. */
    default Known declaresMethod(String simpleTypeName, String methodName) {
        Known type = knows(simpleTypeName);
        if (type != Known.YES) {
            return type == Known.NO ? Known.NO : Known.UNKNOWN;
        }
        return methods(simpleTypeName).contains(methodName) ? Known.YES : Known.NO;
    }

    /**
     * True when the type is present on this platform. Collapses
     * {@link Known#UNKNOWN} to false — correct for "may I rely on this?", wrong
     * for "may I delete a use of this?".
     */
    default boolean hasType(String simpleTypeName) {
        return knows(simpleTypeName) == Known.YES;
    }

    /**
     * True when the type is known AND declares a method with this name.
     * Collapses {@link Known#UNKNOWN} to false; see {@link #hasType}.
     */
    default boolean hasMethod(String simpleTypeName, String methodName) {
        return declaresMethod(simpleTypeName, methodName) == Known.YES;
    }

    /**
     * A vocabulary that measured nothing. Every query answers
     * {@link Known#UNKNOWN}, so a repair pass driven by it changes nothing and a
     * prompt builder driven by it teaches nothing version-specific. That is the
     * correct degradation for an unrecognised platform, and it is why this is the
     * fallback rather than a partial guess.
     */
    static ApiVocabulary empty() {
        return new ApiVocabulary() {
            @Override
            public Set<String> knownTypes() {
                return Collections.emptySet();
            }

            @Override
            public Set<String> constants(String simpleTypeName) {
                return Collections.emptySet();
            }

            @Override
            public Set<String> methods(String simpleTypeName) {
                return Collections.emptySet();
            }

            @Override
            public String toString() {
                return "ApiVocabulary.empty()";
            }
        };
    }
}
