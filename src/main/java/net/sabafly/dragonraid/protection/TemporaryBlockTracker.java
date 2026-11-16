package net.sabafly.dragonraid.protection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;

/**
 * Thread-safe lightweight tracker for temporary blocks and liquids placed during a raid encounter,
 * supporting per-player and global caps plus Folia {@code RegionScheduler}-dispatched rollback.
 */
public final class TemporaryBlockTracker {

    /**
     * Result of attempting to track a newly placed temporary block or fluid.
     */
    public enum TrackOutcome {
        RECORDED,
        ALREADY_TRACKED,
        PLAYER_LIMIT_REACHED,
        TOTAL_LIMIT_REACHED
    }

    public record TrackedBlock(BlockPosKey key, @Nullable UUID ownerId, BlockData originalData) {
    }

    private final Object lock = new Object();
    private final Map<BlockPosKey, TrackedBlock> entries = new LinkedHashMap<>();
    private final Map<UUID, Integer> playerCounts = new HashMap<>();

    /**
     * Records a player-initiated placement (block or bucket fluid) enforcing both per-player and total limits.
     */
    public TrackOutcome tryRecordPlayerPlacement(
            Location location,
            UUID playerId,
            BlockData originalData,
            int maxPerPlayer,
            int maxTotal) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(originalData, "originalData");
        return tryRecordInternal(BlockPosKey.of(location), playerId, originalData, maxPerPlayer, maxTotal);
    }

    /**
     * Records a secondary/system block or fluid spread (e.g. water flow, cobblestone formation)
     * enforcing only the total cap.
     */
    public TrackOutcome tryRecordSecondary(
            Location location,
            BlockData originalData,
            int maxTotal) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(originalData, "originalData");
        return tryRecordInternal(BlockPosKey.of(location), null, originalData, Integer.MAX_VALUE, maxTotal);
    }

    private TrackOutcome tryRecordInternal(
            BlockPosKey key,
            @Nullable UUID ownerId,
            BlockData originalData,
            int maxPerPlayer,
            int maxTotal) {
        synchronized (lock) {
            if (entries.containsKey(key)) {
                return TrackOutcome.ALREADY_TRACKED;
            }
            if (entries.size() >= maxTotal) {
                return TrackOutcome.TOTAL_LIMIT_REACHED;
            }
            if (ownerId != null) {
                int currentPlayerCount = playerCounts.getOrDefault(ownerId, 0);
                if (currentPlayerCount >= maxPerPlayer) {
                    return TrackOutcome.PLAYER_LIMIT_REACHED;
                }
                playerCounts.put(ownerId, currentPlayerCount + 1);
            }
            entries.put(key, new TrackedBlock(key, ownerId, originalData.clone()));
            return TrackOutcome.RECORDED;
        }
    }

    /**
     * Returns {@code true} if the block at {@code location} is currently tracked as temporary.
     */
    public boolean isTracked(@Nullable Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        BlockPosKey key = BlockPosKey.of(location);
        synchronized (lock) {
            return entries.containsKey(key);
        }
    }

    /**
     * Removes tracking for {@code location} when a temporary block is broken or collected.
     */
    public boolean untrack(@Nullable Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        BlockPosKey key = BlockPosKey.of(location);
        synchronized (lock) {
            TrackedBlock removed = entries.remove(key);
            if (removed == null) {
                return false;
            }
            if (removed.ownerId() != null) {
                playerCounts.computeIfPresent(removed.ownerId(), (id, count) -> count > 1 ? count - 1 : null);
            }
            return true;
        }
    }

    /**
     * Returns the current number of tracked temporary blocks.
     */
    public int totalCount() {
        synchronized (lock) {
            return entries.size();
        }
    }

    /**
     * Returns the current number of tracked blocks attributed to {@code playerId}.
     */
    public int playerCount(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (lock) {
            return playerCounts.getOrDefault(playerId, 0);
        }
    }

    /**
     * Clears all tracked entries without modifying the world.
     */
    public void clear() {
        synchronized (lock) {
            entries.clear();
            playerCounts.clear();
        }
    }

    /**
     * Atomically snapshots and clears all tracked blocks, then restores every coordinate
     * to its original {@link BlockData} on the owning Folia region thread grouped by chunk.
     *
     * @return future completing with the number of restored blocks
     */
    public CompletableFuture<Integer> rollbackAll(RaidScheduler scheduler, @Nullable World world) {
        Objects.requireNonNull(scheduler, "scheduler");
        List<TrackedBlock> snapshot;
        synchronized (lock) {
            if (entries.isEmpty()) {
                return CompletableFuture.completedFuture(0);
            }
            snapshot = new ArrayList<>(entries.values());
            entries.clear();
            playerCounts.clear();
        }

        if (world == null) {
            return CompletableFuture.completedFuture(0);
        }

        UUID targetWorldId = world.getUID();
        Map<Long, List<TrackedBlock>> byChunk = new LinkedHashMap<>();
        for (TrackedBlock tracked : snapshot) {
            if (!tracked.key().worldId().equals(targetWorldId)) {
                continue;
            }
            byChunk.computeIfAbsent(tracked.key().chunkKey(), ignored -> new ArrayList<>()).add(tracked);
        }

        if (byChunk.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        AtomicInteger restored = new AtomicInteger(0);
        List<CompletableFuture<Void>> chunkFutures = new ArrayList<>(byChunk.size());
        for (List<TrackedBlock> chunkBlocks : byChunk.values()) {
            BlockPosKey first = chunkBlocks.getFirst().key();
            CompletableFuture<Void> chunkFuture = new CompletableFuture<>();
            chunkFutures.add(chunkFuture);
            scheduler.runAtRegion(world, first.chunkX(), first.chunkZ(), () -> {
                try {
                    for (TrackedBlock tracked : chunkBlocks) {
                        BlockPosKey key = tracked.key();
                        Block block = world.getBlockAt(key.x(), key.y(), key.z());
                        block.setBlockData(tracked.originalData(), false);
                        restored.incrementAndGet();
                    }
                    chunkFuture.complete(null);
                } catch (RuntimeException ex) {
                    chunkFuture.completeExceptionally(ex);
                }
            });
        }

        return CompletableFuture.allOf(chunkFutures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> restored.get());
    }
}
