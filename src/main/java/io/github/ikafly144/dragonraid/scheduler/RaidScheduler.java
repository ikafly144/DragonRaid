package io.github.ikafly144.dragonraid.scheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

/**
 * Folia-safe scheduler abstraction for DragonRaid.
 *
 * <p>All world/block operations must be dispatched to the owning region via
 * {@link #runAtRegion} or entity via {@link #runAtEntity}, global coordination to
 * {@link #runGlobal}, and I/O or packet preparation to {@link #runAsync} /
 * {@link #asyncExecutor()}.
 */
public interface RaidScheduler {

    /**
     * Schedules a task on the global region on the next tick.
     *
     * @param task task to execute
     * @return handle for cancelling the scheduled task
     */
    TaskHandle runGlobal(Runnable task);

    /**
     * Schedules a task on the global region after a delay in ticks.
     *
     * @param task task to execute
     * @param delayTicks delay in ticks (must be {@code > 0})
     * @return handle for cancelling the scheduled task
     */
    TaskHandle runGlobalLater(Runnable task, long delayTicks);

    /**
     * Schedules a repeating task on the global region.
     *
     * @param task task to execute
     * @param initialDelayTicks initial delay in ticks (must be {@code > 0})
     * @param periodTicks repeat period in ticks (must be {@code > 0})
     * @return handle for cancelling the scheduled task
     */
    TaskHandle runGlobalTimer(Runnable task, long initialDelayTicks, long periodTicks);

    /**
     * Schedules a task on the region owning the given location.
     *
     * @param location target location
     * @param task task to execute
     * @return handle for cancelling the scheduled task
     */
    TaskHandle runAtRegion(Location location, Runnable task);

    /**
     * Schedules a task on the region owning the given location after a delay in ticks.
     *
     * @param location target location
     * @param task task to execute
     * @param delayTicks delay in ticks (must be {@code > 0})
     * @return handle for cancelling the scheduled task
     */
    TaskHandle runAtRegionLater(Location location, Runnable task, long delayTicks);

    /**
     * Schedules a repeating task on the region owning the given location.
     *
     * @param location target location
     * @param task task to execute
     * @param initialDelayTicks initial delay in ticks (must be {@code > 0})
     * @param periodTicks repeat period in ticks (must be {@code > 0})
     * @return handle for cancelling the scheduled task
     */
    TaskHandle runAtRegionTimer(Location location, Runnable task, long initialDelayTicks, long periodTicks);

    /**
     * Schedules a task on the region owning the specified chunk.
     *
     * @param world target world
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @param task task to execute
     * @return handle for cancelling the scheduled task
     */
    TaskHandle runAtRegion(World world, int chunkX, int chunkZ, Runnable task);

    /**
     * Schedules a task on the region owning the specified chunk after a delay in ticks.
     *
     * @param world target world
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @param task task to execute
     * @param delayTicks delay in ticks (must be {@code > 0})
     * @return handle for cancelling the scheduled task
     */
    TaskHandle runAtRegionLater(World world, int chunkX, int chunkZ, Runnable task, long delayTicks);

    /**
     * Schedules a repeating task on the region owning the specified chunk.
     *
     * @param world target world
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @param task task to execute
     * @param initialDelayTicks initial delay in ticks (must be {@code > 0})
     * @param periodTicks repeat period in ticks (must be {@code > 0})
     * @return handle for cancelling the scheduled task
     */
    TaskHandle runAtRegionTimer(
        World world,
        int chunkX,
        int chunkZ,
        Runnable task,
        long initialDelayTicks,
        long periodTicks
    );

    /**
     * Schedules a task bound to the given entity's scheduler.
     *
     * @param entity target entity
     * @param task task to execute while the entity is alive
     * @param retired optional callback run if the entity retires before execution
     * @return handle for cancelling the scheduled task, or {@link TaskHandle#retired()} if already retired
     */
    TaskHandle runAtEntity(Entity entity, Runnable task, Runnable retired);

