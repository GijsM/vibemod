package vibemod.poopydiamonds;

import com.gijsm.vibemine.api.VibeContext;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class PoopDropListener implements Listener {

    private final VibeContext ctx;
    private final Random random = new Random();

    public PoopDropListener(VibeContext ctx) {
        this.ctx = ctx;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block == null) {
            return;
        }
        Material type = block.getType();
        if (type != Material.DIAMOND_ORE && type != Material.DEEPSLATE_DIAMOND_ORE && type != Material.DIAMOND_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        World world = block.getWorld();
        if (world == null) {
            return;
        }

        event.setDropItems(false);

        Location loc = block.getLocation().add(0.5, 0.5, 0.5);

        int amount = 1;
        if (type == Material.DIAMOND_BLOCK) {
            amount = 1;
        } else {
            amount = 1 + random.nextInt(1);
        }

        for (int i = 0; i < amount; i++) {
            world.dropItemNaturally(loc, makePoopItem());
        }

        world.spawnParticle(Particle.ITEM_SLIME, loc, 15, 0.3, 0.3, 0.3, 0.05);
        world.playSound(loc, Sound.ENTITY_VILLAGER_NO, 1.0f, 0.6f);
    }

    private ItemStack makePoopItem() {
        ItemStack item = new ItemStack(Material.BROWN_MUSHROOM, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("\u00a76Poop");
            List<String> lore = new ArrayList<>();
            lore.add("\u00a78It's... not a diamond.");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}
