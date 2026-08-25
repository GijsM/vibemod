package com.gijsm.vibemod.runtime;

/**
 * Thrown when a mod fails to load or (re)enable.
 *
 * <p>{@link #where()} names the lifecycle step that failed, and is the
 * {@code where} dimension the error journal records — {@code null} when the
 * failure is not worth journalling (a mod that could not even be instantiated
 * has no runtime history to explain).
 */
public final class ModLoadException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String where;

    public ModLoadException(String msg, Throwable cause) {
        this(msg, cause, null);
    }

    public ModLoadException(String msg, Throwable cause, String where) {
        super(msg, cause);
        this.where = where;
    }

    /** The lifecycle step that failed ({@code "onEnable"}), or null when not journalled. */
    public String where() {
        return where;
    }
}
