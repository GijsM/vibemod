package vibemod.rainbowsword;

import com.gijsm.vibemine.api.VibeContext;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.UUID;

public final class RainbowSwordListener implements Listener {

    private final VibeContext ctx;
    private final HashMap<UUID, Long> cooldowns = new HashMap<>();
    private static final long COOLDOWN_MS = 8000L;

    public RainbowSwordListener(VibeContext ctx) {
        this.ctx = ctx;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!RainbowItems.isRainbowSword(ctx.plugin(), item)) {
            return;
        }
        if (!event.getAction().name().contains("RIGHT_CLICK")) {
            return;
        }

        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(id);
        if (last != null && now - last < COOLDOWN_MS) {
            long remain = (COOLDOWN_MS - (now - last)) / 1000L + 1;
            player.sendMessage(ChatColor.GRAY + "Rainbow Nova recharging... " + remain + "s");
            return;
        }
        cooldowns.put(id, now);
        event.setCancelled(true);
        nova(player);
    }

    private void nova(Player player) {
        World world = player.getWorld();
        Location center = player.getLocation().add(0, 1.0, 0);
        Color[] palette = RainbowItems.palette();
        world.playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.6f);
        world.playSound(center, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.4f);

        int rings = 3;
        for (int r = 0; r < rings; r++) {
            final int ringIndex = r;
            ctx.later(r * 4L, () -> {
                if (!player.isOnline()) {
                    return;
                }
                double radius = 1.5 + ringIndex * 1.5;
                int points = 36;
                for (int i = 0; i < points; i++) {
                    double angle = (2 * Math.PI * i) / points;
                    double x = Math.cos(angle) * radius;
                    double z = Math.sin(angle) * radius;
                    Location p = center.clone().add(x, 0.2, z);
                    Color color = palette[(i + ringIndex * 4) % palette.length];
                    world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, new Particle.DustOptions(color, 1.4f));
                }
                world.spawnParticle(Particle.TOTEM_OF_UNDYING, center, 20, 0.5, 0.5, 0.5, 0.3);
            });
        }

        for (LivingEntity entity : world.getLivingEntities()) {
            if (entity == null || entity.equals(player) || entity.isDead()) {
                continue;
            }
            if (entity.getLocation().distanceSquared(center) > 6 * 6) {
                continue;
            }
            Vector push = entity.getLocation().toVector().subtract(center.toVector());
            if (push.lengthSquared() < 0.0001) {
                push = new Vector(0, 0.4, 0);
            } else {
                push = push.normalize().setY(0.4);
            }
            entity.setVelocity(push);
            entity.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 100, 0, false, true, true));
        }
    }

    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getDamager();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!RainbowItems.isRainbowSword(ctx.plugin(), item)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity)) {
            return;
        }
        LivingEntity victim = (LivingEntity) event.getEntity();
        World world = victim.getWorld();
        Location loc = victim.getLocation().add(0, 1.0, 0);
        Color[] palette = RainbowItems.palette();
        for (int i = 0; i < palette.length; i++) {
            Location p = loc.clone().add((Math.random() - 0.5), Math.random() * 0.6, (Math.random() - 0.5));
            world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, new Particle.DustOptions(palette[i], 1.2f));
        }
        world.spawnParticle(Particle.END_ROD, loc, 8, 0.3, 0.3, 0.3, 0.05);
        world.playSound(loc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.5f);

        PotionEffectType[] effects = { PotionEffectType.SLOWNESS, PotionEffectType.WEAKNESS, PotionEffectType.NAUSEA };
        PotionEffectType chosen = effects[(int) (Math.random() * effects.length)];
        victim.addPotionEffect(new PotionEffect(chosen, 60, 0, false, true, true));
    }
}
