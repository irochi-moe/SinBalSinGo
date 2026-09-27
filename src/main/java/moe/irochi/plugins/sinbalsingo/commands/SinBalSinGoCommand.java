package moe.irochi.plugins.sinbalsingo.commands;

import moe.irochi.plugins.sinbalsingo.LanguageManager;
import moe.irochi.plugins.sinbalsingo.SinBalSinGo;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SinBalSinGoCommand implements CommandExecutor, TabCompleter {

    private final SinBalSinGo plugin;

    public SinBalSinGoCommand(SinBalSinGo plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        LanguageManager lang = plugin.getLanguageManager();
        switch (args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "") {
            case "reload" -> {
                plugin.reloadPluginConfig();
                sender.sendMessage(lang.get(sender, "command.reload"));
            }
            case "export" -> {
                plugin.getModeration().export();
                sender.sendMessage(lang.get(sender, "moderation.admin-queued"));
            }
            case "retry" -> {
                plugin.getModeration().retryAll();
                sender.sendMessage(lang.get(sender, "moderation.admin-queued"));
            }
            case "status" -> sender.sendMessage(lang.get(sender, "command.status", Map.of(
                    "providers", plugin.getAiJudge().describeProviders(),
                    "discord", lang.getRaw(sender, plugin.getModeration().discordConfigured()
                            ? "command.status-set" : "command.status-unset"),
                    "history", String.valueOf(plugin.getChatHistory().size()))));
            default -> sender.sendMessage(lang.get(sender, "command.usage"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        return args.length == 1 ? List.of("reload", "status", "export", "retry") : List.of();
    }
}
