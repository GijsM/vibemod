package vibemod.rainbowsword;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class RainbowSword implements VibeMod {

    private int colorTick = 0;

    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        ctx.listen(new RainbowSwordListener(ctx));

        ctx.command("rainbowsword", "Gives you the legendary Rainbow Sword", (sender, args) -> {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can wield the Rainbow Sword.");
                return;
            }
            Player player = (Player) sender;
            ItemStack sword = RainbowItems.create(ctx.plugin());
            player.getInventory().addItem(sword);
            player.sendMessage(ChatColor.GOLD + "You feel the power of the rainbow surge through you!");
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            player.getWorld().spawnParticle(
                Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.4);
        });

        ctx.repeat(5L, 5L, () -> ambientTrail(ctx));

        ctx.log().info("RainbowSword enabled.");
    }

    private void ambientTrail(VibeContext ctx) {
        colorTick++;
        Color[] palette = RainbowItems.palette();
        Color color = palette[colorTick % palette.length];
        for (Player player : ctx.server().getOnlinePlayers()) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            ItemStack main = player.getInventory().getItemInMainHand();
            ItemStack off = player.getInventory().getItemInOffHand();
            boolean holding = RainbowItems.isRainbowSword(ctx.plugin(), main)
                || RainbowItems.isRainbowSword(ctx.plugin(), off);
            if (!holding) {
                continue;
            }
            Location loc = player.getLocation().add(0, 1.0, 0);
            player.getWorld().spawnParticle(
                Particle.DUST, loc, 3, 0.25, 0.25, 0.25, 0, new Particle.DustOptions(color, 1.0f));
            if (colorTick % 8 == 0) {
                player.getWorld().spawnParticle(Particle.END_ROD, loc, 1, 0.1, 0.1, 0.1, 0.01);
            }
        }
    }
}
