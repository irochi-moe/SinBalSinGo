package moe.irochi.plugins.sinbalsingo.discord;

import moe.irochi.plugins.sinbalsingo.ChatHistory;
import moe.irochi.plugins.sinbalsingo.moderation.Assessment;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.Audit;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.Decision;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.Enforcement;
import moe.irochi.plugins.sinbalsingo.moderation.Policy;
import moe.irochi.plugins.sinbalsingo.moderation.Routing;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * Presents a case to moderators in plain words. The card holds the AI's reading and the decision; the thread holds the
 * original chat, which never changes. Player and AI text is escaped so it cannot format, link or mention.
 */
public final class ReviewMessage {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    /** Card colors, from what least needs a moderator's attention to what most does. */
    private enum Tone {
        GREY(0x95A5A6), GREEN(0x2ECC71), BLUE(0x3498DB), ORANGE(0xE67E22), RED(0xE74C3C);

        final int rgb;

        Tone(int rgb) {
            this.rgb = rgb;
        }
    }

    private record Status(Tone tone, String title, String detail) {}

    private final boolean korean;

    public ReviewMessage(boolean korean) {
        this.korean = korean;
    }

    String tr(String ko, String en) {
        return korean ? ko : en;
    }

    public MessageEmbed card(ModerationCase c) {
        Status status = status(c);
        Set<String> cited = c.reviewableFindings().stream()
                .flatMap(i -> c.assessment.findings().get(i).evidenceIds().stream()).collect(Collectors.toSet());
        List<String> quoted = c.evidence.stream().filter(e -> cited.contains(e.id())).map(e -> line(c, e)).toList();
        EmbedBuilder card = new EmbedBuilder().setColor(status.tone().rgb).setTitle(plain(c.name) + " · " + status.title())
                .setDescription(text(c.assessment.summary(), 600))
                .addField(tr("문제가 된 발언", "Reported messages"),
                        fit(quoted, tr("외 {n}개는 스레드에서 볼 수 있어요.", "{n} more in the thread.")), false);
        var action = new MessageEmbed.Field(tr("조치", "Action"), action(c), false);
        var progress = new MessageEmbed.Field(tr("진행 상황", "Status"), status.detail(), false);
        String footer = tr("신고: ", "Reported by: ") + reporters(c) + " · #" + c.id.substring(0, 8);
        // Discord caps an embed at 6000 characters and 25 fields; findings that do not fit are counted instead. Besides
        // the findings the card holds at most four fields, and 100 characters are kept for the one that counts them.
        int maxFindings = MessageEmbed.MAX_FIELD_AMOUNT - 4;
        int reserved = length(action) + length(progress) + footer.length() + 100;
        List<MessageEmbed.Field> findings = findings(c);
        int shown = 0;
        while (shown < findings.size() && shown < maxFindings
                && card.length() + length(findings.get(shown)) + reserved <= MessageEmbed.EMBED_MAX_LENGTH_BOT) {
            card.addField(findings.get(shown++));
        }
        if (shown < findings.size()) {
            int left = findings.size() - shown;
            card.addField(tr("나머지 AI 판단", "More findings"),
                    tr("외 " + left + "개는 공간이 부족해 생략했어요.", left + " more left out for space."), false);
        }
        return card.addField(action).addField(progress).setFooter(footer).setTimestamp(Instant.ofEpochMilli(c.created))
                .build();
    }

    /** The recommended punishment first in red, then the others lightest first, then the button for no punishment. */
    public List<ActionRow> controls(ModerationCase c) {
        if (!c.pending()) return List.of();
        String prefix = c.id + ":";
        List<Button> buttons = new ArrayList<>();
        Routing.Action recommended = c.actions.get(c.recommended);
        String recommendedLabel = c.actions.size() == 1
                ? tr("위반 맞음 · ", "Violation · ") + recommended.label()
                : recommended.label() + tr(" (추천)", " (recommended)");
        buttons.add(Button.danger(prefix + "confirm:" + c.recommended, clip(recommendedLabel, Button.LABEL_MAX_LENGTH)));
        for (int i = 0; i < c.actions.size(); i++) {
            if (i != c.recommended) {
                buttons.add(Button.secondary(prefix + "confirm:" + i, clip(c.actions.get(i).label(), Button.LABEL_MAX_LENGTH)));
            }
        }
        // Once part of the case was punished automatically, the buttons only decide what to add.
        buttons.add(Button.success(prefix + "dismiss",
                c.automaticAction == null ? tr("문제 없음", "No violation") : tr("추가 처벌 없음", "Nothing more")));
        return ActionRow.partitionOf(buttons);
    }

