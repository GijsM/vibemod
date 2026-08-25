package com.gijsm.vibemod.fabric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.reflect.TypeToken;

/**
 * VibeMod's settings on Fabric: {@code config/vibemod.json}.
 *
 * <p>Deliberately the same key set as Paper's {@code config.yml}, spelled with
 * the same dotted names ({@code openrouter.model}, {@code watchdog.enabled}, …)
 * and carrying the same defaults. Everything above the host — the settings
 * screen, {@code /vibe reload}, the generator's live suppliers — is
 * platform-free and reads these by name, so keeping the names identical is what
 * lets one settings screen serve both platforms without a translation table.
 *
 * <p>Flat JSON rather than nested, because the consumers ask by dotted key
 * anyway and a flat file is one {@code Map} to read, write, and diff. Unknown
 * keys in an existing file are preserved on save: a config a future version
 * wrote should not be silently truncated by an older one.
 */
public final class FabricConfig {

    private static final Logger LOG = Logger.getLogger(FabricConfig.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Every key VibeMod reads, with the same default as Paper's config.yml. */
    private static final Map<String, Object> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("openrouter.api-key", "");
        DEFAULTS.put("openrouter.model", "anthropic/claude-sonnet-5");
        DEFAULTS.put("openrouter.timeout-seconds", 120);
        DEFAULTS.put("openrouter.streaming", true);
        DEFAULTS.put("openrouter.max-tokens", 0);
        DEFAULTS.put("openrouter.reasoning-effort", "off");
        DEFAULTS.put("generation.max-retries", 3);
        DEFAULTS.put("generation.concurrency", 4);
        DEFAULTS.put("watchdog.enabled", true);
        DEFAULTS.put("watchdog.single-invocation-ms", 250);
        DEFAULTS.put("watchdog.per-second-budget-ms", 500);
        DEFAULTS.put("commands.allow-top-level", true);
        DEFAULTS.put("errors.storm-threshold", 10);
        DEFAULTS.put("errors.storm-window-seconds", 60);
        DEFAULTS.put("errors.max-distinct", 25);
        DEFAULTS.put("errors.stack-frames", 10);
        DEFAULTS.put("debug.default-echo", false);
        DEFAULTS.put("ui.force-chat", false);
    }

    private final Path file;
    private volatile Map<String, Object> values = new LinkedHashMap<>(DEFAULTS);

    public FabricConfig(Path file) {
        this.file = file;
        reload();
    }

    /** Re-reads the file, filling in any key it does not carry. Never throws. */
    public void reload() {
        Map<String, Object> merged = new LinkedHashMap<>(DEFAULTS);
        if (Files.isReadable(file)) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                Map<String, Object> read = GSON.fromJson(json,
                        new TypeToken<LinkedHashMap<String, Object>>() { }.getType());
                if (read != null) {
                    merged.putAll(read);
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not read " + file + "; using defaults", e);
            }
        }
        values = merged;
        save();
    }

    /** Writes the current values back, creating the file and its directory. Never throws. */
    public void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject out = new JsonObject();
            for (Map.Entry<String, Object> e : values.entrySet()) {
                out.add(e.getKey(), GSON.toJsonTree(e.getValue()));
            }
            Files.writeString(file, GSON.toJson(out) + "\n", StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not write " + file, e);
        }
    }

    public void set(String key, Object value) {
        Map<String, Object> next = new LinkedHashMap<>(values);
        next.put(key, value);
        values = next;
    }

    public String getString(String key, String fallback) {
        Object value = values.get(key);
        if (value instanceof JsonPrimitive primitive) {
            return primitive.getAsString();
        }
        return value == null ? fallback : String.valueOf(value);
    }

    public boolean getBoolean(String key, boolean fallback) {
        Object value = values.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.equalsIgnoreCase("true") || (fallback && !text.equalsIgnoreCase("false"));
    }

    public long getLong(String key, long fallback) {
        // Gson reads every bare number as a Double; everything numeric therefore
        // arrives here as 250.0 and has to be narrowed rather than cast.
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return (long) Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    public int getInt(String key, int fallback) {
        return (int) getLong(key, fallback);
    }
}
