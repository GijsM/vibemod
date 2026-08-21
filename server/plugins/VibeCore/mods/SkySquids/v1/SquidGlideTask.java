package vibemod.skysquids;

import com.gijsm.vibemine.api.VibeContext;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Squid;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.List;
import java.util.Random;

public final class SquidGlideTask implements Runnable {

    private final VibeContext ctx;
    private final Random random = new Random();

    public SquidGlideTask(VibeContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void run() {
        List<World> worlds = ctx.server().getWorlds();
        if (worlds == null) {
            return;
        }
        for (World world : worlds) {
            if (world == null) {
                continue;
            }
            Collection<Squid> squids = world.getEntitiesByClass(Squid.class);
            if (squids == null) {
                continue;
            }
            for (Squid squid : squids) {
                if (squid == null || squid.isDead()) {
                    continue;
                }
                handleSquid(squid);
            }
        }
    }

    private void handleSquid(Squid squid) {
        squid.setFallDistance(0f);
        Location loc = squid.getLocation();
        if (loc == null) {
            return;
        }
        boolean inWater = squid.isInWater();
        if (inWater) {
            return;
        }

        Vector current = squid.getVelocity();
        double vx = current.getX();
        double vy = current.getY();
        double vz = current.getZ();

        vy += (random.nextDouble() - 0.35) * 0.05;
        vy = Math.max(-0.08, Math.min(0.12, vy));

        vx += (random.nextDouble() - 0.5) * 0.04;
        vz += (random.nextDouble() - 0.5) * 0.04;
        vx = Math.max(-0.25, Math.min(0.25, vx));
        vz = Math.max(-0.25, Math.min(0.25, vz));

        squid.setVelocity(new Vector(vx, vy, vz));

        if (random.nextInt(8) == 0) {
            World world = squid.getWorld();
            if (world != null) {
                world.spawnParticle(Particle.BUBBLE_POP, loc.clone().add(0, 0.5, 0), 4, 0.2, 0.2, 0.2, 0.01);
                world.playSound(loc, Sound.ENTITY_SQUID_SQUIRT, 0.5f, 1.2f);
            }
        }
    }
}
