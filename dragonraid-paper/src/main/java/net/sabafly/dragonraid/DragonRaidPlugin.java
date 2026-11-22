package net.sabafly.dragonraid;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import net.sabafly.dragonraid.achievement.DefaultRaidAchievementService;
import net.sabafly.dragonraid.api.achievement.AchievementDefinition;
import net.sabafly.dragonraid.api.achievement.RaidAchievementService;
import net.sabafly.dragonraid.api.economy.RaidEconomyService;
import net.sabafly.dragonraid.api.finder.ContentEntrySpec;
import net.sabafly.dragonraid.api.finder.ContentFinderService;
import net.sabafly.dragonraid.api.finder.ContentUnlockService;
import net.sabafly.dragonraid.api.item.RaidItemService;
import net.sabafly.dragonraid.api.model.ActorModelService;
import net.sabafly.dragonraid.api.pack.ContentPackCatalogSnapshot;
import net.sabafly.dragonraid.api.pack.ContentPackLoader;
import net.sabafly.dragonraid.api.party.PartyCombatStatusProvider;
import net.sabafly.dragonraid.api.party.PartyHudService;
import net.sabafly.dragonraid.api.party.PartyMemberCombatState;
import net.sabafly.dragonraid.api.party.PartyRoleProvider;
import net.sabafly.dragonraid.api.party.RaidPartyService;
import net.sabafly.dragonraid.api.progression.VanillaEndProgressionPolicy;
import net.sabafly.dragonraid.api.progression.VanillaProgressionService;
import net.sabafly.dragonraid.api.reward.ContentUnlockNotifier;
import net.sabafly.dragonraid.api.reward.RaidRewardService;
import net.sabafly.dragonraid.api.reward.RewardCurrencySink;
import net.sabafly.dragonraid.api.reward.RewardItemDelivery;
import net.sabafly.dragonraid.api.reward.RewardTable;
import net.sabafly.dragonraid.api.script.EncounterBlueprintRegistry;
import net.sabafly.dragonraid.api.script.GimmickActionRegistry;
import net.sabafly.dragonraid.api.script.ScriptHookRegistry;
import net.sabafly.dragonraid.api.service.RaidServiceRegistry;
import net.sabafly.dragonraid.api.stat.RaidStatService;
import net.sabafly.dragonraid.boss.DragonBossController;
import net.sabafly.dragonraid.boss.FoliaDragonBossController;
import net.sabafly.dragonraid.command.ContentFinderCommandHandler;
import net.sabafly.dragonraid.command.ContentFinderCommandRegistrar;
import net.sabafly.dragonraid.command.DefaultContentFinderCommandHandler;
import net.sabafly.dragonraid.command.DefaultPartyCommandHandler;
import net.sabafly.dragonraid.command.DefaultRaidCommandHandler;
import net.sabafly.dragonraid.command.DragonRaidCommandRegistrar;
import net.sabafly.dragonraid.command.PartyCommandHandler;
import net.sabafly.dragonraid.command.PartyCommandRegistrar;
import net.sabafly.dragonraid.command.RaidCommandHandler;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.PluginConfig;
import net.sabafly.dragonraid.core.DefaultRaidServiceRegistry;
import net.sabafly.dragonraid.economy.DefaultRaidEconomyService;
import net.sabafly.dragonraid.encounter.DefaultRaidEncounterManager;
import net.sabafly.dragonraid.encounter.ParticipantState;
import net.sabafly.dragonraid.encounter.RaidEncounterManager;
import net.sabafly.dragonraid.encounter.RaidPlayerLifecycleListener;
import net.sabafly.dragonraid.finder.DefaultContentFinderService;
import net.sabafly.dragonraid.finder.DefaultContentUnlockService;
import net.sabafly.dragonraid.finder.DutyReadyCheckCoordinator;
import net.sabafly.dragonraid.finder.gui.FinderGuiListener;
import net.sabafly.dragonraid.finder.gui.FinderMenuFactory;
import net.sabafly.dragonraid.finder.gui.PaperFinderMenuFactory;
import net.sabafly.dragonraid.hud.AdventureRaidHudService;
import net.sabafly.dragonraid.hud.RaidHudService;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.instance.RaidInstanceManager;
import net.sabafly.dragonraid.instance.TheNextLvlRaidInstanceManager;
import net.sabafly.dragonraid.item.DefaultRaidItemService;
import net.sabafly.dragonraid.model.DefaultActorModelService;
import net.sabafly.dragonraid.party.DefaultRaidPartyService;
import net.sabafly.dragonraid.party.PartyChatDispatcher;
import net.sabafly.dragonraid.party.PartyChatListener;
import net.sabafly.dragonraid.party.PartyLifecycleListener;
import net.sabafly.dragonraid.party.hud.PacketEventsPartyHudService;
import net.sabafly.dragonraid.permission.RaidPermissions;
import net.sabafly.dragonraid.progression.DefaultVanillaEndProgressionPolicy;
import net.sabafly.dragonraid.progression.FoliaVanillaProgressionService;
import net.sabafly.dragonraid.progression.PlayerProgressionJoinListener;
import net.sabafly.dragonraid.progression.VanillaEndPortalListener;
import net.sabafly.dragonraid.protection.ArenaProtectionListener;
import net.sabafly.dragonraid.protection.TemporaryBlockTracker;
import net.sabafly.dragonraid.region.DefaultRaidRegionService;
import net.sabafly.dragonraid.region.RaidRegionService;
import net.sabafly.dragonraid.reward.DefaultRaidRewardService;
import net.sabafly.dragonraid.reward.DefaultRewardApplier;
import net.sabafly.dragonraid.reward.FoliaRewardItemDelivery;
import net.sabafly.dragonraid.reward.PureRewardTableEvaluator;
import net.sabafly.dragonraid.reward.RewardDispatchListener;
import net.sabafly.dragonraid.scheduler.FoliaRaidScheduler;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.script.DefaultEncounterBlueprintRegistry;
import net.sabafly.dragonraid.script.DefaultGimmickActionRegistry;
import net.sabafly.dragonraid.script.DefaultScriptHookRegistry;
import net.sabafly.dragonraid.script.pack.DefaultContentPackLoader;
import net.sabafly.dragonraid.stat.DefaultRaidStatService;
import net.sabafly.dragonraid.storage.PlayerDataRepository;
import net.sabafly.dragonraid.storage.PointTransactionResult;
import net.sabafly.dragonraid.storage.RaidRecoveryRepository;
import net.sabafly.dragonraid.storage.SqliteCurrencyLedgerStore;
import net.sabafly.dragonraid.storage.SqliteDatabase;
import net.sabafly.dragonraid.storage.SqlitePlayerDataRepository;
import net.sabafly.dragonraid.storage.SqliteRaidRecoveryRepository;
import net.sabafly.dragonraid.visual.PacketEventsContentUnlockNotifier;
import net.sabafly.dragonraid.visual.PacketEventsSoulMarkerService;
import net.sabafly.dragonraid.visual.PacketEventsTelegraphService;
import net.sabafly.dragonraid.visual.SoulMarkerService;
import net.sabafly.dragonraid.visual.SpatialTelegraphService;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

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
    private RaidInstanceManager instanceManager;
    private DefaultRaidEncounterManager encounterManager;
    private RaidServiceRegistry serviceRegistry;
    private ActorModelService actorModelService;
    private EncounterBlueprintRegistry blueprintRegistry;
    private GimmickActionRegistry gimmickActionRegistry;
    private ScriptHookRegistry scriptHookRegistry;
    private DefaultRaidRewardService rewardService;
    private RewardDispatchListener rewardDispatchListener;
    private DefaultRaidAchievementService achievementService;
    private RaidPartyService partyService;
    private PacketEventsPartyHudService partyHudService;
    private RaidItemService itemService;
    private ContentUnlockService contentUnlockService;
    private DefaultContentFinderService contentFinderService;
    private DutyReadyCheckCoordinator readyCheckCoordinator;
    private RaidEconomyService economyService;
    private RaidStatService statService;
    private VanillaProgressionService vanillaProgressionService;
    private DefaultVanillaEndProgressionPolicy vanillaEndProgressionPolicy;
    private ContentUnlockNotifier contentUnlockNotifier;
    private RewardItemDelivery rewardItemDelivery;
    private ContentPackLoader contentPackLoader;
    private ExecutorService scriptHookExecutor;

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
            this.blueprintRegistry = new DefaultEncounterBlueprintRegistry();
            this.gimmickActionRegistry = new DefaultGimmickActionRegistry();
            this.scriptHookRegistry = new DefaultScriptHookRegistry();

            PluginConfig.PartySettings partySettings = configManager.pluginConfig().party();
            PluginConfig.FinderSettings finderSettings = configManager.pluginConfig().finder();

            this.partyService = new DefaultRaidPartyService(
                    partySettings.maxSize(),
                    partySettings.inviteTimeoutSeconds() * 1000L,
                    partySettings.disconnectGraceSeconds() * 1000L,
                    playerId -> this.encounterManager != null && this.encounterManager.isInDutyOrInstance(playerId),
                    System::currentTimeMillis
            );
            this.itemService = new DefaultRaidItemService();
            this.contentUnlockService = new DefaultContentUnlockService(playerDataRepository, System::currentTimeMillis, getLogger());
            net.sabafly.dragonraid.api.finder.DutyEnvironmentChecker envChecker =
                    new net.sabafly.dragonraid.api.finder.DutyEnvironmentChecker() {
                        @Override
                        public boolean isPlayerOnline(UUID playerId) {
                            Player p = getServer().getPlayer(playerId);
                            return p != null && p.isOnline();
                        }

                        @Override
                        public boolean isPlayerInEncounter(UUID playerId) {
                            return encounterManager != null && encounterManager.isInDutyOrInstance(playerId);
                        }

                        @Override
                        public boolean isEncounterSlotBusy() {
                            return encounterManager != null && encounterManager.hasActiveDutySession();
                        }

                        @Override
                        public boolean hasBlueprint(String blueprintId) {
                            return blueprintRegistry != null && blueprintRegistry.get(blueprintId).isPresent();
                        }
                    };
            this.contentFinderService = new DefaultContentFinderService(
                    contentUnlockService,
                    partyService,
                    itemService,
                    envChecker,
                    System::currentTimeMillis,
                    finderSettings.readyCheckTimeoutSeconds() * 1000L,
                    finderSettings.skipReadyCheckForSolo()
            );
            this.partyService.addListener(this.contentFinderService);
            this.economyService = new DefaultRaidEconomyService(
                    new SqliteCurrencyLedgerStore(playerDataRepository, System::currentTimeMillis)
            );
            this.statService = new DefaultRaidStatService();
            this.vanillaProgressionService = new FoliaVanillaProgressionService(
                configManager,
                raidScheduler,
                getServer()::getWorld,
                getLogger()
            );
            this.contentUnlockNotifier = new PacketEventsContentUnlockNotifier(
                messageService,
                raidScheduler,
                getServer()::getPlayer,
                getLogger()
            );
            this.rewardItemDelivery = new FoliaRewardItemDelivery(
                raidScheduler,
                getServer()::getPlayer,
                itemService,
                getLogger()
            );
            RewardCurrencySink currencySink = new RewardCurrencySink() {
                @Override
                public CompletableFuture<Boolean> grantCurrency(
                        UUID playerId,
                        @Nullable String playerName,
                        String currencyId,
                        long amount) {
                    return grantCurrencyIdempotent(playerId, playerName, currencyId, amount, null, 0L);
                }

                @Override
                public CompletableFuture<Boolean> grantCurrencyIdempotent(
                        UUID playerId,
                        @Nullable String playerName,
                        String currencyId,
                        long amount,
                        @Nullable String idempotencyKey,
                        long claimedAtEpochMillis) {
                    if (economyService.getCurrency(currencyId).isPresent()) {
                        return economyService.depositIdempotentAsync(
                                playerId, playerName, currencyId, amount, idempotencyKey, claimedAtEpochMillis);
                    }
                    return playerDataRepository.addCurrencyWithCapsIdempotent(
                                    playerId, playerName, currencyId, amount,
                                    0L, 0L, "unbounded", idempotencyKey, claimedAtEpochMillis)
                            .thenApply(PointTransactionResult::success);
                }
            };
            this.achievementService = new DefaultRaidAchievementService(
                playerDataRepository,
                scriptHookRegistry,
                serviceRegistry,
                event -> raidScheduler.runGlobal(() -> getServer().getPluginManager().callEvent(event)),
                System::currentTimeMillis,
                getLogger()
            );
            this.achievementService.setCatalogSnapshotSupplier(
                () -> this.contentPackLoader != null ? this.contentPackLoader.catalogSnapshot() : ContentPackCatalogSnapshot.empty()
            );
            this.scriptHookExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "DragonRaid-Script");
                thread.setDaemon(true);
                return thread;
            });
            DefaultRewardApplier rewardApplier = new DefaultRewardApplier(
                contentUnlockService,
                contentFinderService,
                contentUnlockNotifier,
                currencySink,
                rewardItemDelivery,
                scriptHookRegistry,
                playerDataRepository,
                serviceRegistry,
                System::currentTimeMillis,
                getLogger(),
                scriptHookExecutor
            );
            this.rewardService = new DefaultRaidRewardService(
                new PureRewardTableEvaluator(scriptHookRegistry, serviceRegistry),
                rewardApplier
            );
            this.rewardDispatchListener = new RewardDispatchListener(
                rewardService,
                contentFinderService,
                achievementService,
                contentUnlockService,
                playerDataRepository,
                System::currentTimeMillis,
                getLogger(),
                () -> this.contentPackLoader != null ? this.contentPackLoader.catalogSnapshot() : ContentPackCatalogSnapshot.empty()
            );
            this.vanillaEndProgressionPolicy = new DefaultVanillaEndProgressionPolicy(
                playerDataRepository,
                contentUnlockService,
                achievementService,
                vanillaProgressionService,
                scriptHookRegistry,
                serviceRegistry,
                getLogger()
            );
            this.contentPackLoader = new DefaultContentPackLoader(
                getDataPath().resolve("contents"),
                contentFinderService,
                achievementService,
                rewardService,
                vanillaEndProgressionPolicy,
                getLogger()
            );

            this.regionService = new DefaultRaidRegionService(configManager, raidScheduler);
            this.temporaryBlockTracker = new TemporaryBlockTracker();
            this.soulMarkerService = new PacketEventsSoulMarkerService(configManager, messageService, getLogger());
            this.telegraphService = new PacketEventsTelegraphService(configManager, getLogger());
            this.hudService = new AdventureRaidHudService(messageService, raidScheduler);
            this.bossController = new FoliaDragonBossController(configManager, raidScheduler, getLogger(), actorModelService);
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
                blueprintRegistry,
                gimmickActionRegistry,
                instanceManager,
                getLogger()
            );
            this.encounterManager.setVanillaEndProgressionPolicy(vanillaEndProgressionPolicy);

            serviceRegistry.register(RaidScheduler.class, raidScheduler);
            serviceRegistry.register(RaidInstanceManager.class, instanceManager);
            serviceRegistry.register(ActorModelService.class, actorModelService);
            serviceRegistry.register(EncounterBlueprintRegistry.class, blueprintRegistry);
            serviceRegistry.register(GimmickActionRegistry.class, gimmickActionRegistry);
            serviceRegistry.register(ScriptHookRegistry.class, scriptHookRegistry);
            serviceRegistry.register(RaidRewardService.class, rewardService);
            serviceRegistry.register(RaidAchievementService.class, achievementService);
            serviceRegistry.register(RaidPartyService.class, partyService);
            serviceRegistry.register(PartyRoleProvider.class, PartyRoleProvider.NOOP);
            PartyCombatStatusProvider partyCombatStatusProvider = new PartyCombatStatusProvider() {
                @Override
                public boolean isInActiveEncounter(UUID playerId) {
                    return encounterManager != null && encounterManager.isInDutyOrInstance(playerId);
                }

                @Override
                public PartyMemberCombatState combatState(UUID playerId, boolean online) {
                    if (!online) {
                        return PartyMemberCombatState.OFFLINE;
                    }
                    if (encounterManager == null || !encounterManager.isParticipant(playerId)) {
                        return PartyMemberCombatState.OUT_OF_COMBAT;
                    }
                    return switch (encounterManager.participantState(playerId).orElse(ParticipantState.ALIVE)) {
                        case ALIVE -> PartyMemberCombatState.ALIVE;
                        case DOWNED -> PartyMemberCombatState.DOWNED;
                        case DEAD_SPECTATOR -> PartyMemberCombatState.DEAD_SPECTATOR;
                    };
                }

                @Override
                public int remainingRevives(UUID playerId) {
                    return encounterManager != null ? encounterManager.remainingRevives() : 0;
                }
            };
            this.partyHudService = new PacketEventsPartyHudService(
                    partyService,
                    partyCombatStatusProvider,
                    id -> serviceRegistry.get(PartyRoleProvider.class).orElse(PartyRoleProvider.NOOP).roleFor(id),
                    raidScheduler,
                    getServer()::getPlayer,
                    getLogger(),
                    partySettings.hudEnabledDefault(),
                    partySettings.hudMaxMembersShown()
            );
            this.partyService.addListener(this.partyHudService);
            serviceRegistry.register(PartyHudService.class, partyHudService);
            serviceRegistry.register(RaidItemService.class, itemService);
            serviceRegistry.register(ContentUnlockService.class, contentUnlockService);
            serviceRegistry.register(ContentFinderService.class, contentFinderService);
            serviceRegistry.register(RaidEconomyService.class, economyService);
            serviceRegistry.register(RaidStatService.class, statService);
            serviceRegistry.register(VanillaProgressionService.class, vanillaProgressionService);
            serviceRegistry.register(VanillaEndProgressionPolicy.class, vanillaEndProgressionPolicy);
            serviceRegistry.register(ContentUnlockNotifier.class, contentUnlockNotifier);
            serviceRegistry.register(RewardItemDelivery.class, rewardItemDelivery);
            serviceRegistry.register(ContentPackLoader.class, contentPackLoader);
            serviceRegistry.register(SpatialTelegraphService.class, telegraphService);

            vanillaEndProgressionPolicy.loadServerMilestones().join();
            try {
                contentPackLoader.reload();
            } catch (RuntimeException ex) {
                vanillaEndProgressionPolicy.markPackLoadFailed(ex.getMessage());
                getLogger().log(Level.SEVERE, "Failed to load Content Packs during startup; vanilla End entry fails closed until reload succeeds", ex);
            }

            raidScheduler.runGlobal(() -> encounterManager.recoverPendingBlockRollbacks(getServer()::getWorld));

            VanillaEndPortalListener endPortalListener = new VanillaEndPortalListener(
                achievementService,
                vanillaEndProgressionPolicy,
                instanceManager,
                messageService,
                getLogger()
            );
            PlayerProgressionJoinListener progressionJoinListener = new PlayerProgressionJoinListener(
                playerDataRepository,
                achievementService,
                contentUnlockService,
                vanillaEndProgressionPolicy,
                endPortalListener,
                rewardService,
                rewardDispatchListener,
                getLogger()
            );

            getServer().getPluginManager().registerEvents(rewardDispatchListener, this);
            getServer().getPluginManager().registerEvents(achievementService, this);
            getServer().getPluginManager().registerEvents(vanillaEndProgressionPolicy, this);
            getServer().getPluginManager().registerEvents(endPortalListener, this);
            getServer().getPluginManager().registerEvents(progressionJoinListener, this);
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

            FinderMenuFactory finderMenuFactory = new PaperFinderMenuFactory();
            this.readyCheckCoordinator = new DutyReadyCheckCoordinator(
                configManager,
                contentFinderService,
                blueprintRegistry,
                encounterManager,
                messageService,
                raidScheduler,
                finderMenuFactory,
                getServer()::getPlayer,
                System::currentTimeMillis,
                getLogger()
            );
            contentFinderService.addReadyCheckListener(readyCheckCoordinator);

            PartyChatDispatcher partyChatDispatcher = new PartyChatDispatcher(
                partyService,
                messageService,
                getServer()::getPlayer
            );
            getServer().getPluginManager().registerEvents(
                new PartyChatListener(partyService, partyChatDispatcher),
                this
            );
            getServer().getPluginManager().registerEvents(
                new PartyLifecycleListener(partyService, partyHudService, messageService, getServer()::getPlayer),
                this
            );

            ContentFinderCommandHandler finderCommandHandler = new DefaultContentFinderCommandHandler(
                contentFinderService,
                contentUnlockService,
                partyService,
                encounterManager,
                readyCheckCoordinator,
                messageService,
                finderMenuFactory,
                getServer()::getPlayer
            );
            getServer().getPluginManager().registerEvents(
                new FinderGuiListener(finderCommandHandler, raidScheduler),
                this
            );

            PartyCommandHandler partyCommandHandler = new DefaultPartyCommandHandler(
                partyService,
                partyHudService,
                partyChatDispatcher,
                messageService,
                name -> java.util.Optional.ofNullable(getServer().getPlayerExact(name)),
                id -> java.util.Optional.ofNullable(getServer().getPlayer(id))
            );
            new PartyCommandRegistrar(
                partyCommandHandler,
                () -> getServer().getOnlinePlayers().stream().map(Player::getName).toList()
            ).register(this);

            new ContentFinderCommandRegistrar(
                finderCommandHandler,
                () -> contentFinderService.allContents().stream().map(ContentEntrySpec::contentId).toList()
            ).register(this);

            raidScheduler.runAsyncTimer(() -> {
                partyService.expireInvites();
                partyService.expireDisconnectedSeats();
            }, 1000L, 1000L, TimeUnit.MILLISECONDS);

            raidScheduler.runAsyncTimer(() -> {
                contentFinderService.tickReadyChecks();
                readyCheckCoordinator.tickVisuals();
            }, 500L, 500L, TimeUnit.MILLISECONDS);

            long hudPeriodMs = Math.max(50L, partySettings.hudUpdateIntervalTicks() * 50L);
            raidScheduler.runAsyncTimer(() -> partyHudService.tickAll(), hudPeriodMs, hudPeriodMs, TimeUnit.MILLISECONDS);

            RaidCommandHandler commandHandler = new DefaultRaidCommandHandler(
                configManager,
                messageService,
                raidScheduler,
                playerDataRepository,
                getLogger(),
                name -> java.util.Optional.ofNullable(getServer().getPlayerExact(name)),
                encounterManager,
                contentPackLoader,
                contentUnlockService,
                achievementService,
                vanillaEndProgressionPolicy,
                vanillaProgressionService,
                rewardService
            );
            new DragonRaidCommandRegistrar(
                commandHandler,
                () -> getServer().getOnlinePlayers().stream().map(Player::getName).toList(),
                () -> configManager.rewardsConfig().currencies().keySet(),
                () -> contentFinderService.allContents().stream().map(ContentEntrySpec::contentId).toList(),
                () -> achievementService.definitions().stream().map(AchievementDefinition::id).toList(),
                () -> rewardService.tables().stream().map(RewardTable::id).toList()
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
        if (readyCheckCoordinator != null) {
            try {
                readyCheckCoordinator.shutdown();
            } catch (RuntimeException ex) {
                getLogger().log(Level.WARNING, "Failed to shut down ready check coordinator", ex);
            }
        }
        if (partyHudService != null) {
            try {
                partyHudService.shutdown();
            } catch (RuntimeException ex) {
                getLogger().log(Level.WARNING, "Failed to shut down party HUD service", ex);
            }
        }
        if (encounterManager != null) {
            try {
                encounterManager.shutdown();
            } catch (RuntimeException ex) {
                getLogger().log(Level.WARNING, "Failed to shut down raid encounter manager", ex);
            }
        }
        if (scriptHookExecutor != null) {
            scriptHookExecutor.shutdown();
            try {
                if (!scriptHookExecutor.awaitTermination(3L, TimeUnit.SECONDS)) {
                    scriptHookExecutor.shutdownNow();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                scriptHookExecutor.shutdownNow();
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
        if (vanillaProgressionService instanceof FoliaVanillaProgressionService foliaVanillaProgression) {
            try {
                foliaVanillaProgression.cancelPendingTasks();
            } catch (RuntimeException ex) {
                getLogger().log(Level.WARNING, "Failed to cancel pending vanilla progression tasks during shutdown", ex);
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

    public SpatialTelegraphService telegraphService() {
        return telegraphService;
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
