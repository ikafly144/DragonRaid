package io.github.ikafly144.dragonraid.config;

/**
 * Immutable region definitions for the raid arena, safe waiting area, and void threshold.
 */
public record RegionConfig(
        ArenaRegion arena,
        SafeZoneRegion safeZone,
        double voidYThreshold
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
    public record Point3D(double x, double y, double z) {
    }

    /**
     * Spawn point with orientation.
     */
    public record SpawnPoint(double x, double y, double z, float yaw, float pitch) {
    }

    /**
     * Main combat arena boundary. Supports CYLINDER (center + radius + minY/maxY) or CUBOID (min..max).
     */
    public record ArenaRegion(
            Shape shape,
            Point3D center,
            double radius,
            Point3D min,
            Point3D max,
            int minY,
            int maxY
    ) {
        public boolean contains(double x, double y, double z) {
            if (shape == Shape.CYLINDER) {
                if (y < minY || y > maxY) {
                    return false;
                }
                double dx = x - center.x();
                double dz = z - center.z();
                return (dx * dx + dz * dz) <= (radius * radius);
            }
            return containsCuboid(min, max, x, y, z);
        }
    }

    /**
     * Safe waiting area (obsidian platform) where pre-pull players and late joiners stay.
     */
    public record SafeZoneRegion(
            Shape shape,
            Point3D center,
            double radius,
            Point3D min,
            Point3D max,
            int minY,
            int maxY,
            SpawnPoint spawnPoint
    ) {
        public boolean contains(double x, double y, double z) {
            if (shape == Shape.CYLINDER) {
                if (y < minY || y > maxY) {
                    return false;
                }
                double dx = x - center.x();
                double dz = z - center.z();
                return (dx * dx + dz * dz) <= (radius * radius);
            }
            return containsCuboid(min, max, x, y, z);
        }
    }

    private static boolean containsCuboid(Point3D min, Point3D max, double x, double y, double z) {
        return x >= min.x() && x <= max.x()
                && y >= min.y() && y <= max.y()
                && z >= min.z() && z <= max.z();
    }
}
