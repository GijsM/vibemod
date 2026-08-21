package vibemod.grapplinghook;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;

public final class GrapplingHook implements VibeMod {
    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        ctx.listen(new GrappleListener(ctx));
        ctx.log().info("GrapplingHook enabled.");
    }
}
