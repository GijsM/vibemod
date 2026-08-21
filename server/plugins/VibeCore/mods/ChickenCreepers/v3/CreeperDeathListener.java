package vibemod.chickencreepers;

import com.gijsm.vibemine.api.VibeContext;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

public final class CreeperDeathListener implements Listener {

    private final VibeContext ctx;

    public CreeperDeathListener(VibeContext ctx) {
        this.ctx = ctx;
    }

    @EventHandler
    public void onCreeperDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity == null || entity.getType() != EntityType.CREEPER) {
            return;
        }
        World world = entity.getWorld();
        if (world == null) {
            return;
        }
        Location loc = entity.getLocation();
        if (loc == null) {
            return;
        }

        long chickenCount = ctx.configInt("chicken-count");
        if (chickenCount < 1) {
            chickenCount = 1;
        }
        for (long i = 0; i < chickenCount; i++) {
            world.spawnEntity(loc, EntityType.CHICKEN);
        }

        long particleCount = ctx.configInt("particle-count");
        if (particleCount < 0) {
            particleCount = 0;
        }
        if (particleCount > 0) {
            world.spawnParticle(Particle.CLOUD, loc, (int) particleCount, 0.4, 0.4, 0.4, 0.03);
        }

        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f);
        world.playSound(loc, Sound.ENTITY_CHICKEN_AMBIENT, 1.0f, 1.1f);
    }
}
