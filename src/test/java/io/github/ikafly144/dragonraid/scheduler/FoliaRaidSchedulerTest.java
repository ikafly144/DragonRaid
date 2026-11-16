package io.github.ikafly144.dragonraid.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FoliaRaidSchedulerTest {

    @Mock
    private Plugin plugin;

    @Mock
    private GlobalRegionScheduler globalRegionScheduler;

    @Mock
    private RegionScheduler regionScheduler;

    @Mock
    private AsyncScheduler asyncScheduler;

    private FoliaRaidScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new FoliaRaidScheduler(plugin, globalRegionScheduler, regionScheduler, asyncScheduler);
    }

    @Test
    void pluginConstructorExtractsServerSchedulers() {
        Server server = mock(Server.class);
        Plugin customPlugin = mock(Plugin.class);
        when(customPlugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(globalRegionScheduler);
        when(server.getRegionScheduler()).thenReturn(regionScheduler);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);

        FoliaRaidScheduler constructed = new FoliaRaidScheduler(customPlugin);
        assertNotNull(constructed.asyncExecutor());
        verify(server).getGlobalRegionScheduler();
        verify(server).getRegionScheduler();
        verify(server).getAsyncScheduler();
    }

    @Test
    @SuppressWarnings("unchecked")
    void runGlobalDelegatesAndExecutesRunnable() {
        ScheduledTask scheduledTask = mock(ScheduledTask.class);
        when(globalRegionScheduler.run(eq(plugin), any())).thenReturn(scheduledTask);

        AtomicBoolean ran = new AtomicBoolean(false);
        TaskHandle handle = scheduler.runGlobal(() -> ran.set(true));

        assertNotNull(handle);
        ArgumentCaptor<Consumer<ScheduledTask>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(globalRegionScheduler).run(eq(plugin), captor.capture());

        captor.getValue().accept(scheduledTask);
        assertTrue(ran.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void runGlobalLaterAndTimerDelegateWithTicks() {
        ScheduledTask delayed = mock(ScheduledTask.class);
        ScheduledTask timer = mock(ScheduledTask.class);
        when(globalRegionScheduler.runDelayed(eq(plugin), any(), eq(15L))).thenReturn(delayed);
        when(globalRegionScheduler.runAtFixedRate(eq(plugin), any(), eq(5L), eq(20L))).thenReturn(timer);

        AtomicInteger count = new AtomicInteger();
        scheduler.runGlobalLater(count::incrementAndGet, 15L);
        scheduler.runGlobalTimer(count::incrementAndGet, 5L, 20L);

        ArgumentCaptor<Consumer<ScheduledTask>> delayedCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(globalRegionScheduler).runDelayed(eq(plugin), delayedCaptor.capture(), eq(15L));
        delayedCaptor.getValue().accept(delayed);

        ArgumentCaptor<Consumer<ScheduledTask>> timerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(globalRegionScheduler).runAtFixedRate(eq(plugin), timerCaptor.capture(), eq(5L), eq(20L));
        when(timer.isRepeatingTask()).thenReturn(true);
        timerCaptor.getValue().accept(timer);
        timerCaptor.getValue().accept(timer);

        assertEquals(3, count.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void runAtRegionWithLocationDelegatesAllVariants() {
        Location location = mock(Location.class);
        ScheduledTask taskNow = mock(ScheduledTask.class);
        ScheduledTask taskLater = mock(ScheduledTask.class);
        ScheduledTask taskTimer = mock(ScheduledTask.class);
        when(regionScheduler.run(eq(plugin), eq(location), any())).thenReturn(taskNow);
        when(regionScheduler.runDelayed(eq(plugin), eq(location), any(), eq(10L))).thenReturn(taskLater);
        when(regionScheduler.runAtFixedRate(eq(plugin), eq(location), any(), eq(2L), eq(4L))).thenReturn(taskTimer);

        AtomicInteger runs = new AtomicInteger();
        scheduler.runAtRegion(location, runs::incrementAndGet);
        scheduler.runAtRegionLater(location, runs::incrementAndGet, 10L);
        scheduler.runAtRegionTimer(location, runs::incrementAndGet, 2L, 4L);

        ArgumentCaptor<Consumer<ScheduledTask>> nowCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(regionScheduler).run(eq(plugin), eq(location), nowCaptor.capture());
        nowCaptor.getValue().accept(taskNow);

        ArgumentCaptor<Consumer<ScheduledTask>> laterCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(regionScheduler).runDelayed(eq(plugin), eq(location), laterCaptor.capture(), eq(10L));
        laterCaptor.getValue().accept(taskLater);

        ArgumentCaptor<Consumer<ScheduledTask>> timerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(regionScheduler).runAtFixedRate(eq(plugin), eq(location), timerCaptor.capture(), eq(2L), eq(4L));
        timerCaptor.getValue().accept(taskTimer);

        assertEquals(3, runs.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void runAtRegionWithChunkCoordinatesDelegatesAllVariants() {
        World world = mock(World.class);
        ScheduledTask taskNow = mock(ScheduledTask.class);
        ScheduledTask taskLater = mock(ScheduledTask.class);
        ScheduledTask taskTimer = mock(ScheduledTask.class);
        when(regionScheduler.run(eq(plugin), eq(world), eq(3), eq(-7), any())).thenReturn(taskNow);
        when(regionScheduler.runDelayed(eq(plugin), eq(world), eq(3), eq(-7), any(), eq(12L))).thenReturn(taskLater);
        when(regionScheduler.runAtFixedRate(eq(plugin), eq(world), eq(3), eq(-7), any(), eq(1L), eq(20L)))
            .thenReturn(taskTimer);

        AtomicInteger runs = new AtomicInteger();
        scheduler.runAtRegion(world, 3, -7, runs::incrementAndGet);
        scheduler.runAtRegionLater(world, 3, -7, runs::incrementAndGet, 12L);
        scheduler.runAtRegionTimer(world, 3, -7, runs::incrementAndGet, 1L, 20L);

        ArgumentCaptor<Consumer<ScheduledTask>> nowCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(regionScheduler).run(eq(plugin), eq(world), eq(3), eq(-7), nowCaptor.capture());
        nowCaptor.getValue().accept(taskNow);

        ArgumentCaptor<Consumer<ScheduledTask>> laterCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(regionScheduler).runDelayed(eq(plugin), eq(world), eq(3), eq(-7), laterCaptor.capture(), eq(12L));
        laterCaptor.getValue().accept(taskLater);

        ArgumentCaptor<Consumer<ScheduledTask>> timerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(regionScheduler)
            .runAtFixedRate(eq(plugin), eq(world), eq(3), eq(-7), timerCaptor.capture(), eq(1L), eq(20L));
        timerCaptor.getValue().accept(taskTimer);

        assertEquals(3, runs.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void runAtEntityDelegatesTaskAndRetiredCallback() {
        Entity entity = mock(Entity.class);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        when(entity.getScheduler()).thenReturn(entityScheduler);

        ScheduledTask taskNow = mock(ScheduledTask.class);
        ScheduledTask taskLater = mock(ScheduledTask.class);
        ScheduledTask taskTimer = mock(ScheduledTask.class);
        when(entityScheduler.run(eq(plugin), any(), any())).thenReturn(taskNow);
        when(entityScheduler.runDelayed(eq(plugin), any(), any(), eq(8L))).thenReturn(taskLater);
        when(entityScheduler.runAtFixedRate(eq(plugin), any(), any(), eq(1L), eq(5L))).thenReturn(taskTimer);

        AtomicBoolean ranNow = new AtomicBoolean(false);
        AtomicBoolean ranLater = new AtomicBoolean(false);
        AtomicBoolean retiredCalled = new AtomicBoolean(false);
        AtomicBoolean ranTimer = new AtomicBoolean(false);

        scheduler.runAtEntity(entity, () -> ranNow.set(true));
        scheduler.runAtEntityLater(entity, () -> ranLater.set(true), () -> retiredCalled.set(true), 8L);
        scheduler.runAtEntityTimer(entity, () -> ranTimer.set(true), 1L, 5L);

        ArgumentCaptor<Consumer<ScheduledTask>> nowCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(entityScheduler).run(eq(plugin), nowCaptor.capture(), any());
        nowCaptor.getValue().accept(taskNow);
        assertTrue(ranNow.get());

        ArgumentCaptor<Consumer<ScheduledTask>> laterCaptor = ArgumentCaptor.forClass(Consumer.class);
        ArgumentCaptor<Runnable> retiredCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(entityScheduler).runDelayed(eq(plugin), laterCaptor.capture(), retiredCaptor.capture(), eq(8L));
        laterCaptor.getValue().accept(taskLater);
        retiredCaptor.getValue().run();
        assertTrue(ranLater.get());
        assertTrue(retiredCalled.get());

        ArgumentCaptor<Consumer<ScheduledTask>> timerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(entityScheduler).runAtFixedRate(eq(plugin), timerCaptor.capture(), any(), eq(1L), eq(5L));
        timerCaptor.getValue().accept(taskTimer);
        assertTrue(ranTimer.get());
    }

    @Test
    void runAtEntityReturnsRetiredHandleWhenEntityAlreadyRetired() {
        Entity entity = mock(Entity.class);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        when(entity.getScheduler()).thenReturn(entityScheduler);
        when(entityScheduler.run(eq(plugin), any(), any())).thenReturn(null);

        TaskHandle handle = scheduler.runAtEntity(entity, () -> {}, () -> {});

        assertTrue(handle.isCancelled());
        handle.cancel();
    }

    @Test
    @SuppressWarnings("unchecked")
    void runAsyncAndAsyncExecutorDelegateToAsyncScheduler() {
        ScheduledTask nowTask = mock(ScheduledTask.class);
        ScheduledTask delayedTask = mock(ScheduledTask.class);
        ScheduledTask timerTask = mock(ScheduledTask.class);
        ScheduledTask execTask = mock(ScheduledTask.class);
        when(asyncScheduler.runNow(eq(plugin), any())).thenReturn(nowTask, execTask);
        when(asyncScheduler.runDelayed(eq(plugin), any(), eq(500L), eq(TimeUnit.MILLISECONDS)))
            .thenReturn(delayedTask);
        when(asyncScheduler.runAtFixedRate(eq(plugin), any(), eq(0L), eq(5L), eq(TimeUnit.SECONDS)))
            .thenReturn(timerTask);

        AtomicInteger runs = new AtomicInteger();
        scheduler.runAsync(runs::incrementAndGet);
        scheduler.runAsyncLater(runs::incrementAndGet, 500L, TimeUnit.MILLISECONDS);
        scheduler.runAsyncTimer(runs::incrementAndGet, 0L, 5L, TimeUnit.SECONDS);
        scheduler.asyncExecutor().execute(runs::incrementAndGet);

        ArgumentCaptor<Consumer<ScheduledTask>> nowCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(asyncScheduler, org.mockito.Mockito.times(2)).runNow(eq(plugin), nowCaptor.capture());
        nowCaptor.getAllValues().get(0).accept(nowTask);
        nowCaptor.getAllValues().get(1).accept(execTask);

        ArgumentCaptor<Consumer<ScheduledTask>> delayedCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(asyncScheduler).runDelayed(eq(plugin), delayedCaptor.capture(), eq(500L), eq(TimeUnit.MILLISECONDS));
        delayedCaptor.getValue().accept(delayedTask);

        ArgumentCaptor<Consumer<ScheduledTask>> timerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(asyncScheduler)
            .runAtFixedRate(eq(plugin), timerCaptor.capture(), eq(0L), eq(5L), eq(TimeUnit.SECONDS));
        timerCaptor.getValue().accept(timerTask);

        assertEquals(4, runs.get());
    }

    @Test
    void taskHandleCancelAndIsCancelledDelegateToScheduledTask() {
        ScheduledTask scheduledTask = mock(ScheduledTask.class);
        when(globalRegionScheduler.run(eq(plugin), any())).thenReturn(scheduledTask);
        when(scheduledTask.isCancelled()).thenReturn(false, true);

        TaskHandle handle = scheduler.runGlobal(() -> {});
        assertFalse(handle.isCancelled());

        handle.cancel();
        verify(scheduledTask).cancel();
        assertTrue(handle.isCancelled());
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelAllCancelsOnlyUnfinishedAndNonCancelledTasksPlusPluginSchedulers() {
        Location location = mock(Location.class);
        Entity entity = mock(Entity.class);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        when(entity.getScheduler()).thenReturn(entityScheduler);

        ScheduledTask completedOneShot = mock(ScheduledTask.class);
        ScheduledTask manuallyCancelled = mock(ScheduledTask.class);
        ScheduledTask retiredEntityTask = mock(ScheduledTask.class);
        ScheduledTask activeRegionTimer = mock(ScheduledTask.class);
        ScheduledTask activeEntityTask = mock(ScheduledTask.class);

        when(globalRegionScheduler.run(eq(plugin), any())).thenReturn(completedOneShot);
        when(asyncScheduler.runNow(eq(plugin), any())).thenReturn(manuallyCancelled);
        when(entityScheduler.run(eq(plugin), any(), any())).thenReturn(retiredEntityTask);
        when(regionScheduler.runAtFixedRate(eq(plugin), eq(location), any(), eq(1L), eq(10L)))
            .thenReturn(activeRegionTimer);
        when(entityScheduler.runDelayed(eq(plugin), any(), any(), eq(20L))).thenReturn(activeEntityTask);

        scheduler.runGlobal(() -> {});
        ArgumentCaptor<Consumer<ScheduledTask>> globalCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(globalRegionScheduler).run(eq(plugin), globalCaptor.capture());
        globalCaptor.getValue().accept(completedOneShot);

        TaskHandle manualHandle = scheduler.runAsync(() -> {});
        manualHandle.cancel();

        scheduler.runAtEntity(entity, () -> {}, () -> {});
        ArgumentCaptor<Runnable> retiredCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(entityScheduler).run(eq(plugin), any(), retiredCaptor.capture());
        retiredCaptor.getValue().run();

        scheduler.runAtRegionTimer(location, () -> {}, 1L, 10L);
        ArgumentCaptor<Consumer<ScheduledTask>> regionTimerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(regionScheduler).runAtFixedRate(eq(plugin), eq(location), regionTimerCaptor.capture(), eq(1L), eq(10L));
        when(activeRegionTimer.isRepeatingTask()).thenReturn(true);
        regionTimerCaptor.getValue().accept(activeRegionTimer);

        scheduler.runAtEntityLater(entity, () -> {}, 20L);

        scheduler.cancelAll();

        verify(completedOneShot, never()).cancel();
        verify(manuallyCancelled, org.mockito.Mockito.times(1)).cancel();
        verify(retiredEntityTask, never()).cancel();
        verify(activeRegionTimer).cancel();
        verify(activeEntityTask).cancel();
        verify(globalRegionScheduler).cancelTasks(plugin);
        verify(asyncScheduler).cancelTasks(plugin);
    }

    @Test
    void validatesNullAndTickParameters() {
        Location location = mock(Location.class);
        World world = mock(World.class);
        Entity entity = mock(Entity.class);

        assertThrows(NullPointerException.class, () -> scheduler.runGlobal(null));
        assertThrows(NullPointerException.class, () -> scheduler.runAtRegion((Location) null, () -> {}));
        assertThrows(NullPointerException.class, () -> scheduler.runAtRegion((World) null, 0, 0, () -> {}));
        assertThrows(NullPointerException.class, () -> scheduler.runAtEntity(null, () -> {}));
        assertThrows(NullPointerException.class, () -> scheduler.runAsyncLater(() -> {}, 1L, null));
        assertThrows(NullPointerException.class, () -> scheduler.asyncExecutor().execute(null));

        assertThrows(IllegalArgumentException.class, () -> scheduler.runGlobalLater(() -> {}, 0L));
        assertThrows(IllegalArgumentException.class, () -> scheduler.runGlobalTimer(() -> {}, 1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> scheduler.runAtRegionLater(location, () -> {}, -1L));
        assertThrows(IllegalArgumentException.class, () -> scheduler.runAtRegionTimer(world, 0, 0, () -> {}, 0L, 1L));
        assertThrows(IllegalArgumentException.class, () -> scheduler.runAtEntityLater(entity, () -> {}, 0L));
        assertThrows(IllegalArgumentException.class, () -> scheduler.runAtEntityTimer(entity, () -> {}, 1L, -5L));
        assertThrows(
            IllegalArgumentException.class,
            () -> scheduler.runAsyncLater(() -> {}, -1L, TimeUnit.MILLISECONDS)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> scheduler.runAsyncTimer(() -> {}, 0L, 0L, TimeUnit.SECONDS)
        );
    }

    @Test
    void runAtEntityLaterReleasesTrackingOnRetireEvenWhenRetiredCallbackOmitted() {
        Entity entity = mock(Entity.class);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        when(entity.getScheduler()).thenReturn(entityScheduler);

        ScheduledTask scheduledTask = mock(ScheduledTask.class);
        when(entityScheduler.runDelayed(eq(plugin), any(), any(), eq(20L))).thenReturn(scheduledTask);

        TaskHandle handle = scheduler.runAtEntityLater(entity, () -> {}, 20L);
        ArgumentCaptor<Runnable> retiredCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(entityScheduler).runDelayed(eq(plugin), any(), retiredCaptor.capture(), eq(20L));

        retiredCaptor.getValue().run();
        scheduler.cancelAll();

        verify(scheduledTask, never()).cancel();
        assertNotNull(handle);
    }
}
