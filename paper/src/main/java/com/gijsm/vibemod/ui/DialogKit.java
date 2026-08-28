package com.gijsm.vibemod.ui;

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
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import com.gijsm.vibemod.platform.TickScheduler;

/**
 * The dialog design language in code — the visual vocabulary and the plumbing
 * that {@link PaperDialogRenderer} renders every {@code Screen} with, so the
 * whole UI stays one visual system no matter which screen builder produced it.
 *
 * <p>Before Phase C this was shared by four hand-written dialog classes
 * (retired: {@code Dialogs}, {@code SettingsDialog}, {@code InfoDialogs},
 * {@code ModHubDialog}). Screens are data now (ARCHITECTURE-V2 §3) and the
 * renderer is the only caller — but the vocabulary itself did not change, and
 * that is deliberate: the migration must be pixel-for-pixel invisible to
 * players. Everything here is exactly what those classes used.
 *
 * <ul>
 *   <li><b>Titles</b>: {@link #title} — an aqua {@code ⬡ } prefix plus the
 *       screen name in white, matching {@link Style#prefix()} branding. Screen
 *       builders in core own their titles now; this stays the one place the
 *       grammar is written down (and the loader renderers' reference).</li>
 *   <li><b>Widths</b>: the four-step scale {@link #BODY} (prose),
 *       {@link #WIDE} (source code), {@link #INPUT} (form inputs),
 *       {@link #ROW} (list-row buttons) — no dialog line rides the 200px
 *       vanilla default. {@code WidthHint} maps 1:1 onto these four.</li>
 *   <li><b>Exits</b>: {@link #doneButton} (top of a hierarchy) and
 *       {@link #cancelButton} (forms and confirmations) — the fallbacks the
 *       renderer supplies when a {@code Screen} carries no {@code exit}.</li>
 *   <li><b>Item bodies</b>: {@link #iconBody} (decorative, tooltip
 *       suppressed) and {@link #iconItem} / {@link #resolveIcon} for mod
 *       icons, with the enchant glint marking a running mod.</li>
 *   <li><b>Plumbing</b>: {@link #show} (next tick), {@link #noOp},
 *       {@link #mainThreadClick} (main-thread hop + one shared error
 *       string).</li>
 * </ul>
 *
 * <p>The dialog API is {@code @Experimental} on this Paper version; those
 * warnings are suppressed file-wide (there is no {@code -Werror} anywhere in
 * this build, so they are purely informational) rather than annotated on every
 * method individually.
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
     *
     * <p>{@code glintSupported} is the capability gate:
     * {@code ItemMeta#setEnchantmentGlintOverride} only exists from MC 1.20.5,
     * and the Paper floor is 1.20.6 with the era probe
     * {@code PlatformInfo.hasItemGlintOverride()} (ARCHITECTURE-V2 §0#8 —
     * probes, never version comparisons). When the probe says no we render the
     * plain item: the glint is a cue, never load-bearing information (the
     * state also reads out in the body text and the button labels).
     */
    static ItemStack iconItem(String icon, boolean glint, boolean glintSupported) {
        ItemStack item = new ItemStack(resolveIcon(icon));
        if (glint && glintSupported) {
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
     * close-container packet from here has stalled the main thread before
     * (regression: commits 639dd32/3666bf0 — do not "helpfully" add it back).
     *
     * <p>Routed through {@link TickScheduler} rather than
     * {@code Bukkit.getScheduler()} because that call throws on Folia, and
     * dialogs probe TRUE there (measured on a real Folia 26.2 boot:
     * {@code dialogs=true}), so the crash would have hit the DEFAULT UI on
     * every dialog a player opened.
     *
     * <p>{@code later(0L, …)} and not {@code runOnMain(…)}: {@code runOnMain}
     * runs inline when it is already on the main thread, and "never inline" is
     * the entire point of this method. {@code later(0)} is next-tick on both
     * platforms — on Paper it is literally what {@code runTask} compiles to.
     */
    static void show(TickScheduler scheduler, Player p, Dialog dialog) {
        scheduler.later(0L, () -> p.showDialog(dialog));
    }

    /** A no-op click action for buttons (e.g. Cancel/Done) that should just close the dialog. */
    static DialogAction noOp() {
        return DialogAction.customClick((view, audience) -> {
        }, ClickCallback.Options.builder().uses(1).build());
    }

    /**
     * Wraps a dialog callback so it always hops to the main thread before running, and never lets
     * an exception escape into Bukkit — it is logged to {@code log} and reported to the player
     * instead. {@code uses(1)} makes it one-shot: a second click on the same button is a no-op
     * (a renderer obligation, ARCHITECTURE-V2 §3).
     */
    static DialogAction mainThreadClick(TickScheduler scheduler, Logger log,
                                        BiConsumer<DialogResponseView, Audience> body) {
        return DialogAction.customClick((view, audience) ->
                        scheduler.later(0L, () -> runSafely(log, view, audience, body)),
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
