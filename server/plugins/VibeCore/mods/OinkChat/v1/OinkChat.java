package vibemod.oinkchat;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;

public final class OinkChat implements VibeMod {
    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        ctx.listen(new OinkChatListener());
        ctx.log().info("OinkChat enabled. Oink oink!");
    }
}
