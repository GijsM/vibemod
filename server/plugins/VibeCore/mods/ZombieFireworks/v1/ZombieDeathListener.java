package vibemod.zombiefireworks;

import com.gijsm.vibemine.api.VibeContext;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.meta.FireworkMeta;

import java.util.Random;

public final class ZombieDeathListener implements Listener {

    private final VibeContext ctx;
    private final Random random = new Random();

    private static final Color[] COLORS = new Color[] {
        Color.RED, Color.LIME, Color.AQUA, Color.FUCHSIA, Color.YELLOW, Color.ORANGE, Color.BLUE
    };

    public ZombieDeathListener(VibeContext ctx) {
        this.ctx = ctx;
    }

    @EventHandler
    public void onZombieDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity == null || entity.getType() != EntityType.ZOMBIE) {
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

        Object spawned = world.spawnEntity(loc, EntityType.FIREWORK_ROCKET);
        if (!(spawned instanceof Firework)) {
            return;
        }
        Firework firework = (Firework) spawned;
        FireworkMeta meta = firework.getFireworkMeta();

        Color primary = COLORS[random.nextInt(COLORS.length)];
        Color fade = COLORS[random.nextInt(COLORS.length)];

        FireworkEffect.Type[] types = FireworkEffect.Type.values();
        FireworkEffect.Type type = types[random.nextInt(types.length)];

        FireworkEffect effect = FireworkEffect.builder()
            .withColor(primary)
            .withFade(fade)
            .with(type)
            .flicker(random.nextBoolean())
            .trail(random.nextBoolean())
            .build();

        meta.addEffect(effect);
        meta.setPower(0);
        firework.setFireworkMeta(meta);

        world.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 1.0f);

        ctx.later(2L, () -> {
            if (firework.isValid()) {
                firework.detonate();
            }
        });
    }
}
