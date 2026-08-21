package vibemod.rainbowsword;

import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;

public final class RainbowItems {

    public static final String KEY_NAME = "rainbow_sword";

    private RainbowItems() {
    }

    public static ItemStack create(Plugin plugin) {
        ItemStack item = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(
                ChatColor.RED + "R" + ChatColor.GOLD + "a" + ChatColor.YELLOW + "i" +
                ChatColor.GREEN + "n" + ChatColor.AQUA + "b" + ChatColor.BLUE + "o" +
                ChatColor.LIGHT_PURPLE + "w " + ChatColor.WHITE + "Sword"
            );
            meta.setLore(Arrays.asList(
                ChatColor.GRAY + "A blade woven from every color",
                ChatColor.GRAY + "of the spectrum.",
                "",
                ChatColor.YELLOW + "Right-click: unleash a Rainbow Nova!"
            ));
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, KEY_NAME), PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static boolean isRainbowSword(Plugin plugin, ItemStack item) {
        if (item == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        return meta.getPersistentDataContainer().has(
            new NamespacedKey(plugin, KEY_NAME), PersistentDataType.BYTE);
    }

    public static Color[] palette() {
        return new Color[] {
            Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME,
            Color.AQUA, Color.BLUE, Color.PURPLE, Color.FUCHSIA
        };
    }
}
