package moe.irochi.plugins.sinbalsingo.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import moe.irochi.plugins.sinbalsingo.moderation.Policy;

import java.net.http.HttpClient;
import java.util.concurrent.CompletableFuture;

public record OpenAiProvider(HttpClient http, String apiKey, String model,
                             int maxTokens, int timeoutSeconds, String reasoningEffort) implements AiProvider {

    private static final JsonObject BASE = JsonParser.parseString("""
            {
              "response_format": {
                "type": "json_schema",
                "json_schema": {"name": "moderation_assessment", "strict": true}
              }
            }""").getAsJsonObject();

    @Override
    public String name() {
        return "OPENAI";
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    @Override
    public CompletableFuture<String> requestJudgement(String systemPrompt, String userContent) {
        return AiProvider.post(http, "https://api.openai.com/v1/chat/completions", timeoutSeconds,
                buildBody(systemPrompt, userContent), "authorization", "Bearer " + apiKey)
                .thenApply(OpenAiProvider::extractText);
    }

    String buildBody(String systemPrompt, String userContent) {
        JsonObject body = BASE.deepCopy();
        body.getAsJsonObject("response_format").getAsJsonObject("json_schema").add("schema", Policy.schema());
        body.addProperty("model", model);
        if (!reasoningEffort.isBlank()) body.addProperty("reasoning_effort", reasoningEffort);
        body.addProperty("max_completion_tokens", maxTokens);
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userContent));
        body.add("messages", messages);
        return body.toString();
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static String extractText(String body) {
        JsonArray choices = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) throw new IllegalStateException("No choices");

        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message.has("refusal") && !message.get("refusal").isJsonNull()) throw new IllegalStateException("Refused");
        if (!message.has("content") || message.get("content").isJsonNull()) throw new IllegalStateException("No content");
        return message.get("content").getAsString();
    }
}
