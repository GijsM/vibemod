package vibemod.blockburst;

import com.gijsm.vibemine.api.VibeContext;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

public final class BlockBreakEffectListener implements Listener {

    private final VibeContext ctx;

    public BlockBreakEffectListener(VibeContext ctx) {
        this.ctx = ctx;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event == null || event.isCancelled()) {
            return;
        }
        Block block = event.getBlock();
        if (block == null) {
            return;
        }
        World world = block.getWorld();
        if (world == null) {
            return;
        }

        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        BlockData blockData = block.getBlockData();

        try {
            world.spawnParticle(Particle.BLOCK, center, 30, 0.4, 0.4, 0.4, 0.15, blockData);
        } catch (Exception ignored) {
            // Fallback if BLOCK particle data fails for some reason.
        }

        world.spawnParticle(Particle.CLOUD, center, 14, 0.3, 0.3, 0.3, 0.03);
        world.spawnParticle(Particle.CRIT, center, 10, 0.35, 0.35, 0.35, 0.05);

        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.6f);
        world.playSound(center, Sound.BLOCK_STONE_BREAK, 0.8f, 1.0f);
    }
}
