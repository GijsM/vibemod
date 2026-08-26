package com.gijsm.vibemod.fabric.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.gijsm.vibemod.loader.LoaderConfig;
import com.gijsm.vibemod.loader.content.LoaderModContent;
import com.gijsm.vibemod.loader.content.PackTree;
import com.gijsm.vibemod.loader.content.ServerResourceSink;

/**
 * A dedicated server's answer to {@code assets/**} (V4 Phase 3).
 *
 * <p>V3 stored a generated mod's textures, models and lang files on a dedicated
 * server and logged that they were inert, because a server cannot mount a
 * resource pack into a client's repository — it can only tell the client where
 * to fetch one. This is the "where": the same {@link PackTree} the client writes,
 * zipped deterministically, addressed by its own SHA-1, and published over
 * {@link PackHttp}. {@code FabricPackPush} does the telling.
 *
 * <p><b>Four modes, and the default is the cautious one.</b>
 * <ul>
 *   <li>{@code off} — nothing is served and nothing is pushed. Assets are still
 *       written to disk, so turning it on later needs no regeneration.</li>
 *   <li>{@code port} (default) — a standalone HTTP port, bound where the
 *       operator says. This is deliberately <em>not</em> Polymer AutoHost's
 *       default of sharing the game port: sharing works, but it puts an
 *       unauthenticated HTTP server on the game port of every server that
 *       installs VibeMod, and it breaks behind a proxy where the game port is
 *       not the port players connect to.</li>
 *   <li>{@code external} — VibeMod writes {@code <sha1>.zip} into
 *       {@code <datadir>/respack-served/} and binds nothing. An operator who
 *       already runs nginx, a CDN or an object store points it at that directory
 *       and sets {@code packserver.public-url} to wherever it lands.</li>
 *   <li>{@code shared} — <b>not implemented.</b> It is named here because it is
 *       the mode people will ask for by name and a config value that silently
 *       means something else is worse than one that refuses. Setting it logs
 *       what it would have meant and behaves as {@code off}.</li>
 * </ul>
 *
 * <p><b>The URL is the operator's to state, and nothing is pushed without it.</b>
 * {@code MinecraftServer.getServerIp()} is empty on almost every real
 * deployment, the machine's own interface address is wrong behind NAT, a proxy
 * or a container, and a wrong URL does not degrade — <em>every</em> client fails
 * the same download. So {@code packserver.public-url} is required, its absence
 * is a WARNING loud enough to find in a first boot log, and until it is set the
 * pack is built and served but never offered to anybody.
 */
public final class FabricPackServer implements ServerResourceSink {

    private static final Logger LOG = Logger.getLogger("VibeMod.PackServer");

    /** Config keys, spelled the way {@link LoaderConfig}'s others are. */
    public static final String KEY_MODE = "packserver.mode";
    public static final String KEY_PORT = "packserver.port";
    public static final String KEY_BIND = "packserver.bind";
    public static final String KEY_PUBLIC_URL = "packserver.public-url";
    public static final String KEY_REQUIRED = "packserver.required";
    public static final String KEY_PROMPT = "packserver.prompt";
    public static final String KEY_MAX_BYTES = "packserver.max-bytes";

    /** What an operator can put in {@link #KEY_MODE}. */
    public enum Mode { OFF, PORT, SHARED, EXTERNAL }

    /**
     * The live pack server, or null.
     *
     * <p>Static for the same reason {@code VibeModFabric}'s bridges are: the
     * Fabric configuration event that pushes the pack is a subscription that
     * cannot be undone, so it is made once per process and resolves the current
     * server when it fires, finding null between worlds.
     */
    private static volatile FabricPackServer current;

    private final PackTree tree;
    private final Mode mode;
    private final String publicBase;
    private final boolean required;
    private final String prompt;
    private final long maxBytes;
    /** Null in {@code external} mode, which binds nothing. */
    private final PackHttp http;
    /** Where {@code external} mode drops its zips; null otherwise. */
    private final Path servedDir;

    private volatile PackTree.Archive live;
    private volatile UUID previousId;

    private FabricPackServer(PackTree tree, Mode mode, String publicBase, boolean required,
                             String prompt, long maxBytes, PackHttp http, Path servedDir) {
        this.tree = tree;
        this.mode = mode;
        this.publicBase = publicBase;
        this.required = required;
        this.prompt = prompt;
        this.maxBytes = maxBytes;
        this.http = http;
        this.servedDir = servedDir;
    }

    /** The live pack server, or null on a client, with {@code mode=off}, or between worlds. */
    public static FabricPackServer current() {
        return current;
    }

