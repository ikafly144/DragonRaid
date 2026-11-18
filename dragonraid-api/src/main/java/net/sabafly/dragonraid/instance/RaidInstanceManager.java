package net.sabafly.dragonraid.instance;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

/**
 * Manages temporary combat instance worlds backed by TheNextLvl Worlds and builds the flat
 * circular raid arena with surrounding low obsidian pillars.
 */
public interface RaidInstanceManager {

    /**
     * Metadata for an active temporary raid instance world.
     */
    record RaidInstance(
            World world,
            Location arenaCenter,
            double arenaRadius,
            List<Location> pillarCrystalLocations
    ) {
    }

    /**
     * Returns the currently active temporary raid instance, or {@code null} if none is loaded.
     */
    @Nullable RaidInstance activeInstance();

    /**
     * Returns the active instance world if present, or falls back to the configured main End world.
     */
    @Nullable World activeCombatWorld(@Nullable World fallbackEndWorld);

    /**
     * Returns {@code true} if {@code world} is a DragonRaid-managed temporary raid instance world.
     */
    boolean isInstanceWorld(@Nullable World world);

    /**
     * Ensures a temporary Void {@code THE_END} instance world is created and its flat circular
     * arena + 8 low obsidian pillars are constructed, reusing the active instance if one is already
     * prepared (e.g. for fast retry after a wipe).
     */
    CompletableFuture<RaidInstance> getOrCreateInstance();

    /**
     * Evacuates players to {@code returnLocation} and deletes only {@code targetInstance}
     * (without disturbing any newer active instance created afterwards).
     */
    default CompletableFuture<Void> destroyInstance(
            @Nullable RaidInstance targetInstance,
            @Nullable Location returnLocation) {
        return destroyActiveInstance(returnLocation);
    }

    /**
     * Destroys and deletes the active temporary raid instance world after evacuating any remaining
     * players to {@code returnLocation}.
     */
    CompletableFuture<Void> destroyActiveInstance(@Nullable Location returnLocation);

    /**
     * Cleans up any leftover temporary raid instance worlds on startup or shutdown.
     */
    void cleanupOrphanedInstances();
}
