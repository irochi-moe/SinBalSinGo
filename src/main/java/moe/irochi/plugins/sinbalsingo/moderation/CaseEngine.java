package moe.irochi.plugins.sinbalsingo.moderation;

import moe.irochi.plugins.sinbalsingo.ChatHistory;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.AssessmentState;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.Audit;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.Decision;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.Enforcement;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.Report;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.ReviewState;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** All transitions serialize through this engine; persistence commits before commands can be scheduled. */
public final class CaseEngine {

    private final CaseStore store;

    public CaseEngine(CaseStore store) {
        this.store = store;
    }

    public synchronized ModerationCase submit(UUID target, String name, List<ChatHistory.Entry> evidence, Report report) {
        Set<String> targetIds = new HashSet<>();
        evidence.stream().filter(e -> target.equals(e.senderId())).forEach(e -> targetIds.add(e.id()));
        if (targetIds.isEmpty()) throw new IllegalArgumentException("Missing target evidence");

        Set<String> fresh = new HashSet<>(targetIds);
        List<ModerationCase> prior = store.all().stream().filter(c -> c.target.equals(target)).toList();
        prior.forEach(c -> fresh.removeAll(c.fresh));
        if (fresh.isEmpty()) {
            ModerationCase existing = prior.stream().filter(c -> c.fresh.stream().anyMatch(targetIds::contains))
                    .max(Comparator.comparingLong(c -> c.created)).orElseThrow();
            return store.update(existing.id, c -> c.reports.add(report));
        }

        ModerationCase c = new ModerationCase();
        c.target = target;
        c.name = name;
        c.evidence = List.copyOf(evidence);
        c.fresh = fresh;
        c.reports.add(report);
        store.insert(c);
        return c;
    }

    public synchronized void assessed(String id, Assessment assessment, Routing.Config config, boolean automatic) {
        ModerationCase current = store.get(id);
        Routing.Result route = Routing.route(assessment, current.evidence, current.fresh, config);
        store.update(id, c -> {
            c.assessment = assessment;
            c.assessmentState = AssessmentState.COMPLETE;
            c.policyVersion = Policy.VERSION;
            c.failure = "";
            if (route.outcome() == Routing.Outcome.CLOSE) {
                c.decision = Decision.DISMISSED;
                c.reviewState = ReviewState.RESOLVED;
                audit(c, "policy", "CLOSE_ALLOWED_OR_CONTEXT_ONLY");
                return;
            }
            // Findings that need review do not hold back the clear ones: those are punished now, moderators decide the rest.
            if (automatic && route.outcome() == Routing.Outcome.REVIEW && !route.clear().isEmpty()) {
                Routing.Action part = config.actions().get(Routing.recommend(config.actions(), c.maxSeverity(route.clear())));
                if (part.automaticSafe()) {
                    c.automaticFindings = route.clear();
                    c.automaticAction = part;
                    c.automaticEnforcement = Enforcement.READY;
                    audit(c, "policy", "AUTOMATIC_PART");
                }
            }
            offer(c, config.actions());
            c.automaticCandidate = route.outcome() == Routing.Outcome.AUTOMATIC_CANDIDATE && c.chosen.automaticSafe();
            c.reviewState = ReviewState.PENDING;
            if (automatic && c.automaticCandidate) {
                c.decision = Decision.AUTOMATIC;
                c.enforcement = Enforcement.READY;
                c.reviewState = ReviewState.RESOLVED;
                audit(c, "policy", "AUTOMATIC");
            }
        });
    }

    /** A moderator's button press on the card at {@code guild}/{@code channel}/{@code message}. */
    public synchronized boolean decide(String id, String guild, String channel, String message, String actor,
                                       String action) {
        ModerationCase c = store.get(id);
        if (!acceptsReview(c, guild, channel, message)) return false;
        boolean dismiss = action.equals("dismiss");
        Routing.Action chosen = dismiss ? null : offered(c, action);
        if (!dismiss && chosen == null) return false;
        store.update(id, x -> {
            audit(x, actor, dismiss ? "dismiss" : "confirm");
            x.discordDirty = true;
            x.decision = dismiss ? Decision.DISMISSED : Decision.CONFIRMED;
            x.enforcement = dismiss ? Enforcement.NONE : Enforcement.READY;
            if (!dismiss) x.chosen = chosen;
            x.reviewState = ReviewState.RESOLVED;
        });
        return true;
    }

    /** The offered action a "confirm:<index>" button names, or null for anything else. */
    private static Routing.Action offered(ModerationCase c, String action) {
        if (!action.startsWith("confirm:")) return null;
        try {
            return c.actions.get(Integer.parseInt(action.substring("confirm:".length())));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static void offer(ModerationCase c, List<Routing.Action> actions) {
        c.actions = actions;
        c.recommended = Routing.recommend(actions, c.maxSeverity(c.remainingFindings()));
        c.chosen = actions.get(c.recommended);
    }

    private static void audit(ModerationCase c, String actor, String action) {
        c.audit.add(new Audit(actor, action, System.currentTimeMillis()));
    }

    public synchronized ModerationCase claimEnforcement(String id) {
        ModerationCase c = store.get(id);
        if (c.enforcement != Enforcement.READY
                || (c.decision != Decision.CONFIRMED && c.decision != Decision.AUTOMATIC)) return null;
        return store.update(id, x -> {
            x.enforcement = Enforcement.DISPATCHING;
            x.discordDirty = true;
        });
    }

    public synchronized void dispatchResult(String id, Enforcement result, String detail) {
        store.update(id, x -> {
            x.enforcement = result;
            x.executionOutcome = detail;
            x.discordDirty = true;
        });
    }

    /** The automatic part runs once, whatever the moderators decide about the rest. */
    public synchronized ModerationCase claimAutomaticPart(String id) {
        ModerationCase c = store.get(id);
        if (c.automaticEnforcement != Enforcement.READY) return null;
        return store.update(id, x -> {
            x.automaticEnforcement = Enforcement.DISPATCHING;
            x.discordDirty = true;
        });
    }

    public synchronized void automaticPartResult(String id, Enforcement result, String detail) {
        store.update(id, x -> {
            x.automaticEnforcement = result;
            x.automaticOutcome = detail;
            x.discordDirty = true;
        });
    }

    public static boolean authorizedModerator(String guild, String channel, Set<String> roles,
                                              String configuredGuild, String configuredChannel,
                                              Set<String> configuredRoles) {
        return configuredGuild.equals(guild) && configuredChannel.equals(channel)
                && roles.stream().anyMatch(configuredRoles::contains);
    }

    private static boolean acceptsReview(ModerationCase c, String guild, String channel, String message) {
        return c.pending() && c.assessmentState == AssessmentState.COMPLETE && c.reviewState == ReviewState.SENT
                && c.discordGuild.equals(guild) && c.discordChannel.equals(channel) && c.discordMessage.equals(message);
    }
}
