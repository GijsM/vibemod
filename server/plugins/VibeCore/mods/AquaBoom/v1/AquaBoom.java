package vibemod.aquaboom;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;

public final class AquaBoom implements VibeMod {

    private static final long PERIOD_TICKS = 10L;

    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        ChickenWaterChecker checker = new ChickenWaterChecker(ctx);
        ctx.repeat(PERIOD_TICKS, PERIOD_TICKS, checker::scan);
        ctx.log().info("AquaBoom enabled.");
    }
}
