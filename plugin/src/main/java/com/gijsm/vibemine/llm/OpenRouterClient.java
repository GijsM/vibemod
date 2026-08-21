package com.gijsm.vibemine.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Thin async client for the OpenRouter chat-completions API. Holds one shared
 * {@link HttpClient} instance; never logs or exposes the API key.
 */
public final class OpenRouterClient {

    private static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/chat/completions");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** One message in a chat exchange. */
    public record ChatMessage(String role, String content) {
    }

    private final String apiKey;
    private volatile String model;
    private final Duration timeout;

    public OpenRouterClient(String apiKey, String model, Duration timeout) {
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
    }

    /** POST to the chat-completions endpoint; returns the assistant message text. */
    public CompletableFuture<String> complete(String systemPrompt, List<ChatMessage> messages) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", 16000);
        body.addProperty("temperature", 0.4);

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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(ENDPOINT)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("HTTP-Referer", "https://github.com/gijsm/vibemine")
                .header("X-Title", "VibeMine")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(this::toResult);
    }

    private CompletableFuture<String> toResult(HttpResponse<String> response) {
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

        try {
            JsonArray choices = json.getAsJsonArray("choices");
            JsonObject first = choices.get(0).getAsJsonObject();
            String content = first.getAsJsonObject("message").get("content").getAsString();
            return CompletableFuture.completedFuture(content);
        } catch (RuntimeException e) {
            String snippet = responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody;
            return CompletableFuture.failedFuture(
                    new IOException("OpenRouter response missing choices[0].message.content: " + snippet, e));
        }
    }

    public String model() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
