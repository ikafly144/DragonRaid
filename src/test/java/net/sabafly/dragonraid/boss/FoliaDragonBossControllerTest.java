package net.sabafly.dragonraid.boss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.model.ActorModelController;
import net.sabafly.dragonraid.api.model.ActorModelService;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EnderDragon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FoliaDragonBossControllerTest {

    @TempDir
    Path tempDir;

    private ConfigManager configManager;
    private RaidScheduler scheduler;
    private World endWorld;
    private List<Runnable> entityTimers;

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager(
                tempDir,
                name -> FoliaDragonBossControllerTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();

        endWorld = mock(World.class);
        lenient().when(endWorld.getName()).thenReturn("world_the_end");

        entityTimers = new ArrayList<>();
        scheduler = mock(RaidScheduler.class);
        lenient().when(scheduler.runAtRegion(any(Location.class), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(scheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(scheduler.runAtEntity(any(), any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(scheduler.runAtEntityTimer(any(), any(), any(), anyLong(), anyLong())).thenAnswer(inv -> {
            entityTimers.add(inv.getArgument(1));
            return mock(TaskHandle.class);
        });
    }

    private EnderDragon mockDragon(Location initialLoc, double initialHp) {
        EnderDragon dragon = mock(EnderDragon.class);
        UUID dragonId = UUID.randomUUID();
        final Location[] locRef = {initialLoc.clone()};
        final double[] hpRef = {initialHp};
        final boolean[] aiRef = {true};
        final EnderDragon.Phase[] phaseRef = {EnderDragon.Phase.CIRCLING};

        lenient().when(dragon.getUniqueId()).thenReturn(dragonId);
        lenient().when(dragon.isDead()).thenReturn(false);
        lenient().when(dragon.getWorld()).thenReturn(endWorld);
        lenient().when(dragon.getLocation()).thenAnswer(inv -> locRef[0].clone());
        lenient().when(dragon.hasAI()).thenAnswer(inv -> aiRef[0]);
        lenient().when(dragon.hasGravity()).thenReturn(true);
        lenient().doAnswer(inv -> {
            aiRef[0] = inv.getArgument(0);
            return null;
        }).when(dragon).setAI(any(Boolean.class));
        lenient().when(dragon.getPhase()).thenAnswer(inv -> phaseRef[0]);
        lenient().doAnswer(inv -> {
            phaseRef[0] = inv.getArgument(0);
            return null;
        }).when(dragon).setPhase(any(EnderDragon.Phase.class));
        lenient().when(dragon.getHealth()).thenAnswer(inv -> hpRef[0]);
        lenient().doAnswer(inv -> {
            hpRef[0] = inv.getArgument(0);
            return null;
        }).when(dragon).setHealth(anyDouble());
        lenient().doAnswer(inv -> {
            double dmg = inv.getArgument(0);
            hpRef[0] = Math.max(0.0, hpRef[0] - dmg);
            return null;
        }).when(dragon).damage(anyDouble());
        lenient().when(dragon.teleportAsync(any(Location.class))).thenAnswer(inv -> {
            locRef[0] = inv.<Location>getArgument(0).clone();
            return CompletableFuture.completedFuture(true);
        });
        return dragon;
    }

    @Test
    void bindOrSpawn_suppressesVanillaAiScalesHpAndDrivesPostures() {
        EnderDragon existing = mockDragon(new Location(endWorld, 20.0, 80.0, 0.0), 200.0);
        FoliaDragonBossController controller = new FoliaDragonBossController(
                configManager,
                scheduler,
                Logger.getLogger("BossControllerTest"),
                w -> existing,
                (w, loc) -> {
                    throw new AssertionError("Should not spawn when existing dragon is present");
                }
        );

        controller.bindOrSpawn(endWorld, 1980.0);

        assertFalse(controller.wasSummonedByPlugin());
        assertEquals(1980.0, controller.maxHp(), 1e-6);
        assertEquals(1980.0, controller.currentHp(), 1e-6);
        assertTrue(existing.hasAI());
        assertEquals(EnderDragon.Phase.CIRCLING, existing.getPhase());
        assertEquals(1, entityTimers.size());

        // Drive CENTER_LANDED movement
        controller.setPosture(DragonPosture.CENTER_LANDED);
        for (int i = 0; i < 10; i++) {
            entityTimers.getFirst().run();
        }
        Location landed = controller.bossLocation();
        assertNotNull(landed);
        assertEquals(0.0, landed.getX(), 1e-3);
        assertEquals(66.0, landed.getY(), 1e-3);
        assertEquals(0.0, landed.getZ(), 1e-3);

        // Drive HIGH_HOVER movement
        controller.setPosture(DragonPosture.HIGH_HOVER);
        for (int i = 0; i < 10; i++) {
            entityTimers.getFirst().run();
        }
        assertEquals(82.0, controller.bossLocation().getY(), 1e-3);

        // Drive beginDive: ASCENDING_CHARGE -> DIVING
        Location divePoint = new Location(endWorld, 12.0, 64.0, -8.0);
        controller.beginDive(divePoint, 1.0);
        assertEquals(DragonPosture.ASCENDING_CHARGE, controller.posture());
        controller.tick(1.0);
        assertEquals(DragonPosture.DIVING, controller.posture());
        controller.setPosture(DragonPosture.HIGH_HOVER);

        // Stagger overrides posture temporarily and restores when elapsed
        controller.stagger(2.0);
        assertTrue(controller.isStaggered());
        assertEquals(DragonPosture.STAGGERED, controller.posture());
        controller.tick(1.0);
        assertTrue(controller.isStaggered());
        controller.tick(1.0);
        assertFalse(controller.isStaggered());
        assertEquals(DragonPosture.HIGH_HOVER, controller.posture());

        // Damage and heal clamp cleanly
        controller.damageBoss(500.0);
        assertEquals(1480.0, controller.currentHp(), 1e-6);
        controller.healBoss(200.0);
        assertEquals(1680.0, controller.currentHp(), 1e-6);
        controller.healBoss(1000.0);
        assertEquals(1980.0, controller.currentHp(), 1e-6);

        // Cleanup restores pre-existing dragon instead of removing it
        controller.cleanup(true);
        verify(existing, never()).remove();
        assertTrue(existing.hasAI());
        assertNull(controller.activeDragon());
    }

    @Test
    void bindOrSpawn_spawnsNewDragonAndRemovesOnCleanupWhenSummoned() {
        EnderDragon spawned = mockDragon(new Location(endWorld, 0.0, 74.0, 0.0), 200.0);
        FoliaDragonBossController controller = new FoliaDragonBossController(
                configManager,
                scheduler,
                Logger.getLogger("BossControllerTest"),
                w -> null,
                (w, loc) -> spawned
        );

        controller.bindOrSpawn(endWorld, 1200.0);
        assertTrue(controller.wasSummonedByPlugin());
        assertNotNull(controller.activeDragon());

        controller.cleanup(true);
        verify(spawned, atLeastOnce()).remove();
        assertNull(controller.activeDragon());
    }

    @Test
    void prepareForVanillaDefeat_releasesAiAndMovementTask() {
        EnderDragon existing = mockDragon(new Location(endWorld, 0.0, 74.0, 0.0), 200.0);
        FoliaDragonBossController controller = new FoliaDragonBossController(
                configManager,
                scheduler,
                Logger.getLogger("BossControllerTest"),
                w -> existing,
                (w, loc) -> existing
        );

        controller.bindOrSpawn(endWorld, 1200.0);
        assertTrue(existing.hasAI());

        controller.prepareForVanillaDefeat();
        assertTrue(existing.hasAI());
    }

    @Test
    void holdFirstClearIdleFollowedByBindOrSpawnPreservesOriginalFlags() {
        EnderDragon existing = mockDragon(new Location(endWorld, 0.0, 74.0, 0.0), 200.0);
        existing.setAI(false);
        FoliaDragonBossController controller = new FoliaDragonBossController(
                configManager,
                scheduler,
                Logger.getLogger("BossControllerTest"),
                w -> existing,
                (w, loc) -> existing
        );

        controller.holdFirstClearIdle(endWorld);
        assertTrue(existing.hasAI());

        controller.bindOrSpawn(endWorld, 1200.0).join();
        assertTrue(existing.hasAI());

        controller.cleanup(true).join();
        assertFalse(existing.hasAI());
    }

    @Test
    void bindOrSpawn_propagatesSpawnException() {
        FoliaDragonBossController controller = new FoliaDragonBossController(
                configManager,
                scheduler,
                Logger.getLogger("BossControllerTest"),
                w -> null,
                (w, loc) -> {
                    throw new IllegalStateException("spawn failed");
                }
        );

        CompletableFuture<Void> future = controller.bindOrSpawn(endWorld, 1200.0);
        assertTrue(future.isCompletedExceptionally());
        assertThrows(CompletionException.class, future::join);
    }

    @Test
    void pendingDeletionBlocksStaleReuseDuringDeferredCleanup() {
        List<Runnable> deferredEntityTasks = new ArrayList<>();
        RaidScheduler deferredScheduler = mock(RaidScheduler.class);
        lenient().when(deferredScheduler.runAtRegion(any(Location.class), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(deferredScheduler.runAtEntity(any(), any(), any())).thenAnswer(inv -> {
            deferredEntityTasks.add(inv.getArgument(1));
            return mock(TaskHandle.class);
        });
        lenient().when(deferredScheduler.runAtEntityTimer(any(), any(), any(), anyLong(), anyLong())).thenAnswer(inv -> {
            return mock(TaskHandle.class);
        });

        EnderDragon firstSummoned = mockDragon(new Location(endWorld, 0.0, 74.0, 0.0), 200.0);
        EnderDragon secondSummoned = mockDragon(new Location(endWorld, 0.0, 74.0, 0.0), 200.0);
        final EnderDragon[] finderResult = {null};
        final int[] spawnCalls = {0};

        FoliaDragonBossController controller = new FoliaDragonBossController(
                configManager,
                deferredScheduler,
                Logger.getLogger("BossControllerTest"),
                w -> finderResult[0],
                (w, loc) -> (++spawnCalls[0] == 1) ? firstSummoned : secondSummoned
        );

        controller.bindOrSpawn(endWorld, 1200.0);
        deferredEntityTasks.forEach(Runnable::run);
        deferredEntityTasks.clear();

        // Pretend firstSummoned is still returned by world finder until its queued remove runs
        finderResult[0] = firstSummoned;
        controller.cleanup(true);
        assertEquals(1, deferredEntityTasks.size());

        // Next pull must spawn secondSummoned rather than re-binding firstSummoned pending deletion
        controller.bindOrSpawn(endWorld, 1200.0);
        assertEquals(2, spawnCalls[0]);

        // Run all queued entity tasks -> firstSummoned removed, secondSummoned NOT removed
        deferredEntityTasks.forEach(Runnable::run);
        verify(firstSummoned).remove();
        verify(secondSummoned, never()).remove();
    }

    @Test
    void customArenaCenterIsUsedExplicitlyWithoutGuessingFromWorldName() {
        // World named "raid_instance_end" should still use config center when no explicit center is passed
        World oddlyNamedWorld = mock(World.class);
        lenient().when(oddlyNamedWorld.getName()).thenReturn("raid_instance_end");
        EnderDragon dragon = mockDragon(new Location(oddlyNamedWorld, 0.0, 74.0, 0.0), 1200.0);

        FoliaDragonBossController controller = new FoliaDragonBossController(
                configManager,
                scheduler,
                Logger.getLogger("BossControllerTest"),
                w -> dragon,
                (w, loc) -> dragon
        );

        // Explicit custom center (e.g. from RaidInstance) drives movement to that center
        Location customCenter = new Location(oddlyNamedWorld, 10.0, 90.0, -15.0);
        controller.bindOrSpawn(oddlyNamedWorld, customCenter, 1200.0).join();
        controller.setPosture(DragonPosture.CENTER_LANDED);
        for (int i = 0; i < 20; i++) {
            entityTimers.getFirst().run();
        }
        Location loc = controller.bossLocation();
        assertNotNull(loc);
        assertEquals(10.0, loc.getX(), 1e-3);
        assertEquals(92.0, loc.getY(), 1e-3);
        assertEquals(-15.0, loc.getZ(), 1e-3);
    }

    @Test
    void postureTransitionsAndCleanupSyncAndCloseBoundModelController() {
        EnderDragon dragon = mockDragon(new Location(endWorld, 0.0, 74.0, 0.0), 1200.0);
        ActorModelService modelService = mock(ActorModelService.class);
        ActorModelController idleModelController = mock(ActorModelController.class);
        ActorModelController modelController = mock(ActorModelController.class);
        lenient().when(modelService.bindEntity(eq(dragon), any()))
                .thenReturn(idleModelController, modelController);

        FoliaDragonBossController controller = new FoliaDragonBossController(
                configManager,
                scheduler,
                Logger.getLogger("ModelSyncBossTest"),
                w -> dragon,
                (w, loc) -> dragon,
                modelService
        );

        controller.holdFirstClearIdle(endWorld);
        assertEquals(idleModelController, controller.activeModelController());
        verify(idleModelController).setPosture(DragonPosture.LOW_ORBIT);

        controller.bindOrSpawn(endWorld, 1200.0).join();
        verify(idleModelController).close();
        verify(modelService, org.mockito.Mockito.times(2)).bindEntity(eq(dragon), any());
        assertEquals(modelController, controller.activeModelController());
        verify(modelController).setPosture(DragonPosture.LOW_ORBIT);

        controller.setPosture(DragonPosture.HIGH_HOVER);
        verify(modelController, atLeastOnce()).setPosture(DragonPosture.HIGH_HOVER);

        controller.beginDive(new Location(endWorld, 12.0, 64.0, 0.0), 0.15);
        verify(modelController).setPosture(DragonPosture.ASCENDING_CHARGE);

        controller.tick(0.20);
        verify(modelController).setPosture(DragonPosture.DIVING);

        controller.setPosture(DragonPosture.LOW_ORBIT);
        controller.stagger(0.5);
        verify(modelController).setPosture(DragonPosture.STAGGERED);

        // Stagger expiry syncs model back out of STAGGERED during tick
        controller.tick(0.60);
        verify(modelController, org.mockito.Mockito.times(3)).setPosture(DragonPosture.LOW_ORBIT);

        controller.cleanup(false).join();
        assertNull(controller.activeModelController());
        verify(modelController).close();
    }

    @Test
    void vanillaEnderDragonApplies180DegreeYawOffsetWhileKeepingLogicalBossYawForward() {
        EnderDragon dragon = mockDragon(new Location(endWorld, 20.0, 72.0, 0.0), 1200.0);
        FoliaDragonBossController controller = new FoliaDragonBossController(
                configManager,
                scheduler,
                Logger.getLogger("YawFacingTest"),
                w -> dragon,
                (w, loc) -> dragon
        );

        // 1. HIGH_HOVER transit moving from x=20 toward center (0,76,0) => -X => logical yaw +90, entity yaw -90
        controller.bindOrSpawn(endWorld, 1200.0).join();
        controller.setPosture(DragonPosture.HIGH_HOVER);
        entityTimers.getFirst().run();
        Location logicalHover = controller.bossLocation();
        assertNotNull(logicalHover);
        assertYawNear(90.0f, logicalHover.getYaw(), 1.0f);
        assertYawNear(-90.0f, dragon.getLocation().getYaw(), 1.0f);

        // 2. SWEEPING_LASER / HIGH_HOVER fixed hover aim => logical yaw matches targetYaw, entity yaw is targetYaw + 180
        controller.setPosture(DragonPosture.HIGH_HOVER);
        controller.setHoverAim(45.0f, 0.0f);
        entityTimers.getFirst().run();
        assertYawNear(45.0f, controller.bossLocation().getYaw(), 1.0f);
        assertYawNear(-135.0f, dragon.getLocation().getYaw(), 1.0f);

        // 3. DIVING from current x~17.5 toward (+35,64,0) => +X => logical yaw -90, entity yaw +90
        controller.setPosture(DragonPosture.LOW_ORBIT);
        controller.beginDive(new Location(endWorld, 35.0, 64.0, 0.0), 0.05);
        controller.tick(0.10);
        entityTimers.getFirst().run();
        assertYawNear(-90.0f, controller.bossLocation().getYaw(), 1.0f);
        assertYawNear(90.0f, dragon.getLocation().getYaw(), 1.0f);

        // 4. LOW_ORBIT circular movement rotates entity yaw by +180 relative to logical forward velocity yaw
        controller.setPosture(DragonPosture.LOW_ORBIT);
        entityTimers.getFirst().run();
        Location orbitLogical = controller.bossLocation();
        assertYawNear(orbitLogical.getYaw() + 180.0f, dragon.getLocation().getYaw(), 1.0f);
        org.mockito.ArgumentCaptor<Float> yawCaptor = org.mockito.ArgumentCaptor.forClass(Float.class);
        verify(dragon, atLeastOnce()).setRotation(yawCaptor.capture(), org.mockito.ArgumentMatchers.anyFloat());
        assertYawNear(orbitLogical.getYaw() + 180.0f, yawCaptor.getValue(), 1.0f);
    }

    @Test
    void betterModelRendererDoesNotApplyVanilla180DegreeYawInversion() {
        EnderDragon dragon = mockDragon(new Location(endWorld, 0.0, 72.0, 0.0), 1200.0);
        ActorModelService modelService = mock(ActorModelService.class);
        ActorModelController bmController = mock(ActorModelController.class);
        lenient().when(bmController.rendererType()).thenReturn(net.sabafly.dragonraid.api.enemy.ModelRendererType.BETTER_MODEL);
        lenient().when(modelService.bindEntity(eq(dragon), any())).thenReturn(bmController);

        FoliaDragonBossController controller = new FoliaDragonBossController(
                configManager,
                scheduler,
                Logger.getLogger("BetterModelYawTest"),
                w -> dragon,
                (w, loc) -> dragon,
                modelService
        );

        controller.bindOrSpawn(endWorld, 1200.0).join();
        controller.setPosture(DragonPosture.HIGH_HOVER);
        controller.setHoverAim(45.0f, 0.0f);
        entityTimers.getFirst().run();

        assertYawNear(45.0f, controller.bossLocation().getYaw(), 1.0f);
        assertYawNear(45.0f, dragon.getLocation().getYaw(), 1.0f);
    }

    private static void assertYawNear(float expectedDeg, float actualDeg, float toleranceDeg) {
        double diff = ((actualDeg - expectedDeg) % 360.0 + 540.0) % 360.0 - 180.0;
        assertTrue(Math.abs(diff) <= toleranceDeg,
                "Expected yaw near " + expectedDeg + " but was " + actualDeg);
    }
}
