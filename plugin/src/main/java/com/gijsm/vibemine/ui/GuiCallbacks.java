package com.gijsm.vibemine.ui;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

/**
 * Actions the mod browser GUI cannot perform on its own and instead delegates
 * back to the wiring layer: exporting a jar, reapplying a rolled-back
 * version (the per-mod [⟟ reload] button), opening the configure/edit/fix
 * dialogs, opening the manual/source/errors viewer dialogs, reloading
 * {@code config.yml}, reading/writing the active LLM model, and (new in the
 * dynamic model picker feature) opening the model-picker dialog itself -
 * {@code pickModel} is declared right after {@code getModel}/{@code setModel}
 * since it is model-related plumbing, same as those two. The reasoning-effort
 * pair ({@code getEffort}/{@code setEffort}) follows the same read/write shape
 * as the model pair: the GUI cycles the value, the wiring layer applies and
 * persists it.
 */
public record GuiCallbacks(BiConsumer<Player, String> export,
                           BiConsumer<Player, String> applyVersion,
                           BiConsumer<Player, String> configure,
                           BiConsumer<Player, String> editMod,
                           BiConsumer<Player, String> fix,
                           BiConsumer<Player, String> openManual,
                           BiConsumer<Player, String> openSource,
                           BiConsumer<Player, String> openErrors,
                           Runnable reloadConfig,
                           Supplier<String> getModel, Consumer<String> setModel,
                           Consumer<Player> pickModel,
                           Supplier<String> getEffort, Consumer<String> setEffort) {
}
