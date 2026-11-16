package io.github.ikafly144.dragonraid.scheduler;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * {@link RaidScheduler} implementation backed by Folia's threaded region, entity, global, and async schedulers.
 */
public final class FoliaRaidScheduler implements RaidScheduler {

    private final Plugin plugin;
    private final GlobalRegionScheduler globalRegionScheduler;
    private final RegionScheduler regionScheduler;
    private final AsyncScheduler asyncScheduler;
    private final Set<ScheduledTask> activeTasks = ConcurrentHashMap.newKeySet();
    private final Executor asyncExecutor = command -> {
        Objects.requireNonNull(command, "command");
        runAsync(command);
    };

    /**
     * Creates a scheduler wrapping the Folia schedulers from the plugin's {@link org.bukkit.Server}.
     *
     * @param plugin owning plugin
     */
    public FoliaRaidScheduler(Plugin plugin) {
        this(
            Objects.requireNonNull(plugin, "plugin"),
            plugin.getServer().getGlobalRegionScheduler(),
            plugin.getServer().getRegionScheduler(),
            plugin.getServer().getAsyncScheduler()
        );
    }

    /**
     * Creates a scheduler with explicit Folia scheduler delegates (useful for testing).
     *
     * @param plugin owning plugin
     * @param globalRegionScheduler Folia global region scheduler
     * @param regionScheduler Folia region scheduler
     * @param asyncScheduler Folia async scheduler
     */
    public FoliaRaidScheduler(
        Plugin plugin,
        GlobalRegionScheduler globalRegionScheduler,
        RegionScheduler regionScheduler,
        AsyncScheduler asyncScheduler
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.globalRegionScheduler = Objects.requireNonNull(globalRegionScheduler, "globalRegionScheduler");
        this.regionScheduler = Objects.requireNonNull(regionScheduler, "regionScheduler");
        this.asyncScheduler = Objects.requireNonNull(asyncScheduler, "asyncScheduler");
    }

    @Override
    public TaskHandle runGlobal(Runnable task) {
        return scheduleTracked(task, consumer -> globalRegionScheduler.run(plugin, consumer));
    }

    @Override
    public TaskHandle runGlobalLater(Runnable task, long delayTicks) {
        requirePositive(delayTicks, "delayTicks");
        return scheduleTracked(task, consumer -> globalRegionScheduler.runDelayed(plugin, consumer, delayTicks));
    }

    @Override
    public TaskHandle runGlobalTimer(Runnable task, long initialDelayTicks, long periodTicks) {
        requirePositive(initialDelayTicks, "initialDelayTicks");
        requirePositive(periodTicks, "periodTicks");
        return scheduleTracked(
            task,
            consumer -> globalRegionScheduler.runAtFixedRate(plugin, consumer, initialDelayTicks, periodTicks)
        );
    }

    @Override
    public TaskHandle runAtRegion(Location location, Runnable task) {
        Objects.requireNonNull(location, "location");
        return scheduleTracked(task, consumer -> regionScheduler.run(plugin, location, consumer));
    }

    @Override
    public TaskHandle runAtRegionLater(Location location, Runnable task, long delayTicks) {
        Objects.requireNonNull(location, "location");
        requirePositive(delayTicks, "delayTicks");
        return scheduleTracked(
            task,
            consumer -> regionScheduler.runDelayed(plugin, location, consumer, delayTicks)
        );
    }

    @Override
    public TaskHandle runAtRegionTimer(
        Location location,
        Runnable task,
        long initialDelayTicks,
        long periodTicks
    ) {
        Objects.requireNonNull(location, "location");
        requirePositive(initialDelayTicks, "initialDelayTicks");
        requirePositive(periodTicks, "periodTicks");
        return scheduleTracked(
            task,
            consumer -> regionScheduler.runAtFixedRate(plugin, location, consumer, initialDelayTicks, periodTicks)
        );
    }

    @Override
    public TaskHandle runAtRegion(World world, int chunkX, int chunkZ, Runnable task) {
        Objects.requireNonNull(world, "world");
        return scheduleTracked(task, consumer -> regionScheduler.run(plugin, world, chunkX, chunkZ, consumer));
    }

    @Override
    public TaskHandle runAtRegionLater(World world, int chunkX, int chunkZ, Runnable task, long delayTicks) {
        Objects.requireNonNull(world, "world");
        requirePositive(delayTicks, "delayTicks");
        return scheduleTracked(
            task,
            consumer -> regionScheduler.runDelayed(plugin, world, chunkX, chunkZ, consumer, delayTicks)
        );
    }

    @Override
    public TaskHandle runAtRegionTimer(
        World world,
        int chunkX,
        int chunkZ,
        Runnable task,
        long initialDelayTicks,
        long periodTicks
    ) {
        Objects.requireNonNull(world, "world");
        requirePositive(initialDelayTicks, "initialDelayTicks");
        requirePositive(periodTicks, "periodTicks");
        return scheduleTracked(
            task,
            consumer -> regionScheduler.runAtFixedRate(
                plugin,
                world,
                chunkX,
                chunkZ,
                consumer,
                initialDelayTicks,
                periodTicks
            )
        );
    }

