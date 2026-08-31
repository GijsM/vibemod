package com.gijsm.vibemod.loader.content;

import java.util.Map;

/**
 * Where a mod's {@code assets/**} go on a physical client (V3 Phase 2 §D).
 *
 * <p>The implementation ({@code FabricClientPacks}) owns one runtime resource
 * pack under {@code <gamedir>/vibemod/respack/} and merges every live mod into
 * it. It is reached through this interface for the same reason
 * {@link ClientReloader} is: {@code loader-common} must stay loadable on a
 * dedicated server, where there is no {@code PackRepository} to put a pack in.
 *
 * <p>Both methods run on the server thread and do file I/O only — joining the
 * repository and reloading are the coordinator's job.
 */
public interface ClientResourceSink {

    /**
     * Writes {@code assets} (relative path -> text, {@code .png.grid} entries
     * still in grid form) into the runtime pack under {@code modName}. Returns
     * true when the pack's contents actually changed.
     */
    boolean install(String modName, Map<String, String> assets);

    /** Removes everything {@code modName} put in the pack. True when something went. */
    boolean remove(String modName);

    /** One line for logs and gates, e.g. {@code "respackMods=2 respackFiles=7"}. */
    String describeState();
}
