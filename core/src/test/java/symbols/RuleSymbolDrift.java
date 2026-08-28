package symbols;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.gijsm.vibemod.llm.PromptRule;
import com.gijsm.vibemod.llm.PromptRules;

/**
 * The anti-drift property: <strong>every symbol a rule's text names must appear
 * in that rule's {@code requiresSymbols} or {@code forbidsSymbols}.</strong>
 *
 * <p>This is the check that keeps {@link PromptSymbolGate} honest. The gate
 * verifies claims against real jars, but it can only verify claims it can see;
 * a sentence that names a constant nothing declares is a claim no gate reads.
 * That is exactly how the prompt reached the state docs/API-VOCABULARY.md
 * measured — a worked example naming {@code Enchantment.DURABILITY} sat in the
 * legacy cheat sheet for a year, true on 5 of 21 versions, with nothing
 * anywhere that could notice.
 *
 * <h2>Extracted, and extended, from LlmSelfTest</h2>
 *
 * <p>A first version of this lived inline in {@code LlmSelfTest}. It is here so
 * both callers run the same code, and it is stricter in two ways that its
 * original leniency allowed through:
 *
 * <ol>
 *   <li><strong>Qualified references must match qualified declarations.</strong>
 *       The original accepted any declaration whose bare name matched, so
 *       {@code Particle.SMOKE} in the text was satisfied by
 *       {@code PotionEffectType.SMOKE} in the list — a symbol on the wrong type
 *       is the exact mistake this is supposed to catch. Method-call syntax is
 *       normalised first ({@code Identifier.withDefaultNamespace(...)} is the
 *       same claim as {@code Identifier#withDefaultNamespace}), which is what
 *       makes the strict comparison possible at all.
 *   <li><strong>Bare constants are checked.</strong> The rename rules are
 *       written {@code `DAMAGE_RESISTANCE` (not `RESISTANCE`)}, so most of the
 *       constants the prompt names carry no type at all and the original
 *       pattern — which required a {@code .} or {@code #} — never saw them. A
 *       bare constant is matched by NAME against the declared lists, since that
 *       is all the text gives.
 * </ol>
 */
public final class RuleSymbolDrift {

    private RuleSymbolDrift() {
    }

    /** {@code `Type.CONSTANT`}, {@code `Type#method`}, {@code `Type.method(`}. */
    private static final Pattern QUALIFIED = Pattern.compile(
            "\\b([A-Z][A-Za-z0-9]*)([.#])([A-Za-z_][A-Za-z0-9_]*)\\s*(\\()?");

    /** A bare {@code `SCREAMING_CASE`} token: the rename rules' usual spelling. */
    private static final Pattern BARE = Pattern.compile("^[A-Z][A-Z0-9_]{3,}$");

    /** Text inside single backticks, the prompt's convention for naming a symbol. */
    private static final Pattern BACKTICKED = Pattern.compile("`([^`\n]+)`");

    /**
     * All-caps words that name a TYPE rather than a constant, so they carry no
     * claim about a constant's existence. Kept deliberately tiny: every entry is
     * a hole in the check, and the alternative — dropping the bare-constant scan
     * — is a much larger one.
     */
    private static final Set<String> NOT_CONSTANTS = Set.of("UUID", "JSON", "HTTP", "HUD");

    /** Every rule table the prompt can emit. */
    public static List<PromptRule> allRules() {
        List<PromptRule> all = new ArrayList<>(PromptRules.PAPER);
        all.addAll(PromptRules.LOADER);
        return all;
    }

    /**
     * One undeclared symbol.
     *
     * @param ruleId  the rule whose text names it
     * @param symbol  {@code Type.CONSTANT} or {@code Type#method} when the text
     *                gave a type, otherwise {@code null} for a bare constant —
     *                which no jar can be asked about, since nothing says which
     *                type it is on
     * @param message the human-readable finding
     */
    public record Violation(String ruleId, String symbol, String message) {
    }

    /** One human-readable line per undeclared symbol; empty when the tables are clean. */
    public static List<String> violations() {
        List<String> out = new ArrayList<>();
        for (Violation v : findings()) {
            out.add(v.message());
        }
        return out;
    }

    /** The same findings, with the symbol kept separate so a caller can measure it. */
    public static List<Violation> findings() {
        List<Violation> out = new ArrayList<>();
        for (PromptRule rule : allRules()) {
            Set<String> declared = new HashSet<>(rule.requiresSymbols());
            declared.addAll(rule.forbidsSymbols());
            Set<String> declaredNames = new HashSet<>();
            for (String d : declared) {
                int cut = Math.max(d.indexOf('#'), d.lastIndexOf('.'));
                declaredNames.add(cut < 0 ? d : d.substring(cut + 1));
            }

            Matcher spans = BACKTICKED.matcher(rule.text());
            while (spans.find()) {
                String span = spans.group(1);
                Set<String> qualifiedHere = new HashSet<>();
                Matcher m = QUALIFIED.matcher(span);
                while (m.find()) {
                    String reference = normalise(m);
                    qualifiedHere.add(m.group(3));
                    if (!declared.contains(reference)) {
                        out.add(new Violation(rule.id(), reference,
                                rule.id() + " names " + reference + " in its text but lists it in"
                                        + " neither requiresSymbols nor forbidsSymbols, so no gate"
                                        + " can tell whether the sentence claims it exists or that"
                                        + " it does not (requires=" + rule.requiresSymbols()
                                        + " forbids=" + rule.forbidsSymbols() + ")"));
                    }
                }
                // A bare `CONSTANT`: the whole span is one token, and it is not
                // the tail of a qualified reference already handled above.
                String token = span.trim();
                if (BARE.matcher(token).matches()
                        && !NOT_CONSTANTS.contains(token)
                        && !qualifiedHere.contains(token)
                        && !declaredNames.contains(token)) {
                    out.add(new Violation(rule.id(), null,
                            rule.id() + " names bare constant " + token + " in its text but lists"
                                    + " no symbol by that name in requiresSymbols or forbidsSymbols,"
                                    + " so no jar is ever asked whether it exists"
                                    + " (requires=" + rule.requiresSymbols()
                                    + " forbids=" + rule.forbidsSymbols() + ")"));
                }
            }
        }
        return out;
    }

    /**
     * {@code Type.name(} and {@code Type#name} are the same claim — a method —
     * and {@code Type.NAME} without a call is a constant. Normalising here is
     * what lets the comparison against the declared lists be exact.
     */
    private static String normalise(Matcher m) {
        boolean method = "#".equals(m.group(2)) || m.group(4) != null;
        return m.group(1) + (method ? "#" : ".") + m.group(3);
    }
}
