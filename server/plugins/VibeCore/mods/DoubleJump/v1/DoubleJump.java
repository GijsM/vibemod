package vibemod.doublejump;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class DoubleJump implements VibeMod {

    private final Map<UUID, Boolean> airJumpUsed = new HashMap<>();

    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        ctx.listen(new DoubleJumpListener(ctx, airJumpUsed));
        ctx.repeat(1L, 1L, () -> resetOnGroundPlayers(ctx));
        ctx.log().info("DoubleJump enabled.");
    }

    private void resetOnGroundPlayers(VibeContext ctx) {
        for (Player player : ctx.server().getOnlinePlayers()) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (player.isOnGround()) {
                airJumpUsed.put(player.getUniqueId(), false);
            }
        }
    }
}