    @Override
    public TaskHandle runAtEntity(Entity entity, Runnable task, Runnable retired) {
        return scheduleEntityTracked(
            entity,
            task,
            retired,
            (consumer, retiredCallback) -> entity.getScheduler().run(plugin, consumer, retiredCallback)
        );
    }

    @Override
    public TaskHandle runAtEntityLater(Entity entity, Runnable task, Runnable retired, long delayTicks) {
        requirePositive(delayTicks, "delayTicks");
        return scheduleEntityTracked(
            entity,
            task,
            retired,
            (consumer, retiredCallback) -> entity.getScheduler().runDelayed(
                plugin,
                consumer,
                retiredCallback,
                delayTicks
            )
        );
    }

    @Override
    public TaskHandle runAtEntityTimer(
        Entity entity,
        Runnable task,
        Runnable retired,
        long initialDelayTicks,
        long periodTicks
    ) {
        requirePositive(initialDelayTicks, "initialDelayTicks");
        requirePositive(periodTicks, "periodTicks");
        return scheduleEntityTracked(
            entity,
            task,
            retired,
            (consumer, retiredCallback) -> entity.getScheduler().runAtFixedRate(
                plugin,
                consumer,
                retiredCallback,
                initialDelayTicks,
                periodTicks
            )
        );
    }

    @Override
    public TaskHandle runAsync(Runnable task) {
        return scheduleTracked(task, consumer -> asyncScheduler.runNow(plugin, consumer));
    }

    @Override
    public TaskHandle runAsyncLater(Runnable task, long delay, TimeUnit unit) {
        requireNonNegative(delay, "delay");
        Objects.requireNonNull(unit, "unit");
        return scheduleTracked(task, consumer -> asyncScheduler.runDelayed(plugin, consumer, delay, unit));
    }

    @Override
    public TaskHandle runAsyncTimer(Runnable task, long initialDelay, long period, TimeUnit unit) {
        requireNonNegative(initialDelay, "initialDelay");
        requirePositive(period, "period");
        Objects.requireNonNull(unit, "unit");
        return scheduleTracked(
            task,
            consumer -> asyncScheduler.runAtFixedRate(plugin, consumer, initialDelay, period, unit)
        );
    }

    @Override
    public Executor asyncExecutor() {
        return asyncExecutor;
    }

    @Override
    public void cancelAll() {
        for (ScheduledTask task : List.copyOf(activeTasks)) {
            activeTasks.remove(task);
            task.cancel();
        }
        globalRegionScheduler.cancelTasks(plugin);
        asyncScheduler.cancelTasks(plugin);
    }

    private TaskHandle scheduleTracked(Runnable task, Function<Consumer<ScheduledTask>, ScheduledTask> call) {
        Objects.requireNonNull(task, "task");
        AtomicBoolean completed = new AtomicBoolean(false);
        ScheduledTask scheduled = call.apply(wrapTask(task, completed));
        return registerTask(scheduled, completed);
    }

    private TaskHandle scheduleEntityTracked(
        Entity entity,
        Runnable task,
        Runnable retired,
        BiFunction<Consumer<ScheduledTask>, Runnable, ScheduledTask> call
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(task, "task");
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<ScheduledTask> scheduledRef = new AtomicReference<>();
        Runnable wrappedRetired = retired == null ? null : () -> {
            try {
                retired.run();
            } finally {
                completed.set(true);
                ScheduledTask st = scheduledRef.get();
                if (st != null) {
                    activeTasks.remove(st);
                }
            }
        };
        ScheduledTask scheduled = call.apply(wrapTask(task, completed), wrappedRetired);
        if (scheduled != null) {
            scheduledRef.set(scheduled);
        }
        return registerTask(scheduled, completed);
    }

    private Consumer<ScheduledTask> wrapTask(Runnable task, AtomicBoolean completed) {
        return scheduledTask -> {
            try {
                task.run();
            } finally {
                if (!scheduledTask.isRepeatingTask()) {
                    completed.set(true);
                    activeTasks.remove(scheduledTask);
                }
            }
        };
    }

    private TaskHandle registerTask(ScheduledTask scheduled, AtomicBoolean completed) {
        if (scheduled == null) {
            return TaskHandle.retired();
        }
        if (!completed.get()) {
            activeTasks.add(scheduled);
            // Guard against synchronous completion between scheduling and add.
            if (completed.get()) {
                activeTasks.remove(scheduled);
            }
        }
        return new FoliaTaskHandle(scheduled, activeTasks);
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be > 0, got: " + value);
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, got: " + value);
        }
    }

    private static final class FoliaTaskHandle implements TaskHandle {

        private final ScheduledTask delegate;
        private final Set<ScheduledTask> activeTasks;

        private FoliaTaskHandle(ScheduledTask delegate, Set<ScheduledTask> activeTasks) {
            this.delegate = delegate;
            this.activeTasks = activeTasks;
        }

        @Override
        public void cancel() {
            activeTasks.remove(delegate);
            delegate.cancel();
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }
    }
}
