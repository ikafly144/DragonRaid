package net.sabafly.dragonraid.i18n;

import net.sabafly.dragonraid.config.ConfigException;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.PluginConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * MiniMessage multi-language service loading {@code lang/*.yml} files with
 * per-player locale resolution and fallback chain ({@code requested -> default -> en_US -> key}).
 */
public final class MessageService {

    private static final String FALLBACK_LOCALE = "en_US";
    private static final String PREFIX_KEY = "prefix";

    private final Path langDirectory;
    private final Supplier<PluginConfig.LanguageSettings> languageSettings;
    private final MiniMessage miniMessage;
    private volatile Map<String, Map<String, String>> bundles = Map.of();

    public MessageService(ConfigManager configManager) {
        this(
                Objects.requireNonNull(configManager, "configManager").dataFolder().resolve("lang"),
                () -> configManager.pluginConfig().language(),
                MiniMessage.miniMessage()
        );
    }

    public MessageService(Path langDirectory, Supplier<PluginConfig.LanguageSettings> languageSettings) {
        this(langDirectory, languageSettings, MiniMessage.miniMessage());
    }

    MessageService(
            Path langDirectory,
            Supplier<PluginConfig.LanguageSettings> languageSettings,
            MiniMessage miniMessage) {
        this.langDirectory = Objects.requireNonNull(langDirectory, "langDirectory");
        this.languageSettings = Objects.requireNonNull(languageSettings, "languageSettings");
        this.miniMessage = Objects.requireNonNull(miniMessage, "miniMessage");
    }

    /**
     * Loads or reloads all configured language YAML files from {@code lang/}.
     */
    public synchronized void reload() {
        PluginConfig.LanguageSettings settings = languageSettings.get();
        Set<String> localesToLoad = new LinkedHashSet<>(settings.supportedLocales());
        localesToLoad.add(settings.defaultLocale());
        localesToLoad.add(FALLBACK_LOCALE);

        Map<String, Map<String, String>> loaded = new LinkedHashMap<>();
        for (String locale : localesToLoad) {
            Path file = langDirectory.resolve(locale + ".yml");
            if (!Files.exists(file)) {
                if (locale.equals(settings.defaultLocale())) {
                    throw new ConfigException(locale + ".yml", "", "Default language file not found: " + file);
                }
                continue;
            }
            loaded.put(locale, loadBundle(file));
        }
        this.bundles = Collections.unmodifiableMap(loaded);
    }

    private static Map<String, String> loadBundle(Path path) {
        String fileName = path.getFileName().toString();
        try {
            YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                    .path(path)
                    .nodeStyle(NodeStyle.BLOCK)
                    .indent(2)
                    .build();
            CommentedConfigurationNode root = loader.load();
            Map<String, String> flat = new LinkedHashMap<>();
            flatten("", root, flat);
            return Collections.unmodifiableMap(flat);
        } catch (ConfigurateException ex) {
            throw new ConfigException(fileName, "", "Failed to load language file: " + ex.getMessage(), ex);
        }
    }

    private static void flatten(String prefix, ConfigurationNode section, Map<String, String> out) {
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : section.childrenMap().entrySet()) {
            String key = String.valueOf(entry.getKey());
            ConfigurationNode child = entry.getValue();
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            if (child.isMap()) {
                flatten(fullKey, child, out);
            } else if (!child.isList() && child.rawScalar() != null) {
                String value = child.getString();
                if (value != null) {
                    out.put(fullKey, value);
                }
            }
        }
    }

    /**
     * Resolves the effective locale tag (e.g. {@code ja_JP} or {@code en_US}) for a command sender.
     */
    public String resolveLocale(CommandSender sender) {
        PluginConfig.LanguageSettings settings = languageSettings.get();
        if (settings.perPlayerLocale() && sender instanceof Player player) {
            return resolveLocale(player.locale());
        }
        return settings.defaultLocale();
    }

    /**
     * Maps a Java {@link Locale} to a supported configured locale tag, or falls back to default.
     */
    public String resolveLocale(Locale clientLocale) {
        PluginConfig.LanguageSettings settings = languageSettings.get();
        if (clientLocale == null || !settings.perPlayerLocale()) {
            return settings.defaultLocale();
        }

        String normalized = normalizeLocaleTag(clientLocale.toLanguageTag());
        for (String supported : settings.supportedLocales()) {
            if (supported.equalsIgnoreCase(normalized)) {
                return supported;
            }
        }

        String lang = clientLocale.getLanguage().toLowerCase(Locale.ROOT);
        if (!lang.isEmpty()) {
            for (String supported : settings.supportedLocales()) {
                String supportedLang = supported.split("_")[0].toLowerCase(Locale.ROOT);
                if (supportedLang.equals(lang)) {
                    return supported;
                }
            }
        }
        return settings.defaultLocale();
    }

    private static String normalizeLocaleTag(String tag) {
        return tag == null ? "" : tag.replace('-', '_');
    }

    /**
     * Returns true if {@code key} is defined directly in {@code locale}'s bundle.
     */
    public boolean hasKey(String locale, String key) {
        Map<String, String> map = bundles.get(locale);
        return map != null && map.containsKey(key);
    }

    public Set<String> loadedLocales() {
        return bundles.keySet();
    }

    /**
     * Looks up the raw MiniMessage template for {@code key} using fallback chain:
     * {@code locale -> defaultLocale -> en_US -> key}.
     */
    public String raw(String locale, String key) {
        Objects.requireNonNull(key, "key");
        String defaultLocale = languageSettings.get().defaultLocale();

        String found = lookup(locale, key);
        if (found != null) {
            return found;
        }
        if (!Objects.equals(locale, defaultLocale)) {
            found = lookup(defaultLocale, key);
            if (found != null) {
                return found;
            }
        }
        if (!Objects.equals(locale, FALLBACK_LOCALE) && !Objects.equals(defaultLocale, FALLBACK_LOCALE)) {
            found = lookup(FALLBACK_LOCALE, key);
            if (found != null) {
                return found;
            }
        }
        return key;
    }

    private String lookup(String locale, String key) {
        if (locale == null) {
            return null;
        }
        Map<String, String> map = bundles.get(locale);
        return map != null ? map.get(key) : null;
    }

    public Component render(CommandSender sender, String key, TagResolver... placeholders) {
        return render(resolveLocale(sender), key, placeholders);
    }

    public Component render(Locale clientLocale, String key, TagResolver... placeholders) {
        return render(resolveLocale(clientLocale), key, placeholders);
    }

    public Component render(String locale, String key, TagResolver... placeholders) {
        String template = raw(locale, key);
        TagResolver resolver = buildResolver(locale, key, placeholders);
        return miniMessage.deserialize(template, resolver);
    }

    public List<Component> renderList(CommandSender sender, String key, TagResolver... placeholders) {
        return renderList(resolveLocale(sender), key, placeholders);
    }

    public List<Component> renderList(String locale, String key, TagResolver... placeholders) {
        String template = raw(locale, key);
        TagResolver resolver = buildResolver(locale, key, placeholders);
        String[] lines = template.split("\n", -1);
        List<Component> result = new ArrayList<>(lines.length);
        for (String line : lines) {
            result.add(miniMessage.deserialize(line, resolver));
        }
        return Collections.unmodifiableList(result);
    }

    public void send(CommandSender sender, String key, TagResolver... placeholders) {
        Objects.requireNonNull(sender, "sender");
        sender.sendMessage(render(sender, key, placeholders));
    }

    private TagResolver buildResolver(String locale, String key, TagResolver... placeholders) {
        TagResolver prefixResolver = PREFIX_KEY.equals(key)
                ? TagResolver.empty()
                : Placeholder.parsed(PREFIX_KEY, rawPrefix(locale));

        if (placeholders == null || placeholders.length == 0) {
            return prefixResolver;
        }
        return TagResolver.resolver(prefixResolver, TagResolver.resolver(placeholders));
    }

    private String rawPrefix(String locale) {
        String val = raw(locale, PREFIX_KEY);
        return PREFIX_KEY.equals(val) ? "" : val;
    }
}
