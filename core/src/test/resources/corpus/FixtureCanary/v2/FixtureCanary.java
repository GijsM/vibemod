package vibemod.fixturecanary;

import com.gijsm.vibemod.api.Mod;
import com.gijsm.vibemod.api.VibeContext;
import java.util.Locale;

/**
 * Fixture corpus, v2 — v1 plus a delayed greeting, so the fixture has a real
 * version history and {@code ctx.later(...)} is covered too. The corpus gate
 * compiles EVERY version on disk, not just the current one: a mod's older
 * sources have to keep compiling because rolling back to them is a supported
 * operation.
 */
public final class FixtureCanary implements Mod {

    private FixtureListener listener;

    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        this.listener = new FixtureListener(ctx);

        ctx.command("fixtureping", "Fixture corpus canary", (sender, args) ->
                sender.sendMessage(ctx.configString("greeting") + " #" + listener.seen()));

        ctx.action("ping", (sender, args) -> {
            long times = ctx.configInt("count");
            for (long i = 0; i < times; i++) {
                sender.sendMessage("fixture-action " + i);
            }
        });

        ctx.listen(listener);
        ctx.repeat(20L, 20L, listener::tick);

        // New in v2.
        ctx.later(100L, () -> ctx.log().info("late " + ctx.configString("greeting")));

        if (ctx.configBool("loud")) {
            ctx.log().info(("scale=" + ctx.configDouble("scale")).toUpperCase(Locale.ROOT));
        }
        ctx.log().info("FixtureCanary v2 enabled in " + ctx.dataFolder() + " on " + ctx.server().getName());
    }

    @Override
    public void onDisable(VibeContext ctx) {
        ctx.log().info("FixtureCanary disabled after " + listener.seen() + " events");
    }
}
