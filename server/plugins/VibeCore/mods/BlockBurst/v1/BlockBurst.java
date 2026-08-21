package vibemod.blockburst;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;

public final class BlockBurst implements VibeMod {
    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        ctx.listen(new BlockBreakEffectListener(ctx));
        ctx.log().info("BlockBurst enabled.");
    }
}
