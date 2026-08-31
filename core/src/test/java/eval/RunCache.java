package eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.gijsm.vibemod.llm.OpenRouterClient.ChatMessage;

/**
 * A content-addressed, on-disk cache of model responses, so a run that dies
 * halfway never re-pays for what it already bought.
 *
 * <p>The key is a SHA-256 over the whole request as the model sees it: the
 * model id, the system prompt, then every message's role and content in order.
 * That means the "before" and "after" arms key differently (their system
 * prompts differ), a self-heal round keys differently from round 0 (its message
 * list is longer), and re-running the same cell is free.
 *
 * <p>Sampling temperature is not part of the key, and cannot be: the point of
 * the cache is that the second run of an identical request reuses the first
 * sample rather than buying a fresh one. That makes this a resume cache, not a
 * statistical one — two cells that share a key share a sample.
 */
public final class RunCache {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path dir;
    private int hits;
    private int misses;

    public RunCache(Path outDir) {
        this.dir = outDir.resolve("responses");
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A cached response: the raw completion text, what it cost, and any usage JSON. */
    public record Entry(String key, String content, double costUsd, String usageJson) {
    }

    public Path directory() {
        return dir;
    }

    public int hits() {
        return hits;
    }

    public int misses() {
        return misses;
    }

    /** SHA-256 over model + system prompt + every message, hex, first 32 chars. */
    public static String key(String model, String systemPrompt, List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append(model).append(' ').append(systemPrompt);
        for (ChatMessage m : messages) {
            sb.append(' ').append(m.role()).append(' ').append(m.content());
        }
        return sha256Hex(sb.toString()).substring(0, 32);
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JLS", e);
        }
    }

    /** A hit costs nothing and makes no API call. */
    public Optional<Entry> lookup(String key) {
        Path f = file(key);
        if (!Files.isReadable(f)) {
            misses++;
            return Optional.empty();
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            hits++;
            String usage = obj.has("usage") && !obj.get("usage").isJsonNull()
                    ? obj.get("usage").toString() : null;
            return Optional.of(new Entry(key,
                    obj.get("content").getAsString(),
                    obj.has("costUsd") ? obj.get("costUsd").getAsDouble() : 0.0,
                    usage));
        } catch (IOException | RuntimeException e) {
            System.out.println("  [cache] unreadable entry " + f.getFileName() + " (" + e + ") - treating as miss");
            misses++;
            return Optional.empty();
        }
    }

    /**
     * Writes one bought response. The cost recorded here is what the run was
     * billed; a later cache hit re-reports it as a hit, never as new spend.
     */
    public Entry store(String key, String model, String content, double costUsd, String usageJson) {
        JsonObject obj = new JsonObject();
        obj.addProperty("key", key);
        obj.addProperty("model", model);
        obj.addProperty("costUsd", costUsd);
        obj.addProperty("storedAt", java.time.Instant.now().toString());
        obj.addProperty("content", content);
        if (usageJson != null && !usageJson.isBlank()) {
            try {
                obj.add("usage", JsonParser.parseString(usageJson));
            } catch (RuntimeException e) {
                obj.addProperty("usageRaw", usageJson);
            }
        }
        try {
            Files.writeString(file(key), GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("  [cache] could not write " + key + ": " + e);
        }
        return new Entry(key, content, costUsd, usageJson);
    }

    private Path file(String key) {
        return dir.resolve(key + ".json");
    }
}
