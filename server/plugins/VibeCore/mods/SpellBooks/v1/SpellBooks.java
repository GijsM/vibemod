package vibemod.spellbooks;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class SpellBooks implements VibeMod {

    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        ctx.listen(new SpellCastListener(ctx));
        ctx.command("spellbook", "Get a magic spell book (fireball, heal, blink, lightning, frost)",
                (sender, args) -> giveBook(ctx, sender, args));
        ctx.log().info("SpellBooks enabled.");
    }

    private void giveBook(VibeContext ctx, org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can receive spell books.");
            return;
        }
        String spellId = args.length > 0 ? args[0].toLowerCase() : "fireball";
        ItemStack book = Spells.createBook(ctx.plugin(), spellId);
        if (book == null) {
            player.sendMessage("Unknown spell. Try: fireball, heal, blink, lightning, frost");
            return;
        }
        player.getInventory().addItem(book);
        player.sendMessage("You received a spell book! Right-click to cast.");
    }
}
