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
        world.spawnEntity(loc, EntityType.CHICKEN);
        world.spawnParticle(Particle.POOF, loc, 12, 0.3, 0.3, 0.3, 0.01);
        world.playSound(loc, Sound.ENTITY_CHICKEN_AMBIENT, 1.0f, 1.2f);
    }
}
