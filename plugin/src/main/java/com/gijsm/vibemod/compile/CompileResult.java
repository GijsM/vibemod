package com.gijsm.vibemod.compile;

import java.util.Map;

/**
 * Outcome of an in-memory compilation. On success, {@code classes} maps every
 * compiled binary class name (including inner classes) to its bytecode and
 * {@code diagnostics} holds warnings if any. On failure, {@code classes} is
 * empty and {@code diagnostics} holds the javac error output verbatim.
 */
public record CompileResult(boolean success, Map<String, byte[]> classes, String diagnostics) {
}
