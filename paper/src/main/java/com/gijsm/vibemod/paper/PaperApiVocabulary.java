package com.gijsm.vibemod.paper;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.Bukkit;

import com.gijsm.vibemod.llm.ReflectiveVocabulary;
import com.gijsm.vibemod.platform.ApiVocabulary;

/**
 * The Bukkit half of the runtime vocabulary: which types to look at, and where
 * they live.
 *
 * <p>The reflection engine is {@link ReflectiveVocabulary} in {@code core},
 * which must never depend on {@code paper-api} (ARCHITECTURE-V2 §1) and so names
 * no Bukkit package. Everything Bukkit-shaped about the measurement is the map
 * below, and it lives here, in the module that already imports
 * {@code org.bukkit}. A loader host wanting the same treatment supplies its own
 * map to the same engine.
 *
 * <h2>Choosing the list</h2>
 *
 * <p>These are the types the prompt, the rule table and the coming repair pass
 * ask questions about, plus the two whose absence is itself evidence
 * ({@code Dialog}). Adding a type here is cheap and safe; a type that is
 * <em>missing</em> from the map answers {@code UNKNOWN} rather than "absent"
 * (see {@link ReflectiveVocabulary}), so an incomplete map costs an opportunity,
 * never a wrong answer.
 */
final class PaperApiVocabulary {

    private PaperApiVocabulary() {
    }

    /** Simple name (the {@link ApiVocabulary} key) to fully qualified name. */
    private static final Map<String, String> TYPES = new LinkedHashMap<>();

    static {
        // The vocabulary types: what a generated mod names constants from.
        TYPES.put("Attribute", "org.bukkit.attribute.Attribute");
        TYPES.put("AttributeInstance", "org.bukkit.attribute.AttributeInstance");
        TYPES.put("AttributeModifier", "org.bukkit.attribute.AttributeModifier");
        TYPES.put("Enchantment", "org.bukkit.enchantments.Enchantment");
        TYPES.put("PotionEffectType", "org.bukkit.potion.PotionEffectType");
        TYPES.put("Particle", "org.bukkit.Particle");
        TYPES.put("Sound", "org.bukkit.Sound");
        TYPES.put("Material", "org.bukkit.Material");
        TYPES.put("EntityType", "org.bukkit.entity.EntityType");
        // The method-surface types the capability rules probe.
        TYPES.put("ItemMeta", "org.bukkit.inventory.meta.ItemMeta");
        TYPES.put("ItemFlag", "org.bukkit.inventory.ItemFlag");
        TYPES.put("Registry", "org.bukkit.Registry");
        TYPES.put("NamespacedKey", "org.bukkit.NamespacedKey");
        // Absent below 1.21.7, and its absence is a real measurement.
        TYPES.put("Dialog", "io.papermc.paper.dialog.Dialog");
    }

    /**
     * Measures this server once. Uses the classloader that loaded
     * {@code org.bukkit.Bukkit} rather than our own, so the answer describes the
     * server's API and not the copy of {@code paper-api} VibeMod was built
     * against.
     */
    static ApiVocabulary measure() {
        try {
            ClassLoader loader = Bukkit.class.getClassLoader();
            return ReflectiveVocabulary.of(loader, TYPES, "paper");
        } catch (Throwable t) {
            // A vocabulary is an optimisation, never a requirement. If the
            // measurement itself fails, UNKNOWN-for-everything is the correct
            // answer and the prompt falls back to its version-independent text.
            return ApiVocabulary.empty();
        }
    }
}
