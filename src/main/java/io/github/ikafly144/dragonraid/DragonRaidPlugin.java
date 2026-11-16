package io.github.ikafly144.dragonraid;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import io.github.ikafly144.dragonraid.command.DefaultRaidCommandHandler;
import io.github.ikafly144.dragonraid.command.DragonRaidCommandRegistrar;
import io.github.ikafly144.dragonraid.command.RaidCommandHandler;
import io.github.ikafly144.dragonraid.config.ConfigManager;
import io.github.ikafly144.dragonraid.config.PluginConfig;
import io.github.ikafly144.dragonraid.i18n.MessageService;
import io.github.ikafly144.dragonraid.permission.RaidPermissions;
import io.github.ikafly144.dragonraid.scheduler.FoliaRaidScheduler;
import io.github.ikafly144.dragonraid.scheduler.RaidScheduler;
import io.github.ikafly144.dragonraid.storage.PlayerDataRepository;
import io.github.ikafly144.dragonraid.storage.SqlitePlayerDataRepository;
import java.nio.file.Path;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main Folia plugin entrypoint for DragonRaid.
 */
public final class DragonRaidPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private MessageService messageService;
    private RaidScheduler raidScheduler;
    private PlayerDataRepository playerDataRepository;

    @Override
    public void onEnable() {
        this.configManager = new ConfigManager(this);
        try {
            configManager.initialize();
        } catch (RuntimeException ex) {
            getLogger().log(Level.SEVERE, "Failed to load DragonRaid configuration; disabling plugin", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.messageService = new MessageService(configManager);
        try {
            messageService.reload();
        } catch (RuntimeException ex) {
            getLogger().log(Level.SEVERE, "Failed to load DragonRaid language bundles; disabling plugin", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.raidScheduler = new FoliaRaidScheduler(this);

        PluginConfig.DatabaseSettings dbSettings = configManager.pluginConfig().database();
        Path databasePath = getDataPath().resolve(dbSettings.file());
        try {
            this.playerDataRepository = new SqlitePlayerDataRepository(
                databasePath,
                dbSettings.poolSize(),
                raidScheduler.asyncExecutor()
            );
        } catch (RuntimeException ex) {
            getLogger().log(Level.SEVERE, "Failed to initialize SQLite repository at " + databasePath, ex);
            raidScheduler.cancelAll();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        RaidPermissions.registerDefaults(getServer().getPluginManager());

        RaidCommandHandler commandHandler = new DefaultRaidCommandHandler(
            configManager,
            messageService,
            raidScheduler,
            playerDataRepository,
            getLogger()
        );
        new DragonRaidCommandRegistrar(
            commandHandler,
            () -> getServer().getOnlinePlayers().stream().map(Player::getName).toList(),
            () -> configManager.rewardsConfig().currencies().keySet()
        ).register(this);

        logPacketEventsStatus();
        getLogger().info("DragonRaid enabled.");
    }

    @Override
    public void onDisable() {
        if (raidScheduler != null) {
            try {
                raidScheduler.cancelAll();
            } catch (RuntimeException ex) {
                getLogger().log(Level.WARNING, "Failed to cancel scheduled tasks during shutdown", ex);
            }
        }
        if (playerDataRepository != null) {
            try {
                playerDataRepository.close();
            } catch (Exception ex) {
                getLogger().log(Level.WARNING, "Failed to close player data repository during shutdown", ex);
            }
        }
    }

    /**
     * Returns the configuration manager.
     *
     * @return active {@link ConfigManager}
     */
    public ConfigManager configManager() {
        return configManager;
    }

    /**
     * Returns the MiniMessage i18n service.
     *
     * @return active {@link MessageService}
     */
    public MessageService messageService() {
        return messageService;
    }

    /**
     * Returns the Folia scheduler abstraction.
     *
     * @return active {@link RaidScheduler}
     */
    public RaidScheduler raidScheduler() {
        return raidScheduler;
    }

    /**
     * Returns the player data repository.
     *
     * @return active {@link PlayerDataRepository}
     */
    public PlayerDataRepository playerDataRepository() {
        return playerDataRepository;
    }

    private void logPacketEventsStatus() {
        Plugin pePlugin = getServer().getPluginManager().getPlugin("packetevents");
        try {
            PacketEventsAPI<?> api = PacketEvents.getAPI();
            if (api != null) {
                getLogger().info("PacketEvents detected (API v" + api.getVersion() + ").");
                return;
            }
        } catch (NoClassDefFoundError | RuntimeException ex) {
            getLogger().log(Level.WARNING, "PacketEvents API check failed during startup", ex);
            return;
        }
        if (pePlugin != null) {
            getLogger().info("PacketEvents plugin detected: " + pePlugin.getPluginMeta().getVersion());
        } else {
            getLogger().warning("PacketEvents plugin not found; packet-based visuals will be unavailable.");
        }
    }
}
