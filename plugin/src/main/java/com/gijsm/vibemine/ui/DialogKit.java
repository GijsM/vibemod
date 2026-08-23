package com.gijsm.vibemine.ui;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/**
 * The dialog design language in code: every native dialog class
 * ({@link Dialogs}, {@link SettingsDialog}, {@link InfoDialogs},
 * {@link ModHubDialog}) builds its screens from these shared pieces so the
 * whole UI stays one visual system.
 *
 * <ul>
 *   <li><b>Titles</b>: {@link #title} — an aqua {@code ⬡ } prefix plus the
 *       screen name in white, matching {@link Style#prefix()} branding.</li>
 *   <li><b>Widths</b>: the four-step scale {@link #BODY} (prose),
 *       {@link #WIDE} (source code), {@link #INPUT} (form inputs),
 *       {@link #ROW} (list-row buttons) — no dialog line rides the 200px
 *       vanilla default.</li>
 *   <li><b>Navigation</b>: {@link #navButton} routes through real
 *       {@code /vibe} subcommands so permissions are re-checked and data is
 *       re-fetched per click; the canonical cross-nav quartet
 *       ({@link #manualButton}, {@link #sourceButton}, {@link #errorsButton},
 *       {@link #historyButton}) and the exits ({@link #backToHubButton},
 *       {@link #backToListButton}, {@link #doneButton},
 *       {@link #cancelButton}) live here so wording and tooltips never
 *       drift. Every button carries a tooltip.</li>
 *   <li><b>Item bodies</b>: {@link #iconBody} (decorative, tooltip
 *       suppressed) and {@link #iconItem} / {@link #resolveIcon} for mod
 *       icons, with the enchant glint marking a running mod.</li>
 *   <li><b>Plumbing</b>: {@link #show} (next tick), {@link #noOp},
 *       {@link #mainThreadClick} (main-thread hop + one shared error
 *       string).</li>
 * </ul>
 */
@SuppressWarnings("UnstableApiUsage")
final class DialogKit {

    /** Prose body lines: wider than the 200px default so sentences breathe. */
    static final int BODY = 400;
    /** Source-code bodies: wider still so code lines rarely wrap (vanilla caps a body at 1024px). */
    static final int WIDE = 600;
    /** Every form input (text fields, sliders, dropdowns) so labels/values do not clip. */
    static final int INPUT = 350;
    /** Every list-row button (browser mods, source files, history versions) so labels align. */
    static final int ROW = 300;

    /** Vanilla's "Force Unicode" font: uniform glyph sizing that makes code and tables column-align. */
    static final Key UNIFORM_FONT = Key.key("minecraft", "uniform");

    private DialogKit() {
    }

    // ---- titles ----

    /** The one title grammar: aqua {@code ⬡ } + {@code rest} in white (the dialog cousin of {@link Style#prefix()}). */
    static Component title(String rest) {
        return Component.text("⬡ ", NamedTextColor.AQUA)
                .append(Component.text(rest, NamedTextColor.WHITE));
    }

    // ---- buttons ----

    /**
     * A button that runs a {@code /vibe} subcommand, which re-checks permissions
     * and reopens the target screen with freshly assembled data.
     */
    static ActionButton navButton(String label, String command, String tooltip) {
        return navButton(Component.text(label), command, tooltip);
    }

    /** {@link #navButton(String, String, String)} with a pre-styled label component. */
    static ActionButton navButton(Component label, String command, String tooltip) {
        return ActionButton.builder(label)
                .tooltip(Component.text(tooltip, Style.INFO))
                .action(DialogAction.staticAction(ClickEvent.runCommand(command)))
                .build();
    }

    /** The canonical manual cross-nav button. */
    static ActionButton manualButton(String mod) {
        return navButton("📖 Manual", "/vibe manual " + mod, "Open the player manual");
    }

    /** The canonical source cross-nav button. */
    static ActionButton sourceButton(String mod) {
        return navButton("⌨ Source", "/vibe source " + mod, "Read the generated source");
    }

    /** The canonical errors cross-nav button. */
    static ActionButton errorsButton(String mod) {
        return navButton("⚠ Errors", "/vibe errors " + mod, "View recent error records");
    }

    /** The canonical history cross-nav button. */
    static ActionButton historyButton(String mod) {
        return navButton("⏳ History", "/vibe history " + mod, "Browse and activate previous versions");
    }

