package com.gijsm.vibemod.paper;

import com.gijsm.vibemod.platform.TickScheduler;

/**
 * A {@link TickScheduler} whose handles can also be presented as
 * {@code BukkitTask}s.
 *
 * <p>Two implementations, chosen at boot by
 * {@link PaperPlatformInfo#isRegionised()}:
 *
 * <ul>
 *   <li>{@link PaperTickScheduler} — {@code Bukkit.getScheduler()}, the ordinary
 *       Bukkit scheduler, on Paper and every non-regionised fork.
 *   <li>{@link FoliaTickScheduler} — the global region scheduler and the async
 *       scheduler, on Folia, where {@code Bukkit.getScheduler()} throws
 *       {@link UnsupportedOperationException} the moment it is touched.
 * </ul>
 *
 * <p>This interface is the seam that keeps {@link PaperModHost} from naming
 * either one. It deliberately mentions no {@code io.papermc.paper.threadedregions}
 * type, so a 1.20.6 server — which has never heard of those classes — can load
 * and verify it. {@link FoliaTickScheduler} is the only class that references
 * them, and it is instantiated only when the probe says the server is regionised,
 * so on Paper it is never loaded at all.
 */
public interface BukkitTaskScheduler extends TickScheduler {

    @Override
    BukkitTaskHandle repeat(long delayTicks, long periodTicks, Runnable task);

    @Override
    BukkitTaskHandle later(long delayTicks, Runnable task);

    @Override
    BukkitTaskHandle async(Runnable task);
}
