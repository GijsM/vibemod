package vibemod.coolboss;

import com.gijsm.vibemine.api.VibeContext;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class BossManager implements Listener {

    private static final int SLAM_COOLDOWN_TICKS = 6;

    private final VibeContext ctx;
    private final Map<UUID, Integer> bosses = new HashMap<>();

    public BossManager(VibeContext ctx) {
        this.ctx = ctx;
    }

    public void spawnBoss(Player player) {
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        Location loc = player.getLocation();
        Entity spawned = world.spawnEntity(loc, EntityType.WITHER_SKELETON);
        if (!(spawned instanceof LivingEntity boss)) {
            return;
        }
        boss.setCustomName("\u00a75\u00a7lStormlord");
        boss.setCustomNameVisible(true);
        boss.setFireTicks(0);

        AttributeInstance healthAttr = boss.getAttribute(Attribute.MAX_HEALTH);
        if (healthAttr != null) {
            healthAttr.setBaseValue(250.0);
            boss.setHealth(250.0);
        }
        AttributeInstance dmgAttr = boss.getAttribute(Attribute.ATTACK_DAMAGE);
        if (dmgAttr != null) {
            dmgAttr.setBaseValue(10.0);
        }
        AttributeInstance scaleAttr = boss.getAttribute(Attribute.SCALE);
        if (scaleAttr != null) {
            scaleAttr.setBaseValue(1.5);
        }

        bosses.put(boss.getUniqueId(), SLAM_COOLDOWN_TICKS);
        world.spawnParticle(Particle.EXPLOSION, loc, 3, 1.0, 1.0, 1.0);
        world.playSound(loc, Sound.ENTITY_WITHER_SPAWN, 1.0f, 1.0f);
        player.sendMessage("\u00a75The Stormlord has awakened!");
    }

    public void tick() {
        Iterator<Map.Entry<UUID, Integer>> it = bosses.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            LivingEntity boss = findBoss(entry.getKey());
            if (boss == null || boss.isDead() || !boss.isValid()) {
                it.remove();
                continue;
            }
            int cooldown = entry.getValue() - 1;
            if (cooldown <= 0) {
                performSlam(boss);
                cooldown = SLAM_COOLDOWN_TICKS;
            }
            entry.setValue(cooldown);
        }
    }

    public void clear() {
        bosses.clear();
    }

    private LivingEntity findBoss(UUID id) {
        for (World world : ctx.server().getWorlds()) {
            if (world == null) {
                continue;
            }
            Entity entity = world.getEntity(id);
            if (entity instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    private Player findNearestPlayer(LivingEntity boss, double radius) {
        World world = boss.getWorld();
        if (world == null) {
            return null;
        }
        Player nearest = null;
        double best = radius * radius;
        for (Player p : world.getPlayers()) {
            if (p == null || !p.isOnline() || p.isDead()) {
                continue;
            }
            double d = p.getLocation().distanceSquared(boss.getLocation());
            if (d <= best) {
                best = d;
                nearest = p;
            }
        }
        return nearest;
    }

    private void performSlam(LivingEntity boss) {
        World world = boss.getWorld();
        if (world == null) {
            return;
        }
        Player target = findNearestPlayer(boss, 20.0);
        if (target == null) {
            return;
        }
        Location targetLoc = target.getLocation();
        Location teleportSpot = targetLoc.clone().add(
                (Math.random() - 0.5) * 4.0, 0.0, (Math.random() - 0.5) * 4.0);
        boss.teleport(teleportSpot);

        world.spawnParticle(Particle.EXPLOSION, teleportSpot, 4, 1.0, 0.5, 1.0);
        world.spawnParticle(Particle.CLOUD, teleportSpot, 30, 2.0, 1.0, 2.0, 0.05);
        world.playSound(teleportSpot, Sound.ENTITY_WITHER_SHOOT, 1.5f, 0.7f);

        for (Entity nearby : world.getNearbyEntities(teleportSpot, 4.0, 3.0, 4.0)) {
            if (nearby instanceof Player p && p.isOnline() && !p.isDead()) {
                p.damage(6.0, boss);
                Vector dir = p.getLocation().toVector().subtract(teleportSpot.toVector());
                if (dir.lengthSquared() < 0.01) {
                    dir = new Vector(0.0, 0.5, 0.0);
                }
                dir = dir.normalize().multiply(1.4);
                dir.setY(0.6);
                p.setVelocity(dir);
                p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);
            }
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity == null || !bosses.containsKey(entity.getUniqueId())) {
            return;
        }
        bosses.remove(entity.getUniqueId());
        World world = entity.getWorld();
        if (world != null) {
            Location loc = entity.getLocation();
            world.spawnParticle(Particle.TOTEM_OF_UNDYING, loc, 40, 1.0, 1.0, 1.0, 0.3);
            world.playSound(loc, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            world.dropItemNaturally(loc, new ItemStack(Material.NETHER_STAR, 1));
        }
        Player killer = entity.getKiller();
        if (killer != null && killer.isOnline()) {
            killer.sendMessage("\u00a7dYou have slain the Stormlord!");
        }
    }
}
