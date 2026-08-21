package vibemod.chickencreepers;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;

public final class ChickenCreepers implements VibeMod {
    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        ctx.listen(new CreeperDeathListener(ctx));
        ctx.log().info("ChickenCreepers enabled.");
    }
}
