package vibemod.zombiefireworks;

import com.gijsm.vibemine.api.VibeContext;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
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

    private static final Color[] PALETTE = new Color[] {
        Color.RED, Color.LIME, Color.AQUA, Color.FUCHSIA, Color.YELLOW, Color.ORANGE, Color.BLUE, Color.WHITE, Color.PURPLE
    };

    private static final Color[] RAINBOW = new Color[] {
        Color.fromRGB(255, 0, 0),
        Color.fromRGB(255, 140, 0),
        Color.fromRGB(255, 255, 0),
        Color.fromRGB(0, 200, 0),
        Color.fromRGB(0, 150, 255),
        Color.fromRGB(75, 0, 200),
        Color.fromRGB(200, 0, 200)
    };

    public ZombieDeathListener(VibeContext ctx) {
        this.ctx = ctx;
    }

    @EventHandler
    public void onZombieDeath(EntityDeathEvent event) {
        if (!ctx.configBool("enabled")) {
            return;
        }
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

        double chance = ctx.configDouble("spawn-chance");
        if (random.nextDouble() > chance) {
            return;
        }

        Object spawned = world.spawnEntity(loc, EntityType.FIREWORK_ROCKET);
        if (!(spawned instanceof Firework)) {
            return;
        }
        Firework firework = (Firework) spawned;
        FireworkMeta meta = firework.getFireworkMeta();

        long effectCount = ctx.configInt("effects-per-firework");
        if (effectCount < 1) {
            effectCount = 1;
        }
        if (effectCount > 4) {
            effectCount = 4;
        }

        String colorMode = ctx.configString("color-mode");
        Color monoColor = PALETTE[random.nextInt(PALETTE.length)];

        FireworkEffect.Type[] types = FireworkEffect.Type.values();
        double flickerChance = ctx.configDouble("flicker-chance");
        double trailChance = ctx.configDouble("trail-chance");

        for (int i = 0; i < effectCount; i++) {
            Color primary;
            Color fade;
            if ("monochrome".equals(colorMode)) {
                primary = monoColor;
                fade = PALETTE[random.nextInt(PALETTE.length)];
            } else if ("rainbow".equals(colorMode)) {
                int idx = (int) (((double) i / Math.max(1, effectCount)) * RAINBOW.length);
                primary = RAINBOW[idx % RAINBOW.length];
                fade = RAINBOW[(idx + 1) % RAINBOW.length];
            } else {
                primary = PALETTE[random.nextInt(PALETTE.length)];
                fade = PALETTE[random.nextInt(PALETTE.length)];
            }

            FireworkEffect.Type type = types[random.nextInt(types.length)];
            FireworkEffect effect = FireworkEffect.builder()
                .withColor(primary)
                .withFade(fade)
                .with(type)
                .flicker(random.nextDouble() < flickerChance)
                .trail(random.nextDouble() < trailChance)
                .build();
            meta.addEffect(effect);
        }

        long power = ctx.configInt("firework-power");
        if (power < 0) {
            power = 0;
        }
        if (power > 3) {
            power = 3;
        }
        meta.setPower((int) power);
        firework.setFireworkMeta(meta);

        float volume = (float) ctx.configDouble("sound-volume");
        if (volume < 0f) {
            volume = 0f;
        }
        world.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, volume, 1.0f);

        long delay = ctx.configInt("launch-delay-ticks");
        if (delay < 0) {
            delay = 0;
        }

        final Color burstColor = monoColor;
        ctx.later(delay, () -> {
            if (firework.isValid()) {
                Location detLoc = firework.getLocation();
                World detWorld = firework.getWorld();
                if (detWorld != null && detLoc != null) {
                    detWorld.spawnParticle(Particle.DUST, detLoc, 20, 0.4, 0.4, 0.4,
                        new Particle.DustOptions(burstColor, 1.5f));
                }
                firework.detonate();
            }
        });
    }
}
