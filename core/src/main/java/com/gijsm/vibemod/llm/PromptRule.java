package com.gijsm.vibemod.llm;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * One line (or short block) of platform guidance, plus the condition under which
 * it is true and the symbols it stakes that claim on.
 *
 * <p>The old design put all of this in one hand-written {@code cheatSheet}
 * string per era, and the eras were chosen by a single version comparison. That
 * cannot track the API: there are at least three vocabulary boundaries inside
 * the range one of those two eras served (1.20.5, 1.21, 1.21.3), and the era
 * split is at none of them. Measured consequences are in docs/API-VOCABULARY.md.
 *
 * <h2>The invariant: a rule can never contradict a probe</h2>
 *
 * <p>This is enforced by {@link PromptRules#render}, not by trusting each
 * {@link #when} predicate to have got it right. Before a rule is emitted, every
 * entry in {@link #requiresSymbols} must not be measured absent and every entry
 * in {@link #forbidsSymbols} must not be measured present. A rule that says
 * "{@code X} is available here" therefore cannot survive onto a server whose own
 * classpath says otherwise, however wrong its predicate is — and a rule that
 * forbids {@code X} cannot survive onto a server that has it, which is exactly
 * the defect that made the legacy sheet ban
 * {@code ItemMeta#setEnchantmentGlintOverride} on eight versions that ship it.
 *
 * <p>{@link com.gijsm.vibemod.platform.ApiVocabulary.Known#UNKNOWN} suppresses
 * nothing. A vocabulary that measured nothing must not silently strip the whole
 * cheat sheet, so "we did not look" leaves a rule standing and only positive
 * counter-evidence removes it.
 *
 * <h2>Populate the symbol lists honestly</h2>
 *
 * <p><strong>Every {@code Type.CONSTANT} and {@code Type#method} a rule's
 * {@link #text} names must appear in one of the two lists.</strong> That is the
 * anti-drift mechanism: the offline gate (Objective B3) walks these lists
 * against each supported version's {@code paper-api} jar, so a symbol that is
 * only in the prose is a symbol nothing checks. The lists may also carry the
 * evidence a claim rests on even when the text does not name it — the
 * {@code AttributeModifier} rules key off {@code AttributeModifier#getKey} to
 * decide which constructor era they are in, and say so in a comment.
 *
 * @param id              stable identifier, {@code "paper.itemmeta.glint.yes"};
 *                        used by the gate and the self-tests to name a rule
 *                        without quoting its prose
 * @param when            evaluated against the running server's facts
 * @param text            the prompt text, emitted verbatim. Deliberately a
 *                        literal rather than anything generated from the
 *                        vocabulary, so that what the gate reads is exactly what
 *                        the model reads
 * @param requiresSymbols symbols the text presents as usable
 * @param forbidsSymbols  symbols the text presents as absent or banned
 */
public record PromptRule(String id,
                         Predicate<PromptFacts> when,
                         String text,
                         List<String> requiresSymbols,
                         List<String> forbidsSymbols) {

    public PromptRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(when, "when");
        Objects.requireNonNull(text, "text");
        requiresSymbols = List.copyOf(requiresSymbols);
        forbidsSymbols = List.copyOf(forbidsSymbols);
    }

    /** A rule that is true on every platform this profile serves. */
    public static PromptRule always(String id, String text,
                                    List<String> requires, List<String> forbids) {
        return new PromptRule(id, facts -> true, text, requires, forbids);
    }

    /** A rule with no symbol claims to check — style and shape guidance. */
    public static PromptRule always(String id, String text) {
        return always(id, text, List.of(), List.of());
    }

    /**
     * Whether the measured vocabulary contradicts this rule's own claims. See
     * the class javadoc; {@link PromptRules#render} calls this and no rule is
     * emitted when it answers true.
     */
    public boolean contradictedBy(PromptFacts facts) {
        for (String required : requiresSymbols) {
            if (facts.lacks(required)) {
                return true;
            }
        }
        for (String forbidden : forbidsSymbols) {
            if (facts.declares(forbidden)) {
                return true;
            }
        }
        return false;
    }

    /** Whether this rule should be shown: its predicate holds and nothing contradicts it. */
    public boolean appliesTo(PromptFacts facts) {
        return when.test(facts) && !contradictedBy(facts);
    }
}
