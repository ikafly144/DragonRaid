package net.sabafly.dragonraid.region;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.RegionConfig;
import net.sabafly.dragonraid.config.RegionConfig.ArenaRegion;
import net.sabafly.dragonraid.config.RegionConfig.Point3D;
import net.sabafly.dragonraid.config.RegionConfig.Shape;
import net.sabafly.dragonraid.config.RegionConfig.SpawnPoint;
import net.sabafly.dragonraid.instance.TheNextLvlRaidInstanceManager;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.Nullable;

/**
 * Default {@link RaidRegionService} backed by {@link ConfigManager} and {@link RaidScheduler}.
 */
public final class DefaultRaidRegionService implements RaidRegionService {

    private static final int PROBE_STEPS = 6;
    private static final double INNER_EDGE_MARGIN = 4.0;
    private static final Set<Material> UNSAFE_SUPPORT_MATERIALS = EnumSet.of(
            Material.MAGMA_BLOCK,
            Material.CACTUS,
            Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE
    );

    private final ConfigManager configManager;
    private final RaidScheduler scheduler;

    public DefaultRaidRegionService(ConfigManager configManager, RaidScheduler scheduler) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public boolean isRaidWorld(@Nullable World world) {
        if (world == null) {
            return false;
        }
        String name = world.getName();
        String configuredName = configManager.pluginConfig().world().endWorldName();
        return name.equals(configuredName) || TheNextLvlRaidInstanceManager.isManagedInstanceWorldName(name);
    }

    @Override
    public boolean isInArena(@Nullable Location location) {
        if (location == null || !isRaidWorld(location.getWorld())) {
            return false;
        }
        return configManager.pluginConfig().regions().arena()
                .contains(location.getX(), location.getY(), location.getZ());
    }

    @Override
    public boolean isInArenaHorizontal(@Nullable Location location) {
        if (location == null || !isRaidWorld(location.getWorld())) {
            return false;
        }
        return configManager.pluginConfig().regions().arena()
                .containsHorizontal(location.getX(), location.getZ());
    }

    @Override
    public boolean isInSafeZone(@Nullable Location location) {
        if (location == null || !isRaidWorld(location.getWorld())) {
            return false;
        }
        return configManager.pluginConfig().regions().safeZone()
                .contains(location.getX(), location.getY(), location.getZ());
    }

    @Override
    public boolean isBelowVoidThreshold(@Nullable Location location) {
        if (location == null || !isInArenaHorizontal(location)) {
            return false;
        }
        double threshold = configManager.pluginConfig().regions().voidYThreshold();
        return location.getY() < threshold;
    }

    @Override
    public Location safeZoneSpawnLocation(World endWorld) {
        Objects.requireNonNull(endWorld, "endWorld");
        if (TheNextLvlRaidInstanceManager.isManagedInstanceWorldName(endWorld.getName())) {
            return new Location(endWorld, 0.5, 65.0, 24.5, 180.0f, 0.0f);
        }
        SpawnPoint sp = configManager.pluginConfig().regions().safeZone().spawnPoint();
        return new Location(endWorld, sp.x(), sp.y(), sp.z(), sp.yaw(), sp.pitch());
    }

    @Override
    public Location clampToArenaAnchor(Location from, double yOffset) {
        Objects.requireNonNull(from, "from");
        World world = Objects.requireNonNull(from.getWorld(), "from.getWorld()");
        RegionConfig regions = configManager.pluginConfig().regions();
        ArenaRegion arena = regions.arena();
        Point3D clampedXZ = clampXZInsideArena(arena, from.getX(), from.getZ());
        double minBoundY = arena.shape() == Shape.CYLINDER ? arena.minY() : arena.min().y();
        double maxBoundY = arena.shape() == Shape.CYLINDER ? arena.maxY() : arena.max().y();
        double minSafeY = Math.max(regions.voidYThreshold() + 1.0, minBoundY);
        double maxSafeY = Math.max(minSafeY, maxBoundY - 2.0);
        double targetY = arena.center().y() + Math.max(0.0, yOffset);
        double finalY = Math.max(minSafeY, Math.min(maxSafeY, targetY));
        return new Location(world, clampedXZ.x(), finalY, clampedXZ.z(), from.getYaw(), from.getPitch());
    }

    @Override
    public CompletableFuture<Location> findNearestSafeArenaEdgeAsync(Location from, double yOffset) {
        Objects.requireNonNull(from, "from");
        World world = from.getWorld();
        if (world == null) {
            return CompletableFuture.completedFuture(from);
        }
        RegionConfig regions = configManager.pluginConfig().regions();
        List<Location> probes = buildInwardProbes(world, regions, from);
        return probeSequentially(probes, 0, regions, yOffset, from.getYaw(), from.getPitch(), world);
    }

