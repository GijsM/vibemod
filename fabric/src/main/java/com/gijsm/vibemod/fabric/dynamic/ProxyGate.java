package com.gijsm.vibemod.fabric.dynamic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Mid-play reconfiguration is disabled outright behind a proxy — a gate, not a
 * warning (V4 Phase 5).
 *
 * <h2>Why this is a hard gate</h2>
 *
 * <p>Reconfiguring a player mid-play is broken behind both mainstream proxies,
 * and both bugs are open:
 *
 * <ul>
 *   <li><b>Velocity — PaperMC/Velocity#1723</b>, open since 2026-01-31. Queued
 *       play packets are flushed when the connection changes state and arrive at
 *       the backend while it is still in the configuration phase.</li>
 *   <li><b>BungeeCord — SpigotMC/BungeeCord#3527</b>, the same class of failure.</li>
 * </ul>
 *
 * <p>The issue numbers are in the operator log line on purpose: this gate should
 * be deleted the day either is fixed, and an operator who reads
 * "reconfiguration disabled" deserves to be able to check for themselves rather
 * than take VibeMod's word for it in perpetuity.
 *
 * <p>The failure mode if the gate is wrong in the permissive direction is a
 * player kicked with a protocol error, and the failure mode if it is wrong in the
 * restrictive direction is that content lands on their next join instead of
 * twenty seconds from now. Those are not symmetric, so <b>any</b> signal closes
 * the gate, and {@code auto} is the default.
 *
 * <h2>The signals</h2>
 *
 * <p>Five, and Fabric makes the strongest of them nearly sufficient on its own:
 * Fabric has no built-in proxy forwarding, so Velocity modern forwarding and
 * BungeeCord legacy forwarding both require a third-party mod to work at all. A
 * server with none of those mods installed is not behind a forwarding proxy,
 * whatever else is true of it.
 *
 * <ol>
 *   <li>An explicit {@link Mode} from config. {@code AUTO} is the default;
 *       {@code ASSUME_PROXY} closes the gate unconditionally and is what an
 *       operator sets when their setup is exotic; {@code NEVER_PROXY} opens it
 *       unconditionally and is a deliberate, logged override.</li>
 *   <li>A proxy-forwarding mod on the loader.</li>
 *   <li>A forwarding secret on disk or in the environment — Velocity's own
 *       {@code forwarding.secret}, or a {@code secret} line in FabricProxy-Lite's
 *       config.</li>
 *   <li>{@code online-mode=false} in {@code server.properties} on a dedicated
 *       server, which every proxy setup requires. Read only on a dedicated
 *       server, because on an integrated one it means nothing.</li>
 *   <li>A client brand or handshake host reported in from wherever the packet is
 *       seen — {@link #noteClientBrand} and {@link #noteHandshakeHost}. A
 *       BungeeCord legacy-forwarding handshake carries NUL-separated fields in
 *       the host name, which is unmistakable.</li>
 * </ol>
 *
 * <p>The last one is an entry point rather than a listener because reading the
 * brand payload belongs to whichever class owns the connection, not to the gate.
 * The gate's job is to decide, and to say why.
 */
public final class ProxyGate {

    private static final Logger LOG = Logger.getLogger("VibeMod.Dynamic");

    /** Both issue numbers, in every operator-facing line, so they can be checked. */
    public static final String ISSUES =
            "PaperMC/Velocity#1723 (open since 2026-01-31) and SpigotMC/BungeeCord#3527";

    /** What the operator asked for. */
    public enum Mode {
        /** Decide from the signals below. The default, and the only safe default. */
        AUTO,
        /** Never bounce, whatever the signals say. */
        ASSUME_PROXY,
        /**
         * Bounce even though a signal fired. A deliberate override, logged as
         * one, for an operator who knows their proxy is patched.
         */
        NEVER_PROXY
    }

    /**
     * Mods that implement proxy forwarding on Fabric.
     *
     * <p>Fabric ships none of this itself, so the presence of one of these is the
     * closest thing to a positive answer available without reading a packet.
     */
    private static final List<String> FORWARDING_MODS = List.of(
            "fabricproxy-lite", "fabricproxy", "crossstitch", "proxy-lite", "velocity-forwarding");

    /** Client brands that name a proxy or a proxy-side translator. */
    private static final List<String> PROXY_BRANDS = List.of(
            "velocity", "bungeecord", "waterfall", "geyser", "floodgate", "hexacord", "travertine");

    private final Mode mode;
    private final Path gameDir;
    private final Path configDir;
    private final boolean dedicated;

    /** Signals reported in from a connection, e.g. a client brand. Session-lived. */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    /** Cached decision and its reasons; computed once and logged once. */
    private volatile Set<String> reasons;

    public ProxyGate(Mode mode, Path gameDir, Path configDir, boolean dedicated) {
        this.mode = mode;
        this.gameDir = gameDir;
        this.configDir = configDir;
        this.dedicated = dedicated;
    }

    /**
     * True when mid-play reconfiguration must not happen on this server.
     *
     * <p>Evaluated on demand rather than at boot, because two of the signals —
     * the client brand and the handshake host — can only arrive once somebody
     * connects, and a gate that had already made up its mind would miss them.
     */
    public boolean blocked() {
        Set<String> found = evaluate();
        if (mode == Mode.NEVER_PROXY) {
            if (!found.isEmpty()) {
                LOG.warning("Proxy signals are present (" + String.join("; ", found)
                        + ") but dynamic.proxy=never-proxy, so mid-play reconfiguration is enabled"
                        + " anyway. If players are kicked with a protocol error during a bounce, this"
                        + " setting is why: " + ISSUES);
            }
            return false;
        }
        return !found.isEmpty();
    }

    /** Why the gate is closed, or an empty set when it is open. */
    public Set<String> reasons() {
        return Set.copyOf(evaluate());
    }

    /** One line for the operator log, naming the signals and both issue numbers. */
    public String describeBlock() {
        return "mid-play reconfiguration is disabled on this server ("
                + String.join("; ", evaluate()) + "). New dynamic content applies on a player's next"
                + " join instead. Reconfiguring a player through a proxy is broken upstream: " + ISSUES
                + ". Set dynamic.proxy=never-proxy to override once those are fixed.";
    }

    /**
     * Reports a client's brand string. Any proxy-shaped brand closes the gate for
     * the rest of the session.
     */
    public void noteClientBrand(UUID player, String brand) {
        if (brand == null || brand.isBlank()) {
            return;
        }
        String lower = brand.toLowerCase(Locale.ROOT);
        for (String name : PROXY_BRANDS) {
            if (lower.contains(name)) {
                if (reported.add("client brand of " + player + " names " + name)) {
                    reasons = null;
                    LOG.warning("Client brand \"" + brand + "\" names a proxy; disabling mid-play"
                            + " reconfiguration for this session. " + ISSUES);
                }
                return;
            }
        }
    }

    /**
     * Reports the handshake host name.
     *
     * <p>BungeeCord legacy forwarding rewrites it to
     * {@code host\0ip\0uuid\0properties}, so a NUL in the host is not ambiguous —
     * a vanilla client cannot produce one.
     */
    public void noteHandshakeHost(String host) {
        if (host == null || host.indexOf('\0') < 0) {
            return;
        }
        if (reported.add("the handshake host name carries NUL-separated forwarding fields")) {
            reasons = null;
            LOG.warning("A handshake carried BungeeCord-shaped forwarded fields; disabling mid-play"
                    + " reconfiguration for this session. " + ISSUES);
        }
    }

    private Set<String> evaluate() {
        Set<String> cached = reasons;
        if (cached != null) {
            return cached;
        }
        Set<String> found = new LinkedHashSet<>();
        if (mode == Mode.ASSUME_PROXY) {
            found.add("dynamic.proxy=assume-proxy");
        }
        for (String modId : FORWARDING_MODS) {
            if (isModLoaded(modId)) {
                found.add("the proxy-forwarding mod " + modId + " is installed");
            }
        }
        if (hasForwardingSecret()) {
            found.add("a proxy forwarding secret is configured");
        }
        if (dedicated && offlineMode()) {
            found.add("server.properties has online-mode=false, which every proxy setup requires");
        }
        found.addAll(reported);
        reasons = found;
        return found;
    }

    private static boolean isModLoaded(String modId) {
        try {
            return FabricLoader.getInstance().isModLoaded(modId);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean hasForwardingSecret() {
        if (System.getenv("VELOCITY_FORWARDING_SECRET") != null) {
            return true;
        }
        if (gameDir != null && Files.isRegularFile(gameDir.resolve("forwarding.secret"))) {
            return true;
        }
        if (configDir == null) {
            return false;
        }
        for (String name : List.of("FabricProxy-Lite.toml", "fabricproxy-lite.toml")) {
            Path file = configDir.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String body = Files.readString(file, StandardCharsets.UTF_8);
                for (String line : body.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("secret") && trimmed.contains("=")
                            && trimmed.length() > trimmed.indexOf('=') + 3) {
                        return true;
                    }
                }
            } catch (IOException ignored) {
                // Unreadable is not a signal; it is just unreadable.
            }
        }
        return false;
    }

    private boolean offlineMode() {
        if (gameDir == null) {
            return false;
        }
        Path properties = gameDir.resolve("server.properties");
        if (!Files.isRegularFile(properties)) {
            return false;
        }
        try {
            for (String line : Files.readString(properties, StandardCharsets.UTF_8).split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("online-mode")) {
                    return trimmed.endsWith("false");
                }
            }
        } catch (IOException ignored) {
            // Same as above.
        }
        return false;
    }

    /** e.g. {@code "proxyGate=open proxySignals=0"}. */
    public String describeState() {
        Set<String> found = evaluate();
        boolean open = mode == Mode.NEVER_PROXY || found.isEmpty();
        return "proxyGate=" + (open ? "open" : "closed") + " proxySignals=" + found.size();
    }
}
