package vibemod.fixturecanary;

import com.gijsm.vibemod.api.Mod;
import com.gijsm.vibemod.api.VibeContext;
import java.util.Locale;

/**
 * Fixture corpus, v1. Between this and {@link FixtureListener} it touches every
 * registration path a generated Paper mod has: a top-level command, a named
 * action, a Bukkit listener, a repeating task, and all four config readers.
 *
 * <p>This file is a COMPILE fixture. Nothing runs it; the only thing asserted
 * about it is that it still compiles against the live sdk.
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

        if (ctx.configBool("loud")) {
            ctx.log().info(("scale=" + ctx.configDouble("scale")).toUpperCase(Locale.ROOT));
        }
        ctx.log().info("FixtureCanary enabled in " + ctx.dataFolder() + " on " + ctx.server().getName());
    }

    @Override
    public void onDisable(VibeContext ctx) {
        ctx.log().info("FixtureCanary disabled after " + listener.seen() + " events");
    }
}
