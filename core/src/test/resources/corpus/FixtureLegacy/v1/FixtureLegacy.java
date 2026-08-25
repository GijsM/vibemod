package vibemine.fixturelegacy;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;

/**
 * A mod exactly as it sits on disk from before two renames, and the reason this
 * file is checked in: two compatibility promises are load-bearing for anyone who
 * has been running VibeMod since v1, and nothing else in the headless matrix
 * exercises either of them.
 *
 * <p>One: the imports say {@code com.gijsm.vibemine.api}. That package no longer
 * exists — {@code ModStore.sources()} rewrites the string on read, and if that
 * rewrite is ever dropped as "dead code", every mod generated before the rename
 * stops recompiling on restore-on-boot.
 *
 * <p>Two: it {@code implements VibeMod}, the deprecated bridge interface kept
 * for exactly these mods. New code implements {@code Mod}.
 *
 * <p>Do NOT "modernise" this file. Its staleness is the assertion.
 */
public class FixtureLegacy implements VibeMod {

    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        ctx.command("fixturelegacy", "A mod older than the rename", (sender, args) ->
                sender.sendMessage("still here"));
        ctx.log().info("FixtureLegacy enabled");
    }

    @Override
    public void onDisable(VibeContext ctx) {
        ctx.log().info("FixtureLegacy disabled");
    }
}
