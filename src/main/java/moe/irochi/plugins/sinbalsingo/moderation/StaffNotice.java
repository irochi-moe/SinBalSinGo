package moe.irochi.plugins.sinbalsingo.moderation;

import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.AssessmentState;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.Decision;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.Enforcement;

/** Which in-game notice staff get about a case: a lang key under moderation.notify. */
final class StaffNotice {

    private StaffNotice() {}

    static String of(ModerationCase c) {
        if (c.assessmentState == AssessmentState.FAILED) return "assessment-failed";
        if (c.enforcement == Enforcement.FAILED) return "action-failed";
        if (c.enforcement == Enforcement.UNKNOWN) return "action-unknown";
        if (c.failure.equals("DISCORD_DELIVERY")) return "delivery-failed";
        if (c.decision == Decision.DISMISSED) return c.automaticAction == null ? "dismissed" : "rest-dismissed";
        if (c.decision == Decision.NONE) return "review-pending";
        return c.enforcement == Enforcement.CONFIRMED ? "action-taken" : "action-running";
    }

    static String ofAutomaticPart(ModerationCase c) {
        return switch (c.automaticEnforcement) {
            case CONFIRMED -> "automatic-part-taken";
            case FAILED -> "action-failed";
            case UNKNOWN -> "action-unknown";
            default -> "action-running";
        };
    }

    /** The notify-* switch in config.yml that turns the notice on or off. */
    static String notifySwitch(String notice) {
        return switch (notice) {
            case "review-pending" -> "notify-consider";
            case "dismissed", "rest-dismissed" -> "notify-no";
            case "action-running", "action-taken", "automatic-part-taken" -> "notify-yes";
            default -> "notify-error";
        };
    }
}
