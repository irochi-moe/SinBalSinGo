package moe.irochi.plugins.sinbalsingo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class LanguageManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String[] BUNDLED = {"en", "ko", "ja"};

    private final JavaPlugin plugin;

    private volatile Map<String, YamlConfiguration> languages = new HashMap<>();
    private volatile YamlConfiguration fallback;
    private volatile String fallbackCode = "en";

    public LanguageManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(String fallbackLanguage) {
        Map<String, YamlConfiguration> loaded = new HashMap<>();

        File langDir = new File(plugin.getDataFolder(), "lang");
        langDir.mkdirs();

        for (String code : BUNDLED) {
            File file = new File(langDir, code + ".yml");
            if (!file.exists()) {
                plugin.saveResource("lang/" + code + ".yml", false);
            }
        }

        File[] files = langDir.listFiles((dir, name) ->
                !name.startsWith(".") && name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                String code = name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT);
                try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                    YamlConfiguration local = YamlConfiguration.loadConfiguration(reader);
                    var bundled = plugin.getResource("lang/" + code + ".yml");
                    if (bundled != null) try (Reader defaults = new InputStreamReader(bundled, StandardCharsets.UTF_8)) {
                        local.setDefaults(YamlConfiguration.loadConfiguration(defaults));
                    }
                    loaded.put(code, local);
                } catch (IOException e) {
                    plugin.getLogger().severe("언어 파일 로드 실패 " + name + ": " + e.getMessage());
                }
            }
        }

        String code = (fallbackLanguage == null || fallbackLanguage.isBlank())
                ? "en" : fallbackLanguage.trim().toLowerCase(Locale.ROOT);
        YamlConfiguration fb = loaded.get(code);
        if (fb == null) {
            fb = loaded.get("en");
            code = "en";
        }

        this.languages = loaded;
        this.fallback = fb;
        this.fallbackCode = code;

        plugin.getLogger().info("언어 " + loaded.size() + "개 로드 — 기본 언어: " + code);
    }

    public String languageOf(CommandSender sender) {
        // The console reads Korean, like the plugin's own logs.
        String code = sender instanceof Player player ? player.locale().getLanguage().toLowerCase(Locale.ROOT) : "ko";
        return languages.containsKey(code) ? code : fallbackCode;
    }

    public String fallbackLanguage() {
        return fallbackCode;
    }

    private YamlConfiguration resolve(CommandSender sender) {
        return languages.getOrDefault(languageOf(sender), fallback);
    }

    public String getRaw(CommandSender sender, String key) {
        YamlConfiguration cfg = resolve(sender);
        String value = cfg != null ? cfg.getString(key) : null;
        if (value == null && fallback != null) {
            value = fallback.getString(key);
        }
        return value != null ? value : key;
    }

    public Component get(CommandSender sender, String key) {
        return get(sender, key, Map.of());
    }

    public Component get(CommandSender sender, String key, Map<String, String> placeholders) {
        String raw = getRaw(sender, key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            raw = raw.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return MM.deserialize(raw);
    }

    public List<String> getList(CommandSender sender, String key) {
        YamlConfiguration cfg = resolve(sender);
        List<String> list = cfg != null ? cfg.getStringList(key) : List.of();
        if (list.isEmpty() && fallback != null) {
            list = fallback.getStringList(key);
        }
        return list;
    }

    public Set<String> getListUnion(String key) {
        Set<String> union = new LinkedHashSet<>();
        for (YamlConfiguration cfg : languages.values()) {
            union.addAll(cfg.getStringList(key));
        }
        return union;
    }
}
