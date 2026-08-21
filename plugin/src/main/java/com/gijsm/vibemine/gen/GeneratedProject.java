package com.gijsm.vibemine.gen;

import java.util.List;

/**
 * The parsed result of one LLM generation round: the mod's name, a one-line
 * description, and its Java source files.
 *
 * Contract enforced by the prompt: every file lives in package
 * {@code vibemod.<lowercased mod name>}, file paths are simple names like
 * {@code ChickenCreepers.java}, and exactly one public class implements
 * {@link com.gijsm.vibemine.api.VibeMod} and is named {@code mainClass}.
 */
public record GeneratedProject(String name, String description, String mainClass, List<GeneratedFile> files) {

    public record GeneratedFile(String path, String content) {
    }
}
