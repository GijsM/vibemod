package com.gijsm.vibemine.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.gijsm.vibemine.runtime.ModErrors;
import com.gijsm.vibemine.runtime.ModHandle;
import com.gijsm.vibemine.store.ModStore;

/**
 * Native dialog reading surfaces: the player-facing manual, source, and
 * recent-errors viewers, replacing the virtual books {@link VirtualBooks}
 * used to open ({@code VirtualBooks} keeps only its console {@code dump*}
 * role). Dialog bodies scroll, so there is no pagination here — the manual
 * renders its Markdown via {@link MarkdownMini}, source files render whole.
 *
 * <p>Same experimental-API and threading posture as {@link Dialogs}:
 * {@code UnstableApiUsage} suppressed file-wide, every dialog shown on the
 * main thread, every callback hops back to the main thread and never lets an
 * exception escape into Bukkit.
 *
 * <p>Cross-navigation between the three viewers (and Back from a source file
 * to the file index) runs the corresponding read-only {@code /vibe}
 * subcommand via {@link DialogAction#staticAction} — the same idiom as
 * {@link Dialogs#openFixConfirm} — so the viewers need no data suppliers of
 * their own beyond what each call passes in.
 */
@SuppressWarnings("UnstableApiUsage")
public final class InfoDialogs {

    private static final Logger LOG = Logger.getLogger(InfoDialogs.class.getName());

    /** Prose bodies (manual, errors): wider than the 200px default so sentences breathe. */
    private static final int PROSE_WIDTH = 400;
    /** Source bodies: wider still so code lines rarely wrap (vanilla caps a body at 1024px). */
    private static final int SOURCE_WIDTH = 600;
    /** File buttons on the source index: room for long class names. */
    private static final int FILE_BUTTON_WIDTH = 250;
    /** Vanilla's "Force Unicode" font: uniform glyph sizing reads better for code. */
    private static final Key UNIFORM_FONT = Key.key("minecraft", "uniform");

    private final Plugin plugin;

    public InfoDialogs(Plugin plugin) {
        this.plugin = plugin;
    }

    // ---- manual ----

