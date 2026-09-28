package moe.irochi.plugins.sinbalsingo.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import moe.irochi.plugins.sinbalsingo.SinBalSinGo;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;

public class ChatLogListener implements Listener {

    public enum BlockReason { COOLDOWN, FILTER }

    /** How a logged message ended: sent, stopped by the filter, or cancelled by another plugin. */
    private enum Delivery { SENT, FILTERED, BLOCKED }

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final SinBalSinGo plugin;
    private final ConcurrentHashMap<UUID, String> lastWhisperPartner = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, BlockReason> blockReasons = new ConcurrentHashMap<>();

    private volatile BiPredicate<Player, String> publicChatCheck = (player, message) -> true;
    private volatile BooleanSupplier resendsCancelledChat = () -> false;

    public ChatLogListener(SinBalSinGo plugin) {
        this.plugin = plugin;
    }

    public void setPublicChatCheck(BiPredicate<Player, String> check) {
        this.publicChatCheck = check;
    }

    public void setResendsCancelledChat(BooleanSupplier check) {
        this.resendsCancelledChat = check;
    }

    public void noteBlock(UUID player, BlockReason reason) {
        blockReasons.put(player, reason);
    }

    /**
     * Null when the message is not logged: a cooldown block, or a cancelled message when those are not logged.
     * When {@code resent}, a cancel with no noted reason is the chat plugin sending the message itself.
     */
    private Delivery delivery(boolean cancelled, boolean resent, UUID player) {
        BlockReason reason = blockReasons.remove(player);
        if (!cancelled) return Delivery.SENT;
        if (reason == BlockReason.COOLDOWN) return null;
        if (reason == BlockReason.FILTER) return Delivery.FILTERED;
        if (resent) return Delivery.SENT;
        return plugin.isLogCancelledChat() ? Delivery.BLOCKED : null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        // Azurite and CMI cancel every chat message and send it themselves, so their cancel is not a block.
        Delivery delivery = delivery(event.isCancelled(), resendsCancelledChat.getAsBoolean(), player.getUniqueId());
        if (delivery == null) return;

        String message = PLAIN.serialize(event.originalMessage());
        if (!publicChatCheck.test(player, message)) return;
        plugin.getChatHistory().addChat(player.getUniqueId(), player.getName(), message,
                delivery == Delivery.FILTERED, delivery == Delivery.BLOCKED);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        // MONITOR sees the final state: a command that is not cancelled runs, so its whisper was sent.
        Delivery delivery = delivery(event.isCancelled(), false, player.getUniqueId());
        if (delivery == null) return;

        String raw = stripNamespace(event.getMessage());
        String lower = raw.toLowerCase(Locale.ROOT);

        String whisperPrefix = findPrefix(lower, plugin.getWhisperCommandPrefixes());
        if (whisperPrefix != null) {
            int targetStart = skipSpaces(raw, whisperPrefix.length());
            int spaceIdx = raw.indexOf(' ', targetStart);
            if (spaceIdx >= 0) {
                int msgStart = skipSpaces(raw, spaceIdx + 1);
                Player target = plugin.getServer().getPlayerExact(raw.substring(targetStart, spaceIdx));
                if (msgStart < raw.length() && target != null) {
                    recordWhisper(player, target, raw.substring(msgStart), delivery);
                    return;
                }
            }
        }

        String replyPrefix = findPrefix(lower, plugin.getReplyCommandPrefixes());
        if (replyPrefix != null) {
            int msgStart = skipSpaces(raw, replyPrefix.length());
            if (msgStart >= raw.length()) return;

            String partnerName = lastWhisperPartner.get(player.getUniqueId());
            Player partner = partnerName != null ? plugin.getServer().getPlayerExact(partnerName) : null;
            if (partner != null) recordWhisper(player, partner, raw.substring(msgStart), delivery);
        }
    }

    private static String findPrefix(String lower, List<String> prefixes) {
        return prefixes.stream().filter(lower::startsWith).findFirst().orElse(null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.isLogKills()) return;

        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) return;

        ItemMeta meta = killer.getInventory().getItemInMainHand().getItemMeta();
        String weaponName = meta != null && meta.hasDisplayName()
                ? PLAIN.serialize(meta.displayName()) : "";
        plugin.getChatHistory().addKill(killer.getUniqueId(), killer.getName(), victim.getName(), weaponName);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastWhisperPartner.remove(event.getPlayer().getUniqueId());
        blockReasons.remove(event.getPlayer().getUniqueId());
    }

    private void recordWhisper(Player sender, Player target, String message, Delivery delivery) {
        plugin.getChatHistory().addWhisper(sender.getUniqueId(), sender.getName(), target.getName(), message,
                delivery == Delivery.FILTERED, delivery == Delivery.BLOCKED);
        lastWhisperPartner.put(sender.getUniqueId(), target.getName());
        lastWhisperPartner.put(target.getUniqueId(), sender.getName());
    }

    private static String stripNamespace(String raw) {
        int firstSpace = raw.indexOf(' ');
        int colon = raw.indexOf(':');
        return colon > 1 && (firstSpace < 0 || colon < firstSpace)
                ? "/" + raw.substring(colon + 1) : raw;
    }

    private static int skipSpaces(String s, int from) {
        int i = from;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return i;
    }
}