    public String threadName(ModerationCase c) {
        return tr(c.name + " 신고", "Report: " + c.name);
    }

    public MessageCreateData chat(ModerationCase c) {
        byte[] log = chatLog(c).getBytes(StandardCharsets.UTF_8);
        return new MessageCreateBuilder().setFiles(FileUpload.fromData(log, "chat-" + c.name + ".txt")).build();
    }

    private String chatLog(ModerationCase c) {
        StringBuilder b = new StringBuilder(tr("[채팅 원문] ", "[Original chat] ")).append(c.name).append('\n')
                .append(tr("▶ = 신고 대상의 발언", "▶ = the reported player")).append('\n')
                .append(tr("※ 신고 시점 전후 일부만 담겨 있어요. 귓속말은 신고자와 신고 대상이 주고받은 것만 있어요.",
                        "* Only part of the chat around the report. Whispers only between the reporter and the player."))
                .append("\n\n");
        for (ChatHistory.Entry e : c.evidence) {
            b.append(c.target.equals(e.senderId()) ? "▶ " : "  ")
                    .append(DATE_TIME.format(Instant.ofEpochMilli(e.time()))).append("  ");
            b.append(switch (e.type()) {
                case CHAT -> e.sender() + ": " + oneLine(e.message());
                case WHISPER -> tr("[귓속말] ", "[whisper] ") + e.sender() + " → " + e.recipient() + ": " + oneLine(e.message());
                case KILL -> tr("[처치] ", "[kill] ") + e.sender() + " → " + e.recipient()
                        + tr(" · 무기 이름: ", " · weapon name: ") + oneLine(e.message());
            });
            notes(c, e).forEach(note -> b.append("  [").append(note).append(']'));
            b.append('\n');
        }
        return b.toString();
    }

    /** One cited message on the card. Findings only cite the reported player, so the sender is left out. */
    private String line(ModerationCase c, ChatHistory.Entry e) {
        String message = text(oneLine(e.message()), 300);
        String body = switch (e.type()) {
            case CHAT -> message;
            case WHISPER -> tr("귓속말 → ", "Whisper to ") + plain(e.recipient()) + ": " + message;
            case KILL -> plain(e.recipient()) + tr(" 처치, 무기 이름: ", " killed, weapon name: ") + message;
        };
        List<String> notes = notes(c, e);
        return "<t:" + e.time() / 1000 + ":t>  " + body + (notes.isEmpty() ? "" : "  *(" + String.join(", ", notes) + ")*");
    }

    /** What stopped a message, and whether an earlier case already covered it. */
    private List<String> notes(ModerationCase c, ChatHistory.Entry e) {
        List<String> notes = new ArrayList<>();
        if (e.filtered()) notes.add(tr("필터에 막힘", "blocked by filter"));
        if (e.blocked()) notes.add(tr("다른 플러그인이 막음", "blocked by another plugin"));
        if (c.target.equals(e.senderId()) && !c.fresh.contains(e.id())) {
            notes.add(tr("이전 신고에서 처리됨", "handled in an earlier case"));
        }
        return notes;
    }

    private String action(ModerationCase c) {
        String automatic = c.automaticAction == null ? "" : plain(c.automaticAction.label()) + tr(" (자동)", " (automatic)");
        String decided;
        if (c.decision == Decision.DISMISSED) {
            decided = automatic.isEmpty() ? tr("없음", "None") : "";
        } else if (!c.pending()) {
            decided = plain(c.chosen.label());
        } else {
            decided = (automatic.isEmpty() ? tr("AI 추천: ", "AI recommends: ") : tr("AI 추천 추가 조치: ", "AI recommends adding: "))
                    + plain(c.actions.get(c.recommended).label()) + " (" + tr("심각도 ", "severity ")
                    + severity(c.maxSeverity(c.remainingFindings())) + ")";
        }
        return automatic.isEmpty() || decided.isEmpty() ? automatic + decided : automatic + "\n" + decided;
    }

