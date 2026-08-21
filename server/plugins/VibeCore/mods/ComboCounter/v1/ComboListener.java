package vibemod.combocounter;

import com.gijsm.vibemine.api.VibeContext;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/** Handles combo build-up on mob hits and combo reset when a player takes damage. */
public final class ComboListener implements Listener {

    private final VibeContext ctx;
    private final ComboState state;

    public ComboListener(VibeContext ctx, ComboState state) {
        this.ctx = ctx;
        this.state = state;
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Entity victim = event.getEntity();
        if (victim instanceof Player victimPlayer) {
            resetCombo(victimPlayer);
            return;
        }
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (!(victim instanceof LivingEntity)) {
            return;
        }
        if (event.getFinalDamage() <= 0.0 && event.getDamage() <= 0.0) {
            return;
        }
        int maxCombo = (int) ctx.configInt("max-combo");
        if (maxCombo < 1) {
            maxCombo = 1;
        }
        int combo = state.increment(attacker.getUniqueId(), maxCombo);
        double bonusPer = ctx.configDouble("damage-bonus-per-combo");
        double bonusDamage = bonusPer * (combo - 1);
        if (bonusDamage > 0.0) {
            event.setDamage(event.getDamage() + bonusDamage);
        }
        if (ctx.configBool("show-actionbar")) {
            attacker.sendActionBar("\u00a76Combo: \u00a7e" + combo + "x \u00a77(+"
                    + String.format("%.1f", bonusDamage) + " dmg)");
        }
        attacker.getWorld().spawnParticle(Particle.CRIT, attacker.getEyeLocation(), 8, 0.3, 0.3, 0.3, 0.05);
        float pitch = (float) Math.min(2.0, 1.0 + combo * 0.05);
        attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.6f, pitch);
    }

    @EventHandler
    public void onEnvironmentalDamage(EntityDamageEvent event) {
        if (event.getClass() != EntityDamageEvent.class) {
            return;
        }
        if (event.isCancelled()) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        resetCombo(player);
    }

    private void resetCombo(Player player) {
        int combo = state.getCombo(player.getUniqueId());
        if (combo <= 0) {
            return;
        }
        state.reset(player.getUniqueId());
        if (ctx.configBool("show-actionbar")) {
            player.sendActionBar("\u00a7cCombo reset!");
        }
    }
}
