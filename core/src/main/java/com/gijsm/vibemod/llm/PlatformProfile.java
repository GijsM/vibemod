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
 * @param id                 {@code "paper-modern"}, {@code "paper-legacy"}, {@code "fabric"}, {@code "neoforge"}
 * @param displayName        human label for logs and UI, e.g. {@code "Paper 1.21.7+"}
 * @param roleLine           the prompt's opening "You are an expert … author." sentence
 * @param apiSourceBlock     the flavor's verbatim sdk sources, already framed with
 *                           {@code --- path ---} headers (§6.4)
 * @param importRules        allowed import roots and explicit bans
 * @param cheatSheet         era/platform guidance: which enum and attribute names actually exist
 * @param threadingContract  which thread mod callbacks run on, and what that forbids
 * @param fewShots           worked (user, assistant) example pairs
 * @param pluginDescriptor   the exporter's descriptor template — {@code api-version} on Paper,
 *                           {@code fabric.mod.json} / {@code neoforge.mods.toml} on the loaders (§6.3)
 * @param iconInstruction    what a valid {@code "icon"} value is on this platform/era
 */
public record PlatformProfile(
        String id,
        String displayName,
        String roleLine,
        String apiSourceBlock,
        String importRules,
        String cheatSheet,
        String threadingContract,
        List<FewShot> fewShots,
        String pluginDescriptor,
        String iconInstruction) {

    /** One worked example pair shown to the model. */
    public record FewShot(String user, String assistant) {
    }

    public PlatformProfile {
        fewShots = List.copyOf(fewShots);
    }
}
