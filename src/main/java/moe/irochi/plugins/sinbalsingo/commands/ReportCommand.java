package moe.irochi.plugins.sinbalsingo.commands;

import moe.irochi.plugins.sinbalsingo.ChatHistory;
import moe.irochi.plugins.sinbalsingo.LanguageManager;
import moe.irochi.plugins.sinbalsingo.SinBalSinGo;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.Report;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ReportCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final SinBalSinGo plugin;
    private final ConcurrentHashMap<UUID, Long> lastReport = new ConcurrentHashMap<>();

    public ReportCommand(SinBalSinGo plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        LanguageManager lang = plugin.getLanguageManager();
        if (!(sender instanceof Player reporter) || args.length < 1) {
            sender.sendMessage(lang.get(sender, "report.usage"));
            return true;
        }

        String target = args[0];
        if (target.equalsIgnoreCase(reporter.getName())) {
            reporter.sendMessage(lang.get(reporter, "report.self"));
            return true;
        }

        long remaining = checkCooldown(reporter);
        if (remaining > 0) {
            reporter.sendMessage(lang.get(reporter, "report.cooldown",
                    Map.of("seconds", String.valueOf(remaining))));
            return true;
        }

        List<ChatHistory.Entry> context = plugin.getChatHistory().snapshotFor(target, reporter.getName(), plugin.getContextMaxEntries());
        ChatHistory.Entry identity = context.stream().filter(e -> e.sender().equalsIgnoreCase(target))
                .reduce((first, last) -> last).orElse(null);
        if (identity == null) {
            reporter.sendMessage(lang.get(reporter, "report.no-history",
                    Map.of("target", MM.escapeTags(target))));
            return true;
        }
        long now = System.currentTimeMillis();
        lastReport.put(reporter.getUniqueId(), now);
        plugin.getModeration().submit(identity.senderId(), identity.sender(), context,
                new Report(reporter.getUniqueId(), reporter.getName(), buildReason(args), now));
        return true;
    }

    private String buildReason(String[] args) {
        if (args.length < 2) return "";

        String category = plugin.getLanguageManager().getListUnion("report.categories").stream()
                .filter(c -> c.equalsIgnoreCase(args[1]))
                .findFirst().orElse(null);
        if (category == null) {
            return String.join(" ", List.of(args).subList(1, args.length));
        }

        String detail = args.length > 2
                ? String.join(" ", List.of(args).subList(2, args.length)) : "";
        return "[" + category + "]" + (detail.isBlank() ? "" : " " + detail);
    }

    private long checkCooldown(Player reporter) {
        if (reporter.hasPermission("irochi.sinbalsingo.cooldown.bypass")) return 0;

        long cooldownMs = plugin.getReportCooldownSeconds() * 1000L;
        if (cooldownMs <= 0) return 0;

        long now = System.currentTimeMillis();
        lastReport.values().removeIf(time -> now - time >= cooldownMs);

        Long last = lastReport.get(reporter.getUniqueId());
        return last == null ? 0 : (cooldownMs - (now - last) + 999) / 1000;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> !name.equalsIgnoreCase(sender.getName())
                            && name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .toList();
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return plugin.getLanguageManager().getList(sender, "report.categories").stream()
                    .filter(category -> category.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
