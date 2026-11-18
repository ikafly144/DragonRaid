package net.sabafly.dragonraid.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.sabafly.dragonraid.achievement.DefaultRaidAchievementService;
import net.sabafly.dragonraid.api.enemy.EnemyRole;
import net.sabafly.dragonraid.api.event.EncounterOutcome;
import net.sabafly.dragonraid.api.event.EncounterResultSnapshot;
import net.sabafly.dragonraid.api.event.ParticipantCombatMetrics;
import net.sabafly.dragonraid.api.event.RaidEncounterCompletedEvent;
import net.sabafly.dragonraid.boss.DragonBossController;
import net.sabafly.dragonraid.config.BossConfig;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.gimmick.NormalGimmickExecutor;
import net.sabafly.dragonraid.gimmick.RaidCrystalManager;
import net.sabafly.dragonraid.hud.AdventureRaidHudService;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.instance.RaidInstanceManager;
import net.sabafly.dragonraid.protection.TemporaryBlockTracker;
import net.sabafly.dragonraid.region.DefaultRaidRegionService;
import net.sabafly.dragonraid.region.RaidRegionService;
import net.sabafly.dragonraid.reward.DefaultRaidRewardService;
import net.sabafly.dragonraid.reward.RewardCalculationResult;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import net.sabafly.dragonraid.script.BuiltInEncounters;
import net.sabafly.dragonraid.storage.Difficulty;
import net.sabafly.dragonraid.storage.PlayerDataRepository;
import net.sabafly.dragonraid.storage.PlayerRaidStats;
import net.sabafly.dragonraid.visual.SoulMarkerService;
import net.sabafly.dragonraid.visual.SpatialTelegraphService;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.DragonBattle;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RaidEncounterM3IntegrationTest {

    @TempDir
    Path tempDir;

    private ConfigManager configManager;
    private MessageService messageService;
    private RaidScheduler scheduler;
    private RaidRegionService regionService;
    private TemporaryBlockTracker blockTracker;
    private SoulMarkerService soulMarkerService;
    private PlayerDataRepository playerDataRepository;
    private DragonBossController bossController;
    private AdventureRaidHudService hudService;
    private RaidCrystalManager crystalManager;
    private NormalGimmickExecutor gimmickExecutor;

    private World endWorld;
    private EnderDragon dragon;
    private DragonBattle dragonBattle;
    private Map<UUID, Player> onlinePlayers;
    private AtomicLong nowMillis;
    private List<Runnable> capturedGlobalTimers;
    private DefaultRaidEncounterManager manager;

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager(
                tempDir,
                name -> RaidEncounterM3IntegrationTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();

        messageService = new MessageService(configManager);
        messageService.reload();

        endWorld = mock(World.class);
        lenient().when(endWorld.getUID()).thenReturn(UUID.randomUUID());
        lenient().when(endWorld.getName()).thenReturn("world_the_end");
        lenient().when(endWorld.getMinHeight()).thenReturn(0);
        lenient().when(endWorld.getMaxHeight()).thenReturn(256);

        dragon = mock(EnderDragon.class);
        lenient().when(dragon.getUniqueId()).thenReturn(UUID.randomUUID());
        lenient().when(dragon.getWorld()).thenReturn(endWorld);
        lenient().when(dragon.isDead()).thenReturn(false);

        dragonBattle = mock(DragonBattle.class);
        lenient().when(dragonBattle.hasBeenPreviouslyKilled()).thenReturn(false);
        lenient().when(dragonBattle.getEnderDragon()).thenReturn(dragon);
        lenient().when(endWorld.getEnderDragonBattle()).thenReturn(dragonBattle);

        onlinePlayers = new ConcurrentHashMap<>();
        nowMillis = new AtomicLong(100_000L);
        capturedGlobalTimers = new ArrayList<>();

        scheduler = mock(RaidScheduler.class);
        lenient().when(scheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(scheduler.runAtEntity(any(), any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(scheduler.runAtRegion(any(Location.class), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(scheduler.runGlobalTimer(any(), anyLong(), anyLong())).thenAnswer(inv -> {
            capturedGlobalTimers.add(inv.getArgument(0));
            return mock(TaskHandle.class);
        });

        regionService = new DefaultRaidRegionService(configManager, scheduler);
        blockTracker = new TemporaryBlockTracker();
        soulMarkerService = mock(SoulMarkerService.class);
        playerDataRepository = mock(PlayerDataRepository.class);
        lenient().when(playerDataRepository.recordClear(any(), any(), any(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(PlayerRaidStats.empty(UUID.randomUUID(), "Alice")));

        bossController = mock(DragonBossController.class);
        lenient().when(bossController.bindOrSpawn(any(), anyDouble())).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(bossController.cleanup(any(Boolean.class))).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(bossController.hpRatio()).thenReturn(1.0);
        lenient().when(bossController.bossLocation()).thenReturn(new Location(endWorld, 0.0, 74.0, 0.0));

        hudService = new AdventureRaidHudService(messageService, scheduler);
        SpatialTelegraphService telegraphService = mock(SpatialTelegraphService.class);
        crystalManager = new RaidCrystalManager(
                configManager, scheduler, telegraphService, Logger.getLogger("M3Integration"));
        gimmickExecutor = new NormalGimmickExecutor(
                configManager, messageService, scheduler, telegraphService, crystalManager);

        manager = new DefaultRaidEncounterManager(
                configManager,
                messageService,
                scheduler,
                regionService,
                blockTracker,
                soulMarkerService,
                null,
                playerDataRepository,
                bossController,
                hudService,
                crystalManager,
                gimmickExecutor,
                Logger.getLogger("M3Integration"),
                () -> endWorld,
                onlinePlayers::values,
                onlinePlayers::get,
                nowMillis::get,
                w -> true
        );
    }

    private Player addPlayer(String name, double x, double y, double z) {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        lenient().when(p.getUniqueId()).thenReturn(id);
        lenient().when(p.getName()).thenReturn(name);
        lenient().when(p.isOnline()).thenReturn(true);
        lenient().when(p.locale()).thenReturn(Locale.US);
        lenient().when(p.getWorld()).thenReturn(endWorld);
        lenient().when(p.getLocation()).thenReturn(new Location(endWorld, x, y, z));
        lenient().when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);
        lenient().when(p.getHealth()).thenReturn(20.0);
        onlinePlayers.put(id, p);
        return p;
    }

    @Test
    void firstClearInitAltarPullTicksM3SystemsAndRecordsVictory() {
        Player alice = addPlayer("Alice", 10.0, 64.0, 0.0);

        // First-clear join initializes idle dragon once
        manager.onPlayerJoin(alice);
        verify(bossController).holdFirstClearIdle(endWorld);
        verify(alice).sendMessage(any(Component.class));
        assertTrue(manager.isFirstClearEncounterAvailable());

        // Trigger Altar pull (zero countdown for test convenience)
        configManager.pluginConfig(); // loaded
        assertTrue(manager.startAltarPull(alice, BossConfig.Difficulty.NORMAL));
        assertEquals(RaidState.COUNTDOWN, manager.state());

        // Advance countdown (8 seconds)
        Runnable countdownTimer = capturedGlobalTimers.getFirst();
        for (int i = 0; i < 8; i++) {
            countdownTimer.run();
        }
        assertEquals(RaidState.ACTIVE, manager.state());
        verify(bossController).bindOrSpawn(eq(endWorld), eq(1200.0));
        verify(alice, atLeastOnce()).showBossBar(any());

        // Suppresses EnderDragon explosion during active raid
        EntityExplodeEvent dragonExplode = mock(EntityExplodeEvent.class);
        lenient().when(dragonExplode.getEntity()).thenReturn(dragon);
        manager.onEntityExplode(dragon, dragonExplode);
        verify(dragonExplode).setCancelled(true);

        // Tick 5.0 seconds to trigger SPAWN_CRYSTALS + HUD updates
        for (int i = 0; i < 50; i++) {
            manager.tickActiveEncounter();
        }
        verify(bossController, atLeastOnce()).tick(0.1);
        verify(alice, atLeastOnce()).sendActionBar(any(Component.class));

        // Defeat dragon -> records clear, prepares vanilla defeat, resets state
        nowMillis.addAndGet(45_000L);
        manager.onDragonDefeated(dragon);
        assertEquals(RaidState.IDLE, manager.state());
        verify(bossController).prepareForVanillaDefeat();
        verify(playerDataRepository).recordClear(alice.getUniqueId(), "Alice", Difficulty.NORMAL, 45_000L);
        verify(alice, atLeastOnce()).hideBossBar(any());
    }

    @Test
    void adminResetCleansUpBossAndHud() {
        Player alice = addPlayer("Alice", 10.0, 64.0, 0.0);
        CommandSender admin = mock(CommandSender.class);

        manager.adminStart(admin, BossConfig.Difficulty.NORMAL);
        Runnable countdownTimer = capturedGlobalTimers.getFirst();
        for (int i = 0; i < 8; i++) {
            countdownTimer.run();
        }
        assertEquals(RaidState.ACTIVE, manager.state());

        manager.adminReset(admin);
        assertEquals(RaidState.IDLE, manager.state());
        verify(bossController).cleanup(true);
        verify(alice, atLeastOnce()).hideBossBar(any());
    }

    @Test
    void abortsEncounterWhenBossSpawnFails() {
        Player alice = addPlayer("Alice", 10.0, 64.0, 0.0);
        CommandSender admin = mock(CommandSender.class);
        lenient().when(bossController.bindOrSpawn(any(), anyDouble()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("spawn failure")));

        manager.adminStart(admin, BossConfig.Difficulty.NORMAL);
        Runnable countdownTimer = capturedGlobalTimers.getFirst();
        for (int i = 0; i < 8; i++) {
            countdownTimer.run();
        }

        // Encounter must abort back to IDLE instead of remaining stuck in ACTIVE without a boss
        assertEquals(RaidState.IDLE, manager.state());
        verify(bossController).cleanup(true);
    }

    @Test
    void suppressesLeaveArenaWipeWhileAwaitingInstanceEntry() {
        World instWorld = mock(World.class);
        lenient().when(instWorld.getUID()).thenReturn(UUID.randomUUID());
        lenient().when(instWorld.getName()).thenReturn("dragonraid_instance_test1");
        lenient().when(instWorld.getMinHeight()).thenReturn(0);
        lenient().when(instWorld.getMaxHeight()).thenReturn(256);

        RaidInstanceManager instanceMgr = mock(RaidInstanceManager.class);
        CompletableFuture<RaidInstanceManager.RaidInstance> pendingInst = new CompletableFuture<>();
        RaidInstanceManager.RaidInstance inst = new RaidInstanceManager.RaidInstance(
                instWorld,
                new Location(instWorld, 0.0, 64.0, 0.0),
                40.0,
                List.of()
        );
        lenient().when(instanceMgr.getOrCreateInstance()).thenReturn(pendingInst);
        lenient().when(instanceMgr.isInstanceWorld(instWorld)).thenReturn(true);
        lenient().when(instanceMgr.activeCombatWorld(any())).thenAnswer(inv ->
                pendingInst.isDone() ? instWorld : endWorld);
        lenient().when(bossController.bindOrSpawn(any(), any(), anyDouble()))
                .thenReturn(CompletableFuture.completedFuture(null));

        DefaultRaidEncounterManager instEncounter = new DefaultRaidEncounterManager(
                configManager,
                messageService,
                scheduler,
                regionService,
                blockTracker,
                soulMarkerService,
                null,
                playerDataRepository,
                bossController,
                hudService,
                crystalManager,
                gimmickExecutor,
                instanceMgr,
                Logger.getLogger("InstanceEntryWipeTest"),
                () -> endWorld,
                onlinePlayers::values,
                onlinePlayers::get,
                nowMillis::get,
                w -> true
        );

        // Alice starts at the End safe zone / altar outside the combat arena before instance teleport completes.
        Location altarSafeSpawn = regionService.safeZoneSpawnLocation(endWorld);
        Player alice = addPlayer("Alice", altarSafeSpawn.getX(), altarSafeSpawn.getY(), altarSafeSpawn.getZ());
        CompletableFuture<Boolean> teleportFuture = new CompletableFuture<>();
        lenient().when(alice.teleportAsync(any())).thenReturn(teleportFuture);
        CommandSender admin = mock(CommandSender.class);

        instEncounter.adminStart(admin, BossConfig.Difficulty.NORMAL);
        Runnable countdownTimer = capturedGlobalTimers.getFirst();
        for (int i = 0; i < 8; i++) {
            countdownTimer.run();
        }
        assertEquals(RaidState.ACTIVE, instEncounter.state());

        // Must NOT wipe while instance creation is still pending
        instEncounter.tickActiveEncounter();
        assertEquals(RaidState.ACTIVE, instEncounter.state());

        // Must still NOT wipe while teleportAsync is still in flight
        pendingInst.complete(inst);
        instEncounter.tickActiveEncounter();
        assertEquals(RaidState.ACTIVE, instEncounter.state());

        // Once teleport finishes, normal arena-leave wipe tracking resumes
        lenient().when(alice.getWorld()).thenReturn(instWorld);
        teleportFuture.complete(true);
        lenient().when(alice.getLocation()).thenReturn(new Location(instWorld, 500.0, 64.0, 500.0));
        instEncounter.tickActiveEncounter();
        assertEquals(RaidState.WIPING, instEncounter.state());
    }

    @Test
    void defersInstanceDestroyUntilBlockRollbackCompletesOnVictory() {
        World instWorld = mock(World.class);
        lenient().when(instWorld.getUID()).thenReturn(UUID.randomUUID());
        lenient().when(instWorld.getName()).thenReturn("dragonraid_instance_test2");
        lenient().when(instWorld.getMinHeight()).thenReturn(0);
        lenient().when(instWorld.getMaxHeight()).thenReturn(256);
        Block dummyBlock = mock(Block.class);
        lenient().when(instWorld.getBlockAt(any(Location.class))).thenReturn(dummyBlock);
        lenient().when(instWorld.getBlockAt(any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenReturn(dummyBlock);

        EnderDragon instDragon = mock(EnderDragon.class);
        lenient().when(instDragon.getUniqueId()).thenReturn(UUID.randomUUID());
        lenient().when(instDragon.getWorld()).thenReturn(instWorld);

        List<Runnable> deferredChunkRollbackTasks = new ArrayList<>();
        List<Runnable> deferredGlobalLaterTasks = new ArrayList<>();
        RaidScheduler deferredScheduler = mock(RaidScheduler.class);
        lenient().when(deferredScheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(deferredScheduler.runAtEntity(any(), any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(deferredScheduler.runAtRegion(any(Location.class), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(deferredScheduler.runAtRegion(any(World.class), any(Integer.class), any(Integer.class), any()))
                .thenAnswer(inv -> {
                    deferredChunkRollbackTasks.add(inv.getArgument(3));
                    return mock(TaskHandle.class);
                });
        List<Runnable> globalTimers = new ArrayList<>();
        lenient().when(deferredScheduler.runGlobalTimer(any(), anyLong(), anyLong())).thenAnswer(inv -> {
            globalTimers.add(inv.getArgument(0));
            return mock(TaskHandle.class);
        });
        lenient().when(deferredScheduler.runGlobalLater(any(), eq(100L))).thenAnswer(inv -> {
            deferredGlobalLaterTasks.add(inv.getArgument(0));
            return mock(TaskHandle.class);
        });

        RaidInstanceManager instanceMgr = mock(RaidInstanceManager.class);
        RaidInstanceManager.RaidInstance inst = new RaidInstanceManager.RaidInstance(
                instWorld,
                new Location(instWorld, 0.0, 64.0, 0.0),
                40.0,
                List.of()
        );
        lenient().when(instanceMgr.getOrCreateInstance()).thenReturn(CompletableFuture.completedFuture(inst));
        lenient().when(instanceMgr.activeInstance()).thenReturn(inst);
        lenient().when(instanceMgr.activeCombatWorld(any())).thenReturn(instWorld);
        lenient().when(instanceMgr.isInstanceWorld(instWorld)).thenReturn(true);
        lenient().when(instanceMgr.destroyInstance(eq(inst), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(bossController.bindOrSpawn(any(), any(), anyDouble()))
                .thenReturn(CompletableFuture.completedFuture(null));

        DefaultRaidRegionService deferredRegionService = new DefaultRaidRegionService(configManager, deferredScheduler);
        TemporaryBlockTracker localTracker = new TemporaryBlockTracker(
                data -> "minecraft:stone",
                dataStr -> mock(BlockData.class)
        );
        DefaultRaidEncounterManager instEncounter = new DefaultRaidEncounterManager(
                configManager,
                messageService,
                deferredScheduler,
                deferredRegionService,
                localTracker,
                soulMarkerService,
                null,
                playerDataRepository,
                bossController,
                hudService,
                crystalManager,
                gimmickExecutor,
                instanceMgr,
                Logger.getLogger("VictoryDestroyOrderTest"),
                () -> endWorld,
                onlinePlayers::values,
                onlinePlayers::get,
                nowMillis::get,
                w -> true
        );

        Player alice = addPlayer("Alice", 5.0, 64.0, 0.0);
        instEncounter.adminStart(mock(CommandSender.class), BossConfig.Difficulty.NORMAL);
        Runnable countdownTimer = globalTimers.getFirst();
        for (int i = 0; i < 8; i++) {
            countdownTimer.run();
        }
        assertEquals(RaidState.ACTIVE, instEncounter.state());

        localTracker.tryRecordPlayerPlacement(
                new Location(instWorld, 2.0, 64.0, 2.0),
                alice.getUniqueId(),
                mock(BlockData.class),
                64,
                512
        );
        deferredChunkRollbackTasks.clear();

        instEncounter.onDragonDefeated(instDragon);

        // Before block rollback finishes, neither delayed destroy nor destroyInstance may run
        assertEquals(RaidState.ENDING, instEncounter.state());
        assertTrue(deferredGlobalLaterTasks.isEmpty());
        verify(instanceMgr, never()).destroyInstance(any(), any());
        assertEquals(1, deferredChunkRollbackTasks.size());

        // Complete block rollback -> now 100-tick delayed destroy is scheduled
        deferredChunkRollbackTasks.getFirst().run();
        assertEquals(1, deferredGlobalLaterTasks.size());
        verify(instanceMgr, never()).destroyInstance(any(), any());

        // Execute 100-tick delayed destroy -> instance destroyed and encounter returns to IDLE
        deferredGlobalLaterTasks.getFirst().run();
        verify(instanceMgr).destroyInstance(eq(inst), any());
        assertEquals(RaidState.IDLE, instEncounter.state());
    }

    @Test
    void emitsTelemetryEventAndConnectsRewardAndAchievementModulesOnVictory() {
        RaidCrystalManager mockCrystalMgr = mock(RaidCrystalManager.class);
        lenient().when(mockCrystalMgr.clearAll()).thenReturn(CompletableFuture.completedFuture(null));
        EnderCrystal crystal = mock(EnderCrystal.class);
        lenient().when(mockCrystalMgr.isTrackedCrystal(crystal)).thenReturn(true);
        lenient().when(mockCrystalMgr.onCrystalDamage(eq(crystal), anyDouble(), any())).thenReturn(true);

        DefaultRaidEncounterManager telemetryEncounter = new DefaultRaidEncounterManager(
                configManager,
                messageService,
                scheduler,
                regionService,
                blockTracker,
                soulMarkerService,
                null,
                playerDataRepository,
                bossController,
                hudService,
                mockCrystalMgr,
                gimmickExecutor,
                null,
                Logger.getLogger("TelemetryIntegrationTest"),
                () -> endWorld,
                onlinePlayers::values,
                onlinePlayers::get,
                nowMillis::get,
                w -> true
        );

        DefaultRaidRewardService rewardService = new DefaultRaidRewardService();
        DefaultRaidAchievementService achievementService = new DefaultRaidAchievementService();
        telemetryEncounter.setEncounterCompletedSink(snapshot -> {
            RaidEncounterCompletedEvent event = new RaidEncounterCompletedEvent(snapshot);
            rewardService.onEncounterCompleted(event);
            achievementService.onEncounterCompleted(event);
        });

        Player alice = addPlayer("Alice", 10.0, 64.0, 0.0);
        telemetryEncounter.adminStart(mock(CommandSender.class), BossConfig.Difficulty.HARD);
        Runnable countdownTimer = capturedGlobalTimers.getFirst();
        for (int i = 0; i < 8; i++) {
            countdownTimer.run();
        }
        assertEquals(RaidState.ACTIVE, telemetryEncounter.state());
        assertTrue(telemetryEncounter.enemyRegistry().find(BuiltInEncounters.ENEMY_ANCIENT_DRAGON).isPresent());
        assertTrue(telemetryEncounter.enemyRegistry().find(BuiltInEncounters.ENEMY_OBSIDIAN_CRYSTAL).isPresent());

        // Record boss damage and crystal shatter
        EntityDamageByEntityEvent bossHit = mock(EntityDamageByEntityEvent.class);
        lenient().when(bossHit.getDamager()).thenReturn(alice);
        lenient().when(bossHit.getFinalDamage()).thenReturn(620.0);
        telemetryEncounter.onBossDamage(dragon, bossHit);

        for (int i = 0; i < 3; i++) {
            EntityDamageByEntityEvent crystalHit = mock(EntityDamageByEntityEvent.class);
            lenient().when(crystalHit.getDamager()).thenReturn(alice);
            lenient().when(crystalHit.getDamage()).thenReturn(1.0);
            telemetryEncounter.onCrystalDamage(crystal, crystalHit);
        }

        nowMillis.addAndGet(60_000L);
        telemetryEncounter.onDragonDefeated(dragon);

        EncounterResultSnapshot result = telemetryEncounter.lastCompletedResult();
        assertEquals(EncounterOutcome.VICTORY, result.outcome());
        ParticipantCombatMetrics aliceMetrics = result.participantMetrics().get(alice.getUniqueId());
        assertEquals(620.0, aliceMetrics.damageByRole().get(EnemyRole.PRIMARY_BOSS), 1e-6);
        assertEquals(3, aliceMetrics.totalCrystalsDestroyed());

        RewardCalculationResult reward = rewardService.lastReward(alice.getUniqueId()).orElseThrow();
        assertEquals(25, reward.baseTokens());
        assertEquals(6, reward.crystalBonus());
        assertEquals(3, reward.damageBonus());
        assertTrue(achievementService.unlockedFor(alice.getUniqueId())
                .contains(DefaultRaidAchievementService.ACH_HARD_CONQUEROR));
        assertTrue(achievementService.unlockedFor(alice.getUniqueId())
                .contains(DefaultRaidAchievementService.ACH_CRYSTAL_BREAKER));
    }
}
