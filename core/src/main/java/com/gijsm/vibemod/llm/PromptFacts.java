package com.gijsm.vibemod.llm;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

import com.gijsm.vibemod.platform.ApiVocabulary;
import com.gijsm.vibemod.platform.ApiVocabulary.Known;
import com.gijsm.vibemod.platform.PlatformInfo;

/**
 * Everything the prompt builder is allowed to know about the server it is
 * writing for: the {@link PlatformProfile} chosen for it, the boot-time
 * capability probes on {@link PlatformInfo}, and the {@link ApiVocabulary} the
 * host measured off its own classpath.
 *
 * <p>This type exists to end a specific, measured failure. Before it,
 * {@code PromptLibrary.systemPrompt} received only a {@link PlatformProfile},
 * itself chosen by one version comparison, while {@link PlatformInfo} had
 * already probed eight capabilities at boot and <em>none of them reached the
 * prompt</em>. The result was a prompt that contradicted the host's own probes:
 * it forbade {@code ItemMeta#setEnchantmentGlintOverride} on eight versions that
 * have it, and taught {@code Attribute.GENERIC_MAX_HEALTH} on four versions
 * where only the short form compiles (docs/API-VOCABULARY.md, claims 3 and 4).
 *
 * <h2>Symbol references</h2>
 *
 * <p>{@link #symbol} reads the two spellings the rule table and the offline gate
 * both use:
 *
 * <ul>
 *   <li>{@code "Attribute.MAX_HEALTH"} — a constant on a type;
 *   <li>{@code "ItemMeta#setEnchantmentGlintOverride"} — a method on a type.
 * </ul>
 *
 * <p>Both answer {@link Known}, never a boolean, because the difference between
 * "measured absent" and "never looked" is the whole point of
 * {@link ApiVocabulary}. The boolean conveniences below name which collapse they
 * make: {@link #declares} is "measured present", {@link #lacks} is "measured
 * absent", and {@link #notAbsent} is "not known to be missing" — the right test
 * for text that is safe to show unless we have positive evidence against it.
 *
 * @param profile    the profile whose fixed text this prompt is built from
 * @param info       the host's boot probes, or {@code null} when the prompt is
 *                   being built outside a running host (self-tests, the jar
 *                   exporter). Never dereference it directly; use the accessors.
 * @param vocabulary what the running classpath actually declares; never null,
 *                   {@link ApiVocabulary#empty()} when nothing was measured
 */
public record PromptFacts(PlatformProfile profile, PlatformInfo info, ApiVocabulary vocabulary) {

    public PromptFacts {
        Objects.requireNonNull(profile, "profile");
        vocabulary = vocabulary == null ? ApiVocabulary.empty() : vocabulary;
    }

    /** The facts a live host has: its own profile, probes and measured vocabulary. */
    public static PromptFacts of(PlatformInfo info) {
        Objects.requireNonNull(info, "info");
        return new PromptFacts(PlatformProfiles.forPlatform(info), info, info.vocabulary());
    }

    /**
     * A profile with no host behind it. Every vocabulary query answers
     * {@link Known#UNKNOWN}, so capability-predicated rules drop out and only
     * the version-independent text survives — the correct degradation, and what
     * the self-tests and {@code JarExporter} build against.
     */
    public static PromptFacts unknown(PlatformProfile profile) {
        return new PromptFacts(profile, null, ApiVocabulary.empty());
    }

    // ------------------------------------------------------------------
    // Symbol queries — the honest tri-state
    // ------------------------------------------------------------------

    /**
     * Whether {@code "Type.CONSTANT"} or {@code "Type#method"} exists here.
     * An unparseable reference answers {@link Known#UNKNOWN} rather than
     * throwing: a malformed entry in a rule's symbol list must not be able to
     * suppress a rule, only to fail the offline gate that checks the spelling.
     */
    public Known symbol(String reference) {
        if (reference == null) {
            return Known.UNKNOWN;
        }
        int hash = reference.indexOf('#');
        if (hash > 0) {
            return vocabulary.declaresMethod(reference.substring(0, hash), reference.substring(hash + 1));
        }
        int dot = reference.lastIndexOf('.');
        if (dot > 0) {
            return vocabulary.declaresConstant(reference.substring(0, dot), reference.substring(dot + 1));
        }
        return vocabulary.knows(reference);
    }

    /** Measured present. */
    public boolean declares(String reference) {
        return symbol(reference) == Known.YES;
    }

    /** Measured absent — positive evidence, not a shrug. */
    public boolean lacks(String reference) {
        return symbol(reference) == Known.NO;
    }

    /** Present, or never measured. The right test for "safe to say unless we know better". */
    public boolean notAbsent(String reference) {
        return symbol(reference) != Known.NO;
    }

    /** The constants a type declares here, empty when the type was never measured. */
    public Set<String> constants(String simpleTypeName) {
        Set<String> c = vocabulary.constants(simpleTypeName);
        return c == null ? Collections.emptySet() : c;
    }

    /** True when the vocabulary measured this type and it declares at least one constant. */
    public boolean hasConstants(String simpleTypeName) {
        return vocabulary.knows(simpleTypeName) == Known.YES && !constants(simpleTypeName).isEmpty();
    }

    // ------------------------------------------------------------------
    // Host facts
    // ------------------------------------------------------------------

    /** The running Minecraft version, or {@code ""} when there is no host. */
    public String mcVersion() {
        String v = info == null ? null : info.mcVersion();
        return v == null ? "" : v.trim();
    }

    /**
     * Whether this server ticks in more than one ordering domain (Folia).
     *
     * <p>False with no host, which is the right default for the self-tests and
     * the jar exporter: they build the single-threaded prompt, which is what
     * every non-regionised platform gets and what an exported mod source should
     * describe.
     */
    public boolean regionised() {
        return info != null && info.isRegionised();
    }

    /** Whether a host is behind these facts at all. */
    public boolean hasHost() {
        return info != null;
    }
}
