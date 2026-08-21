package com.gijsm.vibemine.gen;

import java.util.List;

/**
 * The parsed result of one LLM generation round.
 *
 * Two response shapes exist. A full project carries {@code files} (complete
 * sources) plus the descriptive fields. An edit response — allowed on repair
 * and edit rounds only — carries {@code edits} (exact search/replace blocks
 * against the current sources) and may omit any descriptive field to mean
 * "unchanged". {@code usage}, {@code manual}, {@code icon} (a Bukkit Material
 * item name the GUI displays the mod as) and {@code config} are optional
 * in both shapes (null/empty = absent), so v1-shaped responses still parse.
 *
 * Contract enforced by the prompt: every file lives in package
 * {@code vibemod.<lowercased mod name>}, file paths are simple names like
 * {@code ChickenCreepers.java}, and exactly one public class implements
 * {@link com.gijsm.vibemine.api.Mod} and is named {@code mainClass}.
 */
public record GeneratedProject(String name, String description, String usage, String manual,
                               String icon, String mainClass, List<GeneratedFile> files,
                               List<ConfigKnob> config, List<EditBlock> edits) {

    public record GeneratedFile(String path, String content) {
    }

    /**
     * A tunable setting the mod exposes. {@code type} is one of
     * {@code boolean | integer | decimal | text | choice}; {@code def} is the
     * default value as a string; {@code min}/{@code max}/{@code step} apply to
     * numeric types (nullable), {@code choices} to the choice type (nullable).
     */
    public record ConfigKnob(String key, String type, String def, String description,
                             Double min, Double max, Double step, List<String> choices) {
    }

    /**
     * One surgical source edit: {@code find} must occur exactly once in the
     * file at {@code path}; it is replaced by {@code replace} verbatim.
     */
    public record EditBlock(String path, String find, String replace) {
    }

    /** True when this is an edit response rather than a full project. */
    public boolean isEditResponse() {
        return edits != null && !edits.isEmpty();
    }
}
