package com.gijsm.vibemod.store;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * A texture an LLM can actually write: a palette and a grid of characters
 * (V3 Phase 2 §D).
 *
 * <pre>
 * {"palette": {"a": "#8b1a1a", "b": "#ff5555", ".": "transparent"},
 *  "rows": ["..aa..", ".abba.", "abbbba", …]}
 * </pre>
 *
 * <p>Square, at most 64×64. Colours are {@code #RRGGBB}, {@code #RRGGBBAA} or
 * the word {@code transparent}. Every character used in {@code rows} must be in
 * the palette; every palette key is exactly one character.
 *
 * <p>{@link #toPng()} is a hand-rolled encoder — RGBA8, one IDAT, filter type 0
 * on every scanline — because {@code java.desktop} (and therefore
 * {@code ImageIO}) is not on a Minecraft server's module path and adding it to
 * ship a 16×16 icon would be absurd. A PNG that simple is about sixty lines of
 * {@link Deflater} and {@link CRC32}, and the format's own CRCs mean a mistake
 * shows up as "the client refused the texture", not as silent corruption.
 *
 * <p>Every failure is an {@link IllegalArgumentException} with a message
 * written for the model: grids are validated at generation time, so a malformed
 * one costs a self-heal round instead of appearing as a crash in someone's
 * client three minutes later.
 */
public final class PixelGrid {

    /** Vanilla item textures are 16×16; this is a generous ceiling, not a target. */
    public static final int MAX_SIZE = 64;

    private final int size;
    /** Row-major RGBA, four bytes per pixel. */
    private final byte[] pixels;

    private PixelGrid(int size, byte[] pixels) {
        this.size = size;
        this.pixels = pixels;
    }

    public int size() {
        return size;
    }

    /**
     * Parses and fully validates a {@code .png.grid} document. Throws
     * {@link IllegalArgumentException} — never returns a half-valid grid.
     */
    public static PixelGrid parse(String json) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json == null ? "" : json);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("a .png.grid file must be a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new IllegalArgumentException("a .png.grid file must be valid JSON: " + e.getMessage());
        }

        JsonElement paletteElement = root.get("palette");
        if (paletteElement == null || !paletteElement.isJsonObject()) {
            throw new IllegalArgumentException("a .png.grid file needs a \"palette\" object");
        }
        Map<Character, int[]> palette = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : paletteElement.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            if (key.length() != 1) {
                throw new IllegalArgumentException(
                        "every \"palette\" key must be exactly one character, got: \"" + key + "\"");
            }
            if (!entry.getValue().isJsonPrimitive()) {
                throw new IllegalArgumentException(
                        "palette colour for '" + key + "' must be a string like \"#8b1a1a\"");
            }
            palette.put(key.charAt(0), parseColour(key, entry.getValue().getAsString()));
        }

        JsonElement rowsElement = root.get("rows");
        if (rowsElement == null || !rowsElement.isJsonArray()) {
            throw new IllegalArgumentException("a .png.grid file needs a \"rows\" array of strings");
        }
        List<JsonElement> rowList = rowsElement.getAsJsonArray().asList();
        int height = rowList.size();
        if (height == 0) {
            throw new IllegalArgumentException("\"rows\" must not be empty");
        }
        if (height > MAX_SIZE) {
            throw new IllegalArgumentException("a .png.grid may be at most " + MAX_SIZE + "x" + MAX_SIZE
                    + ", got " + height + " rows");
        }

        byte[] pixels = new byte[height * height * 4];
        for (int y = 0; y < height; y++) {
            JsonElement rowElement = rowList.get(y);
            if (!rowElement.isJsonPrimitive()) {
                throw new IllegalArgumentException("every entry in \"rows\" must be a string");
            }
            String row = rowElement.getAsString();
            if (row.length() != height) {
                throw new IllegalArgumentException("a .png.grid must be square: row " + y + " is "
                        + row.length() + " characters wide but there are " + height + " rows");
            }
            for (int x = 0; x < height; x++) {
                char c = row.charAt(x);
                int[] rgba = palette.get(c);
                if (rgba == null) {
                    throw new IllegalArgumentException("character '" + c + "' (row " + y + ", column "
                            + x + ") is not in the \"palette\"");
                }
                int at = (y * height + x) * 4;
                pixels[at] = (byte) rgba[0];
                pixels[at + 1] = (byte) rgba[1];
                pixels[at + 2] = (byte) rgba[2];
                pixels[at + 3] = (byte) rgba[3];
            }
        }
        return new PixelGrid(height, pixels);
    }

    private static int[] parseColour(String key, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.equalsIgnoreCase("transparent")) {
            return new int[] {0, 0, 0, 0};
        }
        if (!value.startsWith("#") || (value.length() != 7 && value.length() != 9)) {
            throw new IllegalArgumentException("palette colour for '" + key
                    + "' must be \"#RRGGBB\", \"#RRGGBBAA\" or \"transparent\", got: \"" + raw + "\"");
        }
        int[] out = new int[] {0, 0, 0, 255};
        for (int i = 0; i < (value.length() - 1) / 2; i++) {
            out[i] = hexByte(key, raw, value.charAt(1 + i * 2), value.charAt(2 + i * 2));
        }
        return out;
    }

    private static int hexByte(String key, String raw, char hi, char lo) {
        int high = Character.digit(hi, 16);
        int low = Character.digit(lo, 16);
        if (high < 0 || low < 0) {
            throw new IllegalArgumentException("palette colour for '" + key
                    + "' is not hexadecimal: \"" + raw + "\"");
        }
        return high * 16 + low;
    }

    /** This grid as a complete 8-bit RGBA PNG file. */
    public byte[] toPng() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Signature.
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});

        // IHDR: width, height, bit depth 8, colour type 6 (RGBA), deflate,
        // adaptive filtering, no interlace.
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        writeInt(ihdr, size);
        writeInt(ihdr, size);
        ihdr.write(8);
        ihdr.write(6);
        ihdr.write(0);
        ihdr.write(0);
        ihdr.write(0);
        writeChunk(out, "IHDR", ihdr.toByteArray());

        // IDAT: every scanline prefixed with filter type 0 ("None"), then zlib.
        byte[] raw = new byte[size * (size * 4 + 1)];
        for (int y = 0; y < size; y++) {
            int at = y * (size * 4 + 1);
            raw[at] = 0;
            System.arraycopy(pixels, y * size * 4, raw, at + 1, size * 4);
        }
        writeChunk(out, "IDAT", deflate(raw));

        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] body) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        writeInt(out, body.length);
        out.writeBytes(name);
        out.writeBytes(body);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(body);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }
}
