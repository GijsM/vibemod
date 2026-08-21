package vibemod.balastic;

import com.gijsm.vibemine.api.VibeContext;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Random;

/** Fires a random 3D line of explosions from the sky down onto a target location. */
public final class BallisticLauncher {

    private static final int TOTAL_STEPS = 24;
    private static final long STEP_PERIOD_TICKS = 2L;

    private final VibeContext ctx;
    private final Random random = new Random();

    public BallisticLauncher(VibeContext ctx) {
        this.ctx = ctx;
    }

    public void launch(Player target) {
        if (target == null || !target.isOnline()) {
            return;
        }
        launchInternal(target.getLocation().clone(), target.getWorld(), target);
    }

    public void launchAt(Location targetLoc, Player shooter) {
        if (targetLoc == null || targetLoc.getWorld() == null) {
            return;
        }
        launchInternal(targetLoc.clone(), targetLoc.getWorld(), shooter);
    }

    private void launchInternal(Location end, World world, Player notifyPlayer) {
        if (world == null) {
            return;
        }

        double angle = random.nextDouble() * Math.PI * 2.0;
        double radius = 6.0 + random.nextDouble() * 10.0;
        double height = 30.0 + random.nextDouble() * 20.0;
        double sway = -3.0 + random.nextDouble() * 6.0;

        Location start = end.clone().add(
                Math.cos(angle) * radius,
                height,
                Math.sin(angle) * radius + sway);

        double[] progress = {0.0};
        BukkitTask[] holder = new BukkitTask[1];

        holder[0] = ctx.repeat(0L, STEP_PERIOD_TICKS, () -> {
            World liveWorld = world;
            if (liveWorld == null) {
                if (holder[0] != null) {
                    holder[0].cancel();
                }
                return;
            }

            progress[0] += 1.0;
            double t = progress[0] / TOTAL_STEPS;
            if (t > 1.0) {
                t = 1.0;
            }

            double x = start.getX() + (end.getX() - start.getX()) * t;
            double y = start.getY() + (end.getY() - start.getY()) * t;
            double z = start.getZ() + (end.getZ() - start.getZ()) * t;
            Location cur = new Location(liveWorld, x, y, z);

            liveWorld.spawnParticle(Particle.FLAME, cur, 16, 0.25, 0.25, 0.25, 0.03);
            liveWorld.spawnParticle(Particle.SMOKE, cur, 10, 0.15, 0.15, 0.15, 0.02);
            liveWorld.playSound(cur, Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 1.8f);

            if (progress[0] >= TOTAL_STEPS) {
                liveWorld.createExplosion(cur.getX(), cur.getY(), cur.getZ(), 3.0f, false, true);
                liveWorld.spawnParticle(Particle.EXPLOSION, cur, 3, 0.2, 0.2, 0.2, 0.0);
                liveWorld.playSound(cur, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.8f);
                if (notifyPlayer != null && notifyPlayer.isOnline()) {
                    notifyPlayer.playSound(notifyPlayer.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.0f, 1.0f);
                }
                if (holder[0] != null) {
                    holder[0].cancel();
                }
            }
        });
    }
}