    private List<MessageEmbed.Field> findings(ModerationCase c) {
        List<MessageEmbed.Field> fields = new ArrayList<>();
        for (int i : c.reviewableFindings()) {
            Assessment.Finding f = c.assessment.findings().get(i);
            String name = tr("AI 판단 ", "AI finding ") + (i + 1) + " · " + Policy.name(f.ruleId(), korean)
                    + (c.automaticFindings.contains(i) ? tr(" · 자동 처리", " · automatic") : "");
            List<String> checks = new ArrayList<>(f.questions());
            checks.addAll(f.missingContext());
            if (Routing.citesWeaponName(f, c.evidence)) {
                checks.add(tr("무기 이름이 근거예요. 이 플레이어가 직접 지었는지, 다른 사람에게 보였는지 확인해 주세요.",
                        "This relies on a weapon name. Check that this player named it and that others saw it."));
            }
            if (Routing.lacksPattern(f)) {
                checks.add(tr("발언이 하나뿐이에요. 반복된 괴롭힘인지 확인해 주세요.",
                        "Only one message is cited. Check that the harassment was repeated."));
            }
            List<String> readings = new ArrayList<>(f.alternatives());
            f.exceptions().stream().filter(Assessment.ExceptionCheck::applies).forEach(e -> readings.add(e.explanation()));
            List<String> lines = new ArrayList<>();
            // Confidence is shown as the number the automatic-enforcement threshold is compared against.
            lines.add("**" + verdict(f.status()) + "** · " + tr("심각도 ", "severity ") + severity(f.severity())
                    + " · " + tr("확신도 ", "confidence ") + f.confidence());
            lines.add(text(f.explanation(), 250));
            checks.stream().limit(2).forEach(q -> lines.add(tr("확인할 점: ", "To check: ") + text(q, 150)));
            readings.stream().limit(1).forEach(r -> lines.add(tr("다른 해석: ", "Other reading: ") + text(r, 150)));
            fields.add(new MessageEmbed.Field(name, fit(lines, tr("외 {n}줄은 생략했어요.", "{n} more lines left out.")), false));
        }
        return fields;
    }

    /** Joins whole lines within one field value, then says how many were left out. */
    private static String fit(List<String> lines, String rest) {
        // Keeps room for the line that counts what was left out.
        int room = MessageEmbed.VALUE_MAX_LENGTH - rest.length() - 10;
        StringJoiner out = new StringJoiner("\n");
        int shown = 0;
        for (String line : lines) {
            if (out.length() + line.length() + 1 > room) break;
            out.add(line);
            shown++;
        }
        if (shown < lines.size()) out.add("… " + rest.replace("{n}", String.valueOf(lines.size() - shown)));
        return out.toString();
    }

    private static int length(MessageEmbed.Field field) {
        return field.getName().length() + field.getValue().length();
    }

    private String reporters(ModerationCase c) {
        List<String> names = c.reports.stream()
                .map(r -> r.name() + (r.reason().isBlank() ? "" : "(" + clip(oneLine(r.reason()), 40) + ")"))
                .distinct().toList();
        return String.join(", ", names.subList(0, Math.min(3, names.size())))
                + (names.size() > 3 ? tr(" 외 " + (names.size() - 3) + "명", " +" + (names.size() - 3)) : "");
    }

    private Status status(ModerationCase c) {
        Status decision = decision(c);
        if (c.automaticAction == null) return decision;
        String rules = c.reason(c.automaticFindings, korean);
        Status automatic = enforcement(c.automaticEnforcement, tr("확실한 " + rules + " 판단은 자동으로 처리했어요.",
                "The clear " + rules + " finding was handled automatically."), c.automaticAction);
        // The title follows the part that most needs attention: a failure, then the review, then a running action.
        Status shown = automatic.tone().compareTo(decision.tone()) > 0 ? automatic : decision;
        return new Status(shown.tone(), shown.title(), automatic.detail() + "\n" + decision.detail());
    }

