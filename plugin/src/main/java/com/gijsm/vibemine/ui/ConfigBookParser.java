package com.gijsm.vibemine.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure-JVM grammar for the plain-text config books players write in. No
 * Bukkit types touch this class so it can be unit-tested without a server.
 *
 * <p>Grammar, per page per line: strip legacy {@code §} formatting codes,
 * trim, skip blank lines and lines starting with {@code #} or {@code //},
 * then split the remainder on the first {@code ':'} or {@code '='} (whichever
 * comes first) into a key and a value. Keys are matched against
 * {@code knownKeys} case-insensitively; an unknown key is reported with a
 * nearest-match suggestion (edit distance &lt;= 2, or a prefix match either
 * way) when one exists. A key repeated later in the input wins over an
 * earlier one, with a warning recorded for the duplicate. Values are handed
 * back verbatim (not validated) - that is the schema's job.
 */
public final class ConfigBookParser {

    /** Parsed key/value pairs (last-value-wins) plus any human-readable problems found. */
    public record ParseResult(Map<String, String> values, List<String> errors) {
    }

    private ConfigBookParser() {
    }

    /** Parses {@code pages} against {@code knownKeys}. Never throws on malformed input. */
    public static ParseResult parse(List<String> pages, Set<String> knownKeys) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        if (pages == null) {
            return new ParseResult(values, errors);
        }

        Set<String> knownLower = new LinkedHashSet<>();
        Map<String, String> canonicalByLower = new LinkedHashMap<>();
        if (knownKeys != null) {
            for (String key : knownKeys) {
                if (key == null) {
                    continue;
                }
                String lower = key.toLowerCase(Locale.ROOT);
                knownLower.add(lower);
                canonicalByLower.putIfAbsent(lower, key);
            }
        }

        for (int pageIdx = 0; pageIdx < pages.size(); pageIdx++) {
            String page = pages.get(pageIdx) == null ? "" : pages.get(pageIdx);
            String[] lines = page.split("\n", -1);
            for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
                String line = stripColorCodes(lines[lineIdx]).trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                    continue;
                }

                int pageNum = pageIdx + 1;
                int lineNum = lineIdx + 1;
                int sepIdx = firstSeparator(line);
                if (sepIdx < 0) {
                    errors.add("page " + pageNum + ", line " + lineNum + ": expected 'key: value'");
                    continue;
                }

                String rawKey = line.substring(0, sepIdx).trim();
                String rawValue = line.substring(sepIdx + 1).trim();
                if (rawKey.isEmpty()) {
                    errors.add("page " + pageNum + ", line " + lineNum + ": expected 'key: value'");
                    continue;
                }

                String lowerKey = rawKey.toLowerCase(Locale.ROOT);
                if (!knownLower.contains(lowerKey)) {
                    String suggestion = nearestKey(lowerKey, knownLower);
                    if (suggestion != null) {
                        errors.add("page " + pageNum + ", line " + lineNum + ": unknown key '" + rawKey
                                + "' (did you mean '" + canonicalByLower.getOrDefault(suggestion, suggestion)
                                + "'?)");
                    } else {
                        errors.add("page " + pageNum + ", line " + lineNum + ": unknown key '" + rawKey + "'");
                    }
                    continue;
                }

                String canonicalKey = canonicalByLower.getOrDefault(lowerKey, lowerKey);
                if (values.containsKey(canonicalKey)) {
                    errors.add("page " + pageNum + ", line " + lineNum + ": duplicate key '" + canonicalKey
                            + "' (last value wins)");
                }
                values.put(canonicalKey, rawValue);
            }
        }

        return new ParseResult(values, errors);
    }

    /** Index of the first {@code ':'} or {@code '='} in {@code line}, or -1 if neither is present. */
    private static int firstSeparator(String line) {
        int colon = line.indexOf(':');
        int equals = line.indexOf('=');
        if (colon < 0) {
            return equals;
        }
        if (equals < 0) {
            return colon;
        }
        return Math.min(colon, equals);
    }

    private static String stripColorCodes(String s) {
        return s.replaceAll("§.?", "");
    }

    /** Nearest of {@code candidates} to {@code key} by prefix match or edit distance &lt;= 2, else null. */
    private static String nearestKey(String key, Set<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            boolean prefixMatch = candidate.startsWith(key) || key.startsWith(candidate);
            int distance = levenshtein(key, candidate);
            if (!prefixMatch && distance > 2) {
                continue;
            }
            if (distance < bestDistance || (distance == bestDistance && (best == null || candidate.compareTo(best) < 0))) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }
}
