package net.sabafly.dragonraid.region;

import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

/**
 * Region boundary and coordinate-resolution service separating the DragonRaid arena
 * and safe waiting area from surrounding End structures.
 */
public interface RaidRegionService {

    /**
     * Returns {@code true} if {@code world} matches the configured End raid world.
     */
    boolean isRaidWorld(@Nullable World world);

    /**
     * Returns {@code true} if {@code location} is inside the configured raid world and 3D arena region.
     */
    boolean isInArena(@Nullable Location location);

    /**
     * Returns {@code true} if {@code location} is inside the configured raid world and within the
     * horizontal (X/Z) footprint of the arena region.
     */
    boolean isInArenaHorizontal(@Nullable Location location);

    /**
     * Returns {@code true} if {@code location} is inside the configured raid world and safe waiting area.
     */
    boolean isInSafeZone(@Nullable Location location);

    /**
     * Returns {@code true} if {@code location} is inside the raid world's arena horizontal footprint
     * and below {@code regions.void-y-threshold}.
     */
    boolean isBelowVoidThreshold(@Nullable Location location);

    /**
     * Builds a Bukkit {@link Location} at the configured safe-zone spawn point in {@code endWorld}.
     */
    Location safeZoneSpawnLocation(World endWorld);

    /**
     * Computes a deterministic geometric anchor inside the arena near {@code from} without reading world blocks.
     */
    Location clampToArenaAnchor(Location from, double yOffset);

    /**
     * Finds the nearest safe solid landing location along an inward ray toward the arena center
     * using Folia region-thread scheduling, falling back to {@link #safeZoneSpawnLocation(World)}.
     */
    CompletableFuture<Location> findNearestSafeArenaEdgeAsync(Location from, double yOffset);
}
