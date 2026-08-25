package com.gijsm.vibemod.platform.ui;

/**
 * Submitted input values, keyed by {@link Input#key()}. Values are what the
 * client claims — callbacks MUST re-validate (clamp numbers, check choice
 * membership) before acting; renderers guarantee only type shape, not range.
 * Accessors return {@code null} when the screen had no such input.
 */
public interface UiResponse {

    String text(String key);

    Boolean bool(String key);

    Double number(String key);
}
