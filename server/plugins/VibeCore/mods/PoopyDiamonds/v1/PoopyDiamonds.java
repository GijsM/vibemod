package vibemod.poopydiamonds;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;

public final class PoopyDiamonds implements VibeMod {
    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        ctx.listen(new PoopDropListener(ctx));
        ctx.log().info("PoopyDiamonds enabled. Diamonds are no more, only poop remains.");
    }
}
