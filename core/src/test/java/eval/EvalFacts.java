package eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.gijsm.vibemod.llm.PlatformProfile;
import com.gijsm.vibemod.llm.PlatformProfiles;
import com.gijsm.vibemod.llm.PromptFacts;
import com.gijsm.vibemod.platform.ApiVocabulary;
import com.gijsm.vibemod.platform.PlatformInfo;

import symbols.ClassFileVocabulary;

/**
 * The offline {@link PromptFacts} builder for the eval harness.
 *
 * <p>This is a faithful copy of the package-private {@code symbols.JarFacts},
 * which the B3 prompt-symbol gate stands on. It is duplicated rather than
 * shared because {@code JarFacts} is package-private and {@code symbols/} is
 * off-limits to this harness. Keeping the behaviour identical is what makes the
 * "after" prompts this harness sends the SAME strings the gate has already
 * verified — an eval that built a different prompt would be measuring something
 * nobody ships.
 */
public final class EvalFacts {

    private EvalFacts() {
    }

    /** Every Minecraft version with a cached jar, oldest first. */
    public static List<String> versions(Path jarsDir) throws IOException {
        List<String> versions = new ArrayList<>();
        try (var jars = Files.list(jarsDir)) {
            jars.forEach(p -> {
                String name = p.getFileName().toString();
                if (name.startsWith("paper-api-") && name.endsWith(".jar")) {
                    versions.add(name.substring("paper-api-".length(), name.length() - ".jar".length()));
                }
            });
        }
        versions.sort(VERSION_ORDER);
        return versions;
    }

    /** Numeric, component-wise: 1.21.9 &lt; 1.21.10 &lt; 26.1.1 &lt; 26.2. */
    public static final Comparator<String> VERSION_ORDER = (a, b) -> {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? parse(x[i]) : 0;
            int yi = i < y.length ? parse(y[i]) : 0;
            if (xi != yi) {
                return Integer.compare(xi, yi);
            }
        }
        return 0;
    };

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static Path jar(Path jarsDir, String version) {
        return jarsDir.resolve("paper-api-" + version + ".jar");
    }

    /** The measured vocabulary of one cached version. */
    public static ClassFileVocabulary vocabulary(Path jarsDir, String version) throws IOException {
        return ClassFileVocabulary.ofJar(jar(jarsDir, version));
    }

    /** The profile id the shipping code picks for this version. */
    public static String profileIdFor(String version) {
        return PlatformProfiles.paperProfileIdFor(version);
    }

    /** The facts a Paper server on this version would build its prompt from. */
    public static PromptFacts factsFor(String version, ApiVocabulary vocabulary) {
        String profileId = PlatformProfiles.paperProfileIdFor(version);
        PlatformProfile profile = PlatformProfiles.byId(profileId);
        return new PromptFacts(profile, stubInfo(version, profileId, vocabulary), vocabulary);
    }

    /**
     * Just enough {@link PlatformInfo} for the prompt builder: the version, the
     * profile id and the measured vocabulary. The booleans are what a dedicated
     * server reports; {@code hasDialogs} is derived from the jar rather than
     * asserted, so it too tracks the measurement.
     */
    private static PlatformInfo stubInfo(String version, String profileId, ApiVocabulary vocabulary) {
        return new PlatformInfo() {
            @Override
            public String platformName() {
                return "paper";
            }

            @Override
            public String mcVersion() {
                return version;
            }

            @Override
            public boolean hasDialogs() {
                return vocabulary.hasType("Dialog");
            }

            @Override
            public boolean hasSystemCompiler() {
                return true;
            }

            @Override
            public boolean hasClient() {
                return false;
            }

            @Override
            public boolean hasNativeCommandMap() {
                return true;
            }

            @Override
            public boolean isDedicatedServer() {
                return true;
            }

            @Override
            public ApiVocabulary vocabulary() {
                return vocabulary;
            }

            @Override
            public String profileId() {
                return profileId;
            }
        };
    }
}
