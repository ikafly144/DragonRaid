package net.sabafly.dragonraid.gimmick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import net.sabafly.dragonraid.visual.SpatialTelegraphService;
import net.sabafly.dragonraid.visual.TelegraphStyle;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RaidCrystalManagerTest {

    @TempDir
    Path tempDir;

    private ConfigManager configManager;
    private RaidScheduler scheduler;
    private SpatialTelegraphService telegraphService;
    private World endWorld;
    private RaidCrystalManager crystalManager;

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager(
                tempDir,
                name -> RaidCrystalManagerTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();

        endWorld = mock(World.class);
        lenient().when(endWorld.getMinHeight()).thenReturn(0);
        lenient().when(endWorld.getMaxHeight()).thenReturn(256);
        lenient().when(endWorld.getPlayers()).thenReturn(List.of());

        scheduler = mock(RaidScheduler.class);
        lenient().when(scheduler.runAtRegion(any(Location.class), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(scheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });

        telegraphService = mock(SpatialTelegraphService.class);

        crystalManager = new RaidCrystalManager(
                configManager,
                scheduler,
                telegraphService,
                Logger.getLogger("CrystalTest"),
                (world, loc) -> {
                    EnderCrystal crystal = mock(EnderCrystal.class);
                    UUID id = UUID.randomUUID();
                    lenient().when(crystal.getUniqueId()).thenReturn(id);
                    lenient().when(crystal.getWorld()).thenReturn(world);
                    lenient().when(crystal.isDead()).thenReturn(false);
                    return crystal;
                }
        );
    }

    @Test
    void spawnsTracksVirtualHpDrawsTethersAndClears() {
        crystalManager.spawnWave(endWorld, 4, 80.0, 4.0);
        assertEquals(4, crystalManager.activeCount());

        // Subsequent wave does not exceed targetCount
        crystalManager.spawnWave(endWorld, 4, 80.0, 4.0);
        assertEquals(4, crystalManager.activeCount());

        Location bossLoc = new Location(endWorld, 0.0, 80.0, 0.0);
        Player viewer = mock(Player.class);
        double regen = crystalManager.tick(0.5, bossLoc, List.of(viewer));
        assertEquals(4 * 4.0 * 0.5, regen, 1e-6);
        verify(telegraphService, atLeastOnce()).renderTetherLine(any(), eq(bossLoc), eq(TelegraphStyle.TETHER), any());

        // Spawn a single targeted crystal test to verify partial and lethal damage
        crystalManager.clearAll();
        assertEquals(0, crystalManager.activeCount());

        EnderCrystal[] captured = new EnderCrystal[1];
        RaidCrystalManager singleMgr = new RaidCrystalManager(
                configManager,
                scheduler,
                telegraphService,
                Logger.getLogger("SingleCrystalTest"),
                (world, loc) -> {
                    EnderCrystal crystal = mock(EnderCrystal.class);
                    lenient().when(crystal.getUniqueId()).thenReturn(UUID.randomUUID());
                    lenient().when(crystal.getWorld()).thenReturn(world);
                    lenient().when(crystal.isDead()).thenReturn(false);
                    captured[0] = crystal;
                    return crystal;
                }
        );

        singleMgr.spawnWave(endWorld, 1, 80.0, 4.0);
        EnderCrystal crystal = captured[0];
        assertTrue(singleMgr.isTrackedCrystal(crystal));

        // Partial hit does not remove crystal
        assertTrue(singleMgr.onCrystalDamage(crystal, 30.0, viewer));
        assertEquals(1, singleMgr.activeCount());

        // Lethal hit removes crystal cleanly
        assertTrue(singleMgr.onCrystalDamage(crystal, 55.0, viewer));
        verify(crystal).remove();
        assertEquals(0, singleMgr.activeCount());
        assertFalse(singleMgr.isTrackedCrystal(crystal));
        assertEquals(0.0, singleMgr.tick(1.0, bossLoc, List.of(viewer)), 1e-6);
    }
}
