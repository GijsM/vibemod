package com.gijsm.vibemod.loader.content;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;

/**
 * One resource reload per batch of changes, never one per mod (V3 Phase 2 §C).
 *
 * <p>A datapack reload costs 200ms–2s on the server thread and a client
 * resource reload puts a progress overlay on the player's screen. Restoring
 * eight mods at boot must therefore not cost eight of either, and a mod's
 * teardown must not pay for one at all — the watchdog gives a registration's
 * {@code close()} 250ms, and {@code MinecraftServer#reloadResources} called on
 * the server thread <em>managed-blocks until the reload finishes</em> (verified
 * by disassembly: it calls {@code managedBlock} when {@code isSameThread()}).
 *
 * <p>So nothing reloads synchronously. A change marks a side dirty, which arms
 * a {@value #DEBOUNCE_TICKS}-tick timer; every further change re-arms it; when
 * it expires exactly one reload runs. A side already reloading never starts a
 * second one — it re-arms instead, so a change that lands mid-reload is not
 * lost.
 *
 * <p>Ticked from the host's existing {@code END_SERVER_TICK} subscription, so
 * it inherits that subscription's "null between worlds" lifetime and needs no
 * loader event of its own.
 */
public final class ReloadCoordinator {

    private static final Logger LOG = Logger.getLogger("VibeMod.Reload");

    /** Two seconds. Long enough to swallow a whole boot restore, short enough to feel immediate. */
    public static final int DEBOUNCE_TICKS = 40;

    private final MinecraftServer server;
    /** Null on a dedicated server: there is no client repository to reload. */
    private final ClientReloader clientReloader;
    /**
     * The pack server (V4 Phase 3), or null when there is none.
     *
     * <p>Shares the client channel rather than getting a third one, because it
     * is the same event with a different delivery: a mod's {@code assets/**}
     * changed, and something has to rebuild. On a client that something is a
     * resource reload; on a dedicated server it is a re-zip. Both cost real work
     * per call and both are triggered by the same eight-mod boot restore, so
     * both want the same {@value #DEBOUNCE_TICKS}-tick coalescing — and giving
     * the pack server its own timer would mean two timers that always fire
     * together and can never both be non-null.
     */
    private final ServerResourceSink serverSink;

    /**
     * World datapack folder names VibeMod currently owns. Folder names rather
     * than pack ids because the id is vanilla's to derive — it is
     * {@code "file/<folder>"} today, and the flush resolves it against the
     * repository rather than hardcoding that.
     */
    private final Set<String> ownedPacks = ConcurrentHashMap.newKeySet();

    private boolean serverDirty;
    private boolean clientDirty;
    private int serverTimer;
    private int clientTimer;
    private volatile boolean serverReloading;
    private volatile boolean clientReloading;
    private int serverReloads;
    private int clientReloads;
    private String lastReason = "-";

    public ReloadCoordinator(MinecraftServer server, ClientReloader clientReloader) {
        this(server, clientReloader, null);
    }

    public ReloadCoordinator(MinecraftServer server, ClientReloader clientReloader,
                             ServerResourceSink serverSink) {
        this.server = server;
        this.clientReloader = clientReloader;
        this.serverSink = serverSink;
    }

    /** True when this host can reload the client's resource packs at all. */
    public boolean hasClient() {
        return clientReloader != null;
    }

    /** True when this host publishes {@code assets/**} to connecting clients (V4 Phase 3). */
    public boolean hasPackServer() {
        return serverSink != null;
    }

    /** Records that VibeMod owns the world datapack folder {@code folderName}. */
    public void ownPack(String folderName) {
        ownedPacks.add(folderName);
    }

    /** Forgets a datapack folder VibeMod no longer owns. */
    public void disownPack(String folderName) {
        ownedPacks.remove(folderName);
    }

    /** Arms (or re-arms) the server-side datapack reload. */
    public synchronized void markServerDirty(String reason) {
        serverDirty = true;
        serverTimer = DEBOUNCE_TICKS;
        lastReason = reason;
    }

    /**
     * Arms (or re-arms) whatever this host does when {@code assets/**} change:
     * a client resource reload, or a pack-server re-zip (V4 Phase 3). No-op on a
     * host with neither, which is a dedicated server running
     * {@code packserver.mode=off}.
     */
    public synchronized void markClientDirty(String reason) {
        if (clientReloader == null && serverSink == null) {
            return;
        }
        clientDirty = true;
        clientTimer = DEBOUNCE_TICKS;
        lastReason = reason;
    }

    /** One tick of both debounce timers. Called from {@code END_SERVER_TICK}. */
    public void tick() {
        boolean flushServer = false;
        boolean flushClient = false;
        synchronized (this) {
            if (serverDirty && !serverReloading && --serverTimer <= 0) {
                serverDirty = false;
                serverReloading = true;
                flushServer = true;
            }
            if (clientDirty && !clientReloading && --clientTimer <= 0) {
                clientDirty = false;
                clientReloading = true;
                flushClient = true;
            }
        }
        if (flushServer) {
            flushServer();
        }
        if (flushClient) {
            flushClient();
        }
    }

