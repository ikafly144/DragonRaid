package net.sabafly.dragonraid.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FoliaVanillaProgressionServiceTest {

    @TempDir
    Path tempDir;

    private ConfigManager configManager;
    private RaidScheduler scheduler;

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager(
                tempDir,
                name -> FoliaVanillaProgressionServiceTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();
        scheduler = mock(RaidScheduler.class);
        doAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return mock(TaskHandle.class);
        }).when(scheduler).runAtRegion(any(Location.class), any(Runnable.class));
        doAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return mock(TaskHandle.class);
        }).when(scheduler).runAtEntity(any(Entity.class), any(Runnable.class));
    }

    @Test
    void synchronizesDragonBattleAndCapsGatewayExpansionAtVanillaLimit() {
        World endWorld = mock(World.class);
        DragonBattle battle = mock(DragonBattle.class);
        EnderDragon liveDragon = mock(EnderDragon.class);
        when(endWorld.getEnderDragonBattle()).thenReturn(battle);
        when(battle.getEnderDragon()).thenReturn(liveDragon);
        when(battle.hasBeenPreviouslyKilled()).thenReturn(false);

        AtomicInteger gateways = new AtomicInteger(18);
        when(battle.getGatewayCount()).thenAnswer(inv -> gateways.get());
        when(battle.spawnNewGateway()).thenAnswer(inv -> {
            gateways.incrementAndGet();
            return true;
        });

        FoliaVanillaProgressionService service = new FoliaVanillaProgressionService(
                configManager,
                scheduler,
                name -> "world_the_end".equals(name) ? endWorld : null,
                Logger.getLogger("test")
        );

        assertTrue(service.synchronizeDragonDefeated(null, true, true, true).join());
        verify(scheduler).runAtRegion(any(Location.class), any(Runnable.class));
        verify(scheduler).runAtEntity(any(Entity.class), any(Runnable.class));
        verify(liveDragon).remove();
        verify(battle).generateEndPortal(true);
        verify(battle).setPreviouslyKilled(true);

        // Request 5 gateways when 18 exist; capped at 20 -> spawns exactly 2.
        int spawned = service.expandEndGateways(null, 5, 25).join();
        assertEquals(2, spawned);
        verify(battle, times(2)).spawnNewGateway();
        assertEquals(20, service.getEndGatewayCount(null).join());
    }

    @Test
    void handlesMissingWorldAndDisabledRemovalSafely() {
        World customEnd = mock(World.class);
        DragonBattle battle = mock(DragonBattle.class);
        EnderDragon liveDragon = mock(EnderDragon.class);
        when(customEnd.getEnderDragonBattle()).thenReturn(battle);
        when(battle.getEnderDragon()).thenReturn(liveDragon);
        when(battle.hasBeenPreviouslyKilled()).thenReturn(true);

        FoliaVanillaProgressionService service = new FoliaVanillaProgressionService(
                configManager,
                scheduler,
                name -> "custom_end".equals(name) ? customEnd : null,
                Logger.getLogger("test")
        );

        assertFalse(service.synchronizeDragonDefeated("missing_world", true, true, true).join());
        assertEquals(0, service.expandEndGateways("missing_world", 1, 20).join());

        assertTrue(service.synchronizeDragonDefeated("custom_end", false, true, false).join());
        verify(liveDragon, never()).remove();
        verify(battle, never()).generateEndPortal(true);
        verify(battle, never()).setPreviouslyKilled(true);
        assertTrue(service.isVanillaDragonPreviouslyKilled("custom_end").join());
    }
}
