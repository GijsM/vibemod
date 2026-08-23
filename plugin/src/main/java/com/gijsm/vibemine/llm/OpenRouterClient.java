package com.gijsm.vibemine.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Thin async client for the OpenRouter chat-completions API. Holds one shared
 * {@link HttpClient} instance; never logs or exposes the API key.
 *
 * <p>Two request shapes are supported: {@link #complete} (buffered, one JSON
 * response) and {@link #completeStreaming} (Server-Sent Events, incremental
 * deltas pushed to a {@link StreamObserver} as they arrive). A streaming round
 * that fails before its first content delta transparently falls back to
 * {@link #complete} — see {@link #completeStreaming} for the full contract.
 */
public final class OpenRouterClient {

    private static final Logger LOG = Logger.getLogger(OpenRouterClient.class.getName());
    private static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/chat/completions");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /**
     * Idle watchdog for streaming rounds: one shared daemon scheduler, ticking every 5s,
     * checking every in-flight stream's last-SSE-line timestamp against its own configured
     * timeout. Shared (not per-stream) because it does nothing but cheap timestamp checks.
     */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread t = new Thread(runnable, "openrouter-stream-watchdog");
        t.setDaemon(true);
        return t;
    });
    private static final long WATCHDOG_PERIOD_SECONDS = 5L;

    /** One message in a chat exchange. */
    public record ChatMessage(String role, String content) {
    }

    /** One completion: the assistant message text plus its real {@code usage.cost} in USD (0 if absent). */
    public record Completion(String content, double costUsd) {
    }

    /**
     * Receives raw streaming deltas as they arrive over SSE. Called on the HTTP client's
     * callback thread (JDK {@link Flow} serializes calls to a given subscriber, so this is
     * never invoked concurrently with itself) — implementations that need to touch the main
     * server thread or throttle output must do so themselves; a slow or throwing observer
     * must not be allowed to break generation (catch broadly inside {@code onDelta}).
     */
    public interface StreamObserver {
        /** {@code delta} is the raw text just appended; {@code totalChars} is the running total. */
        void onDelta(String delta, int totalChars);
    }

    /**
     * A failed completion (truncated/empty response) that nonetheless carries a real,
     * billed {@code usage.cost} - callers that must not lose track of burned money (see
     * {@code ModGenerator}) can recover it from a failed future via this exception.
     */
    public static final class CostAwareException extends IOException {
        private final double costUsd;

        public CostAwareException(String message, double costUsd) {
            super(message);
            this.costUsd = costUsd;
        }

        public double costUsd() {
            return costUsd;
        }
    }

    private final String apiKey;
    private volatile String model;
    private volatile Duration timeout;
    private volatile int maxTokens = 0; // <= 0: omit, OpenRouter uses the model's own ceiling
    private volatile String reasoningEffort; // null: off — omit the "reasoning" field entirely
    private final DoubleAdder sessionCost = new DoubleAdder();

    public OpenRouterClient(String apiKey, String model, Duration timeout) {
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
    }

    /** Cap on completion tokens per request; big mods need headroom. */
    public void setMaxTokens(int maxTokens) {
        this.maxTokens = Math.max(0, maxTokens); // 0 = omit the field entirely
    }

    /**
     * Reasoning/thinking effort sent with every request ("low"/"medium"/"high");
     * anything else — including "off", "", null, or YAML's bare {@code off} parsed
     * as boolean {@code false} and stringified — disables reasoning entirely.
     */
    public void setReasoningEffort(String effort) {
        String normalized = effort == null ? "" : effort.trim().toLowerCase(java.util.Locale.ROOT);
        this.reasoningEffort = switch (normalized) {
            case "low", "medium", "high" -> normalized;
            default -> null;
        };
    }

    /** The active reasoning effort: "low"/"medium"/"high", or "off" when disabled. */
    public String reasoningEffort() {
        String effort = reasoningEffort;
        return effort == null ? "off" : effort;
    }

    /** Shared request-body builder for both the buffered and streaming request shapes. */
    private JsonObject buildBody(String systemPrompt, List<ChatMessage> messages, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        if (maxTokens > 0) {
            body.addProperty("max_tokens", maxTokens);
        }
        String effort = reasoningEffort;
        if (effort != null) {
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", effort);
            body.add("reasoning", reasoning);
            // No "temperature" here: with reasoning enabled, Anthropic models reject any
            // temperature other than 1, so we omit it and let the provider default apply.
        } else {
            body.addProperty("temperature", 0.4);
        }
        if (stream) {
            body.addProperty("stream", true);
            // Deliberately no "stream_options"/"usage.include": OpenRouter always includes
            // usage on the final SSE chunk; that field is deprecated cruft.
        }

        JsonArray msgs = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", systemPrompt);
        msgs.add(sys);
        for (ChatMessage m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role());
            o.addProperty("content", m.content());
            msgs.add(o);
        }
        body.add("messages", msgs);
        return body;
    }

    private HttpRequest.Builder baseRequest(JsonObject body) {
        return HttpRequest.newBuilder()
                .uri(ENDPOINT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("HTTP-Referer", "https://github.com/gijsm/vibemine")
                .header("X-Title", "VibeMine")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    }

    /** POST to the chat-completions endpoint; returns the assistant message text plus its billed cost. */
    public CompletableFuture<Completion> complete(String systemPrompt, List<ChatMessage> messages) {
        JsonObject body = buildBody(systemPrompt, messages, false);
        HttpRequest request = baseRequest(body).timeout(timeout).build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(this::toResult);
    }

    /**
     * Streaming counterpart of {@link #complete}: POSTs with {@code "stream":true} and parses
     * the Server-Sent Events response incrementally, pushing each content delta to
     * {@code observer} as it arrives. Resolves to the same {@link Completion} shape as
     * {@link #complete} once the stream's terminal event ({@code data: [DONE]}) is seen.
     *
     * <p>Transport: a status-aware {@link HttpResponse.BodyHandler} — a non-200 response is
     * buffered as a string and fails with the same message shape as {@link #toResult}; a 200
     * response is handed line-by-line to an internal {@link SseSubscriber} via
     * {@link HttpResponse.BodySubscribers#fromLineSubscriber}.
     *
     * <p>No {@link HttpRequest.Builder#timeout} is set on the streaming request (on JDK 21 it
     * only bounds time-to-headers, not the body — JDK-8258397 — so it would be actively
     * misleading here). Instead an idle watchdog fails the stream with a
     * {@link HttpTimeoutException} if no SSE line arrives for longer than this client's
     * currently configured {@link #setTimeout timeout}.
     *
     * <p>Automatic fallback: if the streaming attempt fails before its first content delta
     * (bad model id, connection reset, stuck-and-idle, mid-stream error before any text, ...),
     * this transparently retries the SAME round via {@link #complete} instead of failing the
     * caller — logged once at INFO. Once at least one delta has been observed, failures
     * propagate as-is; the generation pipeline's own retry loop handles those.
     */
    public CompletableFuture<Completion> completeStreaming(String systemPrompt, List<ChatMessage> messages,
                                                            StreamObserver observer) {
        JsonObject body = buildBody(systemPrompt, messages, true);
        HttpRequest request = baseRequest(body).build(); // NB: no .timeout() — see javadoc above.

        SseSubscriber sse = new SseSubscriber(observer);
        Duration idleTimeout = this.timeout;
        ScheduledFuture<?> watchdogTask = WATCHDOG.scheduleWithFixedDelay(() -> {
            long idleNanos = System.nanoTime() - sse.lastEventNanos();
            if (idleNanos > idleTimeout.toNanos()) {
                sse.fail(new HttpTimeoutException(
                        "OpenRouter stream idle for over " + idleTimeout.getSeconds() + "s"));
            }
        }, WATCHDOG_PERIOD_SECONDS, WATCHDOG_PERIOD_SECONDS, TimeUnit.SECONDS);

        HttpResponse.BodyHandler<Object> handler = responseInfo -> responseInfo.statusCode() != 200
                ? upcast(HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8))
                : upcast(HttpResponse.BodySubscribers.fromLineSubscriber(sse));

        HTTP.sendAsync(request, handler).whenComplete((response, transportError) -> {
            if (transportError != null) {
                sse.fail(transportError instanceof IOException ioe ? ioe
                        : new IOException(String.valueOf(transportError.getMessage()), transportError));
                return;
            }
            if (response.statusCode() != 200) {
                String responseBody = response.body() instanceof String s ? s : "";
                String snippet = responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody;
                sse.fail(new IOException(
                        "OpenRouter request failed: status=" + response.statusCode() + " body=" + snippet));
                return;
            }
            // 200: the subscriber drives its own result from parsed SSE events. If the
            // connection reached EOF without a terminal [DONE] event, decide from whatever
            // was accumulated instead of hanging forever.
            sse.finalizeStream();
        });

        CompletableFuture<Completion> streamed = sse.resultFuture();
        streamed.whenComplete((completion, error) -> {
            watchdogTask.cancel(false);
            sse.cancelSubscription();
        });

        return streamed.exceptionallyCompose(ex -> {
            if (!sse.firstDeltaSeen()) {
                LOG.info("Streaming completion failed before any content arrived (" + ex
                        + "); falling back to a non-streaming request");
                return complete(systemPrompt, messages);
            }
            return CompletableFuture.failedFuture(ex);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> HttpResponse.BodySubscriber<T> upcast(HttpResponse.BodySubscriber<?> subscriber) {
        return (HttpResponse.BodySubscriber<T>) subscriber;
    }

    /**
     * Parses one SSE stream of OpenRouter chat-completion chunks into a single {@link
     * Completion}, forwarding content deltas to a {@link StreamObserver} as they arrive. All
     * state here is only ever touched from the HTTP client's serialized callback thread (per
     * {@link Flow} semantics) except {@link #firstDeltaSeen} and {@link #lastEventNanosValue}
     * (volatile — read by the watchdog thread) and {@link #subscription} (volatile — read/set
     * from both the callback thread and whichever thread cancels it).
     *
     * <p>Rules, in the order applied to each line:
     * <ul>
     *   <li>blank lines and {@code ':'}-prefixed comment lines (OpenRouter keep-alives, e.g.
     *       {@code ": OPENROUTER PROCESSING"}) are skipped;
     *   <li>only {@code data:} lines carry a JSON payload; anything else is skipped;
     *   <li>{@code data: [DONE]} finalizes the stream;
     *   <li>a chunk-level or choice-level {@code "error"} field fails the stream immediately
     *       (also handles {@code finish_reason:"error"} as a hard failure);
     *   <li>{@code choices[0].delta.content} is forwarded when present and non-null (role-only
     *       chunks and the trailing usage-only chunk with {@code choices: []} are tolerated —
     *       never treated as errors);
     *   <li>{@code choices[0].finish_reason} is recorded but does NOT finalize the stream by
     *       itself — the billed {@code usage.cost} routinely arrives in a LATER chunk;
     *   <li>{@code usage.cost} is captured from whichever chunk carries a {@code usage} object.
     * </ul>
     * On finalization: {@code finish_reason:"length"} or blank accumulated content both fail
     * with a {@link CostAwareException} carrying whatever cost was captured (mirrors {@link
     * #toResult}); otherwise the session cost adder is credited and the future completes.
     */
    private final class SseSubscriber implements Flow.Subscriber<String> {

        private final StreamObserver observer;
        private final CompletableFuture<Completion> future = new CompletableFuture<>();
        private final StringBuilder content = new StringBuilder();

        private volatile boolean firstDeltaSeen = false;
        private volatile long lastEventNanosValue = System.nanoTime();
        private volatile Flow.Subscription subscription;

        private String finishReason; // only touched on the HTTP callback thread
        private double cost = 0.0;   // only touched on the HTTP callback thread

        SseSubscriber(StreamObserver observer) {
            this.observer = observer;
        }

        CompletableFuture<Completion> resultFuture() {
            return future;
        }

        boolean firstDeltaSeen() {
            return firstDeltaSeen;
        }

        long lastEventNanos() {
            return lastEventNanosValue;
        }

        void cancelSubscription() {
            Flow.Subscription s = subscription;
            if (s != null) {
                s.cancel();
            }
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(String line) {
            lastEventNanosValue = System.nanoTime();
            if (future.isDone()) {
                return; // already decided; ignore any further lines
            }
            try {
                process(line);
            } catch (RuntimeException malformed) {
                fail(new IOException("Malformed SSE chunk from OpenRouter: " + malformed.getMessage(), malformed));
            }
        }

        @Override
        public void onError(Throwable t) {
            fail(t instanceof IOException ioe ? ioe : new IOException(String.valueOf(t.getMessage()), t));
        }

        @Override
        public void onComplete() {
            finalizeStream();
        }

        private void process(String line) {
            if (line.isBlank() || line.startsWith(":")) {
                return; // blank line / SSE comment (keep-alive)
            }
            if (!line.startsWith("data:")) {
                return; // ignore any other SSE field we don't care about
            }
            String data = line.substring("data:".length()).strip();
            if (data.isEmpty()) {
                return;
            }
            if ("[DONE]".equals(data)) {
                finalizeStream();
                return;
            }

            JsonObject chunk;
            try {
                JsonElement parsed = JsonParser.parseString(data);
                if (!parsed.isJsonObject()) {
                    return;
                }
                chunk = parsed.getAsJsonObject();
            } catch (RuntimeException notJson) {
                return; // tolerate a stray malformed chunk rather than killing the whole stream
            }

            if (chunk.has("error") && !chunk.get("error").isJsonNull()) {
                fail(errorFrom(chunk.get("error")));
                return;
            }

            if (chunk.has("usage") && chunk.get("usage").isJsonObject()) {
                cost = extractCost(chunk);
            }

            JsonArray choices = chunk.has("choices") && chunk.get("choices").isJsonArray()
                    ? chunk.getAsJsonArray("choices") : null;
            if (choices == null || choices.isEmpty()) {
                return; // e.g. the trailing usage-only chunk
            }

            JsonObject choice = choices.get(0).getAsJsonObject();
            if (choice.has("error") && !choice.get("error").isJsonNull()) {
                fail(errorFrom(choice.get("error")));
                return;
            }

            if (choice.has("delta") && choice.get("delta").isJsonObject()) {
                JsonObject delta = choice.getAsJsonObject("delta");
                if (delta.has("content") && !delta.get("content").isJsonNull()) {
                    String piece = delta.get("content").getAsString();
                    if (!piece.isEmpty()) {
                        content.append(piece);
                        firstDeltaSeen = true;
                        observer.onDelta(piece, content.length());
                    }
                }
            }

            if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
                String fr = choice.get("finish_reason").getAsString();
                if ("error".equals(fr)) {
                    fail(new CostAwareException("OpenRouter stream reported finish_reason=error", cost));
                    return;
                }
                finishReason = fr; // recorded, NOT finalized here - usage may still be coming
            }
        }

        private CostAwareException errorFrom(JsonElement errorEl) {
            String message;
            if (errorEl.isJsonObject() && errorEl.getAsJsonObject().has("message")
                    && errorEl.getAsJsonObject().get("message").isJsonPrimitive()) {
                message = errorEl.getAsJsonObject().get("message").getAsString();
            } else {
                message = errorEl.toString();
            }
            return new CostAwareException("OpenRouter stream error: " + message, cost);
        }

        private void fail(Throwable t) {
            if (future.completeExceptionally(t)) {
                cancelSubscription();
            }
        }

        private void finalizeStream() {
            if (future.isDone()) {
                return;
            }
            String fr = finishReason != null ? finishReason : "";
            if ("length".equals(fr)) {
                future.completeExceptionally(new CostAwareException(
                        "response truncated: hit the max_tokens limit (" + maxTokens + ")", cost));
                cancelSubscription();
                return;
            }
            String finalContent = content.toString();
            if (finalContent.isBlank()) {
                future.completeExceptionally(new CostAwareException(
                        "response had empty content (finish_reason=" + fr + ")", cost));
                cancelSubscription();
                return;
            }
            sessionCost.add(cost);
            future.complete(new Completion(finalContent, cost));
            cancelSubscription();
        }
    }

    private CompletableFuture<Completion> toResult(HttpResponse<String> response) {
        String responseBody = response.body() == null ? "" : response.body();
        JsonObject json = null;
        try {
            JsonElement parsed = JsonParser.parseString(responseBody);
            if (parsed.isJsonObject()) {
                json = parsed.getAsJsonObject();
            }
        } catch (RuntimeException ignored) {
            // fall through: non-JSON body treated as opaque error text below.
        }

        boolean hasErrorField = json != null && json.has("error");
        if (response.statusCode() != 200 || hasErrorField) {
            String snippet = responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody;
            return CompletableFuture.failedFuture(
                    new IOException("OpenRouter request failed: status=" + response.statusCode() + " body=" + snippet));
        }

        double cost = extractCost(json);
        try {
            JsonArray choices = json.getAsJsonArray("choices");
            JsonObject first = choices.get(0).getAsJsonObject();
            String finishReason = first.has("finish_reason") && !first.get("finish_reason").isJsonNull()
                    ? first.get("finish_reason").getAsString() : "";
            String content = first.getAsJsonObject("message").get("content").getAsString();
            if ("length".equals(finishReason)) {
                return CompletableFuture.failedFuture(new CostAwareException(
                        "response truncated: hit the max_tokens limit (" + maxTokens + ")", cost));
            }
            if (content.isBlank()) {
                return CompletableFuture.failedFuture(new CostAwareException(
                        "response had empty content (finish_reason=" + finishReason + ")", cost));
            }
            sessionCost.add(cost);
            return CompletableFuture.completedFuture(new Completion(content, cost));
        } catch (RuntimeException e) {
            String snippet = responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody;
            return CompletableFuture.failedFuture(
                    new IOException("OpenRouter response missing choices[0].message.content: " + snippet, e));
        }
    }

    /** Defensive extraction of {@code usage.cost}: absent/null/non-numeric -> 0.0, never throws. */
    private static double extractCost(JsonObject json) {
        if (json == null || !json.has("usage") || !json.get("usage").isJsonObject()) {
            return 0.0;
        }
        JsonObject usage = json.getAsJsonObject("usage");
        if (!usage.has("cost") || usage.get("cost").isJsonNull()) {
            return 0.0;
        }
        try {
            return usage.get("cost").getAsDouble();
        } catch (RuntimeException notANumber) {
            return 0.0;
        }
    }

    /** Cumulative {@code usage.cost} across every successful request this client has made. */
    public double sessionCostUsd() {
        return sessionCost.sum();
    }

    public String model() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    /** Timeout applied to subsequent requests; does not affect requests already in flight. */
    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
