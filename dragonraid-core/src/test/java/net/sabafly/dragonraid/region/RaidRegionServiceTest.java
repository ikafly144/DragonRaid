package net.sabafly.dragonraid.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.RegionConfig;
import net.sabafly.dragonraid.config.RegionConfig.ArenaRegion;
import net.sabafly.dragonraid.config.RegionConfig.Point3D;
import net.sabafly.dragonraid.config.RegionConfig.Shape;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RaidRegionServiceTest {

    @TempDir
    Path tempDir;

    private ConfigManager configManager;
    private RaidScheduler scheduler;
    private DefaultRaidRegionService regionService;
    private World endWorld;
    private World overworld;

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager(
                tempDir,
                name -> RaidRegionServiceTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();

        scheduler = mock(RaidScheduler.class);
        lenient().when(scheduler.runAtRegion(any(Location.class), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });

        regionService = new DefaultRaidRegionService(configManager, scheduler);
        endWorld = mock(World.class);
        lenient().when(endWorld.getName()).thenReturn("world_the_end");
        lenient().when(endWorld.getMinHeight()).thenReturn(0);
        lenient().when(endWorld.getMaxHeight()).thenReturn(256);

        overworld = mock(World.class);
        lenient().when(overworld.getName()).thenReturn("world");
    }

    @Test
    void boundaryChecks_distinguishArenaSafeZoneOutsideAndWorlds() {
        assertTrue(regionService.isRaidWorld(endWorld));
        assertFalse(regionService.isRaidWorld(overworld));

        Location arenaCenter = new Location(endWorld, 0.0, 64.0, 0.0);
        assertTrue(regionService.isInArena(arenaCenter));
        assertFalse(regionService.isInSafeZone(arenaCenter));

        Location safeZonePoint = new Location(endWorld, 100.5, 50.0, 0.5);
        assertTrue(regionService.isInSafeZone(safeZonePoint));
        assertTrue(regionService.isInArena(safeZonePoint)); // Default cylinder radius 120 overlaps obsidian platform

        // Distant Enderman farm outside arena radius (e.g. x=350, z=0) must NOT be protected/touched
        Location endermanFarm = new Location(endWorld, 350.0, 64.0, 0.0);
        assertFalse(regionService.isInArena(endermanFarm));
        assertFalse(regionService.isInArenaHorizontal(endermanFarm));
        assertFalse(regionService.isInSafeZone(endermanFarm));

        // Wrong world at (0,64,0) must return false
        Location overworldCenter = new Location(overworld, 0.0, 64.0, 0.0);
        assertFalse(regionService.isInArena(overworldCenter));
        assertFalse(regionService.isBelowVoidThreshold(new Location(overworld, 0.0, -5.0, 0.0)));
    }

    @Test
    void voidThreshold_triggersBelowThresholdInsideArenaXZOnly() {
        // Below void-y-threshold (0.0) inside arena XZ footprint -> true even though y < arena.minY
        assertTrue(regionService.isBelowVoidThreshold(new Location(endWorld, 50.0, -0.5, 20.0)));
        assertFalse(regionService.isBelowVoidThreshold(new Location(endWorld, 50.0, 0.0, 20.0)));
        assertFalse(regionService.isBelowVoidThreshold(new Location(endWorld, 50.0, 40.0, 20.0)));

        // Below threshold OUTSIDE arena XZ footprint (e.g. distant outer island bridge) -> false
        assertFalse(regionService.isBelowVoidThreshold(new Location(endWorld, 250.0, -10.0, 0.0)));
    }

    @Test
    void findNearestSafeArenaEdgeAsync_locatesSolidGroundAlongInwardRay() {
        Block air = mockBlock(Material.AIR, false, true, false);
        Block endStone = mockBlock(Material.END_STONE, true, false, false);
        when(endWorld.getBlockAt(any(int.class), any(int.class), any(int.class))).thenReturn(air);
        // Place solid platform at (0, 63, 0)
        when(endWorld.getBlockAt(0, 63, 0)).thenReturn(endStone);

        Location fallLoc = new Location(endWorld, 150.0, -2.0, 0.0, 45.0f, 10.0f);
        Location rescued = regionService.findNearestSafeArenaEdgeAsync(fallLoc, 2.0).join();

        assertEquals(0.5, rescued.getX(), 1e-6);
        assertEquals(66.0, rescued.getY(), 1e-6); // 63 + 1 + 2.0 offset
        assertEquals(0.5, rescued.getZ(), 1e-6);
        assertEquals(45.0f, rescued.getYaw());
    }

    @Test
    void findNearestSafeArenaEdgeAsync_fallsBackToSafeZoneWhenNoSolidGroundFound() {
        Block air = mockBlock(Material.AIR, false, true, false);
        when(endWorld.getBlockAt(any(int.class), any(int.class), any(int.class))).thenReturn(air);

        Location fallLoc = new Location(endWorld, 100.0, -5.0, 0.0);
        Location rescued = regionService.findNearestSafeArenaEdgeAsync(fallLoc, 2.0).join();

        assertEquals(100.5, rescued.getX(), 1e-6);
        assertEquals(50.0, rescued.getY(), 1e-6);
        assertEquals(0.5, rescued.getZ(), 1e-6);
    }

    @Test
    void clampToArenaAnchor_clampsFinalYIncludingOffsetInsideArenaBounds() {
        RegionConfig current = configManager.pluginConfig().regions();
        // Cylinder with maxY = 100 and center.y = 99
        ArenaRegion cylinder = new ArenaRegion(
                Shape.CYLINDER,
                new Point3D(0.0, 99.0, 0.0),
                120.0,
                current.arena().min(),
                current.arena().max(),
                0,
                100);
        configManager.updateRegions(new RegionConfig(cylinder, current.safeZone(), 0.0));

        Location cylinderAnchor = regionService.clampToArenaAnchor(new Location(endWorld, 150.0, -5.0, 0.0), 5.0);
        assertTrue(cylinderAnchor.getY() <= 98.0); // maxY - 2.0 headroom
        assertTrue(cylinderAnchor.getY() >= 1.0);

        // Cuboid with maxY = 100 and center.y = 99
        ArenaRegion cuboid = new ArenaRegion(
                Shape.CUBOID,
                new Point3D(0.0, 99.0, 0.0),
                120.0,
                new Point3D(-100.0, 0.0, -100.0),
                new Point3D(100.0, 100.0, 100.0),
                0,
                100);
        configManager.updateRegions(new RegionConfig(cuboid, current.safeZone(), 0.0));

        Location cuboidAnchor = regionService.clampToArenaAnchor(new Location(endWorld, 150.0, -5.0, 0.0), 5.0);
        assertTrue(cuboidAnchor.getY() <= 98.0);
        assertTrue(cuboidAnchor.getY() >= 1.0);
    }

    @Test
    void findNearestSafeArenaEdgeAsync_skipsLedgeWhenOffsetLandingHasCeilingObstruction() {
        Block air = mockBlock(Material.AIR, false, true, false);
        Block endStone = mockBlock(Material.END_STONE, true, false, false);
        when(endWorld.getBlockAt(any(int.class), any(int.class), any(int.class))).thenReturn(air);

        // Solid roof column from Y=67 up to Y=110 blocks landing on Y=64 ledge with yOffset=1.0
        when(endWorld.getBlockAt(0, 64, 0)).thenReturn(endStone);
        for (int roofY = 67; roofY <= 110; roofY++) {
            when(endWorld.getBlockAt(0, roofY, 0)).thenReturn(endStone);
        }
        // Lower clear ledge at (0, 50, 0) has full air above it (51..53)
        when(endWorld.getBlockAt(0, 50, 0)).thenReturn(endStone);

        Location fallLoc = new Location(endWorld, 5.0, -2.0, 0.0);
        Location rescued = regionService.findNearestSafeArenaEdgeAsync(fallLoc, 1.0).join();

        assertEquals(0.5, rescued.getX(), 1e-6);
        assertEquals(52.0, rescued.getY(), 1e-6); // 50 + 1.0 + 1.0 offset
        assertEquals(0.5, rescued.getZ(), 1e-6);
    }

    @Test
    void findNearestSafeArenaEdgeAsync_skipsBlockWhoseCenterFallsOutsideSmallRadius() {
        RegionConfig current = configManager.pluginConfig().regions();
        // Radius = 1 around center (0,0): block (1,0) has center (1.5, 0.5) -> distance > 1.0 (OUTSIDE!)
        ArenaRegion tinyCylinder = new ArenaRegion(
                Shape.CYLINDER,
                new Point3D(0.0, 64.0, 0.0),
                1.0,
                current.arena().min(),
                current.arena().max(),
                0,
                256);
        configManager.updateRegions(new RegionConfig(tinyCylinder, current.safeZone(), 0.0));

        Block air = mockBlock(Material.AIR, false, true, false);
        Block endStone = mockBlock(Material.END_STONE, true, false, false);
        when(endWorld.getBlockAt(any(int.class), any(int.class), any(int.class))).thenReturn(air);
        when(endWorld.getBlockAt(1, 63, 0)).thenReturn(endStone);
        when(endWorld.getBlockAt(0, 63, 0)).thenReturn(endStone);

        Location fallLoc = new Location(endWorld, 1.0, -2.0, 0.0);
        Location rescued = regionService.findNearestSafeArenaEdgeAsync(fallLoc, 1.0).join();

        // Must skip (1.5, 0.5) and land inside arena bounds at (0.5, 0.5)
        assertTrue(tinyCylinder.containsHorizontal(rescued.getX(), rescued.getZ()));
        assertEquals(0.5, rescued.getX(), 1e-6);
        assertEquals(0.5, rescued.getZ(), 1e-6);
    }

    private static Block mockBlock(Material material, boolean solid, boolean passable, boolean liquid) {
        Block block = mock(Block.class);
        lenient().when(block.getType()).thenReturn(material);
        lenient().when(block.isSolid()).thenReturn(solid);
        lenient().when(block.isPassable()).thenReturn(passable);
        lenient().when(block.isLiquid()).thenReturn(liquid);
        return block;
    }
}
