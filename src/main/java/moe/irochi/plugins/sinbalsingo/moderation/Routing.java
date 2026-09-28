package moe.irochi.plugins.sinbalsingo.moderation;

import moe.irochi.plugins.sinbalsingo.ChatHistory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class Routing {

    public enum Outcome { CLOSE, AUTOMATIC_CANDIDATE, REVIEW }

    public record RuleConfig(boolean automatic, int confidence) {}

    /** A punishment moderators can choose; the AI recommends it from {@code minSeverity} upwards. */
    public record Action(String label, String command, int minSeverity, boolean automaticSafe, Map<String, String> labels) {

        public Action {
            // Cases saved before punishments were named per language have no labels.
            labels = labels == null ? Map.of() : Map.copyOf(labels);
        }

        public Action(String label, String command, int minSeverity, boolean automaticSafe) {
            this(label, command, minSeverity, automaticSafe, Map.of());
        }

        /** The name in the first of these languages that has one, else {@code label}. */
        public String labelIn(String... languages) {
            for (String language : languages) {
                String name = labels.get(language);
                if (name != null) return name;
            }
            return label;
        }
    }

    /** A label is one name for everyone, or names by language code: {ko: 경고, en: warning}. */
    static Map<String, String> labels(Object label) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (label instanceof Map<?, ?> byLanguage) {
            byLanguage.forEach((language, name) ->
                    labels.put(Objects.toString(language).trim().toLowerCase(Locale.ROOT), Objects.toString(name, "").trim()));
        }
        return labels;
    }

    /** The punishments are lightest first. */
    public record Config(Map<String, RuleConfig> rules, List<Action> actions) {}

    /** {@code clear} holds the indices of the reviewable findings that need no moderator. */
    public record Result(Outcome outcome, List<Integer> clear) {}

    public static Result route(Assessment assessment, List<ChatHistory.Entry> evidence, Set<String> fresh, Config config) {
        List<Integer> clear = new ArrayList<>();
        boolean review = false;
        for (int i = 0; i < assessment.findings().size(); i++) {
            Assessment.Finding f = assessment.findings().get(i);
            if (!reviewable(f, fresh)) continue;
            if (isClear(f, evidence, config)) clear.add(i);
            else review = true;
        }
        Outcome outcome = review ? Outcome.REVIEW : clear.isEmpty() ? Outcome.CLOSE : Outcome.AUTOMATIC_CANDIDATE;
        return new Result(outcome, List.copyOf(clear));
    }

    /** A violation, or a possible one, that cites evidence no earlier case owns. */
    public static boolean reviewable(Assessment.Finding f, Set<String> fresh) {
        return f.status() != Assessment.Status.NOT_VIOLATION && f.evidenceIds().stream().anyMatch(fresh::contains);
    }

    /**
     * Whether a finding may be punished without a moderator. Assessment.parse has already rejected an established
     * finding with open questions, alternatives, missing context, an applicable exception or an unmet rule check.
     */
    private static boolean isClear(Assessment.Finding f, List<ChatHistory.Entry> evidence, Config config) {
        RuleConfig rule = config.rules().get(f.ruleId());
        return f.status() == Assessment.Status.ESTABLISHED && Policy.rule(f.ruleId()).autoEligible() && rule.automatic()
                && f.confidence() >= rule.confidence() && !lacksPattern(f) && !citesWeaponName(f, evidence);
    }

    /** A rule that needs a pattern, such as persistent harassment, is not shown by a single message. */
    public static boolean lacksPattern(Assessment.Finding f) {
        return Policy.rule(f.ruleId()).requiredCheck().equals("targeted-pattern") && f.evidenceIds().size() < 2;
    }

    /** A weapon name shows what an item was called, not that the killer wrote it or that anyone saw it. */
    public static boolean citesWeaponName(Assessment.Finding f, List<ChatHistory.Entry> evidence) {
        return evidence.stream().anyMatch(e -> e.type() == ChatHistory.Type.KILL && f.evidenceIds().contains(e.id()));
    }

    public static int recommend(List<Action> actions, int severity) {
        int pick = 0;
        for (int i = 0; i < actions.size(); i++) {
            if (severity >= actions.get(i).minSeverity()) pick = i;
        }
        return pick;
    }
}
