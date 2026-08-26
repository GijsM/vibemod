package com.gijsm.vibemod.loader.content;

import java.util.Map;

/**
 * Where a mod's {@code assets/**} go on a <em>dedicated</em> server (V4 Phase 3).
 *
 * <p>{@link ClientResourceSink}'s counterpart, and deliberately not the same
 * interface. The client sink's contract is "put these files where the running
 * client's {@code PackRepository} can see them"; this one's is "put them where a
 * connecting client can <em>download</em> them", which needs a third method the
 * client half has no use for: {@link #rebuild()}, the moment a tree of loose
 * files becomes one addressable zip with a hash.
 *
 * <p>An interface for the same reason {@link ClientReloader} is one, mirrored:
 * this file is compiled into both loader jars, and the implementation
 * ({@code FabricPackServer}) names Fabric networking types that only one of them
 * has. {@link LoaderModContent} and {@link ReloadCoordinator} hold this, so
 * neither has to know whether the pack goes out over HTTP or into a repository
 * two feet away.
 *
 * <p>{@link #install} and {@link #remove} run on the server thread and do file
 * I/O only. {@link #rebuild} does the zip and the hash, and is called from the
 * coordinator's debounce rather than per mod, because zipping the whole tree
 * eight times to restore eight mods is exactly the cost that debounce exists to
 * refuse.
 */
public interface ServerResourceSink {

    /**
     * Writes {@code assets} (relative path -> text, {@code .png.grid} entries
     * still in grid form) into the served tree under {@code modName}. Returns
     * true when the tree's contents actually changed.
     */
    boolean install(String modName, Map<String, String> assets);

    /** Removes everything {@code modName} put in the tree. True when something went. */
    boolean remove(String modName);

    /**
     * Re-zips the tree deterministically and starts serving the result.
     *
     * <p>Never pushes to anybody. A mid-play {@code ClientboundResourcePackPushPacket}
     * costs the player a full resource-stack reload — 2 to 30 seconds of frozen
     * client, MC-12257 — so the new pack waits at its URL and reaches players at
     * the next configuration phase they go through.
     */
    void rebuild();

    /**
     * Where a mod's assets went and where they will be served from, for the one
     * log line {@link LoaderModContent} writes per mod.
     */
    String describeDelivery();

    /** One line for logs and gates, e.g. {@code "packServer=port respackFiles=7 served=ab12…"}. */
    String describeState();
}
