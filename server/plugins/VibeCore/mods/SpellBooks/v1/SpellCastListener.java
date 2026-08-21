package vibemod.spellbooks;

import com.gijsm.vibemine.api.VibeContext;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SpellCastListener implements Listener {

    private final VibeContext ctx;
    private final Map<UUID, Long> lastCastMillis = new HashMap<>();

    public SpellCastListener(VibeContext ctx) {
        this.ctx = ctx;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null || !player.isOnline()) {
            return;
        }
        ItemStack item = event.getItem();
        String spellId = SpellBookItem.getSpellId(ctx.plugin(), item);
        if (spellId == null) {
            return;
        }
        event.setCancelled(true);

        long cooldownMillis = Math.max(1L, ctx.configInt("cooldown-seconds")) * 1000L;
        long now = System.currentTimeMillis();
        Long last = lastCastMillis.get(player.getUniqueId());
        if (last != null && now - last < cooldownMillis) {
            long remain = (cooldownMillis - (now - last)) / 1000L + 1;
            player.sendMessage("\u00A7dSpell recharging... " + remain + "s left.");
            return;
        }
        lastCastMillis.put(player.getUniqueId(), now);

        switch (spellId) {
            case "fireball":
                SpellEffects.castFireball(ctx, player);
                break;
            case "heal":
                SpellEffects.castHeal(ctx, player);
                break;
            case "blink":
                SpellEffects.castBlink(ctx, player);
                break;
            case "lightning":
                SpellEffects.castLightning(ctx, player);
                break;
            case "frost":
                SpellEffects.castFrost(ctx, player);
                break;
            default:
                break;
        }
    }
}
