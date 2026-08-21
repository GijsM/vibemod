package vibemod.hauntedstands;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;

public final class HauntedStands implements VibeMod {

    private static final long BASE_PERIOD_TICKS = 10L;

    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        HauntedStandsTask task = new HauntedStandsTask(ctx);
        ctx.repeat(BASE_PERIOD_TICKS, BASE_PERIOD_TICKS, task::tick);
        ctx.log().info("HauntedStands enabled.");
    }
}
