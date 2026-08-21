package vibemod.zombiefireworks;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;

public final class ZombieFireworks implements VibeMod {
    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        ctx.listen(new ZombieDeathListener(ctx));
        ctx.log().info("ZombieFireworks enabled.");
    }
}
