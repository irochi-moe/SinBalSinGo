package moe.irochi.plugins.sinbalsingo.discord;

import moe.irochi.plugins.sinbalsingo.ChatHistory;
import moe.irochi.plugins.sinbalsingo.moderation.Assessment;
import moe.irochi.plugins.sinbalsingo.moderation.CaseEngine;
import moe.irochi.plugins.sinbalsingo.moderation.CaseStore;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.Enforcement;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.Report;
import moe.irochi.plugins.sinbalsingo.moderation.Policy;
import moe.irochi.plugins.sinbalsingo.moderation.Routing;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static moe.irochi.plugins.sinbalsingo.moderation.Assessment.Status.ESTABLISHED;
import static moe.irochi.plugins.sinbalsingo.moderation.Assessment.Status.SUSPECTED;
import static moe.irochi.plugins.sinbalsingo.moderation.Setup.rules;
import static moe.irochi.plugins.sinbalsingo.moderation.Setup.sent;
import static org.junit.jupiter.api.Assertions.*;

class ReviewMessageTest {

    static final Routing.Action WARN = new Routing.Action("경고", "litebans:warn {target} {reason}", 0, true);
    static final Routing.Action LONG_MUTE = new Routing.Action("1일 채팅 금지", "litebans:tempmute {target} 1d {reason}", 60, false);

    @TempDir Path temp;
    final UUID target = UUID.randomUUID();
    final ReviewMessage view = new ReviewMessage(true);
    final String hostile = "**굵게** @everyone <@123> [링크](https://evil.example) `코드` ||가림||";
    long clock = System.currentTimeMillis();

    static Routing.Action mute(boolean automatic) {
        return new Routing.Action("30분 채팅 금지", "litebans:tempmute {target} 30m {reason}", 30, automatic);
    }

    ChatHistory.Entry entry(UUID sender, String name, ChatHistory.Type type, String text, boolean filtered) {
        return new ChatHistory.Entry(UUID.randomUUID().toString(), clock += 1000, type, sender, name,
                type == ChatHistory.Type.CHAT ? null : "Victim", text, filtered, false);
    }

    Assessment.Finding finding(String rule, Assessment.Status status, ChatHistory.Entry e, String filler) {
        boolean suspected = status == SUSPECTED;
        return new Assessment.Finding(rule, Policy.rule(rule).category(), status, 45, suspected ? 60 : 99, List.of(e.id()),
                suspected ? List.of(filler, filler) : List.of(), List.of(),
                suspected ? List.of(filler, filler, filler, filler) : List.of(),
                filler, "checked", !suspected, List.of(new Assessment.ExceptionCheck("exception", suspected, filler)));
    }

    Assessment mixed(ChatHistory.Entry a, ChatHistory.Entry b) {
        return new Assessment("요약", List.of(finding("R01", ESTABLISHED, a, "설명"), finding("R03", SUSPECTED, b, "설명")));
    }

