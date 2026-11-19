package net.sabafly.dragonraid.progression;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
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
        private final CompletableFuture<T> future;
        private volatile @Nullable TaskHandle handle;

        private PendingOperation(CompletableFuture<T> future) {
            this.future = future;
        }

        private CompletableFuture<T> future() {
            return future;
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
            TaskHandle handle = pending.handle;
            if (handle != null && !handle.isCancelled()) {
                try {
                    handle.cancel();
                } catch (RuntimeException ignored) {
                    // Best-effort cancellation during shutdown.
                }
            }
            pending.future().completeExceptionally(cancelled);
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
        return dispatchRegion(origin, future -> {
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
                    EnderDragon liveDragon = battle.getEnderDragon();
                    if (liveDragon != null) {
                        scheduleDragonRemoval(liveDragon).whenComplete((ignored, err) -> {
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
            return CompletableFuture.completedFuture(0);
        }
        int effectiveMax = Math.min(maxGateways, VanillaClearHookSpec.MAX_VANILLA_GATEWAYS);
        Location origin = new Location(world, 0.0, 64.0, 0.0);
        return dispatchRegion(origin, future -> {
            try {
                DragonBattle battle = world.getEnderDragonBattle();
                if (battle == null) {
                    future.complete(0);
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
    public CompletableFuture<Boolean> isVanillaDragonPreviouslyKilled(@Nullable String endWorldName) {
        World world = resolveEndWorld(endWorldName);
        if (world == null) {
            return CompletableFuture.completedFuture(false);
        }
        Location origin = new Location(world, 0.0, 64.0, 0.0);
        return dispatchRegion(origin, future -> {
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
        return dispatchRegion(origin, future -> {
            try {
                DragonBattle battle = world.getEnderDragonBattle();
                future.complete(battle == null ? 0 : battle.getGatewayCount());
            } catch (RuntimeException ex) {
                future.completeExceptionally(ex);
            }
        });
    }

    private <T> CompletableFuture<T> dispatchRegion(Location origin, Consumer<CompletableFuture<T>> body) {
        CompletableFuture<T> future = new CompletableFuture<>();
        PendingOperation<T> pending = new PendingOperation<>(future);
        pendingOperations.add(pending);
        future.whenComplete((res, err) -> pendingOperations.remove(pending));

        try {
            TaskHandle handle = scheduler.runAtRegion(origin, () -> {
                try {
                    body.accept(future);
                } catch (Throwable ex) {
                    future.completeExceptionally(ex);
                }
            });
            pending.handle = handle;
            if (handle == null) {
                if (!future.isDone()) {
                    future.completeExceptionally(new CancellationException("Region task handle was null"));
                }
            } else {
                handle.onCancel(() -> future.completeExceptionally(
                        new CancellationException("Region task cancelled before execution")));
                if (handle.isCancelled() && !future.isDone()) {
                    future.completeExceptionally(
                            new CancellationException("Region task was cancelled before scheduling returned"));
                }
            }
        } catch (Throwable ex) {
            future.completeExceptionally(ex);
        }
        return future;
    }

    private CompletableFuture<Void> scheduleDragonRemoval(EnderDragon liveDragon) {
        CompletableFuture<Void> removeFuture = new CompletableFuture<>();
        PendingOperation<Void> pending = new PendingOperation<>(removeFuture);
        pendingOperations.add(pending);
        removeFuture.whenComplete((res, err) -> pendingOperations.remove(pending));

        try {
            TaskHandle handle = scheduler.runAtEntity(
                    liveDragon,
                    () -> {
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
            pending.handle = handle;
            if (handle == null || handle == TaskHandle.retired()) {
                // Already retired prior to scheduling -> dragon is gone.
                removeFuture.complete(null);
            } else {
                handle.onCancel(() -> removeFuture.completeExceptionally(
                        new CancellationException("Entity removal task cancelled before execution")));
                if (handle.isCancelled() && !removeFuture.isDone()) {
                    // Handle could be a custom cancelled handle; complete cleanly if retired, or fail if aborted.
                    removeFuture.completeExceptionally(
                            new CancellationException("Entity removal task was cancelled"));
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
