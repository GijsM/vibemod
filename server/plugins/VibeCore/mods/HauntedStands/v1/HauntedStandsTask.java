package vibemod.hauntedstands;

import com.gijsm.vibemine.api.VibeContext;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.List;
import java.util.Random;

/** Ticks frequently; only actually moves stands once move-period-ticks have elapsed. */
public final class HauntedStandsTask {

    private static final long BASE_PERIOD_TICKS = 10L;

    private final VibeContext ctx;
    private final Random random = new Random();
    private long ticksSinceLastPulse = 0L;

    public HauntedStandsTask(VibeContext ctx) {
        this.ctx = ctx;
    }

    public void tick() {
        ticksSinceLastPulse += BASE_PERIOD_TICKS;
        long movePeriod = ctx.configInt("move-period-ticks");
        if (movePeriod < 1) {
            movePeriod = 1;
        }
        if (ticksSinceLastPulse < movePeriod) {
            return;
        }
        ticksSinceLastPulse = 0L;
        pulse();
    }

    private void pulse() {
        boolean nightOnly = ctx.configBool("night-only");
        double moveDistance = ctx.configDouble("move-distance");
        double detectionRange = ctx.configDouble("detection-range");
        double watchAngle = ctx.configDouble("watch-angle");
        double stopDistance = ctx.configDouble("stop-distance");
        double soundChance = ctx.configDouble("sound-chance");

        if (moveDistance <= 0) {
            moveDistance = 0.1;
        }
        if (detectionRange <= 0) {
            detectionRange = 16;
        }
        if (watchAngle <= 0) {
            watchAngle = 15;
        }
        if (stopDistance < 0) {
            stopDistance = 0;
        }

        List<World> worlds = ctx.server().getWorlds();
        if (worlds == null) {
            return;
        }

        for (World world : worlds) {
            if (world == null) {
                continue;
            }
            if (nightOnly && !isNight(world)) {
                continue;
            }

            List<Player> players = world.getPlayers();
            if (players == null || players.isEmpty()) {
                continue;
            }

            Collection<ArmorStand> stands = world.getEntitiesByClass(ArmorStand.class);
            if (stands == null || stands.isEmpty()) {
                continue;
            }

            for (ArmorStand stand : stands) {
                if (stand == null || !stand.isValid()) {
                    continue;
                }
                Player nearest = findNearestPlayer(stand, players, detectionRange);
                if (nearest == null) {
                    continue;
                }
                if (isBeingWatched(stand, players, watchAngle)) {
                    continue;
                }

                double distance = stand.getLocation().distance(nearest.getLocation());
                if (distance <= stopDistance) {
                    continue;
                }

                moveTowards(stand, nearest, moveDistance);

                if (random.nextDouble() < soundChance) {
                    Location loc = stand.getLocation();
                    world.spawnParticle(Particle.SOUL, loc.clone().add(0, 1, 0), 6, 0.2, 0.3, 0.2, 0.02);
                    world.playSound(loc, Sound.ENTITY_ARMOR_STAND_HIT, 0.5f, 0.6f);
                }
            }
        }
    }

    private boolean isNight(World world) {
        long time = world.getTime();
        return time >= 13000L && time <= 23000L;
    }

    private Player findNearestPlayer(ArmorStand stand, List<Player> players, double range) {
        Player nearest = null;
        double nearestDistSq = range * range;
        for (Player player : players) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            double distSq = player.getLocation().distanceSquared(stand.getLocation());
            if (distSq <= nearestDistSq) {
                nearestDistSq = distSq;
                nearest = player;
            }
        }
        return nearest;
    }

    private boolean isBeingWatched(ArmorStand stand, List<Player> players, double watchAngleDegrees) {
        Location eyeTarget = stand.getLocation().clone().add(0, 1.0, 0);
        for (Player player : players) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            Location eye = player.getEyeLocation();
            Vector toStand = eyeTarget.toVector().subtract(eye.toVector());
            if (toStand.lengthSquared() < 0.0001) {
                continue;
            }
            if (toStand.length() > 40) {
                continue;
            }
            Vector direction = player.getLocation().getDirection();
            double angle = Math.toDegrees(direction.angle(toStand));
            if (angle <= watchAngleDegrees && player.hasLineOfSight(stand)) {
                return true;
            }
        }
        return false;
    }

    private void moveTowards(ArmorStand stand, Player target, double moveDistance) {
        Location standLoc = stand.getLocation();
        Vector direction = target.getLocation().toVector().subtract(standLoc.toVector());
        direction.setY(0);
        if (direction.lengthSquared() < 0.0001) {
            return;
        }
        direction.normalize().multiply(moveDistance);

        Location newLoc = standLoc.clone().add(direction);
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
        newLoc.setYaw(yaw);
        newLoc.setPitch(standLoc.getPitch());
        stand.teleport(newLoc);
    }
}
