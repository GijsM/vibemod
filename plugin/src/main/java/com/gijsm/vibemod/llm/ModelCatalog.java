package com.gijsm.vibemod.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Live catalog of OpenRouter models, fetched from the public, unauthenticated
 * {@code GET https://openrouter.ai/api/v1/models} endpoint - no model id or
 * price is ever hardcoded anywhere in VibeMod. {@link #refreshAsync()} fetches
 * in the background and swaps in a new immutable snapshot on success; any
 * fetch/parse failure leaves the previous snapshot in place (logged once,
 * never thrown - callers always see a usable, if stale, catalog).
 *
 * <p>{@link #featured} applies a purely heuristic curation (text-to-text
 * modality, an exclusion regex for non-general-purpose ids, a minimum context
 * window, a small provider allowlist) so nothing here needs updating by hand
 * when OpenRouter adds or retires models.
 */
public final class ModelCatalog {

    private static final Logger LOG = Logger.getLogger(ModelCatalog.class.getName());
    private static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/models");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(15);

    /** Curated paid-tier providers, in the fixed order {@link #selectFeatured} groups them by. */
    private static final List<String> FEATURED_PROVIDERS = List.of(
            "anthropic", "openai", "google", "x-ai", "deepseek", "qwen", "nvidia",
            "z-ai", "moonshotai", "mistralai");
    private static final Pattern EXCLUDE = Pattern.compile(
            ":batch|safety|guard|-vl|omni|image|audio|video|music|clip|lyria|tts|whisper",
            Pattern.CASE_INSENSITIVE);
    private static final long MIN_CONTEXT = 128_000L;
    private static final int FREE_TIER_COUNT = 4;
    private static final int PAID_PER_PROVIDER_CAP = 2;
    private static final int PAID_TOTAL_CAP = 12;

    /** One catalog entry: id, context window, and per-million-token USD prices. */
    public record ModelInfo(String id, long contextLength, double promptUsdPerM, double completionUsdPerM,
                            boolean free) {

        /** {@code "FREE"}, {@code "price unknown"} (negative sentinel prices), or e.g. {@code "$2.00 in / $10.00 out /M"}. */
        public String priceLabel() {
            if (promptUsdPerM < 0 || completionUsdPerM < 0) {
                return "price unknown";
            }
            if (free) {
                return "FREE";
            }
            return "$" + fmtPrice(promptUsdPerM) + " in / $" + fmtPrice(completionUsdPerM) + " out /M";
        }

        /** 2 decimals normally; 2 significant figures for sub-cent prices so they don't collapse to $0.00. */
        private static String fmtPrice(double usdPerM) {
            if (usdPerM <= 0) {
                return "0.00";
            }
            if (usdPerM >= 0.01) {
                return String.format(Locale.ROOT, "%.2f", usdPerM);
            }
            return java.math.BigDecimal.valueOf(usdPerM)
                    .round(new java.math.MathContext(2))
                    .toPlainString();
        }
    }

    /** One parsed catalog row plus its {@code architecture.modality}, needed only by {@link #isEligible}. */
    record ParsedModel(ModelInfo info, String modality) {
    }

    private volatile List<ParsedModel> catalog = List.of();

    public ModelCatalog() {
    }

    /** Fire-and-forget background fetch + parse; on any failure, keeps whatever snapshot is already loaded. */
    public void refreshAsync() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(ENDPOINT)
                .timeout(FETCH_TIMEOUT)
                .GET()
                .build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("status=" + response.statusCode());
                    }
                    catalog = parse(response.body());
                })
                .exceptionally(ex -> {
                    LOG.log(Level.WARNING, "Failed to refresh the OpenRouter model catalog "
                            + "(keeping the previous " + catalog.size() + "-model snapshot)", ex);
                    return null;
                });
    }

    /** Looks up one model by exact id in the last successfully fetched catalog. */
    public Optional<ModelInfo> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return catalog.stream().map(ParsedModel::info).filter(m -> id.equals(m.id())).findFirst();
    }

    /**
     * Every model id in the last successfully fetched catalog, sorted alphabetically —
     * uncurated, for tab completion. Empty until the first fetch succeeds.
     */
    public List<String> allIds() {
        return catalog.stream().map(p -> p.info().id()).sorted().toList();
    }

    /**
     * A short, curated list for pickers: {@code currentModelId} always first (a "price
     * unknown" placeholder when the catalog hasn't loaded yet or doesn't know it), then
     * up to {@value #FREE_TIER_COUNT} free models (largest context first), then a handful
     * of paid models from a small provider allowlist (cheapest completion price per
     * provider, capped). Never empty.
     */
    public List<ModelInfo> featured(String currentModelId) {
        return selectFeatured(catalog, currentModelId);
    }

    // ---- pure, package-private, self-test-friendly core (no network involved) ----

    /** Parses the raw {@code GET /api/v1/models} response body. A single bad row is skipped, not fatal. */
    static List<ParsedModel> parse(String json) {
        List<ParsedModel> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) {
            return out;
        }
        JsonElement dataEl = root.getAsJsonObject().get("data");
        if (dataEl == null || !dataEl.isJsonArray()) {
            return out;
        }
        for (JsonElement el : dataEl.getAsJsonArray()) {
            if (!el.isJsonObject()) {
                continue;
            }
            try {
                out.add(parseOne(el.getAsJsonObject()));
            } catch (RuntimeException badRow) {
                LOG.fine("Skipping unparseable model catalog row: " + badRow);
            }
        }
        return out;
    }

    private static ParsedModel parseOne(JsonObject o) {
        String id = o.has("id") && !o.get("id").isJsonNull() ? o.get("id").getAsString() : "";
        long contextLength = o.has("context_length") && !o.get("context_length").isJsonNull()
                ? o.get("context_length").getAsLong() : 0L;
        String modality = "";
        if (o.has("architecture") && o.get("architecture").isJsonObject()) {
            JsonObject arch = o.getAsJsonObject("architecture");
            if (arch.has("modality") && !arch.get("modality").isJsonNull()) {
                modality = arch.get("modality").getAsString();
            }
        }
        double promptUsdPerM = 0.0;
        double completionUsdPerM = 0.0;
        if (o.has("pricing") && o.get("pricing").isJsonObject()) {
            JsonObject pricing = o.getAsJsonObject("pricing");
            promptUsdPerM = perMillion(pricing, "prompt");
            completionUsdPerM = perMillion(pricing, "completion");
        }
        boolean free = promptUsdPerM <= 0.0 && completionUsdPerM <= 0.0;
        return new ParsedModel(new ModelInfo(id, contextLength, promptUsdPerM, completionUsdPerM, free), modality);
    }

    /** OpenRouter prices are dollars-per-token strings (occasionally raw numbers); converts to $/M tokens. */
    private static double perMillion(JsonObject pricing, String key) {
        if (!pricing.has(key) || pricing.get(key).isJsonNull()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(pricing.get(key).getAsString()) * 1_000_000.0;
        } catch (RuntimeException notAString) {
            try {
                return pricing.get(key).getAsDouble() * 1_000_000.0;
            } catch (RuntimeException stillBad) {
                return 0.0;
            }
        }
    }

    /** The selection heuristic, pulled out standalone so the self-test can drive it without a network call. */
    static List<ModelInfo> selectFeatured(List<ParsedModel> all, String currentModelId) {
        List<ModelInfo> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        ModelInfo current = all.stream().map(ParsedModel::info)
                .filter(m -> m.id().equals(currentModelId))
                .findFirst()
                .orElse(new ModelInfo(currentModelId, 0L, -1.0, -1.0, false));
        out.add(current);
        seen.add(current.id());

        List<ParsedModel> eligible = all.stream().filter(ModelCatalog::isEligible).toList();

        eligible.stream()
                .map(ParsedModel::info)
                .filter(ModelInfo::free)
                .sorted(Comparator.comparingLong(ModelInfo::contextLength).reversed())
                .limit(FREE_TIER_COUNT)
                .forEach(m -> {
                    if (seen.add(m.id())) {
                        out.add(m);
                    }
                });

        int paidAdded = 0;
        for (String provider : FEATURED_PROVIDERS) {
            if (paidAdded >= PAID_TOTAL_CAP) {
                break;
            }
            List<ModelInfo> providerModels = eligible.stream()
                    .map(ParsedModel::info)
                    .filter(m -> !m.free())
                    .filter(m -> provider.equalsIgnoreCase(providerOf(m.id())))
                    .filter(m -> !m.id().toLowerCase(Locale.ROOT).endsWith(":free"))
                    .sorted(Comparator.comparingDouble(ModelInfo::completionUsdPerM)
                            .thenComparing(Comparator.comparingLong(ModelInfo::contextLength).reversed()))
                    .limit(PAID_PER_PROVIDER_CAP)
                    .toList();
            for (ModelInfo m : providerModels) {
                if (paidAdded >= PAID_TOTAL_CAP) {
                    break;
                }
                if (seen.add(m.id())) {
                    out.add(m);
                    paidAdded++;
                }
            }
        }
        return out;
    }

    private static boolean isEligible(ParsedModel p) {
        String modality = p.modality() == null ? "" : p.modality().toLowerCase(Locale.ROOT);
        if (!modality.startsWith("text") || !modality.endsWith("text")) {
            return false;
        }
        String id = p.info().id();
        if (id.toLowerCase(Locale.ROOT).startsWith("openrouter/")) {
            return false;
        }
        if (EXCLUDE.matcher(id).find()) {
            return false;
        }
        return p.info().contextLength() >= MIN_CONTEXT;
    }

    private static String providerOf(String id) {
        int slash = id.indexOf('/');
        return slash < 0 ? "" : id.substring(0, slash).toLowerCase(Locale.ROOT);
    }
}