    /** Where the moderators' decision stands. With an automatic part, it covers only the rest of the case. */
    private Status decision(ModerationCase c) {
        boolean rest = c.automaticAction != null;
        Optional<Audit> decision = c.audit.stream().filter(a -> Set.of("confirm", "dismiss").contains(a.action()))
                .reduce((a, b) -> b);
        String who = decision.map(a -> a.actor().matches("\\d+") ? "<@" + a.actor() + ">" : plain(a.actor()))
                .orElse(tr("관리자", "A moderator"));
        String when = decision.map(a -> " (<t:" + a.time() / 1000 + ":R>)").orElse("");
        if (c.decision == Decision.DISMISSED) {
            return new Status(Tone.GREY, tr("문제 없음", "No violation"), rest
                    ? tr(who + " 님이 나머지는 위반이 아니라고 판단했어요" + when + ". 더 처벌하지 않아요.",
                            who + " found no further violation" + when + ". Nothing more is applied.")
                    : tr(who + " 님이 위반이 아니라고 판단했어요" + when + ". 처벌하지 않아요.",
                            who + " found no violation" + when + ". No punishment."));
        }
        if (c.decision == Decision.NONE) {
            return new Status(Tone.ORANGE, tr("검토 대기", "Awaiting review"), rest
                    ? tr("나머지는 관리자 판단을 기다리고 있어요.", "The rest is waiting for a moderator.")
                    : tr("관리자 판단을 기다리고 있어요.", "Waiting for a moderator."));
        }
        String decided = c.decision == Decision.AUTOMATIC ? tr("자동 조치 대상이라 바로 처리했어요.", "Handled automatically.")
                : rest ? tr(who + " 님이 나머지도 위반으로 판정했어요" + when + ".", who + " confirmed the rest" + when + ".")
                : tr(who + " 님이 위반으로 판정했어요" + when + ".", who + " confirmed the violation" + when + ".");
        return enforcement(c.enforcement, decided, rest ? c.chosen : null);
    }

    /** How an action is going. It is named when the card reports two. */
    private Status enforcement(Enforcement state, String decided, Routing.Action named) {
        String name = named == null ? null : plain(named.label());
        String ko = name == null ? "조치" : name + " 조치";
        String en = name == null ? "The action" : name;
        return switch (state) {
            case CONFIRMED -> new Status(Tone.GREEN, tr("조치 완료", "Action taken"),
                    decided + " " + tr(ko + "를 적용했어요.", en + " was applied."));
            case FAILED -> new Status(Tone.RED, tr("조치 실패", "Action failed"), decided + " "
                    + tr(ko + "를 실행하지 못했어요. 직접 처리해 주세요.", en + " could not run. Please handle it manually."));
            case UNKNOWN -> new Status(Tone.RED, tr("결과 불명", "Result unknown"), decided + " " + tr(
                    (name == null ? "" : name + " 조치 ")
                            + "실행 중 문제가 생겨 적용 여부를 알 수 없어요. 게임에서 확인하고, 중복 처벌되지 않도록 다시 실행하지 마세요.",
                    "Something went wrong while running " + (name == null ? "it" : name)
                            + ". Check in game and do not run it again, to avoid double punishment."));
            default -> new Status(Tone.BLUE, tr("조치 중", "Taking action"),
                    decided + " " + tr(ko + "를 실행하고 있어요.", en + " is running."));
        };
    }

    private String verdict(Assessment.Status status) {
        return switch (status) {
            case ESTABLISHED -> tr("위반 확실", "Clear violation");
            case SUSPECTED -> tr("위반 의심", "Possible violation");
            case NOT_VIOLATION -> tr("위반 아님", "Not a violation");
        };
    }

    // Bands follow the severity scale the AI is instructed to use.
    private String severity(int severity) {
        if (severity >= 90) return tr("매우 심각", "extreme");
        if (severity >= 60) return tr("심각", "serious");
        if (severity >= 30) return tr("보통", "moderate");
        return tr("경미", "mild");
    }

    /** Escapes every ASCII symbol so Discord shows the text literally: no formatting, links, mentions or timestamps. */
    private static String plain(String text) {
        StringBuilder b = new StringBuilder();
        for (char ch : clean(text).toCharArray()) {
            if (ch < 128 && !Character.isLetterOrDigit(ch) && !Character.isWhitespace(ch)) b.append('\\');
            b.append(ch);
        }
        return b.toString();
    }

    private static String text(String raw, int max) {
        return plain(clip(raw, max));
    }

    private static String clip(String text, int max) {
        if (text.length() <= max) return text;
        int end = Character.isHighSurrogate(text.charAt(max - 2)) ? max - 2 : max - 1;
        return text.substring(0, end) + "…";
    }

    private static String clean(String text) {
        return text.replaceAll("[\\p{Cntrl}&&[^\n]]", " ");
    }

    private static String oneLine(String text) {
        return clean(text).replaceAll("\\s+", " ").trim();
    }
}
