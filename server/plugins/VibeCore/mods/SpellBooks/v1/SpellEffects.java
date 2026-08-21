package vibemod.spellbooks;

import com.gijsm.vibemine.api.VibeContext;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Collection;

public final class SpellEffects {

    private SpellEffects() {
    }

    public static void castFireball(VibeContext ctx, Player player) {
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize().multiply(1.3);
        Entity spawned = world.spawnEntity(eye, EntityType.FIREBALL);
        if (spawned instanceof Fireball fireball) {
            fireball.setShooter(player);
            fireball.setDirection(dir);
            double power = ctx.configDouble("fireball-power");
            if (power < 0.1) {
                power = 0.1;
            }
            fireball.setYield((float) power);
            fireball.setIsIncendiary(true);
        }
        world.spawnParticle(Particle.FLAME, eye, 20, 0.2, 0.2, 0.2, 0.05);
        world.playSound(eye, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 1.0f);
    }

    public static void castHeal(VibeContext ctx, Player player) {
        double amount = ctx.configDouble("heal-amount");
        if (amount < 0) {
            amount = 0;
        }
        double max = 20.0;
        org.bukkit.attribute.AttributeInstance maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null) {
            max = maxHealthAttr.getValue();
        }
        double newHealth = Math.min(max, player.getHealth() + amount);
        player.setHealth(newHealth);
        World world = player.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.HEART, player.getLocation().add(0, 1.5, 0), 10, 0.4, 0.4, 0.4, 0.01);
            world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.6f);
        }
    }

    public static void castBlink(VibeContext ctx, Player player) {
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        double distance = ctx.configDouble("blink-distance");
        if (distance < 1) {
            distance = 1;
        }
        Location start = player.getLocation();
        Vector dir = start.getDirection().normalize();
        Location target = start.clone();
        double travelled = 0;
        double step = 0.5;
        while (travelled < distance) {
            Location next = target.clone().add(dir.clone().multiply(step));
            if (!next.getBlock().isPassable() || !next.clone().add(0, 1, 0).getBlock().isPassable()) {
                break;
            }
            target = next;
            travelled += step;
        }
        world.spawnParticle(Particle.PORTAL, start, 30, 0.3, 0.6, 0.3, 0.3);
        player.teleport(target);
        world.spawnParticle(Particle.PORTAL, target, 30, 0.3, 0.6, 0.3, 0.3);
        world.playSound(target, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
    }

    public static void castLightning(VibeContext ctx, Player player) {
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        Location target = player.getTargetBlock(null, 40) != null
                ? player.getTargetBlock(null, 40).getLocation()
                : player.getLocation();
        world.strikeLightning(target);
        world.spawnParticle(Particle.ELECTRIC_SPARK, target.clone().add(0, 1, 0), 25, 0.5, 1.0, 0.5, 0.2);
        world.playSound(target, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f);
    }

    public static void castFrost(VibeContext ctx, Player player) {
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        double radius = ctx.configDouble("frost-radius");
        if (radius < 1) {
            radius = 1;
        }
        long slowSeconds = ctx.configInt("frost-slow-seconds");
        if (slowSeconds < 1) {
            slowSeconds = 1;
        }
        Location center = player.getLocation();
        Collection<Entity> nearby = world.getNearbyEntities(center, radius, radius, radius);
        for (Entity entity : nearby) {
            if (entity instanceof LivingEntity living && !(living instanceof Player) && living != player) {
                living.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) (slowSeconds * 20L), 2, false, true, true));
                living.getWorld().spawnParticle(Particle.SNOWFLAKE, living.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.02);
            }
        }
        world.spawnParticle(Particle.SNOWFLAKE, center, 60, radius * 0.5, 0.5, radius * 0.5, 0.05);
        world.playSound(center, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.6f);
        world.playSound(center, Sound.ITEM_BOTTLE_EMPTY, 1.0f, 0.5f);
    }
}
