package vibemod.spellbooks;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;

public final class SpellBookItem {

    private static final String KEY_NAME = "vibe-spell";

    private SpellBookItem() {
    }

    public static ItemStack create(Plugin plugin, String spellId, String displayName, String... lore) {
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + displayName);
            meta.setLore(Arrays.asList(lore));
            NamespacedKey key = new NamespacedKey(plugin, KEY_NAME);
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, spellId);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static String getSpellId(Plugin plugin, ItemStack item) {
        if (item == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        NamespacedKey key = new NamespacedKey(plugin, KEY_NAME);
        return meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }
}
