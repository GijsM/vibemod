package com.gijsm.vibemod.neoforge;

import java.util.logging.Logger;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;

import net.minecraft.SharedConstants;

import com.gijsm.vibemod.platform.CompilerProvider;
import com.gijsm.vibemod.platform.PlatformInfo;

/**
 * What this NeoForge installation can do, probed once at boot.
 *
 * <p>The twin of {@code FabricPlatformInfo} and for the same reason: VibeMod's
 * loader hosts are MC 26.1+ only (ARCHITECTURE-V2 §0#4), so there is no era
 * split to straddle — dialogs, data components and Brigadier are all simply
 * there. The probes exist to be honest and to keep {@code core}'s
 * capability-gated code paths uniform. The two that carry real information are
 * {@link #hasClient()} and {@link #maxTargetRelease()}.
 */
public final class NeoForgePlatformInfo implements PlatformInfo {

    private static final Logger LOG = Logger.getLogger(NeoForgePlatformInfo.class.getName());

    /** {@code PlatformProfiles} id for the loader profile (§6.2). */
    public static final String PROFILE_ID = "neoforge";

    /** The class whose presence marks a game with the server-side dialog API. */
    private static final String DIALOG_CLASS = "net.minecraft.server.dialog.Dialog";

    private final String mcVersion;
    private final boolean hasDialogs;
    private final boolean hasSystemCompiler;
    private final boolean physicalClient;
    private final boolean dedicated;
    private final int maxTargetRelease;

    public NeoForgePlatformInfo(boolean dedicatedServer) {
        this.mcVersion = detectMcVersion();
        this.hasDialogs = classPresent(DIALOG_CLASS);
        this.hasSystemCompiler = CompilerProvider.resolve().isPresent();
        this.physicalClient = dist() == Dist.CLIENT;
        this.dedicated = dedicatedServer;
        this.maxTargetRelease = Runtime.version().feature();
    }

    /** One line for the boot log: everything a bug report needs to reproduce a UI difference. */
    public String describe() {
        return "neoforge " + mcVersion + " · profile=" + PROFILE_ID
                + " · dialogs=" + hasDialogs
                + " · physicalClient=" + physicalClient
                + " · dedicated=" + dedicated
                + " · target=java" + maxTargetRelease
                + " · java.compiler=" + (hasJavaCompilerModule() ? "present" : "ABSENT");
    }

    /**
     * Whether the {@code java.compiler} module — the one that declares
     * {@code javax.tools} — is in this JVM's boot layer.
     *
     * <p>ARCHITECTURE-V2 §7.3 asks for this probe, because Mojang ships the
     * client with a jlinked runtime rather than a full JDK, and a runtime built
     * without {@code java.compiler} would leave even the bundled ECJ unusable:
     * ECJ implements {@code javax.tools.JavaCompiler}, so the API has to exist
     * for it to be loadable at all. The contingency in that case is to
     * Jar-in-Jar the {@code javax.tools} API classes too.
     *
     * <p>Logged on every boot rather than only checked once here, so the answer
     * comes from whatever runtime an actual user launched with instead of from
     * the developer's JDK.
     */
    public static boolean hasJavaCompilerModule() {
        try {
            return ModuleLayer.boot().findModule("java.compiler").isPresent();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String platformName() {
        return "neoforge";
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

    /**
     * Whether this process has a physical client — the {@code ctx.client(...)}
     * gate. It is the LOADER's dist, not the server's dedicated-ness: a client
     * hosting a LAN world runs an integrated server whose
     * {@code isDedicatedServer()} is false and which does have a client, and a
     * client sitting in the main menu is still a client. The two questions are
     * genuinely different and §8 depends on both.
     */
    @Override
    public boolean hasClient() {
        return physicalClient;
    }

    @Override
    public boolean hasNativeCommandMap() {
        return true;
    }

    @Override
    public boolean isDedicatedServer() {
        return dedicated;
    }

    @Override
    public boolean hasItemGlintOverride() {
        return true;
    }

    @Override
    public boolean hasCommandResync() {
        return true;
    }

    /**
     * The release generated mods may target: the running JVM's own feature
     * version, and nothing lower.
     *
     * <p>ARCHITECTURE-V2 §10.3 left this open for Phase E, and warned that the
     * answer might differ from Fabric's: Paper's ceiling is its own class-file
     * version because its plugin remapper pipes every dynamically defined class
     * through ASM, and NeoForge's ModLauncher transforms far more aggressively
     * than Knot does. <b>It does not matter, for the same structural reason it
     * did not on Fabric.</b>
     *
     * <p>The chain was checked end to end. Generated classes are defined by
     * {@code ModLifecycle.BytesClassLoader}, a plain {@link ClassLoader}
     * subclass calling {@code defineClass} directly, whose parent is the
     * transforming loader. FML's class processors (access transformers, mixin,
     * the coremod pipeline) run inside that loader's own {@code findClass} —
     * they see only classes it is asked to LOAD. A child loader that defines
     * bytes itself never enters that path, so nothing between our compiler
     * output and the JVM's verifier ever reads the class file, and the only
     * thing that can reject a Java 25 class file is a JVM older than 25 — which
     * MC 26.x refuses to start on anyway.
     *
     * <p>Measured, not reasoned: {@code VibeModNeoForge} logs the class-file
     * major version of a class it defines through the very same
     * {@code BytesClassLoader} path at boot, and the acceptance gate asserts on
     * it. See §10.4.
     *
     * <p>{@code InMemoryCompiler} still clamps to
     * {@code min(runtime, backend, host)}, so a bundled ECJ that lags the JVM
     * lowers this on its own — which is the case that actually bites, not the
     * host.
     */
    @Override
    public int maxTargetRelease() {
        return maxTargetRelease;
    }

    @Override
    public String profileId() {
        return PROFILE_ID;
    }

    // ---- probes ----

    /**
     * The dist, asked of FML.
     *
     * <p>{@code FMLEnvironment.getDist()} is the documented answer;
     * {@code FMLLoader.getCurrent()} is the fallback for the (dev-only) case
     * where the environment holder has not been initialised.
     */
    private static Dist dist() {
        try {
            return net.neoforged.fml.loading.FMLEnvironment.getDist();
        } catch (Throwable t) {
            LOG.warning("Could not read the dist from FMLEnvironment (" + t + ")");
        }
        try {
            return FMLLoader.getCurrent().getDist();
        } catch (Throwable t) {
            return Dist.DEDICATED_SERVER;
        }
    }

    /**
     * The game version. {@code SharedConstants} is the game's own answer and is
     * available before a server exists; FML's version info is the fallback for a
     * game whose shared constants moved again.
     */
    private static String detectMcVersion() {
        try {
            return SharedConstants.getCurrentVersion().name();
        } catch (Throwable t) {
            LOG.warning("Could not read the game version from SharedConstants (" + t + ")");
        }
        try {
            return FMLLoader.getCurrent().getVersionInfo().mcVersion();
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean classPresent(String fqcn) {
        try {
            Class.forName(fqcn, false, NeoForgePlatformInfo.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