    /**
     * Builds the pack server for this world, or returns null when this host has
     * no business running one.
     *
     * <p>Null on a physical client whatever the config says: the client already
     * has the tree mounted through {@code FabricClientPacks}, and hosting the
     * same files to itself over TCP would be two copies and a port for nothing.
     */
    public static FabricPackServer startIfEnabled(Path dataFolder, LoaderConfig config,
                                                  boolean dedicatedServer) {
        stopCurrent();
        if (!dedicatedServer) {
            return null;
        }
        Mode mode = parseMode(config.getString(KEY_MODE, "port"));
        if (mode == Mode.OFF) {
            LOG.info("Pack server is off (" + KEY_MODE + "=off); a mod's assets/ stay on disk and inert");
            return null;
        }
        if (mode == Mode.SHARED) {
            LOG.warning(KEY_MODE + "=shared is not implemented. It would share the Minecraft port by "
                    + "sniffing each connection's first bytes for an HTTP verb and rebuilding the Netty "
                    + "pipeline, the way Polymer AutoHost does — which also breaks behind a proxy, where "
                    + "the game port is not the port players connect to. Use `port` (a standalone port) "
                    + "or `external` (serve <datadir>/respack-served/ yourself). Behaving as `off`.");
            return null;
        }

        PackTree tree = new PackTree(dataFolder, "VibeMod generated mods", "the served pack");
        try {
            tree.reset();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not prepare the served resource pack directory", e);
            return null;
        }

        String publicBase = trimTrailingSlash(config.getString(KEY_PUBLIC_URL, "").trim());
        boolean required = config.getBoolean(KEY_REQUIRED, false);
        String prompt = config.getString(KEY_PROMPT, "").trim();
        long maxBytes = config.getLong(KEY_MAX_BYTES, 32L * 1024 * 1024);

        PackHttp http = null;
        Path servedDir = null;
        if (mode == Mode.PORT) {
            String bind = config.getString(KEY_BIND, "0.0.0.0");
            int port = config.getInt(KEY_PORT, 25569);
            try {
                http = PackHttp.start(bind, port);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not bind the pack server to " + bind + ":" + port
                        + "; a mod's assets/ will not reach anybody. Change " + KEY_PORT
                        + " or set " + KEY_MODE + "=off.", e);
                return null;
            }
        } else {
            servedDir = dataFolder.resolve("respack-served");
            try {
                LoaderModContent.deleteRecursively(servedDir);
                Files.createDirectories(servedDir);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not prepare " + servedDir, e);
                return null;
            }
            LOG.info(KEY_MODE + "=external: zips land in " + servedDir
                    + " and VibeMod binds nothing. Serve that directory at " + KEY_PUBLIC_URL + ".");
        }

