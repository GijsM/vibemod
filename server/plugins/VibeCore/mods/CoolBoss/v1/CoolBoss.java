package vibemod.coolboss;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;
import org.bukkit.entity.Player;

public final class CoolBoss implements VibeMod {

    private BossManager manager;

    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        manager = new BossManager(ctx);
        ctx.listen(manager);
        ctx.repeat(20L, 20L, manager::tick);

        ctx.command("coolboss", "Summons the Stormlord boss near you", (sender, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can summon the Stormlord.");
                return;
            }
            manager.spawnBoss(player);
        });

        ctx.log().info("CoolBoss enabled.");
    }

    @Override
    public void onDisable(VibeContext ctx) {
        if (manager != null) {
            manager.clear();
        }
    }
}
