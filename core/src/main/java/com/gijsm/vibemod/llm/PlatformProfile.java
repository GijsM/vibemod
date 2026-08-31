package com.gijsm.vibemod.llm;

import java.util.List;

/**
 * Everything about the target platform that the system prompt (and the jar
 * exporter) must say differently (ARCHITECTURE-V2 §6.1).
 *
 * <p>The prompt is the single highest-leverage artefact in VibeMod: the
 * difference between a mod that compiles first try on Paper 1.20.6 and one that
 * needs three self-heal rounds is one sentence about which era's enum names are
 * real. So the era- and platform-specific sentences are data, chosen once at
 * boot from {@link com.gijsm.vibemod.platform.PlatformInfo}, and everything
 * else in {@link PromptLibrary} stays one fixed text shared by every platform.
 *
 * <p><b>What changed with the capability rework.</b> The era-specific half used
 * to be a {@code cheatSheet} string per profile, and a profile was picked by one
 * version comparison. It is now a {@link PromptRule} table evaluated against the
 * running server's own {@link com.gijsm.vibemod.platform.ApiVocabulary}, so the
 * two Paper profiles share one table and differ only in {@link #displayName()} —
 * the one piece of text with no probe behind it. Everything a jar can settle is
 * settled by asking the jar.
 *
 * @param id                 {@code "paper-modern"}, {@code "paper-legacy"}, {@code "fabric"}, {@code "neoforge"}
 * @param displayName        human label for logs, UI and the prompt's own "this server is"
 *                           line, e.g. {@code "Paper 1.21.7+"}. The only version text left in
 *                           the prompt that is derived from a version string rather than a probe
 * @param roleLine           the prompt's opening "You are an expert … author." sentence.
 *                           Deliberately free of any version number so that two profiles of the
 *                           same platform share it verbatim
 * @param apiSourceBlock     the flavor's verbatim sdk sources, already framed with
 *                           {@code --- path ---} headers (§6.4)
 * @param importRules        allowed import roots and explicit bans
 * @param rules              era/platform guidance as {@code (predicate, text)} pairs, evaluated
 *                           against the running server rather than asserted from a version
 * @param threadingContract  which thread mod callbacks run on, and what that forbids
 * @param fewShots           worked (user, assistant) example pairs
 * @param pluginDescriptor   the exporter's descriptor template — {@code api-version} on Paper,
 *                           {@code fabric.mod.json} / {@code neoforge.mods.toml} on the loaders (§6.3)
 * @param iconInstruction    what a valid {@code "icon"} value is on this platform/era
 * @param entrypointName     the interface the mod's {@code mainClass} implements — {@code "Mod"}
 *                           everywhere until V3, {@code net.fabricmc.api.ModInitializer} in the
 *                           native Fabric profile (V3 Phase 0 §E)
 * @param configContract     the config-knob rules. A profile-level string because a native V3 mod
 *                           has no {@code VibeContext} and therefore no {@code ctx.configX} to read
 *                           knobs with — telling it to read them anyway would produce a mod that
 *                           cannot compile
 * @param filesContract      what may appear in {@code files[]}. Profile-level since V3 Phase 2 §E:
 *                           every profile but the native Fabric one accepts {@code .java} and
 *                           nothing else, and that one also accepts a {@code data/**}/{@code assets/**}
 *                           resource tree
 */
public record PlatformProfile(
        String id,
        String displayName,
        String roleLine,
        String apiSourceBlock,
        String importRules,
        List<PromptRule> rules,
        String threadingContract,
        List<FewShot> fewShots,
        String pluginDescriptor,
        String iconInstruction,
        String entrypointName,
        String configContract,
        String filesContract) {

    /** One worked example pair shown to the model. */
    public record FewShot(String user, String assistant) {
    }

    public PlatformProfile {
        fewShots = List.copyOf(fewShots);
        rules = List.copyOf(rules);
    }
}
