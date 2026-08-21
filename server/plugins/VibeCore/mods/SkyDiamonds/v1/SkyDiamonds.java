package vibemod.skydiamonds;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;

public final class SkyDiamonds implements VibeMod {

    private static final long PERIOD_TICKS = 100L;
    private static final double TARGET_X = 0.0;
    private static final double TARGET_Y = -55.0;
    private static final double TARGET_Z = 0.0;
    private static final double DROP_HEIGHT = 20.0;

    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        ctx.repeat(PERIOD_TICKS, PERIOD_TICKS, () -> dropDiamond(ctx));
        ctx.log().info("SkyDiamonds enabled.");
    }

    private void dropDiamond(VibeContext ctx) {
        World world = firstWorld(ctx);
        if (world == null) {
            return;
        }
        Location spawnLoc = new Location(world, TARGET_X + 0.5, TARGET_Y + DROP_HEIGHT, TARGET_Z + 0.5);
        ItemStack stack = new ItemStack(Material.DIAMOND, 1);
        Item item = world.dropItem(spawnLoc, stack);
        if (item != null) {
            item.setVelocity(item.getVelocity().zero());
        }
        world.spawnParticle(Particle.FIREWORK, spawnLoc, 15, 0.2, 0.2, 0.2, 0.02);
        world.playSound(spawnLoc, Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.4f);
    }

    private World firstWorld(VibeContext ctx) {
        if (ctx.server() == null || ctx.server().getWorlds() == null || ctx.server().getWorlds().isEmpty()) {
            return null;
        }
        return ctx.server().getWorlds().get(0);
    }
}