    /** Opens {@code mod}'s manual: Markdown prose, verified facts, and the config table. */
    public void openManual(Player p, ModStore.StoredMod mod, ModHandle liveOrNull, Map<String, String> values) {
        List<DialogBody> body = new ArrayList<>();
        String manual = mod.manual() == null || mod.manual().isBlank() ? mod.description() : mod.manual();
        for (Component block : MarkdownMini.render(manual, NamedTextColor.GRAY)) {
            body.add(DialogBody.plainMessage(block, PROSE_WIDTH));
        }
        body.add(DialogBody.plainMessage(Component.text("Verified facts", NamedTextColor.DARK_AQUA), PROSE_WIDTH));
        body.add(DialogBody.plainMessage(
                joined(InstallCard.verifiedFactLines(mod, liveOrNull, values)), PROSE_WIDTH));
        body.add(DialogBody.plainMessage(Component.text("Config", NamedTextColor.GOLD), PROSE_WIDTH));
        body.add(DialogBody.plainMessage(joined(configTableLines(values)), PROSE_WIDTH));

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text(mod.name() + " — manual"))
                        .body(body)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(List.of(sourceButton(mod.name()), errorsButton(mod.name())))
                        .exitAction(doneButton())
                        .columns(2)
                        .build()));
        show(p, dialog);
    }

    private static List<String> configTableLines(Map<String, String> values) {
        List<String> lines = new ArrayList<>();
        if (values == null || values.isEmpty()) {
            lines.add("(no configurable settings)");
            return lines;
        }
        for (Map.Entry<String, String> e : new TreeMap<>(values).entrySet()) {
            lines.add(e.getKey() + " = " + e.getValue());
        }
        return lines;
    }

    // ---- source ----

    /**
     * Opens {@code name}'s generated source. A single-file mod opens the file
     * straight away; a multi-file mod opens an index dialog with one button per
     * file, each opening a per-file dialog with a Back button to this index.
     */
    public void openSource(Player p, String name, int version, Map<String, String> sources) {
        if (sources == null || sources.isEmpty()) {
            p.sendMessage(Style.err("No sources on disk for " + name + "."));
            return;
        }
        if (sources.size() == 1) {
            Map.Entry<String, String> only = sources.entrySet().iterator().next();
            show(p, fileDialog(name, version, only.getKey(), only.getValue(),
                    List.of(manualButton(name), errorsButton(name))));
            return;
        }
        show(p, indexDialog(name, version, sources));
    }

    /** The multi-file index: one button per file, plus the cross-navigation buttons. */
    private Dialog indexDialog(String name, int version, Map<String, String> sources) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String fqcn = entry.getKey();
            String source = entry.getValue();
            int lineCount = source.split("\n", -1).length;
            buttons.add(ActionButton.builder(Component.text(simpleName(fqcn)))
                    .tooltip(Component.text(fqcn + " — " + lineCount + " lines", NamedTextColor.GRAY))
                    .width(FILE_BUTTON_WIDTH)
                    .action(openFileAction(name, version, fqcn, source))
                    .build());
        }
        buttons.add(manualButton(name));
        buttons.add(errorsButton(name));
        return Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text(name + " — source v" + version))
                        .body(List.of(DialogBody.plainMessage(Component.text(
                                sources.size() + " files — pick one to read.", NamedTextColor.GRAY))))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(buttons).exitAction(doneButton()).columns(1).build()));
    }

    /** Click action for one index button: hop to the main thread and open that file's dialog. */
    private DialogAction openFileAction(String name, int version, String fqcn, String source) {
        return DialogAction.customClick((view, audience) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!(audience instanceof Player player)) {
                                return;
                            }
                            try {
                                player.showDialog(fileDialog(name, version, fqcn, source,
                                        List.of(backToIndexButton(name))));
                            } catch (Exception e) {
                                LOG.log(Level.WARNING, "Source dialog failed", e);
                                player.sendMessage(Style.err("Could not open " + simpleName(fqcn) + ": "
                                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
                            }
                        }),
                ClickCallback.Options.builder().uses(1).build());
    }

    /** One source file, whole, in the uniform font; {@code nav} supplies Back or cross-navigation. */
    private static Dialog fileDialog(String name, int version, String fqcn, String source, List<ActionButton> nav) {
        return Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text(name + " — source v" + version))
                        .body(List.of(
                                DialogBody.plainMessage(Component.text("// " + fqcn, NamedTextColor.DARK_GRAY),
                                        SOURCE_WIDTH),
                                DialogBody.plainMessage(Component.text(source, NamedTextColor.GRAY).font(UNIFORM_FONT),
                                        SOURCE_WIDTH)))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(nav).exitAction(doneButton()).columns(nav.size()).build()));
    }

    private static String simpleName(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(dot + 1);
    }

    // ---- errors ----

    /** Opens {@code name}'s recent deduped error records, one body block per record. */
    public void openErrors(Player p, String name, List<ModErrors.ErrorRecord> records) {
        List<DialogBody> body = new ArrayList<>();
        if (records == null || records.isEmpty()) {
            body.add(DialogBody.plainMessage(Component.text("No recent errors.", NamedTextColor.GRAY), PROSE_WIDTH));
        }
        for (ModErrors.ErrorRecord r : records == null ? List.<ModErrors.ErrorRecord>of() : records) {
            Component block = Component.text(r.count() + "× " + r.exceptionClass() + ": " + r.message(),
                            NamedTextColor.RED)
                    .append(Component.newline())
                    .append(Component.text("at " + r.topFrame() + " (" + r.where()
                            + ", last " + relativeTime(r.lastSeen()) + ")", NamedTextColor.GRAY));
            for (String frame : r.stack() == null ? List.<String>of() : r.stack()) {
                block = block.append(Component.newline())
                        .append(Component.text("  " + frame, NamedTextColor.DARK_GRAY));
            }
            body.add(DialogBody.plainMessage(block, PROSE_WIDTH));
        }
        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text(name + " — recent errors"))
                        .body(body)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(List.of(manualButton(name), sourceButton(name)))
                        .exitAction(doneButton())
                        .columns(2)
                        .build()));
        show(p, dialog);
    }

    /** Coarse "Ns/Nm/Nh/Nd ago" relative-time formatting for a {@code lastSeen} epoch millis. */
    private static String relativeTime(long epochMillis) {
        long deltaMs = System.currentTimeMillis() - epochMillis;
        if (deltaMs < 0) {
            deltaMs = 0;
        }
        long seconds = deltaMs / 1000;
        if (seconds < 60) {
            return seconds + "s ago";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h ago";
        }
        long days = hours / 24;
        return days + "d ago";
    }

    // ---- shared plumbing ----

    private static ActionButton manualButton(String mod) {
        return navButton("📖 Manual", "/vibe manual " + mod, "Open the player manual");
    }

    private static ActionButton sourceButton(String mod) {
        return navButton("⌨ Source", "/vibe source " + mod, "Read the generated source");
    }

    private static ActionButton errorsButton(String mod) {
        return navButton("⚠ Errors", "/vibe errors " + mod, "View recent error records");
    }

    private static ActionButton backToIndexButton(String mod) {
        return navButton("← Back", "/vibe source " + mod, "Back to the file list");
    }

    /**
     * A button that runs a read-only {@code /vibe} subcommand, which reopens the
     * target viewer with freshly assembled data (see the class javadoc).
     */
    private static ActionButton navButton(String label, String command, String tooltip) {
        return ActionButton.builder(Component.text(label))
                .tooltip(Component.text(tooltip, NamedTextColor.GRAY))
                .action(DialogAction.staticAction(ClickEvent.runCommand(command)))
                .build();
    }

    /** A no-op close button, mirroring {@link Dialogs}' Cancel. */
    private static ActionButton doneButton() {
        return ActionButton.builder(Component.text("Done"))
                .action(DialogAction.customClick((view, audience) -> {
                }, ClickCallback.Options.builder().uses(1).build()))
                .build();
    }

    /** Gray, one Component with newlines between {@code lines}. */
    private static Component joined(List<String> lines) {
        Component out = Component.empty().color(NamedTextColor.GRAY);
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out = out.append(Component.newline());
            }
            out = out.append(Component.text(lines.get(i)));
        }
        return out;
    }

    /**
     * Show next tick (never inside an inventory-click handler); same rationale
     * as {@link Dialogs}: showDialog replaces the client screen on its own.
     */
    private void show(Player p, Dialog dialog) {
        Bukkit.getScheduler().runTask(plugin, () -> p.showDialog(dialog));
    }
}
