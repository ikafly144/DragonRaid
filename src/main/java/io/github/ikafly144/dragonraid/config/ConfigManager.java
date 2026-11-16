package io.github.ikafly144.dragonraid.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.bukkit.plugin.Plugin;

/**
 * Manages loading, validation, default resource extraction, and atomic hot-reload
 * of DragonRaid YAML configuration files.
 */
public final class ConfigManager {

    public static final String CONFIG_FILE = "config.yml";
    public static final String BOSS_NORMAL_FILE = "boss_normal.yml";
    public static final String BOSS_HARD_FILE = "boss_hard.yml";
    public static final String REWARDS_FILE = "rewards.yml";

    private static final List<String> DEFAULT_RESOURCES = List.of(
            CONFIG_FILE,
            BOSS_NORMAL_FILE,
            BOSS_HARD_FILE,
            REWARDS_FILE,
            "lang/ja_JP.yml",
            "lang/en_US.yml"
    );

    private record ConfigSnapshot(
            PluginConfig pluginConfig,
            BossConfig normalBossConfig,
            BossConfig hardBossConfig,
            RewardsConfig rewardsConfig
    ) {
    }

    private final Path dataFolder;
    private final Function<String, InputStream> resourceOpener;
    private volatile ConfigSnapshot snapshot;

    public ConfigManager(Plugin plugin) {
        this(
                Objects.requireNonNull(plugin, "plugin").getDataFolder().toPath(),
                name -> plugin.getResource(name)
        );
    }

    public ConfigManager(Path dataFolder, Function<String, InputStream> resourceOpener) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder");
        this.resourceOpener = Objects.requireNonNull(resourceOpener, "resourceOpener");
    }

    /**
     * Copies bundled default configuration and language files if missing, then loads and validates them.
     */
    public void initialize() {
        saveDefaultResources();
        reload();
    }

    /**
     * Copies bundled default resources into {@link #dataFolder()} without overwriting existing files.
     */
    public void saveDefaultResources() {
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException ex) {
            throw new ConfigException(CONFIG_FILE, "", "Failed to create data folder: " + dataFolder, ex);
        }

        for (String resourcePath : DEFAULT_RESOURCES) {
            Path target = dataFolder.resolve(resourcePath);
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream in = resourceOpener.apply(resourcePath)) {
                if (in == null) {
                    throw new ConfigException(resourcePath, "", "Bundled default resource not found: " + resourcePath);
                }
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                throw new ConfigException(resourcePath, "", "Failed to write default resource: " + target, ex);
            }
        }
    }

    /**
     * Loads and validates all YAML configs. On failure, keeps the previous valid snapshot intact.
     */
    public synchronized void reload() {
        PluginConfig pluginConfig = YamlConfigLoader.loadPluginConfig(dataFolder.resolve(CONFIG_FILE));
        BossConfig normalBoss = YamlConfigLoader.loadBossConfig(
                dataFolder.resolve(BOSS_NORMAL_FILE), BossConfig.Difficulty.NORMAL);
        BossConfig hardBoss = YamlConfigLoader.loadBossConfig(
                dataFolder.resolve(BOSS_HARD_FILE), BossConfig.Difficulty.HARD);
        RewardsConfig rewards = YamlConfigLoader.loadRewardsConfig(dataFolder.resolve(REWARDS_FILE));

        this.snapshot = new ConfigSnapshot(pluginConfig, normalBoss, hardBoss, rewards);
    }

    /**
     * Validates and persists updated region settings to {@code config.yml} asynchronously,
     * preserving existing YAML comments and reloading atomically on completion.
     */
    public CompletableFuture<Void> updateRegionsAsync(RegionConfig regions, Executor executor) {
        Objects.requireNonNull(regions, "regions");
        Objects.requireNonNull(executor, "executor");
        return CompletableFuture.runAsync(() -> updateRegions(regions), executor);
    }

    /**
     * Validates and persists updated region settings to {@code config.yml}, then reloads atomically.
     */
    public synchronized void updateRegions(RegionConfig regions) {
        Objects.requireNonNull(regions, "regions");
        YamlConfigLoader.validateRegionConfig(regions, CONFIG_FILE);

        Path configPath = dataFolder.resolve(CONFIG_FILE);
        YamlConfigLoader.saveRegionsPreservingComments(configPath, regions);
        reload();
    }

    public Path dataFolder() {
        return dataFolder;
    }

    public PluginConfig pluginConfig() {
        return requireSnapshot().pluginConfig();
    }

    public BossConfig normalBossConfig() {
        return requireSnapshot().normalBossConfig();
    }

    public BossConfig hardBossConfig() {
        return requireSnapshot().hardBossConfig();
    }

    public BossConfig bossConfig(BossConfig.Difficulty difficulty) {
        Objects.requireNonNull(difficulty, "difficulty");
        return switch (difficulty) {
            case NORMAL -> normalBossConfig();
            case HARD -> hardBossConfig();
        };
    }

    public RewardsConfig rewardsConfig() {
        return requireSnapshot().rewardsConfig();
    }

    private ConfigSnapshot requireSnapshot() {
        ConfigSnapshot current = this.snapshot;
        if (current == null) {
            throw new IllegalStateException("ConfigManager has not been initialized yet");
        }
        return current;
    }
}
