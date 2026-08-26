package com.gijsm.vibemod.fabric.pack;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The smallest HTTP server that can hand a Minecraft client a resource pack
 * (V4 Phase 3).
 *
 * <p>{@code com.sun.net.httpserver} rather than a dependency, because the JDK
 * has shipped one since 6 and adding Netty routing or a web framework to a mod
 * that already embeds a Java compiler is not a trade worth making.
 *
 * <p><b>It serves from a map, never from the file system.</b> The only thing a
 * request path can do is select a key: {@code /<40 hex>.zip} and nothing else,
 * looked up in {@link #served}, which holds byte arrays this process built. No
 * request can name a file, so there is no traversal to defend against, no
 * symlink to follow, no directory to list and no way to reach a pack that is not
 * currently published. That is a structural property rather than a validated
 * one — the difference between "we checked for {@code ../}" and "there is no
 * path to escape from". VibeMod's surface is already "an LLM writes code that
 * runs on your server"; the file server it ships must not be the second thing on
 * that list.
 *
 * <p>Two packs are held at once, current and previous. A rebuild while somebody
 * is halfway through downloading the old one must not 404 them mid-stream, and
 * two is enough: by the time a third exists, the first download has either
 * finished or timed out long ago.
 *
 * <p><b>Deliberately not the Minecraft port.</b> Polymer's AutoHost shares the
 * game port by sniffing the first bytes of each connection for an HTTP verb and
 * rebuilding the Netty pipeline underneath it. It works. It also puts an
 * unauthenticated HTTP server on the game port of every server that installs the
 * mod, and it breaks behind a proxy, where the port players connect to is not
 * the port the game is listening on. A separate, explicitly configured port is
 * the boring answer and the one an operator can firewall.
 */
public final class PackHttp {

    private static final Logger LOG = Logger.getLogger("VibeMod.PackServer");

    /**
     * {@code /<sha1>.zip}, and nothing that is not exactly that.
     *
     * <p>Written as an explicit character check rather than a regex so that the
     * accepted language is visible in one read: 40 lowercase hex digits, then
     * the literal suffix. A path with a segment, an encoded slash, a query
     * string or an uppercase digit does not match and gets a 404 with no
     * further thought.
     */
    private static final int SHA1_HEX_LENGTH = 40;

    private final HttpServer http;
    private final ExecutorService workers;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    /** sha1 -> pack bytes. Insertion-ordered so the oldest is the one evicted. */
    private final Map<String, byte[]> served = new LinkedHashMap<>();

    private PackHttp(HttpServer http, ExecutorService workers) {
        this.http = http;
        this.workers = workers;
    }

    /**
     * Binds and starts. Throws {@link IOException} when the port is taken or the
     * bind address is not ours — which the caller reports rather than swallows,
     * because a pack server that silently did not start is a server whose
     * players all fail the same download for a reason nobody can see.
     */
    public static PackHttp start(String bindHost, int port) throws IOException {
        InetSocketAddress address = bindHost == null || bindHost.isBlank()
                ? new InetSocketAddress(port)
                : new InetSocketAddress(bindHost, port);
        HttpServer http = HttpServer.create(address, 0);
        // Two threads: a resource pack download is one large sequential write,
        // and joins arrive one at a time even on a busy server. The threads are
        // daemons so a stuck download can never keep the JVM alive past a stop.
        ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "VibeMod pack server");
            thread.setDaemon(true);
            return thread;
        });
        PackHttp server = new PackHttp(http, workers);
        http.createContext("/", server::handle);
        http.setExecutor(workers);
        http.start();
        LOG.info("Pack server listening on " + http.getAddress());
        return server;
    }

    /** The port actually bound, which is the configured one unless it was 0. */
    public int port() {
        return http.getAddress().getPort();
    }

    /**
     * Publishes a pack and retires all but the one it replaced.
     *
     * <p>Idempotent by construction: the key is the content hash, so serving the
     * same pack twice is a no-op and a rebuild that changed nothing does not
     * disturb a download in flight.
     */
    public synchronized void serve(String sha1, byte[] bytes) {
        if (served.containsKey(sha1)) {
            return;
        }
        served.put(sha1, bytes);
        while (served.size() > 2) {
            String oldest = served.keySet().iterator().next();
            served.remove(oldest);
        }
    }

    /** Drops everything, so nothing is downloadable while no pack is current. */
    public synchronized void clear() {
        served.clear();
    }

    private synchronized byte[] lookup(String sha1) {
        return served.get(sha1);
    }

    /** One line for logs and gates. */
    public synchronized String describeState() {
        return "packPort=" + port() + " packsServed=" + served.size()
                + " packHits=" + hits.get() + " packMisses=" + misses.get();
    }

    /** Stops listening and releases the port. Never throws. */
    public void stop() {
        try {
            // Zero delay: every exchange is a plain file transfer with no state
            // to unwind, and a stopping server must not hold the port for a
            // client that walked away mid-download.
            http.stop(0);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Pack server did not stop cleanly", t);
        }
        workers.shutdownNow();
        try {
            workers.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        clear();
    }

    // ------------------------------------------------------------ the handler

    private void handle(HttpExchange exchange) {
        try (exchange) {
            String method = exchange.getRequestMethod();
            boolean head = "HEAD".equals(method);
            if (!head && !"GET".equals(method)) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] body = lookup(keyOf(exchange.getRequestURI().getPath()));
            if (body == null) {
                misses.incrementAndGet();
                // No body and no explanation. A 404 that describes what it was
                // looking for is a 404 that helps somebody enumerate.
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            hits.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            // The URL contains the content hash, so the answer can never change
            // for a given URL and the client may cache it forever. This is what
            // makes a rejoin free rather than another download.
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
            if (head) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (IOException disconnected) {
            // A client that closes mid-download is ordinary, not an incident.
            LOG.log(Level.FINE, "Pack download did not complete", disconnected);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Pack request failed", t);
        }
    }

    /**
     * The map key a request path selects, or {@code null} for every path that is
     * not exactly {@code /<40 lowercase hex>.zip}.
     *
     * <p>Returning null rather than throwing keeps the caller's shape simple:
     * an unparseable path and an unknown hash are the same 404, which is also
     * the only correct answer to both.
     */
    private static String keyOf(String path) {
        if (path == null || path.length() != 1 + SHA1_HEX_LENGTH + 4) {
            return null;
        }
        if (path.charAt(0) != '/' || !path.endsWith(".zip")) {
            return null;
        }
        for (int i = 1; i <= SHA1_HEX_LENGTH; i++) {
            char c = path.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return null;
            }
        }
        return path.substring(1, 1 + SHA1_HEX_LENGTH);
    }
}
