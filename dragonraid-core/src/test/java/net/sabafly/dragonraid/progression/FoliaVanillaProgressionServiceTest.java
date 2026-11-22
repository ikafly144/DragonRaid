package net.sabafly.dragonraid.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.concurrent.CompletionException;
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
        doAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return mock(TaskHandle.class);
        }).when(scheduler).runAtEntity(any(Entity.class), any(Runnable.class), any(Runnable.class));
    }

    @Test
    void synchronizeDragonDefeated_waitsForEntityRemovalAndHandlesRetired() {
        org.bukkit.World world = mock(org.bukkit.World.class);
        org.bukkit.boss.DragonBattle battle = mock(org.bukkit.boss.DragonBattle.class);
        org.bukkit.entity.EnderDragon dragon = mock(org.bukkit.entity.EnderDragon.class);
        org.mockito.Mockito.when(world.getName()).thenReturn("world_the_end");
        org.mockito.Mockito.when(world.getEnderDragonBattle()).thenReturn(battle);
        org.mockito.Mockito.when(battle.getEnderDragon()).thenReturn(dragon);
        org.mockito.Mockito.when(battle.getEndPortalLocation()).thenReturn(new org.bukkit.Location(world, 0.0, 64.0, 0.0));

        java.util.concurrent.atomic.AtomicReference<Runnable> entityTask = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Runnable> retiredTask = new java.util.concurrent.atomic.AtomicReference<>();
        RaidScheduler deferredScheduler = mock(RaidScheduler.class);
        org.mockito.Mockito.when(deferredScheduler.runAtRegion(any(org.bukkit.Location.class), any(Runnable.class)))
                .thenAnswer(inv -> {
                    inv.getArgument(1, Runnable.class).run();
                    return mock(TaskHandle.class);
                });
        java.util.List<Runnable> cancelCallbacks = new java.util.ArrayList<>();
        org.mockito.Mockito.when(deferredScheduler.runAtEntity(
                        org.mockito.ArgumentMatchers.eq(dragon), any(Runnable.class), any(Runnable.class)))
                .thenAnswer(inv -> {
                    entityTask.set(inv.getArgument(1, Runnable.class));
                    retiredTask.set(inv.getArgument(2, Runnable.class));
                    TaskHandle handle = mock(TaskHandle.class);
                    doAnswer(cb -> {
                        cancelCallbacks.add(cb.getArgument(0, Runnable.class));
                        return null;
                    }).when(handle).onCancel(any(Runnable.class));
                    return handle;
                });

        FoliaVanillaProgressionService deferred = new FoliaVanillaProgressionService(
                configManager,
                deferredScheduler,
                name -> world,
                java.util.logging.Logger.getLogger("test"));

        java.util.concurrent.CompletableFuture<Boolean> future =
                deferred.synchronizeDragonDefeated("world_the_end", true, false, true);
        org.junit.jupiter.api.Assertions.assertFalse(future.isDone());
        verify(dragon, org.mockito.Mockito.never()).remove();

        entityTask.get().run();
        verify(dragon).remove();
        assertTrue(future.join());

        // Entity retired before execution -> vanilla dragon is already gone, completes true without removing again
        java.util.concurrent.CompletableFuture<Boolean> retiredFuture =
                deferred.synchronizeDragonDefeated("world_the_end", true, false, true);
        org.junit.jupiter.api.Assertions.assertFalse(retiredFuture.isDone());
        retiredTask.get().run();
        assertTrue(retiredFuture.join());
        verify(dragon, org.mockito.Mockito.times(1)).remove();

        // Cancelling a live removal TaskHandle via onCancel completes the returned future exceptionally
        java.util.concurrent.CompletableFuture<Boolean> cancelledFuture =
                deferred.synchronizeDragonDefeated("world_the_end", true, false, true);
        org.junit.jupiter.api.Assertions.assertFalse(cancelledFuture.isDone());
        cancelCallbacks.get(cancelCallbacks.size() - 1).run();
        assertTrue(cancelledFuture.isCompletedExceptionally());
    }

    @Test
    void cancelPendingTasks_andHandleOnCancel_failAllPendingFutures() {
        org.bukkit.World world = mock(org.bukkit.World.class);
        org.bukkit.boss.DragonBattle battle = mock(org.bukkit.boss.DragonBattle.class);
        org.mockito.Mockito.when(world.getName()).thenReturn("world_the_end");
        org.mockito.Mockito.when(world.getEnderDragonBattle()).thenReturn(battle);

        java.util.List<Runnable> cancelCallbacks = new java.util.ArrayList<>();
        RaidScheduler manualScheduler = mock(RaidScheduler.class);
        org.mockito.Mockito.when(manualScheduler.runAtRegion(any(org.bukkit.Location.class), any(Runnable.class)))
                .thenAnswer(inv -> {
                    TaskHandle handle = mock(TaskHandle.class);
                    doAnswer(cbInv -> {
                        cancelCallbacks.add(cbInv.getArgument(0, Runnable.class));
                        return null;
                    }).when(handle).onCancel(any(Runnable.class));
                    return handle;
                });

        FoliaVanillaProgressionService pending = new FoliaVanillaProgressionService(
                configManager,
                manualScheduler,
                name -> world,
                java.util.logging.Logger.getLogger("test"));

        java.util.concurrent.CompletableFuture<Boolean> syncFuture =
                pending.synchronizeDragonDefeated("world_the_end", true, false, false);
        java.util.concurrent.CompletableFuture<Integer> expandFuture =
                pending.expandEndGateways("world_the_end", 1, 20);
        java.util.concurrent.CompletableFuture<Boolean> killedFuture =
                pending.isVanillaDragonPreviouslyKilled("world_the_end");
        java.util.concurrent.CompletableFuture<Integer> gatewayFuture =
                pending.getEndGatewayCount("world_the_end");

        org.junit.jupiter.api.Assertions.assertFalse(
                syncFuture.isDone() || expandFuture.isDone() || killedFuture.isDone() || gatewayFuture.isDone());
        pending.cancelPendingTasks();
        assertTrue(syncFuture.isCompletedExceptionally());
        assertTrue(expandFuture.isCompletedExceptionally());
        assertTrue(killedFuture.isCompletedExceptionally());
        assertTrue(gatewayFuture.isCompletedExceptionally());

        java.util.concurrent.CompletableFuture<Boolean> onCancelFuture =
                pending.isVanillaDragonPreviouslyKilled("world_the_end");
        org.junit.jupiter.api.Assertions.assertFalse(onCancelFuture.isDone());
        cancelCallbacks.get(cancelCallbacks.size() - 1).run();
        assertTrue(onCancelFuture.isCompletedExceptionally());
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
        verify(scheduler).runAtEntity(any(Entity.class), any(Runnable.class), any(Runnable.class));
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
        CompletionException expandEx = assertThrows(
                CompletionException.class,
                () -> service.expandEndGateways("missing_world", 1, 20).join());
        assertTrue(expandEx.getCause() instanceof IllegalStateException);
        CompletionException targetEx = assertThrows(
                CompletionException.class,
                () -> service.expandEndGatewaysToTarget("missing_world", 5, 20).join());
        assertTrue(targetEx.getCause() instanceof IllegalStateException);

        // Missing DragonBattle also fails exceptionally rather than reporting 0 spawned
        World noBattleWorld = mock(World.class);
        when(noBattleWorld.getEnderDragonBattle()).thenReturn(null);
        FoliaVanillaProgressionService noBattleService = new FoliaVanillaProgressionService(
                configManager, scheduler, name -> "no_battle".equals(name) ? noBattleWorld : null, Logger.getLogger("test"));
        CompletionException noBattleEx = assertThrows(
                CompletionException.class,
                () -> noBattleService.expandEndGatewaysToTarget("no_battle", 2, 20).join());
        assertTrue(noBattleEx.getCause() instanceof IllegalStateException);

        assertTrue(service.synchronizeDragonDefeated("custom_end", false, true, false).join());
        verify(liveDragon, never()).remove();
        verify(battle, never()).generateEndPortal(true);
        verify(battle, never()).setPreviouslyKilled(true);
        assertTrue(service.isVanillaDragonPreviouslyKilled("custom_end").join());
    }

    @Test
    void expandEndGatewaysToTarget_respectsTargetAndPreventsChildScheduleAfterCancel() {
        World world = mock(World.class);
        DragonBattle battle = mock(DragonBattle.class);
        EnderDragon dragon = mock(EnderDragon.class);
        when(world.getName()).thenReturn("world_the_end");
        when(world.getEnderDragonBattle()).thenReturn(battle);
        when(battle.getEnderDragon()).thenReturn(dragon);
        when(battle.getEndPortalLocation()).thenReturn(new Location(world, 0.0, 64.0, 0.0));
        AtomicInteger gateways = new AtomicInteger(1);
        when(battle.getGatewayCount()).thenAnswer(inv -> gateways.get());
        when(battle.spawnNewGateway()).thenAnswer(inv -> {
            gateways.incrementAndGet();
            return true;
        });

        FoliaVanillaProgressionService syncSvc = new FoliaVanillaProgressionService(
                configManager, scheduler, name -> world, Logger.getLogger("test"));
        var targetRes = syncSvc.expandEndGatewaysToTarget("world_the_end", 3, 20).join();
        assertEquals(2, targetRes.spawned());
        assertEquals(3, targetRes.finalGatewayCount());

        // Cancel pending parent operation while region task finishes concurrently -> child entity removal must NOT run
        java.util.concurrent.atomic.AtomicReference<Runnable> regionTask = new java.util.concurrent.atomic.AtomicReference<>();
        RaidScheduler manualScheduler = mock(RaidScheduler.class);
        when(manualScheduler.runAtRegion(any(Location.class), any(Runnable.class))).thenAnswer(inv -> {
            regionTask.set(inv.getArgument(1));
            return mock(TaskHandle.class);
        });
        FoliaVanillaProgressionService raceSvc = new FoliaVanillaProgressionService(
                configManager, manualScheduler, name -> world, Logger.getLogger("test"));
        var cancelledOp = raceSvc.synchronizeDragonDefeated("world_the_end", true, false, true);
        raceSvc.cancelPendingTasks();
        assertTrue(cancelledOp.isCompletedExceptionally());
        regionTask.get().run();
        verify(manualScheduler, never()).runAtEntity(any(), any(), any());
    }
}