    private CompletableFuture<Location> probeSequentially(
            List<Location> probes,
            int index,
            RegionConfig regions,
            double yOffset,
            float yaw,
            float pitch,
            World world) {
        if (index >= probes.size()) {
            return CompletableFuture.completedFuture(safeZoneSpawnLocation(world));
        }
        Location probe = probes.get(index);
        CompletableFuture<@Nullable Location> stepFuture = new CompletableFuture<>();
        scheduler.runAtRegion(probe, () -> {
            try {
                Location found = findSafeStandingColumn(world, probe.getBlockX(), probe.getBlockZ(), regions, yOffset, yaw, pitch);
                stepFuture.complete(found);
            } catch (RuntimeException ex) {
                stepFuture.completeExceptionally(ex);
            }
        });
        return stepFuture.thenCompose(found -> {
            if (found != null) {
                return CompletableFuture.completedFuture(found);
            }
            return probeSequentially(probes, index + 1, regions, yOffset, yaw, pitch, world);
        });
    }

    static List<Location> buildInwardProbes(World world, RegionConfig regions, Location from) {
        ArenaRegion arena = regions.arena();
        Point3D start = clampXZInsideArena(arena, from.getX(), from.getZ());
        Point3D center = arena.center();
        List<Location> probes = new ArrayList<>(PROBE_STEPS + 1);
        double probeY = Math.max(regions.voidYThreshold() + 4.0, center.y());
        for (int i = 0; i <= PROBE_STEPS; i++) {
            double t = (double) i / PROBE_STEPS;
            double x = start.x() + (center.x() - start.x()) * t;
            double z = start.z() + (center.z() - start.z()) * t;
            probes.add(new Location(world, x, probeY, z));
        }
        return probes;
    }

    private static @Nullable Location findSafeStandingColumn(
            World world,
            int blockX,
            int blockZ,
            RegionConfig regions,
            double yOffset,
            float yaw,
            float pitch) {
        ArenaRegion arena = regions.arena();
        if (!arena.containsHorizontal(blockX + 0.5, blockZ + 0.5)) {
            return null;
        }
        int minY = arena.shape() == Shape.CYLINDER ? arena.minY() : (int) Math.floor(arena.min().y());
        int maxY = arena.shape() == Shape.CYLINDER ? arena.maxY() : (int) Math.ceil(arena.max().y());
        int voidFloor = (int) Math.ceil(regions.voidYThreshold());
        int scanBottom = Math.max(world.getMinHeight(), Math.max(minY, voidFloor));
        int scanTop = Math.min(world.getMaxHeight() - 3, Math.min(maxY - 2, (int) Math.ceil(arena.center().y()) + 40));

        double effectiveOffset = Math.max(0.0, yOffset);
        int ceilingLimit = Math.min(world.getMaxHeight() - 1, maxY);
        for (int y = scanTop; y >= scanBottom; y--) {
            Block ground = world.getBlockAt(blockX, y, blockZ);
            if (!isSafeSupportBlock(ground)) {
                continue;
            }
            double safeY = y + 1.0 + effectiveOffset;
            int landingFeetY = (int) Math.floor(safeY);
            int landingHeadY = landingFeetY + 1;
            if (landingHeadY > ceilingLimit) {
                continue;
            }
            if (hasVerticalClearance(world, blockX, blockZ, y + 1, landingHeadY)) {
                return new Location(world, blockX + 0.5, safeY, blockZ + 0.5, yaw, pitch);
            }
        }
        return null;
    }

    private static boolean hasVerticalClearance(World world, int blockX, int blockZ, int fromY, int toY) {
        for (int checkY = fromY; checkY <= toY; checkY++) {
            Block space = world.getBlockAt(blockX, checkY, blockZ);
            if (!space.isPassable() || space.isLiquid()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSafeSupportBlock(Block block) {
        return block.isSolid() && !UNSAFE_SUPPORT_MATERIALS.contains(block.getType());
    }

    private static Point3D clampXZInsideArena(ArenaRegion arena, double x, double z) {
        if (arena.shape() == Shape.CYLINDER) {
            double dx = x - arena.center().x();
            double dz = z - arena.center().z();
            double distSq = dx * dx + dz * dz;
            double maxR = Math.max(1.0, arena.radius() - INNER_EDGE_MARGIN);
            if (distSq <= maxR * maxR || distSq < 1e-9) {
                return new Point3D(x, arena.center().y(), z);
            }
            double dist = Math.sqrt(distSq);
            double scale = maxR / dist;
            return new Point3D(
                    arena.center().x() + dx * scale,
                    arena.center().y(),
                    arena.center().z() + dz * scale
            );
        }
        double minX = arena.min().x() + INNER_EDGE_MARGIN;
        double maxX = arena.max().x() - INNER_EDGE_MARGIN;
        double minZ = arena.min().z() + INNER_EDGE_MARGIN;
        double maxZ = arena.max().z() - INNER_EDGE_MARGIN;
        double clampedX = minX <= maxX ? Math.max(minX, Math.min(maxX, x)) : arena.center().x();
        double clampedZ = minZ <= maxZ ? Math.max(minZ, Math.min(maxZ, z)) : arena.center().z();
        return new Point3D(clampedX, arena.center().y(), clampedZ);
    }
}
