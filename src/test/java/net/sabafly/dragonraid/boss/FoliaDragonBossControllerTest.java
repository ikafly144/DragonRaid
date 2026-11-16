package net.sabafly.dragonraid.boss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
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
        lenient().when(scheduler.runAtEntityTimer(any(), any(), any(), anyLong(), anyLong())).thenAnswer(inv -> {
            entityTimers.add(inv.getArgument(1));
            return mock(TaskHandle.class);
        });
    }

    private EnderDragon mockDragon(Location initialLoc, double initialHp) {
        EnderDragon dragon = mock(EnderDragon.class);
        final Location[] locRef = {initialLoc.clone()};
        final double[] hpRef = {initialHp};
        final boolean[] aiRef = {true};
        final EnderDragon.Phase[] phaseRef = {EnderDragon.Phase.CIRCLING};

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
        assertFalse(existing.hasAI());
        assertEquals(EnderDragon.Phase.HOVER, existing.getPhase());
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
        assertFalse(existing.hasAI());

        controller.prepareForVanillaDefeat();
        assertTrue(existing.hasAI());
    }
}
