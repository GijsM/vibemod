package com.gijsm.vibemine.ui;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

/**
 * Actions the mod browser GUI cannot perform on its own and instead delegates
 * back to the wiring layer: exporting a jar, reapplying a rolled-back
 * version, handing out books, reloading {@code config.yml}, and reading/
 * writing the active LLM model.
 */
public record GuiCallbacks(BiConsumer<Player, String> export,
                           BiConsumer<Player, String> applyVersion,
                           BiConsumer<Player, String> giveConfigBook,
                           BiConsumer<Player, String> giveManualBook,
                           BiConsumer<Player, String> giveSourceBook,
                           Runnable reloadConfig,
                           Supplier<String> getModel, Consumer<String> setModel) {
}
