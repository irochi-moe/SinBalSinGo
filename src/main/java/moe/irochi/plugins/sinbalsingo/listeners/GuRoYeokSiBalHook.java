package moe.irochi.plugins.sinbalsingo.listeners;

import moe.irochi.plugins.guroyeoksibal.GuRoYeokSiBal;
import moe.irochi.plugins.guroyeoksibal.api.ChatBlockedEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class GuRoYeokSiBalHook implements Listener {

    private final ChatLogListener chatLog;

    public GuRoYeokSiBalHook(ChatLogListener chatLog) {
        this.chatLog = chatLog;

        GuRoYeokSiBal guroYeokSiBal =
                (GuRoYeokSiBal) Bukkit.getPluginManager().getPlugin("GuRoYeokSiBal");
        chatLog.setPublicChatCheck(guroYeokSiBal::shouldFilter);
    }

    @EventHandler
    public void onBlocked(ChatBlockedEvent event) {
        chatLog.noteBlock(event.getPlayer().getUniqueId(),
                event.getReason() == ChatBlockedEvent.Reason.COOLDOWN
                        ? ChatLogListener.BlockReason.COOLDOWN
                        : ChatLogListener.BlockReason.FILTER);
    }
}
