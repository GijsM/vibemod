package com.gijsm.vibemod.paper;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.plugin.java.JavaPlugin;

import com.gijsm.vibemod.platform.PlatformInfo;

/**
 * bStats, on Paper only (ARCHITECTURE-V2 §9 Phase F, §10.5).
 *
 * <p><b>It does not run yet, and that is deliberate.</b> bStats keys every
 * submission on a numeric <i>service id</i> that only exists once a human
 * registers the plugin at <a href="https://bstats.org/getting-started">
 * bstats.org/getting-started</a>. VibeMod has no such id, and a placeholder is
 * not a harmless placeholder — it would either be rejected or, worse, silently
 * pile VibeMod's data onto somebody else's chart. So {@link #SERVICE_ID} is
 * {@code -1}, {@link #start} refuses to start on it, and the whole thing is one
 * constant away from live.
 *
 * <p>The relocation matters as much as the id. Paper's plugin class loaders
 * delegate to each other, so two plugins shipping an un-relocated
 * {@code org.bstats} share one class and one config; the build relocates it to
 * {@code com.gijsm.vibemod.bstats} for that reason, and this file is the only
 * place in VibeMod that names bStats at all.
 *
 * <p>Opting out is bStats' own global switch — {@code plugins/bStats/config.yml},
 * {@code enabled: false} — which turns it off for every plugin on the server at
 * once. VibeMod deliberately does not add a second, VibeMod-only toggle: two
 * switches for one behaviour is how a server owner ends up believing they have
 * opted out when they have not.
 *
 * <p>The two charts §9 asks for are {@code platform} and {@code mc_version}.
 * The rest are here because the migration made them the questions worth asking:
 * which UI tier servers actually land on, whether anyone is running without a
 * system compiler (the reason ECJ is bundled on the loaders at all), and how
 * many mods a real install accumulates.
 */
public final class PaperMetrics {

    /**
     * The bStats service id.
     *
     * <p>TODO(gijsm): register VibeMod at https://bstats.org/getting-started
     * (one registration per platform — the Bukkit one is what this constant is)
     * and replace {@code -1} with the id it gives you. Nothing else needs to
     * change; metrics start on the next boot.
     */
    public static final int SERVICE_ID = -1;

    private PaperMetrics() {
    }

    /**
     * Starts bStats if — and only if — a real service id has been registered.
     *
     * @param plugin     the plugin instance bStats reports for
     * @param platform   the live capability probe, source of the platform/version charts
     * @param uiRenderer names the active UI tier ("dialogs" or "chat")
     * @param modCount   how many mods are in the store right now
     */
    public static void start(JavaPlugin plugin, PlatformInfo platform,
                             Supplier<String> uiRenderer, IntSupplier modCount) {
        if (SERVICE_ID <= 0) {
            plugin.getLogger().info("Metrics: off (no bStats service id registered — see PaperMetrics.SERVICE_ID)");
            return;
        }

        Metrics metrics = new Metrics(plugin, SERVICE_ID);
        metrics.addCustomChart(new SimplePie("platform", platform::platformName));
        metrics.addCustomChart(new SimplePie("mc_version", platform::mcVersion));
        metrics.addCustomChart(new SimplePie("ui_renderer", uiRenderer::get));
        metrics.addCustomChart(new SimplePie("system_compiler",
                () -> platform.hasSystemCompiler() ? "jdk" : "jre"));
        metrics.addCustomChart(new SingleLineChart("stored_mods", modCount::getAsInt));
        plugin.getLogger().info("Metrics: bStats service " + SERVICE_ID
                + " (opt out in plugins/bStats/config.yml)");
    }
}
