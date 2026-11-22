package net.sabafly.dragonraid.scheduler;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
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
    private final Set<TrackedTask> activeTasks = ConcurrentHashMap.newKeySet();
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
        for (TrackedTask task : List.copyOf(activeTasks)) {
            task.cancel();
        }
        globalRegionScheduler.cancelTasks(plugin);
        asyncScheduler.cancelTasks(plugin);
    }

    private TaskHandle scheduleTracked(Runnable task, Function<Consumer<ScheduledTask>, ScheduledTask> call) {
        Objects.requireNonNull(task, "task");
        TrackedTask tracked = new TrackedTask(activeTasks);
        ScheduledTask scheduled = call.apply(wrapTask(task, tracked));
        return registerTask(scheduled, tracked);
    }

    private TaskHandle scheduleEntityTracked(
        Entity entity,
        Runnable task,
        Runnable retired,
        BiFunction<Consumer<ScheduledTask>, Runnable, ScheduledTask> call
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(task, "task");
        TrackedTask tracked = new TrackedTask(activeTasks);
        Runnable wrappedRetired = () -> {
            tracked.markFinishedOrRetired();
            try {
                if (retired != null) {
                    retired.run();
                }
            } finally {
                activeTasks.remove(tracked);
            }
        };
        ScheduledTask scheduled = call.apply(wrapTask(task, tracked), wrappedRetired);
        return registerTask(scheduled, tracked);
    }

    private Consumer<ScheduledTask> wrapTask(Runnable task, TrackedTask tracked) {
        return scheduledTask -> {
            boolean repeating = scheduledTask != null && scheduledTask.isRepeatingTask();
            if (!repeating) {
                tracked.markFinishedOrRetired();
            }
            try {
                task.run();
            } finally {
                if (!repeating) {
                    activeTasks.remove(tracked);
                }
            }
        };
    }

    private TaskHandle registerTask(ScheduledTask scheduled, TrackedTask tracked) {
        if (scheduled == null) {
            return TaskHandle.retired();
        }
        tracked.attach(scheduled);
        if (scheduled.isCancelled()) {
            tracked.markCancelledFromDelegate();
            return new FoliaTaskHandle(tracked);
        }
        if (!tracked.isDone()) {
            activeTasks.add(tracked);
            // Guard against synchronous completion or cancellation between scheduling and add.
            if (tracked.isDone()) {
                activeTasks.remove(tracked);
            } else if (scheduled.isCancelled()) {
                activeTasks.remove(tracked);
                tracked.markCancelledFromDelegate();
            }
        }
        return new FoliaTaskHandle(tracked);
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

    private static final class TrackedTask {

        private final Set<TrackedTask> registry;
        private final AtomicReference<ScheduledTask> delegateRef = new AtomicReference<>();
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final List<Runnable> cancelCallbacks = new ArrayList<>();

        private TrackedTask(Set<TrackedTask> registry) {
            this.registry = registry;
        }

        private void attach(ScheduledTask delegate) {
            delegateRef.set(delegate);
        }

        private void markFinishedOrRetired() {
            finished.set(true);
            synchronized (cancelCallbacks) {
                cancelCallbacks.clear();
            }
        }

        private boolean isDone() {
            return finished.get() || cancelled.get();
        }

        private void cancel() {
            registry.remove(this);
            ScheduledTask delegate = delegateRef.get();
            if (delegate != null) {
                delegate.cancel();
            }
            if (!finished.get() && cancelled.compareAndSet(false, true)) {
                fireCancelCallbacks(null);
            }
        }

        private void markCancelledFromDelegate() {
            registry.remove(this);
            if (!finished.get() && cancelled.compareAndSet(false, true)) {
                fireCancelCallbacks(null);
            }
        }

        private void fireCancelCallbacks(Runnable extraCallback) {
            List<Runnable> callbacks;
            synchronized (cancelCallbacks) {
                callbacks = new ArrayList<>(cancelCallbacks.size() + (extraCallback != null ? 1 : 0));
                callbacks.addAll(cancelCallbacks);
                cancelCallbacks.clear();
            }
            if (extraCallback != null) {
                callbacks.add(extraCallback);
            }
            for (Runnable callback : callbacks) {
                try {
                    callback.run();
                } catch (RuntimeException ignored) {
                    // Do not abort subsequent cancellation listeners.
                }
            }
        }

        private boolean isCancelled() {
            if (cancelled.get()) {
                return true;
            }
            ScheduledTask delegate = delegateRef.get();
            return delegate != null && delegate.isCancelled();
        }

        private void onCancel(Runnable callback) {
            if (callback == null || finished.get()) {
                return;
            }
            boolean runSingleImmediately = false;
            boolean drainAllIncludingExtra = false;
            synchronized (cancelCallbacks) {
                if (finished.get()) {
                    return;
                }
                if (cancelled.get()) {
                    runSingleImmediately = true;
                } else {
                    ScheduledTask delegate = delegateRef.get();
                    if (delegate != null && delegate.isCancelled()) {
                        registry.remove(this);
                        if (cancelled.compareAndSet(false, true)) {
                            drainAllIncludingExtra = true;
                        } else {
                            runSingleImmediately = true;
                        }
                    } else {
                        cancelCallbacks.add(callback);
                    }
                }
            }
            if (drainAllIncludingExtra) {
                fireCancelCallbacks(callback);
            } else if (runSingleImmediately) {
                callback.run();
            }
        }
    }

    private static final class FoliaTaskHandle implements TaskHandle {

        private final TrackedTask tracked;

        private FoliaTaskHandle(TrackedTask tracked) {
            this.tracked = tracked;
        }

        @Override
        public void cancel() {
            tracked.cancel();
        }

        @Override
        public boolean isCancelled() {
            return tracked.isCancelled();
        }

        @Override
        public void onCancel(Runnable callback) {
            tracked.onCancel(callback);
        }
    }
}
