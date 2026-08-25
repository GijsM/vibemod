package com.gijsm.vibemod.paper;

import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.inventory.meta.ItemMeta;

import com.gijsm.vibemod.llm.PlatformProfiles;
import com.gijsm.vibemod.platform.CompilerProvider;
import com.gijsm.vibemod.platform.PlatformInfo;

/**
 * What this Paper server can actually do, probed once at boot.
 *
 * <p>Everything here is a probe, never a version comparison
 * (ARCHITECTURE-V2 §0#8) — with one deliberate exception. The dialog capability
 * needs BOTH the API class to exist AND the server to be new enough: Paper
 * shipped {@code io.papermc.paper.dialog.Dialog} while the protocol behind it
 * was still settling, so 1.21.6 has the class but not the behaviour VibeMod's
 * screens assume. Class presence alone would let the dialog renderer load on a
 * server that then renders nothing. Hence the {@code &&}.
 *
 * <p>The class probe is load-bearing in the other direction too: VibeMod is
 * compiled against 1.21.8 paper-api and ships a dialog renderer that references
 * classes a 1.20.6 server has never heard of. Nothing may touch that renderer
 * unless this probe says yes, which is why the bootstrap instantiates it
 * reflectively rather than with {@code new}.
 */
public final class PaperPlatformInfo implements PlatformInfo {

    private static final Logger LOG = Logger.getLogger(PaperPlatformInfo.class.getName());

    /** The class whose presence marks a server with the vanilla dialog API. */
    private static final String DIALOG_CLASS = "io.papermc.paper.dialog.Dialog";

    private final String mcVersion;
    private final String profileId;
    private final boolean hasDialogs;
    private final boolean hasSystemCompiler;
    private final boolean hasNativeCommandMap;
    private final boolean hasItemGlintOverride;
    private final boolean hasCommandResync;

    public PaperPlatformInfo() {
        this.mcVersion = detectMcVersion();
        this.profileId = PlatformProfiles.paperProfileIdFor(this.mcVersion);
        this.hasDialogs = classPresent(DIALOG_CLASS)
                && PlatformProfiles.PAPER_MODERN_ID.equals(this.profileId);
        this.hasSystemCompiler = CompilerProvider.resolve().isPresent();
        this.hasNativeCommandMap = detectCommandMap();
        this.hasItemGlintOverride = methodPresent(ItemMeta.class, "setEnchantmentGlintOverride", Boolean.class);
        this.hasCommandResync = methodPresent(org.bukkit.entity.Player.class, "updateCommands");
    }

    /** One line for the boot log: everything a bug report needs to reproduce a UI difference. */
    public String describe() {
        return "paper " + mcVersion + " · profile=" + profileId
                + " · dialogs=" + hasDialogs
                + " · commandMap=" + hasNativeCommandMap
                + " · glintOverride=" + hasItemGlintOverride
                + " · commandResync=" + hasCommandResync;
    }

    @Override
    public String platformName() {
        return "paper";
    }

    @Override
    public String mcVersion() {
        return mcVersion;
    }

    @Override
    public boolean hasDialogs() {
        return hasDialogs;
    }

    @Override
    public boolean hasSystemCompiler() {
        return hasSystemCompiler;
    }

    @Override
    public boolean hasClient() {
        return false;
    }

    @Override
    public boolean hasNativeCommandMap() {
        return hasNativeCommandMap;
    }

    @Override
    public boolean isDedicatedServer() {
        return true;
    }

    @Override
    public boolean hasItemGlintOverride() {
        return hasItemGlintOverride;
    }

    @Override
    public boolean hasCommandResync() {
        return hasCommandResync;
    }

    @Override
    public String profileId() {
        return profileId;
    }

    // ---- probes ----

    /**
     * The Minecraft version, taken from {@code getBukkitVersion()}
     * ({@code "1.20.6-R0.1-SNAPSHOT"}) rather than Paper's
     * {@code getMinecraftVersion()}: the former is plain Bukkit API present on
     * every supported server and fork, and the prefix before the first dash is
     * exactly the value we want.
     */
    private static String detectMcVersion() {
        try {
            String raw = Bukkit.getBukkitVersion();
            if (raw != null && !raw.isBlank()) {
                int dash = raw.indexOf('-');
                return dash > 0 ? raw.substring(0, dash) : raw;
            }
        } catch (Throwable t) {
            LOG.warning("Could not read the server version (" + t + "); assuming a modern Paper");
        }
        return "";
    }

    private static boolean classPresent(String fqcn) {
        try {
            Class.forName(fqcn, false, PaperPlatformInfo.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static boolean methodPresent(Class<?> owner, String name, Class<?>... params) {
        try {
            owner.getMethod(name, params);
            return true;
        } catch (NoSuchMethodException | LinkageError e) {
            return false;
        }
    }

    /**
     * Whether real top-level commands can be registered. Probed by asking for
     * the map, since a fork may return null (and {@code Bukkit.getCommandMap()}
     * itself is Paper API a hard fork could drop entirely).
     */
    private static boolean detectCommandMap() {
        try {
            return Bukkit.getCommandMap() != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
