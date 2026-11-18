package net.sabafly.dragonraid.instance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Folia-safe builder that constructs a flat circular raid arena and evenly spaced low obsidian
 * pillars for End Crystals inside a temporary Void instance world.
 */
public final class CircularArenaBuilder {

    public static final double DEFAULT_ARENA_RADIUS = 40.0;
    public static final double DEFAULT_PILLAR_RING_RADIUS = 31.0;
    public static final int DEFAULT_PILLAR_COUNT = 8;
    public static final int DEFAULT_PILLAR_HEIGHT = 8;
    public static final int DEFAULT_FLOOR_Y = 64;
    public static final int DEFAULT_SURFACE_Y = DEFAULT_FLOOR_Y + 1;

    private final RaidScheduler scheduler;
    private final double arenaRadius;
    private final double pillarRingRadius;
    private final int pillarCount;
    private final int pillarHeight;
    private final int floorY;

    public CircularArenaBuilder(RaidScheduler scheduler) {
        this(
                scheduler,
                DEFAULT_ARENA_RADIUS,
                DEFAULT_PILLAR_RING_RADIUS,
                DEFAULT_PILLAR_COUNT,
                DEFAULT_PILLAR_HEIGHT,
                DEFAULT_FLOOR_Y
        );
    }

    public CircularArenaBuilder(
            RaidScheduler scheduler,
            double arenaRadius,
            double pillarRingRadius,
            int pillarCount,
            int pillarHeight,
            int floorY) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.arenaRadius = Math.max(16.0, arenaRadius);
        this.pillarRingRadius = Math.max(10.0, Math.min(this.arenaRadius - 4.0, pillarRingRadius));
        this.pillarCount = Math.max(1, pillarCount);
        this.pillarHeight = Math.max(3, pillarHeight);
        this.floorY = floorY;
    }

    /**
     * Computes the crystal spawn locations on top of the evenly spaced obsidian pillars around {@code (0, floorY, 0)}.
     */
    public List<Location> computePillarCrystalLocations(World world) {
        Objects.requireNonNull(world, "world");
        List<Location> locations = new ArrayList<>(pillarCount);
        for (int i = 0; i < pillarCount; i++) {
            double angle = (2.0 * Math.PI * i) / pillarCount;
            int px = (int) Math.round(Math.cos(angle) * pillarRingRadius);
            int pz = (int) Math.round(Math.sin(angle) * pillarRingRadius);
            int topY = floorY + pillarHeight;
            locations.add(new Location(world, px + 0.5, topY + 1.0, pz + 0.5));
        }
        return List.copyOf(locations);
    }

    /**
     * Builds the circular floor and 8 low obsidian pillars across Folia chunk regions.
     */
    public CompletableFuture<List<Location>> buildArena(World world) {
        Objects.requireNonNull(world, "world");
        List<BlockPlacement> placements = generatePlacements();
        Map<ChunkCoord, List<BlockPlacement>> byChunk = new HashMap<>();
        for (BlockPlacement p : placements) {
            ChunkCoord chunk = new ChunkCoord(p.x >> 4, p.z >> 4);
            byChunk.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(p);
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>(byChunk.size());
        for (Map.Entry<ChunkCoord, List<BlockPlacement>> entry : byChunk.entrySet()) {
            ChunkCoord chunk = entry.getKey();
            List<BlockPlacement> chunkPlacements = entry.getValue();
            CompletableFuture<Void> chunkDone = new CompletableFuture<>();
            TaskHandle handle = scheduler.runAtRegion(world, chunk.cx, chunk.cz, () -> {
                try {
                    for (BlockPlacement bp : chunkPlacements) {
                        Block block = world.getBlockAt(bp.x, bp.y, bp.z);
                        if (block != null) {
                            block.setType(bp.material, false);
                        }
                    }
                    chunkDone.complete(null);
                } catch (RuntimeException ex) {
                    chunkDone.completeExceptionally(ex);
                }
            });
            if (handle == null) {
                chunkDone.completeExceptionally(new IllegalStateException(
                        "Failed to schedule arena build for chunk (" + chunk.cx + ", " + chunk.cz + ")"));
            }
            futures.add(chunkDone);
        }

        List<Location> crystalLocations = computePillarCrystalLocations(world);
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> crystalLocations);
    }

    List<BlockPlacement> generatePlacements() {
        List<BlockPlacement> list = new ArrayList<>();
        int rCeil = (int) Math.ceil(arenaRadius);
        double radiusSq = arenaRadius * arenaRadius;
        double outerRimSq = (arenaRadius - 1.5) * (arenaRadius - 1.5);
        double midRingInnerSq = (arenaRadius * 0.5 - 0.8) * (arenaRadius * 0.5 - 0.8);
        double midRingOuterSq = (arenaRadius * 0.5 + 0.8) * (arenaRadius * 0.5 + 0.8);

        for (int x = -rCeil; x <= rCeil; x++) {
            for (int z = -rCeil; z <= rCeil; z++) {
                double distSq = (double) x * x + (double) z * z;
                if (distSq > radiusSq) {
                    continue;
                }
                // Sub-floor bedrock support
                list.add(new BlockPlacement(x, floorY - 1, z, Material.BEDROCK));

                Material surface;
                if (distSq <= 9.0) {
                    surface = (Math.abs(x) == Math.abs(z) || x == 0 || z == 0)
                            ? Material.CRYING_OBSIDIAN
                            : Material.OBSIDIAN;
                } else if (distSq >= outerRimSq) {
                    surface = Material.OBSIDIAN;
                } else if (distSq >= midRingInnerSq && distSq <= midRingOuterSq) {
                    surface = Material.PURPUR_BLOCK;
                } else if ((x == 0 || z == 0) && ((Math.abs(x) + Math.abs(z)) % 4 == 0)) {
                    surface = Material.SEA_LANTERN;
                } else {
                    surface = Material.END_STONE_BRICKS;
                }
                list.add(new BlockPlacement(x, floorY, z, surface));
            }
        }

        // 8 low obsidian pillars (3x3 cross/cylinder with BEDROCK top for crystal placement)
        for (int i = 0; i < pillarCount; i++) {
            double angle = (2.0 * Math.PI * i) / pillarCount;
            int px = (int) Math.round(Math.cos(angle) * pillarRingRadius);
            int pz = (int) Math.round(Math.sin(angle) * pillarRingRadius);
            for (int y = floorY + 1; y <= floorY + pillarHeight; y++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        // Rounded 3x3 cross pillar (skip corners on upper shaft for sleek profile)
                        if (Math.abs(dx) == 1 && Math.abs(dz) == 1 && y > floorY + 2) {
                            continue;
                        }
                        list.add(new BlockPlacement(px + dx, y, pz + dz, Material.OBSIDIAN));
                    }
                }
            }
            list.add(new BlockPlacement(px, floorY + pillarHeight, pz, Material.BEDROCK));
        }
        return list;
    }

    record BlockPlacement(int x, int y, int z, Material material) {
    }

    private record ChunkCoord(int cx, int cz) {
    }
}
