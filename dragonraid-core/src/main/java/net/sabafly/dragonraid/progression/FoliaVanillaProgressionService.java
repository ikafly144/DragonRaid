package net.sabafly.dragonraid.progression;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.progression.VanillaClearHookSpec;
import net.sabafly.dragonraid.api.progression.VanillaProgressionService;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
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
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Location origin = new Location(world, 0.0, 64.0, 0.0);
        scheduler.runAtRegion(origin, () -> {
            try {
                DragonBattle battle = world.getEnderDragonBattle();
                if (battle == null) {
                    future.complete(false);
                    return;
                }
                if (removeLiveVanillaDragon) {
                    EnderDragon liveDragon = battle.getEnderDragon();
                    if (liveDragon != null) {
                        scheduler.runAtEntity(liveDragon, liveDragon::remove);
                    }
                }
                if (generateExitPortal) {
                    battle.generateEndPortal(true);
                }
                if (markPreviouslyKilled && !battle.hasBeenPreviouslyKilled()) {
                    battle.setPreviouslyKilled(true);
                }
                future.complete(true);
            } catch (RuntimeException ex) {
                logger.log(Level.WARNING, "Failed to synchronize vanilla DragonBattle state", ex);
                future.completeExceptionally(ex);
            }
        });
        return future;
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
        CompletableFuture<Integer> future = new CompletableFuture<>();
        Location origin = new Location(world, 0.0, 64.0, 0.0);
        scheduler.runAtRegion(origin, () -> {
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
        return future;
    }

    @Override
    public CompletableFuture<Boolean> isVanillaDragonPreviouslyKilled(@Nullable String endWorldName) {
        World world = resolveEndWorld(endWorldName);
        if (world == null) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        scheduler.runAtRegion(new Location(world, 0.0, 64.0, 0.0), () -> {
            try {
                DragonBattle battle = world.getEnderDragonBattle();
                future.complete(battle != null && battle.hasBeenPreviouslyKilled());
            } catch (RuntimeException ex) {
                future.completeExceptionally(ex);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<Integer> getEndGatewayCount(@Nullable String endWorldName) {
        World world = resolveEndWorld(endWorldName);
        if (world == null) {
            return CompletableFuture.completedFuture(0);
        }
        CompletableFuture<Integer> future = new CompletableFuture<>();
        scheduler.runAtRegion(new Location(world, 0.0, 64.0, 0.0), () -> {
            try {
                DragonBattle battle = world.getEnderDragonBattle();
                future.complete(battle == null ? 0 : battle.getGatewayCount());
            } catch (RuntimeException ex) {
                future.completeExceptionally(ex);
            }
        });
        return future;
    }

    private @Nullable World resolveEndWorld(@Nullable String endWorldName) {
        String target = (endWorldName == null || endWorldName.isBlank())
                ? configManager.pluginConfig().world().endWorldName()
                : endWorldName.trim();
        return worldLookup.apply(target);
    }
}
