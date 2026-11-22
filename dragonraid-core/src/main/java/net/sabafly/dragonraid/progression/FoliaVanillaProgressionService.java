package net.sabafly.dragonraid.progression;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.progression.VanillaClearHookSpec;
import net.sabafly.dragonraid.api.progression.VanillaProgressionService;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderDragon;
import org.jetbrains.annotations.Nullable;

/**
 * Folia-safe {@link VanillaProgressionService} dispatching all {@link DragonBattle} and
 * gateway operations onto the owning region/entity schedulers.
 */
public final class FoliaVanillaProgressionService implements VanillaProgressionService {

    private final ConfigManager configManager;
    private final RaidScheduler scheduler;
    private final Function<String, @Nullable World> worldLookup;
    private final Logger logger;
    private final Set<PendingOperation<?>> pendingOperations = ConcurrentHashMap.newKeySet();

    private static final class PendingOperation<T> {
        private final Object lock = new Object();
        private final CompletableFuture<T> future;
        private @Nullable TaskHandle handle;
        private @Nullable PendingOperation<?> child;
        private boolean cancelled;
        private boolean started;

        private PendingOperation(CompletableFuture<T> future) {
            this.future = future;
        }

        private CompletableFuture<T> future() {
            return future;
        }

        private void attachHandle(@Nullable TaskHandle taskHandle) {
            boolean cancelNow;
            synchronized (lock) {
                this.handle = taskHandle;
                cancelNow = cancelled && taskHandle != null && !taskHandle.isCancelled();
            }
            if (cancelNow) {
                try {
                    taskHandle.cancel();
                } catch (RuntimeException ignored) {
                    // Best-effort cancellation when shutdown raced with handle attachment.
                }
            }
        }

        private boolean tryBeginExecution() {
            synchronized (lock) {
                if (cancelled || future.isDone()) {
                    return false;
                }
                started = true;
                return true;
            }
        }

        private boolean attachChild(PendingOperation<?> childOp) {
            synchronized (lock) {
                if (cancelled || future.isDone()) {
                    return false;
                }
                this.child = childOp;
                return true;
            }
        }

        private boolean isCancelled() {
            synchronized (lock) {
                return cancelled;
            }
        }

        private void cancel(CancellationException cause) {
            @Nullable TaskHandle currentHandle;
            @Nullable PendingOperation<?> currentChild;
            synchronized (lock) {
                if (cancelled) {
                    future.completeExceptionally(cause);
                    return;
                }
                cancelled = true;
                currentHandle = this.handle;
                currentChild = this.child;
            }
            if (currentHandle != null && !currentHandle.isCancelled()) {
                try {
                    currentHandle.cancel();
                } catch (RuntimeException ignored) {
                    // Best-effort cancellation during shutdown.
                }
            }
            if (currentChild != null) {
                currentChild.cancel(cause);
            }
            future.completeExceptionally(cause);
        }
    }

    public FoliaVanillaProgressionService(
            ConfigManager configManager,
            RaidScheduler scheduler,
            Logger logger) {
        this(configManager, scheduler, Bukkit::getWorld, logger);
    }

    public FoliaVanillaProgressionService(
            ConfigManager configManager,
            RaidScheduler scheduler,
            Function<String, @Nullable World> worldLookup,
            Logger logger) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.worldLookup = Objects.requireNonNull(worldLookup, "worldLookup");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Cancels all pending scheduled operations and fails their unresolved futures so shutdown
     * never leaves callers blocked.
     */
    public void cancelPendingTasks() {
        CancellationException cancelled = new CancellationException("VanillaProgressionService shut down before execution");
        for (PendingOperation<?> pending : List.copyOf(pendingOperations)) {
            pendingOperations.remove(pending);
            pending.cancel(cancelled);
        }
    }

