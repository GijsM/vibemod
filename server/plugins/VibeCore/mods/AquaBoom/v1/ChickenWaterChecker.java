package vibemod.aquaboom;

import com.gijsm.vibemine.api.VibeContext;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.List;

public final class ChickenWaterChecker {

    private final VibeContext ctx;

    public ChickenWaterChecker(VibeContext ctx) {
        this.ctx = ctx;
    }

    public void scan() {
        List<World> worlds = ctx.server().getWorlds();
        if (worlds == null) {
            return;
        }
        for (World world : worlds) {
            if (world == null) {
                continue;
            }
            List<Entity> chickens = new ArrayList<>();
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Chicken && entity.isValid()) {
                    chickens.add(entity);
                }
            }
            for (Entity entity : chickens) {
                if (isTouchingWater(entity)) {
                    explode(world, entity);
                }
            }
        }
    }

    private boolean isTouchingWater(Entity entity) {
        if (entity.isInWater()) {
            return true;
        }
        Block block = entity.getLocation().getBlock();
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        return type == Material.WATER || type == Material.WATER_CAULDRON;
    }

    private void explode(World world, Entity chicken) {
        Location loc = chicken.getLocation();
        chicken.remove();
        world.createExplosion(loc, 1.5f, false, false);
        world.spawnParticle(Particle.EXPLOSION, loc, 1, 0.1, 0.1, 0.1, 0.0);
        world.spawnParticle(Particle.CLOUD, loc, 15, 0.4, 0.4, 0.4, 0.02);
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.1f);
    }
}
