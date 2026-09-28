package moe.irochi.plugins.sinbalsingo.ai;

import com.google.gson.Gson;
import moe.irochi.plugins.sinbalsingo.ChatHistory;
import moe.irochi.plugins.sinbalsingo.moderation.Assessment;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.Report;
import moe.irochi.plugins.sinbalsingo.moderation.Policy;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class AiJudge {

    private record Request(UUID reportedUUID, String reportedName, List<Report> reports, List<ChatHistory.Entry> evidence,
                           Set<String> newEvidenceIds, List<String> contextOnlyIds) {}

    private final Logger logger;
    private volatile List<AiProvider> providers = List.of();

    public AiJudge(Logger logger) {
        this.logger = logger;
    }

    public void configure(List<AiProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    public boolean isConfigured() {
        return providers.stream().anyMatch(AiProvider::isConfigured);
    }

    public String describeProviders(String unset) {
        return providers.stream()
                .map(p -> p.name() + "(" + p.model() + (p.isConfigured() ? ")" : ", " + unset + ")"))
                .collect(Collectors.joining(" → "));
    }

    /** Asks each configured provider in turn until one returns a valid assessment. */
    public CompletableFuture<Assessment> judge(UUID target, String name, List<Report> reports,
                                               List<ChatHistory.Entry> context, Set<String> fresh) {
        List<String> contextOnly = context.stream().map(ChatHistory.Entry::id).filter(id -> !fresh.contains(id)).toList();
        String data = new Gson().toJson(new Request(target, name, reports, context, fresh, contextOnly));
        CompletableFuture<Assessment> result =
                CompletableFuture.failedFuture(new IllegalStateException("AI unavailable or invalid assessment"));
        for (AiProvider p : providers) {
            if (p.isConfigured()) result = result.exceptionallyCompose(previous -> ask(p, data, context, target));
        }
        return result;
    }

    private CompletableFuture<Assessment> ask(AiProvider p, String data, List<ChatHistory.Entry> context, UUID target) {
        CompletableFuture<String> request;
        try {
            request = p.requestJudgement(Policy.prompt(), data);
        } catch (RuntimeException e) {
            request = CompletableFuture.failedFuture(e);
        }
        return request.thenApply(text -> Assessment.parse(text, context, target)).whenComplete((a, e) -> {
            // Provider errors may contain credentials or submitted evidence. Never log their body/message.
            if (e != null) logger.warning(p.name() + " 심사 실패 — 다른 제공자가 설정되어 있으면 그쪽에 요청합니다.");
        });
    }
}