    /**
     * Schedules a task bound to the given entity's scheduler with no retired callback.
     *
     * @param entity target entity
     * @param task task to execute while the entity is alive
     * @return handle for cancelling the scheduled task, or {@link TaskHandle#retired()} if already retired
     */
    default TaskHandle runAtEntity(Entity entity, Runnable task) {
        return runAtEntity(entity, task, null);
    }

    /**
     * Schedules a delayed task bound to the given entity's scheduler.
     *
     * @param entity target entity
     * @param task task to execute while the entity is alive
     * @param retired optional callback run if the entity retires before execution
     * @param delayTicks delay in ticks (must be {@code > 0})
     * @return handle for cancelling the scheduled task, or {@link TaskHandle#retired()} if already retired
     */
    TaskHandle runAtEntityLater(Entity entity, Runnable task, Runnable retired, long delayTicks);

    /**
     * Schedules a delayed task bound to the given entity's scheduler with no retired callback.
     *
     * @param entity target entity
     * @param task task to execute while the entity is alive
     * @param delayTicks delay in ticks (must be {@code > 0})
     * @return handle for cancelling the scheduled task, or {@link TaskHandle#retired()} if already retired
     */
    default TaskHandle runAtEntityLater(Entity entity, Runnable task, long delayTicks) {
        return runAtEntityLater(entity, task, null, delayTicks);
    }

    /**
     * Schedules a repeating task bound to the given entity's scheduler.
     *
     * @param entity target entity
     * @param task task to execute while the entity is alive
     * @param retired optional callback run if the entity retires
     * @param initialDelayTicks initial delay in ticks (must be {@code > 0})
     * @param periodTicks repeat period in ticks (must be {@code > 0})
     * @return handle for cancelling the scheduled task, or {@link TaskHandle#retired()} if already retired
     */
    TaskHandle runAtEntityTimer(
        Entity entity,
        Runnable task,
        Runnable retired,
        long initialDelayTicks,
        long periodTicks
    );

    /**
     * Schedules a repeating task bound to the given entity's scheduler with no retired callback.
     *
     * @param entity target entity
     * @param task task to execute while the entity is alive
     * @param initialDelayTicks initial delay in ticks (must be {@code > 0})
     * @param periodTicks repeat period in ticks (must be {@code > 0})
     * @return handle for cancelling the scheduled task, or {@link TaskHandle#retired()} if already retired
     */
    default TaskHandle runAtEntityTimer(Entity entity, Runnable task, long initialDelayTicks, long periodTicks) {
        return runAtEntityTimer(entity, task, null, initialDelayTicks, periodTicks);
    }

    /**
     * Schedules an asynchronous task immediately.
     *
     * @param task task to execute asynchronously
     * @return handle for cancelling the scheduled task
     */
    TaskHandle runAsync(Runnable task);

    /**
     * Schedules an asynchronous task after the given delay.
     *
     * @param task task to execute asynchronously
     * @param delay delay duration (must be {@code >= 0})
     * @param unit time unit for {@code delay}
     * @return handle for cancelling the scheduled task
     */
    TaskHandle runAsyncLater(Runnable task, long delay, TimeUnit unit);

    /**
     * Schedules a repeating asynchronous task.
     *
     * @param task task to execute asynchronously
     * @param initialDelay initial delay duration (must be {@code >= 0})
     * @param period repeat period (must be {@code > 0})
     * @param unit time unit for {@code initialDelay} and {@code period}
     * @return handle for cancelling the scheduled task
     */
    TaskHandle runAsyncTimer(Runnable task, long initialDelay, long period, TimeUnit unit);

    /**
     * Returns an {@link Executor} backed by the asynchronous scheduler.
     *
     * @return async executor suitable for database repositories and background I/O
     */
    Executor asyncExecutor();

    /**
     * Cancels all tracked tasks and plugin-scoped global/async tasks for clean shutdown or reset.
     */
    void cancelAll();
}