    /** Viewer exit: {@code ← Back} to {@code mod}'s hub — every viewer climbs the hierarchy, never dead-ends. */
    static ActionButton backToHubButton(String mod) {
        return navButton("← Back", "/vibe info " + mod, "Back to the " + mod + " hub");
    }

    /** Hub exit: {@code ← Back to list} to the mod browser. */
    static ActionButton backToListButton() {
        return navButton("← Back to list", "/vibe list", "Back to the mod browser");
    }

    /** Top-of-hierarchy exit (browser, costs): a no-op {@code Done} that just closes. */
    static ActionButton doneButton() {
        return ActionButton.builder(Component.text("Done"))
                .tooltip(Component.text("Close", Style.INFO))
                .action(noOp())
                .build();
    }

    /** Form/confirm negative: a no-op {@code Cancel} that closes without acting. */
    static ActionButton cancelButton() {
        return cancelButton("Close without saving");
    }

    /** {@link #cancelButton()} with a screen-specific tooltip. */
    static ActionButton cancelButton(String tooltip) {
        return ActionButton.builder(Component.text("Cancel"))
                .tooltip(Component.text(tooltip, Style.INFO))
                .action(noOp())
                .build();
    }

    // ---- item bodies ----

    /**
     * Resolves a mod's icon Material from its stored {@code icon} name, falling back to
     * {@link Material#PAPER} when the name is blank, unrecognized, or not a real item.
     */
    static Material resolveIcon(String icon) {
        if (icon != null && !icon.isBlank()) {
            Material m = Material.matchMaterial(icon);
            if (m != null && m.isItem()) {
                return m;
            }
        }
        return Material.PAPER;
    }

    /**
     * A mod's icon as an ItemStack; {@code glint} adds the enchant shimmer
     * (the retired chest list's "running" cue, resurrected).
     */
    static ItemStack iconItem(String icon, boolean glint) {
        ItemStack item = new ItemStack(resolveIcon(icon));
        if (glint) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setEnchantmentGlintOverride(Boolean.TRUE);
                item.setItemMeta(meta);
            }
        }
        return item;
    }

    /** A decorative item body: {@code item} with {@code beside} rendered next to it, item tooltip suppressed. */
    static DialogBody iconBody(ItemStack item, Component beside) {
        return DialogBody.item(item)
                .description(DialogBody.plainMessage(beside, BODY))
                .showTooltip(false)
                .build();
    }

    /** {@link #iconBody(ItemStack, Component)} for a plain decorative material (form/dashboard headers). */
    static DialogBody iconBody(Material material, Component beside) {
        return iconBody(new ItemStack(material), beside);
    }

    // ---- text helpers ----

    /** One Component in {@code color} with newlines between {@code lines}. */
    static Component joined(List<String> lines, TextColor color) {
        Component out = Component.empty().color(color);
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out = out.append(Component.newline());
            }
            out = out.append(Component.text(lines.get(i)));
        }
        return out;
    }

    // ---- plumbing ----

    /**
     * Show next tick (never inside an inventory-click handler). Deliberately no
     * closeInventory(): showDialog replaces the client screen on its own, and a
     * close-container packet from here has stalled the main thread before.
     */
    static void show(Plugin plugin, Player p, Dialog dialog) {
        Bukkit.getScheduler().runTask(plugin, () -> p.showDialog(dialog));
    }

    /** A no-op click action for buttons (e.g. Cancel/Done) that should just close the dialog. */
    static DialogAction noOp() {
        return DialogAction.customClick((view, audience) -> {
        }, ClickCallback.Options.builder().uses(1).build());
    }

    /**
     * Wraps a dialog callback so it always hops to the main thread before running, and never lets
     * an exception escape into Bukkit — it is logged to {@code log} and reported to the player
     * instead.
     */
    static DialogAction mainThreadClick(Plugin plugin, Logger log,
                                        BiConsumer<DialogResponseView, Audience> body) {
        return DialogAction.customClick((view, audience) ->
                        Bukkit.getScheduler().runTask(plugin, () -> runSafely(log, view, audience, body)),
                ClickCallback.Options.builder().uses(1).build());
    }

    private static void runSafely(Logger log, DialogResponseView view, Audience audience,
                                  BiConsumer<DialogResponseView, Audience> body) {
        try {
            body.accept(view, audience);
        } catch (Exception e) {
            log.log(Level.WARNING, "Dialog callback failed", e);
            if (audience instanceof Player player) {
                player.sendMessage(Style.err("Something went wrong: "
                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
            }
        }
    }
}
