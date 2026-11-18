package net.sabafly.dragonraid.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TheNextLvlRaidInstanceManagerTest {

    private RaidScheduler scheduler;
    private World mockInstanceWorld;
    private AtomicInteger createCalls;
    private AtomicInteger deleteCalls;
    private TheNextLvlRaidInstanceManager manager;

    @BeforeEach
    void setUp() {
        scheduler = mock(RaidScheduler.class);
        lenient().when(scheduler.runAtRegion(any(Location.class), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(scheduler.runAtRegion(any(World.class), anyInt(), anyInt(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(3).run();
            return TaskHandle.retired();
        });
        lenient().when(scheduler.runAtEntity(any(), any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });

        mockInstanceWorld = mock(World.class);
        lenient().when(mockInstanceWorld.getName()).thenReturn("dragonraid_instance_1");
        Block block = mock(Block.class);
        lenient().when(mockInstanceWorld.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
        lenient().when(mockInstanceWorld.getPlayers()).thenReturn(List.of());

        createCalls = new AtomicInteger(0);
        deleteCalls = new AtomicInteger(0);

        CircularArenaBuilder arenaBuilder = new CircularArenaBuilder(scheduler, 20.0, 15.0, 8, 8, 64);
        TheNextLvlRaidInstanceManager.WorldsBridge bridge = new TheNextLvlRaidInstanceManager.WorldsBridge() {
            @Override
            public CompletableFuture<World> createVoidEndWorld(Key worldKey) {
                createCalls.incrementAndGet();
                return CompletableFuture.completedFuture(mockInstanceWorld);
            }

            @Override
            public CompletableFuture<Boolean> deleteWorld(World world) {
                deleteCalls.incrementAndGet();
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public List<World> loadedInstanceWorlds() {
                return List.of(mockInstanceWorld);
            }
        };

        manager = new TheNextLvlRaidInstanceManager(
                scheduler,
                arenaBuilder,
                bridge,
                Logger.getLogger("InstanceTest")
        );
    }

    @Test
    void createsCircularArenaReusesOnWipeAndDeletesOnDestroy() {
        RaidInstanceManager.RaidInstance instance = manager.getOrCreateInstance().join();
        assertNotNull(instance);
        assertSame(mockInstanceWorld, instance.world());
        assertEquals(8, instance.pillarCrystalLocations().size());
        assertEquals(CircularArenaBuilder.DEFAULT_SURFACE_Y, instance.arenaCenter().getY(), 1e-6);
        assertEquals(1, createCalls.get());
        assertTrue(manager.isInstanceWorld(mockInstanceWorld));

        // Second call (e.g. after wipe retry) reuses the existing instance without recreating
        RaidInstanceManager.RaidInstance reused = manager.getOrCreateInstance().join();
        assertSame(instance, reused);
        assertEquals(1, createCalls.get());

        // Destroying evacuates players and deletes the temporary world
        World lobbyEnd = mock(World.class);
        Player player = mock(Player.class);
        lenient().when(player.isOnline()).thenReturn(true);
        lenient().when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));
        lenient().when(mockInstanceWorld.getPlayers()).thenReturn(List.of(player));

        Location returnLoc = new Location(lobbyEnd, 100.5, 50.0, 0.5);
        manager.destroyActiveInstance(returnLoc).join();
        verify(player).teleportAsync(returnLoc);
        assertEquals(1, deleteCalls.get());
        assertNull(manager.activeInstance());
        assertFalse(manager.isInstanceWorld(null));
    }

    @Test
    void rejectsNonPluginInstanceNamesAndDeletesStaleOrFailedCreations() {
        assertTrue(TheNextLvlRaidInstanceManager.isManagedInstanceWorldName("dragonraid_instance_1"));
        assertFalse(TheNextLvlRaidInstanceManager.isManagedInstanceWorldName("instance_other_plugin"));
        assertFalse(TheNextLvlRaidInstanceManager.isManagedInstanceWorldName("myinstance_2"));

        // Creation interrupted by destroyActiveInstance deletes stale world when future resolves
        CompletableFuture<World> delayedWorldFuture = new CompletableFuture<>();
        AtomicInteger customDeletes = new AtomicInteger(0);
        World staleWorld = mock(World.class);
        lenient().when(staleWorld.getName()).thenReturn("dragonraid_instance_99");
        Block block = mock(Block.class);
        lenient().when(staleWorld.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
        lenient().when(staleWorld.getPlayers()).thenReturn(List.of());

        CircularArenaBuilder smallBuilder = new CircularArenaBuilder(scheduler, 16.0, 12.0, 4, 4, 64);
        TheNextLvlRaidInstanceManager raceManager = new TheNextLvlRaidInstanceManager(
                scheduler,
                smallBuilder,
                new TheNextLvlRaidInstanceManager.WorldsBridge() {
                    @Override
                    public CompletableFuture<World> createVoidEndWorld(Key worldKey) {
                        return delayedWorldFuture;
                    }

                    @Override
                    public CompletableFuture<Boolean> deleteWorld(World world) {
                        customDeletes.incrementAndGet();
                        return CompletableFuture.completedFuture(true);
                    }

                    @Override
                    public List<World> loadedInstanceWorlds() {
                        return List.of();
                    }
                },
                Logger.getLogger("RaceInstanceTest")
        );

        CompletableFuture<RaidInstanceManager.RaidInstance> inflight = raceManager.getOrCreateInstance();
        raceManager.destroyActiveInstance(null).join();
        assertEquals(0, customDeletes.get());

        // Now complete creation -> must detect stale generation and delete staleWorld without activating it
        delayedWorldFuture.complete(staleWorld);
        inflight.join();
        assertNull(raceManager.activeInstance());
        assertEquals(1, customDeletes.get());
    }
}
