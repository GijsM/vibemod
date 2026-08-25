package com.gijsm.vibemod.llm;

import java.util.List;

/**
 * Standalone self-test (no test framework, no network) proving {@link ModelCatalog}'s
 * package-private {@code parse}/{@code selectFeatured} core: free-model detection, the
 * exclusion regex (batch/safety/guard/-vl/lyria ids never surface), the per-provider cap
 * on the curated paid tier, current-model-first ordering with no duplication, {@code
 * priceLabel()} formatting, and the never-empty fallback for an unloaded/empty catalog.
 *
 * <p>Declared in {@code com.gijsm.vibemod.llm} (even though the file lives under {@code
 * src/test/java}, not a matching subdirectory) so it can reach {@code ModelCatalog}'s
 * package-private {@code parse}/{@code selectFeatured}/{@code ParsedModel}.
 */
public class CatalogSelfTest {

    private static int failures = 0;

    /** A realistic canned {@code GET /api/v1/models} body covering every scenario below. */
    private static final String CANNED_JSON = "{\"data\":[" + String.join(",", List.of(
            model("anthropic/claude-sonnet-5", 200000, "text->text", "0.000003", "0.000015"),
            model("anthropic/claude-opus-5", 200000, "text->text", "0.000015", "0.000075"),
            model("anthropic/claude-haiku-5", 200000, "text->text", "0.0000008", "0.000004"),
            model("openai/gpt-5", 128000, "text->text", "0.00001", "0.00003"),
            model("google/gemini-3-pro", 1000000, "text->text", "0.00000125", "0.000005"),
            model("mistralai/mistral-large-2", 128000, "text->text", "0.000002", "0.000006"),
            model("unknownprovider/some-model", 300000, "text->text", "0.000001", "0.000003"),
            model("deepseek/deepseek-cheap", 128000, "text->text", "0.0000000008", "0.000000002"),
            // free tier candidates - only the top 4 by context should survive
            model("nvidia/nemotron-3-ultra-550b-a55b:free", 1000000, "text->text", "0", "0"),
            model("freeprov/free-a", 500000, "text->text", "0", "0"),
            model("freeprov/free-b", 300000, "text->text", "0", "0"),
            model("freeprov/free-c", 900000, "text->text", "0", "0"),
            model("freeprov/free-d", 150000, "text->text", "0", "0"),
            model("freeprov/free-e", 1200000, "text->text", "0", "0"),
            // below the min-context floor - excluded from both tiers despite being free/text
            model("freeprov/tiny-model", 8000, "text->text", "0", "0"),
            // excluded by the id regex despite otherwise qualifying
            model("google/gemini-vision-vl", 200000, "text->text", "0.000001", "0.000002"),
            model("anthropic/claude-guard-1", 200000, "text->text", "0.000001", "0.000002"),
            model("somex/lyria-music", 200000, "text->text", "0", "0"),
            model("openai/gpt-5:batch", 200000, "text->text", "0.000001", "0.000002"),
            // excluded by the openrouter/ prefix rule
            model("openrouter/auto", 200000, "text->text", "0", "0")
    )) + "]}";

    public static void main(String[] args) {
        testFreeDetection();
        testExclusionRegex();
        testFreeTierCapAndOrdering();
        testProviderCap();
        testCurrentModelFirstNoDuplicate();
        testPriceLabelFormatting();
        testEmptyCatalogFallback();

        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
    }

    private static void testFreeDetection() {
        List<ModelCatalog.ParsedModel> all = ModelCatalog.parse(CANNED_JSON);
        ModelCatalog.ModelInfo nemotron = findInfo(all, "nvidia/nemotron-3-ultra-550b-a55b:free");
        check("free model has free=true", nemotron != null && nemotron.free());
        check("free model priceLabel is FREE", nemotron != null && "FREE".equals(nemotron.priceLabel()));
        ModelCatalog.ModelInfo sonnet = findInfo(all, "anthropic/claude-sonnet-5");
        check("paid model has free=false", sonnet != null && !sonnet.free());
        if (failures == 0) {
            System.out.println("PASS: parse() detects free (0/0 pricing) vs paid models");
        }
    }

    private static void testExclusionRegex() {
        List<ModelCatalog.ParsedModel> all = ModelCatalog.parse(CANNED_JSON);
        // Pick a currentModelId unrelated to any excluded id so exclusions aren't masked by dedup.
        List<ModelCatalog.ModelInfo> featured = ModelCatalog.selectFeatured(all, "unknownprovider/some-model");
        for (ModelCatalog.ModelInfo m : featured) {
            check("excluded id '-vl' never featured", !m.id().contains("vision-vl"));
            check("excluded id 'guard' never featured", !m.id().contains("guard"));
            check("excluded id 'lyria' never featured", !m.id().contains("lyria"));
            check("excluded id ':batch' never featured", !m.id().contains(":batch"));
            check("excluded 'openrouter/' prefix never featured", !m.id().startsWith("openrouter/"));
        }
        if (failures == 0) {
            System.out.println("PASS: batch/safety/guard/-vl/lyria/openrouter-prefixed ids are filtered out");
        }
    }

