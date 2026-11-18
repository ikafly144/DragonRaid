package net.sabafly.dragonraid.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.bukkit.plugin.Plugin;

/**
 * Manages loading, validation, default resource extraction, and atomic hot-reload
 * of DragonRaid YAML configuration files.
 */
public final class ConfigManager {

    public static final String CONFIG_FILE = "config.yml";
    public static final String REWARDS_FILE = "rewards.yml";

    private static final List<String> DEFAULT_RESOURCES = List.of(
            CONFIG_FILE,
            REWARDS_FILE,
            "lang/ja_JP.yml",
            "lang/en_US.yml"
    );

    private record ConfigSnapshot(
            PluginConfig pluginConfig,
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
        this.snapshot = buildValidatedSnapshot(pluginConfig);
    }

    /**
     * Validates and persists updated region settings to {@code config.yml} asynchronously,
     * preserving existing YAML comments and reloading atomically on completion.
     */
    public CompletableFuture<Void> updateRegionsAsync(RegionConfig regions, Executor executor) {
        Objects.requireNonNull(regions, "regions");
        return updateRegionsAsync(ignored -> regions, executor);
    }

    /**
     * Applies {@code updater} against the latest {@link RegionConfig} inside the synchronization lock,
     * validates all configuration files, and persists {@code config.yml} asynchronously.
     */
    public CompletableFuture<Void> updateRegionsAsync(UnaryOperator<RegionConfig> updater, Executor executor) {
        Objects.requireNonNull(updater, "updater");
        Objects.requireNonNull(executor, "executor");
        return CompletableFuture.runAsync(() -> updateRegions(updater), executor);
    }

    /**
     * Validates the candidate {@code config.yml} and all companion configuration files before writing
     * updated region settings to disk, ensuring disk and memory stay untouched if any validation fails.
     */
    public synchronized void updateRegions(RegionConfig regions) {
        Objects.requireNonNull(regions, "regions");
        updateRegions(ignored -> regions);
    }

    /**
     * Atomically applies {@code updater} to the latest {@link RegionConfig}, validates the resulting
     * {@code config.yml} and companion files, writes via temp-file swap, and updates the snapshot.
     */
    public synchronized void updateRegions(UnaryOperator<RegionConfig> updater) {
        Objects.requireNonNull(updater, "updater");
        Path configPath = dataFolder.resolve(CONFIG_FILE).toAbsolutePath().normalize();
        RegionConfig latestRegions = this.snapshot != null
                ? this.snapshot.pluginConfig().regions()
                : YamlConfigLoader.loadPluginConfig(configPath).regions();
        RegionConfig updatedRegions = Objects.requireNonNull(updater.apply(latestRegions), "updated regions");

        YamlConfigLoader.validateRegionConfig(updatedRegions, CONFIG_FILE);
        String candidateYaml = YamlConfigLoader.renderRegionsPreservingComments(configPath, updatedRegions);
        PluginConfig candidatePluginConfig = YamlConfigLoader.loadPluginConfigFromString(candidateYaml, CONFIG_FILE);
        ConfigSnapshot candidateSnapshot = buildValidatedSnapshot(candidatePluginConfig);

        writeAtomically(configPath, candidateYaml);
        this.snapshot = candidateSnapshot;
    }

    private static void writeAtomically(Path targetPath, String content) {
        Path parentDir = targetPath.getParent();
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile(parentDir, CONFIG_FILE + ".", ".tmp");
            Files.writeString(tempFile, content, StandardCharsets.UTF_8);
            try {
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new ConfigException(CONFIG_FILE, "regions", "Failed to save updated regions", ex);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Best-effort cleanup of temp file.
                }
            }
        }
    }

    private ConfigSnapshot buildValidatedSnapshot(PluginConfig pluginConfig) {
        RewardsConfig rewards = YamlConfigLoader.loadRewardsConfig(dataFolder.resolve(REWARDS_FILE));
        return new ConfigSnapshot(pluginConfig, rewards);
    }

    public Path dataFolder() {
        return dataFolder;
    }

    public PluginConfig pluginConfig() {
        return requireSnapshot().pluginConfig();
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
