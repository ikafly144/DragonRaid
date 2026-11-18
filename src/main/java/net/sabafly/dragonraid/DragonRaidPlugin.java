package net.sabafly.dragonraid;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import net.sabafly.dragonraid.achievement.DefaultRaidAchievementService;
import net.sabafly.dragonraid.achievement.RaidAchievementService;
import net.sabafly.dragonraid.altar.AltarGuiService;
import net.sabafly.dragonraid.altar.AltarInteractionListener;
import net.sabafly.dragonraid.api.model.ActorModelService;
import net.sabafly.dragonraid.api.script.GimmickActionRegistry;
import net.sabafly.dragonraid.api.service.RaidServiceRegistry;
import net.sabafly.dragonraid.boss.DragonBossController;
import net.sabafly.dragonraid.boss.FoliaDragonBossController;
import net.sabafly.dragonraid.command.DefaultRaidCommandHandler;
import net.sabafly.dragonraid.command.DragonRaidCommandRegistrar;
import net.sabafly.dragonraid.command.RaidCommandHandler;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.PluginConfig;
import net.sabafly.dragonraid.core.DefaultRaidServiceRegistry;
import net.sabafly.dragonraid.encounter.DefaultRaidEncounterManager;
import net.sabafly.dragonraid.encounter.RaidEncounterManager;
import net.sabafly.dragonraid.encounter.RaidPlayerLifecycleListener;
import net.sabafly.dragonraid.gimmick.NormalGimmickExecutor;
import net.sabafly.dragonraid.gimmick.RaidCrystalManager;
import net.sabafly.dragonraid.hud.AdventureRaidHudService;
import net.sabafly.dragonraid.hud.RaidHudService;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.instance.RaidInstanceManager;
import net.sabafly.dragonraid.instance.TheNextLvlRaidInstanceManager;
import net.sabafly.dragonraid.model.DefaultActorModelService;
import net.sabafly.dragonraid.permission.RaidPermissions;
import net.sabafly.dragonraid.protection.ArenaProtectionListener;
import net.sabafly.dragonraid.protection.TemporaryBlockTracker;
import net.sabafly.dragonraid.region.DefaultRaidRegionService;
import net.sabafly.dragonraid.region.RaidRegionService;
import net.sabafly.dragonraid.reward.DefaultRaidRewardService;
import net.sabafly.dragonraid.reward.RaidRewardService;
import net.sabafly.dragonraid.scheduler.FoliaRaidScheduler;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.script.DefaultGimmickActionRegistry;
import net.sabafly.dragonraid.storage.PlayerDataRepository;
import net.sabafly.dragonraid.storage.RaidRecoveryRepository;
import net.sabafly.dragonraid.storage.SqliteDatabase;
import net.sabafly.dragonraid.storage.SqlitePlayerDataRepository;
import net.sabafly.dragonraid.storage.SqliteRaidRecoveryRepository;
import net.sabafly.dragonraid.visual.PacketEventsSoulMarkerService;
import net.sabafly.dragonraid.visual.PacketEventsTelegraphService;
import net.sabafly.dragonraid.visual.SoulMarkerService;
import net.sabafly.dragonraid.visual.SpatialTelegraphService;
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
    private SqliteDatabase sqliteDatabase;
    private PlayerDataRepository playerDataRepository;
    private RaidRecoveryRepository recoveryRepository;
    private RaidRegionService regionService;
    private TemporaryBlockTracker temporaryBlockTracker;
    private SoulMarkerService soulMarkerService;
    private SpatialTelegraphService telegraphService;
    private RaidHudService hudService;
    private DragonBossController bossController;
    private RaidCrystalManager crystalManager;
    private NormalGimmickExecutor gimmickExecutor;
    private RaidInstanceManager instanceManager;
    private DefaultRaidEncounterManager encounterManager;
    private AltarGuiService altarGuiService;
    private RaidServiceRegistry serviceRegistry;
    private ActorModelService actorModelService;
    private GimmickActionRegistry gimmickActionRegistry;
    private DefaultRaidRewardService rewardService;
    private DefaultRaidAchievementService achievementService;

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
            this.sqliteDatabase = new SqliteDatabase(
                databasePath,
                dbSettings.poolSize(),
                raidScheduler.asyncExecutor()
            );
            this.playerDataRepository = new SqlitePlayerDataRepository(sqliteDatabase);
            this.recoveryRepository = new SqliteRaidRecoveryRepository(sqliteDatabase);
        } catch (RuntimeException ex) {
            getLogger().log(Level.SEVERE, "Failed to initialize SQLite database at " + databasePath, ex);
            if (sqliteDatabase != null) {
                sqliteDatabase.close();
            }
            raidScheduler.cancelAll();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            RaidPermissions.registerDefaults(getServer().getPluginManager());

            this.serviceRegistry = new DefaultRaidServiceRegistry();
            this.actorModelService = new DefaultActorModelService(getLogger());
            this.gimmickActionRegistry = new DefaultGimmickActionRegistry();
            this.rewardService = new DefaultRaidRewardService();
            this.achievementService = new DefaultRaidAchievementService();

            this.regionService = new DefaultRaidRegionService(configManager, raidScheduler);
            this.temporaryBlockTracker = new TemporaryBlockTracker();
            this.soulMarkerService = new PacketEventsSoulMarkerService(configManager, messageService, getLogger());
            this.telegraphService = new PacketEventsTelegraphService(configManager, getLogger());
            this.hudService = new AdventureRaidHudService(messageService, raidScheduler);
            this.bossController = new FoliaDragonBossController(configManager, raidScheduler, getLogger(), actorModelService);
            this.crystalManager = new RaidCrystalManager(configManager, raidScheduler, telegraphService, getLogger());
            this.gimmickExecutor = new NormalGimmickExecutor(
                configManager,
                messageService,
                raidScheduler,
                telegraphService,
                crystalManager,
                gimmickActionRegistry
            );
            this.instanceManager = new TheNextLvlRaidInstanceManager(raidScheduler, getLogger());
            this.instanceManager.cleanupOrphanedInstances();
            this.encounterManager = new DefaultRaidEncounterManager(
                configManager,
                messageService,
                raidScheduler,
                regionService,
                temporaryBlockTracker,
                soulMarkerService,
                recoveryRepository,
                playerDataRepository,
                bossController,
                hudService,
                crystalManager,
                gimmickExecutor,
                instanceManager,
                getLogger()
            );

            serviceRegistry.register(RaidScheduler.class, raidScheduler);
            serviceRegistry.register(RaidInstanceManager.class, instanceManager);
            serviceRegistry.register(ActorModelService.class, actorModelService);
            serviceRegistry.register(GimmickActionRegistry.class, gimmickActionRegistry);
            serviceRegistry.register(RaidRewardService.class, rewardService);
            serviceRegistry.register(RaidAchievementService.class, achievementService);
            raidScheduler.runGlobal(() -> encounterManager.recoverPendingBlockRollbacks(getServer()::getWorld));

            getServer().getPluginManager().registerEvents(rewardService, this);
            getServer().getPluginManager().registerEvents(achievementService, this);
            getServer().getPluginManager().registerEvents(
                new ArenaProtectionListener(
                    configManager,
                    regionService,
                    temporaryBlockTracker,
                    messageService,
                    encounterManager::isProtectionActive
                ),
                this
            );
            getServer().getPluginManager().registerEvents(
                new RaidPlayerLifecycleListener(encounterManager, messageService),
                this
            );

            RaidCommandHandler commandHandler = new DefaultRaidCommandHandler(
                configManager,
                messageService,
                raidScheduler,
                playerDataRepository,
                getLogger(),
                encounterManager
            );
            this.altarGuiService = new AltarGuiService(
                configManager,
                messageService,
                raidScheduler,
                regionService,
                playerDataRepository,
                encounterManager,
                commandHandler,
                getLogger()
            );
            getServer().getPluginManager().registerEvents(
                new AltarInteractionListener(altarGuiService, encounterManager),
                this
            );
            new DragonRaidCommandRegistrar(
                commandHandler,
                () -> getServer().getOnlinePlayers().stream().map(Player::getName).toList(),
                () -> configManager.rewardsConfig().currencies().keySet()
            ).register(this);

            logPacketEventsStatus();
            logBetterModelStatus();
            getLogger().info("DragonRaid enabled.");
        } catch (RuntimeException ex) {
            getLogger().log(Level.SEVERE, "Failed during DragonRaid startup; cleaning up resources", ex);
            onDisable();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (encounterManager != null) {
            try {
                encounterManager.shutdown();
            } catch (RuntimeException ex) {
                getLogger().log(Level.WARNING, "Failed to shut down raid encounter manager", ex);
            }
        }
        // Close repositories and shared database first so queued/sync DB writes flush before asyncScheduler tasks cancel.
        if (playerDataRepository != null) {
            try {
                playerDataRepository.close();
            } catch (Exception ex) {
                getLogger().log(Level.WARNING, "Failed to close player data repository during shutdown", ex);
            }
        }
        if (sqliteDatabase != null) {
            try {
                sqliteDatabase.close();
            } catch (Exception ex) {
                getLogger().log(Level.WARNING, "Failed to close SQLite database during shutdown", ex);
            }
        }
        if (raidScheduler != null) {
            try {
                raidScheduler.cancelAll();
            } catch (RuntimeException ex) {
                getLogger().log(Level.WARNING, "Failed to cancel scheduled tasks during shutdown", ex);
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

    /**
     * Returns the raid region boundary service.
     *
     * @return active {@link RaidRegionService}
     */
    public RaidRegionService regionService() {
        return regionService;
    }

    /**
     * Returns the temporary block and fluid tracker.
     *
     * @return active {@link TemporaryBlockTracker}
     */
    public TemporaryBlockTracker temporaryBlockTracker() {
        return temporaryBlockTracker;
    }

    /**
     * Returns the encounter lifecycle manager.
     *
     * @return active {@link RaidEncounterManager}
     */
    public RaidEncounterManager encounterManager() {
        return encounterManager;
    }

    public RaidServiceRegistry serviceRegistry() {
        return serviceRegistry;
    }

    private void logBetterModelStatus() {
        Plugin bmPlugin = getServer().getPluginManager().getPlugin("BetterModel");
        if (bmPlugin != null) {
            getLogger().info("BetterModel detected: " + bmPlugin.getPluginMeta().getVersion());
        } else {
            getLogger().warning("BetterModel plugin not found; BETTER_MODEL actor rigs will be unavailable.");
        }
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
