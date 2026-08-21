package vibemod.oinkspawner;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;

public final class OinkSpawner implements VibeMod {

    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        ctx.command("oink", "Spawns a pig at 0 -58 0 in the overworld", (sender, args) -> spawnPig(ctx, sender));
        ctx.log().info("OinkSpawner enabled.");
    }

    private void spawnPig(VibeContext ctx, CommandSender sender) {
        World world = ctx.server().getWorld("world");
        if (world == null) {
            for (World w : ctx.server().getWorlds()) {
                if (w != null && w.getEnvironment() == World.Environment.NORMAL) {
                    world = w;
                    break;
                }
            }
        }
        if (world == null) {
            sender.sendMessage("Could not find the overworld.");
            return;
        }
        Location loc = new Location(world, 0.5, -58, 0.5);
        world.spawnEntity(loc, EntityType.PIG);
        world.spawnParticle(Particle.CLOUD, loc, 15, 0.3, 0.3, 0.3, 0.02);
        world.playSound(loc, Sound.ENTITY_PIG_AMBIENT, 1.0f, 1.0f);
        sender.sendMessage("Oink! A pig has appeared at 0 -58 0.");
    }
}
