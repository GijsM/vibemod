package symbols;

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

/**
 * One cached {@code paper-api} jar, read as bytes, presented as the
 * {@link PromptFacts} a server running that version would hand the prompt
 * builder.
 *
 * <p>This is the bridge {@link PromptSymbolGate} stands on: it lets an offline
 * test build the <em>real</em> system prompt for a version nobody has booted,
 * against that version's own measured API surface. No Bukkit class is loaded —
 * {@link ClassFileVocabulary} parses class files, so this runs on any JDK with
 * nothing but the jar cache present.
 *
 * <h2>Why the vocabulary here is closed-world, and where it stops being so</h2>
 *
 * <p>A {@code paper-api} jar is a complete enumeration of the Paper API surface,
 * so for a type the jar <em>contains</em>, a constant or method the jar does not
 * list is genuinely absent: {@code NO}, not {@code UNKNOWN}. That is what makes
 * this a gate rather than a guess. For a type the jar does not contain at all,
 * {@link ClassFileVocabulary} answers {@code NO} to
 * {@link ApiVocabulary#knows(String)} — correct for {@code Dialog} on 1.21.6,
 * and correct for the prompt-rule predicates, which only ever ask about Paper
 * types. It is <em>not</em> a safe reading for arbitrary text: the jar has no
 * opinion on {@code Component} (Adventure), {@code VibeContext} (the sdk) or
 * {@code HashMap}, and calling those absent would be a lie about a different
 * classpath. {@link PromptSymbolGate} therefore treats "type not in this jar" as
 * unchecked; see its own comment.
 */
final class JarFacts {

    private JarFacts() {
    }

    /** Every Minecraft version with a cached jar, oldest first. */
    static List<String> versions(Path jarsDir) throws IOException {
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
    static final Comparator<String> VERSION_ORDER = (a, b) -> {
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

    /** True when {@code version} is at or after {@code floor}. */
    static boolean atLeast(String version, String floor) {
        return VERSION_ORDER.compare(version, floor) >= 0;
    }

    static Path jar(Path jarsDir, String version) {
        return jarsDir.resolve("paper-api-" + version + ".jar");
    }

    /** The measured vocabulary of one cached version. */
    static ClassFileVocabulary vocabulary(Path jarsDir, String version) throws IOException {
        return ClassFileVocabulary.ofJar(jar(jarsDir, version));
    }

    /** The facts a Paper server on this version would build its prompt from. */
    static PromptFacts factsFor(String version, ApiVocabulary vocabulary) {
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
