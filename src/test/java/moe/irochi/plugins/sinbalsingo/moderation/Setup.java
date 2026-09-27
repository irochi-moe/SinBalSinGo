package moe.irochi.plugins.sinbalsingo.moderation;

import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.ReviewState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Setup shared by the tests. */
public final class Setup {

    private Setup() {}

    /** Every rule with its policy default and a confidence threshold of 95, offering the given punishments. */
    public static Routing.Config rules(Routing.Action... actions) {
        Map<String, Routing.RuleConfig> rules = new HashMap<>();
        Policy.rules().forEach(rule -> rules.put(rule.id(), new Routing.RuleConfig(rule.autoEligible(), 95)));
        return new Routing.Config(rules, List.of(actions));
    }

    /** Marks the case as posted to guild "g", channel "c" and message "m", where its buttons are accepted. */
    public static ModerationCase sent(CaseStore store, String id) {
        return store.update(id, c -> {
            c.discordGuild = "g";
            c.discordChannel = "c";
            c.discordMessage = "m";
            c.reviewState = ReviewState.SENT;
        });
    }
}
