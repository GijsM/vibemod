package vibemod.spellbooks;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public final class Spells {

    private Spells() {
    }

    public static ItemStack createBook(Plugin plugin, String id) {
        switch (id) {
            case "fireball":
                return SpellBookItem.create(plugin, id, "Book of Fireball",
                        "Hurls a blazing fireball", "Right-click to cast");
            case "heal":
                return SpellBookItem.create(plugin, id, "Book of Mending",
                        "Restores your health", "Right-click to cast");
            case "blink":
                return SpellBookItem.create(plugin, id, "Book of Blinking",
                        "Teleports you forward", "Right-click to cast");
            case "lightning":
                return SpellBookItem.create(plugin, id, "Book of the Storm",
                        "Calls down lightning", "Right-click to cast");
            case "frost":
                return SpellBookItem.create(plugin, id, "Book of Frost",
                        "Freezes nearby foes", "Right-click to cast");
            default:
                return null;
        }
    }
}
