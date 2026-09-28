package moe.irochi.plugins.sinbalsingo;

import moe.irochi.plugins.sinbalsingo.ai.AiJudge;
import moe.irochi.plugins.sinbalsingo.ai.AiProvider;
import moe.irochi.plugins.sinbalsingo.ai.GeminiProvider;
import moe.irochi.plugins.sinbalsingo.ai.OpenAiProvider;
import moe.irochi.plugins.sinbalsingo.commands.ReportCommand;
import moe.irochi.plugins.sinbalsingo.commands.SinBalSinGoCommand;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationService;
import moe.irochi.plugins.sinbalsingo.listeners.ChatLogListener;
import moe.irochi.plugins.sinbalsingo.listeners.GuRoYeokSiBalHook;
import org.bstats.bukkit.Metrics;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Settings are read without inline defaults: a key missing from the server's config.yml falls back to the bundled
 * config.yml, so each default is written once.
 */
public final class SinBalSinGo extends JavaPlugin {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private LanguageManager languageManager;
    private ChatHistory chatHistory;
    private AiJudge aiJudge;
    private ModerationService moderation;

    private volatile boolean logCancelledChat;
    private volatile boolean logKills;
    private volatile List<String> whisperCommandPrefixes;
    private volatile List<String> replyCommandPrefixes;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        languageManager = new LanguageManager(this);
        chatHistory = new ChatHistory();
        aiJudge = new AiJudge(getLogger());
        applyConfig();
        try {
            moderation = new ModerationService(this);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            getLogger().severe("신고 처리를 시작하지 못해 플러그인을 비활성화합니다: " + reason);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        ChatLogListener chatLogListener = new ChatLogListener(this);
        getServer().getPluginManager().registerEvents(chatLogListener, this);
        if (getServer().getPluginManager().getPlugin("GuRoYeokSiBal") != null) {
            getServer().getPluginManager().registerEvents(new GuRoYeokSiBalHook(chatLogListener), this);
            getLogger().info("GuRoYeokSiBal 감지 — 차단 사유·공개 채널 연동 활성화");
        }
        register("report", new ReportCommand(this));
        register("sinbalsingo", new SinBalSinGoCommand(this));

        if (getConfig().getBoolean("bstats")) {
            new Metrics(this, 34367);
        }
    }

    private <T extends CommandExecutor & TabCompleter> void register(String name, T handler) {
        PluginCommand command = Objects.requireNonNull(getCommand(name), name);
        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }

    private void applyConfig() {
        languageManager.load(getConfig().getString("fallback-language"));
        chatHistory.configure(getConfig().getInt("history-max-entries"), getConfig().getInt("history-max-age-minutes"));
        aiJudge.configure(buildProviders());

        logCancelledChat = getConfig().getBoolean("log-cancelled-chat");
        logKills = getConfig().getBoolean("log-kills");
        whisperCommandPrefixes = commandPrefixes("whisper-commands");
        replyCommandPrefixes = commandPrefixes("reply-commands");

        if (!aiJudge.isConfigured()) {
            getLogger().warning("AI API 키가 없습니다 — 신고는 저장되지만 판정은 대기/실패 상태로 남습니다.");
        }
        getLogger().info("준비 완료 — 제공자: " + aiJudge.describeProviders("미설정"));
    }

    private List<String> commandPrefixes(String key) {
        return getConfig().getStringList(key).stream()
                .map(cmd -> "/" + cmd.trim().toLowerCase(Locale.ROOT) + " ")
                .toList();
    }

    private List<AiProvider> buildProviders() {
        int maxTokens = getConfig().getInt("ai.max-tokens");
        int timeout = getConfig().getInt("ai.timeout-seconds");
        AiProvider gemini = new GeminiProvider(httpClient,
                getConfig().getString("ai.gemini.api-key"),
                getConfig().getString("ai.gemini.model"),
                maxTokens, timeout,
                getConfig().getString("ai.gemini.thinking-level"),
                getConfig().getBoolean("ai.gemini.safety-block-none"));
        AiProvider openai = new OpenAiProvider(httpClient,
                getConfig().getString("ai.openai.api-key"),
                getConfig().getString("ai.openai.model"),
                maxTokens, timeout,
                getConfig().getString("ai.openai.reasoning-effort"));

        boolean openaiFirst = "OPENAI".equalsIgnoreCase(getConfig().getString("ai.primary-provider"));
        List<AiProvider> ordered = openaiFirst ? List.of(openai, gemini) : List.of(gemini, openai);
        return getConfig().getBoolean("ai.fallback-enabled") ? ordered : List.of(ordered.get(0));
    }

    public void reloadPluginConfig() {
        reloadConfig();
        applyConfig();
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public ChatHistory getChatHistory() {
        return chatHistory;
    }

    public AiJudge getAiJudge() {
        return aiJudge;
    }

    public ModerationService getModeration() {
        return moderation;
    }

    public boolean isLogCancelledChat() {
        return logCancelledChat;
    }

    public boolean isLogKills() {
        return logKills;
    }

    public List<String> getWhisperCommandPrefixes() {
        return whisperCommandPrefixes;
    }

    public List<String> getReplyCommandPrefixes() {
        return replyCommandPrefixes;
    }

    public int getReportCooldownSeconds() {
        return getConfig().getInt("report-cooldown-seconds");
    }

    public int getContextMaxEntries() {
        return Math.max(1, Math.min(300, getConfig().getInt("context-max-entries")));
    }

    @Override
    public void onDisable() {
        if (moderation != null) moderation.close();
    }
}
