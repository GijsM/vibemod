package vibemod.combocounter;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ComboCounter implements VibeMod {

    private final ComboState state = new ComboState();

    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        ctx.listen(new ComboListener(ctx, state));
        ctx.repeat(20L, 20L, () -> timeoutTick(ctx));
        ctx.log().info("ComboCounter enabled.");
    }

    private void timeoutTick(VibeContext ctx) {
        state.tickClock();
        long timeoutSeconds = ctx.configInt("combo-timeout-seconds");
        if (timeoutSeconds < 1) {
            timeoutSeconds = 1;
        }
        long clock = state.clock();
        Map<UUID, Integer> combos = state.all();
        if (combos.isEmpty()) {
            return;
        }
        java.util.List<UUID> expired = new java.util.ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : combos.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            Long lastHit = state.lastHit(entry.getKey());
            if (lastHit == null) {
                continue;
            }
            if (clock - lastHit >= timeoutSeconds) {
                expired.add(entry.getKey());
            }
        }
        for (UUID id : expired) {
            state.reset(id);
            Player player = ctx.server().getPlayer(id);
            if (player != null && player.isOnline() && ctx.configBool("show-actionbar")) {
                player.sendActionBar("\u00a77Combo expired.");
            }
        }
    }
}
