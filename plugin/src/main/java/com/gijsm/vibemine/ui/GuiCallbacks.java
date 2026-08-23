package com.gijsm.vibemine.ui;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

/**
 * Actions the mod browser GUI cannot perform on its own and instead delegates
 * back to the wiring layer: exporting a jar, reapplying a rolled-back
 * version (the per-mod [⟟ reload] button), opening the configure/edit/fix
 * dialogs, opening the manual/source/errors/history viewer dialogs -
 * {@code openHistory} (version timeline feature) is declared right after
 * {@code openErrors} since it is the fourth viewer dialog - reloading
 * {@code config.yml}, reading/writing the active LLM model, and (new in the
 * dynamic model picker feature) opening the model-picker dialog itself -
 * {@code pickModel} is declared right after {@code getModel}/{@code setModel}
 * since it is model-related plumbing, same as those two. {@code openSettings}
 * (declared last: it arrived with the native settings-form feature) opens the
 * {@link SettingsDialog} that replaced the old chest SETTINGS screen; the
 * former {@code getEffort}/{@code setEffort} pair went with that screen -
 * thinking effort is now an input on the settings form.
 */
public record GuiCallbacks(BiConsumer<Player, String> export,
                           BiConsumer<Player, String> applyVersion,
                           BiConsumer<Player, String> configure,
                           BiConsumer<Player, String> editMod,
                           BiConsumer<Player, String> fix,
                           BiConsumer<Player, String> openManual,
                           BiConsumer<Player, String> openSource,
                           BiConsumer<Player, String> openErrors,
                           BiConsumer<Player, String> openHistory,
                           Runnable reloadConfig,
                           Supplier<String> getModel, Consumer<String> setModel,
                           Consumer<Player> pickModel,
                           Consumer<Player> openSettings) {
}
