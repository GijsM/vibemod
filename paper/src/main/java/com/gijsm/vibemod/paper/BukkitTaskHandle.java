package com.gijsm.vibemod.paper;

import org.bukkit.scheduler.BukkitTask;

import com.gijsm.vibemod.platform.TaskHandle;

/**
 * A {@link TaskHandle} that can also be shown to a generated mod as a
 * {@link BukkitTask}.
 *
 * <p>This interface exists for exactly one reason: {@code VibeContext.repeat}
 * and {@code VibeContext.later} are frozen v3 signatures that return
 * {@code org.bukkit.scheduler.BukkitTask}, and the stored corpus compiles
 * against them. Measured, not assumed: 36 call sites across 6 of the 49 stored
 * mods capture that return value, and <em>every</em> holder is explicitly
 * {@code BukkitTask}-typed — 26 as the {@code BukkitTask[1]} self-reference
 * array trick, 9 as plain locals, 1 as a {@code List<BukkitTask>} element, plus
 * 3 fields and 4 record components downstream. Not one uses {@code var}. So
 * widening the return type to {@link TaskHandle} is a compile error at every one
 * of them, and "the stored corpus keeps compiling" is a hard invariant.
 *
 * <p>Core never sees this type — it only ever holds a {@link TaskHandle}. Only
 * {@link PaperModHost}, which is already Bukkit-shaped by construction, calls
 * {@link #task()}.
 *
 * @see FoliaTickScheduler.FoliaTaskHandle for the implementation that has no
 *      real {@code BukkitTask} to hand over and must synthesise one
 */
public interface BukkitTaskHandle extends TaskHandle {

    /** The task, as the sdk's frozen {@code BukkitTask}-returning signatures require. */
    BukkitTask task();
}