        FabricPackServer server = new FabricPackServer(tree, mode, publicBase, required, prompt,
                maxBytes, http, servedDir);
        if (publicBase.isEmpty()) {
            server.warnAboutTheMissingUrl();
        }
        current = server;
        FabricPackPush.installOnce();
        return server;
    }

    /** Stops and forgets the live pack server. Safe to call when there is none. */
    public static void stopCurrent() {
        FabricPackServer server = current;
        current = null;
        if (server != null && server.http != null) {
            server.http.stop();
        }
    }

    // ------------------------------------------------------ ServerResourceSink

    @Override
    public boolean install(String modName, Map<String, String> assets) {
        return tree.install(modName, assets);
    }

    @Override
    public boolean remove(String modName) {
        return tree.remove(modName);
    }

    /**
     * Re-zips the tree and publishes the result.
     *
     * <p>Everything downstream of this is a pure function of the bytes: the
     * SHA-1 names the URL, and the UUID is derived from the SHA-1 so a client
     * that already holds this pack is holding it under the same id. A rebuild
     * that changed nothing therefore produces the same id, the same URL and the
     * same hash, and the push it eventually causes is one the client answers
     * from its own cache without a download.
     *
     * <p>Nobody is pushed to here. A mid-play {@code ClientboundResourcePackPushPacket}
     * costs a full client resource-stack reload — 2 to 30 seconds of frozen game,
     * MC-12257 — so the new pack sits at its URL and reaches players the next
     * time they pass through configuration.
     */
    @Override
    public void rebuild() {
        PackTree.Archive built = tree.archive();
        if (built == null) {
            retire();
            if (http != null) {
                http.clear();
            }
            LOG.info("The served resource pack is empty; nothing to publish");
            return;
        }
        if (built.bytes().length > maxBytes) {
            retire();
            LOG.warning("The built resource pack is " + built.bytes().length + " bytes, over the "
                    + maxBytes + "-byte " + KEY_MAX_BYTES + " cap; refusing to serve it. Raise the cap "
                    + "or ship smaller textures — every joining player pays for this download.");
            return;
        }
        PackTree.Archive previous = live;
        if (previous != null && !previous.sha1().equals(built.sha1())) {
            previousId = previous.uuid();
        }
        if (http != null) {
            http.serve(built.sha1(), built.bytes());
        } else if (servedDir != null && !writeExternal(built)) {
            return;
        }
        live = built;
        String url = urlOf(built);
        LOG.info("Serving " + built.files() + " asset file(s) as " + built.fileName()
                + " (" + built.bytes().length + " bytes) at "
                + (url == null ? "<no " + KEY_PUBLIC_URL + " set, so nothing is offered>" : url)
                + "; players receive it at their next join");
    }

    @Override
    public String describeDelivery() {
        String url = publicBase.isEmpty() ? "<no " + KEY_PUBLIC_URL + " set>" : publicBase + "/<sha1>.zip";
        return tree.root() + ", served at " + url;
    }

    @Override
    public String describeState() {
        PackTree.Archive built = live;
        return "packMode=" + mode.name().toLowerCase(Locale.ROOT)
                + " " + tree.describeState()
                + " packSha1=" + (built == null ? "-" : built.sha1())
                + (http == null ? "" : " " + http.describeState());
    }

    // ------------------------------------------------------------ the offer

    /**
     * What a connecting client should be told, or null when there is nothing to
     * tell it — an empty pack, an oversized one, or (the case that matters) a
     * server whose operator has not said what URL players can reach.
     */
    public Offer offer() {
        PackTree.Archive built = live;
        if (built == null) {
            return null;
        }
        String url = urlOf(built);
        if (url == null) {
            return null;
        }
        return new Offer(built.uuid(), url, built.sha1(), required, prompt);
    }

    /**
     * The pack this server pushed before the current one, or null.
     *
     * <p>Popped before the push so a client does not end up holding two VibeMod
     * packs stacked on each other, with the older one's models winning wherever
     * the newer one no longer defines them. It is a single slot rather than a
     * history because the id is content-derived: a client that has been away long
     * enough for two rebuilds has also been away long enough to have dropped the
     * pack at disconnect.
     */
    public UUID previousId() {
        return previousId;
    }

    /** Everything {@code ClientboundResourcePackPushPacket} needs, and nothing else. */
    public record Offer(UUID id, String url, String sha1, boolean required, String prompt) {
    }

    // ------------------------------------------------------------ internals

    /**
     * Stops offering whatever was current, remembering its id.
     *
     * <p>The remembering is the point. When the last mod with assets is removed
     * there is nothing to push, so there is also no push to hang a pop off — and
     * a client that rejoins would otherwise be handed a pop only once a *new*
     * pack exists. Holding the id here means the next push, whenever it comes,
     * still takes the stale one off first.
     */
    private void retire() {
        PackTree.Archive previous = live;
        if (previous != null) {
            previousId = previous.uuid();
        }
        live = null;
    }

    private String urlOf(PackTree.Archive built) {
        if (publicBase.isEmpty()) {
            return null;
        }
        return publicBase + "/" + built.fileName();
    }

    /**
     * {@code external} mode's whole implementation: write the zip where the
     * operator's own web server can find it, and delete the ones that are no
     * longer current.
     */
    private boolean writeExternal(PackTree.Archive built) {
        try {
            Files.write(servedDir.resolve(built.fileName()), built.bytes());
            try (var entries = Files.list(servedDir)) {
                for (Path stale : entries.toList()) {
                    if (!stale.getFileName().toString().equals(built.fileName())) {
                        Files.deleteIfExists(stale);
                    }
                }
            }
            return true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not write the served pack to " + servedDir, e);
            return false;
        }
    }

    /**
     * The one warning worth being loud about.
     *
     * <p>A missing URL is not a degraded pack server, it is a pack server nobody
     * can use, and the failure it would otherwise produce is every client failing
     * the same download with a message that names neither VibeMod nor the reason.
     * So it is stated once, at WARNING, with the exact line to add and the exact
     * consequence of not adding it.
     */
    private void warnAboutTheMissingUrl() {
        String suggestion = http == null
                ? "https://your.cdn.example/vibemod"
                : "http://your.server.address:" + http.port();
        LOG.warning("VibeMod's pack server is running but " + KEY_PUBLIC_URL + " is not set, so no "
                + "resource pack will be offered to anybody. There is no safe guess: getServerIp() is "
                + "empty on most servers and a machine's own address is wrong behind NAT, a container or "
                + "a proxy — and a wrong URL fails for EVERY player, not some. Set it in "
                + "config/vibemod.json, e.g. \"" + KEY_PUBLIC_URL + "\": \"" + suggestion + "\", to the "
                + "address players can actually reach. Assets are still written to " + tree.root() + ".");
    }

    private static Mode parseMode(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "off", "false", "none" -> Mode.OFF;
            case "shared" -> Mode.SHARED;
            case "external" -> Mode.EXTERNAL;
            case "port", "", "true" -> Mode.PORT;
            default -> {
                LOG.warning("Unknown " + KEY_MODE + "=" + raw
                        + "; expected off | port | shared | external. Using port.");
                yield Mode.PORT;
            }
        };
    }

    private static String trimTrailingSlash(String url) {
        String value = url;
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