    /**
     * State for logs and gates, e.g.
     * {@code "serverReloads=1 clientReloads=0 serverDirty=false clientDirty=false
     * serverPending=0 clientPending=0 ownedPacks=1 lastReload=ResourceCanary loaded"}.
     */
    public synchronized String describeState() {
        return "serverReloads=" + serverReloads
                + " clientReloads=" + clientReloads
                + " serverDirty=" + serverDirty
                + " clientDirty=" + clientDirty
                + " serverPending=" + Math.max(0, serverDirty ? serverTimer : 0)
                + " clientPending=" + Math.max(0, clientDirty ? clientTimer : 0)
                + " ownedPacks=" + ownedPacks.size()
                + (serverSink == null ? "" : " " + serverSink.describeState())
                + " lastReload=" + lastReason;
    }

    // ------------------------------------------------------------------ server

    /**
     * Vanilla's own {@code /reload}, replayed: reload the repository, keep
     * everything currently selected, adopt every newly-discovered pack the world
     * has not explicitly disabled, and hand the lot to
     * {@code MinecraftServer#reloadResources}.
     *
     * <p>The last step is what removes a deleted pack from {@code level.dat}:
     * {@code reloadResources} writes the resulting selection back through
     * {@code WorldData#setDataConfiguration}, so a pack whose folder is gone
     * stops being remembered and the world stops warning about it forever.
     */
    private void flushServer() {
        long startedAt = System.nanoTime();
        List<String> ids;
        try {
            PackRepository repo = server.getPackRepository();
            repo.reload();
            ids = new ArrayList<>(repo.getSelectedIds());
            Collection<String> disabled =
                    server.getWorldData().getDataConfiguration().dataPacks().getDisabled();
            for (String id : repo.getAvailableIds()) {
                if (!disabled.contains(id) && !ids.contains(id)) {
                    ids.add(id);
                }
            }
            // Belt and braces on top of vanilla's auto-adopt: ours go in by name
            // whatever the world's disabled list says, because a live mod whose
            // recipes are switched off is a bug report nobody can diagnose.
            for (String folder : ownedPacks) {
                String id = idFor(repo, folder);
                if (id != null && !ids.contains(id)) {
                    ids.add(id);
                }
            }
            // And the other direction: a pack we just deleted must not be handed
            // back, or level.dat keeps remembering an id with nothing behind it.
            ids.removeIf(id -> !repo.isAvailable(id));
        } catch (Throwable t) {
            serverReloading = false;
            LOG.log(Level.WARNING, "Could not prepare a datapack reload", t);
            return;
        }

        LOG.info("Reloading server data (" + lastReason + "), " + ids.size() + " pack(s) selected");
        try {
            server.reloadResources(ids).whenComplete((ignored, failure) -> {
                synchronized (this) {
                    serverReloading = false;
                    serverReloads++;
                    if (serverDirty) {
                        serverTimer = DEBOUNCE_TICKS;
                    }
                }
                if (failure != null) {
                    LOG.log(Level.WARNING, "Datapack reload failed", failure);
                } else {
                    LOG.info("Server data reloaded in "
                            + ((System.nanoTime() - startedAt) / 1_000_000L) + "ms");
                }
            });
        } catch (Throwable t) {
            synchronized (this) {
                serverReloading = false;
            }
            LOG.log(Level.WARNING, "Datapack reload threw", t);
        }
    }

    /**
     * The pack id vanilla derived for a world datapack folder.
     *
     * <p>{@code "file/" + folder} today (read off {@code FolderRepositorySource}'s
     * string-concat bootstrap), but asked of the repository rather than assumed,
     * so a version that changes the prefix changes nothing here.
     */
    private static String idFor(PackRepository repo, String folder) {
        String expected = "file/" + folder;
        for (String id : repo.getAvailableIds()) {
            if (id.equals(expected) || id.equals(folder) || id.endsWith("/" + folder)) {
                return id;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ client

    /**
     * The debounced answer to "a mod's assets changed".
     *
     * <p>On a client that is a resource reload. On a dedicated server it is the
     * pack server rebuilding its zip — which is where V3's early return used to
     * be, and the early return was the whole reason `assets/**` were inert
     * there. The rebuild deliberately does <em>not</em> push to anybody who is
     * already playing: a mid-play push is a full client resource-stack reload,
     * 2 to 30 seconds of frozen game (MC-12257), for a texture that will be
     * there next time they join anyway.
     */
    private void flushClient() {
        ClientReloader reloader = clientReloader;
        if (reloader == null) {
            ServerResourceSink sink = serverSink;
            if (sink != null) {
                LOG.info("Rebuilding the served resource pack (" + lastReason + ")");
                try {
                    sink.rebuild();
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, "Rebuilding the served resource pack threw", t);
                }
            }
            synchronized (this) {
                clientReloading = false;
                clientReloads++;
                if (clientDirty) {
                    clientTimer = DEBOUNCE_TICKS;
                }
            }
            return;
        }
        LOG.info("Reloading client resources (" + lastReason + ")");
        try {
            reloader.reload(() -> {
                synchronized (this) {
                    clientReloading = false;
                    clientReloads++;
                    if (clientDirty) {
                        clientTimer = DEBOUNCE_TICKS;
                    }
                }
            });
        } catch (Throwable t) {
            synchronized (this) {
                clientReloading = false;
            }
            LOG.log(Level.WARNING, "Client resource reload threw", t);
        }
    }
}
