package moe.irochi.plugins.sinbalsingo.moderation;

import moe.irochi.plugins.sinbalsingo.ChatHistory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ModerationCase {

    public enum AssessmentState { PENDING, RUNNING, COMPLETE, FAILED }

    public enum ReviewState { NONE, PENDING, SENT, DELIVERY_FAILED, RESOLVED }

    public enum Decision { NONE, DISMISSED, CONFIRMED, AUTOMATIC }

    public enum Enforcement { NONE, READY, DISPATCHING, CONFIRMED, FAILED, UNKNOWN }

    public record Report(UUID reporter, String name, String reason, long time) {}

    public record Audit(String actor, String action, long time) {}

    public String id = UUID.randomUUID().toString();
    public long created = System.currentTimeMillis();
    public UUID target;
    public String name;
    // The policy the assessment was made under; empty until the AI has assessed the case.
    public String policyVersion = "";
    public List<ChatHistory.Entry> evidence = List.of();
    public Set<String> fresh = new HashSet<>();
    public List<Report> reports = new ArrayList<>();
    public Assessment assessment;
    public AssessmentState assessmentState = AssessmentState.PENDING;
    public ReviewState reviewState = ReviewState.NONE;
    public Decision decision = Decision.NONE;
    public Enforcement enforcement = Enforcement.NONE;
    public List<Audit> audit = new ArrayList<>();
    // Punishments offered to moderators, lightest first; chosen is the recommendation until a moderator picks one.
    public List<Routing.Action> actions = List.of();
    public int recommended;
    public Routing.Action chosen;
    public boolean automaticCandidate;
    // A case that needs review may still hold clear findings. Those are punished at once with automaticAction, apart
    // from the moderators' decision, which then covers only the remaining findings.
    public List<Integer> automaticFindings = List.of();
    public Routing.Action automaticAction;
    public Enforcement automaticEnforcement = Enforcement.NONE;
    public String automaticOutcome = "";
    public String discordGuild = "";
    public String discordChannel = "";
    public String discordMessage = "";
    // The chat thread is posted once; the id is kept so a failed post resumes in the same thread.
    public String discordThread = "";
    public boolean discordThreadPosted;
    public boolean discordDirty;
    public int assessmentAttempts;
    public int deliveryAttempts;
    public long retryAfter;
    public String failure = "";
    public String executionOutcome = "";

    public boolean pending() {
        return decision == Decision.NONE;
    }

    /** Indices of the findings moderators see: violations, or possible ones, that cite this case's new evidence. */
    public List<Integer> reviewableFindings() {
        return IntStream.range(0, assessment.findings().size())
                .filter(i -> Routing.reviewable(assessment.findings().get(i), fresh)).boxed().toList();
    }

    /** The findings moderators decide on: the reviewable ones, less any punished automatically. */
    public List<Integer> remainingFindings() {
        return reviewableFindings().stream().filter(i -> !automaticFindings.contains(i)).toList();
    }

    public int maxSeverity(List<Integer> findings) {
        return findings.stream().mapToInt(i -> assessment.findings().get(i).severity()).max().orElse(0);
    }

    /** Names of the rules the given findings broke; only our own text, never player input, reaches a command. */
    public String reason(List<Integer> findings, boolean korean) {
        return findings.stream().map(i -> Policy.name(assessment.findings().get(i).ruleId(), korean)).distinct()
                .collect(Collectors.joining(", "));
    }
}
