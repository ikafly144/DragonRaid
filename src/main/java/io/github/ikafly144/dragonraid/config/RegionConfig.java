package io.github.ikafly144.dragonraid.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

/**
 * Immutable region definitions for the raid arena, safe waiting area, and void threshold.
 */
@ConfigSerializable
public record RegionConfig(
        @Setting("arena") ArenaRegion arena,
        @Setting("safe-zone") SafeZoneRegion safeZone,
        @Setting("void-y-threshold") double voidYThreshold
) {

    /**
     * Supported 3D region shapes.
     */
    public enum Shape {
        CYLINDER,
        CUBOID
    }

    /**
     * 3D coordinate point.
     */
    @ConfigSerializable
    public record Point3D(
            @Setting("x") double x,
            @Setting("y") double y,
            @Setting("z") double z
    ) {
    }

    /**
     * Spawn point with orientation.
     */
    @ConfigSerializable
    public record SpawnPoint(
            @Setting("x") double x,
            @Setting("y") double y,
            @Setting("z") double z,
            @Setting("yaw") float yaw,
            @Setting("pitch") float pitch
    ) {
    }

    /**
     * Main combat arena boundary. Supports CYLINDER (center + radius + minY/maxY) or CUBOID (min..max).
     */
    @ConfigSerializable
    public record ArenaRegion(
            @Setting("shape") Shape shape,
            @Setting("center") Point3D center,
            @Setting("radius") double radius,
            @Setting("min") Point3D min,
            @Setting("max") Point3D max,
            @Setting("min-y") int minY,
            @Setting("max-y") int maxY
    ) {
        public boolean contains(double x, double y, double z) {
            return RegionConfig.containsGeometry(shape, center, radius, min, max, minY, maxY, x, y, z);
        }
    }

    /**
     * Safe waiting area (obsidian platform) where pre-pull players and late joiners stay.
     */
    @ConfigSerializable
    public record SafeZoneRegion(
            @Setting("shape") Shape shape,
            @Setting("center") Point3D center,
            @Setting("radius") double radius,
            @Setting("min") Point3D min,
            @Setting("max") Point3D max,
            @Setting("min-y") int minY,
            @Setting("max-y") int maxY,
            @Setting("spawn-point") SpawnPoint spawnPoint
    ) {
        public boolean contains(double x, double y, double z) {
            return RegionConfig.containsGeometry(shape, center, radius, min, max, minY, maxY, x, y, z);
        }
    }

    private static boolean containsGeometry(
            Shape shape,
            Point3D center,
            double radius,
            Point3D min,
            Point3D max,
            int minY,
            int maxY,
            double x,
            double y,
            double z) {
        if (shape == Shape.CYLINDER) {
            if (y < minY || y > maxY) {
                return false;
            }
            double dx = x - center.x();
            double dz = z - center.z();
            return (dx * dx + dz * dz) <= (radius * radius);
        }
        return x >= min.x() && x <= max.x()
                && y >= min.y() && y <= max.y()
                && z >= min.z() && z <= max.z();
    }
}