    @Override
    public CompletableFuture<Boolean> synchronizeDragonDefeated(
            @Nullable String endWorldName,
            boolean generateExitPortal,
            boolean markPreviouslyKilled,
            boolean removeLiveVanillaDragon) {
        World world = resolveEndWorld(endWorldName);
        if (world == null) {
            return CompletableFuture.completedFuture(false);
        }
        Location origin = new Location(world, 0.0, 64.0, 0.0);
        return dispatchRegion(origin, (future, pending) -> {
            try {
                DragonBattle battle = world.getEnderDragonBattle();
                if (battle == null) {
                    future.complete(false);
                    return;
                }
                if (generateExitPortal) {
                    battle.generateEndPortal(true);
                }
                if (markPreviouslyKilled && !battle.hasBeenPreviouslyKilled()) {
                    battle.setPreviouslyKilled(true);
                }
                if (removeLiveVanillaDragon) {
                    if (pending.isCancelled() || future.isDone()) {
                        return;
                    }
                    EnderDragon liveDragon = battle.getEnderDragon();
                    if (liveDragon != null) {
                        scheduleDragonRemoval(liveDragon, pending).whenComplete((ignored, err) -> {
                            if (err != null) {
                                future.completeExceptionally(err);
                            } else {
                                future.complete(true);
                            }
                        });
                        return;
                    }
                }
                future.complete(true);
            } catch (RuntimeException ex) {
                logger.log(Level.WARNING, "Failed to synchronize vanilla DragonBattle state", ex);
                future.completeExceptionally(ex);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> expandEndGateways(
            @Nullable String endWorldName,
            int count,
            int maxGateways) {
        if (count <= 0 || maxGateways <= 0) {
            return CompletableFuture.completedFuture(0);
        }
        World world = resolveEndWorld(endWorldName);
        if (world == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("End world unavailable for gateway expansion: " + endWorldName));
        }
        int effectiveMax = Math.min(maxGateways, VanillaClearHookSpec.MAX_VANILLA_GATEWAYS);
        Location origin = new Location(world, 0.0, 64.0, 0.0);
        return dispatchRegion(origin, (future, pending) -> {
            try {
                DragonBattle battle = world.getEnderDragonBattle();
                if (battle == null) {
                    future.completeExceptionally(
                            new IllegalStateException("DragonBattle unavailable in world: " + world.getName()));
                    return;
                }
                int spawned = 0;
                while (spawned < count && battle.getGatewayCount() < effectiveMax) {
                    if (!battle.spawnNewGateway()) {
                        break;
                    }
                    spawned++;
                }
                future.complete(spawned);
            } catch (RuntimeException ex) {
                logger.log(Level.WARNING, "Failed to expand vanilla End gateways", ex);
                future.completeExceptionally(ex);
            }
        });
    }

    @Override
    public CompletableFuture<GatewayExpansionResult> expandEndGatewaysToTarget(
            @Nullable String endWorldName,
            int targetCount,
            int maxGateways) {
        if (maxGateways <= 0 || targetCount <= 0) {
            return CompletableFuture.completedFuture(new GatewayExpansionResult(0, 0, Math.max(0, maxGateways)));
        }
        World world = resolveEndWorld(endWorldName);
        if (world == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("End world unavailable for gateway expansion: " + endWorldName));
        }
        int effectiveMax = Math.min(maxGateways, VanillaClearHookSpec.MAX_VANILLA_GATEWAYS);
        int effectiveTarget = Math.min(targetCount, effectiveMax);
        Location origin = new Location(world, 0.0, 64.0, 0.0);
        return dispatchRegion(origin, (future, pending) -> {
            try {
                DragonBattle battle = world.getEnderDragonBattle();
                if (battle == null) {
                    future.completeExceptionally(
                            new IllegalStateException("DragonBattle unavailable in world: " + world.getName()));
                    return;
                }
                int spawned = 0;
                while (battle.getGatewayCount() < effectiveTarget) {
                    if (!battle.spawnNewGateway()) {
                        break;
                    }
                    spawned++;
                }
                future.complete(new GatewayExpansionResult(spawned, battle.getGatewayCount(), effectiveMax));
            } catch (RuntimeException ex) {
                logger.log(Level.WARNING, "Failed to expand vanilla End gateways to target " + effectiveTarget, ex);
                future.completeExceptionally(ex);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> isVanillaDragonPreviouslyKilled(@Nullable String endWorldName) {
        World world = resolveEndWorld(endWorldName);
        if (world == null) {
            return CompletableFuture.completedFuture(false);
        }
        Location origin = new Location(world, 0.0, 64.0, 0.0);
        return dispatchRegion(origin, (future, pending) -> {
            try {
                DragonBattle battle = world.getEnderDragonBattle();
                future.complete(battle != null && battle.hasBeenPreviouslyKilled());
            } catch (RuntimeException ex) {
                future.completeExceptionally(ex);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> getEndGatewayCount(@Nullable String endWorldName) {
        World world = resolveEndWorld(endWorldName);
        if (world == null) {
            return CompletableFuture.completedFuture(0);
        }
        Location origin = new Location(world, 0.0, 64.0, 0.0);
        return dispatchRegion(origin, (future, pending) -> {
            try {
                DragonBattle battle = world.getEnderDragonBattle();
                future.complete(battle == null ? 0 : battle.getGatewayCount());
            } catch (RuntimeException ex) {
                future.completeExceptionally(ex);
            }
        });
    }

    @FunctionalInterface
    private interface RegionBody<T> {
        void run(CompletableFuture<T> future, PendingOperation<T> pending);
    }

    private <T> CompletableFuture<T> dispatchRegion(Location origin, RegionBody<T> body) {
        CompletableFuture<T> future = new CompletableFuture<>();
        PendingOperation<T> pending = new PendingOperation<>(future);
        pendingOperations.add(pending);
        future.whenComplete((res, err) -> pendingOperations.remove(pending));

        try {
            TaskHandle handle = scheduler.runAtRegion(origin, () -> {
                if (!pending.tryBeginExecution()) {
                    return;
                }
                try {
                    body.run(future, pending);
                } catch (Throwable ex) {
                    future.completeExceptionally(ex);
                }
            });
            pending.attachHandle(handle);
            if (handle == null) {
                pending.cancel(new CancellationException("Region task handle was null"));
            } else {
                handle.onCancel(() -> pending.cancel(
                        new CancellationException("Region task cancelled before execution")));
                if (handle.isCancelled() && !future.isDone()) {
                    pending.cancel(new CancellationException("Region task was cancelled before scheduling returned"));
                }
            }
        } catch (Throwable ex) {
            future.completeExceptionally(ex);
        }
        return future;
    }

    private CompletableFuture<Void> scheduleDragonRemoval(EnderDragon liveDragon, PendingOperation<?> parentPending) {
        CompletableFuture<Void> removeFuture = new CompletableFuture<>();
        PendingOperation<Void> pending = new PendingOperation<>(removeFuture);
        if (!parentPending.attachChild(pending)) {
            removeFuture.completeExceptionally(
                    new CancellationException("Parent progression task cancelled before dragon removal"));
            return removeFuture;
        }
        pendingOperations.add(pending);
        removeFuture.whenComplete((res, err) -> pendingOperations.remove(pending));

        try {
            TaskHandle handle = scheduler.runAtEntity(
                    liveDragon,
                    () -> {
                        if (!pending.tryBeginExecution()) {
                            return;
                        }
                        try {
                            liveDragon.remove();
                            removeFuture.complete(null);
                        } catch (Throwable ex) {
                            removeFuture.completeExceptionally(ex);
                        }
                    },
                    // Entity retired before execution -> vanilla dragon is already gone.
                    () -> removeFuture.complete(null)
            );
            pending.attachHandle(handle);
            if (handle == null || handle == TaskHandle.retired()) {
                // Already retired prior to scheduling -> dragon is gone.
                removeFuture.complete(null);
            } else {
                handle.onCancel(() -> pending.cancel(
                        new CancellationException("Entity removal task cancelled before execution")));
                if (handle.isCancelled() && !removeFuture.isDone()) {
                    pending.cancel(new CancellationException("Entity removal task was cancelled"));
                }
            }
        } catch (Throwable ex) {
            removeFuture.completeExceptionally(ex);
        }
        return removeFuture;
    }

    private @Nullable World resolveEndWorld(@Nullable String endWorldName) {
        String target = (endWorldName == null || endWorldName.isBlank())
                ? configManager.pluginConfig().world().endWorldName()
                : endWorldName.trim();
        return worldLookup.apply(target);
    }
}