    String chatFile(ModerationCase c) throws Exception {
        var chat = view.chat(c);
        assertTrue(chat.getEmbeds().isEmpty());
        assertEquals(List.of("chat-" + c.name + ".txt"), chat.getFiles().stream().map(FileUpload::getName).toList());
        try (var data = chat.getFiles().get(0).getData()) {
            return new String(data.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String field(MessageEmbed embed, String name) {
        return embed.getFields().stream().filter(f -> name.equals(f.getName())).findFirst().orElseThrow().getValue();
    }

    List<String> buttons(ModerationCase c) {
        return view.controls(c).get(0).getButtons().stream().map(Button::getLabel).toList();
    }

    void sendable(MessageEmbed embed) {
        assertTrue(embed.isSendable() && embed.getFields().size() <= 25, "length " + embed.getLength());
        String text = embed.getDescription() + embed.getFields().stream().map(MessageEmbed.Field::getValue).toList();
        assertFalse(text.contains("[링크](https"));
        assertFalse(text.contains("<@123>"));
    }

    @Test void worstCaseFitsOneCardAndPlayerTextCannotFormat() throws Exception {
        try (var store = new CaseStore(temp)) {
            var engine = new CaseEngine(store);
            List<ChatHistory.Entry> log = new ArrayList<>();
            log.add(entry(UUID.randomUUID(), "Other_Player", ChatHistory.Type.CHAT, "앞선 대화", false));
            for (int i = 0; i < 25; i++) {
                log.add(entry(target, "Bad_Guy", ChatHistory.Type.CHAT, hostile + " " + "가".repeat(200), i % 2 == 0));
            }
            log.add(entry(target, "Bad_Guy", ChatHistory.Type.KILL, "욕설 무기", false));
            var submitted = engine.submit(target, "Bad_Guy", log, new Report(UUID.randomUUID(), "Reporter", hostile, clock));
            List<Assessment.Finding> findings = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                findings.add(finding(i % 2 == 0 ? "R01" : "R03", i % 3 == 0 ? ESTABLISHED : SUSPECTED, log.get(1 + i),
                        hostile + "나".repeat(3800)));
            }
            engine.assessed(submitted.id, new Assessment(hostile + "다".repeat(3900), findings), rules(WARN), false);
            ModerationCase c = store.get(submitted.id);

            MessageEmbed card = view.card(c);
            sendable(card);
            assertEquals("Bad\\_Guy · 검토 대기", card.getTitle());
            assertTrue(card.getDescription().contains("\\*\\*굵게\\*\\*"));
            assertTrue(field(card, "문제가 된 발언").contains("개는 스레드에서 볼 수 있습니다"));
            assertTrue(field(card, "나머지 AI 판단").contains("공간이 부족해 생략했습니다"));
            assertEquals("관리자 판단을 기다립니다.", field(card, "진행 상황"), "the status always survives trimming");
            assertDoesNotThrow(() -> new MessageCreateBuilder().setEmbeds(card).setComponents(view.controls(c)).build());

            String file = chatFile(c);
            assertTrue(file.contains("▶ ") && file.contains("[필터에 막힘]") && file.contains("[처치] Bad_Guy → Victim"));
            assertTrue(file.contains(hostile), "the file keeps the original text");
            assertEquals(log.size(), file.lines().filter(l -> l.matches("[▶ ] \\d{4}-.*")).count(), "every entry is kept");
            assertFalse(file.contains(c.id) || view.threadName(c).contains(c.id.substring(0, 8)));
        }
    }

    @Test void cardFollowsTheDecision() throws Exception {
        try (var store = new CaseStore(temp)) {
            var engine = new CaseEngine(store);
            var other = entry(UUID.randomUUID(), "Bob", ChatHistory.Type.CHAT, "먼저 한 말", false);
            var a = entry(target, "Player", ChatHistory.Type.CHAT, "첫 발언", false);
            var b = entry(target, "Player", ChatHistory.Type.CHAT, "둘째 발언", true);
            var report = new Report(UUID.randomUUID(), "Reporter", "욕설", clock);
            var id = engine.submit(target, "Player", List.of(other, a, b), report).id;
            engine.assessed(id, mixed(a, b), rules(WARN, mute(false), LONG_MUTE), false);
            var c = sent(store, id);

            String quoted = field(view.card(c), "문제가 된 발언");
            assertFalse(quoted.contains("먼저 한 말"), "context lives in the thread, not the card");
            assertTrue(quoted.endsWith(":t>  둘째 발언  *(필터에 막힘)*"));
            assertEquals("**위반 의심** · 심각도 보통 · 확신도 60\n설명\n확인할 점: 설명\n확인할 점: 설명\n다른 해석: 설명",
                    field(view.card(c), "AI 판단 2 · 인신공격"));
            assertEquals("신고: Reporter(욕설) · #" + c.id.substring(0, 8), view.card(c).getFooter().getText());
            String file = chatFile(c);
            assertTrue(file.contains("  Bob: 먼저 한 말") && file.contains("▶ ") && file.contains("Player: 둘째 발언  [필터에 막힘]"));
            assertEquals(List.of("30분 채팅 금지 (추천)", "경고", "1일 채팅 금지", "문제 없음"), buttons(c));
            assertEquals("AI 추천: 30분 채팅 금지 (심각도 보통)", field(view.card(c), "처벌"));

            assertTrue(engine.decide(c.id, "g", "c", "m", "123456", "confirm:0"));
            c = store.get(c.id);
            assertTrue(view.controls(c).isEmpty());
            assertEquals("Player · 처벌 중", view.card(c).getTitle());
            assertEquals("경고", field(view.card(c), "처벌"), "the moderator's pick, not the recommendation");
            assertTrue(field(view.card(c), "진행 상황").startsWith("<@123456> 님이 위반으로 판정했습니다"));
        }
    }

    @Test void cardShowsTheAutomaticPartAndAsksOnlyAboutTheRest() throws Exception {
        try (var store = new CaseStore(temp)) {
            var engine = new CaseEngine(store);
            var a = entry(target, "Player", ChatHistory.Type.CHAT, "욕설", false);
            var b = entry(target, "Player", ChatHistory.Type.CHAT, "비하", false);
            var id = engine.submit(target, "Player", List.of(a, b), new Report(UUID.randomUUID(), "Reporter", "", clock)).id;
            engine.assessed(id, mixed(a, b), rules(WARN, mute(true), LONG_MUTE), true);
            var c = sent(store, id);

            assertEquals("Player · 검토 대기", view.card(c).getTitle());
            assertEquals("30분 채팅 금지 (자동)\nAI 추천 추가 처벌: 30분 채팅 금지 (심각도 보통)", field(view.card(c), "처벌"));
            assertTrue(field(view.card(c), "AI 판단 1 · 욕설 · 자동 처벌").startsWith("**위반 확실**"));
            assertEquals("확실한 욕설 판단은 자동으로 처리했습니다. 30분 채팅 금지 처벌을 실행하고 있습니다.\n"
                    + "나머지는 관리자 판단을 기다립니다.", field(view.card(c), "진행 상황"));
            assertEquals(List.of("30분 채팅 금지 (추천)", "경고", "1일 채팅 금지", "추가 처벌 없음"), buttons(c));

            engine.claimAutomaticPart(id);
            engine.automaticPartResult(id, Enforcement.FAILED, "Command dispatcher returned false");
            c = store.get(id);
            assertEquals("Player · 처벌 실패", view.card(c).getTitle(), "a failure outranks the pending review");

            store.update(id, x -> x.automaticEnforcement = Enforcement.CONFIRMED);
            assertTrue(engine.decide(id, "g", "c", "m", "123456", "dismiss"));
            c = store.get(id);
            assertEquals("Player · 처벌 완료", view.card(c).getTitle());
            assertEquals("30분 채팅 금지 (자동)", field(view.card(c), "처벌"));
            assertTrue(field(view.card(c), "진행 상황").startsWith("확실한 욕설 판단은 자동으로 처리했습니다. 30분 채팅 금지 처벌을 적용했습니다.\n"
                    + "<@123456> 님이 나머지는 위반이 아니라고 판단했습니다"));
            assertTrue(view.controls(c).isEmpty());
        }
    }

    @Test void dismissedCardHasNoAction() throws Exception {
        try (var store = new CaseStore(temp)) {
            var engine = new CaseEngine(store);
            var a = entry(target, "Player", ChatHistory.Type.CHAT, "발언", false);
            var id = engine.submit(target, "Player", List.of(a), new Report(UUID.randomUUID(), "Reporter", "", clock)).id;
            engine.assessed(id, new Assessment("요약", List.of(finding("R01", ESTABLISHED, a, "설명"))), rules(WARN), false);
            var c = sent(store, id);
            assertEquals(List.of("위반 맞음 · 경고", "문제 없음"), buttons(c));
            assertTrue(engine.decide(id, "g", "c", "m", "123456", "dismiss"));
            c = store.get(id);
            assertEquals("Player · 문제 없음", view.card(c).getTitle());
            assertEquals("없음", field(view.card(c), "처벌"));
            assertTrue(field(view.card(c), "진행 상황").startsWith("<@123456> 님이 위반이 아니라고 판단했습니다"));
        }
    }
}
