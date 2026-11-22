package net.sabafly.dragonraid.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.script.EncounterBlueprint;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import net.sabafly.dragonraid.api.script.EnrageRuleSpec;
import net.sabafly.dragonraid.api.script.GimmickAction;
import net.sabafly.dragonraid.api.script.RaidScriptContext;
import net.sabafly.dragonraid.api.script.ReviveRuleSpec;
import net.sabafly.dragonraid.boss.DragonBossController;
import net.sabafly.dragonraid.config.BossConfig;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.protection.TemporaryBlockTracker;
import net.sabafly.dragonraid.region.RaidRegionService;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import net.sabafly.dragonraid.script.DefaultEncounterBlueprintRegistry;
import net.sabafly.dragonraid.script.DefaultGimmickActionRegistry;
import net.sabafly.dragonraid.script.dsl.EncounterBlueprintBuilder;
import net.sabafly.dragonraid.storage.RaidRecoveryRepository;
import net.sabafly.dragonraid.storage.RaidRecoveryRepository.PendingBlockRollback;
import net.sabafly.dragonraid.storage.RaidRecoveryRepository.PendingPlayerCleanup;
import net.sabafly.dragonraid.storage.SqliteDatabase;
import net.sabafly.dragonraid.storage.SqliteRaidRecoveryRepository;
import net.sabafly.dragonraid.visual.SoulMarkerService;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RaidEncounterManagerTest {

    @TempDir
    Path tempDir;

    private ConfigManager configManager;
    private MessageService messageService;
    private RaidScheduler scheduler;
    private RaidRegionService regionService;
    private TemporaryBlockTracker blockTracker;
    private SoulMarkerService soulMarkerService;

    private World endWorld;
    private Map<UUID, Player> onlinePlayers;
    private AtomicLong nowMillis;
    private List<Runnable> capturedGlobalTimers;
    private List<Runnable> capturedDelayedTasks;

    private DefaultEncounterBlueprintRegistry blueprintRegistry;
    private DefaultGimmickActionRegistry gimmickActionRegistry;
    private DefaultRaidEncounterManager manager;

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager(
                tempDir,
                name -> RaidEncounterManagerTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();

        messageService = new MessageService(configManager);
        messageService.reload();

        endWorld = mock(World.class);
        lenient().when(endWorld.getUID()).thenReturn(UUID.randomUUID());
        lenient().when(endWorld.getName()).thenReturn("world_the_end");

        onlinePlayers = new ConcurrentHashMap<>();
        nowMillis = new AtomicLong(100_000L);
        capturedGlobalTimers = new ArrayList<>();
        capturedDelayedTasks = new ArrayList<>();

        scheduler = mock(RaidScheduler.class);
        lenient().when(scheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(scheduler.runAtEntity(any(), any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(scheduler.runAtEntityLater(any(), any(), anyLong())).thenReturn(TaskHandle.retired());
        lenient().when(scheduler.runAtRegion(any(World.class), anyInt(), anyInt(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(3).run();
            return TaskHandle.retired();
        });
        lenient().when(scheduler.runGlobal(any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(0).run();
            return TaskHandle.retired();
        });
        lenient().when(scheduler.runGlobalTimer(any(), anyLong(), anyLong())).thenAnswer(inv -> {
            Runnable task = inv.getArgument(0);
            capturedGlobalTimers.add(task);
            return mock(TaskHandle.class);
        });
        lenient().when(scheduler.runGlobalLater(any(), anyLong())).thenAnswer(inv -> {
            Runnable task = inv.getArgument(0);
            capturedDelayedTasks.add(task);
            return mock(TaskHandle.class);
        });

        regionService = mock(RaidRegionService.class);
        lenient().when(regionService.isRaidWorld(endWorld)).thenReturn(true);
        lenient().when(regionService.safeZoneSpawnLocation(endWorld))
                .thenReturn(new Location(endWorld, 100.5, 50.0, 0.5));
        lenient().when(regionService.isInArena(any())).thenAnswer(inv -> {
            Location l = inv.getArgument(0);
            return l != null && endWorld.equals(l.getWorld()) && (l.getX() * l.getX() + l.getZ() * l.getZ()) <= 120.0 * 120.0
                    && l.getY() >= 0.0 && l.getY() <= 256.0;
        });
        lenient().when(regionService.isInSafeZone(any())).thenAnswer(inv -> {
            Location l = inv.getArgument(0);
            return l != null && endWorld.equals(l.getWorld())
                    && l.getX() >= 92.0 && l.getX() <= 108.0
                    && l.getZ() >= -8.0 && l.getZ() <= 8.0;
        });
        lenient().when(regionService.isBelowVoidThreshold(any())).thenAnswer(inv -> {
            Location l = inv.getArgument(0);
            return l != null && endWorld.equals(l.getWorld()) && l.getY() < 0.0;
        });
        lenient().when(regionService.findNearestSafeArenaEdgeAsync(any(), anyDouble()))
                .thenReturn(CompletableFuture.completedFuture(new Location(endWorld, 10.5, 66.0, 0.5)));
        lenient().when(regionService.clampToArenaAnchor(any(), anyDouble()))
                .thenReturn(new Location(endWorld, 10.5, 66.0, 0.5));

        blockTracker = new TemporaryBlockTracker();
        soulMarkerService = mock(SoulMarkerService.class);
        blueprintRegistry = new DefaultEncounterBlueprintRegistry();
        blueprintRegistry.register(sampleBlueprint("dragon_normal", EncounterDifficulty.NORMAL));
        blueprintRegistry.register(sampleBlueprint("dragon_hard", EncounterDifficulty.HARD));
        gimmickActionRegistry = new DefaultGimmickActionRegistry();

        manager = newTestManager(scheduler, blockTracker, null, Logger.getLogger("EncounterTest"));
    }

    private DefaultRaidEncounterManager newTestManager(
            RaidScheduler testScheduler,
            TemporaryBlockTracker tracker,
            RaidRecoveryRepository recoveryRepo,
            Logger logger) {
        return new DefaultRaidEncounterManager(
                configManager,
                messageService,
                testScheduler,
                regionService,
                tracker,
                soulMarkerService,
                recoveryRepo,
                null,
                null,
                null,
                blueprintRegistry,
                gimmickActionRegistry,
                null,
                logger,
                () -> endWorld,
                () -> onlinePlayers.values(),
                id -> onlinePlayers.get(id),
                nowMillis::get);
    }

    private static EncounterBlueprint sampleBlueprint(String id, EncounterDifficulty difficulty) {
        return new EncounterBlueprintBuilder(id)
                .difficulty(difficulty)
                .displayNameKey(difficulty == EncounterDifficulty.HARD ? "boss.hard-name" : "boss.normal-name")
                .primaryBoss("boss_alpha")
                .revive(difficulty == EncounterDifficulty.HARD ? ReviveRuleSpec.hardDefault() : ReviveRuleSpec.normalDefault())
                .enrage(difficulty == EncounterDifficulty.HARD ? EnrageRuleSpec.hardDefault() : EnrageRuleSpec.normalDefault())
                .enemy("boss_alpha", e -> e
                        .displayNameKey("boss.normal-name")
                        .stats(difficulty == EncounterDifficulty.HARD ? 2400.0 : 1200.0, 0.65, false, true, true))
                .phase("phase-1", p -> p.displayNameKey("phase.p1.name").hpRange(1.0, 0.0).loop(10.0))
                .build();
    }

    @Test
    void vanillaEndMovement_doesNotAutoStartWhenIdle() {
        Location safeZone = new Location(endWorld, 100.5, 50.0, 0.5);
        Location combatArena = new Location(endWorld, 80.0, 64.0, 0.0);
        Player alice = addPlayer("Alice", safeZone);

        when(alice.getLocation()).thenReturn(combatArena);
        manager.onPlayerMove(new PlayerMoveEvent(alice, safeZone, combatArena));

        assertEquals(RaidState.IDLE, manager.state());
        assertEquals(0, capturedGlobalTimers.size());
    }

    @Test
    void startEncounter_launchesFromBlueprintDirectlyAndExecutesRegisteredGimmick() {
        AtomicInteger gimmickCalls = new AtomicInteger();
        gimmickActionRegistry.register(new GimmickAction() {
            @Override
            public String id() {
                return "TEST_GIMMICK";
            }

            @Override
            public void execute(RaidScriptContext context, Map<String, Object> params) {
                gimmickCalls.incrementAndGet();
            }
        });
        EncounterBlueprint bp = new EncounterBlueprintBuilder("custom_finder_bp")
                .difficulty(EncounterDifficulty.NORMAL)
                .displayNameKey("boss.normal-name")
                .primaryBoss("boss_alpha")
                .enemy("boss_alpha", e -> e
                        .displayNameKey("boss.normal-name")
                        .stats(1200.0, 0.65, false, true, true))
                .phase("phase-1", p -> p
                        .displayNameKey("phase.p1.name")
                        .hpRange(1.0, 0.0)
                        .onEnter("TEST_GIMMICK")
                        .loop(10.0))
                .build();
        DragonBossController mockBoss = mock(DragonBossController.class);
        lenient().when(mockBoss.bindOrSpawn(any(), anyDouble())).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(mockBoss.hpRatio()).thenReturn(1.0);
        DefaultRaidEncounterManager customMgr = new DefaultRaidEncounterManager(
                configManager,
                messageService,
                scheduler,
                regionService,
                blockTracker,
                soulMarkerService,
                null,
                null,
                mockBoss,
                null,
                blueprintRegistry,
                gimmickActionRegistry,
                null,
                Logger.getLogger("DirectStartTest"),
                () -> endWorld,
                () -> onlinePlayers.values(),
                id -> onlinePlayers.get(id),
                nowMillis::get);

        Player alice = addPlayer("Alice", new Location(endWorld, 100.5, 50.0, 0.5));
        assertTrue(customMgr.startEncounter(bp, List.of(alice)));
        assertEquals(RaidState.ACTIVE, customMgr.state());
        assertTrue(customMgr.isParticipant(alice.getUniqueId()));
        assertEquals(1, gimmickCalls.get());
    }

    @Test
    void startEncounter_deduplicatesPlayersAndReturnsFalseWhileActive() {
        EncounterBlueprint bp = sampleBlueprint("dedup_bp", EncounterDifficulty.NORMAL);
        Player alice = addPlayer("Alice", new Location(endWorld, 100.5, 50.0, 0.5));

        assertTrue(manager.startEncounter(bp, List.of(alice, alice)));
        assertEquals(1, manager.activeScaling().orElseThrow().partySize());
        assertFalse(manager.startEncounter(bp, List.of(alice)));
    }

    @Test
    void victory_aggregatesPerPlayerFirstClearExcludingDbFailure_andGatesLegacyFallbackByHookMatch() {
        assertTrue(configManager.pluginConfig().world().preserveVanillaProgression());
        lenient().when(scheduler.runAtRegion(any(Location.class), any(Runnable.class))).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });

        EncounterBlueprint bp = new EncounterBlueprintBuilder("custom_raid")
                .difficulty(EncounterDifficulty.NORMAL)
                .displayNameKey("boss.normal-name")
                .primaryBoss("boss_alpha")
                .enemy("boss_alpha", e -> e
                        .displayNameKey("boss.normal-name")
                        .stats(1200.0, 0.65, false, true, true))
                .phase("phase-1", p -> p
                        .displayNameKey("phase.p1.name")
                        .hpRange(1.0, 0.0)
                        .loop(10.0))
                .build();

        org.bukkit.boss.DragonBattle endBattle = mock(org.bukkit.boss.DragonBattle.class);
        lenient().when(endBattle.getEndPortalLocation()).thenReturn(new Location(endWorld, 0.0, 64.0, 0.0));
        lenient().when(endWorld.getEnderDragonBattle()).thenReturn(endBattle);

        Player alice = addPlayer("Alice", new Location(endWorld, 100.5, 50.0, 0.5));
        Player bob = addPlayer("Bob", new Location(endWorld, 101.5, 50.0, 0.5));
        Player charlie = addPlayer("Charlie", new Location(endWorld, 102.5, 50.0, 0.5));
        lenient().when(alice.getWorld()).thenReturn(endWorld);
        lenient().when(bob.getWorld()).thenReturn(endWorld);
        lenient().when(charlie.getWorld()).thenReturn(endWorld);

        net.sabafly.dragonraid.storage.PlayerDataRepository mockRepo =
                mock(net.sabafly.dragonraid.storage.PlayerDataRepository.class);
        lenient().when(mockRepo.recordClear(any(), any(), any(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(mockRepo.recordEncounterClear(eq(alice.getUniqueId()), any(), eq("custom_raid"), anyLong(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(mockRepo.recordEncounterClear(eq(bob.getUniqueId()), any(), eq("custom_raid"), anyLong(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(mockRepo.recordEncounterClear(eq(charlie.getUniqueId()), any(), eq("custom_raid"), anyLong(), anyLong()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("db error")));

        UUID dragonId = UUID.randomUUID();
        EnderDragon mockDragon = mock(EnderDragon.class);
        lenient().when(mockDragon.getUniqueId()).thenReturn(dragonId);
        lenient().when(mockDragon.getWorld()).thenReturn(endWorld);
        lenient().when(mockDragon.getDragonBattle()).thenReturn(null);

        DragonBossController mockBoss = mock(DragonBossController.class);
        lenient().when(mockBoss.bindOrSpawn(any(), anyDouble())).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(mockBoss.cleanup(anyBoolean())).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(mockBoss.hpRatio()).thenReturn(1.0);
        lenient().when(mockBoss.activeDragon()).thenReturn(mockDragon);

        net.sabafly.dragonraid.api.progression.VanillaEndProgressionPolicy mockPolicy =
                mock(net.sabafly.dragonraid.api.progression.VanillaEndProgressionPolicy.class);
        when(mockPolicy.hasApplicableWorldProgressionHook(
                org.mockito.ArgumentMatchers.eq("custom_raid"),
                org.mockito.ArgumentMatchers.eq(EncounterDifficulty.NORMAL),
                anyBoolean()))
                .thenReturn(true);

        AtomicReference<net.sabafly.dragonraid.api.event.EncounterResultSnapshot> capturedSnapshot =
                new AtomicReference<>();
        DefaultRaidEncounterManager mgr = new DefaultRaidEncounterManager(
                configManager,
                messageService,
                scheduler,
                regionService,
                blockTracker,
                soulMarkerService,
                null,
                mockRepo,
                mockBoss,
                null,
                blueprintRegistry,
                gimmickActionRegistry,
                null,
                Logger.getLogger("VictoryTest"),
                () -> endWorld,
                () -> onlinePlayers.values(),
                id -> onlinePlayers.get(id),
                nowMillis::get);
        mgr.setVanillaEndProgressionPolicy(mockPolicy);
        mgr.setEncounterCompletedSink(capturedSnapshot::set);

        assertTrue(mgr.startEncounter(bp, List.of(alice, bob, charlie)));
        nowMillis.addAndGet(5_000L);
        mgr.onDragonDefeated(mockDragon);

        net.sabafly.dragonraid.api.event.EncounterResultSnapshot snap = capturedSnapshot.get();
        assertTrue(snap.firstClear());
        assertEquals(java.util.Set.of(alice.getUniqueId()), snap.firstClearPlayerIds());
        assertTrue(snap.isFirstClearFor(alice.getUniqueId()));
        assertFalse(snap.isFirstClearFor(bob.getUniqueId()));
        assertFalse(snap.isFirstClearFor(charlie.getUniqueId()));
        // Clear hook matched -> legacy vanilla fallback is skipped
        verify(endBattle, never()).spawnNewGateway();
        verify(endBattle, never()).setPreviouslyKilled(true);

        // Run a second victory where no applicable world progression hook matches -> legacy fallback executes
        when(mockPolicy.hasApplicableWorldProgressionHook(
                org.mockito.ArgumentMatchers.eq("custom_raid"),
                org.mockito.ArgumentMatchers.eq(EncounterDifficulty.NORMAL),
                anyBoolean()))
                .thenReturn(false);
        assertTrue(mgr.startEncounter(bp, List.of(bob)));
        nowMillis.addAndGet(5_000L);
        mgr.onDragonDefeated(mockDragon);

        verify(endBattle, org.mockito.Mockito.times(1)).spawnNewGateway();
        verify(endBattle, org.mockito.Mockito.times(1)).setPreviouslyKilled(true);
    }

    @Test
    void delayedVictoryDoesNotOverwriteNewerCompletionResult_andPublishesOnGlobalThread() {
        EnderDragon mockDragon = mock(EnderDragon.class);
        lenient().when(mockDragon.getUniqueId()).thenReturn(UUID.randomUUID());
        lenient().when(mockDragon.getWorld()).thenReturn(endWorld);
        lenient().when(mockDragon.getDragonBattle()).thenReturn(null);
        when(endWorld.getEnderDragonBattle()).thenReturn(null);

        Player alice = addPlayer("Alice", new Location(endWorld, 10.5, 66.0, 0.5));
        UUID aliceId = alice.getUniqueId();
        var bpSlow = sampleBlueprint("enc_slow", EncounterDifficulty.NORMAL);
        var bpFast = sampleBlueprint("enc_fast", EncounterDifficulty.NORMAL);

        CompletableFuture<Boolean> delayedClearFuture = new CompletableFuture<>();
        net.sabafly.dragonraid.storage.PlayerDataRepository mockRepo =
                mock(net.sabafly.dragonraid.storage.PlayerDataRepository.class);
        when(mockRepo.recordEncounterClear(eq(aliceId), eq("Alice"), org.mockito.ArgumentMatchers.eq("enc_slow"), anyLong(), anyLong()))
                .thenReturn(delayedClearFuture);
        when(mockRepo.recordEncounterClear(eq(aliceId), eq("Alice"), org.mockito.ArgumentMatchers.eq("enc_fast"), anyLong(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(true));
        lenient().when(mockRepo.recordClear(any(), any(), any(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(null));

        DragonBossController mockBoss = mock(DragonBossController.class);
        lenient().when(mockBoss.bindOrSpawn(any(), anyDouble())).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(mockBoss.cleanup(anyBoolean())).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(mockBoss.hpRatio()).thenReturn(1.0);
        lenient().when(mockBoss.activeDragon()).thenReturn(mockDragon);

        AtomicInteger globalRunCount = new AtomicInteger();
        RaidScheduler trackingScheduler = mock(RaidScheduler.class);
        lenient().when(trackingScheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(trackingScheduler.runAtEntity(any(), any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(trackingScheduler.runAtRegion(any(World.class), anyInt(), anyInt(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(3).run();
            return TaskHandle.retired();
        });
        lenient().when(trackingScheduler.runGlobalTimer(any(), anyLong(), anyLong())).thenAnswer(inv -> {
            capturedGlobalTimers.add(inv.getArgument(0));
            return mock(TaskHandle.class);
        });
        lenient().when(trackingScheduler.runGlobalLater(any(), anyLong())).thenReturn(mock(TaskHandle.class));
        lenient().when(trackingScheduler.runGlobal(any())).thenAnswer(inv -> {
            globalRunCount.incrementAndGet();
            inv.<Runnable>getArgument(0).run();
            return TaskHandle.retired();
        });

        DefaultRaidEncounterManager mgr = new DefaultRaidEncounterManager(
                configManager, messageService, trackingScheduler, regionService, blockTracker,
                soulMarkerService, null, mockRepo, mockBoss, null,
                blueprintRegistry, gimmickActionRegistry, null, Logger.getLogger("SeqTest"),
                () -> endWorld, () -> onlinePlayers.values(), id -> onlinePlayers.get(id), nowMillis::get);

        assertTrue(mgr.startEncounter(bpSlow, List.of(alice)));
        nowMillis.addAndGet(1_000L);
        mgr.onDragonDefeated(mockDragon);
        // Slow victory DB future still incomplete; run second (fast) encounter to completion
        assertTrue(mgr.startEncounter(bpFast, List.of(alice)));
        nowMillis.addAndGet(2_000L);
        mgr.onDragonDefeated(mockDragon);
        assertEquals("enc_fast", mgr.lastCompletedResult().encounterId());

        // Now complete slow victory's DB future -> must NOT overwrite lastCompletedResult
        delayedClearFuture.complete(true);
        assertEquals("enc_fast", mgr.lastCompletedResult().encounterId());
        assertTrue(globalRunCount.get() >= 2);
    }

    @Test
    void scriptContextDamageAndKnockback_ignoreStaleGenerationAfterReset() {
        List<Runnable> queuedEntityTasks = new ArrayList<>();
        RaidScheduler queuedScheduler = mock(RaidScheduler.class);
        lenient().when(queuedScheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            queuedEntityTasks.add(inv.getArgument(1));
            return TaskHandle.retired();
        });
        lenient().when(queuedScheduler.runAtEntity(any(), any(), any())).thenAnswer(inv -> {
            queuedEntityTasks.add(inv.getArgument(1));
            return TaskHandle.retired();
        });
        lenient().when(queuedScheduler.runGlobalTimer(any(), anyLong(), anyLong())).thenAnswer(inv -> {
            capturedGlobalTimers.add(inv.getArgument(0));
            return mock(TaskHandle.class);
        });
        lenient().when(queuedScheduler.runGlobalLater(any(), anyLong())).thenReturn(mock(TaskHandle.class));

        gimmickActionRegistry.register(new GimmickAction() {
            @Override
            public String id() {
                return "DEFERRED_STRIKE";
            }

            @Override
            public void execute(RaidScriptContext context, Map<String, Object> params) {
                for (Player target : context.aliveParticipants()) {
                    context.damagePlayer(target, 10.0, "test_strike");
                    context.knockbackPlayer(target, new org.bukkit.util.Vector(1.0, 0.5, 0.0));
                }
            }
        });
        EncounterBlueprint bp = new EncounterBlueprintBuilder("stale_script_bp")
                .difficulty(EncounterDifficulty.NORMAL)
                .displayNameKey("boss.normal-name")
                .primaryBoss("boss_alpha")
                .enemy("boss_alpha", e -> e
                        .displayNameKey("boss.normal-name")
                        .stats(1200.0, 0.65, false, true, true))
                .phase("phase-1", p -> p
                        .displayNameKey("phase.p1.name")
                        .hpRange(1.0, 0.0)
                        .onEnter("DEFERRED_STRIKE")
                        .loop(10.0))
                .build();

        DragonBossController mockBoss = mock(DragonBossController.class);
        lenient().when(mockBoss.bindOrSpawn(any(), anyDouble())).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(mockBoss.hpRatio()).thenReturn(1.0);
        lenient().when(mockBoss.cleanup(anyBoolean())).thenReturn(CompletableFuture.completedFuture(null));

        DefaultRaidEncounterManager mgr = new DefaultRaidEncounterManager(
                configManager,
                messageService,
                queuedScheduler,
                regionService,
                blockTracker,
                soulMarkerService,
                null,
                null,
                mockBoss,
                null,
                blueprintRegistry,
                gimmickActionRegistry,
                null,
                Logger.getLogger("StaleScriptContextTest"),
                () -> endWorld,
                () -> onlinePlayers.values(),
                id -> onlinePlayers.get(id),
                nowMillis::get);

        Player alice = addPlayer("Alice", new Location(endWorld, 100.5, 50.0, 0.5));
        lenient().when(alice.getVelocity()).thenReturn(new org.bukkit.util.Vector());
        assertTrue(mgr.startEncounter(bp, List.of(alice)));

        // Drain initial capture tasks so only post-reset/new tasks or queued script tasks remain carefully separated
        List<Runnable> staleAndSetupTasks = new ArrayList<>(queuedEntityTasks);
        queuedEntityTasks.clear();

        // Reset encounter and immediately start a fresh generation BEFORE stale script tasks execute
        mgr.adminReset(alice);
        queuedEntityTasks.clear();
        assertTrue(mgr.startEncounter(sampleBlueprint("fresh_bp", EncounterDifficulty.NORMAL), List.of(alice)));
        queuedEntityTasks.clear();

        // Now flush all queued tasks from the reset first encounter
        for (Runnable task : staleAndSetupTasks) {
            task.run();
        }

        verify(alice, never()).damage(anyDouble());
        verify(alice, never()).setVelocity(any());
    }

    @Test
    void activeEncounter_locksLateJoinersToSafeZone() {
        Player alice = addPlayer("Alice", new Location(endWorld, 0.0, 64.0, 0.0));
        manager.adminStart(alice, BossConfig.Difficulty.NORMAL);
        assertEquals(RaidState.COUNTDOWN, manager.state());

        // Tick 8 seconds down to ACTIVE
        Runnable countdown = capturedGlobalTimers.get(0);
        for (int i = 0; i < 8; i++) {
            countdown.run();
        }
        assertEquals(RaidState.ACTIVE, manager.state());
        assertTrue(manager.isParticipant(alice.getUniqueId()));
        assertEquals(1, manager.remainingRevives());

        // Late joiner Bob enters arena -> cancelled and teleported to safe zone
        Player bob = addPlayer("Bob", new Location(endWorld, 0.0, 64.0, 0.0));
        assertFalse(manager.isParticipant(bob.getUniqueId()));
        PlayerMoveEvent bobMove = new PlayerMoveEvent(
                bob, new Location(endWorld, 100.5, 50.0, 0.5), new Location(endWorld, 0.0, 64.0, 0.0));
        manager.onPlayerMove(bobMove);
        assertTrue(bobMove.isCancelled());
        verify(bob).teleportAsync(new Location(endWorld, 100.5, 50.0, 0.5));
    }

    @Test
    void lethalDamageAndReviveChannel_normalRulesAllow25sHoldAndDecrementRevives() {
        Player alice = addPlayer("Alice", new Location(endWorld, 0.0, 64.0, 0.0));
        Player bob = addPlayer("Bob", new Location(endWorld, 1.0, 64.0, 0.0));
        startActiveEncounter(alice, BossConfig.Difficulty.NORMAL);

        assertEquals(2, manager.remainingRevives());

        // Alice takes lethal damage -> cancelled & transitions to DOWNED
        EntityDamageEvent lethal = mockDamageEvent(50.0, EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        manager.onPlayerDamage(alice, lethal);
        verify(lethal).setCancelled(true);
        assertTrue(manager.isDowned(alice.getUniqueId()));
        verify(alice).setInvulnerable(true);
        verify(alice).setInvisible(true);
        verify(soulMarkerService, atLeastOnce()).spawnOrUpdateMarker(
                eq(alice.getUniqueId()), eq("Alice"), any(), eq(null), eq(0.0), any());

        // Downed Alice cannot move X/Y/Z, only rotate
        PlayerMoveEvent moveAttempt = new PlayerMoveEvent(
                alice,
                new Location(endWorld, 0.0, 64.0, 0.0),
                new Location(endWorld, 5.0, 64.0, 0.0, 90.0f, 15.0f));
        manager.onPlayerMove(moveAttempt);
        assertEquals(0.0, moveAttempt.getTo().getX(), 1e-6);
        assertEquals(90.0f, moveAttempt.getTo().getYaw(), 1e-6);

        // Bob sneaks within 2.5m for 2.5 seconds (25 ticks of 0.1s monitor step)
        when(bob.isSneaking()).thenReturn(true);
        for (int i = 0; i < 25; i++) {
            manager.tickActiveEncounter();
        }

        assertEquals(ParticipantState.ALIVE, manager.participantState(alice.getUniqueId()).orElseThrow());
        assertEquals(1, manager.remainingRevives());
        verify(soulMarkerService, atLeastOnce()).removeMarker(eq(alice.getUniqueId()), any());
    }

    @Test
    void hardMode45SecondTimeout_transitionsToDeadSpectatorAndTriggersWipe() {
        Player alice = addPlayer("Alice", new Location(endWorld, 0.0, 64.0, 0.0));
        Player bob = addPlayer("Bob", new Location(endWorld, 10.0, 64.0, 0.0));
        startActiveEncounter(alice, BossConfig.Difficulty.HARD);

        assertEquals(1, manager.remainingRevives()); // ceil(2 / 2) = 1

        // Alice downed at t = 100_000
        manager.onPlayerDamage(alice, mockDamageEvent(50.0, EntityDamageEvent.DamageCause.ENTITY_ATTACK));
        assertTrue(manager.isDowned(alice.getUniqueId()));

        // Advance clock by 45 seconds -> Alice expires to DEAD_SPECTATOR
        nowMillis.addAndGet(45_000L);
        manager.tickActiveEncounter();
        assertTrue(manager.isDeadSpectator(alice.getUniqueId()));
        verify(alice).setGameMode(GameMode.SPECTATOR);

        // Bob falls into void -> rescued & downed -> 0 ALIVE left -> WIPING
        when(bob.getLocation()).thenReturn(new Location(endWorld, 40.0, -3.0, 0.0));
        manager.tickActiveEncounter();
        verify(bob, atLeastOnce()).teleportAsync(new Location(endWorld, 10.5, 66.0, 0.5));
        assertEquals(RaidState.WIPING, manager.state());

        // Track a temporary block and verify wipe delayed task rolls back & returns to IDLE
        BlockData origData = mock(BlockData.class);
        when(origData.clone()).thenReturn(origData);
        blockTracker.tryRecordPlayerPlacement(new Location(endWorld, 3, 65, 3), bob.getUniqueId(), origData, 64, 512);
        org.bukkit.block.Block worldBlock = mock(org.bukkit.block.Block.class);
        when(endWorld.getBlockAt(3, 65, 3)).thenReturn(worldBlock);

        assertFalse(capturedDelayedTasks.isEmpty());
        capturedDelayedTasks.get(0).run();
        assertEquals(RaidState.IDLE, manager.state());
        verify(worldBlock).setBlockData(origData, false);
        verify(alice, atLeastOnce()).setGameMode(GameMode.SURVIVAL);
    }

    @Test
    void soloFallbackTotemAndDisconnectModesWorkAsConfigured() throws Exception {
        Player alice = addPlayer("Alice", new Location(endWorld, 0.0, 64.0, 0.0));
        ItemStack totem = mock(ItemStack.class);
        when(totem.getType()).thenReturn(Material.TOTEM_OF_UNDYING);
        EntityEquipment eq = mock(EntityEquipment.class);
        when(eq.getItemInMainHand()).thenReturn(totem);
        when(eq.getItemInOffHand()).thenReturn(mock(ItemStack.class));
        when(alice.getEquipment()).thenReturn(eq);

        startActiveEncounter(alice, BossConfig.Difficulty.NORMAL);

        // Lethal hit while holding Totem (N=1) is NOT intercepted -> allows vanilla Totem
        EntityDamageEvent lethalWithTotem = mockDamageEvent(50.0, EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        manager.onPlayerDamage(alice, lethalWithTotem);
        verify(lethalWithTotem, never()).setCancelled(true);
        assertEquals(ParticipantState.ALIVE, manager.participantState(alice.getUniqueId()).orElseThrow());

        // Item loss protection applies on PlayerDeathEvent
        PlayerDeathEvent deathEvent = mock(PlayerDeathEvent.class);
        when(deathEvent.getDrops()).thenReturn(new ArrayList<>(List.of(totem)));
        manager.onParticipantDeath(alice, deathEvent);
        verify(deathEvent).setKeepInventory(true);
        verify(deathEvent).setKeepLevel(true);

        // Reset, enable down-on-disconnect: true, start 2-player raid, quit Alice -> DOWNED
        manager.adminReset(alice);
        String updatedCfg = Files.readString(tempDir.resolve("config.yml"), StandardCharsets.UTF_8)
                .replace("down-on-disconnect: false", "down-on-disconnect: true");
        Files.writeString(tempDir.resolve("config.yml"), updatedCfg, StandardCharsets.UTF_8);
        configManager.reload();

        Player bob = addPlayer("Bob", new Location(endWorld, 2.0, 64.0, 0.0));
        capturedGlobalTimers.clear();
        startActiveEncounter(alice, BossConfig.Difficulty.NORMAL);
        manager.onPlayerQuit(alice);
        assertTrue(manager.isDowned(alice.getUniqueId()));

        // Rejoin while raid still active restores DOWNED state
        manager.onPlayerJoin(alice);
        assertTrue(manager.isDowned(alice.getUniqueId()));
        assertTrue(manager.isParticipant(alice.getUniqueId()));
        verify(bob, never()).setGameMode(GameMode.SPECTATOR);
    }

    @Test
    void pendingCleanupPlayersPreservedAcrossSubsequentEncounterStart() {
        Player alice = addPlayer("Alice", new Location(endWorld, 0.0, 64.0, 0.0));
        Player bob = addPlayer("Bob", new Location(endWorld, 1.0, 64.0, 0.0));
        startActiveEncounter(alice, BossConfig.Difficulty.HARD);

        // Alice gets downed, disconnects, and expires to DEAD_SPECTATOR (or stays downed offline)
        manager.onPlayerDamage(alice, mockDamageEvent(50.0, EntityDamageEvent.DamageCause.ENTITY_ATTACK));
        assertTrue(manager.isDowned(alice.getUniqueId()));
        when(alice.isOnline()).thenReturn(false);
        onlinePlayers.remove(alice.getUniqueId());
        manager.onPlayerQuit(alice);

        // Raid ends via adminReset while Alice is offline -> saved in pendingCleanupPlayers
        manager.adminReset(bob);
        assertEquals(RaidState.IDLE, manager.state());

        // Bob starts a NEW raid and locks in before Alice returns
        startActiveEncounter(bob, BossConfig.Difficulty.NORMAL);

        // Now Alice reconnects -> her cleanup record MUST still exist and restore her state
        when(alice.isOnline()).thenReturn(true);
        when(alice.isInvisible()).thenReturn(true);
        when(alice.isInvulnerable()).thenReturn(true);
        when(alice.isCollidable()).thenReturn(false);
        onlinePlayers.put(alice.getUniqueId(), alice);

        manager.onPlayerJoin(alice);
        verify(alice, atLeastOnce()).setInvisible(false);
        verify(alice, atLeastOnce()).setInvulnerable(false);
        verify(alice, atLeastOnce()).setCollidable(true);
    }

    @Test
    void voidRescueInProgress_cancelsIncomingDamageWithoutOverwritingRescueLocation() {
        CompletableFuture<Location> delayedRescue = new CompletableFuture<>();
        when(regionService.findNearestSafeArenaEdgeAsync(any(), anyDouble())).thenReturn(delayedRescue);

        Player alice = addPlayer("Alice", new Location(endWorld, 0.0, 64.0, 0.0));
        Player bob = addPlayer("Bob", new Location(endWorld, 5.0, 64.0, 0.0));
        startActiveEncounter(alice, BossConfig.Difficulty.NORMAL);

        // Alice falls into void
        Location voidLoc = new Location(endWorld, 50.0, -4.0, 0.0);
        when(alice.getLocation()).thenReturn(voidLoc);
        manager.tickActiveEncounter();

        // While rescue future is still pending, lethal VOID damage fires
        EntityDamageEvent voidDamage = mockDamageEvent(50.0, EntityDamageEvent.DamageCause.VOID);
        manager.onPlayerDamage(alice, voidDamage);
        verify(voidDamage).setCancelled(true);
        // Must still be ALIVE until rescue resolves, NOT clamped prematurely
        assertEquals(ParticipantState.ALIVE, manager.participantState(alice.getUniqueId()).orElseThrow());

        // Complete rescue to safe ledge (12.5, 68.0, 4.5)
        Location rescuedLedge = new Location(endWorld, 12.5, 68.0, 4.5);
        delayedRescue.complete(rescuedLedge);

        assertTrue(manager.isDowned(alice.getUniqueId()));
        verify(alice).teleportAsync(rescuedLedge);
        verify(soulMarkerService, atLeastOnce()).spawnOrUpdateMarker(
                eq(alice.getUniqueId()), eq("Alice"), eq(rescuedLedge), eq(null), eq(0.0), any());
        verify(bob, never()).setGameMode(GameMode.SPECTATOR);
    }

    @Test
    void partySizeEnforcement_enforcesMinPlayersAndMaxPlayersCap() throws Exception {
        String updatedCfg = Files.readString(tempDir.resolve("config.yml"), StandardCharsets.UTF_8)
                .replace("min-players: 1", "min-players: 2")
                .replace("max-players: 8", "max-players: 2")
                .replace("enforce-max-players: false", "enforce-max-players: true");
        Files.writeString(tempDir.resolve("config.yml"), updatedCfg, StandardCharsets.UTF_8);
        configManager.reload();

        // 1 player < minPlayers(2) -> rejected
        Player alice = addPlayer("Alice", new Location(endWorld, 10.0, 64.0, 0.0));
        manager.adminStart(alice, BossConfig.Difficulty.NORMAL);
        assertEquals(RaidState.IDLE, manager.state());

        // 2 players -> countdown starts
        Player bob = addPlayer("Bob", new Location(endWorld, 12.0, 64.0, 0.0));
        manager.adminStart(alice, BossConfig.Difficulty.NORMAL);
        assertEquals(RaidState.COUNTDOWN, manager.state());

        // 3rd player joins in safe-zone during countdown -> capped to maxPlayers(2), safe-zone player locked out
        Player charlie = addPlayer("Charlie", new Location(endWorld, 100.5, 50.0, 0.5));
        Runnable countdown = capturedGlobalTimers.get(capturedGlobalTimers.size() - 1);
        int secs = configManager.pluginConfig().encounter().pullCountdownSeconds();
        for (int i = 0; i < secs; i++) {
            countdown.run();
        }
        assertEquals(RaidState.ACTIVE, manager.state());
        assertEquals(2, manager.activeScaling().orElseThrow().partySize());
        assertTrue(manager.isParticipant(alice.getUniqueId()));
        assertTrue(manager.isParticipant(bob.getUniqueId()));
        assertFalse(manager.isParticipant(charlie.getUniqueId()));
        verify(charlie, atLeastOnce()).teleportAsync(new Location(endWorld, 100.5, 50.0, 0.5));
    }

    @Test
    void shutdownAndStartupRecovery_persistsOfflineCleanupsAndPendingBlocksInSqlite() {
        Path dbPath = tempDir.resolve("recovery-encounter.db");
        UUID worldId = endWorld.getUID();
        BlockData origData = mock(BlockData.class);
        when(origData.clone()).thenReturn(origData);
        TemporaryBlockTracker tracker = new TemporaryBlockTracker(d -> "minecraft:air", s -> origData);
        UUID aliceId;
        UUID bobId;

        try (SqliteDatabase db = new SqliteDatabase(dbPath, 2, Runnable::run)) {
            SqliteRaidRecoveryRepository recoveryRepo = new SqliteRaidRecoveryRepository(db);
            DefaultRaidEncounterManager firstRun = newTestManager(
                    scheduler,
                    tracker,
                    recoveryRepo,
                    Logger.getLogger("RecoveryTest1"));

            Player alice = addPlayer("Alice", new Location(endWorld, 0.0, 64.0, 0.0));
            Player bob = addPlayer("Bob", new Location(endWorld, 2.0, 64.0, 0.0));
            aliceId = alice.getUniqueId();
            bobId = bob.getUniqueId();
            firstRun.adminStart(alice, BossConfig.Difficulty.HARD);
            Runnable countdown = capturedGlobalTimers.get(capturedGlobalTimers.size() - 1);
            for (int i = 0; i < configManager.pluginConfig().encounter().pullCountdownSeconds(); i++) {
                countdown.run();
            }
            assertEquals(RaidState.ACTIVE, firstRun.state());

            // Alice becomes DEAD_SPECTATOR and disconnects; Bob places a temporary block
            firstRun.onPlayerDamage(alice, mockDamageEvent(50.0, EntityDamageEvent.DamageCause.ENTITY_ATTACK));
            nowMillis.addAndGet(45_000L);
            firstRun.tickActiveEncounter();
            assertTrue(firstRun.isDeadSpectator(alice.getUniqueId()));

            when(alice.isOnline()).thenReturn(false);
            onlinePlayers.remove(alice.getUniqueId());
            firstRun.onPlayerQuit(alice);

            tracker.tryRecordPlayerPlacement(new Location(endWorld, 7, 65, 7), bob.getUniqueId(), origData, 64, 512);

            // Server shutdown
            firstRun.shutdown();
        }

        // Simulate restart with fresh DB + tracker + encounter manager
        org.bukkit.block.Block worldBlock = mock(org.bukkit.block.Block.class);
        when(endWorld.getBlockAt(7, 65, 7)).thenReturn(worldBlock);

        try (SqliteDatabase db2 = new SqliteDatabase(dbPath, 2, Runnable::run)) {
            SqliteRaidRecoveryRepository recoveryRepo2 = new SqliteRaidRecoveryRepository(db2);
            TemporaryBlockTracker tracker2 = new TemporaryBlockTracker(d -> "minecraft:air", s -> origData);
            DefaultRaidEncounterManager secondRun = newTestManager(
                    scheduler,
                    tracker2,
                    recoveryRepo2,
                    Logger.getLogger("RecoveryTest2"));

            // Startup block recovery restores (7, 65, 7) and removes persisted row
            secondRun.recoverPendingBlockRollbacks(id -> worldId.equals(id) ? endWorld : null);
            verify(worldBlock).setBlockData(origData, false);
            assertTrue(recoveryRepo2.loadPendingBlockRollbacksSync().isEmpty());

            // Offline Alice and previously-online Bob both have persisted cleanup records after shutdown
            var persistedCleanups = recoveryRepo2.loadPendingPlayerCleanupsSync();
            assertEquals(2, persistedCleanups.size());
            assertTrue(persistedCleanups.containsKey(aliceId));
            assertTrue(persistedCleanups.containsKey(bobId));

            // Offline Alice rejoins after restart -> restored from SPECTATOR to SURVIVAL & moved to safe-zone
            Player aliceRejoined = mock(Player.class);
            when(aliceRejoined.getUniqueId()).thenReturn(aliceId);
            when(aliceRejoined.getName()).thenReturn("Alice");
            when(aliceRejoined.isOnline()).thenReturn(true);
            when(aliceRejoined.getWorld()).thenReturn(endWorld);
            when(aliceRejoined.getLocation()).thenReturn(new Location(endWorld, 0.0, 64.0, 0.0));
            when(aliceRejoined.getGameMode()).thenReturn(GameMode.SPECTATOR);
            when(aliceRejoined.teleportAsync(any())).thenReturn(CompletableFuture.completedFuture(true));

            secondRun.onPlayerJoin(aliceRejoined);
            verify(aliceRejoined).setGameMode(GameMode.SURVIVAL);
            verify(aliceRejoined).teleportAsync(new Location(endWorld, 100.5, 50.0, 0.5));
            assertEquals(java.util.Set.of(bobId), recoveryRepo2.loadPendingPlayerCleanupsSync().keySet());

            // Bob rejoins -> Bob's pending cleanup is also applied and deleted
            Player bobRejoined = mock(Player.class);
            when(bobRejoined.getUniqueId()).thenReturn(bobId);
            when(bobRejoined.getName()).thenReturn("Bob");
            when(bobRejoined.isOnline()).thenReturn(true);
            when(bobRejoined.getWorld()).thenReturn(endWorld);
            when(bobRejoined.getLocation()).thenReturn(new Location(endWorld, 0.0, 64.0, 0.0));
            when(bobRejoined.getGameMode()).thenReturn(GameMode.SURVIVAL);
            when(bobRejoined.teleportAsync(any())).thenReturn(CompletableFuture.completedFuture(true));

            secondRun.onPlayerJoin(bobRejoined);
            verify(bobRejoined).teleportAsync(new Location(endWorld, 100.5, 50.0, 0.5));
            assertTrue(recoveryRepo2.loadPendingPlayerCleanupsSync().isEmpty());
        }
    }

    @Test
    void queuedEntitySnapshotUpdates_handlePendingThenFlushedMonitorTicks() {
        List<Runnable> queuedEntityTasks = new ArrayList<>();
        RaidScheduler queuedScheduler = mock(RaidScheduler.class);
        lenient().when(queuedScheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            queuedEntityTasks.add(inv.getArgument(1));
            return TaskHandle.retired();
        });
        lenient().when(queuedScheduler.runAtEntity(any(), any(), any())).thenAnswer(inv -> {
            queuedEntityTasks.add(inv.getArgument(1));
            return TaskHandle.retired();
        });
        lenient().when(queuedScheduler.runAtEntityLater(any(), any(), anyLong())).thenReturn(TaskHandle.retired());
        lenient().when(queuedScheduler.runGlobalTimer(any(), anyLong(), anyLong())).thenAnswer(inv -> {
            capturedGlobalTimers.add(inv.getArgument(0));
            return mock(TaskHandle.class);
        });
        lenient().when(queuedScheduler.runGlobalLater(any(), anyLong())).thenAnswer(inv -> {
            capturedDelayedTasks.add(inv.getArgument(0));
            return mock(TaskHandle.class);
        });

        DefaultRaidEncounterManager asyncMgr = newTestManager(
                queuedScheduler,
                blockTracker,
                null,
                Logger.getLogger("QueuedEncounterTest"));

        Player alice = addPlayer("Alice", new Location(endWorld, 0.0, 64.0, 0.0));
        Player bob = addPlayer("Bob", new Location(endWorld, 5.0, 64.0, 0.0));
        asyncMgr.adminStart(alice, BossConfig.Difficulty.NORMAL);
        Runnable countdown = capturedGlobalTimers.get(capturedGlobalTimers.size() - 1);
        for (int i = 0; i < configManager.pluginConfig().encounter().pullCountdownSeconds(); i++) {
            countdown.run();
        }
        assertEquals(RaidState.ACTIVE, asyncMgr.state());

        // Flush initial captureOriginalState / title tasks
        while (!queuedEntityTasks.isEmpty()) {
            queuedEntityTasks.removeFirst().run();
        }

        // Alice moves into void without triggering onPlayerMove yet; entity task hasn't flushed snapshot
        when(alice.getLocation()).thenReturn(new Location(endWorld, 40.0, -3.0, 0.0));
        asyncMgr.tickActiveEncounter();
        // Before entity task runs, stale snapshot hasn't triggered void rescue yet
        assertEquals(ParticipantState.ALIVE, asyncMgr.participantState(alice.getUniqueId()).orElseThrow());
        assertFalse(queuedEntityTasks.isEmpty());

        // Flush queued entity snapshot refresh tasks, then run monitor tick again
        while (!queuedEntityTasks.isEmpty()) {
            queuedEntityTasks.removeFirst().run();
        }
        asyncMgr.tickActiveEncounter();
        while (!queuedEntityTasks.isEmpty()) {
            queuedEntityTasks.removeFirst().run();
        }

        assertTrue(asyncMgr.isDowned(alice.getUniqueId()));
        verify(alice, atLeastOnce()).teleportAsync(new Location(endWorld, 10.5, 66.0, 0.5));
        verify(bob, never()).setGameMode(GameMode.SPECTATOR);
    }

    @Test
    void changingReviverResetsProgress_andInitialSnapshotDelayDoesNotFalseWipe() {
        List<Runnable> queuedEntityTasks = new ArrayList<>();
        RaidScheduler queuedScheduler = mock(RaidScheduler.class);
        lenient().when(queuedScheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            queuedEntityTasks.add(inv.getArgument(1));
            return TaskHandle.retired();
        });
        lenient().when(queuedScheduler.runAtEntity(any(), any(), any())).thenAnswer(inv -> {
            queuedEntityTasks.add(inv.getArgument(1));
            return TaskHandle.retired();
        });
        lenient().when(queuedScheduler.runAtEntityLater(any(), any(), anyLong())).thenReturn(TaskHandle.retired());
        lenient().when(queuedScheduler.runGlobalTimer(any(), anyLong(), anyLong())).thenAnswer(inv -> {
            capturedGlobalTimers.add(inv.getArgument(0));
            return mock(TaskHandle.class);
        });
        lenient().when(queuedScheduler.runGlobalLater(any(), anyLong())).thenAnswer(inv -> {
            capturedDelayedTasks.add(inv.getArgument(0));
            return mock(TaskHandle.class);
        });

        DefaultRaidEncounterManager mgr = newTestManager(
                queuedScheduler,
                blockTracker,
                null,
                Logger.getLogger("ReviverResetTest"));

        Player alice = addPlayer("Alice", new Location(endWorld, 0.0, 64.0, 0.0));
        Player bob = addPlayer("Bob", new Location(endWorld, 1.0, 64.0, 0.0));
        Player charlie = addPlayer("Charlie", new Location(endWorld, 1.0, 64.0, 0.0));

        mgr.adminStart(alice, BossConfig.Difficulty.NORMAL);
        Runnable countdown = capturedGlobalTimers.get(capturedGlobalTimers.size() - 1);
        for (int i = 0; i < configManager.pluginConfig().encounter().pullCountdownSeconds(); i++) {
            countdown.run();
        }
        assertEquals(RaidState.ACTIVE, mgr.state());

        // Tick BEFORE initial entity snapshot tasks run -> must NOT trigger false allLeftArena wipe
        mgr.tickActiveEncounter();
        assertEquals(RaidState.ACTIVE, mgr.state());

        // Flush initial snapshots & down Alice
        while (!queuedEntityTasks.isEmpty()) {
            queuedEntityTasks.removeFirst().run();
        }
        mgr.onPlayerDamage(alice, mockDamageEvent(50.0, EntityDamageEvent.DamageCause.ENTITY_ATTACK));
        while (!queuedEntityTasks.isEmpty()) {
            queuedEntityTasks.removeFirst().run();
        }
        assertTrue(mgr.isDowned(alice.getUniqueId()));

        // Refresh snapshots with Bob sneaking, then Bob channels for 2.0s (20 ticks)
        when(bob.isSneaking()).thenReturn(true);
        mgr.tickActiveEncounter();
        while (!queuedEntityTasks.isEmpty()) {
            queuedEntityTasks.removeFirst().run();
        }
        for (int i = 0; i < 20; i++) {
            mgr.tickActiveEncounter();
            while (!queuedEntityTasks.isEmpty()) {
                queuedEntityTasks.removeFirst().run();
            }
        }
        assertTrue(mgr.isDowned(alice.getUniqueId()));

        // Bob stops, Charlie takes over -> progress resets to 0, so 5 ticks (0.5s) is NOT enough!
        // First tick still sees Bob's previous snapshot (2.1s) while scheduling Charlie's sneaking=true snapshot.
        when(bob.isSneaking()).thenReturn(false);
        when(charlie.isSneaking()).thenReturn(true);
        mgr.tickActiveEncounter();
        while (!queuedEntityTasks.isEmpty()) {
            queuedEntityTasks.removeFirst().run();
        }
        for (int i = 0; i < 5; i++) {
            mgr.tickActiveEncounter();
            while (!queuedEntityTasks.isEmpty()) {
                queuedEntityTasks.removeFirst().run();
            }
        }
        assertTrue(mgr.isDowned(alice.getUniqueId()));

        // Charlie completes remaining 20 ticks (total 25 ticks = 2.5s for Charlie) -> revived!
        for (int i = 0; i < 20; i++) {
            mgr.tickActiveEncounter();
            while (!queuedEntityTasks.isEmpty()) {
                queuedEntityTasks.removeFirst().run();
            }
        }
        assertEquals(ParticipantState.ALIVE, mgr.participantState(alice.getUniqueId()).orElseThrow());
    }

    @Test
    void voidRescueIgnoresStaleCompletionAfterReset_andUnrecoveredBlocksPersistAcrossShutdown() {
        CompletableFuture<Location> delayedRescue = new CompletableFuture<>();
        when(regionService.findNearestSafeArenaEdgeAsync(any(), anyDouble())).thenReturn(delayedRescue);

        Player alice = addPlayer("Alice", new Location(endWorld, 0.0, 64.0, 0.0));
        startActiveEncounter(alice, BossConfig.Difficulty.NORMAL);

        // Trigger void rescue in generation 1
        when(alice.getLocation()).thenReturn(new Location(endWorld, 40.0, -3.0, 0.0));
        manager.tickActiveEncounter();

        // Admin resets and starts generation 2 while generation 1 rescue is still in flight
        when(alice.getLocation()).thenReturn(new Location(endWorld, 0.0, 64.0, 0.0));
        manager.adminReset(alice);
        startActiveEncounter(alice, BossConfig.Difficulty.NORMAL);

        // Stale rescue from generation 1 completes -> MUST be ignored
        delayedRescue.complete(new Location(endWorld, 10.5, 66.0, 0.5));
        assertEquals(ParticipantState.ALIVE, manager.participantState(alice.getUniqueId()).orElseThrow());

        // Also verify unrecovered blocks for unloaded worlds survive shutdown merge
        Path dbPath = tempDir.resolve("unloaded-world.db");
        UUID unloadedWorldId = UUID.randomUUID();
        BlockData origData = mock(BlockData.class);
        when(origData.clone()).thenReturn(origData);
        TemporaryBlockTracker tracker = new TemporaryBlockTracker(d -> "minecraft:air", s -> origData);

        try (SqliteDatabase db = new SqliteDatabase(dbPath, 2, Runnable::run)) {
            SqliteRaidRecoveryRepository repo = new SqliteRaidRecoveryRepository(db);
            repo.replacePendingBlockRollbacksSync(List.of(
                    new PendingBlockRollback(unloadedWorldId, 5, 60, 5, "minecraft:obsidian")));

            DefaultRaidEncounterManager mgr = newTestManager(
                    scheduler,
                    tracker,
                    repo,
                    Logger.getLogger("UnloadedTest"));

            // worldLookup returns null for unloadedWorldId
            mgr.recoverPendingBlockRollbacks(id -> null);
            tracker.tryRecordPlayerPlacement(new Location(endWorld, 1, 64, 1), alice.getUniqueId(), origData, 64, 512);
            mgr.shutdown();

            List<PendingBlockRollback> persisted = repo.loadPendingBlockRollbacksSync();
            assertEquals(2, persisted.size());
            assertTrue(persisted.stream().anyMatch(r -> r.worldId().equals(unloadedWorldId)));
            assertTrue(persisted.stream().anyMatch(r -> r.worldId().equals(endWorld.getUID())));
        }
    }

    @Test
    void retiredJoinCleanupTaskPreservesRecordForNextJoin() {
        Path dbPath = tempDir.resolve("retired-join.db");
        List<Runnable> queuedEntityTasks = new ArrayList<>();
        RaidScheduler retiringScheduler = mock(RaidScheduler.class);
        lenient().when(retiringScheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            queuedEntityTasks.add(inv.getArgument(1));
            return TaskHandle.retired();
        });

        try (SqliteDatabase db = new SqliteDatabase(dbPath, 2, Runnable::run)) {
            SqliteRaidRecoveryRepository repo = new SqliteRaidRecoveryRepository(db);
            UUID aliceId = UUID.randomUUID();
            repo.upsertPendingPlayerCleanupAsync(
                    new net.sabafly.dragonraid.storage.RaidRecoveryRepository.PendingPlayerCleanup(
                            aliceId, "SURVIVAL", false, true, false, true)).join();

            DefaultRaidEncounterManager mgr = newTestManager(
                    retiringScheduler,
                    blockTracker,
                    repo,
                    Logger.getLogger("RetiredJoinTest"));

            Player alice = mock(Player.class);
            when(alice.getUniqueId()).thenReturn(aliceId);
            when(alice.getName()).thenReturn("Alice");
            when(alice.getWorld()).thenReturn(endWorld);
            when(alice.getLocation()).thenReturn(new Location(endWorld, 0.0, 64.0, 0.0));
            when(alice.getGameMode()).thenReturn(GameMode.SPECTATOR);
            when(alice.teleportAsync(any())).thenReturn(CompletableFuture.completedFuture(true));

            // First join queues entity task, then entity retires before running (task dropped)
            mgr.onPlayerJoin(alice);
            queuedEntityTasks.clear();
            assertEquals(1, repo.loadPendingPlayerCleanupsSync().size());

            // Second join actually executes the entity task -> cleans up & deletes record
            mgr.onPlayerJoin(alice);
            assertEquals(1, queuedEntityTasks.size());
            queuedEntityTasks.removeFirst().run();
            verify(alice).setGameMode(GameMode.SURVIVAL);
            assertTrue(repo.loadPendingPlayerCleanupsSync().isEmpty());
        }
    }

    @Test
    void staleFinishWipeAfterReset_doesNotResetNextRaid_andWipingQuitMarksOffline() {
        Player alice = addPlayer("Alice", new Location(endWorld, 0.0, 64.0, 0.0));
        Player bob = addPlayer("Bob", new Location(endWorld, 2.0, 64.0, 0.0));
        startActiveEncounter(alice, BossConfig.Difficulty.NORMAL);

        // Down Alice and Bob -> enters WIPING and schedules delayed finishWipe
        manager.onPlayerDamage(alice, mockDamageEvent(50.0, EntityDamageEvent.DamageCause.ENTITY_ATTACK));
        manager.onPlayerDamage(bob, mockDamageEvent(50.0, EntityDamageEvent.DamageCause.ENTITY_ATTACK));
        assertEquals(RaidState.WIPING, manager.state());
        Runnable staleWipeTask = capturedDelayedTasks.getLast();

        // Bob quits during WIPING -> marked offline (not still marked online)
        manager.onPlayerQuit(bob);
        assertFalse(manager.participantState(bob.getUniqueId()).isEmpty());

        // Admin resets while WIPING and starts a fresh raid
        manager.adminReset(alice);
        assertEquals(RaidState.IDLE, manager.state());
        startActiveEncounter(alice, BossConfig.Difficulty.NORMAL);
        assertEquals(RaidState.ACTIVE, manager.state());

        // Firing the stale finishWipe from generation 1 must NOT wipe the new generation 2 raid
        staleWipeTask.run();
        assertEquals(RaidState.ACTIVE, manager.state());
        assertTrue(manager.isParticipant(alice.getUniqueId()));
    }

    @Test
    void inFlightBlockRollbackInterruptedByShutdown_preservesBlocksInSqlite() {
        Path dbPath = tempDir.resolve("inflight-rollback.db");
        BlockData origData = mock(BlockData.class);
        when(origData.clone()).thenReturn(origData);
        TemporaryBlockTracker tracker = new TemporaryBlockTracker(d -> "minecraft:air", s -> origData);

        List<Runnable> pendingRegionRollbacks = new ArrayList<>();
        RaidScheduler deferredRegionScheduler = mock(RaidScheduler.class);
        lenient().when(deferredRegionScheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(deferredRegionScheduler.runAtEntity(any(), any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(deferredRegionScheduler.runAtEntityLater(any(), any(), anyLong())).thenReturn(TaskHandle.retired());
        lenient().when(deferredRegionScheduler.runGlobalTimer(any(), anyLong(), anyLong())).thenAnswer(inv -> {
            capturedGlobalTimers.add(inv.getArgument(0));
            return mock(TaskHandle.class);
        });
        lenient().when(deferredRegionScheduler.runGlobalLater(any(), anyLong())).thenAnswer(inv -> {
            capturedDelayedTasks.add(inv.getArgument(0));
            return mock(TaskHandle.class);
        });
        lenient().when(deferredRegionScheduler.runAtRegion(any(), anyInt(), anyInt(), any())).thenAnswer(inv -> {
            pendingRegionRollbacks.add(inv.getArgument(3));
            return TaskHandle.retired();
        });

        try (SqliteDatabase db = new SqliteDatabase(dbPath, 2, Runnable::run)) {
            SqliteRaidRecoveryRepository repo = new SqliteRaidRecoveryRepository(db);
            DefaultRaidEncounterManager mgr = newTestManager(
                    deferredRegionScheduler,
                    tracker,
                    repo,
                    Logger.getLogger("InFlightRollbackTest"));

            Player alice = addPlayer("Alice", new Location(endWorld, 0.0, 64.0, 0.0));
            mgr.adminStart(alice, BossConfig.Difficulty.NORMAL);
            Runnable countdown = capturedGlobalTimers.getLast();
            for (int i = 0; i < configManager.pluginConfig().encounter().pullCountdownSeconds(); i++) {
                countdown.run();
            }
            assertEquals(RaidState.ACTIVE, mgr.state());

            tracker.tryRecordPlayerPlacement(new Location(endWorld, 8, 65, 8), alice.getUniqueId(), origData, 64, 512);
            // Admin reset kicks off async region rollback -> stays ENDING & blocks new pull while pending
            mgr.adminReset(alice);
            assertEquals(1, pendingRegionRollbacks.size());
            assertEquals(RaidState.ENDING, mgr.state());
            assertFalse(mgr.canModifyArenaRegion());

            mgr.adminStart(alice, BossConfig.Difficulty.HARD);
            assertEquals(RaidState.ENDING, mgr.state());

            // Shutdown happens BEFORE the region task executes -> block must be persisted to SQLite
            mgr.shutdown();
            List<PendingBlockRollback> persisted = repo.loadPendingBlockRollbacksSync();
            assertEquals(1, persisted.size());
            assertEquals(8, persisted.getFirst().x());
        }
    }

    @Test
    void deferredBlockRollback_blocksStartWhilePendingThenTransitionsToIdleOnCompletion() {
        List<Runnable> pendingRegionRollbacks = new ArrayList<>();
        RaidScheduler deferredRegionScheduler = mock(RaidScheduler.class);
        lenient().when(deferredRegionScheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(deferredRegionScheduler.runAtEntity(any(), any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(deferredRegionScheduler.runAtEntityLater(any(), any(), anyLong())).thenReturn(TaskHandle.retired());
        lenient().when(deferredRegionScheduler.runGlobalTimer(any(), anyLong(), anyLong())).thenAnswer(inv -> {
            capturedGlobalTimers.add(inv.getArgument(0));
            return mock(TaskHandle.class);
        });
        lenient().when(deferredRegionScheduler.runGlobalLater(any(), anyLong())).thenAnswer(inv -> {
            capturedDelayedTasks.add(inv.getArgument(0));
            return mock(TaskHandle.class);
        });
        lenient().when(deferredRegionScheduler.runAtRegion(any(), anyInt(), anyInt(), any())).thenAnswer(inv -> {
            pendingRegionRollbacks.add(inv.getArgument(3));
            return TaskHandle.retired();
        });

        BlockData origData = mock(BlockData.class);
        when(origData.clone()).thenReturn(origData);
        TemporaryBlockTracker tracker = new TemporaryBlockTracker(d -> "minecraft:air", s -> origData);
        org.bukkit.block.Block worldBlock = mock(org.bukkit.block.Block.class);
        when(endWorld.getBlockAt(9, 65, 9)).thenReturn(worldBlock);

        DefaultRaidEncounterManager mgr = newTestManager(
                deferredRegionScheduler,
                tracker,
                null,
                Logger.getLogger("DeferredRollbackTest"));

        Player alice = addPlayer("Alice", new Location(endWorld, 0.0, 64.0, 0.0));
        mgr.adminStart(alice, BossConfig.Difficulty.NORMAL);
        Runnable countdown = capturedGlobalTimers.getLast();
        for (int i = 0; i < configManager.pluginConfig().encounter().pullCountdownSeconds(); i++) {
            countdown.run();
        }
        assertEquals(RaidState.ACTIVE, mgr.state());

        tracker.tryRecordPlayerPlacement(new Location(endWorld, 9, 65, 9), alice.getUniqueId(), origData, 64, 512);
        mgr.adminReset(alice);
        assertEquals(RaidState.ENDING, mgr.state());

        // Executing the deferred region rollback clears pending blocks and transitions ENDING -> IDLE
        pendingRegionRollbacks.removeFirst().run();
        assertEquals(RaidState.IDLE, mgr.state());
        assertTrue(mgr.canModifyArenaRegion());

        mgr.adminStart(alice, BossConfig.Difficulty.NORMAL);
        assertEquals(RaidState.COUNTDOWN, mgr.state());
    }

    @Test
    void staleCountdownAfterReset_doesNotCancelOrHijackNewCountdown() {
        Player alice = addPlayer("Alice", new Location(endWorld, 0.0, 64.0, 0.0));
        manager.adminStart(alice, BossConfig.Difficulty.HARD);
        Runnable oldHardCountdown = capturedGlobalTimers.getLast();
        int secs = configManager.pluginConfig().encounter().pullCountdownSeconds();
        for (int i = 0; i < secs - 1; i++) {
            oldHardCountdown.run();
        }
        assertEquals(RaidState.COUNTDOWN, manager.state());

        // Reset and start a fresh NORMAL countdown
        manager.adminReset(alice);
        assertEquals(RaidState.IDLE, manager.state());
        manager.adminStart(alice, BossConfig.Difficulty.NORMAL);
        Runnable newNormalCountdown = capturedGlobalTimers.getLast();
        assertEquals(RaidState.COUNTDOWN, manager.state());

        // Stale final tick of old HARD countdown must NOT lockInAndActivate NORMAL countdown with HARD
        oldHardCountdown.run();
        assertEquals(RaidState.COUNTDOWN, manager.state());

        // Completing the NORMAL countdown activates with NORMAL difficulty
        for (int i = 0; i < secs; i++) {
            newNormalCountdown.run();
        }
        assertEquals(RaidState.ACTIVE, manager.state());
        assertEquals(BossConfig.Difficulty.NORMAL, manager.activeScaling().orElseThrow().difficulty());
    }

    @Test
    void lastSurvivorDownedIntoWiping_stillReceivesDownedFlagsMovementPinAndDamageGuard() {
        List<Runnable> queuedEntityTasks = new ArrayList<>();
        RaidScheduler queuedScheduler = mock(RaidScheduler.class);
        lenient().when(queuedScheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            queuedEntityTasks.add(inv.getArgument(1));
            return TaskHandle.retired();
        });
        lenient().when(queuedScheduler.runAtEntity(any(), any(), any())).thenAnswer(inv -> {
            queuedEntityTasks.add(inv.getArgument(1));
            return TaskHandle.retired();
        });
        lenient().when(queuedScheduler.runAtEntityLater(any(), any(), anyLong())).thenReturn(TaskHandle.retired());
        lenient().when(queuedScheduler.runGlobalTimer(any(), anyLong(), anyLong())).thenAnswer(inv -> {
            capturedGlobalTimers.add(inv.getArgument(0));
            return mock(TaskHandle.class);
        });
        lenient().when(queuedScheduler.runGlobalLater(any(), anyLong())).thenAnswer(inv -> {
            capturedDelayedTasks.add(inv.getArgument(0));
            return mock(TaskHandle.class);
        });

        DefaultRaidEncounterManager mgr = newTestManager(
                queuedScheduler,
                blockTracker,
                null,
                Logger.getLogger("LastDownWipingTest"));

        Player solo = addPlayer("Solo", new Location(endWorld, 0.0, 64.0, 0.0));
        mgr.adminStart(solo, BossConfig.Difficulty.NORMAL);
        Runnable countdown = capturedGlobalTimers.getLast();
        for (int i = 0; i < configManager.pluginConfig().encounter().pullCountdownSeconds(); i++) {
            countdown.run();
        }
        while (!queuedEntityTasks.isEmpty()) {
            queuedEntityTasks.removeFirst().run();
        }
        assertEquals(RaidState.ACTIVE, mgr.state());

        // Lethal hit downs the only survivor -> immediate transition to WIPING BEFORE runAtEntity flushes
        mgr.onPlayerDamage(solo, mockDamageEvent(50.0, EntityDamageEvent.DamageCause.ENTITY_ATTACK));
        assertEquals(RaidState.WIPING, mgr.state());

        // Flushing queued entity task during WIPING must STILL apply invulnerable/invisible/collidable=false
        while (!queuedEntityTasks.isEmpty()) {
            queuedEntityTasks.removeFirst().run();
        }
        verify(solo).setInvulnerable(true);
        verify(solo).setInvisible(true);
        verify(solo).setCollidable(false);

        // During WIPING, further damage is still cancelled and X/Y/Z movement remains pinned
        EntityDamageEvent wipeDamage = mockDamageEvent(10.0, EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        mgr.onPlayerDamage(solo, wipeDamage);
        verify(wipeDamage).setCancelled(true);

        Location movedTo = new Location(endWorld, 5.0, 64.0, 5.0, 90.0f, 15.0f);
        org.bukkit.event.player.PlayerMoveEvent moveEvent = new org.bukkit.event.player.PlayerMoveEvent(
                solo, new Location(endWorld, 0.0, 64.0, 0.0), movedTo);
        mgr.onPlayerMove(moveEvent);
        assertEquals(0.0, moveEvent.getTo().getX(), 1e-6);
        assertEquals(90.0f, moveEvent.getTo().getYaw(), 1e-4);
    }

    @Test
    void startEncounterSupportsInstanceWipeStandbyRetryLeaveAndPerPlayerReturnLocations() {
        World overworld = mock(World.class);
        lenient().when(overworld.getUID()).thenReturn(UUID.randomUUID());
        lenient().when(overworld.getName()).thenReturn("world");
        World instWorld = mock(World.class);
        lenient().when(instWorld.getUID()).thenReturn(UUID.randomUUID());
        lenient().when(instWorld.getName()).thenReturn("dragonraid_instance_1");
        lenient().when(regionService.isRaidWorld(instWorld)).thenReturn(true);
        lenient().when(regionService.safeZoneSpawnLocation(instWorld))
                .thenReturn(new Location(instWorld, 0.5, 65.0, 24.5));

        net.sabafly.dragonraid.instance.RaidInstanceManager instanceMgr =
                mock(net.sabafly.dragonraid.instance.RaidInstanceManager.class);
        net.sabafly.dragonraid.instance.RaidInstanceManager.RaidInstance inst =
                new net.sabafly.dragonraid.instance.RaidInstanceManager.RaidInstance(
                        instWorld,
                        new Location(instWorld, 0.0, 64.0, 0.0),
                        40.0,
                        List.of());
        AtomicBoolean instActive = new AtomicBoolean(false);
        lenient().when(instanceMgr.getOrCreateInstance()).thenAnswer(inv -> {
            instActive.set(true);
            return CompletableFuture.completedFuture(inst);
        });
        lenient().when(instanceMgr.activeInstance()).thenAnswer(inv -> instActive.get() ? inst : null);
        lenient().when(instanceMgr.activeCombatWorld(any())).thenAnswer(inv -> instActive.get() ? instWorld : inv.getArgument(0));
        lenient().when(instanceMgr.isInstanceWorld(instWorld)).thenReturn(true);
        lenient().when(instanceMgr.destroyInstance(any(), any())).thenAnswer(inv -> {
            instActive.set(false);
            return CompletableFuture.completedFuture(null);
        });

        DragonBossController bossCtrl = mock(DragonBossController.class);
        EnderDragon fakeDragon = mock(EnderDragon.class);
        lenient().when(fakeDragon.getWorld()).thenReturn(instWorld);
        lenient().when(fakeDragon.getUniqueId()).thenReturn(UUID.randomUUID());
        lenient().when(bossCtrl.bindOrSpawn(any(), any(), anyDouble())).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(bossCtrl.bindOrSpawn(any(), anyDouble())).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(bossCtrl.activeDragon()).thenReturn(fakeDragon);
        lenient().when(bossCtrl.cleanup(anyBoolean())).thenReturn(CompletableFuture.completedFuture(null));

        DefaultRaidEncounterManager instManager = new DefaultRaidEncounterManager(
                configManager,
                messageService,
                scheduler,
                regionService,
                blockTracker,
                soulMarkerService,
                null,
                null,
                bossCtrl,
                null,
                blueprintRegistry,
                gimmickActionRegistry,
                instanceMgr,
                Logger.getLogger("InstanceFlowTest"),
                () -> endWorld,
                () -> onlinePlayers.values(),
                id -> onlinePlayers.get(id),
                nowMillis::get);

        Location returnA = new Location(overworld, 100.0, 70.0, -50.0);
        Location returnB = new Location(overworld, -200.0, 82.0, 310.0);
        Player alice = addPlayer("Alice", returnA);
        Player bob = addPlayer("Bob", returnB);
        when(alice.getWorld()).thenReturn(overworld);
        when(bob.getWorld()).thenReturn(overworld);
        EncounterBlueprint bp = sampleBlueprint("dragon_normal", EncounterDifficulty.NORMAL);

        assertTrue(instManager.startEncounter(
                bp,
                List.of(alice, bob),
                Map.of(alice.getUniqueId(), returnA, bob.getUniqueId(), returnB)));
        assertEquals(RaidState.ACTIVE, instManager.state());
        assertTrue(instManager.hasActiveDutySession());
        assertTrue(instManager.isInDutyOrInstance(alice.getUniqueId()));
        verify(alice).teleportAsync(new Location(instWorld, 0.5, 65.0, 24.5));
        verify(bob).teleportAsync(new Location(instWorld, 0.5, 65.0, 24.5));

        // Simulate wipe -> enters standby inside the same temporary instance
        when(alice.getWorld()).thenReturn(instWorld);
        when(bob.getWorld()).thenReturn(instWorld);
        instManager.onPlayerDamage(alice, mockDamageEvent(50.0, EntityDamageEvent.DamageCause.ENTITY_ATTACK));
        instManager.onPlayerDamage(bob, mockDamageEvent(50.0, EntityDamageEvent.DamageCause.ENTITY_ATTACK));
        assertEquals(RaidState.WIPING, instManager.state());
        capturedDelayedTasks.getLast().run();

        assertEquals(RaidState.IDLE, instManager.state());
        assertTrue(instActive.get(), "Instance must stay alive during wipe standby");
        assertTrue(instManager.hasActiveDutySession(), "Duty slot must remain busy during standby");
        assertTrue(instManager.isInWipeStandby(alice.getUniqueId()));
        assertTrue(instManager.isInDutyOrInstance(alice.getUniqueId()));
        verify(instanceMgr, never()).destroyInstance(any(), any());

        // Retry works while in standby; downing Bob and withdrawing removes Bob's soul marker cleanly
        assertTrue(instManager.retryEncounter(alice));
        assertEquals(RaidState.ACTIVE, instManager.state());
        assertFalse(instManager.isInWipeStandby(alice.getUniqueId()));
        instManager.onPlayerDamage(bob, mockDamageEvent(50.0, EntityDamageEvent.DamageCause.ENTITY_ATTACK));
        assertTrue(instManager.isDowned(bob.getUniqueId()));
        org.mockito.Mockito.clearInvocations(soulMarkerService);

        // Bob withdraws mid-fight via /cf leave -> removes soul marker & returns to Bob's overworld location; Alice keeps fighting
        assertTrue(instManager.leaveEncounter(bob));
        verify(soulMarkerService).removeMarker(eq(bob.getUniqueId()), any());
        verify(bob).teleportAsync(returnB);
        assertFalse(instManager.isInDutyOrInstance(bob.getUniqueId()));
        assertTrue(instManager.isParticipant(alice.getUniqueId()));
        assertTrue(instActive.get());

        // Alice (last participant) withdraws via /cf leave -> returns to Alice's overworld location & destroys instance
        assertTrue(instManager.leaveEncounter(alice));
        verify(alice).teleportAsync(returnA);
        assertFalse(instManager.hasActiveDutySession());
        verify(instanceMgr).destroyInstance(eq(inst), any());

        // Start again and test Victory returning each player to their distinct pre-entry locations (plus world fallback)
        World nether = mock(World.class);
        lenient().when(nether.getUID()).thenReturn(UUID.randomUUID());
        lenient().when(nether.getName()).thenReturn("world_nether");
        Location netherReturn = new Location(nether, 12.0, 64.0, -30.0);
        when(alice.getWorld()).thenReturn(overworld);
        when(bob.getWorld()).thenReturn(nether);
        assertTrue(instManager.startEncounter(
                bp,
                List.of(alice, bob),
                Map.of(alice.getUniqueId(), returnA, bob.getUniqueId(), netherReturn)));
        when(alice.getWorld()).thenReturn(instWorld);
        when(bob.getWorld()).thenReturn(instWorld);

        org.mockito.Mockito.clearInvocations(alice, bob, instanceMgr);
        instManager.onDragonDefeated(fakeDragon);
        assertFalse(capturedDelayedTasks.isEmpty());
        capturedDelayedTasks.getLast().run();
        verify(alice).teleportAsync(returnA);
        verify(bob).teleportAsync(netherReturn);
        verify(instanceMgr).destroyInstance(eq(inst), any());
    }

    @Test
    void bossSpawnFailureDestroysOwnedInstanceAndStandbyBlocksAdminStart() {
        World overworld = mock(World.class);
        lenient().when(overworld.getUID()).thenReturn(UUID.randomUUID());
        lenient().when(overworld.getName()).thenReturn("world");
        World instWorld = mock(World.class);
        lenient().when(instWorld.getUID()).thenReturn(UUID.randomUUID());
        lenient().when(instWorld.getName()).thenReturn("dragonraid_instance_abort");
        lenient().when(regionService.isRaidWorld(instWorld)).thenReturn(true);
        lenient().when(regionService.safeZoneSpawnLocation(instWorld))
                .thenReturn(new Location(instWorld, 0.5, 65.0, 24.5));

        net.sabafly.dragonraid.instance.RaidInstanceManager instanceMgr =
                mock(net.sabafly.dragonraid.instance.RaidInstanceManager.class);
        net.sabafly.dragonraid.instance.RaidInstanceManager.RaidInstance inst =
                new net.sabafly.dragonraid.instance.RaidInstanceManager.RaidInstance(
                        instWorld, new Location(instWorld, 0.0, 64.0, 0.0), 40.0, List.of());
        AtomicBoolean instActive = new AtomicBoolean(false);
        lenient().when(instanceMgr.getOrCreateInstance()).thenAnswer(inv -> {
            instActive.set(true);
            return CompletableFuture.completedFuture(inst);
        });
        lenient().when(instanceMgr.activeInstance()).thenAnswer(inv -> instActive.get() ? inst : null);
        lenient().when(instanceMgr.activeCombatWorld(any())).thenAnswer(inv -> instActive.get() ? instWorld : inv.getArgument(0));
        lenient().when(instanceMgr.isInstanceWorld(instWorld)).thenReturn(true);
        lenient().when(instanceMgr.destroyInstance(any(), any())).thenAnswer(inv -> {
            instActive.set(false);
            return CompletableFuture.completedFuture(null);
        });

        DragonBossController bossCtrl = mock(DragonBossController.class);
        lenient().when(bossCtrl.bindOrSpawn(any(), any(), anyDouble()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("spawn failure")));
        lenient().when(bossCtrl.bindOrSpawn(any(), anyDouble()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("spawn failure")));
        lenient().when(bossCtrl.cleanup(anyBoolean())).thenReturn(CompletableFuture.completedFuture(null));

        DefaultRaidEncounterManager instManager = new DefaultRaidEncounterManager(
                configManager, messageService, scheduler, regionService, blockTracker, soulMarkerService,
                null, null, bossCtrl, null, blueprintRegistry, gimmickActionRegistry, instanceMgr,
                Logger.getLogger("SpawnFailureTest"), () -> endWorld, () -> onlinePlayers.values(),
                id -> onlinePlayers.get(id), nowMillis::get);

        Location returnA = new Location(overworld, 12.0, 64.0, 34.0);
        Player alice = addPlayer("Alice", returnA);
        when(alice.getWorld()).thenReturn(overworld);
        EncounterBlueprint bp = sampleBlueprint("dragon_normal", EncounterDifficulty.NORMAL);
        blueprintRegistry.register(bp);

        // First attempt fails boss spawn -> owned instance is destroyed and slot becomes free again
        assertTrue(instManager.startEncounter(bp, List.of(alice), Map.of(alice.getUniqueId(), returnA)));
        verify(instanceMgr).destroyInstance(eq(inst), any());
        assertFalse(instActive.get());
        assertEquals(RaidState.IDLE, instManager.state());

        // Subsequent valid encounter succeeds without ENCOUNTER_BUSY wedging
        lenient().when(bossCtrl.bindOrSpawn(any(), any(), anyDouble()))
                .thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(bossCtrl.bindOrSpawn(any(), anyDouble()))
                .thenReturn(CompletableFuture.completedFuture(null));
        assertTrue(instManager.startEncounter(bp, List.of(alice), Map.of(alice.getUniqueId(), returnA)));
        assertTrue(instActive.get());

        // Wipe enters standby -> adminStart must reject instead of clobbering standbySession
        Location instSafe = new Location(instWorld, 0.5, 65.0, 24.5);
        lenient().when(regionService.isInArena(instSafe)).thenReturn(true);
        lenient().when(regionService.isInSafeZone(instSafe)).thenReturn(true);
        when(alice.getWorld()).thenReturn(instWorld);
        when(alice.getLocation()).thenReturn(instSafe);
        instManager.onPlayerDamage(alice, mockDamageEvent(50.0, EntityDamageEvent.DamageCause.ENTITY_ATTACK));
        capturedDelayedTasks.getLast().run();
        assertTrue(instManager.isInWipeStandby(alice.getUniqueId()));
        net.kyori.adventure.text.Component expectedRunningMsg = messageService.render(alice, "command.already-running");
        net.kyori.adventure.text.Component noPlayersMsg = messageService.render(alice, "command.start-no-players");
        org.mockito.Mockito.clearInvocations(alice);
        instManager.adminStart(alice, BossConfig.Difficulty.NORMAL);
        verify(alice).sendMessage(expectedRunningMsg);
        verify(alice, never()).sendMessage(noPlayersMsg);
        assertEquals(RaidState.IDLE, instManager.state());
        assertTrue(instManager.isInWipeStandby(alice.getUniqueId()));
    }

    @Test
    void endingLeaveStatusRetiredCleanupPreservationRollbackOrderingAndConcurrentWithdrawalsAreHandled() {
        World overworld = mock(World.class);
        lenient().when(overworld.getUID()).thenReturn(UUID.randomUUID());
        lenient().when(overworld.getName()).thenReturn("world");
        World instWorld = mock(World.class);
        lenient().when(instWorld.getUID()).thenReturn(UUID.randomUUID());
        lenient().when(instWorld.getName()).thenReturn("dragonraid_instance_sync");
        lenient().when(regionService.isRaidWorld(instWorld)).thenReturn(true);
        lenient().when(regionService.safeZoneSpawnLocation(instWorld))
                .thenReturn(new Location(instWorld, 0.5, 65.0, 24.5));

        net.sabafly.dragonraid.instance.RaidInstanceManager instanceMgr =
                mock(net.sabafly.dragonraid.instance.RaidInstanceManager.class);
        net.sabafly.dragonraid.instance.RaidInstanceManager.RaidInstance inst =
                new net.sabafly.dragonraid.instance.RaidInstanceManager.RaidInstance(
                        instWorld, new Location(instWorld, 0.0, 64.0, 0.0), 40.0, List.of());
        AtomicBoolean instActive = new AtomicBoolean(false);
        lenient().when(instanceMgr.getOrCreateInstance()).thenAnswer(inv -> {
            instActive.set(true);
            return CompletableFuture.completedFuture(inst);
        });
        lenient().when(instanceMgr.activeInstance()).thenAnswer(inv -> instActive.get() ? inst : null);
        lenient().when(instanceMgr.activeCombatWorld(any())).thenAnswer(inv -> instActive.get() ? instWorld : inv.getArgument(0));
        lenient().when(instanceMgr.isInstanceWorld(instWorld)).thenReturn(true);
        lenient().when(instanceMgr.destroyInstance(any(), any())).thenAnswer(inv -> {
            instActive.set(false);
            return CompletableFuture.completedFuture(null);
        });

        RaidRecoveryRepository recRepo = mock(RaidRecoveryRepository.class);
        DragonBossController bossCtrl = mock(DragonBossController.class);
        lenient().when(bossCtrl.bindOrSpawn(any(), any(), anyDouble())).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(bossCtrl.bindOrSpawn(any(), anyDouble())).thenReturn(CompletableFuture.completedFuture(null));
        AtomicReference<Runnable> onCleanupHook = new AtomicReference<>();
        lenient().when(bossCtrl.cleanup(anyBoolean())).thenAnswer(inv -> {
            Runnable hook = onCleanupHook.getAndSet(null);
            if (hook != null) {
                hook.run();
            }
            return CompletableFuture.completedFuture(null);
        });

        AtomicBoolean retireWithdrawalTask = new AtomicBoolean(false);
        RaidScheduler customScheduler = mock(RaidScheduler.class);
        lenient().when(customScheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(customScheduler.runAtEntity(any(), any(), any())).thenAnswer(inv -> {
            if (retireWithdrawalTask.get()) {
                inv.<Runnable>getArgument(2).run();
            } else {
                inv.<Runnable>getArgument(1).run();
            }
            return TaskHandle.retired();
        });
        lenient().when(customScheduler.runAtRegion(any(World.class), anyInt(), anyInt(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(3).run();
            return TaskHandle.retired();
        });
        lenient().when(customScheduler.runGlobal(any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(0).run();
            return TaskHandle.retired();
        });
        lenient().when(customScheduler.runGlobalTimer(any(), anyLong(), anyLong())).thenAnswer(inv -> {
            capturedGlobalTimers.add(inv.getArgument(0));
            return mock(TaskHandle.class);
        });
        lenient().when(customScheduler.runGlobalLater(any(), anyLong())).thenAnswer(inv -> {
            capturedDelayedTasks.add(inv.getArgument(0));
            return mock(TaskHandle.class);
        });

        DefaultRaidEncounterManager mgr = new DefaultRaidEncounterManager(
                configManager, messageService, customScheduler, regionService, blockTracker, soulMarkerService,
                recRepo, null, bossCtrl, null, blueprintRegistry, gimmickActionRegistry, instanceMgr,
                Logger.getLogger("LifecycleEdgeTest"), () -> endWorld, () -> onlinePlayers.values(),
                id -> onlinePlayers.get(id), nowMillis::get);

        Location retA = new Location(overworld, 1.0, 64.0, 2.0);
        Location retB = new Location(overworld, 3.0, 64.0, 4.0);
        Player alice = addPlayer("Alice", retA);
        Player bob = addPlayer("Bob", retB);
        when(alice.getWorld()).thenReturn(overworld);
        when(bob.getWorld()).thenReturn(overworld);
        EncounterBlueprint bp = sampleBlueprint("dragon_normal", EncounterDifficulty.NORMAL);

        // #4 & #8: Alice withdraws first with an incomplete teleport Future; Bob's withdrawal task retires -> preserves cleanup & waits for Alice
        assertTrue(mgr.startEncounter(bp, List.of(alice, bob), Map.of(alice.getUniqueId(), retA, bob.getUniqueId(), retB)));
        CompletableFuture<Boolean> aliceTpFuture = new CompletableFuture<>();
        when(alice.teleportAsync(retA)).thenReturn(aliceTpFuture);
        assertEquals(RaidEncounterManager.LeaveEncounterResult.LEFT, mgr.leaveEncounterDetailed(alice));

        retireWithdrawalTask.set(true);
        org.mockito.Mockito.clearInvocations(recRepo, instanceMgr);
        assertEquals(RaidEncounterManager.LeaveEncounterResult.LEFT, mgr.leaveEncounterDetailed(bob));
        verify(recRepo).upsertPendingPlayerCleanupAsync(org.mockito.ArgumentMatchers.argThat(
                (PendingPlayerCleanup p) -> p.playerId().equals(bob.getUniqueId())));
        verify(recRepo, never()).deletePendingPlayerCleanupAsync(bob.getUniqueId());
        // Alice's teleport still pending -> instance must NOT be destroyed yet (#8)
        verify(instanceMgr, never()).destroyInstance(any(), any());
        aliceTpFuture.complete(true);
        verify(instanceMgr).destroyInstance(eq(inst), any());

        // #3 & #7: Start 2-player encounter, take Bob offline before wipe, check ENDING status inside finishWipe hook & verify offline Bob cleanup is preserved (#7)
        retireWithdrawalTask.set(false);
        when(alice.teleportAsync(any())).thenReturn(CompletableFuture.completedFuture(true));
        when(alice.getWorld()).thenReturn(overworld);
        when(bob.getWorld()).thenReturn(overworld);
        when(bob.isOnline()).thenReturn(true);
        assertTrue(mgr.startEncounter(bp, List.of(alice, bob), Map.of(alice.getUniqueId(), retA, bob.getUniqueId(), retB)));
        when(alice.getWorld()).thenReturn(instWorld);
        when(bob.getWorld()).thenReturn(instWorld);
        when(bob.isOnline()).thenReturn(false);
        mgr.onPlayerQuit(bob);
        org.mockito.Mockito.clearInvocations(recRepo, instanceMgr);

        AtomicReference<RaidEncounterManager.LeaveEncounterResult> duringEndingResult = new AtomicReference<>();
        onCleanupHook.set(() -> duringEndingResult.set(mgr.leaveEncounterDetailed(alice)));
        mgr.onPlayerDamage(alice, mockDamageEvent(50.0, EntityDamageEvent.DamageCause.ENTITY_ATTACK));
        capturedDelayedTasks.getLast().run();

        assertEquals(RaidEncounterManager.LeaveEncounterResult.ENDING_IN_PROGRESS, duringEndingResult.get());
        verify(recRepo, never()).deletePendingPlayerCleanupAsync(bob.getUniqueId());
        verify(recRepo).upsertPendingPlayerCleanupAsync(org.mockito.ArgumentMatchers.argThat(
                (PendingPlayerCleanup p) -> p.playerId().equals(bob.getUniqueId())));
        // #9 single volatile read safe check
        RaidParticipant rp = new RaidParticipant(alice.getUniqueId(), "Alice");
        rp.setPreEntryReturnLocation(retA);
        assertEquals(retA, rp.preEntryReturnLocation());
        rp.setPreEntryReturnLocation(null);
        assertEquals(null, rp.preEntryReturnLocation());
    }

    @Test
    void soloWipeFinalDisconnectBeforeStandbyPublicationDestroysInstanceWithoutPublishingStandby() {
        World overworld = mock(World.class);
        lenient().when(overworld.getUID()).thenReturn(UUID.randomUUID());
        lenient().when(overworld.getName()).thenReturn("world");
        World instWorld = mock(World.class);
        lenient().when(instWorld.getUID()).thenReturn(UUID.randomUUID());
        lenient().when(instWorld.getName()).thenReturn("dragonraid_instance_quit_race");
        lenient().when(regionService.isRaidWorld(instWorld)).thenReturn(true);
        lenient().when(regionService.safeZoneSpawnLocation(instWorld))
                .thenReturn(new Location(instWorld, 0.5, 65.0, 24.5));

        net.sabafly.dragonraid.instance.RaidInstanceManager instanceMgr =
                mock(net.sabafly.dragonraid.instance.RaidInstanceManager.class);
        net.sabafly.dragonraid.instance.RaidInstanceManager.RaidInstance inst =
                new net.sabafly.dragonraid.instance.RaidInstanceManager.RaidInstance(
                        instWorld, new Location(instWorld, 0.0, 64.0, 0.0), 40.0, List.of());
        AtomicBoolean instActive = new AtomicBoolean(false);
        lenient().when(instanceMgr.getOrCreateInstance()).thenAnswer(inv -> {
            instActive.set(true);
            return CompletableFuture.completedFuture(inst);
        });
        lenient().when(instanceMgr.activeInstance()).thenAnswer(inv -> instActive.get() ? inst : null);
        lenient().when(instanceMgr.activeCombatWorld(any())).thenAnswer(inv -> instActive.get() ? instWorld : inv.getArgument(0));
        lenient().when(instanceMgr.isInstanceWorld(instWorld)).thenReturn(true);
        lenient().when(instanceMgr.destroyInstance(any(), any())).thenAnswer(inv -> {
            instActive.set(false);
            return CompletableFuture.completedFuture(null);
        });

        DragonBossController bossCtrl = mock(DragonBossController.class);
        lenient().when(bossCtrl.bindOrSpawn(any(), any(), anyDouble())).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(bossCtrl.bindOrSpawn(any(), anyDouble())).thenReturn(CompletableFuture.completedFuture(null));
        AtomicReference<Runnable> onCleanupHook = new AtomicReference<>();
        lenient().when(bossCtrl.cleanup(anyBoolean())).thenAnswer(inv -> {
            Runnable hook = onCleanupHook.getAndSet(null);
            if (hook != null) {
                hook.run();
            }
            return CompletableFuture.completedFuture(null);
        });

        DefaultRaidEncounterManager mgr = new DefaultRaidEncounterManager(
                configManager, messageService, scheduler, regionService, blockTracker, soulMarkerService,
                null, null, bossCtrl, null, blueprintRegistry, gimmickActionRegistry, instanceMgr,
                Logger.getLogger("QuitWipeRaceTest"), () -> endWorld, () -> onlinePlayers.values(),
                id -> onlinePlayers.get(id), nowMillis::get);

        Location retA = new Location(overworld, 15.0, 64.0, 25.0);
        Player alice = addPlayer("Alice", retA);
        when(alice.getWorld()).thenReturn(overworld);
        EncounterBlueprint bp = sampleBlueprint("dragon_normal", EncounterDifficulty.NORMAL);

        assertTrue(mgr.startEncounter(bp, List.of(alice), Map.of(alice.getUniqueId(), retA)));
        when(alice.getWorld()).thenReturn(instWorld);

        // Simulate Paper PlayerQuitEvent during finishWipe before standbySession is published (while isOnline is still true!)
        onCleanupHook.set(() -> mgr.onPlayerQuit(alice));
        mgr.onPlayerDamage(alice, mockDamageEvent(50.0, EntityDamageEvent.DamageCause.ENTITY_ATTACK));
        assertEquals(RaidState.WIPING, mgr.state());
        capturedDelayedTasks.getLast().run();

        assertFalse(mgr.isInWipeStandby(alice.getUniqueId()));
        assertFalse(mgr.hasActiveDutySession());
        assertFalse(instActive.get());
        verify(instanceMgr).destroyInstance(eq(inst), any());
    }

    @Test
    void failedWithdrawalTeleportPreservesFlagsSeparationRetryAndSqliteRestartRecovery() {
        World overworld = mock(World.class);
        UUID overworldId = UUID.randomUUID();
        lenient().when(overworld.getUID()).thenReturn(overworldId);
        lenient().when(overworld.getName()).thenReturn("world");
        World instWorld = mock(World.class);
        lenient().when(instWorld.getUID()).thenReturn(UUID.randomUUID());
        lenient().when(instWorld.getName()).thenReturn("dragonraid_instance_tp_retry");
        lenient().when(regionService.isRaidWorld(instWorld)).thenReturn(true);
        lenient().when(regionService.safeZoneSpawnLocation(instWorld))
                .thenReturn(new Location(instWorld, 0.5, 65.0, 24.5));

        net.sabafly.dragonraid.instance.RaidInstanceManager instanceMgr =
                mock(net.sabafly.dragonraid.instance.RaidInstanceManager.class);
        net.sabafly.dragonraid.instance.RaidInstanceManager.RaidInstance inst =
                new net.sabafly.dragonraid.instance.RaidInstanceManager.RaidInstance(
                        instWorld, new Location(instWorld, 0.0, 64.0, 0.0), 40.0, List.of());
        AtomicBoolean instActive = new AtomicBoolean(false);
        lenient().when(instanceMgr.getOrCreateInstance()).thenAnswer(inv -> {
            instActive.set(true);
            return CompletableFuture.completedFuture(inst);
        });
        lenient().when(instanceMgr.activeInstance()).thenAnswer(inv -> instActive.get() ? inst : null);
        lenient().when(instanceMgr.activeCombatWorld(any())).thenAnswer(inv -> instActive.get() ? instWorld : inv.getArgument(0));
        lenient().when(instanceMgr.isInstanceWorld(instWorld)).thenReturn(true);
        lenient().when(instanceMgr.destroyInstance(any(), any())).thenAnswer(inv -> {
            instActive.set(false);
            return CompletableFuture.completedFuture(null);
        });

        DragonBossController bossCtrl = mock(DragonBossController.class);
        lenient().when(bossCtrl.bindOrSpawn(any(), any(), anyDouble())).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(bossCtrl.bindOrSpawn(any(), anyDouble())).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(bossCtrl.cleanup(anyBoolean())).thenReturn(CompletableFuture.completedFuture(null));

        Path dbPath = tempDir.resolve("withdrawal-retry-recovery.db");
        Location retA = new Location(overworld, 112.5, 71.0, -34.25, 45.0f, 10.0f);
        Location retB = new Location(overworld, -18.0, 64.0, 42.0);
        UUID aliceId;

        try (SqliteDatabase db1 = new SqliteDatabase(dbPath, 2, Runnable::run)) {
            SqliteRaidRecoveryRepository repo1 = new SqliteRaidRecoveryRepository(db1);
            DefaultRaidEncounterManager mgr = new DefaultRaidEncounterManager(
                    configManager, messageService, scheduler, regionService, blockTracker, soulMarkerService,
                    repo1, null, bossCtrl, null, blueprintRegistry, gimmickActionRegistry, instanceMgr,
                    Logger.getLogger("TpFailRetryTest"), () -> endWorld, () -> onlinePlayers.values(),
                    id -> onlinePlayers.get(id), nowMillis::get);

            Player alice = addPlayer("Alice", retA);
            Player bob = addPlayer("Bob", retB);
            aliceId = alice.getUniqueId();
            when(alice.getWorld()).thenReturn(overworld);
            when(bob.getWorld()).thenReturn(overworld);
            EncounterBlueprint bp = sampleBlueprint("dragon_normal", EncounterDifficulty.NORMAL);

            assertTrue(mgr.startEncounter(bp, List.of(alice, bob),
                    Map.of(aliceId, retA, bob.getUniqueId(), retB)));
            when(alice.getWorld()).thenReturn(instWorld);
            when(bob.getWorld()).thenReturn(instWorld);

            // Down Alice so her state flags are modified and captured
            mgr.onPlayerDamage(alice, mockDamageEvent(50.0, EntityDamageEvent.DamageCause.ENTITY_ATTACK));
            assertTrue(mgr.isDowned(aliceId));

            // Alice's withdrawal teleport returns false -> flags restored, return record preserved, /cf leave retry works
            net.kyori.adventure.text.Component tpFailedMsg = messageService.render(alice, "finder.leave-teleport-failed");
            org.mockito.Mockito.clearInvocations(alice);
            when(alice.teleportAsync(retA)).thenReturn(CompletableFuture.completedFuture(false));
            assertEquals(RaidEncounterManager.LeaveEncounterResult.LEFT, mgr.leaveEncounterDetailed(alice));

            verify(alice, atLeastOnce()).setInvisible(false);
            verify(alice, atLeastOnce()).setCollidable(true);
            verify(alice, atLeastOnce()).setInvulnerable(false);
            verify(alice).sendMessage(tpFailedMsg);
            assertTrue(mgr.isInDutyOrInstance(aliceId), "Stuck player inside instance remains in duty lock");
            assertFalse(mgr.isParticipant(aliceId));

            PendingPlayerCleanup persistedRetry = repo1.loadPendingPlayerCleanupsSync().get(aliceId);
            assertTrue(persistedRetry != null && !persistedRetry.restoreFlags() && persistedRetry.hasReturnLocation());
            assertEquals(overworldId, persistedRetry.returnWorldId());
            assertEquals(112.5, persistedRetry.returnX());

            // Retry /cf leave with succeeding teleport -> does NOT re-apply flags, removes record, clears duty lock
            org.mockito.Mockito.clearInvocations(alice);
            when(alice.teleportAsync(retA)).thenReturn(CompletableFuture.completedFuture(true));
            assertEquals(RaidEncounterManager.LeaveEncounterResult.LEFT, mgr.leaveEncounterDetailed(alice));
            verify(alice).teleportAsync(retA);
            verify(alice, never()).setGameMode(any());
            verify(alice, never()).setInvisible(anyBoolean());
            verify(alice, never()).setCollidable(anyBoolean());
            verify(alice, never()).setInvulnerable(anyBoolean());
            when(alice.getWorld()).thenReturn(overworld);
            assertFalse(mgr.isInDutyOrInstance(aliceId));
            assertFalse(repo1.loadPendingPlayerCleanupsSync().containsKey(aliceId));

            // Now fail Bob's teleport exceptionally and simulate server restart recovery
            when(bob.teleportAsync(retB)).thenReturn(
                    CompletableFuture.failedFuture(new IllegalStateException("tp failed")));
            assertEquals(RaidEncounterManager.LeaveEncounterResult.LEFT, mgr.leaveEncounterDetailed(bob));
            PendingPlayerCleanup bobPersisted = repo1.loadPendingPlayerCleanupsSync().get(bob.getUniqueId());
            assertTrue(bobPersisted != null && !bobPersisted.restoreFlags() && bobPersisted.hasReturnLocation());
            assertEquals(overworldId, bobPersisted.returnWorldId());
            mgr.shutdown();
        }

        // Reopen DB on simulated server restart and verify Bob returns to exact retB on join without touching flags
        try (SqliteDatabase db2 = new SqliteDatabase(dbPath, 2, Runnable::run)) {
            SqliteRaidRecoveryRepository repo2 = new SqliteRaidRecoveryRepository(db2);
            DefaultRaidEncounterManager recoveredMgr = new DefaultRaidEncounterManager(
                    configManager, messageService, scheduler, regionService, blockTracker, soulMarkerService,
                    repo2, null, bossCtrl, null, blueprintRegistry, gimmickActionRegistry, instanceMgr,
                    Logger.getLogger("TpFailRestartTest"), () -> endWorld, () -> onlinePlayers.values(),
                    id -> onlinePlayers.get(id), nowMillis::get);
            recoveredMgr.setWorldLookup(id -> overworldId.equals(id) ? overworld : endWorld.getUID().equals(id) ? endWorld : null);

            Player bobRejoined = mock(Player.class);
            UUID bobId = repo2.loadPendingPlayerCleanupsSync().keySet().iterator().next();
            when(bobRejoined.getUniqueId()).thenReturn(bobId);
            when(bobRejoined.getName()).thenReturn("Bob");
            when(bobRejoined.isOnline()).thenReturn(true);
            when(bobRejoined.getWorld()).thenReturn(endWorld);
            when(bobRejoined.getLocation()).thenReturn(new Location(endWorld, 0.0, 64.0, 0.0));
            when(bobRejoined.teleportAsync(retB)).thenReturn(CompletableFuture.completedFuture(true));

            recoveredMgr.onPlayerJoin(bobRejoined);
            verify(bobRejoined).teleportAsync(retB);
            verify(bobRejoined, never()).setGameMode(any());
            verify(bobRejoined, never()).setInvisible(anyBoolean());
            assertTrue(repo2.loadPendingPlayerCleanupsSync().isEmpty());
        }
    }

    @Test
    void concurrentInstanceWithdrawalsNeverDropInFlightFuturesBeforeInstanceDestroy() throws Exception {
        World overworld = mock(World.class);
        lenient().when(overworld.getUID()).thenReturn(UUID.randomUUID());
        lenient().when(overworld.getName()).thenReturn("world");
        World instWorld = mock(World.class);
        lenient().when(instWorld.getUID()).thenReturn(UUID.randomUUID());
        lenient().when(instWorld.getName()).thenReturn("dragonraid_instance_concurrent");
        lenient().when(regionService.isRaidWorld(instWorld)).thenReturn(true);
        lenient().when(regionService.safeZoneSpawnLocation(instWorld))
                .thenReturn(new Location(instWorld, 0.5, 65.0, 24.5));

        net.sabafly.dragonraid.instance.RaidInstanceManager instanceMgr =
                mock(net.sabafly.dragonraid.instance.RaidInstanceManager.class);
        net.sabafly.dragonraid.instance.RaidInstanceManager.RaidInstance inst =
                new net.sabafly.dragonraid.instance.RaidInstanceManager.RaidInstance(
                        instWorld, new Location(instWorld, 0.0, 64.0, 0.0), 40.0, List.of());
        AtomicBoolean instActive = new AtomicBoolean(false);
        lenient().when(instanceMgr.getOrCreateInstance()).thenAnswer(inv -> {
            instActive.set(true);
            return CompletableFuture.completedFuture(inst);
        });
        lenient().when(instanceMgr.activeInstance()).thenAnswer(inv -> instActive.get() ? inst : null);
        lenient().when(instanceMgr.activeCombatWorld(any())).thenAnswer(inv -> instActive.get() ? instWorld : inv.getArgument(0));
        lenient().when(instanceMgr.isInstanceWorld(instWorld)).thenReturn(true);
        lenient().when(instanceMgr.destroyInstance(any(), any())).thenAnswer(inv -> {
            instActive.set(false);
            return CompletableFuture.completedFuture(null);
        });

        DragonBossController bossCtrl = mock(DragonBossController.class);
        lenient().when(bossCtrl.bindOrSpawn(any(), any(), anyDouble())).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(bossCtrl.bindOrSpawn(any(), anyDouble())).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(bossCtrl.cleanup(anyBoolean())).thenReturn(CompletableFuture.completedFuture(null));

        DefaultRaidEncounterManager mgr = new DefaultRaidEncounterManager(
                configManager, messageService, scheduler, regionService, blockTracker, soulMarkerService,
                null, null, bossCtrl, null, blueprintRegistry, gimmickActionRegistry, instanceMgr,
                Logger.getLogger("ConcurrentWithdrawalTest"), () -> endWorld, () -> onlinePlayers.values(),
                id -> onlinePlayers.get(id), nowMillis::get);

        EncounterBlueprint bp = sampleBlueprint("dragon_normal", EncounterDifficulty.NORMAL);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int iter = 0; iter < 15; iter++) {
                onlinePlayers.clear();
                org.mockito.Mockito.clearInvocations(instanceMgr);

                Location retA = new Location(overworld, 1.0, 64.0, 1.0);
                Location retB = new Location(overworld, 2.0, 64.0, 2.0);
                Location retC = new Location(overworld, 3.0, 64.0, 3.0);
                Player alice = addPlayer("Alice" + iter, retA);
                Player bob = addPlayer("Bob" + iter, retB);
                Player charlie = addPlayer("Charlie" + iter, retC);
                when(alice.getWorld()).thenReturn(overworld);
                when(bob.getWorld()).thenReturn(overworld);
                when(charlie.getWorld()).thenReturn(overworld);

                assertTrue(mgr.startEncounter(bp, List.of(alice, bob, charlie),
                        Map.of(alice.getUniqueId(), retA, bob.getUniqueId(), retB, charlie.getUniqueId(), retC)));

                CompletableFuture<Boolean> tpA = new CompletableFuture<>();
                CompletableFuture<Boolean> tpB = new CompletableFuture<>();
                when(alice.teleportAsync(retA)).thenReturn(tpA);
                when(bob.teleportAsync(retB)).thenReturn(tpB);
                when(charlie.teleportAsync(retC)).thenReturn(CompletableFuture.completedFuture(true));

                // Alice initiates withdrawal first
                assertEquals(RaidEncounterManager.LeaveEncounterResult.LEFT, mgr.leaveEncounterDetailed(alice));

                // Race Alice's completion against Bob's registration on separate threads
                java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
                CompletableFuture<Void> fA = CompletableFuture.runAsync(() -> {
                    try {
                        barrier.await(2, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    tpA.complete(true);
                }, pool);
                CompletableFuture<Void> fB = CompletableFuture.runAsync(() -> {
                    try {
                        barrier.await(2, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    mgr.leaveEncounterDetailed(bob);
                }, pool);
                CompletableFuture.allOf(fA, fB).join();

                // Charlie (last participant) leaves and completes immediately BEFORE Bob's tpB finishes
                when(alice.getWorld()).thenReturn(overworld);
                when(bob.getWorld()).thenReturn(overworld);
                when(charlie.getWorld()).thenReturn(overworld);
                assertEquals(RaidEncounterManager.LeaveEncounterResult.LEFT, mgr.leaveEncounterDetailed(charlie));

                // Bob's teleport is still incomplete -> instance MUST NOT be destroyed yet!
                verify(instanceMgr, never()).destroyInstance(any(), any());
                tpB.complete(true);
                verify(instanceMgr).destroyInstance(eq(inst), any());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void startActiveEncounter(Player initiator, BossConfig.Difficulty difficulty) {
        manager.adminStart(initiator, difficulty);
        Runnable countdown = capturedGlobalTimers.get(capturedGlobalTimers.size() - 1);
        int secs = configManager.pluginConfig().encounter().pullCountdownSeconds();
        for (int i = 0; i < secs; i++) {
            countdown.run();
        }
        assertEquals(RaidState.ACTIVE, manager.state());
    }

    private Player addPlayer(String name, Location loc) {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        lenient().when(p.getUniqueId()).thenReturn(id);
        lenient().when(p.getName()).thenReturn(name);
        lenient().when(p.isOnline()).thenReturn(true);
        lenient().when(p.getWorld()).thenReturn(endWorld);
        lenient().when(p.getLocation()).thenReturn(loc);
        AtomicReference<GameMode> mode = new AtomicReference<>(GameMode.SURVIVAL);
        lenient().when(p.getGameMode()).thenAnswer(inv -> mode.get());
        lenient().doAnswer(inv -> {
            mode.set(inv.getArgument(0));
            return null;
        }).when(p).setGameMode(any());
        lenient().when(p.getHealth()).thenReturn(20.0);
        lenient().when(p.getMaxHealth()).thenReturn(20.0);
        lenient().when(p.isCollidable()).thenReturn(true);
        lenient().when(p.isInvisible()).thenReturn(false);
        lenient().when(p.isInvulnerable()).thenReturn(false);
        lenient().when(p.locale()).thenReturn(Locale.US);
        lenient().when(p.teleportAsync(any())).thenReturn(CompletableFuture.completedFuture(true));
        onlinePlayers.put(id, p);
        return p;
    }

    private static EntityDamageEvent mockDamageEvent(double finalDamage, EntityDamageEvent.DamageCause cause) {
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        lenient().when(event.getFinalDamage()).thenReturn(finalDamage);
        lenient().when(event.getCause()).thenReturn(cause);
        return event;
    }
}
