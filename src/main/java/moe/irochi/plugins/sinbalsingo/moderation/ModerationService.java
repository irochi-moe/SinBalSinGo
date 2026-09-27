package moe.irochi.plugins.sinbalsingo.moderation;

import moe.irochi.plugins.sinbalsingo.ChatHistory;
import moe.irochi.plugins.sinbalsingo.SinBalSinGo;
import moe.irochi.plugins.sinbalsingo.discord.DiscordReviewBot;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.AssessmentState;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.Enforcement;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.Report;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.ReviewState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ModerationService implements AutoCloseable {

    private record Dispatch(Enforcement result, String detail) {}

    private final SinBalSinGo plugin;
    public final CaseStore store;
    public final CaseEngine engine;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SinBalSinGo-cases");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> assessing = new HashSet<>();
    private final Set<String> delivering = new HashSet<>();
    private final Set<String> notifySwitches;
    private final Routing.Config config;
    private final boolean automatic;
    private final boolean korean;
    private final int maxAttempts;
    private final int retrySeconds;
    private final int deleteAfterDays;
    private final DiscordReviewBot bot;

    public ModerationService(SinBalSinGo plugin) throws IOException {
        this.plugin = plugin;
        var cfg = plugin.getConfig();
        Path root = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path storage = root.resolve(cfg.getString("moderation.persistence-directory")).normalize();
        if (!storage.startsWith(root)) throw new IllegalArgumentException("Persistence must be within plugin data directory");
        notifySwitches = Stream.of("notify-yes", "notify-no", "notify-consider", "notify-error")
                .filter(cfg::getBoolean).collect(Collectors.toSet());
        automatic = cfg.getBoolean("moderation.automatic-enforcement");
        maxAttempts = Math.max(1, cfg.getInt("moderation.max-attempts"));
        retrySeconds = Math.max(5, cfg.getInt("moderation.retry-seconds"));
        deleteAfterDays = Math.max(1, cfg.getInt("moderation.delete-after-days"));
        config = new Routing.Config(ruleConfigs(cfg), actions(cfg));
        korean = "ko".equals(cfg.getString("discord.language"));

        store = new CaseStore(storage);
        engine = new CaseEngine(store);
        bot = new DiscordReviewBot(plugin, this, korean);
        worker.scheduleWithFixedDelay(() -> safe(this::tick), 2, 5, TimeUnit.SECONDS);
        worker.scheduleWithFixedDelay(() -> safe(this::deleteExpired), 0, 1, TimeUnit.HOURS);
    }

    private static Map<String, Routing.RuleConfig> ruleConfigs(ConfigurationSection cfg) {
        Map<String, Routing.RuleConfig> rules = new HashMap<>();
        for (Policy.Rule rule : Policy.rules()) {
            String base = "moderation.rules." + rule.id();
            int confidence = cfg.getInt(base + ".confidence");
            if (confidence < 0 || confidence > 100) throw new IllegalArgumentException("confidence must be 0..100");
            rules.put(rule.id(), new Routing.RuleConfig(cfg.getBoolean(base + ".automatic-eligible"), confidence));
        }
        return Map.copyOf(rules);
    }

    /** The punishments in moderation.actions, lightest first. An entry that looks wrong stops the plugin from starting. */
    private static List<Routing.Action> actions(ConfigurationSection cfg) {
        List<Routing.Action> actions = new ArrayList<>();
        for (Map<?, ?> item : cfg.getMapList("moderation.actions")) {
            String label = Objects.toString(item.get("label"), "").trim();
            String command = Objects.toString(item.get("command"), "").trim();
            int minSeverity = item.get("min-severity") instanceof Number n ? n.intValue() : 0;
            int lighter = actions.isEmpty() ? 0 : actions.get(actions.size() - 1).minSeverity();
            boolean validLabel = !label.isEmpty() && label.length() <= 80;
            // A console command on one line, with no placeholder other than the documented three.
            boolean validCommand = command.length() <= 1000 && !command.startsWith("/")
                    && command.chars().noneMatch(ch -> ch < 32)
                    && !command.replace("{uuid}", "").replace("{target}", "").replace("{reason}", "").matches(".*[{}].*");
            if (!validLabel || !validCommand || minSeverity < lighter || minSeverity > 100) {
                throw new IllegalArgumentException("Invalid moderation action: " + label);
            }
            actions.add(new Routing.Action(label, command, minSeverity, Boolean.TRUE.equals(item.get("automatic"))));
        }
        if (actions.isEmpty() || actions.get(0).minSeverity() != 0) {
            throw new IllegalArgumentException("moderation.actions needs a first action with min-severity 0");
        }
        return List.copyOf(actions);
    }

    public boolean discordConfigured() {
        return bot.isConfigured();
    }

    public void execute(Runnable task) {
        try {
            worker.execute(() -> safe(task));
        } catch (RejectedExecutionException stopped) {
            // Persisted in-flight work is recovered on restart.
        }
    }

    private void safe(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "신고 처리 작업 실패 — 진행 상태는 저장되어 있습니다.", e);
        }
    }

    public void submit(UUID target, String name, List<ChatHistory.Entry> context, Report report) {
        execute(() -> {
            try {
                engine.submit(target, name, context, report);
            } catch (RuntimeException e) {
                feedback(report.reporter(), "submission-failed");
                throw e;
            }
            feedback(report.reporter(), "submitted");
            tick();
        });
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (ModerationCase c : store.all()) {
            if (c.automaticEnforcement == Enforcement.READY) enforce(c.id, true);
            if (c.enforcement == Enforcement.READY) enforce(c.id, false);
            if (c.pending() && c.assessmentState != AssessmentState.COMPLETE && c.assessmentAttempts < maxAttempts
                    && now >= c.retryAfter && assessing.add(c.id)) {
                assess(c);
            }
            if (c.assessmentState == AssessmentState.COMPLETE && c.chosen != null
                    && (c.discordMessage.isEmpty() || c.discordDirty || (c.pending() && !c.discordThreadPosted))
                    && c.deliveryAttempts < maxAttempts && now >= c.retryAfter && delivering.add(c.id)) {
                deliver(c.id);
            }
        }
    }

    private void deliver(String id) {
        CompletableFuture<Void> delivery;
        try {
            // JDA checks channel permissions before sending and throws synchronously; handle it like any delivery failure.
            delivery = bot.deliver(store.update(id, x -> x.deliveryAttempts++));
        } catch (RuntimeException e) {
            delivery = CompletableFuture.failedFuture(e);
        }
        delivery.whenComplete((ignored, error) -> execute(() -> {
            delivering.remove(id);
            if (error == null) return;
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            plugin.getLogger().warning("Discord 전송 실패 (사건 " + id + "): " + cause);
            ModerationCase failed = store.update(id, x -> {
                x.failure = "DISCORD_DELIVERY";
                x.retryAfter = retryAt();
                if (x.pending() && x.discordMessage.isEmpty()) x.reviewState = ReviewState.DELIVERY_FAILED;
            });
            notifyStaff(failed);
        }));
    }

    private void assess(ModerationCase c) {
        try {
            store.update(c.id, x -> {
                x.assessmentAttempts++;
                x.assessmentState = AssessmentState.RUNNING;
            });
        } catch (RuntimeException e) {
            assessing.remove(c.id);
            throw e;
        }
        plugin.getAiJudge().judge(c.target, c.name, c.reports, c.evidence, c.fresh)
                .whenComplete((assessment, error) -> execute(() -> {
                    assessing.remove(c.id);
                    try {
                        if (error == null) engine.assessed(c.id, assessment, config, automatic);
                        else assessmentFailed(c.id, "AI_OR_SCHEMA");
                        notifyStaff(store.get(c.id));
                    } catch (RuntimeException e) {
                        try {
                            assessmentFailed(c.id, "ASSESSMENT_ROUTING_OR_STORAGE");
                        } catch (RuntimeException persistenceFailure) {
                            plugin.getLogger().severe("심사 실패를 저장하지 못했습니다.");
                        }
                        throw e;
                    }
                    // Outside the try: a delivery failure must not mark a completed assessment as failed.
                    tick();
                }));
    }

    private void assessmentFailed(String id, String failure) {
        store.update(id, x -> {
            x.assessmentState = AssessmentState.FAILED;
            x.failure = failure;
            x.retryAfter = retryAt();
        });
    }

    private long retryAt() {
        return System.currentTimeMillis() + retrySeconds * 1000L;
    }

    /** Runs the decided action, or with {@code automaticPart} the one applied at once to the clear part of a case. */
    private void enforce(String id, boolean automaticPart) {
        ModerationCase c = automaticPart ? engine.claimAutomaticPart(id) : engine.claimEnforcement(id);
        if (c == null) return;
        Routing.Action action = automaticPart ? c.automaticAction : c.chosen;
        String reason = c.reason(automaticPart ? c.automaticFindings : c.remainingFindings(), korean);
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            Dispatch outcome = dispatch(c, action, reason);
            execute(() -> {
                if (automaticPart) engine.automaticPartResult(id, outcome.result(), outcome.detail());
                else engine.dispatchResult(id, outcome.result(), outcome.detail());
                ModerationCase resolved = store.get(id);
                // Reporters hear once, when the first part of the case is applied.
                Enforcement other = automaticPart ? resolved.enforcement : resolved.automaticEnforcement;
                if (outcome.result() == Enforcement.CONFIRMED && other != Enforcement.CONFIRMED) {
                    resolved.reports.stream().map(Report::reporter).distinct()
                            .forEach(reporter -> feedback(reporter, "action-taken"));
                }
                if (automaticPart) notifyStaff(resolved, StaffNotice.ofAutomaticPart(resolved), resolved.automaticAction);
                else notifyStaff(resolved);
            });
        });
    }

    /** Runs the action's console command. The punished player hears only from the punishment plugin. */
    private Dispatch dispatch(ModerationCase c, Routing.Action action, String reason) {
        if (action.command().isBlank()) return new Dispatch(Enforcement.CONFIRMED, "No command configured");
        try {
            String name = plugin.getServer().getOfflinePlayer(c.target).getName();
            if (name == null || !name.matches("[A-Za-z0-9_]{1,16}") || !name.equals(c.name)) {
                return new Dispatch(Enforcement.FAILED, "Identity validation failed; not dispatched");
            }
            String command = action.command().replace("{uuid}", c.target.toString()).replace("{target}", name)
                    .replace("{reason}", reason);
            if (!plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command)) {
                return new Dispatch(Enforcement.FAILED, "Command dispatcher returned false");
            }
            return new Dispatch(Enforcement.CONFIRMED, "Command dispatched: " + command);
        } catch (Exception e) {
            return new Dispatch(Enforcement.UNKNOWN, "Exception during dispatch; verify externally; do not replay");
        }
    }

    private void deleteExpired() {
        // Assessment and delivery callbacks still expect their case; it is deleted on the next pass.
        int deleted = store.deleteOlderThan(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(deleteAfterDays),
                id -> assessing.contains(id) || delivering.contains(id));
        if (deleted > 0) {
            plugin.getLogger().info("보관 기간 " + deleteAfterDays + "일이 지난 사건 " + deleted + "개를 삭제했습니다.");
        }
    }

    public void notifyStaff(ModerationCase c) {
        notifyStaff(c, StaffNotice.of(c), c.chosen);
    }

    private void notifyStaff(ModerationCase c, String notice, Routing.Action action) {
        if (!plugin.isEnabled() || !notifySwitches.contains(StaffNotice.notifySwitch(notice))) return;
        Map<String, String> placeholders = Map.of("target", c.name, "action", action == null ? "" : action.label(),
                "case", c.id);
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            for (Player staff : plugin.getServer().getOnlinePlayers()) {
                staff.getScheduler().run(plugin, t -> {
                    if (staff.hasPermission("irochi.sinbalsingo.notify")) {
                        staff.sendMessage(plugin.getLanguageManager().get(staff, "moderation.notify." + notice, placeholders));
                    }
                }, null);
            }
        });
    }

    private void feedback(UUID id, String key) {
        if (!plugin.isEnabled()) return;
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) {
                p.getScheduler().run(plugin, t -> p.sendMessage(plugin.getLanguageManager().get(p, "moderation." + key)), null);
            }
        });
    }

    public void export() {
        execute(() -> {
            store.export(plugin.getDataFolder().toPath().resolve("evaluation.jsonl"));
            plugin.getLogger().info("관리 작업 완료 — export: evaluation.jsonl에 저장했습니다.");
        });
    }

    /** Gives every case its attempts back, for use once an AI or Discord outage is fixed. */
    public void retryAll() {
        execute(() -> {
            for (ModerationCase c : store.all()) {
                store.update(c.id, x -> {
                    x.assessmentAttempts = 0;
                    x.deliveryAttempts = 0;
                    x.retryAfter = 0;
                });
            }
            tick();
            plugin.getLogger().info("관리 작업 완료 — retry: 모든 신고의 시도 횟수를 초기화했습니다.");
        });
    }

    @Override
    public void close() {
        bot.close();
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow();
            store.close();
        } catch (Exception e) {
            plugin.getLogger().severe("신고 저장소를 닫지 못했습니다.");
        }
    }
}
