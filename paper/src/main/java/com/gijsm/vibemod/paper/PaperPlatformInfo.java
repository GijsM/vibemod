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
 * (ARCHITECTURE-V2 §0#8) — with one belt-and-braces exception, the dialog
 * capability, which requires both the API class and a modern profile.
 *
 * <p>This javadoc used to justify that {@code &&} by claiming 1.21.6 "has the
 * class but not the behaviour". <b>That was wrong.</b> Measured against every
 * cached {@code paper-api} jar: {@code io.papermc.paper.dialog.Dialog} is absent
 * from 1.21.6 entirely and first appears in 1.21.7 (docs/API-VOCABULARY.md,
 * claim 5). The class probe alone would therefore have been sufficient. The
 * {@code &&} is kept because it is harmless and costs nothing on a fork that
 * back-ports the class without the protocol, but it is a belt on top of braces,
 * not the load-bearing part it was described as.
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

    /**
     * The class whose presence marks a genuinely regionised server.
     *
     * <p>Choosing this correctly took three wrong answers, every one of them
     * caught on a real server rather than by reading, so the reasoning is
     * recorded here instead of being rediscovered:
     *
     * <ul>
     *   <li>{@code ...threadedregions.scheduler.GlobalRegionScheduler} and every
     *       other type in that package ship in ordinary {@code paper-api}
     *       ({@code javap}, 1.21.8) and work on single-threaded Paper, where they
     *       delegate to the main thread. Probing the scheduler API reports every
     *       Paper server as regionised.
     *   <li>{@code ...threadedregions.RegionizedServerInitEvent} is likewise in
     *       {@code paper-api}.
     *   <li>{@code ...threadedregions.TickRegions} — which this probe briefly
     *       used — is in the plain <b>Paper server jar</b>: observed loading from
     *       {@code paper-1.20.6.jar} on a real Paper 1.20.6 boot. Paper carries
     *       parts of Folia's regionised source without running regionised.
     * </ul>
     *
     * <p>{@code RegionizedServer} is the one that holds. Measured across four
     * real boots with {@code -Xlog:class+load}: present on Folia 26.2 (loaded
     * four times), absent on Paper 1.20.6, 1.21.8 and 26.2.
     *
     * <p>Getting this wrong is not a soft failure, and modern Paper will not tell
     * you. A false positive selects {@link FoliaTickScheduler} on a server that
     * is not Folia; Paper 1.20.6 then dies on its first async hop with
     * {@code NoSuchMethodError: Bukkit.isGlobalTickThread()}, because that method
     * postdates 1.20.6. On Paper 1.21.8 and 26.2 the same false positive is
     * silent, because the global region scheduler genuinely works there.
     */
    private static final String[] REGIONISED_CLASSES = {
        "io.papermc.paper.threadedregions.RegionizedServer",
    };

    private final String mcVersion;
    private final String profileId;
    private final boolean hasDialogs;
    private final boolean hasSystemCompiler;
    private final boolean hasNativeCommandMap;
    private final boolean hasItemGlintOverride;
    private final boolean hasCommandResync;
    private final boolean regionised;
    private final int maxTargetRelease;
    /** Measured once here; reflecting Material alone walks 1,900+ fields. */
    private final com.gijsm.vibemod.platform.ApiVocabulary vocabulary;

    public PaperPlatformInfo() {
        this.mcVersion = detectMcVersion();
        this.profileId = PlatformProfiles.paperProfileIdFor(this.mcVersion);
        this.hasDialogs = classPresent(DIALOG_CLASS)
                && PlatformProfiles.PAPER_MODERN_ID.equals(this.profileId);
        this.hasSystemCompiler = CompilerProvider.resolve().isPresent();
        this.hasNativeCommandMap = detectCommandMap();
        this.hasItemGlintOverride = methodPresent(ItemMeta.class, "setEnchantmentGlintOverride", Boolean.class);
        this.hasCommandResync = methodPresent(org.bukkit.entity.Player.class, "updateCommands");
        this.regionised = detectRegionised();
        this.maxTargetRelease = detectMaxTargetRelease();
        this.vocabulary = PaperApiVocabulary.measure();
    }

    @Override
    public com.gijsm.vibemod.platform.ApiVocabulary vocabulary() {
        return vocabulary;
    }

    /** One line for the boot log: everything a bug report needs to reproduce a UI difference. */
    public String describe() {
        return "paper " + mcVersion + " · profile=" + profileId
                + " · dialogs=" + hasDialogs
                + " · commandMap=" + hasNativeCommandMap
                + " · glintOverride=" + hasItemGlintOverride
                + " · commandResync=" + hasCommandResync
                + " · regionised=" + regionised
                + " · target=java" + maxTargetRelease
                + " · vocabulary=" + vocabulary;
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
    public boolean isRegionised() {
        return regionised;
    }

    @Override
    public int maxTargetRelease() {
        return maxTargetRelease;
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

    /**
     * The release generated mods may target, read from the class-file major
     * version of {@code org.bukkit.Bukkit} — i.e. whatever this server itself was
     * compiled for.
     *
     * <p>The server's bytecode tooling is built for the server's own class files,
     * so that is exactly the ceiling a generated mod must respect. Paper 1.20.6
     * proves it: it is happy to run on a JDK 25 but its plugin remapper's ASM
     * 9.7 rejects every Java 25 class we hand to {@code defineClass}. Falls back
     * to the runtime's feature version when the class file cannot be read, which
     * is the old (and on modern servers correct) behaviour.
     */
    private static int detectMaxTargetRelease() {
        int fallback = Runtime.version().feature();
        try (java.io.InputStream in = Bukkit.class.getClassLoader()
                .getResourceAsStream("org/bukkit/Bukkit.class")) {
            if (in == null) {
                return fallback;
            }
            byte[] header = in.readNBytes(8);
            if (header.length < 8 || (header[0] & 0xFF) != 0xCA || (header[1] & 0xFF) != 0xFE) {
                return fallback;
            }
            int major = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
            // Class file major 65 == Java 21; the offset has been 44 since Java 1.1.
            int release = major - 44;
            if (release < 17 || release > fallback) {
                // Nonsense, or a server newer than the JVM running it: trust the JVM.
                return fallback;
            }
            return release;
        } catch (Throwable t) {
            LOG.fine("Could not read the server's class-file version (" + t
                    + "); targeting Java " + fallback);
            return fallback;
        }
    }

    /**
     * Whether this server ticks in more than one ordering domain.
     *
     * <p>Never asks {@code Bukkit.getScheduler()}. That call is precisely what
     * blows up on Folia — {@code CraftScheduler.handle} throws
     * {@link UnsupportedOperationException} — so using it as a feature test
     * would mean triggering the crash in order to learn that the crash happens.
     *
     * <p>Two independent signals, either of which is sufficient. The class probe
     * is the precise one; the name check is the belt, because a regionised fork
     * may rename the internals but still has to identify itself, and reporting a
     * regionised server as single-threaded is the expensive direction of this
     * error — it would hand every generated mod a threading contract that is
     * false and let it race silently.
     */
    private static boolean detectRegionised() {
        for (String fqcn : REGIONISED_CLASSES) {
            if (classPresent(fqcn)) {
                LOG.fine("Regionised server detected via " + fqcn);
                return true;
            }
        }
        try {
            String name = Bukkit.getName();
            String version = Bukkit.getVersion();
            if (containsFolia(name) || containsFolia(version)) {
                LOG.fine("Regionised server detected via server identity: " + name + " / " + version);
                return true;
            }
        } catch (Throwable t) {
            LOG.fine("Could not read the server identity (" + t + "); assuming not regionised");
        }
        return false;
    }

    private static boolean containsFolia(String s) {
        return s != null && s.toLowerCase(java.util.Locale.ROOT).contains("folia");
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
