package com.gijsm.vibemod.llm;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The measured constant lists, injected into the prompt verbatim instead of
 * described in prose.
 *
 * <p>This is the half of the rework that retires an error class rather than
 * fixing an instance of it. No sentence anywhere now claims which attribute
 * names are real: the running server is asked, and its answer is pasted in. A
 * Minecraft release that renames something cannot make this wrong, which is
 * exactly what could not be said of the hand-maintained era prose it replaces.
 *
 * <h2>What is dumped, and what is not</h2>
 *
 * <p>Measured across all 21 cached {@code paper-api} jars, joined with
 * {@code ", "}:
 *
 * <table border="1">
 *   <caption>Cost of a full dump, per type</caption>
 *   <tr><th>Type</th><th>Constants</th><th>Chars</th><th>Dumped</th></tr>
 *   <tr><td>{@code Attribute}</td><td>13-40</td><td>291-779</td><td>yes</td></tr>
 *   <tr><td>{@code Enchantment}</td><td>39-43</td><td>505-531</td><td>yes</td></tr>
 *   <tr><td>{@code PotionEffectType}</td><td>33-40</td><td>384-480</td><td>yes</td></tr>
 *   <tr><td>{@code Particle}</td><td>101-125</td><td>~1,500</td><td>no</td></tr>
 *   <tr><td>{@code EntityType}</td><td>125-159</td><td>~1,600</td><td>no</td></tr>
 *   <tr><td>{@code Sound}</td><td>1,474-1,968</td><td>~45,000</td><td>no</td></tr>
 *   <tr><td>{@code Material}</td><td>1,866-2,155</td><td>~30,000</td><td>no</td></tr>
 * </table>
 *
 * <p>The three dumped types cost at most ~1.8k characters together — roughly
 * 450 tokens — and they are precisely the three the measurements found the
 * prompt lying about. The four omitted ones are covered by
 * {@code paper.constants.big-enums}, which tells the model they are not listed
 * and what to do instead; a local validator that repairs their spellings without
 * an LLM round-trip is the next phase's job, not the prompt's.
 *
 * <p>{@code Particle} and {@code EntityType} are borderline on cost alone and
 * are excluded deliberately: dumping 150 names invites the model to reach for an
 * exotic one, where the current advice — prefer an obviously-common constant —
 * biases it toward names that have been stable for a decade.
 */
public final class VocabularyBlock {

    private VocabularyBlock() {
    }

    /** The types dumped in full, in emission order. */
    private static final List<String> DUMPED = List.of("Attribute", "Enchantment", "PotionEffectType");

    /**
     * The measured constant lists, or {@code ""} when nothing was measured.
     *
     * <p>Empty is the correct output for a host with no vocabulary — a loader,
     * or a self-test building a prompt with no server behind it. Saying nothing
     * is right; inventing a list from a version guess is what this replaces.
     */
    public static String render(PromptFacts facts) {
        StringBuilder sb = new StringBuilder();
        for (String type : DUMPED) {
            if (!facts.hasConstants(type)) {
                continue;
            }
            Set<String> sorted = new TreeSet<>(facts.constants(type));
            sb.append("\nEvery `").append(type).append("` constant on this server (")
                    .append(sorted.size()).append(", exhaustive - any other spelling is a compile error):\n");
            sb.append(String.join(", ", sorted)).append('\n');
        }
        return sb.toString();
    }
}
