package moe.irochi.plugins.sinbalsingo.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import moe.irochi.plugins.sinbalsingo.moderation.Policy;

import java.net.http.HttpClient;
import java.util.concurrent.CompletableFuture;

public record GeminiProvider(HttpClient http, String apiKey, String model, int maxTokens,
                             int timeoutSeconds, String thinkingLevel, boolean safetyBlockNone)
        implements AiProvider {

    private static final JsonObject BASE = JsonParser.parseString("""
            {
              "generationConfig": {
                "responseMimeType": "application/json"
              },
              "safetySettings": [
                {"category": "HARM_CATEGORY_HARASSMENT", "threshold": "BLOCK_NONE"},
                {"category": "HARM_CATEGORY_HATE_SPEECH", "threshold": "BLOCK_NONE"},
                {"category": "HARM_CATEGORY_SEXUALLY_EXPLICIT", "threshold": "BLOCK_NONE"},
                {"category": "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold": "BLOCK_NONE"}
              ]
            }""").getAsJsonObject();

    @Override
    public String name() {
        return "GEMINI";
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    @Override
    public CompletableFuture<String> requestJudgement(String systemPrompt, String userContent) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
        return AiProvider.post(http, url, timeoutSeconds, buildBody(systemPrompt, userContent), "x-goog-api-key", apiKey)
                .thenApply(GeminiProvider::extractText);
    }

    String buildBody(String systemPrompt, String userContent) {
        JsonObject body = BASE.deepCopy();
        JsonObject generation = body.getAsJsonObject("generationConfig");
        generation.add("responseJsonSchema", Policy.schema());
        generation.addProperty("maxOutputTokens", maxTokens);
        if (!thinkingLevel.isBlank()) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("thinkingLevel", thinkingLevel);
            generation.add("thinkingConfig", thinking);
        }
        if (!safetyBlockNone) body.remove("safetySettings");

        body.add("system_instruction", content(null, systemPrompt));
        JsonArray contents = new JsonArray();
        contents.add(content("user", userContent));
        body.add("contents", contents);
        return body.toString();
    }

    private static JsonObject content(String role, String text) {
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject content = new JsonObject();
        if (role != null) content.addProperty("role", role);
        content.add("parts", parts);
        return content;
    }

    private static String extractText(String body) {
        JsonArray candidates = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("candidates");
        if (candidates == null || candidates.isEmpty()) throw new IllegalStateException("No candidates");

        JsonObject candidate = candidates.get(0).getAsJsonObject();
        String finishReason = candidate.has("finishReason") ? candidate.get("finishReason").getAsString() : "";
        if ("MAX_TOKENS".equals(finishReason)) throw new IllegalStateException("Truncated at ai.max-tokens");

        JsonObject content = candidate.getAsJsonObject("content");
        JsonArray parts = content != null ? content.getAsJsonArray("parts") : null;
        if (parts != null) {
            for (JsonElement element : parts) {
                JsonObject part = element.getAsJsonObject();
                boolean thought = part.has("thought") && part.get("thought").getAsBoolean();
                if (!thought && part.has("text")) return part.get("text").getAsString();
            }
        }
        throw new IllegalStateException("No text (finishReason " + finishReason + ")");
    }
}