    private static void testFreeTierCapAndOrdering() {
        List<ModelCatalog.ParsedModel> all = ModelCatalog.parse(CANNED_JSON);
        List<ModelCatalog.ModelInfo> featured = ModelCatalog.selectFeatured(all, "google/gemini-3-pro");
        List<String> freeIds = featured.stream().filter(ModelCatalog.ModelInfo::free).map(ModelCatalog.ModelInfo::id).toList();
        check("free tier capped at 4", freeIds.size() == 4);
        check("free tier ordered by context desc",
                freeIds.equals(List.of("freeprov/free-e", "nvidia/nemotron-3-ultra-550b-a55b:free",
                        "freeprov/free-c", "freeprov/free-a")));
        check("free tier excludes below-min-context free model", !freeIds.contains("freeprov/tiny-model"));
        check("free tier excludes the smaller leftover free models", !freeIds.contains("freeprov/free-b")
                && !freeIds.contains("freeprov/free-d"));
        if (failures == 0) {
            System.out.println("PASS: free tier is capped at 4, ordered by context length descending");
        }
    }

    private static void testProviderCap() {
        List<ModelCatalog.ParsedModel> all = ModelCatalog.parse(CANNED_JSON);
        // currentModelId unrelated to anthropic so both cheapest anthropic models fit untouched.
        List<ModelCatalog.ModelInfo> featured = ModelCatalog.selectFeatured(all, "google/gemini-3-pro");
        List<String> anthropicIds = featured.stream()
                .map(ModelCatalog.ModelInfo::id)
                .filter(id -> id.startsWith("anthropic/"))
                .toList();
        check("provider cap keeps at most 2 anthropic models", anthropicIds.size() == 2);
        check("provider cap picks the two cheapest-completion anthropic models",
                anthropicIds.contains("anthropic/claude-haiku-5") && anthropicIds.contains("anthropic/claude-sonnet-5"));
        check("provider cap drops the most expensive anthropic model", !anthropicIds.contains("anthropic/claude-opus-5"));
        check("non-featured provider is excluded from the paid tier",
                featured.stream().noneMatch(m -> m.id().equals("unknownprovider/some-model")));
        if (failures == 0) {
            System.out.println("PASS: paid tier caps at 2 models per allow-listed provider, cheapest completion first");
        }
    }

    private static void testCurrentModelFirstNoDuplicate() {
        List<ModelCatalog.ParsedModel> all = ModelCatalog.parse(CANNED_JSON);
        List<ModelCatalog.ModelInfo> featured = ModelCatalog.selectFeatured(all, "anthropic/claude-sonnet-5");
        check("current model is first", "anthropic/claude-sonnet-5".equals(featured.get(0).id()));
        check("current model carries its real price",
                featured.get(0).promptUsdPerM() == 3.0 && featured.get(0).completionUsdPerM() == 15.0);
        long count = featured.stream().filter(m -> m.id().equals("anthropic/claude-sonnet-5")).count();
        check("current model is never duplicated", count == 1);
        if (failures == 0) {
            System.out.println("PASS: current model is always first, never duplicated");
        }
    }

    private static void testPriceLabelFormatting() {
        check("normal price: 2 decimals",
                "$2.00 in / $10.00 out /M".equals(
                        new ModelCatalog.ModelInfo("x", 100, 2.0, 10.0, false).priceLabel()));
        check("free: FREE",
                "FREE".equals(new ModelCatalog.ModelInfo("x", 100, 0, 0, true).priceLabel()));
        check("unknown (negative sentinel): price unknown",
                "price unknown".equals(new ModelCatalog.ModelInfo("x", 0, -1.0, -1.0, false).priceLabel()));
        String subCent = new ModelCatalog.ModelInfo("x", 100, 0.0008, 0.002, false).priceLabel();
        check("sub-cent price never collapses to $0.00", !subCent.contains("$0.00 "));
        check("sub-cent price keeps significant digits", subCent.contains("0.0008") && subCent.contains("0.002"));
        if (failures == 0) {
            System.out.println("PASS: priceLabel() formats FREE / unknown / normal / sub-cent prices");
        }
    }

    private static void testEmptyCatalogFallback() {
        List<ModelCatalog.ModelInfo> featured = ModelCatalog.selectFeatured(List.of(), "some/unloaded-model");
        check("empty catalog still returns a non-empty list", !featured.isEmpty());
        check("empty catalog featured() has exactly the current model", featured.size() == 1
                && "some/unloaded-model".equals(featured.get(0).id()));
        check("empty catalog current model has unknown price", "price unknown".equals(featured.get(0).priceLabel()));

        List<ModelCatalog.ParsedModel> emptyParse = ModelCatalog.parse("{\"data\":[]}");
        check("parse() on an empty data array returns an empty list", emptyParse.isEmpty());
        if (failures == 0) {
            System.out.println("PASS: featured() never returns empty, even with no catalog loaded");
        }
    }

    // ---- helpers ----

    private static ModelCatalog.ModelInfo findInfo(List<ModelCatalog.ParsedModel> all, String id) {
        return all.stream().map(ModelCatalog.ParsedModel::info).filter(m -> m.id().equals(id)).findFirst().orElse(null);
    }

    private static String model(String id, long contextLength, String modality, String promptPrice, String completionPrice) {
        return "{\"id\":\"" + id + "\",\"context_length\":" + contextLength
                + ",\"architecture\":{\"modality\":\"" + modality + "\"}"
                + ",\"pricing\":{\"prompt\":\"" + promptPrice + "\",\"completion\":\"" + completionPrice + "\"}}";
    }

    private static void check(String label, boolean condition) {
        if (!condition) {
            fail(label);
        }
    }

    private static void fail(String message) {
        failures++;
        System.out.println("FAIL: " + message);
    }
}
