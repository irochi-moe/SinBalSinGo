package moe.irochi.plugins.sinbalsingo.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public interface AiProvider {

    String name();

    String model();

    boolean isConfigured();

    CompletableFuture<String> requestJudgement(String systemPrompt, String userContent);

    /** Posts a JSON body and returns the response body. Any status but 200 fails without the body, which may hold secrets. */
    static CompletableFuture<String> post(HttpClient http, String url, int timeoutSeconds, String body, String... headers) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("content-type", "application/json")
                .headers(headers)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(response -> {
            if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
            return response.body();
        });
    }
}
