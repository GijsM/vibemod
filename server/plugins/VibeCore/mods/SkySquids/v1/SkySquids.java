package vibemod.skysquids;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;

public final class SkySquids implements VibeMod {

    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        ctx.listen(new SquidFallGuard());
        ctx.repeat(5L, 5L, new SquidGlideTask(ctx));
        ctx.log().info("SkySquids enabled - squids can now fly!");
    }
}
