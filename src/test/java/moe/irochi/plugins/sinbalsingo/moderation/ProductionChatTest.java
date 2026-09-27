package moe.irochi.plugins.sinbalsingo.moderation;

import com.google.gson.JsonParser;
import moe.irochi.plugins.sinbalsingo.ChatHistory;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.Decision;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.Enforcement;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.Report;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.ReviewState;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static moe.irochi.plugins.sinbalsingo.moderation.Assessment.Status.ESTABLISHED;
import static moe.irochi.plugins.sinbalsingo.moderation.Assessment.Status.NOT_VIOLATION;
import static moe.irochi.plugins.sinbalsingo.moderation.Assessment.Status.SUSPECTED;
import static moe.irochi.plugins.sinbalsingo.moderation.Setup.rules;
import static org.junit.jupiter.api.Assertions.*;

class ProductionChatTest {

    private static final UUID TARGET = uuid("PlayerA");
    private static final Routing.Action WARNING = new Routing.Action("Recorded warning", "", 0, true);

    @TempDir Path temp;

    // Curated expectations, not model-generated labels or player violation records.
    private record Fixture(String id, List<ChatHistory.Entry> evidence, String rule, Assessment.Status status, int severity,
                           String rationale) {

        Set<String> fresh() {
            return evidence.stream().filter(e -> TARGET.equals(e.senderId())).map(ChatHistory.Entry::id)
                    .collect(Collectors.toSet());
        }

        Routing.Outcome expectedRoute() {
            if (status == NOT_VIOLATION) return Routing.Outcome.CLOSE;
            return status == ESTABLISHED && Policy.rule(rule).autoEligible()
                    ? Routing.Outcome.AUTOMATIC_CANDIDATE : Routing.Outcome.REVIEW;
        }

        Assessment labeled() {
            boolean suspected = status == SUSPECTED;
            boolean allowed = status == NOT_VIOLATION;
            return new Assessment(rationale, List.of(new Assessment.Finding(rule, Policy.rule(rule).category(), status,
                    severity, allowed ? 0 : suspected ? 60 : 99, fresh().stream().sorted().toList(),
                    suspected ? List.of("허용되는 설명·인용 또는 금지 표현의 직접 사용") : List.of(), List.of(),
                    suspected ? List.of("금지 표현의 직접 사용인지 허용되는 설명·인용인지 확인할 맥락이 있는가?") : List.of(),
                    rationale, rationale, !suspected,
                    List.of(new Assessment.ExceptionCheck(Policy.rule(rule).exceptions(), allowed, rationale)))));
        }
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static List<Fixture> loadFixtures() throws IOException {
        try (var in = ProductionChatTest.class.getResourceAsStream("/production-chat-fixtures.json")) {
            String json = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            List<Fixture> fixtures = new ArrayList<>();
            for (var item : JsonParser.parseString(json).getAsJsonArray()) {
                var o = item.getAsJsonObject();
                String id = o.get("id").getAsString();
                List<ChatHistory.Entry> evidence = new ArrayList<>();
                for (var value : o.getAsJsonArray("messages")) {
                    var m = value.getAsJsonObject();
                    String sender = m.get("sender").getAsString();
                    boolean whisper = m.get("private").getAsBoolean();
                    evidence.add(new ChatHistory.Entry(uuid(id + ":" + evidence.size()).toString(),
                            1_700_000_000_000L + evidence.size() * 1000L,
                            whisper ? ChatHistory.Type.WHISPER : ChatHistory.Type.CHAT, uuid(sender), sender,
                            whisper ? (sender.equals("PlayerA") ? "PlayerB" : "PlayerA") : null,
                            m.get("text").getAsString(), m.get("filtered").getAsBoolean(), false));
                }
                fixtures.add(new Fixture(id, List.copyOf(evidence), o.get("rule").getAsString(),
                        Assessment.Status.valueOf(o.get("status").getAsString()), o.get("severity").getAsInt(),
                        o.get("rationale").getAsString()));
            }
            return fixtures;
        }
    }

    @TestFactory Stream<DynamicTest> annotatedCasesRouteAndPersistSafely() throws Exception {
        return loadFixtures().stream().map(f -> DynamicTest.dynamicTest(f.id(), () -> {
            var a = Assessment.parse(CaseStore.JSON.toJson(f.labeled()), f.evidence(), TARGET);
            assertEquals(f.expectedRoute(), Routing.route(a, f.evidence(), f.fresh(), rules(WARNING)).outcome());
            try (var store = new CaseStore(temp.resolve(f.id()))) {
                var engine = new CaseEngine(store);
                var id = engine.submit(TARGET, "PlayerA", f.evidence(), new Report(uuid("Reporter"), "Reporter", "신고", 1)).id;
                engine.assessed(id, a, rules(WARNING), false);
                var c = store.get(id);
                assertEquals(Enforcement.NONE, c.enforcement, "Review-only must not execute");
                if (f.expectedRoute() == Routing.Outcome.CLOSE) {
                    assertEquals(Decision.DISMISSED, c.decision);
                    assertNull(c.chosen);
                } else {
                    assertEquals(Decision.NONE, c.decision);
                    assertEquals(ReviewState.PENDING, c.reviewState);
                    assertEquals("Recorded warning", c.chosen.label());
                    assertEquals(f.expectedRoute() == Routing.Outcome.AUTOMATIC_CANDIDATE, c.automaticCandidate);
                }
            }
        }));
    }

    @Test void policyExamplesAreLoadedWithoutCopyingAllTestCases() throws Exception {
        var fixtures = loadFixtures();
        assertEquals(fixtures.size(), fixtures.stream().map(Fixture::id).distinct().count());
        assertTrue(Policy.rule("R02").allowed().contains("독 45초로 변경했어요"));
        for (var rule : Policy.rules()) assertTrue(Policy.prompt().contains(rule.allowed()));
        assertTrue(Policy.prompt().contains("filtered=true"));
        assertFalse(Policy.prompt().contains("시작하자마자 풀드랍나노"));
        assertFalse(Policy.prompt().contains("씨발 너 여기있길래"));
        assertFalse(Policy.prompt().contains("ㅗㅗㅗㅗㅗ"));
    }
}
