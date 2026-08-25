package com.gijsm.vibemod.fabric;

import java.util.logging.Logger;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.SharedConstants;

import com.gijsm.vibemod.platform.CompilerProvider;
import com.gijsm.vibemod.platform.PlatformInfo;

/**
 * What this Fabric installation can do, probed once at boot.
 *
 * <p>Most of the answers are simpler than on Paper, and for one reason:
 * VibeMod's Fabric host is MC 26.1+ only (ARCHITECTURE-V2 §0#4). There is no era
 * split to straddle — dialogs, data components and Brigadier are all simply
 * there — so the probes exist to be honest and to keep {@code core}'s
 * capability-gated code paths uniform, not because a 26.x install might answer
 * differently. The two that carry real information are {@link #hasClient()} and
 * {@link #maxTargetRelease()}.
 */
public final class FabricPlatformInfo implements PlatformInfo {

    private static final Logger LOG = Logger.getLogger(FabricPlatformInfo.class.getName());

    /** {@code PlatformProfiles} id for the loader profile (§6.2). */
    public static final String PROFILE_ID = "fabric";

    /** The class whose presence marks a game with the server-side dialog API. */
    private static final String DIALOG_CLASS = "net.minecraft.server.dialog.Dialog";

    private final String mcVersion;
    private final boolean hasDialogs;
    private final boolean hasSystemCompiler;
    private final boolean physicalClient;
    private final boolean dedicated;
    private final int maxTargetRelease;

    public FabricPlatformInfo(boolean dedicatedServer) {
        this.mcVersion = detectMcVersion();
        this.hasDialogs = classPresent(DIALOG_CLASS);
        this.hasSystemCompiler = CompilerProvider.resolve().isPresent();
        this.physicalClient = FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
        this.dedicated = dedicatedServer;
        this.maxTargetRelease = Runtime.version().feature();
    }

    /** One line for the boot log: everything a bug report needs to reproduce a UI difference. */
    public String describe() {
        return "fabric " + mcVersion + " · profile=" + PROFILE_ID
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
     * <p>ARCHITECTURE-V2 §7.3 asks for this probe before Phase D builds anything,
     * because Mojang ships the client with a jlinked runtime rather than a full
     * JDK, and a runtime built without {@code java.compiler} would leave even the
     * bundled ECJ unusable: ECJ implements {@code javax.tools.JavaCompiler}, so
     * the API has to exist for it to be loadable at all. The contingency in that
     * case is to Jar-in-Jar the {@code javax.tools} API classes too.
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
        return "fabric";
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
     * gate. It is the LOADER's environment type, not the server's dedicated-ness:
     * a client hosting a LAN world runs an integrated server whose
     * {@code isDedicatedServer()} is false and which does have a client, and a
     * client sitting in the main menu is still a client environment. The two
     * questions are genuinely different and §8 depends on both.
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
     * <p>ARCHITECTURE-V2 §10.2 left this as a question for Phase D, because the
     * Paper answer was surprising: Paper 1.20.6 pipes every dynamically defined
     * class through the ASM 9.7 in its plugin remapper, which rejects Java 25
     * class files, so the ceiling there is the server's own class-file version
     * rather than the JVM's.
     *
     * <p>Fabric has no equivalent. The chain was checked end to end: generated
     * classes are defined by {@code ModLifecycle.BytesClassLoader}, a plain
     * {@link ClassLoader} subclass calling {@code defineClass} directly, whose
     * parent is Knot. Knot's transformer and Mixin's transformer only see classes
     * they themselves <em>load</em> — Mixin applies during {@code KnotClassDelegate}'s
     * class-load path, and a child loader that defines bytes itself never enters
     * it. Nothing between our compiler output and the JVM reads the class file,
     * so the only thing that can reject it is the JVM, and MC 26.x requires
     * Java 25 anyway.
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
     * The game version. {@code SharedConstants} is the primary source because it
     * is the game's own answer and is available before a server exists;
     * {@code FabricLoader.getRawGameVersion()} is the fallback for a game whose
     * shared constants moved again.
     */
    private static String detectMcVersion() {
        try {
            return SharedConstants.getCurrentVersion().name();
        } catch (Throwable t) {
            LOG.warning("Could not read the game version from SharedConstants (" + t + ")");
        }
        try {
            return FabricLoader.getInstance().getRawGameVersion();
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean classPresent(String fqcn) {
        try {
            Class.forName(fqcn, false, FabricPlatformInfo.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
