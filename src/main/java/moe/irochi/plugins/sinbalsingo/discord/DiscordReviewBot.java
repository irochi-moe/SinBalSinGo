package moe.irochi.plugins.sinbalsingo.discord;

import moe.irochi.plugins.sinbalsingo.SinBalSinGo;
import moe.irochi.plugins.sinbalsingo.moderation.CaseEngine;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.ReviewState;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Gateway bot runs in the plugin; no inbound HTTP endpoint and no message-content intent. */
public final class DiscordReviewBot extends ListenerAdapter implements AutoCloseable {

    private final ModerationService service;
    private final String guild;
    private final String channel;
    private final Set<String> roles;
    private final ReviewMessage view;
    private final JDA jda;

    public DiscordReviewBot(SinBalSinGo plugin, ModerationService service, boolean korean) {
        this.service = service;
        view = new ReviewMessage(korean);
        var cfg = plugin.getConfig();
        guild = cfg.getString("discord.guild-id");
        channel = cfg.getString("discord.channel-id");
        roles = Set.copyOf(cfg.getStringList("discord.moderator-role-ids"));
        String token = cfg.getString("discord.token").trim();

        List<String> missing = new ArrayList<>();
        if (token.isEmpty()) missing.add("discord.token");
        if (guild.isBlank()) missing.add("discord.guild-id");
        if (channel.isBlank()) missing.add("discord.channel-id");
        if (roles.isEmpty()) missing.add("discord.moderator-role-ids");
        JDA started = null;
        if (!missing.isEmpty()) {
            plugin.getLogger().warning("Discord 봇을 시작하지 않았습니다 — 설정 누락: " + String.join(", ", missing)
                    + ". 신고는 대기 상태로 남습니다.");
        } else {
            try {
                started = JDABuilder.createLight(token, List.of()).addEventListeners(this).build();
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Discord 봇 시작 실패 (" + e.getClass().getSimpleName() + ": "
                        + e.getMessage() + ") — 신고는 대기 상태로 남습니다. 봇 토큰을 확인하세요.");
            }
        }
        jda = started;
    }

    public boolean isConfigured() {
        return jda != null;
    }

    public CompletableFuture<Void> deliver(ModerationCase c) {
        if (jda == null || jda.getStatus() != JDA.Status.CONNECTED) {
            return CompletableFuture.failedFuture(new IllegalStateException("Discord에 연결되어 있지 않습니다."));
        }
        TextChannel destination = jda.getTextChannelById(channel);
        if (destination == null || !destination.getGuild().getId().equals(guild)) {
            return CompletableFuture.failedFuture(new IllegalStateException("신고 채널을 찾을 수 없습니다. discord.guild-id와 discord.channel-id를 확인하세요."));
        }
        MessageCreateData card = new MessageCreateBuilder().setEmbeds(view.card(c)).setComponents(view.controls(c))
                .setAllowedMentions(List.of()).build();
        CompletableFuture<Message> send;
        if (c.discordMessage.isEmpty()) {
            send = destination.sendMessage(card).submit();
        } else {
            if (!c.discordGuild.equals(guild) || !c.discordChannel.equals(channel)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("신고 채널이 바뀌었습니다. 원래 채널로 되돌리세요."));
            }
            // Replace also drops attachments and buttons the new card no longer has.
            send = destination.editMessageById(c.discordMessage,
                    MessageEditBuilder.fromCreateData(card).setReplace(true).build()).submit();
        }
        return send.thenCompose(message -> {
            service.store.update(c.id, x -> {
                x.discordGuild = guild;
                x.discordChannel = channel;
                x.discordMessage = message.getId();
                if (x.pending()) x.reviewState = ReviewState.SENT;
                // The case may have moved on while the card was being sent.
                x.discordDirty = x.decision != c.decision || x.enforcement != c.enforcement
                        || x.automaticEnforcement != c.automaticEnforcement || x.audit.size() != c.audit.size();
            });
            return c.discordThreadPosted ? CompletableFuture.completedFuture(null) : postChat(c, message);
        }).thenRun(() -> service.store.update(c.id, x -> {
            x.deliveryAttempts = 0;
            x.failure = "";
        }));
    }

    /** Evidence never changes, so the chat is posted once and the thread is never edited. */
    private CompletableFuture<Void> postChat(ModerationCase c, Message card) {
        CompletableFuture<ThreadChannel> thread;
        if (c.discordThread.isEmpty()) {
            thread = card.createThreadChannel(view.threadName(c)).submit().thenApply(created -> {
                service.store.update(c.id, x -> x.discordThread = created.getId());
                return created;
            });
        } else {
            ThreadChannel existing = jda.getThreadChannelById(c.discordThread);
            if (existing == null) return CompletableFuture.failedFuture(new IllegalStateException("채팅 원문 스레드를 찾을 수 없습니다."));
            thread = CompletableFuture.completedFuture(existing);
        }
        return thread.thenCompose(t -> t.sendMessage(view.chat(c)).submit())
                .thenRun(() -> service.store.update(c.id, x -> x.discordThreadPosted = true));
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        // ACK first, then execute disk/decision work on the serialized worker.
        event.deferReply(true).queue(hook -> service.execute(() -> {
            String result = view.tr("권한이 없거나, 이미 처리됐거나 내용이 바뀐 신고입니다.",
                    "You lack permission, or this report was already handled or has changed.");
            try {
                Set<String> memberRoles = new HashSet<>();
                if (event.getMember() != null) event.getMember().getRoles().forEach(r -> memberRoles.add(r.getId()));
                String actualGuild = event.getGuild() == null ? "" : event.getGuild().getId();
                String[] parts = event.getComponentId().split(":", 2);
                if (service.store.get(parts[0]) == null) {
                    result = view.tr("보관 기간이 지나 기록이 삭제된 신고입니다.",
                            "This report's record was deleted after the retention period.");
                } else if (CaseEngine.authorizedModerator(actualGuild, event.getChannelId(), memberRoles, guild, channel, roles)
                        && service.engine.decide(parts[0], actualGuild, event.getChannelId(), event.getMessageId(),
                                event.getUser().getId(), parts[1])) {
                    ModerationCase c = service.store.get(parts[0]);
                    service.notifyStaff(c);
                    result = view.tr("저장했습니다. 처리 결과는 신고 메시지에 표시됩니다.", "Saved. The result will show on the report message.");
                }
            } catch (Exception e) {
                result = view.tr("저장하지 못했습니다. 처벌이 적용됐는지 알 수 없으니 신고 메시지를 확인하세요.",
                        "Couldn't save. Check the report message to see whether the punishment was applied.");
            }
            hook.editOriginal(result).setAllowedMentions(List.of()).queue();
        }));
    }

    @Override
    public void close() {
        if (jda != null) jda.shutdownNow();
    }
}
