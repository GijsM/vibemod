package com.gijsm.vibemod.platform.ui;

/**
 * What a button does.
 *
 * <ul>
 *   <li>{@code RunCommand} — the player runs a real command (stateless
 *       navigation: permissions re-checked, data re-fetched by the command).
 *       The preferred action; use callbacks only when a command round-trip
 *       cannot express it (form submits, in-place drill-downs).</li>
 *   <li>{@code Submit} — invoke the callback with the screen's input values.</li>
 *   <li>{@code Callback} — invoke the callback; the response carries no values.</li>
 * </ul>
 */
public sealed interface UiAction {

    record RunCommand(String command) implements UiAction {
    }

    record Submit(UiCallback callback) implements UiAction {
    }

    record Callback(UiCallback callback) implements UiAction {
    }
}
